# 독립형 HashiCorp Vault + Spring Boot (Redis & DB) 시나리오 북  

> **목적** : 온프레미스 환경에서 **Vault OSS**를 독립적으로 구축하고, Spring Boot 백엔드가 **Redis 캐시**와 **RDBMS**(예: MySQL) 설정 값을 Vault 로부터 안전하게 **주입**받아 구동되는 end‑to‑end 흐름을 실습한다.

---
## 1. 전체 아키텍처 흐름도  

```mermaid
flowchart TD
    subgraph "Backend Segment"
        APP[Spring Boot App] -->|AppRole login| VPN((Vault LB))
        VPN --> V1(Vault Node 1)
        VPN --> V2(Vault Node 2)
        VPN --> V3(Vault Node 3)
        V1 <-->|Raft replication| V2
        V1 <-->|Raft replication| V3
    end
    V1 -->|KV (v2) <redis/*>| REDIS_SECRET[secret/data/redis]
    V1 -->|KV (v2) <mysql/*>| DB_SECRET[secret/data/mysql]
    APP -->|Redis Conn.| REDIS((Redis Cluster))
    APP -->|JDBC/Hikari| MYSQL((MySQL Cluster))
```

* **독립형 Vault 3‑노드** + **Raft Storage** : HA & 내부 복제 citeturn0search1  
* 앱은 **AppRole** 토큰으로 로그인 → KV v2 (정적 시크릿) 또는 **Database Secrets Engine**(동적) 사용 가능 citeturn0search2turn0search11  

---
## 2. 인프라 배포 – Docker Compose  

### 2‑1. Vault Cluster (`infra/vault/docker-compose.yml`)

```yaml
version: "3.9"
services:
  vault1:
    image: hashicorp/vault:1.17
    hostname: vault1
    cap_add: ["IPC_LOCK"]
    restart: unless-stopped
    environment:
      VAULT_ADDR: "https://vault1:8200"
      VAULT_LOCAL_CONFIG: |
        ui = true
        listener "tcp" {
          address = "0.0.0.0:8200"
          tls_cert_file = "/vault/tls/vault.crt"
          tls_key_file  = "/vault/tls/vault.key"
        }
        storage "raft" {
          path    = "/vault/data"
          node_id = "node1"
        }
        api_addr     = "https://vault1:8200"
        cluster_addr = "https://vault1:8201"
    volumes:
      - ./data/vault1:/vault/data
      - ./tls:/vault/tls:ro
    ports: ["8200:8200","8201:8201"]
  vault2:
    <<동일 – node_id=node2, ports 8202/8203>>
  vault3:
    <<동일 – node_id=node3, ports 8204/8205>>
```

> **Raft Storage** 로 별도 외부 DB 의존성 없음.

### 2‑2. Redis & MySQL (간략 예시)

```yaml
services:
  redis:
    image: redis:7.2-alpine
    command: ["redis-server", "--requirepass", "$REDIS_PASS"]
    ports: ["6379:6379"]
  mysql:
    image: mysql:8.4
    env_file: .env
    volumes:
      - mysql_data:/var/lib/mysql
    ports: ["3306:3306"]
volumes:
  mysql_data:
```

---
## 3. Vault 초기화 & 시크릿 등록 스크립트  

`scripts/vault_bootstrap.sh` :

