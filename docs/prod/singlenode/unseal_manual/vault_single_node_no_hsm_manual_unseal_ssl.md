# Vault 단일 노드 (Raft + 수동 Unseal) **HSM/KMS 없이** 운영 가이드

**적용 버전**  
- HashiCorp **Vault 1.19.5** (오픈소스 에디션)  
- 외부 HSM·클라우드 KMS **불사용**, Shamir Secret Shares 기반 **수동 Unseal**

> ⚠️ **주의**  
> 이 문서는 **싱글 노드** 운영 + **수동 Unseal**을 전제로 한 최소 구성입니다.  
> 노드 장애 시 서비스가 곧바로 중단되므로, 장기적으로는 **3–5 노드 Raft 클러스터 + Auto‑Unseal** 도입을 권장합니다.

---
## 디렉터리 구조
```
vault-single-raft-manual/
├── .env                  # 변수 파일 (Git 제외)
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
# Git 에 커밋 금지
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
      - "8201:8201"                 # Raft cluster 통신 (향후 확장 대비)
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
# 기본 UI 및 클러스터 설정
ui            = true
disable_mlock = false
cluster_name  = "vault-prod-single"

# Raft 스토리지 (Integrated Storage)
storage "raft" {
  path    = "/vault/data"
  node_id = "vault-node-1"
}

# Auto‑Unseal 미사용 → seal stanza 생략
# Vault 재시작 시 `vault operator unseal` 로 3개 키를 입력해야 함

# TCP Listener
listener "tcp" {
  address         = "0.0.0.0:8200"
  tls_cert_file   = "/vault/userconfig/certs/vault.crt"
  tls_key_file    = "/vault/userconfig/certs/vault.key"
  cluster_address = "0.0.0.0:8201"
}

# 외부에서 접근 가능한 주소
api_addr     = "https://${VAULT_DNS_OR_IP}:8200"
cluster_addr = "https://${VAULT_DNS_OR_IP}:8201"
```

---

---
## SSL/TLS 인증서 준비 가이드
Vault는 **HTTPS**(TLS)만 지원하므로 신뢰할 수 있는 인증서가 반드시 필요합니다.  
두 가지 대표적인 방법을 설명합니다.

### 1) 테스트·내부망용 **자체 서명(Self‑Signed) 인증서**
```bash
# vault/certs 디렉터리에서 실행 (CN은 VAULT_DNS_OR_IP)
openssl req -newkey rsa:4096 -nodes -keyout vault.key \
  -x509 -days 365 -out vault.crt \
  -subj "/CN=${VAULT_DNS_OR_IP}"

# 퍼미션 강화
chmod 600 vault.key vault.crt
```
- `vault.hcl` 의 `tls_cert_file`, `tls_key_file` 경로와 일치해야 합니다.
- 클라이언트(브라우저·CLI) 측에서 인증서를 신뢰하도록 루트­CA 저장소에 추가하거나, `VAULT_SKIP_VERIFY=true` 등을 사용해야 합니다.

### 2) 운영 환경용 **Let's Encrypt (Certbot)**
```bash
# 호스트에 Certbot 설치 후 DNS·HTTP 인증으로 발급 (예: DNS 인증)
certbot certonly --manual --preferred-challenges dns \
    -d "${VAULT_DNS_OR_IP}" --agree-tos -m admin@example.com

# 발급 결과 (기본 경로 /etc/letsencrypt/live/<도메인>/)
cp /etc/letsencrypt/live/${VAULT_DNS_OR_IP}/fullchain.pem vault/certs/vault.crt
cp /etc/letsencrypt/live/${VAULT_DNS_OR_IP}/privkey.pem  vault/certs/vault.key
chmod 600 vault/certs/vault.key
```
- 인증서 자동 갱신 후 Vault 컨테이너를 **graceful reload** 하려면:
  ```bash
  docker compose exec vault         kill -HUP 1   # PID 1(vault) 프로세스에 SIGHUP → TLS 인증서 재로딩
  ```

#### 참고: Nginx / HAProxy 앞단 TLS 종료(Termination)
- Vault 앞단에 프록시를 두고 TLS 종료를 프록시에서 수행할 수도 있습니다.  
- 이 경우 `vault.hcl` 의 `tls_disable = true` 로 Listener 를 평문(`http`)으로 열고, 프록시에서 **mTLS**·IP ACL 등을 적용하세요.


## 초기화 스크립트 (vault/scripts/init-first-time.sh)
```bash
#!/usr/bin/env bash
# Vault 최초 1회 초기화 및 Unseal (키 3개 자동 입력)
set -euo pipefail

echo "▶️ Vault 초기화 중..."
INIT_JSON=$(docker compose exec vault       vault operator init         -key-shares=5         -key-threshold=3         -format=json)

TS=$(date -u +%s)
INIT_FILE="/tmp/vault_init_${TS}.json"
echo "$INIT_JSON" | tee "$INIT_FILE"

echo ""
echo "🔑 5개의 Unseal Key 및 Root Token이 ${INIT_FILE} 에 저장되었습니다."
echo "‼️ 파일을 즉시 오프라인/암호화 매체로 이동하고, 최소 3개 키를 서로 다른 장소에 분산 보관하세요."

# Unseal (키 3개 사용)
for i in 0 1 2; do
  KEY=$(echo "$INIT_JSON" | jq -r ".unseal_keys_b64[${i}]")
  echo "  • Unseal key $((i+1)) 입력..."
  docker compose exec vault vault operator unseal "$KEY"
done

ROOT_TOKEN=$(echo "$INIT_JSON" | jq -r .root_token)
echo ""
echo "🎉 Unseal 완료 — Root Token: $ROOT_TOKEN"
echo "👉 Root Token 역시 안전한 비밀 저장소에 보관하세요."
```

---
## Unseal 스크립트 (vault/scripts/unseal-all.sh)
```bash
#!/usr/bin/env bash
# Vault 재시작 후 보관 중인 키 3개를 입력해 Unseal
set -euo pipefail

read -s -p "Unseal key 1: " KEY1; echo
read -s -p "Unseal key 2: " KEY2; echo
read -s -p "Unseal key 3: " KEY3; echo

docker compose exec vault vault operator unseal "$KEY1"
docker compose exec vault vault operator unseal "$KEY2"
docker compose exec vault vault operator unseal "$KEY3"

echo "✅ Vault Unseal 완료"
```

---
## 운영 체크리스트
1. **키 관리** — 5개 Unseal Key 중 3개 이상 분실 시 복구 불가.  
2. **재시작 절차** — Vault 재시작 때마다 `unseal-all.sh` 실행해 키 3개 입력 필요.  
3. **백업** — `vault operator raft snapshot save` 로 주기적 스냅샷 백업 + 복구 리허설.  
4. **모니터링** — Prometheus Exporter로 메트릭 수집, 로그 중앙화.  
5. **확장 계획** — 장기적으로 HSM/KMS Auto‑Unseal + 다중 노드 Raft 클러스터 전환 권장.

---
## 참고 문서
- Manual Unseal & Shamir Keys  
  <https://developer.hashicorp.com/vault/docs/concepts/seal>
- Raft Storage  
  <https://developer.hashicorp.com/vault/docs/configuration/storage/raft>
- Vault 1.19.x 릴리즈 노트  
  <https://developer.hashicorp.com/vault/docs/updates/release-notes>
