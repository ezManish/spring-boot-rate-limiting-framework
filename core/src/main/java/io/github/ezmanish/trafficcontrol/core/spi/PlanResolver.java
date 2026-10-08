package io.github.ezmanish.trafficcontrol.core.spi;

import java.util.Optional;

/** SPI for resolving a client's subscription plan name (e.g. FREE, PRO, ENTERPRISE). */
@FunctionalInterface
public interface PlanResolver {

  Optional<String> resolvePlan(RateLimitContext ctx);
}
