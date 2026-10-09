package io.github.ezmanish.trafficcontrol.core.algorithm;

import io.github.ezmanish.trafficcontrol.core.api.Algorithm;
import io.github.ezmanish.trafficcontrol.core.api.RateRule;

/**
 * Generic Cell Rate Algorithm (GCRA / Leaky Bucket as meter) implementation matching
 * docs/19_ALGORITHM_SPECIFICATION.md §2. Tracks rate via a single Theoretical Arrival Time (TAT) in
 * microseconds.
 */
public class GcraAlgorithm implements RateLimitAlgorithm {

  @Override
  public Algorithm algorithm() {
    return Algorithm.GCRA;
  }

  @Override
  public EvaluationResult evaluate(
      long nowMs, RateRule rule, long cost, AlgorithmState currentState) {
    long r = rule.requests();
    long wMs = rule.window().toMillis();
    long b = rule.burst();

    // Convert window to microseconds
    long wUs = wMs * 1000L;
    long tUs = ceilDiv(wUs, r); // Emission interval T
    long tauUs = b * tUs; // Burst tolerance tau

    long nowUs = nowMs * 1000L;

    // If requested cost exceeds burst capacity, request can never succeed
    if (cost > b) {
      return new EvaluationResult(false, 0L, b, nowMs + wMs, wMs, currentState);
    }

    long tatUs;
    if (currentState instanceof GcraState state) {
      tatUs = state.theoreticalArrivalTimeUs();
    } else {
      tatUs = nowUs;
    }

    long tat0 = Math.max(tatUs, nowUs);
    long newTat = tat0 + cost * tUs;

    boolean allowed = (newTat - nowUs) <= tauUs;

    if (allowed) {
      long finalTat = newTat;
      long remaining = Math.max(0L, (tauUs - (finalTat - nowUs)) / tUs);
      long resetAtMs = ceilDiv(finalTat, 1000L);
      GcraState newState = new GcraState(finalTat);
      return new EvaluationResult(true, remaining, b, resetAtMs, 0L, newState);
    } else {
      long remaining = Math.max(0L, (tauUs - (tat0 - nowUs)) / tUs);
      long resetAtMs = ceilDiv(tat0, 1000L);
      long retryAfterMs = ceilDiv(newTat - tauUs - nowUs, 1000L);
      return new EvaluationResult(false, remaining, b, resetAtMs, retryAfterMs, currentState);
    }
  }

  private static long ceilDiv(long a, long b) {
    if (a <= 0) {
      return 0L;
    }
    return (a + b - 1) / b;
  }
}
