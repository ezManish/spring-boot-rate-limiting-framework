package io.github.ezmanish.trafficcontrol.core.api;

import java.time.Duration;

/**
 * Adaptive controller configuration for a policy.
 *
 * @param enabled whether adaptive limiting is enabled
 * @param minMultiplier minimum multiplier to scale down limits (default 0.25)
 * @param targetP95 target p95 latency threshold for triggering load shedding
 */
public record AdaptiveConfig(boolean enabled, double minMultiplier, Duration targetP95) {

  public AdaptiveConfig {
    if (minMultiplier <= 0.0 || minMultiplier >= 1.0) {
      throw new IllegalArgumentException(
          "minMultiplier must be in (0.0, 1.0), got: " + minMultiplier);
    }
  }

  public static AdaptiveConfig disabled() {
    return new AdaptiveConfig(false, 0.25, Duration.ofMillis(200));
  }
}
