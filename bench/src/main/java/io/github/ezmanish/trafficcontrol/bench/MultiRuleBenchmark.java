package io.github.ezmanish.trafficcontrol.bench;

import io.github.ezmanish.trafficcontrol.core.algorithm.RateLimitAlgorithmRegistry;
import io.github.ezmanish.trafficcontrol.core.api.Algorithm;
import io.github.ezmanish.trafficcontrol.core.api.DefaultRateLimitEngine;
import io.github.ezmanish.trafficcontrol.core.api.Policy;
import io.github.ezmanish.trafficcontrol.core.api.RateLimitEngine;
import io.github.ezmanish.trafficcontrol.core.api.RateRule;
import io.github.ezmanish.trafficcontrol.core.api.RequestContext;
import io.github.ezmanish.trafficcontrol.core.spi.Clock;
import io.github.ezmanish.trafficcontrol.store.local.LocalRateLimitStore;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.infra.Blackhole;

/** JMH Microbenchmark comparing atomic multi-rule evaluation overhead (1 vs 2 vs 4 rules, W4). */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
public class MultiRuleBenchmark {

  private RateLimitEngine engine;
  private Policy singleRulePolicy;
  private Policy dualRulePolicy;
  private Policy quadRulePolicy;

  private RequestContext ctxSingle;
  private RequestContext ctxDual;
  private RequestContext ctxQuad;

  @Setup
  public void setUp() {
    Clock clock = System::currentTimeMillis;
    RateLimitAlgorithmRegistry registry = new RateLimitAlgorithmRegistry();
    LocalRateLimitStore store =
        new LocalRateLimitStore(100_000, Duration.ofMinutes(10), registry, clock);
    this.engine = new DefaultRateLimitEngine(store);

    // 1 Rule: 10,000 req / sec
    this.singleRulePolicy =
        Policy.builder("single-rule")
            .rules(
                List.of(
                    new RateRule(
                        "r1", Algorithm.TOKEN_BUCKET, 10_000, Duration.ofSeconds(1), 10_000)))
            .build();

    // 2 Rules: 10,000 req / sec + 100,000 req / min
    this.dualRulePolicy =
        Policy.builder("dual-rule")
            .rules(
                List.of(
                    new RateRule(
                        "r1", Algorithm.TOKEN_BUCKET, 10_000, Duration.ofSeconds(1), 10_000),
                    new RateRule(
                        "r2", Algorithm.TOKEN_BUCKET, 100_000, Duration.ofMinutes(1), 100_000)))
            .build();

    // 4 Rules: per second + per minute + per hour + per day
    this.quadRulePolicy =
        Policy.builder("quad-rule")
            .rules(
                List.of(
                    new RateRule(
                        "r1", Algorithm.TOKEN_BUCKET, 10_000, Duration.ofSeconds(1), 10_000),
                    new RateRule(
                        "r2", Algorithm.TOKEN_BUCKET, 100_000, Duration.ofMinutes(1), 100_000),
                    new RateRule(
                        "r3", Algorithm.TOKEN_BUCKET, 500_000, Duration.ofHours(1), 500_000),
                    new RateRule(
                        "r4", Algorithm.TOKEN_BUCKET, 2_000_000, Duration.ofDays(1), 2_000_000)))
            .build();

    this.ctxSingle = new RequestContext("client-1", "single-rule", null, 1);
    this.ctxDual = new RequestContext("client-2", "dual-rule", null, 1);
    this.ctxQuad = new RequestContext("client-4", "quad-rule", null, 1);
  }

  @Benchmark
  public void singleRule(Blackhole blackhole) {
    var d = engine.evaluate(ctxSingle, singleRulePolicy);
    if (blackhole != null) {
      blackhole.consume(d);
    }
  }

  @Benchmark
  public void dualRules(Blackhole blackhole) {
    var d = engine.evaluate(ctxDual, dualRulePolicy);
    if (blackhole != null) {
      blackhole.consume(d);
    }
  }

  @Benchmark
  public void quadRules(Blackhole blackhole) {
    var d = engine.evaluate(ctxQuad, quadRulePolicy);
    if (blackhole != null) {
      blackhole.consume(d);
    }
  }
}
