package io.github.ezmanish.trafficcontrol.core.spi;

import java.util.Optional;

/** SPI for resolving client identity keys from the framework-neutral RateLimitContext. */
@FunctionalInterface
public interface RateLimitKeyResolver {

  Optional<String> resolve(RateLimitContext ctx);
}
