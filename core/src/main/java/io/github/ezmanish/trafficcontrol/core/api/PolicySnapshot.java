package io.github.ezmanish.trafficcontrol.core.api;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/** Immutable snapshot of all active policies. */
public record PolicySnapshot(
    long version, String checksum, Map<String, Policy> policies, Instant loadedAt) {

  public PolicySnapshot {
    Objects.requireNonNull(policies, "Policies map cannot be null");
    policies = Map.copyOf(policies);
    if (loadedAt == null) {
      loadedAt = Instant.now();
    }
  }

  public static PolicySnapshot of(long version, Map<String, Policy> policies) {
    return new PolicySnapshot(version, "", policies, Instant.now());
  }

  public Policy getPolicy(String name) {
    return policies.get(name);
  }
}
