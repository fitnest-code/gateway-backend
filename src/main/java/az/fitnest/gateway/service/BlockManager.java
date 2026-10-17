package az.fitnest.gateway.service;

import az.fitnest.gateway.util.RequestUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.Duration;

@Component
public class BlockManager {

    private static final Logger log = LoggerFactory.getLogger(BlockManager.class);

    private static final int USER_BLOCK_THRESHOLD = 5;
    private static final int IP_BLOCK_THRESHOLD = 50;
    private static final Duration USER_BLOCK_DURATION = Duration.ofMinutes(15);
    private static final Duration IP_BLOCK_DURATION = Duration.ofHours(1);
    private final ReactiveRedisTemplate<String, String> redisTemplate;

    public BlockManager(ReactiveRedisTemplate<String, String> redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    public Mono<Boolean> isBlocked(String clientIP, String userEmail) {
        Mono<Boolean> userBlocked = (userEmail != null && !userEmail.isEmpty())
                ? redisTemplate.hasKey("blocked:user:" + userEmail)
                : Mono.just(false);

        return userBlocked.flatMap(blocked -> blocked ? Mono.just(true) : isIpBlocked(clientIP));
    }

    private Mono<Boolean> isIpBlocked(String clientIP) {
        if (!RequestUtils.isBlockableAddress(clientIP)) {
            return Mono.just(false);
        }
        return redisTemplate.hasKey("blocked:ip:" + clientIP);
    }

    public Mono<Long> recordFailedAttempt(String clientIP, String userEmail) {
        if (userEmail != null && !userEmail.isEmpty()) {
            return recordFailedAttempt(userEmail, USER_BLOCK_THRESHOLD, USER_BLOCK_DURATION, "user");
        }

        if (!RequestUtils.isBlockableAddress(clientIP)) {
            log.debug("Not tracking failed attempts for non-routable client address {}", clientIP);
            return Mono.just(0L);
        }

        return recordFailedAttempt(clientIP, IP_BLOCK_THRESHOLD, IP_BLOCK_DURATION, "ip");
    }

    private Mono<Long> recordFailedAttempt(String identifier, int threshold, Duration blockDuration, String type) {
        String attemptsKey = "failed_attempts:" + type + ":" + identifier;
        String blockKey = "blocked:" + type + ":" + identifier;

        return redisTemplate.opsForValue().increment(attemptsKey)
                .flatMap(attempts -> {
                    if (attempts == 1) {
                        return redisTemplate.expire(attemptsKey, blockDuration)
                                .thenReturn(attempts);
                    }
                    return Mono.just(attempts);
                })
                .flatMap(attempts -> {
                    if (attempts >= threshold) {
                        log.warn("Blocking {} '{}' for {} after {} failed attempts", type, identifier, blockDuration, attempts);
                        return redisTemplate.opsForValue().set(blockKey, "1")
                                .then(redisTemplate.expire(blockKey, blockDuration))
                                .then(redisTemplate.delete(attemptsKey))
                                .thenReturn(attempts);
                    }
                    return Mono.just(attempts);
                });
    }
}
