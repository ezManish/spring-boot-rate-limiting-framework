package io.github.ezmanish.trafficcontrol.core.spi;

import io.github.ezmanish.trafficcontrol.core.api.ConcurrencyRule;
import io.github.ezmanish.trafficcontrol.core.api.RateRule;
import java.util.List;

/**
 * Storage SPI for rate limit and concurrency permit state. Implementations must guarantee atomic,
 * all-or-nothing multi-rule evaluation.
 */
public interface RateLimitStore {

  /**
   * Atomically evaluate all rate rules and optional concurrency permit. Commit state changes ONLY
   * if all pass; otherwise commit nothing.
   *
   * @param clientKey client identity key
   * @param policyName policy name
   * @param rules list of rate rules to evaluate
   * @param cost permit cost
   * @param concurrency optional concurrency rule
   * @param eventId unique event/lease id
   * @return StoreResult containing decision details
   */
  StoreResult tryConsume(
      String clientKey,
      String policyName,
      List<RateRule> rules,
      long cost,
      ConcurrencyRule concurrency,
      String eventId);

  /**
   * Release an acquired concurrency permit.
   *
   * @param clientKey client identity key
   * @param policyName policy name
   * @param permitId permit/lease id returned from tryConsume
   */
  void release(String clientKey, String policyName, String permitId);
}
