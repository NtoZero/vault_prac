# Vault + Spring Boot 간단한 예제

HashiCorp Vault에서 키-값을 읽어서 Spring Boot 애플리케이션을 구성하는 예제입니다.

## 실행 방법

### 1. Vault 및 MySQL 시작
```bash
docker-compose up -d
```

### 2. Vault 설정 (시크릿 저장)
```bash
# 스크립트 실행 권한 부여
chmod +x scripts/setup-vault.sh

# Vault 초기화 및 시크릿 설정
./scripts/setup-vault.sh
```

### 3. Spring Boot 애플리케이션 실행
```bash
# Vault 토큰 환경변수 설정
export VAULT_TOKEN=myroot

# 애플리케이션 실행
./gradlew bootRun
```

## 확인 방법

### 1. 설정값 확인
```bash
curl http://localhost:8080/api/config
```

예상 응답:
```json
{
  "app": {
    "name": "Vault Demo Application",
    "version": "2.0.0", 
    "message": "Hello from HashiCorp Vault!"
  },
  "database": {
    "username": "demo_user",
    "password": "***",
    "url": "jdbc:mysql://mysql:3306/demo?useSSL=false&characterEncoding=UTF-8"
  }
}
```

### 2. 헬스 체크
```bash
curl http://localhost:8080/api/health
```

### 3. Spring Actuator 엔드포인트
```bash
# 환경변수 확인
curl http://localhost:8080/actuator/env

# 설정 속성 확인  
curl http://localhost:8080/actuator/configprops
```

## Vault에서 시크릿 변경

```bash
# 새로운 값으로 업데이트
vault kv put secret/demo/config \
  app.message="Updated message from Vault!"

# 애플리케이션 재시작 후 변경된 값 확인
```

## 주요 구성 요소

- **application.yml**: Vault 연결 설정 및 플레이스홀더 정의 (Spring Boot 2.4+ 권장 방식)
- **AppConfig, DatabaseConfig**: @ConfigurationProperties로 설정값 매핑
- **ConfigController**: 설정값을 REST API로 확인

## 보안 고려사항

- 개발환경에서는 `-dev` 모드를 사용하지만, 운영환경에서는 적절한 인증/인가 설정 필요
- Vault 토큰은 환경변수나 Kubernetes Secret 등으로 안전하게 관리
- TLS 설정으로 네트워크 통신 암호화 권장
