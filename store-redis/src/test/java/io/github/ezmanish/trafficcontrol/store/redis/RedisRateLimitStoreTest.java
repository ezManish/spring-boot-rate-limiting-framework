package io.github.ezmanish.trafficcontrol.store.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import io.github.ezmanish.trafficcontrol.core.api.Algorithm;
import io.github.ezmanish.trafficcontrol.core.api.ConcurrencyRule;
import io.github.ezmanish.trafficcontrol.core.api.RateRule;
import io.github.ezmanish.trafficcontrol.core.spi.StoreResult;
import io.lettuce.core.api.sync.RedisCommands;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class RedisRateLimitStoreTest {

  @Mock private RedisCommands<String, String> commands;

  @Mock private RedisScriptManager scriptManager;

  private CircuitBreaker circuitBreaker;
  private RedisRateLimitStore store;

  @BeforeEach
  void setUp() {
    circuitBreaker = new CircuitBreaker(2, Duration.ofSeconds(5));
    store = new RedisRateLimitStore(commands, scriptManager, circuitBreaker);
  }

  @Test
  @DisplayName("TC-020: Allowed decision maps 8 integers from Lua script into StoreResult")
  void testAllowedDecisionMapping() {
    RateRule rule = new RateRule("r1", Algorithm.TOKEN_BUCKET, 10, Duration.ofSeconds(1), 10);
    // Lua return: [allowed, binding_rule, reason, remaining, limit, reset_at_ms, retry_after_ms,
    // now_ms]
    when(scriptManager.evalDecide(eq(commands), any(), any()))
        .thenReturn(List.of(1L, 1L, 0L, 9L, 10L, 1760000001000L, 0L, 1760000000000L));

    StoreResult result = store.tryConsume("userHash", "api", List.of(rule), 1, null, "evt-1");

    assertThat(result.allowed()).isTrue();
    assertThat(result.bindingRuleIndex()).isEqualTo(1);
    assertThat(result.remaining()).isEqualTo(9);
    assertThat(result.limit()).isEqualTo(10);
    assertThat(result.resetAtMs()).isEqualTo(1760000001000L);
    assertThat(result.retryAfterMs()).isEqualTo(0);
  }

  @Test
  @DisplayName("TC-030: Denied rate rule maps to StoreResult with reason 1")
  void testDeniedRateRuleMapping() {
    RateRule rule = new RateRule("r1", Algorithm.TOKEN_BUCKET, 10, Duration.ofSeconds(1), 10);
    when(scriptManager.evalDecide(eq(commands), any(), any()))
        .thenReturn(List.of(0L, 1L, 1L, 0L, 10L, 1760000001000L, 200L, 1760000000000L));

    StoreResult result = store.tryConsume("userHash", "api", List.of(rule), 1, null, "evt-1");

    assertThat(result.allowed()).isFalse();
    assertThat(result.reason()).isEqualTo(1);
    assertThat(result.retryAfterMs()).isEqualTo(200);
  }

  @Test
  @DisplayName("TC-050: Denied concurrency maps to StoreResult with reason 2")
  void testDeniedConcurrencyMapping() {
    ConcurrencyRule conc = new ConcurrencyRule(2, Duration.ZERO, Duration.ofSeconds(30));
    when(scriptManager.evalDecide(eq(commands), any(), any()))
        .thenReturn(List.of(0L, 0L, 2L, 0L, 2L, 1760000005000L, 5000L, 1760000000000L));

    StoreResult result = store.tryConsume("userHash", "api", List.of(), 1, conc, "evt-1");

    assertThat(result.allowed()).isFalse();
    assertThat(result.reason()).isEqualTo(2);
    assertThat(result.bindingRuleIndex()).isEqualTo(0);
    assertThat(result.retryAfterMs()).isEqualTo(5000);
  }

  @Test
  @DisplayName(
      "TC-061 & TC-062: Circuit breaker open immediately throws RedisStoreUnavailableException")
  void testCircuitBreakerOpenThrows() {
    // Trigger 2 failures to open breaker
    when(scriptManager.evalDecide(eq(commands), any(), any()))
        .thenThrow(new RuntimeException("Redis connection timed out"));

    RateRule rule = new RateRule("r1", Algorithm.TOKEN_BUCKET, 10, Duration.ofSeconds(1), 10);

    assertThatThrownBy(() -> store.tryConsume("userHash", "api", List.of(rule), 1, null, "evt-1"))
        .isInstanceOf(RedisStoreUnavailableException.class);

    assertThatThrownBy(() -> store.tryConsume("userHash", "api", List.of(rule), 1, null, "evt-1"))
        .isInstanceOf(RedisStoreUnavailableException.class);

    assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);

    // 3rd request fails fast without calling Redis
    assertThatThrownBy(() -> store.tryConsume("userHash", "api", List.of(rule), 1, null, "evt-1"))
        .isInstanceOf(RedisStoreUnavailableException.class)
        .hasMessageContaining("circuit breaker is open");

    verify(scriptManager, times(2)).evalDecide(any(), any(), any());
  }

  @Test
  @DisplayName("TC-051: release executes ZREM on concurrency key")
  void testReleaseExecutesZrem() {
    store.release("userHash", "api", "permit-123");
    verify(commands).zrem("tc:{userHash}:api:conc", "permit-123");
  }

  @Test
  @DisplayName("TC-052: Concurrency permit ID is generated and returned when eventId is null")
  void testConcurrencyPermitIdGeneratedWhenNull() {
    ConcurrencyRule conc = new ConcurrencyRule(5, Duration.ZERO, Duration.ofSeconds(30));
    when(scriptManager.evalDecide(eq(commands), any(), any()))
        .thenReturn(List.of(1L, 0L, 0L, 0L, 5L, 1760000030000L, 0L, 1760000000000L));

    StoreResult result = store.tryConsume("userHash", "api", List.of(), 1, conc, null);

    assertThat(result.allowed()).isTrue();
    assertThat(result.permitId()).isNotNull();
    assertThat(result.permitId()).isNotBlank();
  }

  @Test
  @DisplayName("TC-053: Concurrency permit ID preserves caller provided eventId")
  void testConcurrencyPermitIdPreservesEventId() {
    ConcurrencyRule conc = new ConcurrencyRule(5, Duration.ZERO, Duration.ofSeconds(30));
    when(scriptManager.evalDecide(eq(commands), any(), any()))
        .thenReturn(List.of(1L, 0L, 0L, 0L, 5L, 1760000030000L, 0L, 1760000000000L));

    StoreResult result = store.tryConsume("userHash", "api", List.of(), 1, conc, "custom-evt-999");

    assertThat(result.allowed()).isTrue();
    assertThat(result.permitId()).isEqualTo("custom-evt-999");
  }
}
