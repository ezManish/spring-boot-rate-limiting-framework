package io.github.ezmanish.trafficcontrol.core.adaptive;

/** Snapshot of host system performance telemetry sampled at interval T = 5s (docs/21 §2). */
public record AdaptiveSignals(double cpu, double inflightRatio, double p95Ms, double err5xx) {

  public boolean hasInvalidSignals() {
    return Double.isNaN(cpu)
        || Double.isNaN(inflightRatio)
        || Double.isNaN(p95Ms)
        || Double.isNaN(err5xx)
        || Double.isInfinite(cpu)
        || Double.isInfinite(inflightRatio)
        || Double.isInfinite(p95Ms)
        || Double.isInfinite(err5xx);
  }
}
