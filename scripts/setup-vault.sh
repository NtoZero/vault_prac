#!/bin/bash

# Vault 설정
export VAULT_ADDR='http://localhost:8200'
export VAULT_TOKEN='myroot'

echo "=== Vault 초기화 및 시크릿 설정 ==="

# KV v2 시크릿 엔진 활성화
echo "1. KV v2 시크릿 엔진 활성화..."
vault secrets enable -path=secret kv-v2

# 애플리케이션 설정값 저장
echo "2. 애플리케이션 설정값 저장..."
vault kv put secret/demo/config \
  app.name="Vault Demo Application" \
  app.version="2.0.0" \
  app.message="Hello from HashiCorp Vault!" \
  database.username="demo_user" \
  database.password="demo_pass" \
  database.url="jdbc:mysql://mysql:3306/demo?useSSL=false&characterEncoding=UTF-8"

# 저장된 시크릿 확인
echo "3. 저장된 시크릿 확인..."
vault kv get secret/demo/config

echo "=== Vault 설정 완료 ==="
echo "Spring Boot 애플리케이션을 시작할 수 있습니다."
echo "환경변수: VAULT_TOKEN=myroot"
