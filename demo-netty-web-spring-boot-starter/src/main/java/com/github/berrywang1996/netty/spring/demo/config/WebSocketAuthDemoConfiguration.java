package com.github.berrywang1996.netty.spring.demo.config;

import com.github.berrywang1996.netty.spring.web.websocket.context.WebSocketHandshakeInterceptor;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.QueryStringDecoder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Demo configuration that registers a {@link WebSocketHandshakeInterceptor} for
 * token-based WebSocket authentication.
 *
 * <p>Activate with {@code --spring.profiles.active=auth-demo}.
 *
 * <p>The interceptor validates a {@code token} query parameter against a hardcoded
 * set of accepted tokens. In a real application this would delegate to a JWT
 * library, OAuth2 introspection endpoint, or session store.
 *
 * <p>Example connection URL:
 * <pre>ws://localhost:8080/ws/test?token=demo-token-2026</pre>
 *
 * @author berrywang1996
 * @since V1.3.0
 */
@Slf4j
@Configuration
@Profile("auth-demo")
public class WebSocketAuthDemoConfiguration {

    /**
     * Hardcoded set of valid demo tokens. In a production application this
     * would be replaced by a real token service (JWT validation, OAuth2
     * introspection, session store lookup, etc.).
     */
    private static final Set<String> VALID_TOKENS = ConcurrentHashMap.newKeySet();

    static {
        VALID_TOKENS.add("demo-token-2026");
        VALID_TOKENS.add("test-secret");
    }

    /**
     * Registers a {@link WebSocketHandshakeInterceptor} bean that validates
     * an authentication token before allowing a WebSocket handshake to proceed.
     *
     * <p>The token is extracted from either:
     * <ol>
     *   <li>The {@code token} query parameter in the WebSocket URL</li>
     *   <li>The {@code Authorization: Bearer <token>} HTTP header</li>
     * </ol>
     *
     * <p>If the token is missing or not in the {@link #VALID_TOKENS} set, the
     * handshake is rejected and the client receives the
     * {@link WebSocketHandshakeInterceptor#rejectionReason()} message.
     *
     * @return a new {@link WebSocketHandshakeInterceptor} that enforces token validation
     */
    @Bean
    public WebSocketHandshakeInterceptor demoTokenInterceptor() {
        return new WebSocketHandshakeInterceptor() {

            /**
             * Validates the token extracted from the incoming handshake request.
             * Rejects the handshake if the token is missing or invalid.
             */
            @Override
            public boolean beforeHandshake(FullHttpRequest request, String uri) {
                String token = extractToken(request);
                if (token == null || token.isEmpty()) {
                    log.warn("[auth-demo] WebSocket handshake rejected: missing token. uri={}", uri);
                    return false;
                }
                if (!VALID_TOKENS.contains(token)) {
                    log.warn("[auth-demo] WebSocket handshake rejected: invalid token. uri={}, token={}",
                            uri, maskToken(token));
                    return false;
                }
                log.info("[auth-demo] WebSocket handshake allowed. uri={}, token={}", uri, maskToken(token));
                return true;
            }

            /** Returns a human-readable reason when a handshake is rejected. */
            @Override
            public String rejectionReason() {
                return "Missing or invalid token. Pass ?token=<valid-token> in the WebSocket URL.";
            }

            /**
             * Extracts the authentication token from the request, checking the
             * query parameter first and falling back to the Authorization header.
             *
             * @param request the full HTTP request initiating the WebSocket upgrade
             * @return the extracted token string, or {@code null} if not found
             */
            private String extractToken(FullHttpRequest request) {
                // Try query parameter first
                QueryStringDecoder decoder = new QueryStringDecoder(request.uri());
                Map<String, List<String>> params = decoder.parameters();
                List<String> tokenValues = params.get("token");
                if (tokenValues != null && !tokenValues.isEmpty()) {
                    return tokenValues.get(0);
                }
                // Fallback: check Authorization header (Bearer scheme)
                String authHeader = request.headers().get("Authorization");
                if (authHeader != null && authHeader.startsWith("Bearer ")) {
                    return authHeader.substring(7).trim();
                }
                return null;
            }

            /**
             * Masks a token for safe logging by keeping only the first 4
             * characters and replacing the rest with asterisks.
             *
             * @param token the raw token string
             * @return the masked token (e.g., {@code "demo****"})
             */
            private String maskToken(String token) {
                if (token.length() <= 4) {
                    return "****";
                }
                return token.substring(0, 4) + "****";
            }
        };
    }
}
