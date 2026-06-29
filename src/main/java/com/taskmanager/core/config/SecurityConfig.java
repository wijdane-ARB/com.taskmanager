package com.taskmanager.core.config;

import com.taskmanager.core.security.JwtAuthenticationFilter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

/**
 * Changes from the original:
 *
 * Added CORS configuration.
 *
 * Without it the browser's preflight OPTIONS request receives no
 * Access-Control-Allow-Origin header, so every fetch from a web app
 * or Flutter WebView is blocked before it reaches the JWT filter.
 * Spring Security rejects the preflight at the filter chain level —
 * the 401 response doesn't include CORS headers, so the browser
 * reports a CORS error, not an auth error, making this hard to debug.
 *
 * Allowed origins are injected from application properties so they
 * can differ between dev (localhost:*) and prod (your domain) without
 * a code change. The JWT filter is stateless so no credentials/cookies
 * need to be allowed.
 *
 * Allowed headers explicitly include Authorization so Bearer tokens
 * sent from the Flutter WebView aren't stripped by the browser.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthFilter;

    /**
     * Comma-separated list of allowed origins.
     * In application.yml / environment:
     *   cors.allowed-origins: http://localhost:3000,https://app.yourdomain.com
     */
    @Value("${cors.allowed-origins:http://localhost:3000,http://localhost:8080}")
    private List<String> allowedOrigins;

    public SecurityConfig(JwtAuthenticationFilter jwtAuthFilter) {
        this.jwtAuthFilter = jwtAuthFilter;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS)
                )
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.POST, "/api/v1/auth/register").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/auth/login").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/auth/refresh").permitAll()
                        // Preflight requests must be permitted or they hit the JWT filter and fail
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .anyRequest().authenticated()
                )
                .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();

        config.setAllowedOrigins(allowedOrigins);

        // Standard REST verbs + OPTIONS for preflight
        config.setAllowedMethods(List.of("GET", "POST", "PATCH", "PUT", "DELETE", "OPTIONS"));

        // Authorization must be explicitly listed — it is not a CORS-safelisted header.
        // Content-Type is needed for JSON request bodies.
        config.setAllowedHeaders(List.of("Authorization", "Content-Type", "Accept"));

        // Expose no extra response headers to the browser — least privilege.
        config.setExposedHeaders(List.of());

        // No cookies or credentials — we're stateless JWT only
        config.setAllowCredentials(false);

        // Cache preflight response for 1 hour (3600 s) — reduces OPTIONS round-trips
        config.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", config);
        return source;
    }

    /**
     * BCrypt cost 12 — ~4× more work than the default (10) for attackers,
     * still ~300 ms on a modern CPU which is acceptable for a login endpoint.
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }

    @Bean
    public AuthenticationManager authenticationManager(
            AuthenticationConfiguration config
    ) throws Exception {
        return config.getAuthenticationManager();
    }
}