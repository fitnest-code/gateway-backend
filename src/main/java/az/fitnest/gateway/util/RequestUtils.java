package az.fitnest.gateway.util;

import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.server.ServerWebExchange;

import java.net.Inet6Address;
import java.net.InetAddress;

public class RequestUtils {

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

    public static String forwardedForHeader(ServerHttpRequest request) {
        String value = request.getHeaders().getFirst("X-Forwarded-For");
        return value == null || value.isBlank() ? "none" : value;
    }

    private static String firstValidIp(String headerValue) {
        if (headerValue == null || headerValue.isBlank()) {
            return null;
        }
        for (String part : headerValue.split(",")) {
            String candidate = part.trim();
            if (isLiteralAddress(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    /**
     * Literal-only check: never triggers a DNS lookup for junk header values.
     */
    private static boolean isLiteralAddress(String value) {
        try {
            InetAddress.ofLiteral(value);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /**
     * Whether an address may be used as a blocking identifier.
     *
     * <p>Loopback, link-local, site-local (RFC1918) and unique-local addresses are shared by
     * design — behind the ingress every request can appear to originate from the same node
     * address. Blocking one of those would lock out every user behind that hop, so such
     * addresses are never blocked.</p>
     */
    public static boolean isBlockableAddress(String ip) {
        if (ip == null || ip.isBlank()) {
            return false;
        }
        try {
            InetAddress address = InetAddress.ofLiteral(ip);
            return !(address.isAnyLocalAddress()
                    || address.isLoopbackAddress()
                    || address.isLinkLocalAddress()
                    || address.isSiteLocalAddress()
                    || address.isMulticastAddress()
                    || isUniqueLocalIpv6(address));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static boolean isUniqueLocalIpv6(InetAddress address) {
        if (!(address instanceof Inet6Address)) {
            return false;
        }
        return (address.getAddress()[0] & 0xfe) == 0xfc;
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
