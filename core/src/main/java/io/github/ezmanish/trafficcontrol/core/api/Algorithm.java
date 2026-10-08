package io.github.ezmanish.trafficcontrol.core.api;

/** Supported rate-limiting algorithms. */
public enum Algorithm {
  TOKEN_BUCKET(1),
  GCRA(2),
  FIXED_WINDOW(3),
  SLIDING_WINDOW_COUNTER(4),
  SLIDING_WINDOW_LOG(5);

  private final int id;

  Algorithm(int id) {
    this.id = id;
  }

  public int id() {
    return id;
  }

  public static Algorithm fromString(String name) {
    if (name == null) {
      throw new IllegalArgumentException("Algorithm name cannot be null");
    }
    String normalized = name.trim().toUpperCase();
    if ("LEAKY_BUCKET".equals(normalized)) {
      return GCRA;
    }
    return Algorithm.valueOf(normalized);
  }
}
