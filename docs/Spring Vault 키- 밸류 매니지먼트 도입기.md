# Spring Vault 온프레미스 마이그레이션 플레이북 (Gradle Edition)

> **목표** : 기존 SaaS 서비스에서 **AWS KMS**(Key Management Service) 기반 암복호화/시크릿 관리를 **HashiCorp Vault + Spring Vault** 조합으로 전환하여 **온프레미스** 환경에서도 벤더 종속성 없이 **KMS 동급 보안 수준**을 확보한다.

---
## 1. 개요

| 구분 | 내용 |
|------|------|
| **현행** | AWS KMS + Parameter Store / Secrets Manager |
| **목표** | HashiCorp Vault (3 노드 Raft 클러스터, TLS + AppRole Auth) |
| **장점** | ‑ 벤더 종속 제거<br>‑ 온프레미스 및 멀티‑클라우드 대응<br>‑ 정교한 ACL·Lease·Audit 기능 |
| **범위** | 시크릿 저장, 암복호화(Transit), Spring Boot 앱 설정(bootstrap) |
| **불포함** | Vault Enterprise 전용 기능, HSM 연계 |

> **예상 총 소요** : **약 7 ~ 10 개 MD** (Man‑Day)  
> (상세 일정은 [§ 7] 참조)

---
## 2. 아키텍처 & Business Flow

```mermaid
flowchart LR
    subgraph "Dev / Staging / Prod"
        direction TB
        C1[Client<br>(Spring Boot)] -->|TLS| VLB((Vault HA Load Balancer))
        VLB --> V1(Vault Node 1)
        VLB --> V2(Vault Node 2)
        VLB --> V3(Vault Node 3)
        V1 <-->|Raft replication| V2
        V1 <-->|Raft replication| V3
    end
    C1-->|AppRole login| VLB:::auth
    V1 -->|Transit Encrypt/Decrypt| STORAGE[(Raft Storage)]
classDef auth fill:#f6f9,stroke:#333;
```

* **Raft Storage** : 내부 디스크에 복제 저장 (3 노드 ⇒ 1 노드 Fail 시 무중단) citeturn0search1
* **Namespace / Mount Path** 분리로 `dev`, `stg`, `prod` 환경 간 완전 격리.
* 모든 통신 **TLS v1.3** + **mTLS**(옵션) 로 암호화.

---
## 3. 준비 사항

| 구분 | 최소 사양 / 버전 |
|------|-----------------|
| Vault OSS | 1.17.x (2025‑05 기준) |
| Docker Engine | 24.x (Compose v2 내장) |
| Spring Boot | 3.2.x |
| Spring Cloud | 2025.x (호환 `spring-cloud-starter-vault-config`) |
| Java | 17 이상 |
| CI/CD | GitHub Actions or Jenkins |

---
## 4. 인프라 구축 (Docker Compose)

`infra/vault/docker-compose.yml` :

```yaml
version: "3.9"
services:
  vault1:
    image: hashicorp/vault:1.17
    container_name: vault1
    hostname: vault1
    restart: unless-stopped
    cap_add: [ "IPC_LOCK" ]
    environment:
      VAULT_LOCAL_CONFIG: |
        ui            = true
        listener "tcp" {
          address     = "0.0.0.0:8200"
          tls_cert_file = "/vault/tls/vault.crt"
          tls_key_file  = "/vault/tls/vault.key"
        }
        storage "raft" {
          path    = "/vault/data"
          node_id = "node1"
        }
        seal "transit" {
          address            = "https://vault1:8200"
          token              = "TRANSIT_UNSEAL_TOKEN"
          disable_renewal    = "false"
          key_name           = "autounseal_key"
          mount_path         = "transit/"
        }
        api_addr = "https://vault1:8200"
        cluster_addr = "https://vault1:8201"
    volumes:
      - ./data/vault1:/vault/data
      - ./tls:/vault/tls:ro
    ports:
      - "8201:8201"   # cluster
      - "8200:8200"   # api/ui
  vault2:
    <<동일, node_id=node2, ports=8202/8200 etc>>
  vault3:
    <<동일, node_id=node3, ports=8203/8200 etc>>
```

