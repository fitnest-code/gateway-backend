package az.fitnest.gateway.security;

import java.util.Set;

public class PathValidator {

    private static final Set<String> CSRF_EXEMPTED_PATHS = Set.of();

    private static final Set<String> AUTH_REQUIRED_PATHS = Set.of(
            "/payment/abb/init",
            "/payment/abb/installment",
            "/payment/bob/init",
            "/payment/bob/pay-with-card"
    );

    private static final Set<String> AUTH_EXEMPT_PATHS = Set.of(
            "/api/v1/auth/password-recovery/admin/forgot-password",
            "/api/v1/auth/password-recovery/admin/reset-password",
            "/.well-known/jwks.json",
            "/payment/.well-known/jwks.json"
    );

    private PathValidator() {
    }

    public static boolean requiresAuthForContent(String path) {

        return path.contains("/admin/") || path.endsWith("/admin");
    }

    public static boolean isCsrfExempted(String path) {
        return CSRF_EXEMPTED_PATHS.contains(path);
    }

    public static boolean requiresAuth(String path) {
        if (AUTH_EXEMPT_PATHS.contains(path)) {
            return false;
        }

        if (path.startsWith("/api/v1/goals/images/")) {
            return false;
        }

        if (path.contains("/admin/") || path.endsWith("/admin") ||
                path.startsWith("/api/v1/me") || path.startsWith("/api/v2/me") ||
                path.startsWith("/api/v3/me") ||
                path.startsWith("/api/v3/subscription-packages") ||
                path.startsWith("/api/v1/internal/")) {
            return true;
        }

        return AUTH_REQUIRED_PATHS.contains(path) ||
                path.startsWith("/api/v1/media/upload") ||
                path.startsWith("/api/v1/media/delete") ||
                path.startsWith("/api/v1/media/move");
    }

    public static boolean isAdminRoute(String path) {

        return path.startsWith("/api/v1/admin/") || path.contains("/admin");
    }

    public static boolean startsWithApiV1(String path) {
        return path.startsWith("/api/v1/");
    }
}
