package io.github.ezmanish.trafficcontrol.core.algorithm;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.ezmanish.trafficcontrol.core.api.Algorithm;
import io.github.ezmanish.trafficcontrol.core.api.RateRule;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SlidingWindowLogAlgorithmTest {

  private SlidingWindowLogAlgorithm algorithm;

  @BeforeEach
  void setUp() {
    algorithm = new SlidingWindowLogAlgorithm();
  }

  @Test
  @DisplayName(
      "19 §7: Test vector R=3, W=1000ms: events at t=0,100,200 allowed; 4th at t=300 denied with retry=700")
  void testCanonicalVector() {
    RateRule rule = new RateRule("r1", Algorithm.SLIDING_WINDOW_LOG, 3, Duration.ofMillis(1000), 3);

    // Event 1 at t=0
    EvaluationResult e1 = algorithm.evaluate(0L, rule, 1, null);
    assertThat(e1.allowed()).isTrue();
    assertThat(e1.remaining()).isEqualTo(2);

    // Event 2 at t=100
    EvaluationResult e2 = algorithm.evaluate(100L, rule, 1, e1.newState());
    assertThat(e2.allowed()).isTrue();
    assertThat(e2.remaining()).isEqualTo(1);

    // Event 3 at t=200
    EvaluationResult e3 = algorithm.evaluate(200L, rule, 1, e2.newState());
    assertThat(e3.allowed()).isTrue();
    assertThat(e3.remaining()).isEqualTo(0);

    // Event 4 at t=300 (denied)
    EvaluationResult e4 = algorithm.evaluate(300L, rule, 1, e3.newState());
    assertThat(e4.allowed()).isFalse();
    assertThat(e4.retryAfterMs()).isEqualTo(700L); // 0 + 1000 - 300 = 700

    // Event 5 at t=1000: t=0 event has expired (window is (0, 1000]), so 1 slot freed
    EvaluationResult e5 = algorithm.evaluate(1000L, rule, 1, e4.newState());
    assertThat(e5.allowed()).isTrue();
    assertThat(e5.remaining()).isEqualTo(0);
  }

  @Test
  @DisplayName("TC-115: Memory is bounded by requests limit; expired timestamps are evicted")
  void testMemoryBoundedByLimit() {
    RateRule rule = new RateRule("r1", Algorithm.SLIDING_WINDOW_LOG, 5, Duration.ofMillis(1000), 5);
    AlgorithmState state = null;

    // Fill capacity
    for (int i = 0; i < 5; i++) {
      EvaluationResult res = algorithm.evaluate(i * 10L, rule, 1, state);
      assertThat(res.allowed()).isTrue();
      state = res.newState();
    }

    SlidingWindowLogState logState = (SlidingWindowLogState) state;
    assertThat(logState.timestamps()).hasSize(5);

    // After 2 seconds, evaluate again
    EvaluationResult after2s = algorithm.evaluate(2000L, rule, 1, state);
    assertThat(after2s.allowed()).isTrue();
    SlidingWindowLogState newLogState = (SlidingWindowLogState) after2s.newState();

    // All old timestamps evicted; only the new one remains
    assertThat(newLogState.timestamps()).hasSize(1);
    assertThat(newLogState.timestamps().get(0)).isEqualTo(2000L);
  }
}
