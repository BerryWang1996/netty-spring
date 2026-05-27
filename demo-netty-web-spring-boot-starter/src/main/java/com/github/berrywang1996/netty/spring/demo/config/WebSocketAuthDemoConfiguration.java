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

    /** Hardcoded demo tokens — replace with a real token service in production. */
    private static final Set<String> VALID_TOKENS = ConcurrentHashMap.newKeySet();

    static {
        VALID_TOKENS.add("demo-token-2026");
        VALID_TOKENS.add("test-secret");
    }

    @Bean
    public WebSocketHandshakeInterceptor demoTokenInterceptor() {
        return new WebSocketHandshakeInterceptor() {

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

            @Override
            public String rejectionReason() {
                return "Missing or invalid token. Pass ?token=<valid-token> in the WebSocket URL.";
            }

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

            private String maskToken(String token) {
                if (token.length() <= 4) {
                    return "****";
                }
                return token.substring(0, 4) + "****";
            }
        };
    }
}
