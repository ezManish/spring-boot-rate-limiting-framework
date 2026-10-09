package io.github.ezmanish.trafficcontrol.core.algorithm;

/**
 * State for the Generic Cell Rate Algorithm (GCRA / Leaky Bucket as meter).
 *
 * @param theoreticalArrivalTimeUs theoretical arrival time (TAT) in epoch microseconds
 */
public record GcraState(long theoreticalArrivalTimeUs) implements AlgorithmState {}
