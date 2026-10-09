package io.github.ezmanish.trafficcontrol.spring.web.response;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.ezmanish.trafficcontrol.spring.autoconfigure.TrafficControlProperties.ProblemConfig;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Writes RFC 9457 Problem Details JSON bodies (Content-Type: application/problem+json) and sets
 * Retry-After and Cache-Control headers on rejections.
 */
public class ProblemDetailsResponseWriter {

  private final ObjectMapper objectMapper;
  private final ProblemConfig problemConfig;

  public ProblemDetailsResponseWriter(ObjectMapper objectMapper, ProblemConfig problemConfig) {
    this.objectMapper = objectMapper != null ? objectMapper : new ObjectMapper();
    this.problemConfig = problemConfig != null ? problemConfig : new ProblemConfig();
  }

  public ProblemDetailsResponseWriter() {
    this(new ObjectMapper(), new ProblemConfig());
  }

  public void writeProblem(
      HttpServletRequest request,
      HttpServletResponse response,
      String kind,
      int status,
      Long retryAfterSeconds,
      String policyName,
      String ruleId)
      throws IOException {

    response.setStatus(status);
    response.setContentType("application/problem+json;charset=UTF-8");
    response.setHeader("Cache-Control", "no-store");

    if (retryAfterSeconds != null && retryAfterSeconds > 0) {
      response.setHeader("Retry-After", String.valueOf(retryAfterSeconds));
    }

    String baseUri =
        problemConfig.getBaseUri() != null
            ? problemConfig.getBaseUri()
            : "urn:trafficcontrol:problem:";
    String typeUrn = baseUri + kind;

    String detail =
        switch (kind) {
          case "rate-limit-exceeded" -> "Request rate limit exceeded.";
          case "concurrency-limit-exceeded" -> "Too many concurrent requests.";
          case "store-unavailable" -> "Rate limiting is temporarily unavailable.";
          case "identity-required" -> "A client identity is required for this endpoint.";
          default -> "Rate limit violation.";
        };

    Map<String, Object> body = new LinkedHashMap<>();
    body.put("type", typeUrn);
    body.put("title", "Too Many Requests");
    body.put("status", status);
    body.put("detail", detail);

    if (retryAfterSeconds != null && !"identity-required".equals(kind)) {
      body.put("retryAfterSeconds", retryAfterSeconds);
    }

    if (problemConfig.isExposeDetails()) {
      if (policyName != null) {
        body.put("policy", policyName);
      }
      if (ruleId != null) {
        body.put("rule", ruleId);
      }
    }

    if (problemConfig.isIncludeInstance()) {
      String requestId = request.getHeader("X-Request-Id");
      if (requestId == null || requestId.isBlank()) {
        requestId = UUID.randomUUID().toString();
      }
      body.put("instance", "urn:uuid:" + requestId);
    }

    response.getWriter().write(objectMapper.writeValueAsString(body));
    response.getWriter().flush();
  }
}
