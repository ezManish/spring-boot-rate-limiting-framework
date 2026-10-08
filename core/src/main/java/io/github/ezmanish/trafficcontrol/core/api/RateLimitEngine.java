package io.github.ezmanish.trafficcontrol.core.api;

/** Main evaluation engine for rate limiting decisions. */
public interface RateLimitEngine {

  Decision evaluate(RequestContext ctx, Policy policy);
}
