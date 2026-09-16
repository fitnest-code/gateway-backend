package az.fitnest.gateway.configuration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

@Component
@ConfigurationProperties(prefix = "fitnest.rate-limit")
public class RateLimitConfig {

    private Map<String, Integer> limits = new HashMap<>();

    public RateLimitConfig() {
        limits.put("auth.login.post", 5);
        limits.put("auth.verify-otp.post", 5);
        limits.put("auth.request-otp.post", 3);
        limits.put("auth.resend-otp.post", 3);
        limits.put("auth.daily-otp.post", 10);
        limits.put("auth.send-reset-password-link.post", 2);
        limits.put("auth.reset-password.post", 5);

        limits.put("general.write", 60);
        limits.put("general.read", 300);
        limits.put("landing.read", 120);
        limits.put("landing.media.get", 90);
        limits.put("landing.write", 20);
        limits.put("landing.contact-messages.post", 8);
        limits.put("landing.contact-messages.daily", 40);
        limits.put("bmi.calculate.post", 20);
    }

    public int getDailyLimit(String rateLimitKey) {
        if ("landing.contact-messages.post".equals(rateLimitKey)) {
            return limits.getOrDefault("landing.contact-messages.daily", 40);
        }
        return 0;
    }

    public int getLimit(String key) {
        return limits.getOrDefault(key, 300);
    }

    public String getReadCategory(String path) {
        if (path.startsWith("/api/v1/auth/")) {
            return "AUTH_GET";
        } else if (path.startsWith("/api/v1/me/") || path.startsWith("/api/v2/me")) {
            return "ME_GET";
        } else if (path.startsWith("/api/v1/public/landing/media/")) {
            return "LANDING_MEDIA_GET";
        } else if (path.startsWith("/api/v1/public/landing/")) {
            return "LANDING_GET";
        } else if (path.startsWith("/api/v1/media/")) {
            return "MEDIA_GET";
        } else {
            return "GENERAL_GET";
        }
    }

    public String getRateLimitKey(String path, String method) {
        boolean isRead = "GET".equals(method);
        boolean isWrite = Arrays.asList("POST", "PUT", "DELETE", "PATCH").contains(method);

        if (path.startsWith("/api/v1/auth/login") && isWrite) {
            return "auth.login.post";
        } else if (path.startsWith("/api/v1/auth/otp/send") && isWrite) {
            return "auth.request-otp.post";
        } else if (path.startsWith("/api/v1/auth/otp/resend") && isWrite) {
            return "auth.resend-otp.post";
        } else if ((path.startsWith("/api/v1/auth/otp/verify")
                || path.startsWith("/api/v1/auth/forgot-password/verify-otp")) && isWrite) {
            return "auth.verify-otp.post";
        } else if (path.equals("/api/v1/auth/forgot-password") && isWrite) {
            return "auth.send-reset-password-link.post";
        } else if (path.equals("/api/v1/auth/reset-password") && isWrite) {
            return "auth.reset-password.post";
        }

        if (path.contains("/otp/") && isWrite) {
             return "auth.request-otp.post";
        }

        if (path.startsWith("/api/v1/public/landing/contact-messages") && isWrite) {
            return "landing.contact-messages.post";
        }
        if (path.startsWith("/api/v1/public/landing/") && isWrite) {
            return "landing.write";
        }
        if (path.startsWith("/api/v1/bmi/") && isWrite) {
            return "bmi.calculate.post";
        }

        if (isWrite) {
            return "general.write";
        }
        if (path.startsWith("/api/v1/public/landing/media/")) {
            return "landing.media.get";
        }
        if (path.startsWith("/api/v1/public/landing/")) {
            return "landing.read";
        }
        if (isRead) {
            return "general.read";
        }

        return "general.read";
    }

    public Map<String, Integer> getLimits() {
        return limits;
    }

    public void setLimits(Map<String, Integer> limits) {
        this.limits = limits;
    }
}
