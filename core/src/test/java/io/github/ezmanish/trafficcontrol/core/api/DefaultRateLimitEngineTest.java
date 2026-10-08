package io.github.ezmanish.trafficcontrol.core.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.ezmanish.trafficcontrol.core.spi.DecisionListener;
import io.github.ezmanish.trafficcontrol.core.spi.FakeClock;
import io.github.ezmanish.trafficcontrol.core.spi.RateLimitStore;
import io.github.ezmanish.trafficcontrol.core.spi.StoreResult;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class DefaultRateLimitEngineTest {

  @Test
  @DisplayName("Engine evaluates store and notifies decision listeners")
  void testEngineEvaluationAndListener() {
    RateLimitStore mockStore = mock(RateLimitStore.class);
    when(mockStore.tryConsume(anyString(), anyString(), any(), anyLong(), any(), anyString()))
        .thenReturn(StoreResult.allow(1, 4L, 5L, 2000L, null));

    AtomicBoolean listenerInvoked = new AtomicBoolean(false);
    DecisionListener listener =
        (decision, context) -> {
          listenerInvoked.set(true);
          assertThat(decision.allowed()).isTrue();
          assertThat(decision.remaining()).isEqualTo(4L);
        };

    RateRule rule = new RateRule("r1", Algorithm.TOKEN_BUCKET, 5, Duration.ofSeconds(1), 5);
    Policy policy = Policy.builder("test-policy").rules(List.of(rule)).build();

    DefaultRateLimitEngine engine =
        new DefaultRateLimitEngine(mockStore, new FakeClock(1000L), null, List.of(listener));

    RequestContext ctx = RequestContext.of("user-1", "test-policy");
    Decision decision = engine.evaluate(ctx, policy);

    assertThat(decision.allowed()).isTrue();
    assertThat(decision.remaining()).isEqualTo(4L);
    assertThat(decision.ruleId()).isEqualTo("r1");
    assertThat(listenerInvoked.get()).isTrue();
  }

  @Test
  @DisplayName("Engine respects plan overrides from RequestContext")
  void testPlanOverrideSelection() {
    RateLimitStore mockStore = mock(RateLimitStore.class);
    RateRule freeRule =
        new RateRule("free-rule", Algorithm.TOKEN_BUCKET, 10, Duration.ofMinutes(1), 10);
    RateRule proRule =
        new RateRule("pro-rule", Algorithm.TOKEN_BUCKET, 100, Duration.ofMinutes(1), 100);

    Policy policy =
        Policy.builder("tiered-policy")
            .rules(List.of(freeRule))
            .plans(Map.of("PRO", new PlanOverride(List.of(proRule))))
            .build();

    when(mockStore.tryConsume(
            eq("user-pro"), eq("tiered-policy"), eq(List.of(proRule)), eq(1L), any(), anyString()))
        .thenReturn(StoreResult.allow(1, 99L, 100L, 2000L, null));

    DefaultRateLimitEngine engine = new DefaultRateLimitEngine(mockStore);
    RequestContext proCtx = new RequestContext("user-pro", "tiered-policy", "PRO", 1L);

    Decision decision = engine.evaluate(proCtx, policy);
    assertThat(decision.allowed()).isTrue();
    assertThat(decision.ruleId()).isEqualTo("pro-rule");
  }

  @Test
  @DisplayName("Engine invokes FailureStrategy when store throws exception")
  void testFailureStrategyOnException() {
    RateLimitStore faultyStore = mock(RateLimitStore.class);
    when(faultyStore.tryConsume(any(), any(), any(), anyLong(), any(), any()))
        .thenThrow(new RuntimeException("Redis connection timed out"));

    Policy failOpenPolicy = Policy.builder("read-policy").failMode(FailMode.FAIL_OPEN).build();
    Policy failClosedPolicy = Policy.builder("login-policy").failMode(FailMode.FAIL_CLOSED).build();

    DefaultRateLimitEngine engine = new DefaultRateLimitEngine(faultyStore);

    Decision openDecision = engine.evaluate(RequestContext.of("u1", "read-policy"), failOpenPolicy);
    assertThat(openDecision.allowed()).isTrue();
    assertThat(openDecision.degraded()).isTrue();

    Decision closedDecision =
        engine.evaluate(RequestContext.of("u1", "login-policy"), failClosedPolicy);
    assertThat(closedDecision.allowed()).isFalse();
    assertThat(closedDecision.degraded()).isTrue();
    assertThat(closedDecision.retryAfter()).isEqualTo(Duration.ofSeconds(10));
  }
}
