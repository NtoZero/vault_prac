
# Vault를 이용한 **파일 암·복호화** 가이드

> **대상 버전** : Vault ≥ 1.10 (Transit Secret Engine 지원)  
> **적용 범위** : **개발·운영** 동일 과정, 단 운영은 TLS + RBAC 필수

---

## 1. 개념 이해

| 항목 | 설명 |
|------|------|
| **Transit Secret Engine** | 키 관리·암·복호화 전용 엔진. Vault 내부에 **키만 저장** 하고, 원본 데이터는 저장하지 않음 |
| **Envelope Encryption** | (1) 파일 내용을 **대칭키(데이터 키)** 로 암호화 → (2) 데이터 키를 Transit 마스터 키로 암호화 |
| **Chunk 방식** | 32 KB 이상 파일은 Base64 → Splitting → 각 Chunk 암호화 후 조합하는 패턴 사용 |

> 📌 Vault는 **파일 스토리지** 엔진이 아닙니다. 실제 파일은 S3 / 오브젝트 스토리지 / DB 등에 저장하고,  
> **Ciphertext 또는 DEK(Data Encryption Key)** 만 Vault Transit 으로 보호합니다.

---

## 2. Transit Secret Engine 활성화

```bash
# ① transit 엔진 활성화
vault secrets enable transit

# ② 파일용 키 생성 (AES‑256‑GCM96)
vault write -f transit/keys/file-enc-key             deletion_allowed=false             type="aes256-gcm96"
```

*`deletion_allowed=false` 로 키 삭제를 방지해 RTO 리스크 최소화.*

---

## 3. **소형 파일(<32 KB)** 암·복호화

### 3‑1. 암호화

```bash
FILE="logo.png"
PLAINTEXT=$(base64 -w0 "$FILE")

CIPHERTEXT=$(vault write -field=ciphertext              transit/encrypt/file-enc-key              plaintext="$PLAINTEXT")

echo "$CIPHERTEXT" > "${FILE}.enc"
```

### 3‑2. 복호화

```bash
CIPHERTEXT=$(cat logo.png.enc)

PLAINTEXT_B64=$(vault write -field=plaintext                 transit/decrypt/file-enc-key                 ciphertext="$CIPHERTEXT")

echo "$PLAINTEXT_B64" | base64 -d > logo_restored.png
```

---

## 4. **대형 파일(>32 KB)** Envelope Encryption 스크립트

```bash
#!/usr/bin/env bash
# encrypt_large.sh <SRC_FILE> <DST_FILE.enc>
set -e
SRC="$1"; OUT="$2"

# 1) 256‑bit 임시 데이터키 생성
DEK=$(openssl rand -base64 32)

# 2) 파일 내용을 DEK 로 AES‑256‑GCM 암호화
openssl enc -aes-256-gcm -pbkdf2 -iter 100000         -salt -in "$SRC" -out "$OUT" -pass "pass:$DEK"

# 3) DEK 를 Vault Transit 으로 래핑
WRAPPED_DEK=$(vault write -field=ciphertext               transit/encrypt/file-enc-key               plaintext="$(echo -n "$DEK" | base64 -w0)")

# 4) 메타데이터 저장
cat <<EOF > "${OUT}.meta"
ciphertext=$WRAPPED_DEK
algorithm=aes256-gcm
EOF
```

> **복호화** 는 역순: 메타에서 `ciphertext` → Transit `decrypt` → `openssl dec`.

---

## 5. CI/CD 파이프라인 적용 예

```yaml
# GitHub Actions 예시 (Secrets → VAULT_ADDR, VAULT_TOKEN)
- name: Encrypt Release Artifact
  run: |
    ./scripts/encrypt_large.sh build/app.jar dist/app.jar.enc
  env:
    VAULT_ADDR: ${{ secrets.VAULT_ADDR }}
    VAULT_TOKEN: ${{ secrets.VAULT_TOKEN }}

- name: Upload to S3
  uses: aws-actions/s3-sync@v1
  with:
    args: --follow-symlinks --acl private
    bucket: my-secure-artifacts
    source: dist
```

---

## 6. 키 롤오버(Key Rotation)

```bash
# ① 새로운 키버전 생성 (자동 key_version+1)
vault write -f transit/keys/file-enc-key/rotate

# ② 새 버전으로 re‑wrap
vault write -f transit/rewrap/file-enc-key             ciphertext="$OLD_CIPHERTEXT"
```

*`rewrap` 은 복호화 없이 새 마스터 키로 암호문을 재암호화.*

---

## 7. 모범 사례 ✔︎

- **TLS** : Vault API 통신은 반드시 HTTPS  
- **RBAC** : `transit/encrypt/*`, `transit/decrypt/*` 를 별도 Policy 로 최소 권한 부여  
- **감사 로그** : `vault audit enable file ...` 로 모든 Encrypt/Decrypt 호출 기록  
- **DEK 수명** : 장기 저장 금지, 사용 후 즉시 폐기 또는 메모리 상주만  
- **SOPS / Git‑Crypt** : 애플리케이션 구성 파일도 Envelope Encryption 패턴으로 관리

---

## 8. 참고 문서

- [Vault Transit Secret Engine 공식 가이드](https://developer.hashicorp.com/vault/docs/secrets/transit)  
- [Envelope Encryption 패턴 설명](https://developer.hashicorp.com/vault/tutorials/encryption-as-a-service/kms-envelope-encryption)

---

## 9. 결론

Vault Transit Secret Engine을 활용하면 **파일 데이터 자체** 는 안전한 스토리지에 두고,  
**암호화 키 관리·롤오버·접근 제어** 는 Vault 로 중앙집중화할 수 있습니다.  
이로써 **컴플라이언스(PII, GDPR) 요구 사항** 과 **키 관리 복잡도** 두 마리 토끼를 잡을 수 있습니다. 🛡️

대장님, 안전한 파일 암·복호화 워크플로 구축에 도움이 되길 바랍니다!
