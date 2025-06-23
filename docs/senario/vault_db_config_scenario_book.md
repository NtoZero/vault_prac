# 싱글 Vault + Spring 애플리케이션 DB 구성 시나리오북  
*HashiCorp Vault KV 시크릿 엔진 기반 Key‑Value(키‑밸류) 매니지먼트*

---

## 1. 개요
본 문서는 **단일 노드 Vault 서버**와 **Spring Boot** 애플리케이션 환경에서  
데이터베이스(DB) 접속 정보를 **키‑밸류(KV) 시크릿 엔진 v2**로 관리·주입하는 end‑to‑end 시나리오를 제공합니다.  
온프레미스(독립형) 배포를 가정하며, 높은 보안 수준과 운영 편의성을 모두 충족하도록 설계했습니다.

> **목표**
> 1. Vault 서버를 Docker Compose로 손쉽게 배포  
> 2. Vault 초기화·언실(Init/Unseal) 및 루트 토큰 관리 자동화  
> 3. DB 자격 증명(유저·패스워드)을 KV v2에 저장  
> 4. Spring Boot가 Vault에서 시크릿을 안전하게 읽어 HikariCP DataSource 구성  
> 5. 운영 시 고려사항 및 장애 대응 절차

---

## 2. 아키텍처 & 플로우

```mermaid
flowchart LR
    A[Developer<br>CI/CD] -->|docker compose up| V[Vault (1 node)]
    V --KV API--> S(Spring Boot 앱)
    S --JDBC--> DB[(MySQL/PostgreSQL)]
```

1. **Vault 컨테이너**가 `8200` 포트(HTTPS)로 기동  
2. `init.sh` 스크립트가 Vault를 *초기화*하고 5‑키 / 3‑키 *언실(Quorum)* 진행  
3. 동일 스크립트가 **DB 시크릿**(`secret/data/app/db`) 저장  
4. **Spring Boot**는 부트스트랩 단계에서 Vault Token을 사용해 시크릿을 조회  
5. 조회한 값으로 **HikariCP** 풀을 구성해 DB 연결

---

## 3. 사전 준비

| 항목 | 버전 예시 | 비고 |
|------|-----------|------|
| Docker Engine | 24.x | Linux/Windows 호스트 |
| docker‑compose | v2 plugin | compose.yaml v3.9 |
| Vault | 1.17.x | 공식 HashiCorp 이미지 |
| Spring Boot | 3.3.x | `spring-cloud-starter-vault-config` 사용 |
| DB | MySQL 8.4 / PostgreSQL 16 | 예시로 MySQL 사용 |

---

## 4. Docker Compose

`docker-compose.yml`

```yaml
version: "3.9"

services:
   vault:
      image: hashicorp/vault:1.17
      container_name: vault
      restart: unless-stopped
      ports:
         - "8200:8200"
      environment:
         # CLI 등에서 사용할 주소 정보는 그대로 둡니다.
         VAULT_ADDR: "http://127.0.0.1:8200"
         VAULT_API_ADDR: "http://127.0.0.1:8200"
      cap_add:
         - IPC_LOCK
      volumes:
         # 1. 설정(Config) 볼륨: 호스트의 ./volume/vault/config 디렉토리를 컨테이너의 /vault/config에 연결합니다.
         # 컨테이너가 설정을 임의로 변경하지 못하도록 읽기 전용(:ro)으로 설정하는 것이 안전합니다.
         - ./volume/vault/config:/vault/config:ro

         # 2. 데이터(Data) 볼륨: 호스트의 ./volume/vault/data 디렉토리를 컨테이너의 스토리지 경로인 /vault/file에 연결합니다.
         # 이곳에 실제 비밀 정보가 저장되므로 반드시 영속적으로 유지해야 합니다.
         - ./volume/vault/data:/vault/file

         # 3. 스크립트(Script) 볼륨: 호스트의 ./volume/scripts 디렉토리를 컨테이너의 /scripts에 읽기 전용으로 연결합니다.
         - ./volume/scripts:/scripts:ro

   mysql:
      image: mysql:8.4
      container_name: mysql
      restart: unless-stopped
      env_file:
         - ./env/mysql.env
      command: >
         --character-set-server=utf8mb4
         --collation-server=utf8mb4_unicode_ci
      ports:
         - "3306:3306"
      volumes:
         - ./volume/mysql/data:/var/lib/mysql
```

### 주요 주석
- **VAULT_LOCAL_CONFIG**: 하이엔드 환경에서는 `raft` 스토리지와 TLS 인증서 사용을 권장  
- **entrypoint**: 컨테이너 시작 시 `init.sh` 실행 → Vault 초기화·설정 후 데몬 유지  
- **cap_add: IPC_LOCK**: 메모리 스왑 방지 목적으로 mlock 권장 (demo에선 disable)  

---

## 5. Vault 초기화 & 시크릿 등록 스크립트

`scripts/init.sh`

