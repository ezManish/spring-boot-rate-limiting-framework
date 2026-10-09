package io.github.ezmanish.trafficcontrol.core.algorithm;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.ezmanish.trafficcontrol.core.api.Algorithm;
import io.github.ezmanish.trafficcontrol.core.api.RateRule;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SlidingWindowCounterAlgorithmTest {

  private final SlidingWindowCounterAlgorithm algorithm = new SlidingWindowCounterAlgorithm();

  @Test
  @DisplayName("Test Vector 19 §7: Sliding Window Counter R=10, W=1000, prev=10 at t=1500")
  void testSlidingWindowCounterSpecVector() {
    RateRule rule =
        new RateRule("r1", Algorithm.SLIDING_WINDOW_COUNTER, 10, Duration.ofSeconds(1), 10);

    // Previous window was [0, 1000), where 10 requests were consumed
    AlgorithmState state = new SlidingWindowCounterState(10, 0, 1000L);

    // At t=1500, elapsed e = 500ms in window [1000, 2000). Weight = (1000-500)/1000 = 0.5.
    // est = 10 * 0.5 + curr = 5 + curr.
    // 5 requests should be allowed (curr goes 1..5, est+cost goes 6..10)
    for (int i = 0; i < 5; i++) {
      EvaluationResult result = algorithm.evaluate(1500L, rule, 1, state);
      assertThat(result.allowed()).as("Request %d should be allowed", i + 1).isTrue();
      state = result.newState();
    }

    // 6th request at t=1500: est = 5 + 5 = 10. 10 + 1 = 11 > 10 -> denied! retryAfterMs = 1
    EvaluationResult sixth = algorithm.evaluate(1500L, rule, 1, state);
    assertThat(sixth.allowed()).isFalse();
    assertThat(sixth.retryAfterMs()).isEqualTo(1L);
  }
}
