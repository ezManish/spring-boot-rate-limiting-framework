package io.github.ezmanish.trafficcontrol.store.redis;

/** Exception thrown when Redis store cannot be reached or its circuit breaker is open. */
public class RedisStoreUnavailableException extends RuntimeException {

  private final long retryAfterMs;

  public RedisStoreUnavailableException(String message, long retryAfterMs) {
    super(message);
    this.retryAfterMs = retryAfterMs;
  }

  public RedisStoreUnavailableException(String message) {
    this(message, 1000L);
  }

  public long getRetryAfterMs() {
    return retryAfterMs;
  }
}
