package io.github.ezmanish.trafficcontrol.store.redis;

import java.util.Objects;

/**
 * Formats Redis keys with cluster hash tags ({clientHash}) ensuring all keys for a client-policy
 * pair map to the identical Redis Cluster hash slot, preventing CROSSSLOT errors.
 */
public final class RedisKeyFormatter {

  private static final String PREFIX = "tc";

  private RedisKeyFormatter() {}

  /** Builds key for a rate limit rule: tc:{<clientHash>}:<policyName>:<ruleId> */
  public static String formatRuleKey(String clientHash, String policyName, String ruleId) {
    Objects.requireNonNull(clientHash, "clientHash cannot be null");
    Objects.requireNonNull(policyName, "policyName cannot be null");
    Objects.requireNonNull(ruleId, "ruleId cannot be null");
    return PREFIX + ":{" + clientHash + "}:" + policyName + ":" + ruleId;
  }

  /** Builds key for concurrency permit tracking: tc:{<clientHash>}:<policyName>:conc */
  public static String formatConcurrencyKey(String clientHash, String policyName) {
    Objects.requireNonNull(clientHash, "clientHash cannot be null");
    Objects.requireNonNull(policyName, "policyName cannot be null");
    return PREFIX + ":{" + clientHash + "}:" + policyName + ":conc";
  }
}
