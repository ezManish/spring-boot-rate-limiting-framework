package io.github.ezmanish.trafficcontrol.core.api;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/** An immutable rate limit policy snapshot. */
public record Policy(
    String name,
    RateLimitKey key,
    List<RateLimitKey> components,
    Algorithm algorithm,
    FailMode failMode,
    List<RateRule> rules,
    ConcurrencyRule concurrency,
    Map<String, PlanOverride> plans,
    AdaptiveConfig adaptive) {

  public Policy {
    Objects.requireNonNull(name, "Policy name cannot be null");
    Objects.requireNonNull(key, "RateLimitKey cannot be null");
    components = components != null ? List.copyOf(components) : List.of();
    if (algorithm == null) {
      algorithm = Algorithm.TOKEN_BUCKET;
    }
    if (failMode == null) {
      failMode = FailMode.FAIL_OPEN;
    }
    rules = rules != null ? List.copyOf(rules) : List.of();
    plans = plans != null ? Map.copyOf(plans) : Map.of();
    if (adaptive == null) {
      adaptive = AdaptiveConfig.disabled();
    }
  }

  public static Builder builder(String name) {
    return new Builder(name);
  }

  public static class Builder {
    private final String name;
    private RateLimitKey key = RateLimitKey.USER;
    private List<RateLimitKey> components = List.of();
    private Algorithm algorithm = Algorithm.TOKEN_BUCKET;
    private FailMode failMode = FailMode.FAIL_OPEN;
    private List<RateRule> rules = List.of();
    private ConcurrencyRule concurrency;
    private Map<String, PlanOverride> plans = Map.of();
    private AdaptiveConfig adaptive = AdaptiveConfig.disabled();

    public Builder(String name) {
      this.name = name;
    }

    public Builder key(RateLimitKey key) {
      this.key = key;
      return this;
    }

    public Builder components(List<RateLimitKey> components) {
      this.components = components;
      return this;
    }

    public Builder algorithm(Algorithm algorithm) {
      this.algorithm = algorithm;
      return this;
    }

    public Builder failMode(FailMode failMode) {
      this.failMode = failMode;
      return this;
    }

    public Builder rules(List<RateRule> rules) {
      this.rules = rules;
      return this;
    }

    public Builder concurrency(ConcurrencyRule concurrency) {
      this.concurrency = concurrency;
      return this;
    }

    public Builder plans(Map<String, PlanOverride> plans) {
      this.plans = plans;
      return this;
    }

    public Builder adaptive(AdaptiveConfig adaptive) {
      this.adaptive = adaptive;
      return this;
    }

    public Policy build() {
      return new Policy(
          name, key, components, algorithm, failMode, rules, concurrency, plans, adaptive);
    }
  }
}
