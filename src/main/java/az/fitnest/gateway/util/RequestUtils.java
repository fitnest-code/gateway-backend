package az.fitnest.gateway.util;

import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.server.ServerWebExchange;

import java.util.regex.Pattern;

public class RequestUtils {

    private static final Pattern IPV4 = Pattern.compile(
            "^(?:(?:25[0-5]|2[0-4]\\d|[01]?\\d\\d?)\\.){3}(?:25[0-5]|2[0-4]\\d|[01]?\\d\\d?)$");
    private static final Pattern IPV6 = Pattern.compile("^[0-9a-fA-F:]{2,45}$");

    private RequestUtils() {
    }

    public static String extractClientIP(ServerHttpRequest request) {
        String forwarded = firstValidIp(request.getHeaders().getFirst("X-Forwarded-For"));
        if (forwarded != null) {
            return forwarded;
        }

        String realIp = firstValidIp(request.getHeaders().getFirst("X-Real-IP"));
        if (realIp != null) {
            return realIp;
        }

        if (request.getRemoteAddress() != null && request.getRemoteAddress().getAddress() != null) {
            return request.getRemoteAddress().getAddress().getHostAddress();
        }
        return "unknown";
    }

    private static String firstValidIp(String headerValue) {
        if (headerValue == null || headerValue.isBlank()) {
            return null;
        }
        for (String part : headerValue.split(",")) {
            String candidate = part.trim();
            if (isValidIp(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    private static boolean isValidIp(String value) {
        return IPV4.matcher(value).matches() || IPV6.matcher(value).matches();
    }

    public static String extractToken(ServerWebExchange exchange) {
        var accessTokenCookie = exchange.getRequest().getCookies().getFirst("accessToken");
        if (accessTokenCookie != null && !accessTokenCookie.getValue().isEmpty()) {
            return accessTokenCookie.getValue();
        }

        String authHeader = exchange.getRequest().getHeaders().getFirst("Authorization");
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            return authHeader.substring(7);
        }

        return null;
    }

    public static boolean isStateChangingMethod(String method) {
        return "POST".equals(method) || "PUT".equals(method) || "DELETE".equals(method) || "PATCH".equals(method);
    }

    public static String extractCsrfTokenFromCookies(ServerHttpRequest request) {
        var csrfCookie = request.getCookies().getFirst("csrfTokenHttpOnly");
        return csrfCookie != null ? csrfCookie.getValue() : null;
    }

    public static String extractLanguage(ServerWebExchange exchange, String tokenLang) {
        String queryLang = exchange.getRequest().getQueryParams().getFirst("lang");
        if (queryLang != null && !queryLang.isEmpty()) {
            return queryLang.toLowerCase();
        }

        String headerLang = exchange.getRequest().getHeaders().getFirst("Accept-Language");
        if (headerLang != null && !headerLang.isEmpty()) {
            return headerLang.split(",")[0].split("-")[0].split(";")[0].trim().toLowerCase();
        }

        if (tokenLang != null && !tokenLang.isEmpty()) {
            return tokenLang.toLowerCase();
        }

        return "az";
    }
}
