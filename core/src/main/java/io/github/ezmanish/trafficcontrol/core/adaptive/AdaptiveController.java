package io.github.ezmanish.trafficcontrol.core.adaptive;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Local AIMD Adaptive Rate Limiting Controller matching docs/21_ADAPTIVE_SPECIFICATION.md. Scales
 * rate limits dynamically based on CPU, concurrency in-flight ratio, p95 latency, and 5xx errors.
 */
public class AdaptiveController {

  private final double minMultiplier;
  private final double targetP95Ms;
  private final AtomicBoolean enabled = new AtomicBoolean(true);
  private final AtomicBoolean frozen = new AtomicBoolean(false);

  private double multiplier = 1.0;
  private int consecutiveOverloaded = 0;
  private int consecutiveHealthy = 0;
  private long cooldownUntilMs = 0L;

  public AdaptiveController(double minMultiplier, double targetP95Ms, boolean enabled) {
    this.minMultiplier = Math.max(0.01, Math.min(1.0, minMultiplier));
    this.targetP95Ms = targetP95Ms > 0 ? targetP95Ms : 100.0;
    this.enabled.set(enabled);
  }

  public AdaptiveController(double targetP95Ms) {
    this(0.25, targetP95Ms, true);
  }

  /** Evaluates telemetry signals for the sampling interval (T = 5s). */
  public synchronized void evaluateInterval(AdaptiveSignals signals, long nowMs) {
    if (!enabled.get() || frozen.get() || signals == null || signals.hasInvalidSignals()) {
      consecutiveOverloaded = 0;
      consecutiveHealthy = 0;
      return;
    }

    // 1. Overload definition: any signal crosses critical threshold
    boolean isOverloaded =
        signals.cpu() > 0.85
            || signals.inflightRatio() > 0.80
            || signals.p95Ms() > targetP95Ms
            || signals.err5xx() > 0.05;

    // 2. Healthy definition: all signals strictly within healthy bounds
    boolean isHealthy =
        signals.cpu() < 0.70
            && signals.inflightRatio() < 0.60
            && signals.p95Ms() < 0.8 * targetP95Ms
            && signals.err5xx() < 0.01;

    if (isOverloaded) {
      consecutiveOverloaded++;
      consecutiveHealthy = 0;

      // Multiplicative Decrease: 2 consecutive overloaded intervals + cooldown elapsed
      if (consecutiveOverloaded >= 2 && nowMs >= cooldownUntilMs) {
        multiplier = Math.max(minMultiplier, multiplier * 0.7);
        cooldownUntilMs = nowMs + 15_000L; // 15s cooldown
      }
    } else if (isHealthy) {
      consecutiveHealthy++;
      consecutiveOverloaded = 0;

      // Additive Increase: 6 consecutive healthy intervals (30s)
      if (consecutiveHealthy >= 6) {
        multiplier = Math.min(1.0, multiplier + 0.05);
        consecutiveHealthy = 0;
      }
    } else {
      // Neutral interval
      consecutiveOverloaded = 0;
      consecutiveHealthy = 0;
    }
  }

  public long scaleRequests(long requests) {
    if (!enabled.get()) {
      return requests;
    }
    return Math.max(1L, (long) Math.floor(requests * getMultiplier()));
  }

  public long scaleBurst(long burst) {
    if (!enabled.get()) {
      return burst;
    }
    return Math.max(1L, (long) Math.floor(burst * getMultiplier()));
  }

  public synchronized double getMultiplier() {
    return multiplier;
  }

  public synchronized void setMultiplier(double m) {
    this.multiplier = Math.max(minMultiplier, Math.min(1.0, m));
  }

  public boolean isEnabled() {
    return enabled.get();
  }

  public void setEnabled(boolean enabled) {
    this.enabled.set(enabled);
  }

  public boolean isFrozen() {
    return frozen.get();
  }

  public void setFrozen(boolean frozen) {
    this.frozen.set(frozen);
  }

  public double getMinMultiplier() {
    return minMultiplier;
  }

  public double getTargetP95Ms() {
    return targetP95Ms;
  }
}
