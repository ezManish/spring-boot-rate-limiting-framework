package io.github.ezmanish.trafficcontrol.core.algorithm;

import io.github.ezmanish.trafficcontrol.core.api.Algorithm;
import io.github.ezmanish.trafficcontrol.core.api.RateRule;

/**
 * Fixed Window algorithm implementation matching docs/19_ALGORITHM_SPECIFICATION.md §3. Uses
 * epoch-aligned windows: ws = floor(now_ms / W) * W.
 */
public class FixedWindowAlgorithm implements RateLimitAlgorithm {

  @Override
  public Algorithm algorithm() {
    return Algorithm.FIXED_WINDOW;
  }

  @Override
  public EvaluationResult evaluate(
      long nowMs, RateRule rule, long cost, AlgorithmState currentState) {
    long r = rule.requests();
    long w = rule.window().toMillis();
    long ws = (nowMs / w) * w;

    long count = 0L;
    if (currentState instanceof FixedWindowState state) {
      if (state.windowStartMs() == ws) {
        count = state.count();
      }
    }

    long resetAtMs = ws + w;

    if (count + cost <= r) {
      long newCount = count + cost;
      long remaining = Math.max(0L, r - newCount);
      FixedWindowState newState = new FixedWindowState(newCount, ws);
      return new EvaluationResult(true, remaining, r, resetAtMs, 0L, newState);
    } else {
      long remaining = Math.max(0L, r - count);
      long retryAfterMs = resetAtMs - nowMs;
      return new EvaluationResult(false, remaining, r, resetAtMs, retryAfterMs, currentState);
    }
  }
}
