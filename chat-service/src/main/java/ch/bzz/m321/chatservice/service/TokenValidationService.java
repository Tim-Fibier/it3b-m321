package ch.bzz.m321.chatservice.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.stereotype.Service;

/**
 * Validiert JWT-Tokens gegen die JWKS von Keycloak (wiederverwendet den
 * JwtDecoder, den Spring Security für den Resource Server autokonfiguriert).
 * Wird für die WebSocket-Authentifizierung benötigt, da der Browser beim
 * WebSocket-Handshake keinen Authorization-Header mitschicken kann.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TokenValidationService {

    private final JwtDecoder jwtDecoder;

    public boolean isTokenValid(String token) {
        return decode(token) != null;
    }

    /**
     * Extrahiert die Benutzer-ID (bevorzugt "preferred_username", sonst "sub")
     * aus einem gültigen Token. Gibt null zurück, wenn das Token ungültig ist.
     */
    public String extractUserId(String token) {
        Jwt jwt = decode(token);
        if (jwt == null) {
            return null;
        }
        String preferredUsername = jwt.getClaimAsString("preferred_username");
        return preferredUsername != null ? preferredUsername : jwt.getSubject();
    }

    private Jwt decode(String token) {
        if (token == null || token.isBlank()) {
            return null;
        }
        try {
            return jwtDecoder.decode(token);
        } catch (JwtException e) {
            log.warn("Token-Validierung fehlgeschlagen: {}", e.getMessage());
            return null;
        }
    }
}
