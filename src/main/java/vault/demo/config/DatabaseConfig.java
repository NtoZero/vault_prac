package vault.demo.config;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;

@Slf4j
@Data
@Component
@ConfigurationProperties(prefix = "database")
public class DatabaseConfig {
    private String username;
    private String password;
    private String url;
    
    @PostConstruct
    public void logDatabaseConfig() {
        log.info("========== Database Configuration from Vault ==========");
        log.info("Database Username: {}", username);
        log.info("Database Password: {}", maskPassword(password));
        log.info("Database URL: {}", url);
        log.info("=======================================================");
    }
    
    private String maskPassword(String password) {
        if (password == null || password.isEmpty()) {
            return "***";
        }
        if (password.length() <= 4) {
            return "*".repeat(password.length());
        }
        return password.substring(0, 2) + "*".repeat(password.length() - 4) + password.substring(password.length() - 2);
    }
}
