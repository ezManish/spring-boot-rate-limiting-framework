package io.github.ezmanish.trafficcontrol.core.algorithm;

import java.util.List;

/** Immutable state holding timestamps of admitted requests for Sliding Window Log (docs/19 §5). */
public record SlidingWindowLogState(List<Long> timestamps) implements AlgorithmState {

  public SlidingWindowLogState {
    timestamps = timestamps != null ? List.copyOf(timestamps) : List.of();
  }
}