```bash
#!/usr/bin/env bash
# ─────────────────────────────────────────────
# 이 스크립트를 bash로 실행하도록 인터프리터를 지정합니다.
# 플랫폼에 상관없이 /usr/bin/env가 찾는 bash를 사용하므로 이식성이 높습니다.

set -e
# ─────────────────────────────────────────────
# 명령어 중 하나라도 비정상(0이 아닌 상태)으로 종료되면
# 즉시 스크립트 실행을 멈춰 에러를 숨기지 않도록 합니다.

# 1) init & unseal (1회)
# ─────────────────────────────────────────────
# Vault 클러스터를 최초 한 번만 초기화(init)하고,
# Shamir 비밀 공유 방식으로 생성된 unseal 키를 이용해 각 노드를 잠금 해제(Unseal)합니다.
#  - key-shares=5    : 총 5개의 키를 생성
#  - key-threshold=3 : 이 중 3개 키를 모아야 Unseal 가능
#  - -format=json    : 결과를 JSON으로 저장해 자동화 스크립트와 연동
docker exec vault1 vault operator init \
  -key-shares=5 -key-threshold=3 -format=json > init.json

# init.json 파일에서 첫 3개의 unseal 키를 추출해
# vault1, vault2, vault3 컨테이너에 각각 입력(해제)합니다.
for k in $(jq -r '.unseal_keys_b64[0:3][]' init.json); do
  docker exec vault1 vault operator unseal "$k"
  docker exec vault2 vault operator unseal "$k"
  docker exec vault3 vault operator unseal "$k"
done

# 2) login w/ root token
# ─────────────────────────────────────────────
# init.json에 생성된 root 토큰을 추출해
# VAULT_ADDR 환경변수에 엔드포인트를 설정한 뒤 로그인합니다.
# 이 토큰으로 뒤따르는 시크릿 등록·정책·Auth 설정을 수행할 권한을 얻습니다.
ROOT=$(jq -r .root_token init.json)
export VAULT_ADDR=https://localhost:8200
vault login "$ROOT"

# 3) enable KV v2
# ─────────────────────────────────────────────
# 기본으로 활성화되어 있지 않은 KV 시크릿 엔진(v2)을
# "/secret" 경로에 마운트합니다.
# v2 버전은 버전 관리(Versioning) 기능을 제공해 과거 값 복원이 가능합니다.
vault secrets enable -path=secret kv-v2

# 4) write Redis & DB creds
# ─────────────────────────────────────────────
# 애플리케이션에서 사용할 Redis 연결 정보와
# MySQL 접속 정보를 Vault에 키-값 형태로 저장합니다.
# 이렇게 저장된 시크릿은 Spring Vault가 런타임에 안전하게 조회해 주입합니다.
vault kv put secret/redis \
    url=redis://redis:6379 \
    password="${REDIS_PASS:-redis123}"

vault kv put secret/mysql \
    username="appuser" \
    password="s3cr3t!" \
    host="mysql" \
    port=3306 \
    db="appdb"

# 5) AppRole for Spring Boot
# ─────────────────────────────────────────────
# 1) spring-app 정책(policy)을 정의해
#    secret 경로의 읽기 권한만 허용하도록 설정합니다.
vault policy write spring-app - <<EOF
path "secret/data/*" {
  capabilities = ["read"]
}
EOF

# 2) AppRole 인증 방식을 활성화하고,
#    spring-app 역할(role)을 생성해 24h 토큰 유효기간을 부여합니다.
vault auth enable approle
vault write auth/approle/role/spring-app \
      token_ttl=24h token_max_ttl=72h \
      policies="spring-app"

# 3) 애플리케이션이 사용할 role_id와 secret_id를 추출해 파일로 저장합니다.
#    CI/CD 에서 이 두 값을 환경변수로 주입해 AppRole 로그인에 사용합니다.
vault read -field=role_id auth/approle/role/spring-app/role-id   > role_id
vault write -f -field=secret_id auth/approle/role/spring-app/secret-id > secret_id
```

---
## 4. Spring Boot 애플리케이션 (Gradle)

### 4‑1. `build.gradle.kts`

```kotlin
plugins {
    id("org.springframework.boot") version "3.2.5"
    id("io.spring.dependency-management") version "1.1.5"
    kotlin("jvm") version "1.9.24" // 옵션
}
repositories { mavenCentral() }
dependencyManagement {
    imports {
        mavenBom("org.springframework.cloud:spring-cloud-dependencies:2025.0.0")
    }
}
dependencies {
    implementation("org.springframework.boot:spring-boot-starter-data-redis")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.cloud:spring-cloud-starter-vault-config")
    implementation("org.springframework.vault:spring-vault-core")
    runtimeOnly("mysql:mysql-connector-j")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
}
```

### 4‑2. `bootstrap.yaml` (시크릿 소스 정의)

```yaml
spring:
  application:
    name: demo-app
  cloud:
    vault:
      uri: https://vault1:8200
      tls:
        skip-verification: true
      authentication: approle
      app-role:
        role-id: ${VAULT_ROLE_ID}
        secret-id: ${VAULT_SECRET_ID}
      kv:
        backend: secret
        default-context: redis     # prefix 선택
        profile-separator: "_"
```

