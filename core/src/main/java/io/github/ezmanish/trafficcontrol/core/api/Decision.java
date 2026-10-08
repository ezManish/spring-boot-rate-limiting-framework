package io.github.ezmanish.trafficcontrol.core.api;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * Result of evaluating rate limit rules for a request.
 *
 * @param outcome ALLOW or REJECT
 * @param limit limit value of the binding rule
 * @param remaining remaining permits of the binding rule
 * @param resetAt instant when the binding rule is fully replenished
 * @param retryAfter minimum wait before a rejected request may succeed
 * @param policyName name of the evaluated policy
 * @param ruleId id of the binding rule
 * @param degraded whether this decision was made in degraded mode (e.g. fail-open)
 * @param permitId optional concurrency lease permit ID (if acquired)
 */
public record Decision(
    Outcome outcome,
    long limit,
    long remaining,
    Instant resetAt,
    Duration retryAfter,
    String policyName,
    String ruleId,
    boolean degraded,
    String permitId) {

  public Decision {
    Objects.requireNonNull(outcome, "Outcome cannot be null");
    if (retryAfter == null) {
      retryAfter = Duration.ZERO;
    }
  }

  public boolean allowed() {
    return outcome == Outcome.ALLOW;
  }

  public static Decision allow(
      long limit,
      long remaining,
      Instant resetAt,
      String policyName,
      String ruleId,
      String permitId) {
    return new Decision(
        Outcome.ALLOW,
        limit,
        remaining,
        resetAt,
        Duration.ZERO,
        policyName,
        ruleId,
        false,
        permitId);
  }

  public static Decision reject(
      long limit,
      long remaining,
      Instant resetAt,
      Duration retryAfter,
      String policyName,
      String ruleId) {
    return new Decision(
        Outcome.REJECT, limit, remaining, resetAt, retryAfter, policyName, ruleId, false, null);
  }

  public static Decision degradedAllow(String policyName) {
    return new Decision(
        Outcome.ALLOW, 0, 0, Instant.EPOCH, Duration.ZERO, policyName, null, true, null);
  }

  public static Decision degradedReject(String policyName, Duration retryAfter) {
    return new Decision(
        Outcome.REJECT, 0, 0, Instant.EPOCH, retryAfter, policyName, null, true, null);
  }
}
