package io.github.ezmanish.trafficcontrol.core.api;

import java.time.Duration;

/**
 * Concurrency limiting specification for a policy.
 *
 * @param max maximum concurrent permits
 * @param waitDuration maximum duration to wait for a permit (0 = fail immediately)
 * @param leaseTtl TTL for distributed permits to prevent leaks on crash
 */
public record ConcurrencyRule(int max, Duration waitDuration, Duration leaseTtl) {

  public ConcurrencyRule {
    if (max <= 0) {
      throw new IllegalArgumentException("Concurrency max must be > 0, got: " + max);
    }
    if (waitDuration == null) {
      waitDuration = Duration.ZERO;
    }
    if (leaseTtl == null) {
      leaseTtl = Duration.ofSeconds(30);
    }
  }

  public static ConcurrencyRule of(int max) {
    return new ConcurrencyRule(max, Duration.ZERO, Duration.ofSeconds(30));
  }
}
