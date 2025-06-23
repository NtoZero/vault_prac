
# HashiCorp Vault RBAC 가이드 (개발·운영 공통)

> **목표**  
> Vault 저장소(Secret Engine) 전체를 **역할 기반 접근 제어(RBAC)** 로 보호하는 방법을 단계별로 정리합니다.  
> 개발·운영 모드 구분 없이 동일한 원리로 적용됩니다.

---

## 1. RBAC 개념 요약

| 용어 | 설명 |
|------|------|
| **Entity** | Vault 내부 사용자 ID. 여러 인증 방법(Auth Method)을 하나의 Entity 로 통합해 권한을 일관되게 부여 |
| **Policy** | 허용(allow)·거부(deny) 규칙 집합. HCL 문법 사용 |
| **Group** | Entity 를 묶어 Policy 를 일괄 적용 |
| **Role** | 특정 Secret Engine(예: DB, PKI) 에서 토큰·자격증명 발급 시 설정 묶음. *(Policy 와 혼동 주의)* |

> **결론** : Vault RBAC의 핵심은 **Policy** 작성 → **Entity/Group** 연결 → (필요 시) **Role** 설정.

---

## 2. 정책(Policy) 작성

### 2‑1. 최소 권한 접근 원칙

```hcl
# kv-read-only.hcl
path "secret/data/*" {
  capabilities = ["read", "list"]
}
```

### 2‑2. 읽기 + 쓰기 + 삭제

```hcl
# kv-full-access.hcl
path "secret/data/*" {
  capabilities = ["create", "update", "read", "delete", "list"]
}

# 메타데이터(API v2) 권한도 필요
path "secret/metadata/*" {
  capabilities = ["delete", "list"]
}
```

### 2‑3. 시스템 관리

```hcl
# admin.hcl
path "sys/*"         { capabilities = ["create","read","update","delete","sudo"] }
path "auth/*"        { capabilities = ["create","read","update","delete","sudo"] }
path "secret/*"      { capabilities = ["create","read","update","delete","sudo"] }
```

> ⚠️ `sudo` 가 포함되면 **root 에 준하는 권한** 입니다. 운영 환경에서는 철저히 분리하세요.

---

## 3. Policy 등록 & 검증

```bash
# 정책 등록
vault policy write kv-read-only ./kv-read-only.hcl
vault policy write kv-full-access ./kv-full-access.hcl
vault policy write admin ./admin.hcl

# 확인
vault policy read kv-read-only
```

---

## 4. 인증 방식(Auth Method)과 Entity 연동

### 4‑1. 예시 : GitHub OAuth

```bash
# 1) GitHub auth 활성화
vault auth enable github

# 2) 팀(member) → 정책 매핑
vault write auth/github/map/teams/dev kv-read-only
vault write auth/github/map/teams/platform-admin admin

# 3) 로그인 테스트 (개발자 머신)
vault login -method=github token=<PERSONAL_GITHUB_PAT>
```

> 로그인 성공 시 **Entity** 가 자동 생성되고, 해당 팀에 매핑된 Policy 가 적용됩니다.

### 4‑2. 예시 : AppRole (CI/CD)

```bash
# AppRole 생성
vault write auth/approle/role/ci-cd       policies="kv-full-access"       token_ttl=1h token_max_ttl=4h

# RoleID + SecretID 로 로그인
ROLE_ID=$(vault read -field=role_id auth/approle/role/ci-cd/role-id)
SECRET_ID=$(vault write -f -field=secret_id auth/approle/role/ci-cd/secret-id)

vault write auth/approle/login role_id="$ROLE_ID" secret_id="$SECRET_ID"
```

---

## 5. 그룹(Group) 활용 예시

```bash
# LDAP 그룹 → Vault Group 매핑
GROUP_ID=$(vault write identity/group name="ops-team"               policies="kv-read-only" type=external -format=json | jq -r .data.id)

# LDAP Auth Mount 와 연결
vault write auth/ldap/groups/ops-team       policies=kv-read-only       group_id="$GROUP_ID"
```

---

## 6. 비밀 엔진 별 **Role** 관리

| Secret Engine | 주요 Role 옵션 | 비고 |
|---------------|---------------|------|
| **database**  | `db_name`, `creation_statements`, `default_ttl` | 동적 DB 계정 발급 |
| **pki**       | `allowed_domains`, `max_ttl`, `key_type` | TLS Cert 자동 발급 |
| **aws**       | `credential_type`, `policy_arns`, `ttl` | 일회용 IAM 자격증명 |

```bash
# 예) database 엔진 Role
vault write database/roles/app       db_name="mysql-prod"       creation_statements="CREATE USER '{{name}}'@'%' IDENTIFIED BY '{{password}}'; GRANT SELECT ON *.* TO '{{name}}'@'%';"       default_ttl="1h"       max_ttl="24h"
```

---

## 7. Dev ↔ Prod 환경 분리 전략

| 항목 | 개발(dev) | 운영(prod) |
|------|-----------|-----------|
| **Namespace** | `dev/` (Enterprise) / 별도 Mount | `prod/` |
| **Policy Prefix** | `dev-kv-*` | `prod-kv-*` |
| **Auth Mount Path** | `auth/github-dev` | `auth/github` |
| **Storage** | `file` or `raft` | `raft` + HA |
| **Unseal 방식** | Shamir(Key Shares) | KMS auto-unseal |

> **Best Practice** : **정책 이름**·**Mount Path**·**Namespace** 에 접두사(dev/prod) 를 붙여 실수로 교차 접근을 방지합니다.

---

## 8. 감사(Audit) 활성화

```bash
vault audit enable file file_path=/vault/logs/audit.log
```

*RBAC 정책이 의도대로 동작하는지, 감사 로그를 통해 주기적으로 검증하세요.*

---

## 9. 체크리스트

- [ ] 정책에 `sudo` 남용 금지  
- [ ] 각 팀(개발/운영/플랫폼) 별 최소 정책 연결  
- [ ] CI/CD 용 AppRole or OIDC Role 은 TTL 을 짧게  
- [ ] 감사 로그 무결성 확보 (WORM 저장소 권장)  
- [ ] 정책 변경 시 GitOps(예: Terraform + Vault Provider) 관리  

---

## 10. 결론

Vault RBAC 는 **Policy 기반** 으로 강력하면서도 세밀한 권한 제어를 제공합니다.  
개발‧운영 환경 모두 동일한 메커니즘을 사용하므로,  
**정책 템플릿화 + GitOps** 를 통해 일관적인 접근 제어를 구현하세요.

대장님, 이 가이드가 Vault 보안 체계를 탄탄히 구축하는 데 도움이 되기를 바랍니다! 🚀
