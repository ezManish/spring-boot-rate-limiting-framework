package io.github.ezmanish.trafficcontrol.spring.web.response;

import io.github.ezmanish.trafficcontrol.core.api.Decision;
import io.github.ezmanish.trafficcontrol.spring.autoconfigure.TrafficControlProperties.HeadersConfig;
import jakarta.servlet.http.HttpServletResponse;

/** Emits standard rate limit headers according to the configured style (LEGACY, IETF, BOTH). */
public class RateLimitHeaderWriter {

  public void writeHeaders(
      HttpServletResponse response, Decision decision, HeadersConfig headersConfig) {
    if (headersConfig == null || !headersConfig.isEnabled()) {
      return;
    }

    // RFC / Spec: Not sent when the decision is degraded (fail-open or store-unavailable):
    // there is no result to report.
    if (decision.degraded()) {
      return;
    }

    String style =
        headersConfig.getStyle() != null ? headersConfig.getStyle().toUpperCase() : "LEGACY";

    long limit = decision.limit();
    long remaining = Math.max(0, decision.remaining());
    long resetEpochSeconds = (decision.resetAt().toEpochMilli() + 999) / 1000;
    long resetDeltaSeconds =
        Math.max(0, (decision.resetAt().toEpochMilli() - System.currentTimeMillis() + 999) / 1000);

    if ("LEGACY".equals(style) || "BOTH".equals(style)) {
      response.setHeader("X-RateLimit-Limit", String.valueOf(limit));
      response.setHeader("X-RateLimit-Remaining", String.valueOf(remaining));
      response.setHeader("X-RateLimit-Reset", String.valueOf(resetEpochSeconds));
    }

    if ("IETF".equals(style) || "BOTH".equals(style)) {
      response.setHeader("RateLimit-Limit", String.valueOf(limit));
      response.setHeader("RateLimit-Remaining", String.valueOf(remaining));
      response.setHeader("RateLimit-Reset", String.valueOf(resetDeltaSeconds));
    }
  }
}
