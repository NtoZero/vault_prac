package vault.demo.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import vault.demo.config.AppConfig;
import vault.demo.config.DatabaseConfig;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class ConfigController {
    
    private final AppConfig appConfig;
    private final DatabaseConfig databaseConfig;

    @GetMapping("/config")
    public Map<String, Object> getConfig() {
        Map<String, Object> config = new HashMap<>();
        
        Map<String, String> app = new HashMap<>();
        app.put("name", appConfig.getName());
        app.put("version", appConfig.getVersion());
        app.put("message", appConfig.getMessage());
        
        Map<String, String> database = new HashMap<>();
        database.put("username", databaseConfig.getUsername());
        database.put("password", "***");  // 보안상 마스킹
        database.put("url", databaseConfig.getUrl());
        
        config.put("app", app);
        config.put("database", database);
        
        return config;
    }

    @GetMapping("/health")
    public Map<String, String> health() {
        Map<String, String> status = new HashMap<>();
        status.put("status", "UP");
        status.put("message", appConfig.getMessage());
        return status;
    }
}
