package io.github.ezmanish.trafficcontrol.core.api;

/** Behavior when the backing rate limit store is unavailable or circuit breaker is open. */
public enum FailMode {
  FAIL_OPEN,
  FAIL_CLOSED
}
