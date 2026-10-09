package io.github.ezmanish.trafficcontrol.spring.web.policy.update;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Background scheduler periodically polling Redis for policy version updates (TC-073). Ensures
 * eventual consistency even if a pub/sub network packet was dropped.
 */
public class PolicyPollScheduler {

  private static final Logger log = LoggerFactory.getLogger(PolicyPollScheduler.class);

  private final RedisPolicyUpdateSubscriber subscriber;
  private final Duration pollInterval;
  private ScheduledExecutorService executor;

  public PolicyPollScheduler(RedisPolicyUpdateSubscriber subscriber, Duration pollInterval) {
    this.subscriber = Objects.requireNonNull(subscriber, "subscriber cannot be null");
    this.pollInterval = pollInterval != null ? pollInterval : Duration.ofSeconds(30);
  }

  public synchronized void start() {
    if (executor != null && !executor.isShutdown()) {
      return;
    }
    this.executor =
        Executors.newSingleThreadScheduledExecutor(
            r -> {
              Thread t = new Thread(r, "trafficcontrol-policy-poller");
              t.setDaemon(true);
              return t;
            });

    long intervalSec = Math.max(1, pollInterval.toSeconds());
    executor.scheduleWithFixedDelay(
        () -> {
          try {
            subscriber.pollAndSync();
          } catch (Throwable t) {
            log.warn("Error during policy poll: {}", t.getMessage());
          }
        },
        intervalSec,
        intervalSec,
        TimeUnit.SECONDS);

    log.info("Started background policy poll fallback with interval of {}s (TC-073)", intervalSec);
  }

  public synchronized void stop() {
    if (executor != null) {
      executor.shutdownNow();
      executor = null;
    }
  }
}
