package io.github.ezmanish.trafficcontrol.bench;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.ezmanish.trafficcontrol.core.algorithm.RateLimitAlgorithmRegistry;
import io.github.ezmanish.trafficcontrol.core.api.Algorithm;
import io.github.ezmanish.trafficcontrol.core.api.Decision;
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

/** JMH Microbenchmark comparing TrafficControl engine against Bucket4j baseline (S1 vs S2, W1). */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
public class TokenBucketBenchmark {

  private RateLimitEngine trafficControlEngine;
  private Policy trafficControlPolicy;
  private RequestContext requestContext;

  private Bucket bucket4jBucket;

  @Setup
  public void setUp() {
    // 1. Setup TrafficControl Engine (S1)
    Clock clock = System::currentTimeMillis;
    RateLimitAlgorithmRegistry registry = new RateLimitAlgorithmRegistry();
    LocalRateLimitStore store =
        new LocalRateLimitStore(100_000, Duration.ofMinutes(10), registry, clock);
    this.trafficControlEngine = new DefaultRateLimitEngine(store);

    this.trafficControlPolicy =
        Policy.builder("bench-policy")
            .rules(
                List.of(
                    new RateRule(
                        "r1",
                        Algorithm.TOKEN_BUCKET,
                        10_000_000,
                        Duration.ofSeconds(60),
                        10_000_000)))
            .build();
    this.requestContext = new RequestContext("bench-client-key", "bench-policy", null, 1);

    // 2. Setup Bucket4j baseline (S2)
    Bandwidth limit =
        Bandwidth.builder()
            .capacity(10_000_000)
            .refillGreedy(10_000_000, Duration.ofSeconds(60))
            .build();
    this.bucket4jBucket = Bucket.builder().addLimit(limit).build();
  }

  @Benchmark
  public void trafficControlLocal(Blackhole blackhole) {
    Decision decision = trafficControlEngine.evaluate(requestContext, trafficControlPolicy);
    if (blackhole != null) {
      blackhole.consume(decision);
    }
  }

  @Benchmark
  public void bucket4jLocal(Blackhole blackhole) {
    boolean consumed = bucket4jBucket.tryConsume(1);
    if (blackhole != null) {
      blackhole.consume(consumed);
    }
  }
}
