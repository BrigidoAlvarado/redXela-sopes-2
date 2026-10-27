package gt.edu.usac.cunoc.redxela.api;

import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

@Configuration
@EnableScheduling
@EnableWebSocketMessageBroker
public class ComunicacionConfig implements WebSocketMessageBrokerConfigurer {
    private static final String[] ORIGENES = {"http://localhost:4200", "http://127.0.0.1:4200"};
    @Configuration
    static class CorsConfig implements WebMvcConfigurer {
        @Override public void addCorsMappings(CorsRegistry registry) {
            registry.addMapping("/api/**").allowedOrigins(ORIGENES).allowedMethods("GET", "POST", "PUT", "OPTIONS");
        }
    }
    @Override public void registerStompEndpoints(StompEndpointRegistry registry) { registry.addEndpoint("/ws").setAllowedOrigins(ORIGENES); }
    @Override public void configureMessageBroker(MessageBrokerRegistry registry) { registry.enableSimpleBroker("/topic"); }
}
