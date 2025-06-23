package vault.demo.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class VaultConfigurationLogger {
    
    private final Environment environment;
    
    @Value("${spring.cloud.vault.uri:N/A}")
    private String vaultUri;
    
    @Value("${spring.cloud.vault.kv.backend:secret}")
    private String vaultBackend;
    
    @Value("${spring.config.import:N/A}")
    private String configImport;
    
    @Value("${spring.application.name:unknown}")
    private String applicationName;
    
    public VaultConfigurationLogger(Environment environment) {
        this.environment = environment;
    }
    
    @EventListener(ApplicationReadyEvent.class)
    public void logVaultConfiguration() {
        log.info("========== Vault Configuration Details ==========");
        log.info("🏪 Vault URI: {}", vaultUri);
        log.info("📂 Vault KV Backend: {}", vaultBackend);
        log.info("📋 Config Import: {}", configImport);
        log.info("📱 Application Name: {}", applicationName);
        
        // Vault 경로 분석 및 로깅
        logVaultPaths();
        
        // 실제 property 소스 정보
        logPropertySources();
        
        log.info("✅ Application successfully loaded configuration from Vault!");
        log.info("===============================================");
    }
    
    private void logVaultPaths() {
        log.info("📍 Vault Secret Paths:");
        
        // config.import에서 경로 추출
        if (configImport != null && configImport.startsWith("vault://")) {
            String secretPath = configImport.replace("vault://", "");
            log.info("  → Primary Path: {}", secretPath);
            log.info("  → Full Vault Path: {}/{}", vaultBackend, secretPath);
            log.info("  → API Endpoint: {}/v1/{}/data/{}", vaultUri, vaultBackend, secretPath);
        }
        
        // 일반적인 Spring Cloud Vault 경로들도 로깅
        String appName = applicationName.replace("-", "/");
        log.info("  → Application-based paths:");
        log.info("    • {}/data/{}", vaultBackend, appName);
        log.info("    • {}/data/application", vaultBackend);
        
        // 프로파일이 있다면 추가 경로
        String[] activeProfiles = environment.getActiveProfiles();
        if (activeProfiles.length > 0) {
            log.info("  → Profile-based paths:");
            for (String profile : activeProfiles) {
                log.info("    • {}/data/{},{}", vaultBackend, appName, profile);
                log.info("    • {}/data/application,{}", vaultBackend, profile);
            }
        }
    }
    
    private void logPropertySources() {
        log.info("🔍 Property Sources Analysis:");
        
        // 특정 키들이 어느 소스에서 왔는지 확인
        checkPropertySource("app.name");
        checkPropertySource("app.version");
        checkPropertySource("app.message");
        checkPropertySource("database.username");
        checkPropertySource("database.url");
    }
    
    private void checkPropertySource(String propertyKey) {
        try {
            String value = environment.getProperty(propertyKey);
            if (value != null) {
                // PropertySource 이름에서 Vault 관련 정보 추출
                org.springframework.core.env.PropertySource<?> propertySource = 
                    ((org.springframework.core.env.AbstractEnvironment) environment)
                    .getPropertySources()
                    .stream()
                    .filter(ps -> ps.getProperty(propertyKey) != null)
                    .findFirst()
                    .orElse(null);
                
                if (propertySource != null) {
                    String sourceName = propertySource.getName();
                    if (sourceName.contains("vault")) {
                        log.info("  ✓ {}: '{}' (from: {})", propertyKey, 
                               propertyKey.contains("password") ? "***" : value, 
                               sourceName);
                    } else {
                        log.info("  ⚠ {}: '{}' (from: {} - NOT from Vault)", propertyKey, 
                               propertyKey.contains("password") ? "***" : value, 
                               sourceName);
                    }
                }
            }
        } catch (Exception e) {
            log.debug("Could not analyze property source for {}: {}", propertyKey, e.getMessage());
        }
    }
}
