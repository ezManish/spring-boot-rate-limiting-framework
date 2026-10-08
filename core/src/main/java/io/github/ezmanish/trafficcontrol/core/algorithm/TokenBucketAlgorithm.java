package io.github.ezmanish.trafficcontrol.core.algorithm;

import io.github.ezmanish.trafficcontrol.core.api.Algorithm;
import io.github.ezmanish.trafficcontrol.core.api.RateRule;

/**
 * Token Bucket algorithm implementation matching docs/19_ALGORITHM_SPECIFICATION.md §1. Uses
 * micro-token integer arithmetic where 1 token = W units.
 */
public class TokenBucketAlgorithm implements RateLimitAlgorithm {

  @Override
  public Algorithm algorithm() {
    return Algorithm.TOKEN_BUCKET;
  }

  @Override
  public EvaluationResult evaluate(
      long nowMs, RateRule rule, long cost, AlgorithmState currentState) {
    long r = rule.requests();
    long w = rule.window().toMillis();
    long b = rule.burst();
    long cap = b * w;

    // If requested cost exceeds burst capacity, request can never succeed
    if (cost > b) {
      return new EvaluationResult(false, 0L, b, nowMs + w, w, currentState);
    }

    long t;
    long ts;

    if (currentState instanceof TokenBucketState state) {
      t = state.tokens();
      ts = state.lastRefillMs();
    } else {
      t = cap;
      ts = nowMs;
    }

    long maxElapsed = ceilDiv(cap, r);
    long elapsed = Math.min(Math.max(0L, nowMs - ts), maxElapsed);
    t = Math.min(cap, t + elapsed * r);
    ts = nowMs;

    long need = cost * w;
    boolean allowed = t >= need;

    if (allowed) {
      long newTokens = t - need;
      long remaining = newTokens / w;
      long resetAtMs = nowMs + ceilDiv(cap - newTokens, r);
      TokenBucketState newState = new TokenBucketState(newTokens, ts);
      return new EvaluationResult(true, remaining, b, resetAtMs, 0L, newState);
    } else {
      long remaining = t / w;
      long resetAtMs = nowMs + ceilDiv(cap - t, r);
      long retryAfterMs = ceilDiv(need - t, r);
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
