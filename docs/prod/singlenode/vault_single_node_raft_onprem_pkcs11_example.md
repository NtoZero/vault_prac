# Vault 단일 노드 (Raft + PKCS#11 Auto‑Unseal) 온프레미스 예제

**적용 버전**  
- Vault **1.19.5** (Enterprise 기능 *PKCS#11 Seal* 사용)  
- HSM: PKCS#11 인터페이스 지원 장비 (예: Thales Luna, Utimaco, SoftHSM 등)

> ⚠️ **주의**  
> 본 예제는 외부 클라우드 서비스를 전혀 사용하지 않는 **완전한 온프레미스** 시나리오용입니다.  
> PKCS#11 Seal 기능은 **Vault Enterprise** 라이선스가 필요합니다.  
> HSM 라이브러리 파일 경로와 슬롯, PIN 값 등은 장비·환경마다 다르므로 반드시 변경하세요.  
> 단일 노드는 여전히 SPOF이므로 프로덕션에는 **3–5 노드** Raft 클러스터를 권장합니다.

## 디렉터리 구조
```
vault-single-raft-pkcs11/
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
    environment:
      VAULT_ADDR: "https://${VAULT_DNS_OR_IP}:8200"
      VAULT_API_ADDR: "https://${VAULT_DNS_OR_IP}:8200"
      VAULT_RAFT_NODE_ID: "vault-node-1"
    volumes:
      # Vault 설정 · 데이터
      - ./vault/config:/vault/config:ro
      - ./vault/file:/vault/data
      - ./vault/certs:/vault/userconfig/certs:ro
      # 호스트의 PKCS#11 라이브러리를 컨테이너에 매핑 (예시)
      - /usr/local/lib/libCryptoki2_64.so:/usr/vault/lib/libCryptoki2_64.so:ro
    command: >
      vault server -config=/vault/config/vault.hcl
```

## Vault 설정 (vault/config/vault.hcl)
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
  lib              = "/usr/vault/lib/libCryptoki2_64.so"  # HSM PKCS#11 라이브러리
  slot             = "0"                                  # 슬롯 ID
  pin              = "0000-0000-0000-0000"                # 사용자 PIN (예: 프로텍트 서버: USER-PIN)
  key_label        = "vault-hsm-key"
  hmac_key_label   = "vault-hsm-hmac-key"
  generate_key     = "true"                               # 키 자동 생성 (최초 1회)
  mechanism        = "0x0009"                             # RSA‑PKCS OAEP
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

### 설정 참고  
- PKCS#11 Seal 파라미터는 HSM 벤더 문서를 따라 조정합니다.  
  HashiCorp 공식 예시를 참고하세요. citeturn0search0  
- Raft 스토리지 사용 시 `cluster_addr` 필수입니다. citeturn1search0

## 초기화 스크립트 (vault/scripts/init-first-time.sh)
```bash
#!/usr/bin/env bash
# 최초 1회 Vault 초기화 (Auto‑Unseal: HSM이 unseal 수행)
set -euo pipefail

# recovery-shares: 복구 키 조각 수, recovery-threshold: 복구에 필요한 최소 조각
INIT_OUTPUT=$(docker compose exec vault       vault operator init -recovery-shares=5 -recovery-threshold=3 -format=json)

TIMESTAMP=$(date -u +%s)
echo "$INIT_OUTPUT" | tee /tmp/vault_init_${TIMESTAMP}.json

ROOT_TOKEN=$(echo "$INIT_OUTPUT" | jq -r .root_token)
echo "Root token: $ROOT_TOKEN"
echo "🔐 recovery 키 조각 5개를 서로 다른 안전한 매체에 보관하고, init JSON 파일을 즉시 삭제하세요."
```

## 백업 스크립트 (vault/scripts/backup_snapshot.sh)
```bash
#!/usr/bin/env bash
# 일 1회 크론 실행 예시: 매일 03:15 UTC
set -euo pipefail

TIMESTAMP=$(date -u +%Y%m%dT%H%M%SZ)
SNAPSHOT_FILE="/tmp/vault_snapshot_${TIMESTAMP}.snap"

docker compose exec vault       vault operator raft snapshot save "$SNAPSHOT_FILE"

# 온프레미스 오브젝트 스토리지 또는 NAS로 복사 (예: MinIO)
mc cp "$SNAPSHOT_FILE" "minio/vault-backups/${TIMESTAMP}.snap"

rm -f "$SNAPSHOT_FILE"
echo "✅ Snapshot ${TIMESTAMP} 백업 완료"
```

## 운영 체크리스트 (온프레미스)
1. **HSM 모니터링**: PIN 회전·키 백업 정책 수립, 장애 대비 이중화 구성.  
2. **Vault 운영 메트릭**: Prometheus Node Exporter + Vault exporter.  
3. **정기 백업 검증**: 월 1회 이상 `snapshot restore` 리허설 수행.  
4. **클러스터 확장**: 추가 노드 설치 후 `vault operator raft join` 사용.

## 참고 문서
- PKCS#11 Seal 구성 가이드  
  <https://developer.hashicorp.com/vault/docs/configuration/seal/pkcs11>  
- 통합 스토리지(Raft) 구성  
  <https://developer.hashicorp.com/vault/docs/configuration/storage/raft>  
- Vault 1.19.x 릴리즈 노트  
  <https://developer.hashicorp.com/vault/docs/updates/release-notes>
