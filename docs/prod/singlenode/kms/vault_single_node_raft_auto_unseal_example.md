# Vault 단일 노드 (Raft + Auto‑Unseal) 최소 운영 예제

**버전 기준**  
HashiCorp **Vault 1.19.5** (LTS) 이미지를 사용합니다. 1.19.x는 2025‑03‑05 GA 이후 현재 장기 지원(LTS) 버전입니다.

> ⚠️ **중요**  
> 본 가이드는 *단일 노드* 환경에서 **Raft 스토리지 + Auto‑Unseal** 을 적용해 운영 리스크를 최소화하려는 조직을 위한 **차선책** 예시입니다.  
> 고가용성이 필요한 프로덕션에서는 **3 ~ 5 노드** Raft 클러스터를 권장합니다.

## 디렉터리 구조
```
vault-single-raft/
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
    image: hashicorp/vault:1.19.5
    container_name: vault
    restart: unless-stopped
    ports:
      - "8200:8200"                 # HTTPS API & Web UI
      - "8201:8201"                 # Raft cluster 통신
    cap_add:
      - IPC_LOCK                    # mlock 사용 권한
    environment:
      VAULT_ADDR: "https://${VAULT_DNS_OR_IP}:8200"
      VAULT_API_ADDR: "https://${VAULT_DNS_OR_IP}:8200"
      VAULT_RAFT_NODE_ID: "vault-node-1"
      AWS_REGION: "ap-northeast-2"  # Auto‑Unseal용 KMS와 동일 리전
    volumes:
      - ./vault/config:/vault/config:ro
      - ./vault/file:/vault/data            # Raft 데이터
      - ./vault/certs:/vault/userconfig/certs:ro
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

# Auto‑Unseal (AWS KMS 예시)
seal "awskms" {
  region     = "ap-northeast-2"
  kms_key_id = "xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx"  # KMS 키 ARN 또는 ID
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

## 초기화 스크립트 (vault/scripts/init-first-time.sh)
```bash
#!/usr/bin/env bash
# 최초 1회만 실행하여 Vault를 초기화합니다.
set -euo pipefail

# 초기화 (Shamir key share 생성은 1개이지만 Auto‑Unseal로 대체됨)
INIT_OUTPUT=$(docker compose exec vault       vault operator init -recovery-shares=1 -recovery-threshold=1 -format=json)

echo "$INIT_OUTPUT" | tee /tmp/vault_init_$(date -u +%s).json

ROOT_TOKEN=$(echo "$INIT_OUTPUT" | jq -r .root_token)
echo "Root token: $ROOT_TOKEN"
echo "🔑 루트 토큰을 즉시 안전한 비밀관리 시스템에 저장하고 파일은 삭제하세요."
```

## 백업 스크립트 (vault/scripts/backup_snapshot.sh)
```bash
#!/usr/bin/env bash
# 주기적으로 실행하여 Raft 스냅샷을 백업합니다.
set -euo pipefail

TIMESTAMP=$(date -u +%Y%m%dT%H%M%SZ)
SNAPSHOT_FILE="/tmp/vault_snapshot_${TIMESTAMP}.snap"

docker compose exec vault       vault operator raft snapshot save "$SNAPSHOT_FILE"

# 예: AWS CLI를 사용해 S3로 업로드 (버전 관리 버킷 권장)
aws s3 cp "$SNAPSHOT_FILE" "s3://my-vault-backups/${TIMESTAMP}.snap" --storage-class STANDARD_IA

rm -f "$SNAPSHOT_FILE"
echo "✅ Snapshot ${TIMESTAMP} 업로드 완료"
```

## 운영 체크리스트 (단일 노드 상항)
1. **모니터링**  
   - Prometheus · Grafana로 Vault 메트릭 수집 (telemetry stanza).
2. **정책 관리**  
   - root 토큰 사용 최소화, 팀별 정책·토큰으로 분리.
3. **정기 백업 검증**  
   - 월 1회 이상 `snapshot restore` 리허설 수행.
4. **노드 확장 계획**  
   - 두 번째 노드 추가 시 `vault operator raft join` 명령으로 RAID 손쉽게 확장.

## 참고 문서
- Raft 스토리지 통합 가이드  
  <https://developer.hashicorp.com/vault/docs/configuration/storage/raft>
- AWS KMS Auto‑Unseal 설정  
  <https://developer.hashicorp.com/vault/docs/configuration/seal/awskms>
- Vault 1.19.x 릴리즈 노트  
  <https://developer.hashicorp.com/vault/docs/updates/release-notes>
