package io.github.ezmanish.trafficcontrol.core.spi;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

/** Deterministic test clock that can be advanced manually. */
public class FakeClock implements Clock {

  private final AtomicLong currentTimeMs;

  public FakeClock(long initialTimeMs) {
    this.currentTimeMs = new AtomicLong(initialTimeMs);
  }

  public FakeClock() {
    this(0L);
  }

  @Override
  public long nowMillis() {
    return currentTimeMs.get();
  }

  public void set(long epochMillis) {
    currentTimeMs.set(epochMillis);
  }

  public void advance(long millis) {
    currentTimeMs.addAndGet(millis);
  }

  public void advance(Duration duration) {
    advance(duration.toMillis());
  }
}
