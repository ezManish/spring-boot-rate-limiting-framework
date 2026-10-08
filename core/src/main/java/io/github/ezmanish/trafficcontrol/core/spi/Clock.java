package io.github.ezmanish.trafficcontrol.core.spi;

import java.time.Instant;

/**
 * Monotonic or authoritative time source. In local mode, System.currentTimeMillis() or a test fake.
 * In Redis mode, Redis TIME.
 */
@FunctionalInterface
public interface Clock {

  long nowMillis();

  default Instant now() {
    return Instant.ofEpochMilli(nowMillis());
  }

  static Clock system() {
    return System::currentTimeMillis;
  }
}
