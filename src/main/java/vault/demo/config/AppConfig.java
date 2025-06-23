package vault.demo.config;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;

@Slf4j
@Data
@Component
@ConfigurationProperties(prefix = "app")
public class AppConfig {
    private String name;
    private String version;
    private String message;
    
    @PostConstruct
    public void logAppConfig() {
        log.info("========== App Configuration from Vault ==========");
        log.info("App Name: {}", name);
        log.info("App Version: {}", version);
        log.info("App Message: {}", message);
        log.info("==================================================");
    }
}
