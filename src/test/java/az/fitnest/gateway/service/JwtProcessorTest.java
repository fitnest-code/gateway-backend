package az.fitnest.gateway.service;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.data.redis.core.ReactiveZSetOperations;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Mono;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Pins the rejection reasons reported for a token.
 *
 * <p>Previously every failure (missing, malformed, expired, blacklisted, revoked session)
 * collapsed into a single boolean, so a gateway-wide "401 storm" could not be attributed
 * from logs. The reasons are what make that incident diagnosable.
 */
class JwtProcessorTest {

    private static final String SECRET = "test-secret-value-that-is-long-enough-for-hmac-sha256!!";

    private JwtProcessor processor;
    private SecretKey key;

    @BeforeEach
    void setUp() {
        processor = new JwtProcessor(null, "auth:user:session:");
        ReflectionTestUtils.setField(processor, "secretKey", SECRET);
        processor.init();
        key = Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void missingTokenIsReportedAsMISSING_TOKEN() {
        assertThat(reasonOf(null)).isEqualTo(JwtProcessor.RejectionReason.MISSING_TOKEN);
        assertThat(reasonOf("")).isEqualTo(JwtProcessor.RejectionReason.MISSING_TOKEN);
    }

    @Test
    void malformedOrForeignlySignedTokenIsReportedAsINVALID_TOKEN() {
        assertThat(reasonOf("not.a.jwt")).isEqualTo(JwtProcessor.RejectionReason.INVALID_TOKEN);

        SecretKey otherKey = Keys.hmacShaKeyFor(
                "another-secret-value-that-is-long-enough-for-hmac-sha256!!".getBytes(StandardCharsets.UTF_8));
        String foreignToken = Jwts.builder().subject("42").signWith(otherKey).compact();

        assertThat(reasonOf(foreignToken)).isEqualTo(JwtProcessor.RejectionReason.INVALID_TOKEN);
    }

    @Test
    void expiredTokenIsReportedAsEXPIRED() {
        String token = Jwts.builder()
                .subject("42")
                .expiration(new Date(System.currentTimeMillis() - 60_000))
                .signWith(key)
                .compact();

        assertThat(reasonOf(token)).isEqualTo(JwtProcessor.RejectionReason.EXPIRED);
    }

    @Test
    void validTokenIsAcceptedAndCarriesItsClaims() {
        String token = Jwts.builder()
                .subject("42")
                .claim("jti", "abc123")
                .claim("email", "user@fitnest.az")
                .claim("lang", "az")
                .signWith(key)
                .compact();

        JwtProcessor.TokenValidationResult result = processor.validateTokenForGateway(token).block();

        assertThat(result).isNotNull();
        assertThat(result.valid).isTrue();
        assertThat(result.rejectionReason).isNull();
        assertThat(result.userId).isEqualTo(42L);
        assertThat(result.email).isEqualTo("user@fitnest.az");
        assertThat(result.language).isEqualTo("az");
    }

    @Test
    void rejectedFactoryNeverLeaksClaims() {
        JwtProcessor.TokenValidationResult result =
                JwtProcessor.TokenValidationResult.rejected(JwtProcessor.RejectionReason.BLACKLISTED);

        assertThat(result.valid).isFalse();
        assertThat(result.userId).isNull();
        assertThat(result.email).isNull();
        assertThat(result.jti).isNull();
        assertThat(result.rejectionReason).isEqualTo(JwtProcessor.RejectionReason.BLACKLISTED);
    }

    @Test
    @SuppressWarnings("unchecked")
    void webSessionIsAcceptedWhenMobileZsetScoreIsEmpty() {
        // Regression: ReactiveZSetOperations.score() returns Mono.empty() when the member is
        // absent. Nested flatMap skipped :web/:legacy and the auth filter completed empty 200s.
        ReactiveRedisTemplate<String, String> redis = mock(ReactiveRedisTemplate.class);
        ReactiveZSetOperations<String, String> zset = mock(ReactiveZSetOperations.class);
        when(redis.opsForZSet()).thenReturn(zset);
        when(redis.hasKey(org.mockito.ArgumentMatchers.anyString())).thenReturn(Mono.just(false));

        String jti = "web-jti-1";
        long future = Instant.now().getEpochSecond() + 3600;
        when(zset.score(eq("auth:user:session:6:mobile"), eq(jti))).thenReturn(Mono.empty());
        when(zset.score(eq("auth:user:session:6:web"), eq(jti))).thenReturn(Mono.just((double) future));
        when(zset.score(eq("auth:user:session:6"), eq(jti))).thenReturn(Mono.empty());

        JwtProcessor withRedis = new JwtProcessor(redis, "auth:user:session:");
        ReflectionTestUtils.setField(withRedis, "secretKey", SECRET);
        withRedis.init();

        String token = Jwts.builder()
                .subject("6")
                .claim("jti", jti)
                .claim("email", "admin@fitnest.az")
                .claim("lang", "AZ")
                .signWith(key)
                .compact();

        JwtProcessor.TokenValidationResult result = withRedis.validateToken(token).block();

        assertThat(result).isNotNull();
        assertThat(result.valid).isTrue();
        assertThat(result.userId).isEqualTo(6L);
        assertThat(result.jti).isEqualTo(jti);
    }

    private JwtProcessor.RejectionReason reasonOf(String token) {
        JwtProcessor.TokenValidationResult result = processor.validateTokenForGateway(token).block();
        assertThat(result).isNotNull();
        assertThat(result.valid).isFalse();
        return result.rejectionReason;
    }
}
