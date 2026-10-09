package io.github.ezmanish.trafficcontrol.store.redis;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/** Thread-safe Circuit Breaker protecting Redis access from cascade failures (TC-063..TC-065). */
public class CircuitBreaker {

  public enum State {
    CLOSED,
    OPEN,
    HALF_OPEN
  }

  private final int failureThreshold;
  private final Duration openDuration;
  private final AtomicReference<State> state = new AtomicReference<>(State.CLOSED);
  private final AtomicInteger consecutiveFailures = new AtomicInteger(0);
  private final AtomicLong openedAtMs = new AtomicLong(0);
  private final AtomicInteger halfOpenSuccesses = new AtomicInteger(0);

  public CircuitBreaker(int failureThreshold, Duration openDuration) {
    if (failureThreshold <= 0) {
      throw new IllegalArgumentException("failureThreshold must be > 0");
    }
    this.failureThreshold = failureThreshold;
    this.openDuration = openDuration != null ? openDuration : Duration.ofSeconds(5);
  }

  public CircuitBreaker() {
    this(5, Duration.ofSeconds(5));
  }

  public boolean allowRequest() {
    State current = state.get();
    if (current == State.CLOSED) {
      return true;
    }

    long now = System.currentTimeMillis();
    if (current == State.OPEN) {
      if (now - openedAtMs.get() >= openDuration.toMillis()) {
        if (state.compareAndSet(State.OPEN, State.HALF_OPEN)) {
          halfOpenSuccesses.set(0);
          return true;
        }
      }
      return false;
    }

    // HALF_OPEN: allow probe
    return true;
  }

  public void recordSuccess() {
    State current = state.get();
    if (current == State.HALF_OPEN) {
      if (halfOpenSuccesses.incrementAndGet() >= 1) {
        // Probe succeeded, close the circuit
        consecutiveFailures.set(0);
        state.set(State.CLOSED);
      }
    } else if (current == State.CLOSED) {
      consecutiveFailures.set(0);
    }
  }

  public void recordFailure() {
    int failures = consecutiveFailures.incrementAndGet();
    if (failures >= failureThreshold || state.get() == State.HALF_OPEN) {
      state.set(State.OPEN);
      openedAtMs.set(System.currentTimeMillis());
    }
  }

  public State getState() {
    State current = state.get();
    if (current == State.OPEN
        && (System.currentTimeMillis() - openedAtMs.get() >= openDuration.toMillis())) {
      return State.HALF_OPEN;
    }
    return current;
  }

  public long getRemainingWaitMs() {
    if (state.get() != State.OPEN) {
      return 0;
    }
    long elapsed = System.currentTimeMillis() - openedAtMs.get();
    return Math.max(0, openDuration.toMillis() - elapsed);
  }

  public void reset() {
    state.set(State.CLOSED);
    consecutiveFailures.set(0);
    openedAtMs.set(0);
    halfOpenSuccesses.set(0);
  }
}
