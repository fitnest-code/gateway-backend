package az.fitnest.gateway.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.data.redis.core.ReactiveValueOperations;
import reactor.core.publisher.Mono;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class BlockManagerTest {

    private static final String SHARED_ADDRESS = "10.30.0.5";
    private static final String PUBLIC_ADDRESS = "203.0.113.7";

    @Mock
    private ReactiveRedisTemplate<String, String> redisTemplate;
    @Mock
    private ReactiveValueOperations<String, String> valueOperations;

    @Test
    void neverTouchesRedisForASharedAddress() {
        BlockManager blockManager = new BlockManager(redisTemplate);

        assertThat(blockManager.recordFailedAttempt(SHARED_ADDRESS, null).block()).isZero();
        assertThat(blockManager.isBlocked(SHARED_ADDRESS, null).block()).isFalse();

        verifyNoInteractions(redisTemplate);
    }

    @Test
    void countsFailuresForAPublicAddress() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.increment("failed_attempts:ip:" + PUBLIC_ADDRESS)).thenReturn(Mono.just(1L));
        when(redisTemplate.expire(eq("failed_attempts:ip:" + PUBLIC_ADDRESS), any(Duration.class)))
                .thenReturn(Mono.just(true));

        BlockManager blockManager = new BlockManager(redisTemplate);

        assertThat(blockManager.recordFailedAttempt(PUBLIC_ADDRESS, null).block()).isEqualTo(1L);
        verify(redisTemplate).expire(eq("failed_attempts:ip:" + PUBLIC_ADDRESS), any(Duration.class));
    }

    @Test
    void blocksAPublicAddressOnceTheThresholdIsReached() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.increment(anyString())).thenReturn(Mono.just(50L));
        when(valueOperations.set("blocked:ip:" + PUBLIC_ADDRESS, "1")).thenReturn(Mono.just(true));
        when(redisTemplate.expire(eq("blocked:ip:" + PUBLIC_ADDRESS), any(Duration.class)))
                .thenReturn(Mono.just(true));
        when(redisTemplate.delete("failed_attempts:ip:" + PUBLIC_ADDRESS)).thenReturn(Mono.just(1L));

        BlockManager blockManager = new BlockManager(redisTemplate);

        assertThat(blockManager.recordFailedAttempt(PUBLIC_ADDRESS, null).block()).isEqualTo(50L);
        verify(valueOperations).set("blocked:ip:" + PUBLIC_ADDRESS, "1");
        verify(redisTemplate).delete("failed_attempts:ip:" + PUBLIC_ADDRESS);
    }

    @Test
    void userBlocksStillApplyForTheUserKey() {
        when(redisTemplate.hasKey("blocked:user:user@fitnest.az")).thenReturn(Mono.just(true));

        BlockManager blockManager = new BlockManager(redisTemplate);

        assertThat(blockManager.isBlocked(SHARED_ADDRESS, "user@fitnest.az").block()).isTrue();
    }
}