> *Raft 스토리지를 사용하므로 추가 DB·S3 등의 유료 벤더 의존성 無* citeturn0search0

---
### 4‑1. 초기화 & Unseal 스크립트

```bash
# 1) 최초 초기화 (한 번만)
docker exec vault1 vault operator init -key-shares=5 -key-threshold=3       -format=json > init.json

# 2) 3 키 Unseal
jq -r ".unseal_keys_b64[]" init.json | head -3 | while read k; do
  docker exec vault1 vault operator unseal $k
done
# node2,node3 도 동일 키 사용
```

---
## 5. Spring Boot 연동 (Gradle)

`build.gradle.kts` (예시):

```kotlin
plugins {
    id("org.springframework.boot") version "3.2.5"
    id("io.spring.dependency-management") version "1.1.5"
    kotlin("jvm") version "1.9.24"   // 옵션
}

repositories {
    mavenCentral()
}

dependencyManagement {
    imports {
        mavenBom("org.springframework.cloud:spring-cloud-dependencies:2025.0.0")
    }
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter")
    implementation("org.springframework.cloud:spring-cloud-starter-vault-config")
    implementation("org.springframework.vault:spring-vault-core")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
}
```

`bootstrap.yaml` (예시):

```yaml
spring:
  cloud:
    vault:
      uri: https://vault.mycorp.local:8200
      authentication: approle
      app-role:
        role-id:  <ROLE_ID>
        secret-id: ${VAULT_SECRET_ID}
      kv:
        enabled: true
        backend: secret
        default-context: application
        profile-separator: "_"
```

> **AppRole** 인증은 CI/CD 자동 Secret 배포에 적합하며 K8s Auth 및 TLS Cert Auth 도 선택 가능.

---
## 6. 환경별 Vault 분리 전략

| 환경 | Namespace 또는 KV Prefix | 예시 Policy |
|------|------------------------|-------------|
| dev  | `dev/` or `ns‑dev/` | `path "dev/*" { capabilities = ["read","list"] }` |
| stg  | `stg/` or `ns‑stg/` | ... |
| prod | `prod/` or `ns‑prod/` | 최소권한 principle |

* CI 파이프라인에서 `vault kv put prod/app/db PASSWORD=***` 식으로 배포.

---
## 7. 마이그레이션 일정(안)

| WBS | 작업 | 기간(MD) |
|-----|------|---------|
| 1   | 요구사항 상세 정의 & 설계 확정 | 1 |
| 2   | 인프라 (서버/네트워크/TLS) 준비 | 1 |
| 3   | Vault 클러스터 구성 & HA 검증 | 1 |
| 4   | 시크릿 데이터 마이그레이션 스크립트 작성 | 1 |
| 5   | Spring Boot Gradle 의존성 및 bootstrap.yaml 적용 | 1 |
| 6   | 통합 테스트 (CI 포함) | 1 |
| 7   | 운영 Cut‑over & 모니터링 튜닝 | 1‑3 |
| **합계** | **7‑10 MD** |

---
## 8. 고가용성 & 데이터 보존 대책

1. **Raft 3 노드 클러스터** → 1 노드 장애 시 자동 Failover
2. **Snapshot 백업** : `vault operator raft snapshot save /backup/$(date).snap`
3. **Audit Device** : `vault audit enable file file_path=/vault/logs/audit.log`
4. **자동 Unseal**(Transit or Shamir) 로 장애 복구 시간 단축

---
## 9. 참고 자료

* Spring Vault Reference Guide → HashiCorp Vault 1.17 지원 확인 citeturn0search0
* HashiCorp Docs: *Raft Integrated Storage* HA 구성 가이드 (2025‑05) citeturn0search1
* Spring Cloud Vault Gradle 예제 (GitHub jandd/spring‑boot‑vault‑demo) citeturn0search6

---
### 부록 A : Vault 모니터링 예시

```yaml
# Prometheus scrape config
  - job_name: "vault"
    static_configs:
      - targets: ["vault1:8200","vault2:8200","vault3:8200"]
```

> Grafana + Vault Exporter로 seal 상태·lease 만료·request rate 대시보드 구현 가능.

---
**작성일 : 2025‑06‑23**  〮 담당 : SEC‑ARCH 팀