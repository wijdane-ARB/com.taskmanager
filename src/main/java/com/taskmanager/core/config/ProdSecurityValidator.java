package com.taskmanager.core.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import jakarta.annotation.PostConstruct;
import java.util.List;

@Configuration
@Profile("prod")
public class ProdSecurityValidator {

    @Value("${jwt.secret}")
    private String jwtSecret;

    @Value("${app.security.code-hmac-secret}")
    private String codeHmacSecret;

    @Value("${cors.allowed-origins}")
    private List<String> corsOrigins;

    @PostConstruct
    public void validate() {
        if ("your-very-long-dev-secret-key-at-least-32-chars".equals(jwtSecret)) {
            throw new IllegalStateException("FATAL: Default DEV jwt.secret used in PROD profile!");
        }
        if ("dev-only-change-me-code-hmac-secret-0123456789".equals(codeHmacSecret)) {
            throw new IllegalStateException("FATAL: Default DEV code-hmac-secret used in PROD profile!");
        }
        if (corsOrigins != null) {
            for (String origin : corsOrigins) {
                if (origin != null && origin.toLowerCase().contains("localhost")) {
                    throw new IllegalStateException("FATAL: CORS allowed-origins contains localhost in PROD profile!");
                }
            }
        }
    }
}
