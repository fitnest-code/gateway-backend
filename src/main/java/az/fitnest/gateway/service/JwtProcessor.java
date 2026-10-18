package az.fitnest.gateway.service;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Component
public class JwtProcessor {

    private static final Logger log = LoggerFactory.getLogger(JwtProcessor.class);

    private final ReactiveRedisTemplate<String, String> redisTemplate;
    private final String sessionPrefix;
    @Value("${JWT_SECRET:your-jwt-secret-change-in-production}")
    private String secretKey;
    private io.jsonwebtoken.JwtParser jwtParser;

    public JwtProcessor(
            ReactiveRedisTemplate<String, String> redisTemplate,
            @Value("${security.redis.session-prefix:auth:user:session:}") String sessionPrefix
    ) {
        this.redisTemplate = redisTemplate;
        this.sessionPrefix = sessionPrefix;
    }

    @PostConstruct
    public void init() {
        SecretKey key = Keys.hmacShaKeyFor(secretKey.getBytes(StandardCharsets.UTF_8));
        this.jwtParser = Jwts.parser()
                .verifyWith(key)
                .build();
    }

    public String generateJti() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    public Mono<TokenValidationResult> validateTokenForGateway(String token) {
        return validateTokenInternal(token, false, false);
    }

    public Mono<TokenValidationResult> validateToken(String token) {
        return validateTokenInternal(token, true, true);
    }

    private Mono<TokenValidationResult> validateTokenInternal(String token, boolean checkBlacklist, boolean checkSession) {
        if (token == null || token.isEmpty()) {
            return Mono.just(TokenValidationResult.rejected(RejectionReason.MISSING_TOKEN));
        }

        Claims claims;
        try {
            claims = jwtParser.parseSignedClaims(token).getPayload();
        } catch (io.jsonwebtoken.ExpiredJwtException e) {
            return Mono.just(TokenValidationResult.rejected(RejectionReason.EXPIRED));
        } catch (Exception e) {
            log.debug("JWT could not be parsed or verified: {}", e.toString());
            return Mono.just(TokenValidationResult.rejected(RejectionReason.INVALID_TOKEN));
        }

        String subjectStr = claims.getSubject();
        Long parsedUserId = null;
        if (subjectStr != null && !subjectStr.isEmpty()) {
            try {
                parsedUserId = Long.parseLong(subjectStr);
            } catch (NumberFormatException e) {

                parsedUserId = claims.get("userId", Long.class);
            }
        }
        final Long userId = parsedUserId;

        final String language = claims.get("lang", String.class);
        final String email = claims.get("email", String.class);
        final String jti = claims.get("jti", String.class);
        @SuppressWarnings("unchecked") final List<String> roles = claims.get("roles", List.class);

        if (checkBlacklist && jti != null) {
            String blacklistKey = "blacklist:jti:" + jti;
            return redisTemplate.hasKey(blacklistKey)
                    .flatMap(isBlacklisted -> {
                        if (Boolean.TRUE.equals(isBlacklisted)) {
                            return Mono.just(TokenValidationResult.rejected(RejectionReason.BLACKLISTED));
                        }
                        if (checkSession && userId != null) {
                            return validateSession(userId, jti, email, roles, language);
                        }
                        return Mono.just(TokenValidationResult.authenticated(email, userId, roles, jti, language));
                    });
        }

        if (checkSession && userId != null && jti != null) {
            return validateSession(userId, jti, email, roles, language);
        }

        return Mono.just(TokenValidationResult.authenticated(email, userId, roles, jti, language));
    }

    private Mono<TokenValidationResult> validateSession(Long userId, String jti, String email, List<String> roles, String language) {
        String mobileSessionKey = sessionPrefix + userId + ":mobile";
        String webSessionKey = sessionPrefix + userId + ":web";
        String legacySessionKey = sessionPrefix + userId;
        long now = Instant.now().getEpochSecond();

        // Check each category ZSET for the JTI with score > now (valid session)
        return redisTemplate.opsForZSet().score(mobileSessionKey, jti)
                .flatMap(mobileScore -> {
                    if (mobileScore != null && mobileScore > now) {
                        return Mono.just(TokenValidationResult.authenticated(email, userId, roles, jti, language));
                    }
                    return redisTemplate.opsForZSet().score(webSessionKey, jti)
                            .flatMap(webScore -> {
                                if (webScore != null && webScore > now) {
                                    return Mono.just(TokenValidationResult.authenticated(email, userId, roles, jti, language));
                                }
                                return redisTemplate.opsForZSet().score(legacySessionKey, jti)
                                        .map(legacyScore -> {
                                            if (legacyScore != null && legacyScore > now) {
                                                return TokenValidationResult.authenticated(email, userId, roles, jti, language);
                                            }
                                            return TokenValidationResult.rejected(RejectionReason.SESSION_NOT_FOUND);
                                        })
                                        .defaultIfEmpty(TokenValidationResult.rejected(RejectionReason.SESSION_NOT_FOUND));
                            });
                });
    }

    public boolean isAdminRoute(String path) {
        return path.startsWith("/api/v1/admin/") &&
                !path.equals("/api/v1/admin/create-admin");
    }

    /**
     * Why a token was rejected. Exposed so the gateway can log (and unit test) the exact cause
     * instead of collapsing every failure into "unauthorized".
     */
    public enum RejectionReason {
        MISSING_TOKEN,
        INVALID_TOKEN,
        EXPIRED,
        BLACKLISTED,
        SESSION_NOT_FOUND
    }

    public static class TokenValidationResult {
        public final boolean valid;
        public final String email;
        public final Long userId;
        public final List<String> roles;
        public final String jti;
        public final String language;
        public final RejectionReason rejectionReason;

        public TokenValidationResult(boolean valid, String email, Long userId, List<String> roles, String jti, String language) {
            this(valid, email, userId, roles, jti, language,
                    valid ? null : RejectionReason.INVALID_TOKEN);
        }

        public TokenValidationResult(boolean valid, String email, Long userId, List<String> roles, String jti, String language,
                                     RejectionReason rejectionReason) {
            this.valid = valid;
            this.email = email;
            this.userId = userId;
            this.roles = roles;
            this.jti = jti;
            this.language = language;
            this.rejectionReason = rejectionReason;
        }

        public static TokenValidationResult authenticated(String email, Long userId, List<String> roles, String jti, String language) {
            return new TokenValidationResult(true, email, userId, roles, jti, language, null);
        }

        public static TokenValidationResult rejected(RejectionReason reason) {
            return new TokenValidationResult(false, null, null, null, null, null, reason);
        }
    }
}
