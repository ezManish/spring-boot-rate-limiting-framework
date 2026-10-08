package io.github.ezmanish.trafficcontrol.core.algorithm;

import io.github.ezmanish.trafficcontrol.core.api.Algorithm;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

/** Registry of available RateLimitAlgorithm implementations. */
public class RateLimitAlgorithmRegistry {

  private final Map<Algorithm, RateLimitAlgorithm> algorithms = new EnumMap<>(Algorithm.class);

  public RateLimitAlgorithmRegistry() {
    register(new TokenBucketAlgorithm());
    register(new FixedWindowAlgorithm());
  }

  public void register(RateLimitAlgorithm algorithm) {
    Objects.requireNonNull(algorithm, "Algorithm cannot be null");
    algorithms.put(algorithm.algorithm(), algorithm);
  }

  public RateLimitAlgorithm get(Algorithm algorithm) {
    RateLimitAlgorithm impl = algorithms.get(algorithm);
    if (impl == null) {
      throw new UnsupportedOperationException("Algorithm " + algorithm + " is not registered");
    }
    return impl;
  }
}
