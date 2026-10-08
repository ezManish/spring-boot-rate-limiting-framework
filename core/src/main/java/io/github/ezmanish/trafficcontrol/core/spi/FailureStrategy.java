package io.github.ezmanish.trafficcontrol.core.spi;

import io.github.ezmanish.trafficcontrol.core.api.Decision;
import io.github.ezmanish.trafficcontrol.core.api.FailMode;
import io.github.ezmanish.trafficcontrol.core.api.Policy;
import io.github.ezmanish.trafficcontrol.core.api.RequestContext;
import java.time.Duration;

/** Strategy applied when the underlying store fails or throws an exception. */
@FunctionalInterface
public interface FailureStrategy {

  Decision onStoreFailure(Policy policy, RequestContext context, Throwable cause);

  static FailureStrategy defaultStrategy() {
    return (policy, context, cause) -> {
      if (policy.failMode() == FailMode.FAIL_CLOSED) {
        // Returns 429 with breaker wait / default retry-after
        return Decision.degradedReject(policy.name(), Duration.ofSeconds(10));
      } else {
        return Decision.degradedAllow(policy.name());
      }
    };
  }
}
