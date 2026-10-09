package io.github.ezmanish.trafficcontrol.spring.actuator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.ezmanish.trafficcontrol.core.spi.RateLimitStore;
import io.github.ezmanish.trafficcontrol.spring.autoconfigure.TrafficControlProperties;
import io.github.ezmanish.trafficcontrol.spring.web.policy.PolicyRegistry;
import io.github.ezmanish.trafficcontrol.store.redis.CircuitBreaker;
import io.github.ezmanish.trafficcontrol.store.redis.RedisRateLimitStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;

class RateLimitHealthIndicatorTest {

  private TrafficControlProperties properties;
  private PolicyRegistry policyRegistry;
  private RateLimitHealthIndicator healthIndicator;

  @BeforeEach
  void setUp() {
    properties = new TrafficControlProperties();
    properties.setStore("local");
    policyRegistry = new PolicyRegistry(properties);
  }

  @Test
  @DisplayName("Health is UP for local store with active policy version")
  void testLocalStoreHealthUp() {
    RateLimitStore localStore = mock(RateLimitStore.class);
    healthIndicator = new RateLimitHealthIndicator(properties, policyRegistry, localStore);

    Health health = healthIndicator.health();

    assertThat(health.getStatus()).isEqualTo(Status.UP);
    assertThat(health.getDetails()).containsEntry("store", "local");
    assertThat(health.getDetails()).containsEntry("policyVersion", 1L);
  }

  @Test
  @DisplayName("Health is DOWN when Redis store circuit breaker is OPEN")
  void testRedisStoreHealthDownWhenBreakerOpen() {
    properties.setStore("redis");
    RedisRateLimitStore redisStore = mock(RedisRateLimitStore.class);
    CircuitBreaker breaker = mock(CircuitBreaker.class);
    when(breaker.getState()).thenReturn(CircuitBreaker.State.OPEN);
    when(redisStore.getCircuitBreaker()).thenReturn(breaker);

    healthIndicator = new RateLimitHealthIndicator(properties, policyRegistry, redisStore);

    Health health = healthIndicator.health();

    assertThat(health.getStatus()).isEqualTo(Status.DOWN);
    assertThat(health.getDetails()).containsEntry("circuitBreaker", "OPEN");
    assertThat(health.getDetails().get("reason").toString()).contains("circuit breaker is OPEN");
  }
}
