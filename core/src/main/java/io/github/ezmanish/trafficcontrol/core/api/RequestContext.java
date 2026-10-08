package io.github.ezmanish.trafficcontrol.core.api;

import java.util.Objects;

/**
 * Context of an incoming request evaluated by the rate limit engine.
 *
 * @param clientKey resolved identity key of the client
 * @param policyName name of the matched policy
 * @param plan optional subscription plan name (e.g. FREE, PRO)
 * @param cost permit cost of the request (defaults to 1)
 */
public record RequestContext(String clientKey, String policyName, String plan, long cost) {

  public RequestContext {
    Objects.requireNonNull(clientKey, "Client key cannot be null");
    Objects.requireNonNull(policyName, "Policy name cannot be null");
    if (cost <= 0) {
      throw new IllegalArgumentException("Cost must be > 0, got: " + cost);
    }
  }

  public static RequestContext of(String clientKey, String policyName) {
    return new RequestContext(clientKey, policyName, null, 1L);
  }

  public static RequestContext of(String clientKey, String policyName, long cost) {
    return new RequestContext(clientKey, policyName, null, cost);
  }
}
