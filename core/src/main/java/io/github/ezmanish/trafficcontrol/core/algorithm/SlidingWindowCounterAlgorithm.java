package io.github.ezmanish.trafficcontrol.core.algorithm;

import io.github.ezmanish.trafficcontrol.core.api.Algorithm;
import io.github.ezmanish.trafficcontrol.core.api.RateRule;

/**
 * Sliding Window Counter weighted two-window approximation algorithm matching
 * docs/19_ALGORITHM_SPECIFICATION.md §4.
 */
public class SlidingWindowCounterAlgorithm implements RateLimitAlgorithm {

  @Override
  public Algorithm algorithm() {
    return Algorithm.SLIDING_WINDOW_COUNTER;
  }

  @Override
  public EvaluationResult evaluate(
      long nowMs, RateRule rule, long cost, AlgorithmState currentState) {
    long r = rule.requests();
    long w = rule.window().toMillis();
    long wsNow = (nowMs / w) * w;

    long prev = 0L;
    long curr = 0L;

    if (currentState instanceof SlidingWindowCounterState state) {
      long ws = state.windowStartMs();
      if (ws == wsNow) {
        prev = state.previousCount();
        curr = state.currentCount();
      } else if (ws == wsNow - w) {
        prev = state.currentCount();
        curr = 0L;
      }
    }

    long e = nowMs - wsNow;
    long est = (prev * (w - e)) / w + curr;
    long resetAtMs = wsNow + w;

    if (est + cost <= r) {
      long newCurr = curr + cost;
      long remaining = Math.max(0L, r - (est + cost));
      SlidingWindowCounterState newState = new SlidingWindowCounterState(prev, newCurr, wsNow);
      return new EvaluationResult(true, remaining, r, resetAtMs, 0L, newState);
    } else {
      long remaining = Math.max(0L, r - est);
      long retryAfterMs;
      long m = r - curr - cost;
      if (m >= 0 && prev > 0) {
        long wait = (w - ceilDiv((m + 1) * w, prev) + 1) - e;
        retryAfterMs = Math.max(1L, wait);
      } else {
        retryAfterMs = resetAtMs - nowMs;
      }
      return new EvaluationResult(false, remaining, r, resetAtMs, retryAfterMs, currentState);
    }
  }

  private static long ceilDiv(long a, long b) {
    if (a <= 0) {
      return 0L;
    }
    return (a + b - 1) / b;
  }
}
