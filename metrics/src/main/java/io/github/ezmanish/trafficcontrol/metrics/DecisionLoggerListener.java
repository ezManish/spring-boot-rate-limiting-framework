package io.github.ezmanish.trafficcontrol.metrics;

import io.github.ezmanish.trafficcontrol.core.api.Decision;
import io.github.ezmanish.trafficcontrol.core.api.RequestContext;
import io.github.ezmanish.trafficcontrol.core.spi.DecisionListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Structured JSON logging for rate limit decisions (docs/11 §2, TC-083). Always uses client key
 * hashes and never outputs raw PII or user identity (TC-084).
 */
public class DecisionLoggerListener implements DecisionListener {

  private static final Logger log = LoggerFactory.getLogger(DecisionLoggerListener.class);

  @Override
  public void onDecision(Decision decision, RequestContext context) {
    if (decision == null) {
      return;
    }

    String policy = decision.policyName() != null ? decision.policyName() : "unknown";
    String outcome = decision.allowed() ? "allow" : "reject";
    String rule = decision.ruleId() != null ? decision.ruleId() : "none";
    String keyHash =
        context != null && context.clientKey() != null ? context.clientKey() : "unknown";
    long remaining = decision.remaining();
    long retryAfterMs = decision.retryAfter() != null ? decision.retryAfter().toMillis() : 0L;
    boolean degraded = decision.degraded();

    String json =
        String.format(
            "{\"event\":\"rate_limit\",\"policy\":\"%s\",\"outcome\":\"%s\",\"rule\":\"%s\","
                + "\"key_hash\":\"%s\",\"remaining\":%d,\"retry_after_ms\":%d,\"degraded\":%s}",
            policy, outcome, rule, keyHash, remaining, retryAfterMs, degraded);

    if (!decision.allowed() || degraded) {
      log.info(json);
    } else if (log.isDebugEnabled()) {
      log.debug(json);
    }
  }
}
