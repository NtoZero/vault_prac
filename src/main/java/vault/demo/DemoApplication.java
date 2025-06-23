package vault.demo;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.event.ApplicationStartedEvent;
import org.springframework.context.event.EventListener;

@Slf4j
@SpringBootApplication
public class DemoApplication {

    public static void main(String[] args) {
        log.info("Starting Vault Demo Application...");
        SpringApplication.run(DemoApplication.class, args);
    }
    
    @EventListener(ApplicationStartedEvent.class)
    public void onApplicationStarted() {
        log.info("🚀 Vault Demo Application started successfully!");
        log.info("📊 Check configuration: http://localhost:8080/api/config");
        log.info("💚 Health check: http://localhost:8080/api/health");
    }
}
