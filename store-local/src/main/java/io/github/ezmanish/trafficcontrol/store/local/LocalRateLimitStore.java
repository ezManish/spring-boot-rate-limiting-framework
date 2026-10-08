package io.github.ezmanish.trafficcontrol.store.local;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.github.ezmanish.trafficcontrol.core.algorithm.AlgorithmState;
import io.github.ezmanish.trafficcontrol.core.algorithm.EvaluationResult;
import io.github.ezmanish.trafficcontrol.core.algorithm.RateLimitAlgorithm;
import io.github.ezmanish.trafficcontrol.core.algorithm.RateLimitAlgorithmRegistry;
import io.github.ezmanish.trafficcontrol.core.api.ConcurrencyRule;
import io.github.ezmanish.trafficcontrol.core.api.RateRule;
import io.github.ezmanish.trafficcontrol.core.spi.Clock;
import io.github.ezmanish.trafficcontrol.core.spi.RateLimitStore;
import io.github.ezmanish.trafficcontrol.core.spi.StoreResult;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/**
 * In-memory thread-safe implementation of RateLimitStore backed by Caffeine. Provides atomic,
 * all-or-nothing multi-rule evaluation and local concurrency tracking.
 */
public class LocalRateLimitStore implements RateLimitStore {

  private static final int STRIPE_COUNT = 256;
  private final ReentrantLock[] locks = new ReentrantLock[STRIPE_COUNT];

  private final Cache<String, AlgorithmState> stateCache;
  private final Map<String, Set<String>> concurrencyPermits = new ConcurrentHashMap<>();
  private final RateLimitAlgorithmRegistry algorithmRegistry;
  private final Clock clock;

  public LocalRateLimitStore(
      long maxKeys, Duration idleTtl, RateLimitAlgorithmRegistry registry, Clock clock) {
    for (int i = 0; i < STRIPE_COUNT; i++) {
      locks[i] = new ReentrantLock();
    }
    this.stateCache =
        Caffeine.newBuilder()
            .maximumSize(maxKeys > 0 ? maxKeys : 100_000)
            .expireAfterAccess(
                idleTtl != null ? idleTtl.toMillis() : TimeUnit.HOURS.toMillis(1),
                TimeUnit.MILLISECONDS)
            .build();
    this.algorithmRegistry = registry != null ? registry : new RateLimitAlgorithmRegistry();
    this.clock = clock != null ? clock : Clock.system();
  }

  public LocalRateLimitStore() {
    this(100_000, Duration.ofMinutes(10), new RateLimitAlgorithmRegistry(), Clock.system());
  }

  public LocalRateLimitStore(Clock clock) {
    this(100_000, Duration.ofMinutes(10), new RateLimitAlgorithmRegistry(), clock);
  }

