package vault.demo.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.vault.core.VaultTemplate;
import org.springframework.vault.support.VaultResponse;

@Slf4j
@Component
public class VaultHealthLogger implements HealthIndicator {
    
    @Autowired(required = false)
    private VaultTemplate vaultTemplate;
    
    @EventListener(ApplicationReadyEvent.class)
    public void testVaultConnection() {
        log.info("🔍 Testing Vault Connection and Secret Access...");
        
        if (vaultTemplate == null) {
            log.warn("⚠️ VaultTemplate is not available - Vault connection may not be configured");
            return;
        }
        
        try {
            // Vault 연결 테스트
            log.info("📡 Testing Vault server connection...");
            
            // secret/demo/config 경로에서 데이터 읽기 테스트
            String secretPath = "secret/data/demo/config";
            log.info("🔑 Attempting to read from path: {}", secretPath);
            
            VaultResponse response = vaultTemplate.read(secretPath);
            
            if (response != null && response.getData() != null) {
                log.info("✅ Successfully connected to Vault!");
                log.info("📊 Secret metadata:");
                log.info("  → Path: {}", secretPath);
                log.info("  → Keys available: {}", response.getData().keySet());
                log.info("  → Total properties: {}", response.getData().size());
                
                // 각 키의 값 로깅 (패스워드는 마스킹)
                response.getData().forEach((key, value) -> {
                    if (key.toString().toLowerCase().contains("password")) {
                        log.info("  → {}: ***", key);
                    } else {
                        log.info("  → {}: {}", key, value);
                    }
                });
                
            } else {
                log.warn("⚠️ Vault responded but no data found at path: {}", secretPath);
            }
            
        } catch (Exception e) {
            log.error("❌ Failed to connect to Vault or read secrets: {}", e.getMessage());
            log.debug("Vault connection error details:", e);
        }
    }
    
    @Override
    public Health health() {
        if (vaultTemplate == null) {
            return Health.down()
                    .withDetail("reason", "VaultTemplate not available")
                    .build();
        }
        
        try {
            VaultResponse response = vaultTemplate.read("secret/data/demo/config");
            if (response != null) {
                return Health.up()
                        .withDetail("vault-path", "secret/data/demo/config")
                        .withDetail("secrets-count", response.getData() != null ? response.getData().size() : 0)
                        .build();
            } else {
                return Health.down()
                        .withDetail("reason", "No response from Vault")
                        .build();
            }
        } catch (Exception e) {
            return Health.down()
                    .withDetail("reason", e.getMessage())
                    .build();
        }
    }
}
