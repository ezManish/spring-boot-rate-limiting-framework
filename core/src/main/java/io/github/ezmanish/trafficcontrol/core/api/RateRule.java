package io.github.ezmanish.trafficcontrol.core.api;

import java.time.Duration;
import java.util.Objects;

/**
 * A single rate rule within a policy.
 *
 * @param id unique identifier within the policy
 * @param algorithm algorithm to evaluate this rule
 * @param requests number of allowed requests/permits per window
 * @param window duration of the rate limit window
 * @param burst maximum burst capacity (for Token Bucket and GCRA; defaults to requests)
 */
public record RateRule(String id, Algorithm algorithm, long requests, Duration window, long burst) {

  public RateRule {
    Objects.requireNonNull(id, "Rule id cannot be null");
    Objects.requireNonNull(algorithm, "Algorithm cannot be null");
    Objects.requireNonNull(window, "Window cannot be null");
    if (requests <= 0) {
      throw new IllegalArgumentException("Requests must be > 0, got: " + requests);
    }
    if (burst <= 0) {
      throw new IllegalArgumentException("Burst must be > 0, got: " + burst);
    }
  }

  public static RateRule of(String id, Algorithm algorithm, long requests, Duration window) {
    return new RateRule(id, algorithm, requests, window, requests);
  }

  public static RateRule of(long requests, Duration window) {
    return new RateRule("r1", Algorithm.TOKEN_BUCKET, requests, window, requests);
  }
}
