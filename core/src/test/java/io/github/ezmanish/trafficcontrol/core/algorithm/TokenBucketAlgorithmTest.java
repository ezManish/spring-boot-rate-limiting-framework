package io.github.ezmanish.trafficcontrol.core.algorithm;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.ezmanish.trafficcontrol.core.api.Algorithm;
import io.github.ezmanish.trafficcontrol.core.api.RateRule;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class TokenBucketAlgorithmTest {

  private final TokenBucketAlgorithm algorithm = new TokenBucketAlgorithm();

  @Test
  @DisplayName("TC-001: 100 requests in 1m window, 101st is rejected")
  void testHundredRequestsInOneMinute() {
    RateRule rule = new RateRule("r1", Algorithm.TOKEN_BUCKET, 100, Duration.ofMinutes(1), 100);
    long now = 1000L;
    AlgorithmState state = null;

    for (int i = 0; i < 100; i++) {
      EvaluationResult result = algorithm.evaluate(now, rule, 1, state);
      assertThat(result.allowed()).as("Request %d should be allowed", i + 1).isTrue();
      assertThat(result.remaining()).isEqualTo(100 - (i + 1));
      state = result.newState();
    }

    EvaluationResult rejected = algorithm.evaluate(now, rule, 1, state);
    assertThat(rejected.allowed()).isFalse();
    assertThat(rejected.remaining()).isEqualTo(0);
    assertThat(rejected.retryAfterMs()).isGreaterThan(0);
  }

  @Test
  @DisplayName("TC-002: Advance fake clock 60s after exhaustion restores full capacity")
  void testRestoreFullCapacity() {
    RateRule rule = new RateRule("r1", Algorithm.TOKEN_BUCKET, 10, Duration.ofSeconds(60), 10);
    long now = 0L;
    AlgorithmState state = null;

    for (int i = 0; i < 10; i++) {
      EvaluationResult result = algorithm.evaluate(now, rule, 1, state);
      state = result.newState();
    }

    // Exhausted
    EvaluationResult rejected = algorithm.evaluate(now, rule, 1, state);
    assertThat(rejected.allowed()).isFalse();

    // Advance 60 seconds
    now += 60_000L;
    EvaluationResult refilled = algorithm.evaluate(now, rule, 1, state);
    assertThat(refilled.allowed()).isTrue();
    assertThat(refilled.remaining()).isEqualTo(9); // 10 restored, 1 consumed
  }

  @Test
  @DisplayName("TC-003: Partial refill (30s of 60s window) restores ~50% tokens")
  void testPartialRefill() {
    RateRule rule = new RateRule("r1", Algorithm.TOKEN_BUCKET, 10, Duration.ofSeconds(60), 10);
    long now = 0L;
    AlgorithmState state = null;

    // Consume all 10 tokens
    for (int i = 0; i < 10; i++) {
      EvaluationResult result = algorithm.evaluate(now, rule, 1, state);
      state = result.newState();
    }

    // Advance 30 seconds
    now += 30_000L;
    EvaluationResult partial = algorithm.evaluate(now, rule, 1, state);
    assertThat(partial.allowed()).isTrue();
    // 5 tokens refilled, 1 consumed -> 4 remaining
    assertThat(partial.remaining()).isEqualTo(4);
  }

  @Test
  @DisplayName("TC-004: Burst > requests configured allows burst up to capacity")
  void testBurstConfigured() {
    RateRule rule = new RateRule("r1", Algorithm.TOKEN_BUCKET, 5, Duration.ofSeconds(10), 15);
    long now = 0L;
    AlgorithmState state = null;

    for (int i = 0; i < 15; i++) {
      EvaluationResult result = algorithm.evaluate(now, rule, 1, state);
      assertThat(result.allowed()).as("Burst request %d should be allowed", i + 1).isTrue();
      state = result.newState();
    }

    EvaluationResult rejected = algorithm.evaluate(now, rule, 1, state);
    assertThat(rejected.allowed()).isFalse();
  }

  @Test
  @DisplayName("TC-006 & Test Vector 19 §7: R=10, W=1000, B=10 exact conformance")
  void testSpecTestVector() {
    RateRule rule = new RateRule("r1", Algorithm.TOKEN_BUCKET, 10, Duration.ofSeconds(1), 10);
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
