package io.github.ezmanish.trafficcontrol.store.redis;

import io.github.ezmanish.trafficcontrol.core.api.Algorithm;
import io.github.ezmanish.trafficcontrol.core.api.ConcurrencyRule;
import io.github.ezmanish.trafficcontrol.core.api.RateRule;
import io.github.ezmanish.trafficcontrol.core.spi.RateLimitStore;
import io.github.ezmanish.trafficcontrol.core.spi.StoreResult;
import io.lettuce.core.api.sync.RedisCommands;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Distributed Redis implementation of RateLimitStore using atomic Lua scripting (tc_decide).
 * Guarantees all-or-nothing multi-rule atomicity across Redis Cluster nodes using hash tags.
 */
public class RedisRateLimitStore implements RateLimitStore {

  private static final Logger log = LoggerFactory.getLogger(RedisRateLimitStore.class);

  private final RedisCommands<String, String> commands;
  private final RedisScriptManager scriptManager;
  private final CircuitBreaker circuitBreaker;

  public RedisRateLimitStore(
      RedisCommands<String, String> commands,
      RedisScriptManager scriptManager,
      CircuitBreaker circuitBreaker) {
    this.commands = Objects.requireNonNull(commands, "RedisCommands cannot be null");
    this.scriptManager = scriptManager != null ? scriptManager : new RedisScriptManager();
    this.circuitBreaker = circuitBreaker != null ? circuitBreaker : new CircuitBreaker();
  }

  public RedisRateLimitStore(RedisCommands<String, String> commands) {
    this(commands, new RedisScriptManager(), new CircuitBreaker());
  }

  @Override
  public StoreResult tryConsume(
      String clientKey,
      String policyName,
      List<RateRule> rules,
      long cost,
      ConcurrencyRule concurrency,
      String eventId) {

    if (!circuitBreaker.allowRequest()) {
      throw new RedisStoreUnavailableException(
          "Redis circuit breaker is open", circuitBreaker.getRemainingWaitMs());
    }

    List<RateRule> safeRules = rules != null ? rules : List.of();
    boolean hasConc = concurrency != null && concurrency.max() > 0;
    int n = safeRules.size();

    // Build KEYS with {clientKey} hash tags to ensure exact slot alignment in Redis Cluster
    List<String> keyList = new ArrayList<>(n + (hasConc ? 1 : 0));
    for (RateRule rule : safeRules) {
      keyList.add(RedisKeyFormatter.formatRuleKey(clientKey, policyName, rule.id()));
    }
    if (hasConc) {
      keyList.add(RedisKeyFormatter.formatConcurrencyKey(clientKey, policyName));
    }
    String[] keys = keyList.toArray(new String[0]);

    // Build ARGV
    String effectiveEventId =
        (eventId != null && !eventId.isBlank()) ? eventId : java.util.UUID.randomUUID().toString();
    List<String> argList = new ArrayList<>();
    argList.add("1"); // ARGV[1]: script_version
    argList.add(String.valueOf(n)); // ARGV[2]: n
    argList.add(hasConc ? "1" : "0"); // ARGV[3]: has_conc
    argList.add(String.valueOf(Math.max(1, cost))); // ARGV[4]: cost
    argList.add(effectiveEventId); // ARGV[5]: event_id

    // Rule parameters: algo_id, requests, window_ms, burst, reserved
    for (RateRule rule : safeRules) {
      argList.add(String.valueOf(toAlgoId(rule.algorithm())));
      argList.add(String.valueOf(rule.requests()));
      argList.add(String.valueOf(rule.window().toMillis()));
      argList.add(String.valueOf(rule.burst()));
      argList.add("0"); // reserved
    }

    // Concurrency parameters: max, lease_ttl_ms, key_ttl_ms
    if (hasConc) {
      argList.add(String.valueOf(concurrency.max()));
      long leaseTtlMs = concurrency.leaseTtl().toMillis();
      argList.add(String.valueOf(leaseTtlMs));
      argList.add(String.valueOf(leaseTtlMs * 2)); // key TTL
    }
    String[] args = argList.toArray(new String[0]);

    try {
      List<Long> result = scriptManager.evalDecide(commands, keys, args);
      circuitBreaker.recordSuccess();

      // Output format: [allowed, binding_rule, reason, remaining, limit, reset_at_ms,
      // retry_after_ms, now_ms]
      boolean allowed = result.get(0) == 1L;
      int bindingRuleIndex = result.get(1).intValue();
      int reason = result.get(2).intValue();
      long remaining = result.get(3);
      long limit = result.get(4);
      long resetAtMs = result.get(5);
      long retryAfterMs = result.get(6);
      String permitId = (allowed && hasConc) ? effectiveEventId : null;

      return new StoreResult(
          allowed, bindingRuleIndex, reason, remaining, limit, resetAtMs, retryAfterMs, permitId);
    } catch (Throwable t) {
      circuitBreaker.recordFailure();
      log.error(
          "Failed to execute rate limit decision script against Redis for key: {}", clientKey, t);
      throw new RedisStoreUnavailableException(
          "Redis store error: " + t.getMessage(), circuitBreaker.getRemainingWaitMs());
    }
  }

  @Override
  public void release(String clientKey, String policyName, String permitId) {
    if (permitId == null || permitId.isBlank()) {
      return;
    }
    try {
      String concKey = RedisKeyFormatter.formatConcurrencyKey(clientKey, policyName);
      commands.zrem(concKey, permitId);
    } catch (Throwable t) {
      // TC-055: Release failure is logged, permit will be reclaimed by lease TTL
      log.warn(
          "Failed to release concurrency permit in Redis (key={}, permit={}): {}",
          clientKey,
          permitId,
          t.getMessage());
    }
  }

  public CircuitBreaker getCircuitBreaker() {
    return circuitBreaker;
  }

  private int toAlgoId(Algorithm algo) {
    if (algo == null) {
      return 1;
    }
    return switch (algo) {
      case TOKEN_BUCKET -> 1;
      case GCRA -> 2;
      case FIXED_WINDOW -> 3;
      case SLIDING_WINDOW_COUNTER -> 4;
      case SLIDING_WINDOW_LOG -> 5;
    };
  }
}
