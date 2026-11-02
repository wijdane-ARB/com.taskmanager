// PATH: src/main/java/com/taskmanager/core/config/WebSocketConfig.java
package com.taskmanager.core.config;

import com.taskmanager.core.security.JwtChannelInterceptor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    private final JwtChannelInterceptor jwtChannelInterceptor;
    
    @Value("${cors.allowed-origins:http://localhost:3000,http://127.0.0.1:3000,http://localhost:8080}")
    private List<String> allowedOrigins;

    public WebSocketConfig(JwtChannelInterceptor jwtChannelInterceptor) {
        this.jwtChannelInterceptor = jwtChannelInterceptor;
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        // Raw WebSocket endpoint for mobile & web clients
        String[] origins = allowedOrigins != null && !allowedOrigins.isEmpty()
                ? allowedOrigins.toArray(new String[0])
                : new String[]{"http://localhost:*", "http://127.0.0.1:*", "http://10.0.2.2:*"};
                
        registry.addEndpoint("/ws")
                .setAllowedOriginPatterns(origins);
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        // Clients subscribe to paths starting with /topic or /queue
        registry.enableSimpleBroker("/topic", "/queue");
        // Messages sent from clients to server start with /app
        registry.setApplicationDestinationPrefixes("/app");
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(jwtChannelInterceptor);
    }
}