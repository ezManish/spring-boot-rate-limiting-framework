package io.github.ezmanish.trafficcontrol.metrics;

import io.github.ezmanish.trafficcontrol.core.api.Decision;
import io.github.ezmanish.trafficcontrol.core.api.RequestContext;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Micrometer metrics collector for the TrafficControl rate limiting framework (docs/11). Strictly
 * forbids PII (user, IP, identity) in metric tags to avoid cardinality explosion (TC-084).
 */
public class TrafficControlMetrics {

  private final MeterRegistry registry;
  private final ConcurrentMap<String, Counter> requestCounters = new ConcurrentHashMap<>();
  private final ConcurrentMap<String, Counter> degradedCounters = new ConcurrentHashMap<>();
  private final ConcurrentMap<String, Timer> latencyTimers = new ConcurrentHashMap<>();
  private final AtomicLong activePolicyVersion = new AtomicLong(1L);

  public TrafficControlMetrics(MeterRegistry registry) {
    this.registry = Objects.requireNonNull(registry, "MeterRegistry cannot be null");
    registry.gauge("trafficcontrol.policy.version", activePolicyVersion, AtomicLong::get);
  }

  public void recordDecision(Decision decision, RequestContext context, long latencyNanos) {
    if (decision == null) {
      return;
    }

    String policy = decision.policyName() != null ? decision.policyName() : "unknown";
    String outcome = decision.allowed() ? "allow" : "reject";
    String rule = decision.ruleId() != null ? decision.ruleId() : "none";
    String degraded = String.valueOf(decision.degraded());

    // 1. trafficcontrol.requests counter (TC-080)
    String counterKey = policy + ":" + outcome + ":" + rule + ":" + degraded;
    requestCounters
        .computeIfAbsent(
            counterKey,
            k ->
                Counter.builder("trafficcontrol.requests")
                    .description("Rate limit decisions")
                    .tags(
                        Tags.of(
                            Tag.of("policy", policy),
                            Tag.of("outcome", outcome),
                            Tag.of("rule", rule),
                            Tag.of("degraded", degraded)))
                    .register(registry))
        .increment();

    // 2. trafficcontrol.degraded counter (TC-082)
    if (decision.degraded()) {
      String mode = decision.allowed() ? "fail_open" : "fail_closed";
      String degradedKey = policy + ":" + mode;
      degradedCounters
          .computeIfAbsent(
              degradedKey,
              k ->
                  Counter.builder("trafficcontrol.degraded")
                      .description("Degraded decisions when store is unavailable")
                      .tags(Tags.of(Tag.of("policy", policy), Tag.of("mode", mode)))
                      .register(registry))
          .increment();
    }

    // 3. trafficcontrol.decision.latency timer (TC-081)
    if (latencyNanos > 0) {
      latencyTimers
          .computeIfAbsent(
              policy,
              k ->
                  Timer.builder("trafficcontrol.decision.latency")
                      .description("Rate limit decision evaluation latency")
                      .tag("policy", policy)
                      .publishPercentileHistogram()
                      .register(registry))
          .record(latencyNanos, TimeUnit.NANOSECONDS);
    }
  }

  public void registerCircuitBreakerGauge(
      String store, java.util.function.Supplier<Number> stateSupplier) {
    registry.gauge(
        "trafficcontrol.breaker.state",
        Tags.of("store", store),
        stateSupplier,
        s -> s.get().doubleValue());
  }

  public void updatePolicyVersion(long version) {
    activePolicyVersion.set(version);
  }

  public MeterRegistry getRegistry() {
    return registry;
  }
}
