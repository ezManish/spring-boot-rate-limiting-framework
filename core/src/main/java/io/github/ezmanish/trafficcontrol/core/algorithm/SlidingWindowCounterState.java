package io.github.ezmanish.trafficcontrol.core.algorithm;

/**
 * State for the Sliding Window Counter approximation algorithm.
 *
 * @param previousCount count of requests in previous window
 * @param currentCount count of requests in current window
 * @param windowStartMs epoch millis of the current window start
 */
public record SlidingWindowCounterState(long previousCount, long currentCount, long windowStartMs)
    implements AlgorithmState {}
