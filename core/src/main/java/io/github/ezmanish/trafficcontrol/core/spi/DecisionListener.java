package io.github.ezmanish.trafficcontrol.core.spi;

import io.github.ezmanish.trafficcontrol.core.api.Decision;
import io.github.ezmanish.trafficcontrol.core.api.RequestContext;

/** Listener invoked upon every rate limiting decision (for metrics, auditing, and logging). */
@FunctionalInterface
public interface DecisionListener {

  void onDecision(Decision decision, RequestContext context);
}
