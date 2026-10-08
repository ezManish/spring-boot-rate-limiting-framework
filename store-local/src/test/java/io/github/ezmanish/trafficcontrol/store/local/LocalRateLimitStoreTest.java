package io.github.ezmanish.trafficcontrol.store.local;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.ezmanish.trafficcontrol.core.api.Algorithm;
import io.github.ezmanish.trafficcontrol.core.api.ConcurrencyRule;
import io.github.ezmanish.trafficcontrol.core.api.RateRule;
import io.github.ezmanish.trafficcontrol.core.spi.FakeClock;
import io.github.ezmanish.trafficcontrol.core.spi.StoreResult;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class LocalRateLimitStoreTest {

  private FakeClock clock;
  private LocalRateLimitStore store;

  @BeforeEach
  void setUp() {
    clock = new FakeClock(1_000_000L);
    store = new LocalRateLimitStore(clock);
  }

  @Test
  @DisplayName("TC-030 & TC-031: Multi-Rule Atomicity: All-or-nothing consumption")
  void testMultiRuleAtomicity() {
    RateRule ruleA = new RateRule("rA", Algorithm.TOKEN_BUCKET, 2, Duration.ofSeconds(1), 2);
    RateRule ruleB = new RateRule("rB", Algorithm.FIXED_WINDOW, 10, Duration.ofMinutes(1), 10);
    List<RateRule> rules = List.of(ruleA, ruleB);

    // 1st request: passes both rules
    StoreResult res1 = store.tryConsume("user1", "policy1", rules, 1, null, "evt1");
    assertThat(res1.allowed()).isTrue();

    // 2nd request: passes both rules
    StoreResult res2 = store.tryConsume("user1", "policy1", rules, 1, null, "evt2");
    assertThat(res2.allowed()).isTrue();

    // 3rd request: ruleA is exhausted (2 of 2 consumed), ruleB still has capacity
    StoreResult res3 = store.tryConsume("user1", "policy1", rules, 1, null, "evt3");
    assertThat(res3.allowed()).isFalse();
    assertThat(res3.bindingRuleIndex()).isEqualTo(1); // ruleA caused rejection

    // Now test atomicity: if ruleA refilled or we check ruleB alone, ruleB must NOT have consumed a
    // token from res3
    // Let's verify ruleB by testing ruleB alone: 10 capacity, 2 consumed from res1 & res2 ->
    // exactly 8 remaining
    List<RateRule> ruleBOnly = List.of(ruleB);
    StoreResult checkB = store.tryConsume("user1", "policy1", ruleBOnly, 1, null, "evt4");
    assertThat(checkB.allowed()).isTrue();
    assertThat(checkB.remaining())
        .isEqualTo(7L); // 8 - 1 = 7 remaining, proving res3 did NOT consume from B!
  }

  @Test
  @DisplayName("TC-050 & TC-051: Concurrency limiting and permit release")
  void testConcurrencyLimiting() {
    ConcurrencyRule conc = new ConcurrencyRule(2, Duration.ZERO, Duration.ofSeconds(30));

    // 1st permit
    StoreResult r1 = store.tryConsume("user1", "policy1", List.of(), 1, conc, "lease-1");
    assertThat(r1.allowed()).isTrue();
    assertThat(r1.permitId()).isEqualTo("lease-1");

    // 2nd permit
    StoreResult r2 = store.tryConsume("user1", "policy1", List.of(), 1, conc, "lease-2");
    assertThat(r2.allowed()).isTrue();
    assertThat(r2.permitId()).isEqualTo("lease-2");

    // 3rd request exceeds max concurrency (2)
    StoreResult r3 = store.tryConsume("user1", "policy1", List.of(), 1, conc, "lease-3");
    assertThat(r3.allowed()).isFalse();
    assertThat(r3.reason()).isEqualTo(2); // concurrency reason

    // Release lease-1
    store.release("user1", "policy1", "lease-1");

    // Now another request can be admitted
    StoreResult r4 = store.tryConsume("user1", "policy1", List.of(), 1, conc, "lease-4");
    assertThat(r4.allowed()).isTrue();
    assertThat(r4.permitId()).isEqualTo("lease-4");
  }

  @Test
  @DisplayName(
      "TC-113: Concurrency Stress: 1000 threads against one key with limit 100 has 0 overshoot")
  void testConcurrentThreadsNoOvershoot() throws InterruptedException {
    int threads = 100;
    int requestsPerThread = 10;
    int totalRequests = threads * requestsPerThread; // 1000 total requests
    long limit = 100;

    RateRule rule =
        new RateRule("r1", Algorithm.TOKEN_BUCKET, limit, Duration.ofSeconds(60), limit);
    List<RateRule> rules = List.of(rule);

    ExecutorService executor = Executors.newFixedThreadPool(threads);
    CountDownLatch startLatch = new CountDownLatch(1);
    CountDownLatch doneLatch = new CountDownLatch(threads);

    AtomicInteger allowedCount = new AtomicInteger(0);
    AtomicInteger rejectedCount = new AtomicInteger(0);

    for (int i = 0; i < threads; i++) {
      executor.submit(
          () -> {
            try {
              startLatch.await();
              for (int j = 0; j < requestsPerThread; j++) {
                StoreResult res = store.tryConsume("hot-client", "api", rules, 1, null, "evt");
                if (res.allowed()) {
                  allowedCount.incrementAndGet();
                } else {
                  rejectedCount.incrementAndGet();
                }
              }
            } catch (InterruptedException ignored) {
            } finally {
              doneLatch.countDown();
            }
          });
    }

    startLatch.countDown();
    doneLatch.await();
    executor.shutdown();

    assertThat(allowedCount.get()).isEqualTo((int) limit);
    assertThat(rejectedCount.get()).isEqualTo(totalRequests - (int) limit);
  }
}
