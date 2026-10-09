package io.github.ezmanish.trafficcontrol.store.redis;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class CircuitBreakerTest {

  @Test
  @DisplayName("TC-063: Circuit breaker opens after failure threshold")
  void testOpensAfterThreshold() {
    CircuitBreaker breaker = new CircuitBreaker(3, Duration.ofMillis(200));

    assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
    assertThat(breaker.allowRequest()).isTrue();

    breaker.recordFailure();
    breaker.recordFailure();
    assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);

    breaker.recordFailure(); // 3rd failure
    assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);
    assertThat(breaker.allowRequest()).isFalse();
  }

  @Test
  @DisplayName("TC-064 & TC-065: Half-open probe success recovers breaker to CLOSED")
  void testHalfOpenProbeSuccess() throws Exception {
    CircuitBreaker breaker = new CircuitBreaker(2, Duration.ofMillis(50));

    breaker.recordFailure();
    breaker.recordFailure();
    assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);

    // Wait past open duration
    Thread.sleep(70);

    // Now transitions to HALF_OPEN
    assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.HALF_OPEN);
    assertThat(breaker.allowRequest()).isTrue();

    // Successful probe closes circuit
    breaker.recordSuccess();
    assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
    assertThat(breaker.allowRequest()).isTrue();
  }
}
