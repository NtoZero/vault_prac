# Vault 단일 노드 (Raft + PKCS#11 Auto‑Unseal) 온프레미스 예제 — **PIN 보안 강화 버전**

**적용 버전**  
- Vault **1.19.5 Enterprise** (PKCS#11 Seal)  
- HSM: PKCS#11 인터페이스 지원 장비 (Thales Luna, Utimaco, SoftHSM 등)

> ⚠️ **주의**  
> 본 예제는 클라우드 의존성을 없앤 **완전 온프레미스** 시나리오입니다.  
> PKCS#11 Seal은 **Vault Enterprise 라이선스**가 필요합니다.  
> 단일 노드는 여전히 SPOF이므로 프로덕션에는 **3 – 5 노드** Raft 클러스터를 권장합니다.

---
## 디렉터리 구조
```
vault-single-raft-pkcs11/
├── .env                       # <‑‑ HSM PIN 등 민감값을 저장 (Git 제외)
├── docker-compose.yml
└── vault/
    ├── config/
    │   └── vault.hcl
    ├── scripts/
    │   ├── backup_snapshot.sh
    │   └── init-first-time.sh
    └── certs/
        ├── vault.crt
        └── vault.key
```

---
## .env 예시
```dotenv
# Git 에 커밋 금지! (예: .gitignore)
VAULT_DNS_OR_IP=vault.example.com
VAULT_HSM_PIN=**REPLACE_WITH_HSM_USER_PIN**
```

---
## docker-compose.yml
```yaml
version: "3.9"

services:
  vault:
    image: hashicorp/vault-enterprise:1.19.5
    container_name: vault
    restart: unless-stopped
    ports:
      - "8200:8200"                 # HTTPS API & Web UI
      - "8201:8201"                 # Raft cluster 통신
    cap_add:
      - IPC_LOCK
    env_file:
      - ./.env                      # 민감값 별도 관리
    environment:
      VAULT_ADDR: "https://${VAULT_DNS_OR_IP}:8200"
      VAULT_API_ADDR: "https://${VAULT_DNS_OR_IP}:8200"
      VAULT_RAFT_NODE_ID: "vault-node-1"
      # 아래 항목들은 .env 에서 override 됨
      VAULT_HSM_PIN: "${VAULT_HSM_PIN}"
    volumes:
      - ./vault/config:/vault/config:ro
      - ./vault/file:/vault/data
      - ./vault/certs:/vault/userconfig/certs:ro
      # 호스트의 PKCS#11 라이브러리를 컨테이너에 매핑 (예시)
      - /usr/local/lib/libCryptoki2_64.so:/usr/vault/lib/libCryptoki2_64.so:ro
    command: >
      vault server -config=/vault/config/vault.hcl
```

---
## Vault 설정 (vault/config/vault.hcl)
```hcl
ui            = true
disable_mlock = false
cluster_name  = "vault-prod-single"

# Raft 스토리지 (Integrated Storage)
storage "raft" {
  path    = "/vault/data"
  node_id = "vault-node-1"
}

# PKCS#11 Auto‑Unseal (온프레미스 HSM)
seal "pkcs11" {
  lib              = "/usr/vault/lib/libCryptoki2_64.so"
  slot             = "0"
  pin              = { env = "VAULT_HSM_PIN" }  # 환경 변수로 PIN 주입
  key_label        = "vault-hsm-key"
  hmac_key_label   = "vault-hsm-hmac-key"
  generate_key     = "true"
  mechanism        = "0x0009"
}

# TCP Listener
listener "tcp" {
  address         = "0.0.0.0:8200"
  tls_cert_file   = "/vault/userconfig/certs/vault.crt"
  tls_key_file    = "/vault/userconfig/certs/vault.key"
  cluster_address = "0.0.0.0:8201"
}

api_addr     = "https://${VAULT_DNS_OR_IP}:8200"
cluster_addr = "https://${VAULT_DNS_OR_IP}:8201"
```

---
## 초기화 스크립트 (vault/scripts/init-first-time.sh)
```bash
#!/usr/bin/env bash
# 최초 1회 Vault 초기화 (Auto‑Unseal ‑ HSM이 unseal 수행)
set -euo pipefail

INIT_OUTPUT=$(docker compose exec vault       vault operator init -recovery-shares=5 -recovery-threshold=3 -format=json)

TS=$(date -u +%s)
echo "$INIT_OUTPUT" | tee /tmp/vault_init_${TS}.json

ROOT_TOKEN=$(echo "$INIT_OUTPUT" | jq -r .root_token)
echo "Root token: $ROOT_TOKEN"
echo "🔐 recovery 키 조각 5개를 서로 다른 안전 매체에 분산 보관하고, init JSON 파일은 즉시 삭제하세요."
```

---
## 백업 스크립트 (vault/scripts/backup_snapshot.sh)
```bash
#!/usr/bin/env bash
# 일 1회 크론으로 실행: 03:15 UTC
set -euo pipefail

TS=$(date -u +%Y%m%dT%H%M%SZ)
SNAP="/tmp/vault_snapshot_${TS}.snap"

docker compose exec vault       vault operator raft snapshot save "$SNAP"

# 온프레미스 오브젝트 스토리지(예: MinIO) 업로드
mc cp "$SNAP" "minio/vault-backups/${TS}.snap"

rm -f "$SNAP"
echo "✅ Snapshot ${TS} 백업 완료"
```

---
## 운영 체크리스트
1. **HSM 모니터링** — PIN 회전, 키 백업·이중화, 감사 로그.  
2. **Vault 메트릭 수집** — Prometheus Exporter & Grafana.  
3. **정기 백업 검증** — 월 1회 `snapshot restore` 리허설.  
4. **클러스터 확장** — 추가 노드 설치 후 `vault operator raft join`.

---
## 참고 문서
- PKCS#11 Seal 구성 가이드  
  <https://developer.hashicorp.com/vault/docs/configuration/seal/pkcs11>
- Raft 스토리지 구성  
  <https://developer.hashicorp.com/vault/docs/configuration/storage/raft>
- Vault 1.19.x 릴리즈 노트  
  <https://developer.hashicorp.com/vault/docs/updates/release-notes>
