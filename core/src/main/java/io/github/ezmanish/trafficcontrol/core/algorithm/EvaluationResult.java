package io.github.ezmanish.trafficcontrol.core.algorithm;

/**
 * Result of evaluating a single rule through an algorithm.
 *
 * @param allowed whether this rule permitted the request
 * @param remaining remaining permits after consumption (if allowed) or current remaining (if
 *     denied)
 * @param limit limit capacity of the rule
 * @param resetAtMs epoch millis when the rule will be fully replenished
 * @param retryAfterMs wait millis until request of this cost would be allowed (0 if allowed)
 * @param newState state to commit if all rules in the policy pass
 */
public record EvaluationResult(
    boolean allowed,
    long remaining,
    long limit,
    long resetAtMs,
    long retryAfterMs,
    AlgorithmState newState) {}
