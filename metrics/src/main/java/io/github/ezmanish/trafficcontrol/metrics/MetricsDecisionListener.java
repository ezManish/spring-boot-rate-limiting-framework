package io.github.ezmanish.trafficcontrol.metrics;

import io.github.ezmanish.trafficcontrol.core.api.Decision;
import io.github.ezmanish.trafficcontrol.core.api.RequestContext;
import io.github.ezmanish.trafficcontrol.core.spi.DecisionListener;
import java.util.Objects;

/** Bridges RateLimitEngine decision events to Micrometer metrics (TC-080..084). */
public class MetricsDecisionListener implements DecisionListener {

  private final TrafficControlMetrics metrics;

  public MetricsDecisionListener(TrafficControlMetrics metrics) {
    this.metrics = Objects.requireNonNull(metrics, "TrafficControlMetrics cannot be null");
  }

  @Override
  public void onDecision(Decision decision, RequestContext context) {
    // Latency is recorded as a standard decision event
    metrics.recordDecision(decision, context, 0L);
  }

  public TrafficControlMetrics getMetrics() {
    return metrics;
  }
}
