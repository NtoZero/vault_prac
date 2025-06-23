
# Vault 다중 환경(Docker Compose) 구성 가이드

> **대상 독자**  
> - 개발‧테스트용 **로컬 Vault**와 별도로 **Production Vault**를 동일 저장소(레포지토리)에서 관리하려는 팀  
> - *docker compose* v2 이상 사용자를 가정합니다.

---

## 1. 전체 디렉터리 구조 예시

```text
.
├─ docker-compose.dev.yml        # (기존) 개발 환경
├─ docker-compose.prod.yml       # (신규) 운영 환경
├─ env/
│  ├─ mysql.env                  # 개발용 MySQL 환경변수
│  └─ prod_mysql.env             # 운영용 MySQL 환경변수
├─ volume/
│  ├─ dev/                       # 개발 데이터·설정
│  │  └─ vault/ …
│  └─ prod/                      # 운영 데이터·설정
│     └─ vault/ …
└─ vault_config/
   ├─ dev.hcl                    # (기존) 개발 설정
   └─ prod.hcl                   # (신규) 운영 설정
```

> **Tip** : CI 파이프라인(GitHub Actions 등)에서 `docker-compose.dev.yml` 과 `docker-compose.prod.yml` 을 분기(build matrix)로 분리해두면, 하나의 레포지토리로 로컬 개발 ‧ 스테이징 ‧ 운영을 모두 관리할 수 있습니다.

---

## 2. 운영용 `docker-compose.prod.yml`

```yaml
version: "3.9"

services:
  vault:
    image: hashicorp/vault:1.17
    container_name: vault-prod
    restart: unless-stopped
    # 운영에서는 0.0.0.0 에 바인딩 + TLS 권장
    ports:
      - "8200:8200"
    environment:
      VAULT_ADDR: "https://vault.mycompany.com:8200"
      VAULT_API_ADDR: "https://vault.mycompany.com:8200"
      VAULT_LOCAL_CONFIG: |
        # 운영에서는 HCL 파일 대신 ENV 로 최소 설정만 주고,
        # /vault/config/prod.hcl 에 나머지를 선언하는 형태도 가능
        ui = true
    cap_add:
      - IPC_LOCK
    volumes:
      # (1) 설정 파일
      - ./vault_config/prod.hcl:/vault/config/prod.hcl:ro
      # (2) 운영 데이터 디렉터리
      - ./volume/prod/vault/data:/vault/file
      # (3) 초기화 스크립트 (읽기전용)
      - ./volume/prod/vault/scripts:/scripts:ro
    command: >
      sh -c "vault server -config=/vault/config/prod.hcl &
             sleep 10 &&
             /scripts/init_unseal_prod.sh"

  mysql:
    image: mysql:8.4
    container_name: mysql-prod
    restart: unless-stopped
    env_file:
      - ./env/prod_mysql.env
    command: >
      --character-set-server=utf8mb4
      --collation-server=utf8mb4_unicode_ci
    ports:
      - "3306:3306"
    volumes:
      - ./volume/prod/mysql/data:/var/lib/mysql
```

### 2‑1. 주요 차이점 정리

| 구분 | 개발(dev) | 운영(prod) |
|------|-----------|-----------|
| 컨테이너명 | `vault` | `vault-prod` |
| 데이터 볼륨 | `./volume/vault/data` | `./volume/prod/vault/data` |
| 설정 파일 | `dev.hcl` | `prod.hcl` – TLS·스토리지 Back‑end 차별화 |
| ENV 파일 | 없음(직접 기입) | `.env.pro` 또는 별도 Vault 전용 ENV 사용 |
| 실행 스크립트 | 필요시 `init.sh` | `init_unseal_prod.sh` (키 쉐어·TLS Cert 발급 포함) |

---

## 3. 운영용 Vault 설정 `vault_config/prod.hcl`

```hcl
# prod.hcl
disable_mlock = true   # 컨테이너 환경 특성

ui            = true
listener "tcp" {
  address       = "0.0.0.0:8200"
  tls_cert_file = "/vault/config/certs/vault.crt"
  tls_key_file  = "/vault/config/certs/vault.key"
}

# File storage 예시 (HA가 필요하면 raft 또는 consul 권장)
storage "file" {
  path = "/vault/file"
}
```

> **TLS 필수** : 운영 환경은 반드시 TLS 인증서를 적용하시고,  
> `VAULT_ADDR/VAULT_API_ADDR` 도 `https://` 로 맞춰주세요.

---

## 4. 실행 방법

1. **운영용 ENV 파일 준비**

   ```bash
   cp env/mysql.env env/prod_mysql.env
   # 비밀번호·DB 이름 등 민감 값 수정
   ```

2. **컨테이너 기동**

   ```bash
   # dev (예시)
   docker compose -f docker-compose.dev.yml up -d

   # prod (예시 – 같은 호스트에서 분리 기동)
   docker compose -f docker-compose.prod.yml --env-file env/prod_mysql.env up -d
   ```

3. **초기화 & Unseal**

   운영 스크립트(`init_unseal_prod.sh`)에서는…

   ```bash
   vault operator init -key-shares=5 -key-threshold=3 -format=json > /scripts/init.json
   # 필요한 만큼 unseal 키 적용
   for k in $(jq -r '.unseal_keys_b64[0:3][]' /scripts/init.json); do
       vault operator unseal "$k"
   done
   ```

---

## 5. FAQ

| 질문 | 답변 |
|------|------|
| **Q. dev/prod 둘 다 8200 포트를 쓰면 충돌 아닌가요?** | 보통 개발은 로컬 PC, 운영은 별도 서버 혹은 다른 Docker 네트워크에서 돌기 때문에 직접 충돌하지 않습니다. 같은 호스트에서 동시에 띄울 경우, `8200:8200` → `8201:8200` 과 같이 Host‑Side 포트를 바꿔주세요. |
| **Q. 파일 스토리지가 안전한가요?** | 단일 노드·스몰 서비스에는 충분하지만, HA/이중화가 필요하면 `raft` 스토리지(내장)나 외부 `consul` 스토리지를 권장합니다. |
| **Q. Git 커밋 시 민감 정보 유출 위험?** | ENV 파일과 TLS Key·Unseal Key JSON 은 `.gitignore` 로 필수 제외, Git‑Crypt, SOPS 등을 사용해 암호화된 상태로만 저장하세요. |

---

## 6. 마무리

이 가이드는 **로컬(개발) + 운영** 두 가지 Vault 인스턴스를 **Docker Compose** 로 간단하게 관리할 수 있도록 한 *Minimal‑Yet‑Practical* 예제입니다.  
규모가 커지면 다음을 고려하세요.

1. **스토리지 백엔드** : `raft`, `consul` 등으로 HA 구성  
2. **자동 Unseal** : AWS KMS / GCP KMS / HSM 기반 `"auto_unseal"`  
3. **GitOps** : Vault 정책 & 시크릿을 Terraform / Vault Helm Chart 로 선언적 관리

대장님, 안전하고 일관된 Vault 운영 환경 구축에 도움이 되길 바랍니다. 🚀