```bash
#!/usr/bin/env bash
set -e

# ========= 설정 =========
SHARES=5            # 키 조각 수
THRESHOLD=3         # 복구(언실)에 필요한 키 수
DB_PATH="secret/app/db"  # 시크릿 경로(KV v2)
DB_USER="app_user"
DB_PASS="app_pass_ChangeMe!"

# 1) Vault 초기화(JSON 포맷) - 최초 1회
if [ ! -f /vault/file/init.json ]; then
  vault operator init     -key-shares=${SHARES}     -key-threshold=${THRESHOLD}     -format=json > /vault/file/init.json
fi

# 2) 언실 수행 (threshold 개수만큼)
for key in $(jq -r ".unseal_keys_b64[0:${THRESHOLD}][]" /vault/file/init.json); do
  vault operator unseal "$key"
done

# 3) 루트 토큰 로그인
ROOT_TOKEN=$(jq -r .root_token /vault/file/init.json)
export VAULT_TOKEN="$ROOT_TOKEN"

# 4) KV 시크릿 엔진(v2) 활성화(존재하지 않을 때만)
vault secrets enable -path=secret kv-v2 || true

# 5) DB 자격 증명 등록
vault kv put ${DB_PATH}   username="${DB_USER}"   password="${DB_PASS}"   url="jdbc:mysql://mysql:3306/demo?useSSL=false&characterEncoding=UTF-8"

echo "[INIT] Vault ready.  Root token stored at /vault/file/init.json"
```

> **보안 TIP**  
> ‑ 실제 운영에서는 init.json을 안전한 외부 키 관리 시스템(HSM, SSM Parameter Store 등)에 즉시 보관하고  
> ‑ `tls_disable=false`로 TLS를 활성화해야 합니다.

---

## 6. Spring Boot 설정

### 6‑1. 의존성
`build.gradle.kts`
```kotlin
implementation("org.springframework.boot:spring-boot-starter-data-jdbc")
implementation("org.springframework.cloud:spring-cloud-starter-vault-config")
```

### 6‑2. `bootstrap.yml`
```yaml
spring:
  application:
    name: demo
  cloud:
    vault:
      uri: http://localhost:8200        # 데모용 HTTP (TLS 시 https)
      token: ${VAULT_TOKEN}             # CI/CD가 주입 또는 k8s Secret로 마운트
      kv:
        enabled: true
        backend: secret
        application-name: app/db        # → secret/data/app/db
```

### 6‑3. `application.yml`
```yaml
spring:
  datasource:
    username: ${username}        # Vault에서 주입된 플레이스홀더
    password: ${password}
    url: ${url}
    driver-class-name: com.mysql.cj.jdbc.Driver
```

> `bootstrap.yml`은 **Spring Cloud Config 단계**에서 읽히므로  
> Vault에 접근해 시크릿을 먼저 가져온 뒤 `application.yml` 속성을 해석할 수 있습니다.

---

## 7. 실행 순서

1. ```bash
   docker-compose up -d
   ```
   Vault & MySQL 기동 → `init.sh` 자동 실행
2. **init.json** 확인 후 *루트 토큰*과 *언실 키* 안전보관
3. Spring Boot 애플리케이션 실행 → Vault로부터 DB 시크릿 주입 → DB 연결 성공
4. 이후 비밀번호 교체 시
   ```bash
   vault kv put secret/app/db password="새패스워드"
   ```
   애플리케이션 재로드(Actuator / roll‑restart)만으로 즉시 반영

---

## 8. 장애 대응 & 운영 고려

| 시나리오 | 대응 방법 |
|----------|-----------|
| Vault 재시작 시 `sealed` 상태 | `init.json`의 언실 키 3개 입력 후 운영 재개 |
| 루트 토큰 분실 | 언실 후 `vault operator generate-root` 절차 진행 |
| 시크릿 롤백 필요 | `vault kv get -version=<n> secret/app/db` 후 필요 시 `undelete` |
| TLS 적용 | PEM 인증서 볼륨 마운트 + `tls_disable="false"` 설정 |
| 고가용성(HA) | 이후 단계에서 `raft` 스토리지 + `replication` 구성 고려 |

---

## 9. 예상 일정

| 단계 | 작업 내용 | 예상 소요 |
|------|----------|-----------|
| 환경 준비 | Docker 설치, 포트 개방 | 0.5 h |
| Vault 배포 | compose 작성·기동 | 0.5 h |
| 초기화 & KV 구성 | init.sh 수정, 시크릿 입력 | 0.5 h |
| Spring 연동 | 의존성 추가, yml 작성 | 1 h |
| 테스트 & 검증 | 연결 확인, 장애 시나리오 | 1 h |
| **총합** | | **~3.5 h** |

---

## 10. 참고 자료
- HashiCorp Vault 공식 문서 <https://developer.hashicorp.com/vault>  
- Spring Cloud Vault Reference <https://docs.spring.io/spring-cloud-vault/docs/current/reference/html/>  

---

**© 2025.06.23 — 작성자: ChatGPT (for 대장님)**  