### 4‑3. `application.yaml` – Redis / DB 바인딩

```yaml
spring:
  redis:
    url: ${url}          # Vault에서 가져옴 (context=redis)
    password: ${password}
  datasource:
    url: jdbc:mysql://${host}:${port}/${db}
    username: ${username}
    password: ${password}
    hikari:
      maximum-pool-size: 10
```

* Spring Cloud Vault가 `secret/data/redis` 키의 `url/password` 값을 Redis 설정에 매핑.  
* `secret/data/mysql` 값은 Datasource 속성에 매핑.  

---
## 5. 로컬 구동 & 확인 절차  

1. **Vault 클러스터 Start**  

   ```bash
   docker compose -f infra/vault/docker-compose.yml up -d
   ./scripts/vault_bootstrap.sh
   ```
   Vault `status` 확인 → 3 노드 **unsealed**, HA Enabled citeturn0search4  

2. **Redis·MySQL 컨테이너 가동**  

   ```bash
   docker compose -f infra/app-stack.yml up -d redis mysql
   ```

3. **환경 변수 삽입 후 애플리케이션 실행**  

   ```bash
   export VAULT_ROLE_ID=$(cat role_id)
   export VAULT_SECRET_ID=$(cat secret_id)
   ./gradlew bootRun
   ```

4. **로그 확인**  

   ```
2025-06-23 13:05:22  INFO  ...  Located property source: vault:secret/redis
2025-06-23 13:05:22  INFO  ...  Connected to Redis & MySQL successfully
   ```

---
## 6. CI/CD (예 : GitHub Actions)

```yaml
name: build-and-deploy
on: [push]
jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: 17
      - name: Export Vault creds
        run: |
          echo "VAULT_ROLE_ID=${{ secrets.CI_VAULT_ROLE_ID }}" >> $GITHUB_ENV
          echo "VAULT_SECRET_ID=${{ secrets.CI_VAULT_SECRET_ID }}" >> $GITHUB_ENV
      - name: Build JAR
        run: ./gradlew bootJar
      - name: Build & Push Docker
        run: |
          docker build -t registry.example.com/demo:${{ github.sha }} .
          docker push registry.example.com/demo:${{ github.sha }}
```

* CI 서버는 **AppRole ID/Secret ID** 를 GitHub Encrypted Secrets 로 보관 → 필요 시만 노출.

---
## 7. 확장 : 동적 DB 자격 증명 (선택)

1. **Database Secrets Engine 활성화**  

   ```bash
   vault secrets enable database
   vault write database/config/mydb         plugin_name=mysql-database-plugin         connection_url="{{username}}:{{password}}@tcp(mysql:3306)/"         allowed_roles="readonly,readwrite"         username="root"         password="${MYSQL_ROOT_PASSWORD}"
   ```

2. **Role 정의** :  

   ```bash
   vault write database/roles/readwrite         db_name=mydb         creation_statements="GRANT ALL ON *.* TO '{{name}}'@'%' IDENTIFIED BY '{{password}}';"         default_ttl="1h" max_ttl="24h"
   ```

3. **Spring Boot** 측에서 `spring.cloud.vault.database` 설정 추가하면 자동으로 **렌트(lease) 회전**. citeturn0search11

---
## 8. 문제 해결 FAQ

| 증상 | 원인 | 해결 |
|------|------|------|
| `Status=sealed` | Unseal 키 부족 | 3 키 재입력 |
| `Handshake failed` | TLS Self‑Signed 미검증 | `tls.skip-verification=true` 또는 CA 신뢰 |
| `Connection refused: redis` | Vault 시크릿 값 오타 | `vault kv get secret/redis` 확인 |

---
## 9. 참고 링크

* Vault Raft Reference Architecture citeturn0search1  
* Spring 앱 시크릿 자동 재로드 예제 citeturn0search2  
* Database Secrets Engine Guide citeturn0search11  

---
**작성일 : 2025‑06‑23**  |  담당 : SEC‑ARCH 팀  
