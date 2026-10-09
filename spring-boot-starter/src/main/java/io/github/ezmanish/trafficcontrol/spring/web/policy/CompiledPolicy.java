package io.github.ezmanish.trafficcontrol.spring.web.policy;

import io.github.ezmanish.trafficcontrol.core.api.Policy;
import io.github.ezmanish.trafficcontrol.core.api.RateLimitKey;
import io.github.ezmanish.trafficcontrol.spring.web.resolver.OnMissingKeyStrategy;
import java.util.List;

/**
 * Compiled policy combining the core Policy domain model with Spring web key-resolution metadata.
 */
public record CompiledPolicy(
    Policy policy,
    RateLimitKey keyType,
    List<RateLimitKey> components,
    String apiKeyHeader,
    String claimName,
    String tenantSource,
    String tenantName,
    String keyResolverBean,
    String planResolverBean,
    OnMissingKeyStrategy onMissingKey) {}
