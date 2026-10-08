package io.github.ezmanish.trafficcontrol.core.algorithm;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.ezmanish.trafficcontrol.core.api.Algorithm;
import io.github.ezmanish.trafficcontrol.core.api.RateRule;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class FixedWindowAlgorithmTest {

  private final FixedWindowAlgorithm algorithm = new FixedWindowAlgorithm();

  @Test
  @DisplayName("Test Vector 19 §7: Fixed Window R=3, W=1000, 4 requests at t=1500")
  void testFixedWindowSpecVector() {
    RateRule rule = new RateRule("r1", Algorithm.FIXED_WINDOW, 3, Duration.ofSeconds(1), 3);
    long now = 1500L;
    AlgorithmState state = null;

    // 3 requests allowed
    for (int i = 0; i < 3; i++) {
      EvaluationResult result = algorithm.evaluate(now, rule, 1, state);
      assertThat(result.allowed()).isTrue();
      assertThat(result.remaining()).isEqualTo(2 - i);
      state = result.newState();
    }

    // 4th request denied with retry=500 and reset=2000
    EvaluationResult fourth = algorithm.evaluate(now, rule, 1, state);
    assertThat(fourth.allowed()).isFalse();
    assertThat(fourth.retryAfterMs()).isEqualTo(500L);
    assertThat(fourth.resetAtMs()).isEqualTo(2000L);
  }

  @Test
  @DisplayName("TC-111: Boundary burst behavior (up to 2R across window boundary)")
  void testBoundaryBurst() {
    RateRule rule = new RateRule("r1", Algorithm.FIXED_WINDOW, 5, Duration.ofSeconds(1), 5);
    AlgorithmState state = null;

    // 5 requests at end of window 1 (t=999)
    for (int i = 0; i < 5; i++) {
      EvaluationResult res = algorithm.evaluate(999L, rule, 1, state);
      assertThat(res.allowed()).isTrue();
      state = res.newState();
    }

    // 5 requests at start of window 2 (t=1001)
    for (int i = 0; i < 5; i++) {
      EvaluationResult res = algorithm.evaluate(1001L, rule, 1, state);
      assertThat(res.allowed()).isTrue();
      state = res.newState();
    }

    // 11th request at t=1002 rejected
    EvaluationResult rejected = algorithm.evaluate(1002L, rule, 1, state);
    assertThat(rejected.allowed()).isFalse();
  }
}
