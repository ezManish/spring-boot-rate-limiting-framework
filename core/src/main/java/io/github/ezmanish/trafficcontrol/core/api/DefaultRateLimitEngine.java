package io.github.ezmanish.trafficcontrol.core.api;

import io.github.ezmanish.trafficcontrol.core.spi.Clock;
import io.github.ezmanish.trafficcontrol.core.spi.DecisionListener;
import io.github.ezmanish.trafficcontrol.core.spi.FailureStrategy;
import io.github.ezmanish.trafficcontrol.core.spi.RateLimitStore;
import io.github.ezmanish.trafficcontrol.core.spi.StoreResult;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Default implementation of RateLimitEngine. */
public class DefaultRateLimitEngine implements RateLimitEngine {

  private final RateLimitStore store;
  private final Clock clock;
  private final FailureStrategy failureStrategy;
  private final List<DecisionListener> decisionListeners;

  public DefaultRateLimitEngine(
      RateLimitStore store,
      Clock clock,
      FailureStrategy failureStrategy,
      List<DecisionListener> decisionListeners) {
    this.store = Objects.requireNonNull(store, "RateLimitStore cannot be null");
    this.clock = clock != null ? clock : Clock.system();
    this.failureStrategy =
        failureStrategy != null ? failureStrategy : FailureStrategy.defaultStrategy();
    this.decisionListeners = decisionListeners != null ? List.copyOf(decisionListeners) : List.of();
  }

  public DefaultRateLimitEngine(RateLimitStore store) {
    this(store, Clock.system(), FailureStrategy.defaultStrategy(), List.of());
  }

  @Override
  public Decision evaluate(RequestContext ctx, Policy policy) {
    Objects.requireNonNull(ctx, "RequestContext cannot be null");
    Objects.requireNonNull(policy, "Policy cannot be null");

    List<RateRule> effectiveRules = resolveRules(ctx, policy);
    String eventId = UUID.randomUUID().toString();

    Decision decision;
    try {
      StoreResult result =
          store.tryConsume(
              ctx.clientKey(),
              policy.name(),
              effectiveRules,
              ctx.cost(),
              policy.concurrency(),
              eventId);

      decision = mapStoreResult(result, policy, effectiveRules);
    } catch (Throwable t) {
      decision = failureStrategy.onStoreFailure(policy, ctx, t);
    }

    for (DecisionListener listener : decisionListeners) {
      try {
        listener.onDecision(decision, ctx);
      } catch (Throwable ignored) {
        // Decision listeners must never fail request processing
      }
    }

    return decision;
  }

  private List<RateRule> resolveRules(RequestContext ctx, Policy policy) {
    if (ctx.plan() != null && policy.plans().containsKey(ctx.plan())) {
      PlanOverride override = policy.plans().get(ctx.plan());
      if (override != null && !override.rules().isEmpty()) {
        return override.rules();
      }
    }
    return policy.rules();
  }

  private Decision mapStoreResult(StoreResult result, Policy policy, List<RateRule> rules) {
    String ruleId = null;
    if (result.bindingRuleIndex() > 0 && result.bindingRuleIndex() <= rules.size()) {
      ruleId = rules.get(result.bindingRuleIndex() - 1).id();
    } else if (result.reason() == 2) {
      ruleId = "concurrency";
    }

    Instant resetAt =
        result.resetAtMs() > 0 ? Instant.ofEpochMilli(result.resetAtMs()) : Instant.EPOCH;

    Duration retryAfter =
        result.retryAfterMs() > 0 ? Duration.ofMillis(result.retryAfterMs()) : Duration.ZERO;

    if (result.allowed()) {
      return Decision.allow(
          result.limit(), result.remaining(), resetAt, policy.name(), ruleId, result.permitId());
    } else {
      return Decision.reject(
          result.limit(), result.remaining(), resetAt, retryAfter, policy.name(), ruleId);
    }
  }
}
