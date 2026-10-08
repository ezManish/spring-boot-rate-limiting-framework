package io.github.ezmanish.trafficcontrol.core.algorithm;

/**
 * State for the Token Bucket algorithm.
 *
 * @param tokens current micro-tokens (1 token = window_ms units)
 * @param lastRefillMs timestamp in epoch milliseconds of the last refill
 */
public record TokenBucketState(long tokens, long lastRefillMs) implements AlgorithmState {}
