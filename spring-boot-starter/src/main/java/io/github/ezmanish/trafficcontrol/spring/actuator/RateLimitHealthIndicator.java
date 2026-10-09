package io.github.ezmanish.trafficcontrol.spring.actuator;

import io.github.ezmanish.trafficcontrol.core.spi.RateLimitStore;
import io.github.ezmanish.trafficcontrol.spring.autoconfigure.TrafficControlProperties;
import io.github.ezmanish.trafficcontrol.spring.web.policy.PolicyRegistry;
import java.util.Objects;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;

/**
 * Spring Boot Actuator Health Indicator for TrafficControl (docs/11 §5). Reports store type,
 * circuit breaker state, policy version, and active policies count.
 */
public class RateLimitHealthIndicator implements HealthIndicator {

  private final TrafficControlProperties properties;
  private final PolicyRegistry policyRegistry;
  private final RateLimitStore rateLimitStore;

  public RateLimitHealthIndicator(
      TrafficControlProperties properties,
      PolicyRegistry policyRegistry,
      RateLimitStore rateLimitStore) {
    this.properties = Objects.requireNonNull(properties, "properties cannot be null");
    this.policyRegistry = Objects.requireNonNull(policyRegistry, "policyRegistry cannot be null");
    this.rateLimitStore = Objects.requireNonNull(rateLimitStore, "rateLimitStore cannot be null");
  }

  @Override
  public Health health() {
    Health.Builder builder = Health.up();

    builder.withDetail("store", properties.getStore());
    builder.withDetail("policyVersion", policyRegistry.getSnapshot().version());
    builder.withDetail("policiesCount", policyRegistry.getSnapshot().namedPolicies().size());

    // Check Redis Circuit Breaker if using RedisRateLimitStore
    try {
      if (rateLimitStore
          instanceof io.github.ezmanish.trafficcontrol.store.redis.RedisRateLimitStore redisStore) {
        var breaker = redisStore.getCircuitBreaker();
        builder.withDetail("circuitBreaker", breaker.getState().name());
        if (breaker.getState()
            == io.github.ezmanish.trafficcontrol.store.redis.CircuitBreaker.State.OPEN) {
          builder.down().withDetail("reason", "Redis circuit breaker is OPEN");
        }
      }
    } catch (Throwable ignored) {
      // Redis store classes might not be on classpath if running purely local
    }

    return builder.build();
  }
}
