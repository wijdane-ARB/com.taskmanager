package com.taskmanager.core.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import jakarta.annotation.PostConstruct;
import java.util.List;

@Configuration
@Profile("prod")
public class ProdSecurityValidator {

    @Value("${app.security.code-hmac-secret}")
    private String codeHmacSecret;

    @Value("${cors.allowed-origins}")
    private List<String> corsOrigins;

    @PostConstruct
    public void validate() {
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
