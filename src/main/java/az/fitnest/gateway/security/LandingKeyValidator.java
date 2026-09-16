package az.fitnest.gateway.security;

import az.fitnest.gateway.configuration.LandingKeyConfig;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;

@Component
public class LandingKeyValidator {

    public static final String HEADER_NAME = "X-FitNest-Landing-Key";
    private static final int MAX_KEY_LENGTH = 256;

    private final LandingKeyConfig config;

    public LandingKeyValidator(LandingKeyConfig config) {
        this.config = config;
    }

    public static boolean isProtectedLandingPath(String path, String method) {
        if (path == null || !path.startsWith("/api/v1/public/landing/")) {
            return false;
        }
        return !(isRead(method) && path.startsWith("/api/v1/public/landing/media/"));
    }

    public boolean isAllowed(ServerWebExchange exchange) {
        ServerHttpRequest request = exchange.getRequest();
        String path = request.getPath().value();
        String method = request.getMethod() == null ? "" : request.getMethod().name();

        if (!isProtectedLandingPath(path, method) || !config.isEnforced()) {
            return true;
        }

        if (hasKeyInQuery(request)) {
            return false;
        }

        List<String> values = request.getHeaders().get(HEADER_NAME);
        if (values == null || values.size() != 1) {
            return false;
        }

        String provided = values.get(0);
        if (provided == null
                || provided.length() > MAX_KEY_LENGTH
                || provided.indexOf('\r') >= 0
                || provided.indexOf('\n') >= 0) {
            return false;
        }

        return constantTimeEquals(config.getApiKey(), provided);
    }

    public ServerWebExchange stripKey(ServerWebExchange exchange) {
        return exchange.mutate()
                .request(exchange.getRequest().mutate()
                        .headers(headers -> headers.remove(HEADER_NAME))
                        .build())
                .build();
    }

    private static boolean isRead(String method) {
        return "GET".equalsIgnoreCase(method) || "HEAD".equalsIgnoreCase(method);
    }

    private static boolean hasKeyInQuery(ServerHttpRequest request) {
        return request.getQueryParams().keySet().stream()
                .anyMatch(name -> HEADER_NAME.equalsIgnoreCase(name));
    }

    private static boolean constantTimeEquals(String expected, String provided) {
        byte[] left = expected.getBytes(StandardCharsets.UTF_8);
        byte[] right = provided.getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(left, right);
    }
}