  @Override
  public StoreResult tryConsume(
      String clientKey,
      String policyName,
      List<RateRule> rules,
      long cost,
      ConcurrencyRule concurrency,
      String eventId) {

    Objects.requireNonNull(clientKey, "Client key cannot be null");
    Objects.requireNonNull(policyName, "Policy name cannot be null");
    if (rules == null) {
      rules = List.of();
    }

    ReentrantLock lock = lockFor(clientKey, policyName);
    lock.lock();
    try {
      long nowMs = clock.nowMillis();

      // 1. Check Concurrency Rule if configured
      String concKey = clientKey + ":" + policyName + ":conc";
      if (concurrency != null && concurrency.max() > 0) {
        Set<String> activePermits = concurrencyPermits.get(concKey);
        int inFlight = activePermits != null ? activePermits.size() : 0;
        if (inFlight >= concurrency.max()) {
          return StoreResult.denyConcurrency(inFlight, concurrency.max(), 0L);
        }
      }

      // 2. Evaluate all rate rules tentatively
      List<EvaluationResult> results = new ArrayList<>(rules.size());
      List<String> stateKeys = new ArrayList<>(rules.size());

      boolean allAllowed = true;
      for (RateRule rule : rules) {
        String stateKey = clientKey + ":" + policyName + ":" + rule.id();
        stateKeys.add(stateKey);

        AlgorithmState currentState = stateCache.getIfPresent(stateKey);
        RateLimitAlgorithm algo = algorithmRegistry.get(rule.algorithm());
        EvaluationResult eval = algo.evaluate(nowMs, rule, cost, currentState);
        results.add(eval);

        if (!eval.allowed()) {
          allAllowed = false;
        }
      }

      // 3. Commit only if all rules passed
      if (allAllowed) {
        // Commit states to cache
        for (int i = 0; i < rules.size(); i++) {
          EvaluationResult eval = results.get(i);
          if (eval.newState() != null) {
            stateCache.put(stateKeys.get(i), eval.newState());
          }
        }

        // Acquire concurrency permit
        String permitId = null;
        if (concurrency != null && concurrency.max() > 0) {
          permitId = eventId != null ? eventId : java.util.UUID.randomUUID().toString();
          concurrencyPermits
              .computeIfAbsent(concKey, k -> ConcurrentHashMap.newKeySet())
              .add(permitId);
        }

        // Identify binding rule on allow: lowest remaining / limit ratio (earliest on tie)
        int bindingIndex = 1;
        long bindingRemaining = 0;
        long bindingLimit = 0;
        long bindingResetAt = nowMs;

        if (!rules.isEmpty()) {
          double minRatio = Double.MAX_VALUE;
          for (int i = 0; i < rules.size(); i++) {
            EvaluationResult eval = results.get(i);
            double ratio = eval.limit() > 0 ? (double) eval.remaining() / eval.limit() : 0.0;
            if (ratio < minRatio) {
              minRatio = ratio;
              bindingIndex = i + 1;
              bindingRemaining = eval.remaining();
              bindingLimit = eval.limit();
              bindingResetAt = eval.resetAtMs();
            }
          }
        }

        return StoreResult.allow(
            bindingIndex, bindingRemaining, bindingLimit, bindingResetAt, permitId);
      } else {
        // Denied: write NOTHING! Find binding rule: failing rule with largest retryAfterMs
        int bindingIndex = 1;
        long maxRetryAfter = -1;
        long bindingRemaining = 0;
        long bindingLimit = 0;
        long bindingResetAt = nowMs;

        for (int i = 0; i < rules.size(); i++) {
          EvaluationResult eval = results.get(i);
          if (!eval.allowed() && eval.retryAfterMs() > maxRetryAfter) {
            maxRetryAfter = eval.retryAfterMs();
            bindingIndex = i + 1;
            bindingRemaining = eval.remaining();
            bindingLimit = eval.limit();
            bindingResetAt = eval.resetAtMs();
          }
        }

        return StoreResult.denyRate(
            bindingIndex, bindingRemaining, bindingLimit, bindingResetAt, maxRetryAfter);
      }
    } finally {
      lock.unlock();
    }
  }

  @Override
  public void release(String clientKey, String policyName, String permitId) {
    if (clientKey == null || policyName == null || permitId == null) {
      return;
    }
    String concKey = clientKey + ":" + policyName + ":conc";
    Set<String> permits = concurrencyPermits.get(concKey);
    if (permits != null) {
      permits.remove(permitId);
      if (permits.isEmpty()) {
        concurrencyPermits.remove(concKey, Collections.emptySet());
      }
    }
  }

  public long keyCount() {
    return stateCache.estimatedSize();
  }

  public void clear() {
    stateCache.invalidateAll();
    concurrencyPermits.clear();
  }

  private ReentrantLock lockFor(String clientKey, String policyName) {
    int hash = (clientKey + ":" + policyName).hashCode();
    int stripe = (hash & 0x7FFFFFFF) % STRIPE_COUNT;
    return locks[stripe];
  }
}
