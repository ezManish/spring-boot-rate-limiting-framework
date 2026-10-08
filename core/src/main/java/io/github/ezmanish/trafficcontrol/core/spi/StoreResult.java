package io.github.ezmanish.trafficcontrol.core.spi;

/**
 * Result returned by a RateLimitStore evaluation.
 *
 * @param allowed whether the request is allowed (all rules passed)
 * @param bindingRuleIndex 1-based index of the binding rule (0 = concurrency)
 * @param reason 0 = none, 1 = rate limit exceeded, 2 = concurrency limit exceeded
 * @param remaining remaining permits for the binding rule
 * @param limit limit capacity for the binding rule
 * @param resetAtMs timestamp in epoch milliseconds when replenished
 * @param retryAfterMs wait time in milliseconds on denial (0 on allow)
 * @param permitId lease ID if a concurrency permit was acquired
 */
public record StoreResult(
    boolean allowed,
    int bindingRuleIndex,
    int reason,
    long remaining,
    long limit,
    long resetAtMs,
    long retryAfterMs,
    String permitId) {

  public static StoreResult allow(
      int bindingRuleIndex, long remaining, long limit, long resetAtMs, String permitId) {
    return new StoreResult(true, bindingRuleIndex, 0, remaining, limit, resetAtMs, 0L, permitId);
  }

  public static StoreResult denyRate(
      int bindingRuleIndex, long remaining, long limit, long resetAtMs, long retryAfterMs) {
    return new StoreResult(
        false, bindingRuleIndex, 1, remaining, limit, resetAtMs, retryAfterMs, null);
  }

  public static StoreResult denyConcurrency(long currentInFlight, long max, long retryAfterMs) {
    return new StoreResult(false, 0, 2, 0L, max, 0L, retryAfterMs, null);
  }
}
