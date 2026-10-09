package io.github.ezmanish.trafficcontrol.spring.web.policy;

import io.github.ezmanish.trafficcontrol.spring.autoconfigure.TrafficControlProperties.PathRuleConfig;
import io.github.ezmanish.trafficcontrol.spring.autoconfigure.TrafficControlProperties.PolicyConfig;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable snapshot of all active policies and path rules (TR-07, ADR-008). Enables lock-free,
 * zero-downtime atomic swaps on live policy updates.
 */
public record PolicySnapshot(
    long version,
    String checksum,
    Instant updatedAt,
    String updatedBy,
    Map<String, CompiledPolicy> namedPolicies,
    List<PathRuleConfig> pathRules,
    Map<String, PolicyConfig> rawConfigs) {

  public PolicySnapshot {
    Objects.requireNonNull(updatedAt, "updatedAt cannot be null");
    Objects.requireNonNull(updatedBy, "updatedBy cannot be null");
    namedPolicies = namedPolicies != null ? Map.copyOf(namedPolicies) : Map.of();
    pathRules = pathRules != null ? List.copyOf(pathRules) : List.of();
    rawConfigs = rawConfigs != null ? Map.copyOf(rawConfigs) : Map.of();
    if (checksum == null || checksum.isBlank()) {
      checksum = computeChecksum(namedPolicies.keySet().toString() + ":" + version);
    }
  }

  public static String computeChecksum(String content) {
    try {
      MessageDigest md = MessageDigest.getInstance("SHA-256");
      byte[] digest = md.digest(content.getBytes(StandardCharsets.UTF_8));
      return "sha256:" + HexFormat.of().formatHex(digest);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 algorithm unavailable", e);
    }
  }
}
