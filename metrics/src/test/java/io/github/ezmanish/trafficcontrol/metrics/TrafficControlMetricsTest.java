package io.github.ezmanish.trafficcontrol.metrics;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.ezmanish.trafficcontrol.core.api.Decision;
import io.github.ezmanish.trafficcontrol.core.api.RequestContext;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class TrafficControlMetricsTest {

  private SimpleMeterRegistry meterRegistry;
  private TrafficControlMetrics metrics;
  private MetricsDecisionListener listener;

  @BeforeEach
  void setUp() {
    meterRegistry = new SimpleMeterRegistry();
    metrics = new TrafficControlMetrics(meterRegistry);
    listener = new MetricsDecisionListener(metrics);
  }

  @Test
  @DisplayName(
      "TC-080: Allow and reject decisions increment trafficcontrol.requests counter with outcome tag")
  void testRequestsCounterIncrements() {
    RequestContext ctx = new RequestContext("clientHash123", "login", null, 1);
    Decision allow = Decision.allow(10, 9, Instant.now().plusSeconds(60), "login", "r1", null);
    Decision reject =
        Decision.reject(
            10, 0, Instant.now().plusSeconds(60), Duration.ofSeconds(10), "login", "r1");

    listener.onDecision(allow, ctx);
    listener.onDecision(reject, ctx);

    Counter allowCounter =
        meterRegistry
            .find("trafficcontrol.requests")
            .tag("policy", "login")
            .tag("outcome", "allow")
            .counter();
    assertThat(allowCounter).isNotNull();
    assertThat(allowCounter.count()).isEqualTo(1.0);

    Counter rejectCounter =
        meterRegistry
            .find("trafficcontrol.requests")
            .tag("policy", "login")
            .tag("outcome", "reject")
            .counter();
    assertThat(rejectCounter).isNotNull();
    assertThat(rejectCounter.count()).isEqualTo(1.0);
  }

  @Test
  @DisplayName("TC-081: Latency timer records duration")
  void testLatencyTimer() {
    RequestContext ctx = new RequestContext("clientHash123", "search", null, 1);
    Decision allow = Decision.allow(100, 99, Instant.now().plusSeconds(60), "search", "r1", null);

    metrics.recordDecision(allow, ctx, 2_500_000L); // 2.5 ms

    Timer timer =
        meterRegistry.find("trafficcontrol.decision.latency").tag("policy", "search").timer();
    assertThat(timer).isNotNull();
    assertThat(timer.count()).isEqualTo(1);
    assertThat(timer.totalTime(java.util.concurrent.TimeUnit.MILLISECONDS))
        .isGreaterThanOrEqualTo(2.0);
  }

  @Test
  @DisplayName("TC-082: Degraded decisions increment trafficcontrol.degraded counter with mode tag")
  void testDegradedCounterIncrements() {
    RequestContext ctx = new RequestContext("clientHash123", "checkout", null, 1);
    Decision degradedAllow = Decision.degradedAllow("checkout");
    Decision degradedReject = Decision.degradedReject("checkout", Duration.ofSeconds(5));

    metrics.recordDecision(degradedAllow, ctx, 0L);
    metrics.recordDecision(degradedReject, ctx, 0L);

    Counter failOpen =
        meterRegistry
            .find("trafficcontrol.degraded")
            .tag("policy", "checkout")
            .tag("mode", "fail_open")
            .counter();
    assertThat(failOpen).isNotNull();
    assertThat(failOpen.count()).isEqualTo(1.0);

    Counter failClosed =
        meterRegistry
            .find("trafficcontrol.degraded")
            .tag("policy", "checkout")
            .tag("mode", "fail_closed")
            .counter();
    assertThat(failClosed).isNotNull();
    assertThat(failClosed.count()).isEqualTo(1.0);
  }

  @Test
  @DisplayName("TC-084: Metrics tags never contain user identity or IP address (PII protection)")
  void testNoPiiInTags() {
    RequestContext ctx = new RequestContext("192.168.1.100", "users", null, 1);
    Decision decision = Decision.allow(10, 9, Instant.now().plusSeconds(60), "users", "r1", null);
    listener.onDecision(decision, ctx);

    for (Meter meter : meterRegistry.getMeters()) {
      for (Tag tag : meter.getId().getTags()) {
        assertThat(tag.getKey()).isNotEqualTo("user");
        assertThat(tag.getKey()).isNotEqualTo("ip");
        assertThat(tag.getKey()).isNotEqualTo("clientKey");
        assertThat(tag.getValue()).isNotEqualTo("192.168.1.100");
      }
    }
  }
}
