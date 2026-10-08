package io.github.ezmanish.trafficcontrol.core.api;

import java.util.List;
import java.util.Objects;

/** Limit overrides for a specific subscription plan tier. */
public record PlanOverride(List<RateRule> rules) {
  public PlanOverride {
    Objects.requireNonNull(rules, "PlanOverride rules cannot be null");
    if (rules.isEmpty()) {
      throw new IllegalArgumentException("PlanOverride must define at least one rule");
    }
    rules = List.copyOf(rules);
  }
}
