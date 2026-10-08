package io.github.ezmanish.trafficcontrol.core.api;

/**
 * Key extraction strategies for identifying clients. Note: PLAN is not a key type; plans select
 * limit sets via PlanResolver (ADR-037).
 */
public enum RateLimitKey {
  USER,
  IP,
  API_KEY,
  JWT_CLAIM,
  TENANT,
  ENDPOINT,
  GLOBAL,
  COMPOSITE,
  CUSTOM
}
