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

/**
 * JMH Microbenchmark comparing decision throughput across all 5 rate limiting algorithms (W7, S7).
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
public class AlgorithmSweepBenchmark {

  private RateLimitEngine engine;
  private Policy tokenBucketPolicy;
  private Policy gcraPolicy;
  private Policy slidingCounterPolicy;
  private Policy fixedWindowPolicy;
  private Policy slidingLogPolicy;

  private RequestContext ctxTokenBucket;
  private RequestContext ctxGcra;
  private RequestContext ctxSlidingCounter;
  private RequestContext ctxFixedWindow;
  private RequestContext ctxSlidingLog;

  @Setup
  public void setUp() {
    Clock clock = System::currentTimeMillis;
    RateLimitAlgorithmRegistry registry = new RateLimitAlgorithmRegistry();
    LocalRateLimitStore store =
        new LocalRateLimitStore(100_000, Duration.ofMinutes(10), registry, clock);
    this.engine = new DefaultRateLimitEngine(store);

    long limit = 10_000_000;
    Duration window = Duration.ofSeconds(60);

    this.tokenBucketPolicy =
        Policy.builder("tb-policy")
            .rules(List.of(new RateRule("r1", Algorithm.TOKEN_BUCKET, limit, window, limit)))
            .build();
    this.gcraPolicy =
        Policy.builder("gcra-policy")
            .rules(List.of(new RateRule("r1", Algorithm.GCRA, limit, window, limit)))
            .build();
    this.slidingCounterPolicy =
        Policy.builder("sc-policy")
            .rules(
                List.of(new RateRule("r1", Algorithm.SLIDING_WINDOW_COUNTER, limit, window, limit)))
            .build();
    this.fixedWindowPolicy =
        Policy.builder("fw-policy")
            .rules(List.of(new RateRule("r1", Algorithm.FIXED_WINDOW, limit, window, limit)))
            .build();
    this.slidingLogPolicy =
        Policy.builder("sl-policy")
            .rules(List.of(new RateRule("r1", Algorithm.SLIDING_WINDOW_LOG, limit, window, limit)))
            .build();

    this.ctxTokenBucket = new RequestContext("client-tb", "tb-policy", null, 1);
    this.ctxGcra = new RequestContext("client-gcra", "gcra-policy", null, 1);
    this.ctxSlidingCounter = new RequestContext("client-sc", "sc-policy", null, 1);
    this.ctxFixedWindow = new RequestContext("client-fw", "fw-policy", null, 1);
    this.ctxSlidingLog = new RequestContext("client-sl", "sl-policy", null, 1);
  }

  @Benchmark
  public void tokenBucket(Blackhole blackhole) {
    var d = engine.evaluate(ctxTokenBucket, tokenBucketPolicy);
    if (blackhole != null) {
      blackhole.consume(d);
    }
  }

  @Benchmark
  public void gcra(Blackhole blackhole) {
    var d = engine.evaluate(ctxGcra, gcraPolicy);
    if (blackhole != null) {
      blackhole.consume(d);
    }
  }

  @Benchmark
  public void slidingWindowCounter(Blackhole blackhole) {
    var d = engine.evaluate(ctxSlidingCounter, slidingCounterPolicy);
    if (blackhole != null) {
      blackhole.consume(d);
    }
  }

  @Benchmark
  public void fixedWindow(Blackhole blackhole) {
    var d = engine.evaluate(ctxFixedWindow, fixedWindowPolicy);
    if (blackhole != null) {
      blackhole.consume(d);
    }
  }

  @Benchmark
  public void slidingWindowLog(Blackhole blackhole) {
    var d = engine.evaluate(ctxSlidingLog, slidingLogPolicy);
    if (blackhole != null) {
      blackhole.consume(d);
    }
  }
}
