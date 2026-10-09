package io.github.ezmanish.trafficcontrol.core.algorithm;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.ezmanish.trafficcontrol.core.api.Algorithm;
import io.github.ezmanish.trafficcontrol.core.api.RateRule;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class GcraAlgorithmTest {

  private final GcraAlgorithm algorithm = new GcraAlgorithm();

  @Test
  @DisplayName("Test Vector 19 §7: GCRA R=10, W=1000, B=10 identical to Token Bucket")
  void testGcraSpecVector() {
    RateRule rule = new RateRule("r1", Algorithm.GCRA, 10, Duration.ofSeconds(1), 10);
    AlgorithmState state = null;

    // 10 requests at t=0
    for (int i = 0; i < 10; i++) {
      EvaluationResult result = algorithm.evaluate(0L, rule, 1, state);
      assertThat(result.allowed()).isTrue();
      assertThat(result.remaining()).isEqualTo(9 - i);
      state = result.newState();
    }

    // 11th request at t=0: denied, retry=100
    EvaluationResult eleventh = algorithm.evaluate(0L, rule, 1, state);
    assertThat(eleventh.allowed()).isFalse();
    assertThat(eleventh.retryAfterMs()).isEqualTo(100L);

    // 12th request at t=100: allowed, remaining=0, reset=1100
    EvaluationResult twelfth = algorithm.evaluate(100L, rule, 1, state);
    assertThat(twelfth.allowed()).isTrue();
    assertThat(twelfth.remaining()).isEqualTo(0L);
    assertThat(twelfth.resetAtMs()).isEqualTo(1100L);
  }
}
