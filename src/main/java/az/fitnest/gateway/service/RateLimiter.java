package az.fitnest.gateway.service;

import az.fitnest.gateway.configuration.RateLimitConfig;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Arrays;
import java.util.Collections;

@Component
public class RateLimiter {

    private static final RedisScript<Long> RATE_LIMIT_SCRIPT = RedisScript.of(
            "local key = KEYS[1] " +
                    "local limit = tonumber(ARGV[1]) " +
                    "local window = tonumber(ARGV[2]) " +
                    "local current = redis.call('INCR', key) " +
                    "if current == 1 then " +
                    "    redis.call('PEXPIRE', key, window) " +
                    "end " +
                    "if current <= limit then " +
                    "    return current " +
                    "else " +
                    "    return -1 " +
                    "end",
            Long.class
    );
    private static final Duration RATE_WINDOW = Duration.ofMinutes(1);
    private static final Duration DAILY_WINDOW = Duration.ofDays(1);
    private static final java.util.regex.Pattern ID_PATTERN = java.util.regex.Pattern.compile("/\\d+");
    private static final java.util.regex.Pattern UUID_PATTERN = java.util.regex.Pattern.compile("/[a-f0-9]{24}");
    private final ReactiveRedisTemplate<String, String> redisTemplate;
    private final RateLimitConfig rateLimitConfig;

    public RateLimiter(ReactiveRedisTemplate<String, String> redisTemplate, RateLimitConfig rateLimitConfig) {
        this.redisTemplate = redisTemplate;
        this.rateLimitConfig = rateLimitConfig;
    }

    public Mono<Long> checkRateLimit(String identifier, String path, String method) {
        boolean isRead = "GET".equals(method);
        String key;
        String rateLimitKey = rateLimitConfig.getRateLimitKey(path, method);
        int limit = rateLimitConfig.getLimit(rateLimitKey);
        long window = RATE_WINDOW.toMillis();

        if (isRead) {
            String category = rateLimitConfig.getReadCategory(path);
            key = "ratelimit:get:" + identifier + ":" + category;
        } else {
            String normalizedPath = normalizePathForRateLimit(path);
            key = "ratelimit:write:" + identifier + ":" + normalizedPath;
        }

        Mono<Long> minute = execute(key, limit, window);
        int dailyLimit = rateLimitConfig.getDailyLimit(rateLimitKey);
        if (dailyLimit <= 0) {
            return minute;
        }

        String dailyKey = "ratelimit:daily:" + identifier + ":" + rateLimitKey;
        return minute.flatMap(result -> {
            if (result == -1) {
                return Mono.just(-1L);
            }
            return execute(dailyKey, dailyLimit, DAILY_WINDOW.toMillis());
        });
    }

    private Mono<Long> execute(String key, int limit, long windowMs) {
        return redisTemplate.execute(RATE_LIMIT_SCRIPT, Collections.singletonList(key),
                        Arrays.asList(String.valueOf(limit), String.valueOf(windowMs)))
                .next()
                .defaultIfEmpty(0L);
    }

    private String normalizePathForRateLimit(String path) {
        if (path.startsWith("/api/v1/auth/")) {
            return path;
        }

        String normalized = ID_PATTERN.matcher(path).replaceAll("/*");
        return UUID_PATTERN.matcher(normalized).replaceAll("/*");
    }
}
