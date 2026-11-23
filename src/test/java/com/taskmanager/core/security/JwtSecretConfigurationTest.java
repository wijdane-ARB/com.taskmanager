package com.taskmanager.core.security;

import io.jsonwebtoken.security.WeakKeyException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.PropertyPlaceholderAutoConfiguration;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class JwtSecretConfigurationTest {
    @ParameterizedTest
    @ValueSource(strings = {"application.properties", "application.yml"})
    void missingSecretPreventsStartup(String configFile) {
        contextWithSecret(null)
                .withPropertyValues("spring.config.location=classpath:/" + configFile)
                .run(context -> assertThat(context.getStartupFailure())
                        .rootCause().hasMessageContaining("Could not resolve placeholder 'JWT_SECRET'"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   "})
    void blankSecretPreventsStartup(String secret) {
        contextWithSecret(secret).run(context -> assertThat(context.getStartupFailure())
                .hasRootCauseMessage("JWT_SECRET must not be blank."));
    }

    @ParameterizedTest
    @ValueSource(strings = {"default", "dev", "prod"})
    void oldDevelopmentKeyPreventsStartup(String profile) {
        contextWithSecret("your-very-long-dev-secret-key-at-least-32-chars")
                .withPropertyValues("spring.profiles.active=" + profile)
                .run(context -> assertThat(context.getStartupFailure())
                        .hasRootCauseMessage("JWT_SECRET must not use the old development key."));
    }

    @Test
    void shortSecretPreventsStartup() {
        contextWithSecret("too-short").run(context -> assertThat(context.getStartupFailure())
                .hasRootCauseInstanceOf(WeakKeyException.class));
    }

    @Test
    void ciSecretStartsAndSignsTokens() {
        contextWithSecret("very-long-test-secret-key-at-least-32-chars-for-test").run(context -> {
            assertThat(context).hasSingleBean(JwtService.class);
            JwtService jwtService = context.getBean(JwtService.class);
            UUID userId = UUID.randomUUID();
            assertThat(jwtService.extractUserId(jwtService.generateAccessToken(userId))).isEqualTo(userId);
        });
    }

    private ApplicationContextRunner contextWithSecret(String secret) {
        return new ApplicationContextRunner()
                .withInitializer(context -> {
                    var sources = context.getEnvironment().getPropertySources();
                    sources.remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
                    sources.remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
                    if (secret != null) {
                        sources.addFirst(new MapPropertySource("testJwtSecret", Map.of("JWT_SECRET", secret)));
                    }
                    new ConfigDataApplicationContextInitializer().initialize(context);
                })
                .withConfiguration(AutoConfigurations.of(PropertyPlaceholderAutoConfiguration.class))
                .withUserConfiguration(JwtService.class);
    }
}
