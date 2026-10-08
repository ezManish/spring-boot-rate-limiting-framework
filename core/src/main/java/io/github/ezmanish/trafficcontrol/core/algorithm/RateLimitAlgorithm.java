package io.github.ezmanish.trafficcontrol.core.algorithm;

import io.github.ezmanish.trafficcontrol.core.api.Algorithm;
import io.github.ezmanish.trafficcontrol.core.api.RateRule;

/**
 * Common SPI for all rate limiting algorithms. Evaluates tentative state and returns allowance,
 * limits, and retryAfter. Must match the exact semantics in docs/19_ALGORITHM_SPECIFICATION.md.
 */
public interface RateLimitAlgorithm {

  Algorithm algorithm();

  /**
   * Evaluates a rule against the given state and clock. Does NOT mutate state in-place if rejected,
   * or mutates tentative state to be committed on success.
   *
   * @param nowMs current epoch time in milliseconds
   * @param rule rate rule parameters (requests, window, burst)
   * @param cost number of permits requested
   * @param currentState existing state or null if key does not exist
   * @return EvaluationResult containing outcome, remaining, resetAtMs, retryAfterMs, and newState
   */
  EvaluationResult evaluate(long nowMs, RateRule rule, long cost, AlgorithmState currentState);
}
