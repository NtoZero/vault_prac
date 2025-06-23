# 🧭 종합 Vault API 사용 가이드 (KV v2 기준, Vault 1.17)

> 최신 버전 기준 (2025-06)  
> 이 가이드는 HashiCorp Vault를 실전에서 사용하는 데 필요한 API를 **중복 없이** 정리한 문서입니다.  
> KV Secrets Engine은 `v2` 버전을 기준으로 작성되었습니다.

---

## 📌 1. 기본 개념

- Vault는 모든 API 요청을 `/v1/` 경로 하위에서 처리합니다.
- "v2"는 HTTP 경로가 아니라 **KV Secrets Engine의 버전**을 의미합니다.
- 모든 요청은 **토큰 기반 인증** 또는 AppRole 등을 통해 보호됩니다.
- 대부분의 API는 JSON 요청/응답을 사용합니다.

---

## 🛠️ 2. 시스템 API (`/sys`)

| 기능 | 메서드 | 경로 | 설명 |
|------|--------|------|------|
| 헬스 체크 | GET | `/v1/sys/health` | Vault 서버 상태 확인 (leader 여부 포함) |
| 봉인 | POST | `/v1/sys/seal` | 강제로 봉인 |
| 봉인 해제 | PUT | `/v1/sys/unseal` | 수동 키 조각 입력 방식 (threshold 개수 필요) |
| 봉인 상태 확인 | GET | `/v1/sys/seal-status` | 봉인 여부, 진행률 등 확인 |
| 시크릿 엔진 마운트 | POST | `/v1/sys/mounts/secret` | KV 엔진 등록 시 `options.version=2` 지정 |
| 마운트 목록 | GET | `/v1/sys/mounts` | 등록된 엔진 목록 확인 |

---

## 🔐 3. 인증 API (`/auth`)

### 3.1 Token 인증 (기본)

| 메서드 | 경로 | 설명 |
|--------|------|------|
| POST | `/v1/auth/token/create` | 새 토큰 생성 |
| GET  | `/v1/auth/token/lookup-self` | 내 토큰 정보 조회 |
| POST | `/v1/auth/token/revoke-self` | 내 토큰 폐기 |

### 3.2 AppRole 인증 (CI/CD에 추천)

| 메서드 | 경로 | 설명 |
|--------|------|------|
| POST | `/v1/auth/approle/login` | `role_id` + `secret_id` 기반 로그인 |
| GET  | `/v1/auth/approle/role/<role>` | AppRole 정보 조회 |

---

## 🗄️ 4. KV Secrets Engine v2 API

> 기본 마운트 경로가 `secret/`라고 가정합니다.

| 동작 | 메서드 | 경로 | 설명 |
|------|--------|------|------|
| 읽기 | GET | `/v1/secret/data/<path>` | 특정 버전 조회 가능 (`?version=1`) |
| 쓰기 | POST | `/v1/secret/data/<path>` | 새 데이터 또는 새 버전 등록 |
| 수정(Patch) | PATCH | `/v1/secret/data/<path>` | 일부 키만 변경 |
| 삭제(Soft) | DELETE | `/v1/secret/data/<path>` | 특정 버전 삭제 (복구 가능) |
| 복구 | POST | `/v1/secret/undelete/<path>` | 삭제된 버전 복구 |
| 영구 삭제 | POST | `/v1/secret/destroy/<path>` | 복구 불가능한 삭제 |
| 메타데이터 조회 | GET | `/v1/secret/metadata/<path>` | 생성일/버전/삭제 정보 확인 |
| 메타데이터 삭제 | DELETE | `/v1/secret/metadata/<path>` | 전체 버전 + 메타데이터 삭제 |

---

## 🧱 5. 정책 및 권한 (RBAC)

- Vault는 경로 기반 ACL 정책을 사용합니다.
- `capabilities` 키에 따라 `read`, `create`, `update`, `delete`, `list`, `sudo` 등을 지정합니다.

> ✅ 예시: KV v2 시크릿 전체 접근 허용
```hcl
path "secret/data/*" {
  capabilities = ["create", "read", "update", "delete", "list"]
}
```

---

## 📦 6. 실제 운영 가이드

| 항목 | 권장 설정 |
|------|------------|
| 인증 | AppRole 또는 Token |
| 시크릿 저장 | KV v2, 버전 관리 활성화 |
| TLS 보안 | 운영 환경에서는 반드시 HTTPS |
| 봉인 해제 | Auto Unseal (AWS KMS 등 외부 연동), 수동이면 `PUT /sys/unseal` 사용 |
| 모니터링 | `/sys/health` 활용, Prometheus 연동 가능 |
| 키 조각 보관 | 수동 Unseal의 경우 서로 다른 관리자에게 분산 보관 |

---

## 📚 참고 문서

- 공식 API 문서: [https://developer.hashicorp.com/vault/api-docs](https://developer.hashicorp.com/vault/api-docs)
- KV v2 엔진 상세: [https://developer.hashicorp.com/vault/api-docs/secret/kv/kv-v2](https://developer.hashicorp.com/vault/api-docs/secret/kv/kv-v2)
- AppRole 인증: [https://developer.hashicorp.com/vault/docs/auth/approle](https://developer.hashicorp.com/vault/docs/auth/approle)

---
