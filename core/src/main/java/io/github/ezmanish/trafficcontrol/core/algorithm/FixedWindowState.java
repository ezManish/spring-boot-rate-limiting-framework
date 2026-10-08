package io.github.ezmanish.trafficcontrol.core.algorithm;

/**
 * State for the Fixed Window algorithm.
 *
 * @param count current number of requests consumed in the window
 * @param windowStartMs epoch millis of the aligned window start
 */
public record FixedWindowState(long count, long windowStartMs) implements AlgorithmState {}
