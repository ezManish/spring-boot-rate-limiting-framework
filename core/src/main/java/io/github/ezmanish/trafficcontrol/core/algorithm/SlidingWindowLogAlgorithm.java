package io.github.ezmanish.trafficcontrol.core.algorithm;

import io.github.ezmanish.trafficcontrol.core.api.Algorithm;
import io.github.ezmanish.trafficcontrol.core.api.RateRule;
import java.util.ArrayList;
import java.util.List;

/**
 * Sliding Window Log algorithm matching docs/19_ALGORITHM_SPECIFICATION.md §5. Strictly bounded
 * memory O(R) where R <= 10,000 (TC-115).
 */
public class SlidingWindowLogAlgorithm implements RateLimitAlgorithm {

  @Override
  public Algorithm algorithm() {
    return Algorithm.SLIDING_WINDOW_LOG;
  }

  @Override
  public EvaluationResult evaluate(
      long nowMs, RateRule rule, long cost, AlgorithmState currentState) {
    long r = rule.requests();
    long w = rule.window().toMillis();
    long windowStart = nowMs - w;

    List<Long> activeTimestamps = new ArrayList<>();
    if (currentState instanceof SlidingWindowLogState state) {
      for (Long ts : state.timestamps()) {
        if (ts > windowStart) { // window is (now - W, now]
          activeTimestamps.add(ts);
        }
      }
    }

    int count = activeTimestamps.size();
    long newestScore =
        activeTimestamps.isEmpty() ? nowMs : activeTimestamps.get(activeTimestamps.size() - 1);

    if (count + cost <= r) {
      for (int i = 0; i < cost; i++) {
        activeTimestamps.add(nowMs);
      }
      long remaining = Math.max(0L, r - count - cost);
      long resetAtMs = nowMs + w;
      SlidingWindowLogState newState = new SlidingWindowLogState(activeTimestamps);
      return new EvaluationResult(true, remaining, r, resetAtMs, 0L, newState);
    } else {
      long remaining = Math.max(0L, r - count);
      int k = (int) (count + cost - r); // k-th item (1-based)
      long retryAfterMs = w;
      if (k >= 1 && k <= activeTimestamps.size()) {
        long kScore = activeTimestamps.get(k - 1);
        retryAfterMs = Math.max(1L, kScore + w - nowMs);
      }
      long resetAtMs = newestScore + w;
      return new EvaluationResult(
          false,
          remaining,
          r,
          resetAtMs,
          retryAfterMs,
          new SlidingWindowLogState(activeTimestamps));
    }
  }
}
