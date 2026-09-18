package ch.bzz.m321.chatservice.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.SecurityFilterChain;

/**
 * REST-Endpunkte (/api/**) verlangen ein gültiges JWT von Keycloak (Realm "chat").
 * /ws wird ohne HTTP-Auth durchgelassen, da der Browser beim WebSocket-Handshake
 * keinen Authorization-Header mitschicken kann; die Authentifizierung erfolgt
 * dort per erster Nachricht, siehe ChatWebSocketHandler.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/ws/**", "/actuator/health", "/actuator/info").permitAll()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(Customizer.withDefaults()));
        return http.build();
    }
}
