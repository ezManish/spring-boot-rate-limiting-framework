package io.github.ezmanish.trafficcontrol.bench;

import static org.assertj.core.api.Assertions.assertThatNoException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Smoke test verifying JMH benchmarks execute properly without runtime errors. */
class BenchmarkExecutionTest {

  @Test
  @DisplayName("Smoke test: TokenBucketBenchmark executes without errors")
  void testTokenBucketBenchmark() {
    TokenBucketBenchmark bench = new TokenBucketBenchmark();
    bench.setUp();

    assertThatNoException()
        .isThrownBy(
            () -> {
              bench.trafficControlLocal(null);
              bench.bucket4jLocal(null);
            });
  }

  @Test
  @DisplayName("Smoke test: AlgorithmSweepBenchmark executes all 5 algorithms without errors")
  void testAlgorithmSweepBenchmark() {
    AlgorithmSweepBenchmark bench = new AlgorithmSweepBenchmark();
    bench.setUp();

    assertThatNoException()
        .isThrownBy(
            () -> {
              bench.tokenBucket(null);
              bench.gcra(null);
              bench.slidingWindowCounter(null);
              bench.fixedWindow(null);
              bench.slidingWindowLog(null);
            });
  }

  @Test
  @DisplayName("Smoke test: MultiRuleBenchmark executes 1, 2, and 4 rules without errors")
  void testMultiRuleBenchmark() {
    MultiRuleBenchmark bench = new MultiRuleBenchmark();
    bench.setUp();

    assertThatNoException()
        .isThrownBy(
            () -> {
              bench.singleRule(null);
              bench.dualRules(null);
              bench.quadRules(null);
            });
  }
}
