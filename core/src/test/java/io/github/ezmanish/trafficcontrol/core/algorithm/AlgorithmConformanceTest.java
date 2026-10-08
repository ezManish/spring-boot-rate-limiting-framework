package io.github.ezmanish.trafficcontrol.core.algorithm;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.ezmanish.trafficcontrol.core.api.RateRule;
import java.time.Duration;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Shared algorithm conformance suite matching docs/09_TEST_CASES.md (TC-110..112). Every algorithm
 * must pass this suite.
 */
class AlgorithmConformanceTest {

  static Stream<RateLimitAlgorithm> algorithms() {
    return Stream.of(new TokenBucketAlgorithm(), new FixedWindowAlgorithm());
  }

  @ParameterizedTest
  @MethodSource("algorithms")
  @DisplayName("TC-110: Exactly N requests allowed in one window, N+1 is rejected")
  void testExactLimitEnforcement(RateLimitAlgorithm algorithm) {
    long limit = 20;
    Duration window = Duration.ofSeconds(10);
    RateRule rule = new RateRule("r1", algorithm.algorithm(), limit, window, limit);

    long now = 1000L;
    AlgorithmState state = null;

    for (int i = 0; i < limit; i++) {
      EvaluationResult result = algorithm.evaluate(now, rule, 1, state);
      assertThat(result.allowed())
          .as("%s: Request %d should be allowed", algorithm.algorithm(), i + 1)
          .isTrue();
      assertThat(result.remaining())
          .as("%s: Remaining should decrement", algorithm.algorithm())
          .isEqualTo(limit - (i + 1));
      state = result.newState();
    }

    EvaluationResult overLimit = algorithm.evaluate(now, rule, 1, state);
    assertThat(overLimit.allowed())
        .as("%s: Request %d must be denied", algorithm.algorithm(), limit + 1)
        .isFalse();
    assertThat(overLimit.remaining()).isEqualTo(0L);
    assertThat(overLimit.retryAfterMs()).isGreaterThan(0L);
  }

  @ParameterizedTest
  @MethodSource("algorithms")
  @DisplayName("TC-112: Full capacity restored after window duration passes")
  void testFullCapacityRestoredAfterWindow(RateLimitAlgorithm algorithm) {
    long limit = 10;
    Duration window = Duration.ofSeconds(5);
    RateRule rule = new RateRule("r1", algorithm.algorithm(), limit, window, limit);

    long now = 10_000L;
    AlgorithmState state = null;

    // Exhaust all capacity
    for (int i = 0; i < limit; i++) {
      EvaluationResult result = algorithm.evaluate(now, rule, 1, state);
      state = result.newState();
    }

    // Verify exhausted
    assertThat(algorithm.evaluate(now, rule, 1, state).allowed()).isFalse();

    // Advance beyond window
    now += window.toMillis() + 500L;

    EvaluationResult refilled = algorithm.evaluate(now, rule, 1, state);
    assertThat(refilled.allowed()).isTrue();
    assertThat(refilled.remaining()).isEqualTo(limit - 1);
  }
}
