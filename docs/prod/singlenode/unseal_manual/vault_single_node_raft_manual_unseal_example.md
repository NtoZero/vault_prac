# Vault 단일 노드 (Raft + 수동 Unseal) 온프레미스 예제

**적용 버전**  
- HashiCorp **Vault 1.19.5** (커뮤니티 에디션)  
- 외부 HSM / KMS 없이 *수동*으로 봉인 해제(Unseal)

> ⚠️ **중요**  
> 이 구성은 **싱글 노드** + **수동 Unseal** 방식입니다.  
> 고가용성(HA)이 없으므로 노드 장애 시 Vault 서비스가 즉시 중단됩니다.  
> 프로덕션에선 3–5 노드 Raft 클러스터 + Auto‑Unseal(HSM/KMS) 사용을 강력히 권장합니다.

---
## 디렉터리 구조
```
vault-single-raft-manual/
├── .env                # DNS 등 변수 (Git 제외)
├── docker-compose.yml
└── vault/
    ├── config/
    │   └── vault.hcl
    ├── scripts/
    │   ├── init-first-time.sh
    │   └── unseal-all.sh
    └── certs/
        ├── vault.crt
        └── vault.key
```

---
## .env 예시
```dotenv
VAULT_DNS_OR_IP=vault.example.com
```

---
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
      - "8201:8201"                 # Raft cluster 통신 (확장 대비)
    cap_add:
      - IPC_LOCK                    # mlock 사용 권한
    env_file:
      - ./.env
    environment:
      VAULT_ADDR: "https://${VAULT_DNS_OR_IP}:8200"
      VAULT_API_ADDR: "https://${VAULT_DNS_OR_IP}:8200"
      VAULT_RAFT_NODE_ID: "vault-node-1"
    volumes:
      - ./vault/config:/vault/config:ro
      - ./vault/file:/vault/data
      - ./vault/certs:/vault/userconfig/certs:ro
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

# (Auto‑Unseal 없음) — 수동 Unseal 방식
# seal stanza 제거
# 봉인 해제 키는 Shamir Secret Shares 로 생성
# 노드 재시작 시 vault operator unseal 명령으로 3개 키 입력 필요

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
# 최초 1회 Vault 초기화 + 3개 키로 수동 Unseal
set -euo pipefail

echo "▶️ Vault 초기화 진행..."
INIT_OUTPUT=$(docker compose exec vault       vault operator init         -key-shares=5         -key-threshold=3         -format=json)

TS=$(date -u +%s)
INIT_FILE="/tmp/vault_init_${TS}.json"
echo "$INIT_OUTPUT" | tee "$INIT_FILE"

echo ""
echo "🔑 5개의 Unseal Key(share)와 Root Token이 ${INIT_FILE} 에 저장되었습니다."
echo "‼️ 이 파일을 **즉시** 오프라인/암호화 매체로 이동하고, 최소 3개 키를 서로 다른 장소에 분산 보관하세요."

# (옵션) 첫 부트 시 바로 Unseal — 키 3개 자동 사용
for i in 0 1 2; do
  KEY=$(jq -r ".unseal_keys_b64[${i}]" "$INIT_FILE")
  echo "  • Unseal key $((i+1)) 입력중..."
  docker compose exec vault vault operator unseal "$KEY"
done

ROOT_TOKEN=$(jq -r .root_token "$INIT_FILE")
echo ""
echo "🎉 Vault Unseal 완료. Root Token: $ROOT_TOKEN"
echo "👉 Root Token 역시 안전한 비밀 저장소에 보관하세요."
```

---
## Unseal 스크립트 (vault/scripts/unseal-all.sh)
```bash
#!/usr/bin/env bash
# 서버 재기동 후, 보관 중인 키 3개를 입력하여 Unseal
set -euo pipefail

read -p "Unseal key 1: " KEY1
read -p "Unseal key 2: " KEY2
read -p "Unseal key 3: " KEY3

docker compose exec vault vault operator unseal "$KEY1"
docker compose exec vault vault operator unseal "$KEY2"
docker compose exec vault vault operator unseal "$KEY3"

echo "✅ Unseal 완료"
```

---
## 운영 체크리스트
1. **키 보관** — 5개 Unseal Key 중 3개 이상이 손실되면 Vault를 복구할 수 없습니다.  
2. **재시작 절차** — 컨테이너 재시작 시 `unseal-all.sh` 로 3개 키 입력 필요.  
3. **백업** — `vault operator raft snapshot save` 명령으로 주기적 스냅샷 백업.  
4. **HA 확장 계획** — 장기적으로 HSM·KMS Auto‑Unseal 및 다중 노드 도입 권장.

---
## 참고 문서
- Manual Unseal & Key Sharding  
  <https://developer.hashicorp.com/vault/docs/concepts/seal#vault-operator-unseal>
- Raft Storage  
  <https://developer.hashicorp.com/vault/docs/configuration/storage/raft>
- Vault 1.19.x 릴리즈 노트  
  <https://developer.hashicorp.com/vault/docs/updates/release-notes>
