package io.github.ezmanish.trafficcontrol.spring.web.response;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.ezmanish.trafficcontrol.spring.autoconfigure.TrafficControlProperties.ProblemConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class ProblemDetailsResponseWriterTest {

  private final ObjectMapper mapper = new ObjectMapper();

  @Test
  @DisplayName("TC-120: Default 429 RFC 9457 Problem Details body & headers")
  void testDefaultProblemDetailsResponse() throws Exception {
    ProblemConfig config = new ProblemConfig();
    ProblemDetailsResponseWriter writer = new ProblemDetailsResponseWriter(mapper, config);

    MockHttpServletRequest request = new MockHttpServletRequest();
    MockHttpServletResponse response = new MockHttpServletResponse();

    writer.writeProblem(request, response, "rate-limit-exceeded", 429, 23L, "login", "r1");

    assertThat(response.getStatus()).isEqualTo(429);
    assertThat(response.getContentType()).isEqualTo("application/problem+json;charset=UTF-8");
    assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
    assertThat(response.getHeader("Retry-After")).isEqualTo("23");

    JsonNode json = mapper.readTree(response.getContentAsString());
    assertThat(json.get("type").asText())
        .isEqualTo("urn:trafficcontrol:problem:rate-limit-exceeded");
    assertThat(json.get("title").asText()).isEqualTo("Too Many Requests");
    assertThat(json.get("status").asInt()).isEqualTo(429);
    assertThat(json.get("detail").asText()).isEqualTo("Request rate limit exceeded.");
    assertThat(json.get("retryAfterSeconds").asLong()).isEqualTo(23L);

    // Omitted by default for security
    assertThat(json.has("policy")).isFalse();
    assertThat(json.has("rule")).isFalse();
    assertThat(json.has("instance")).isFalse();
  }

  @Test
  @DisplayName(
      "TC-123: Distinct problem type values for quota vs concurrency vs outage vs identity")
  void testDistinctProblemTypes() throws Exception {
    ProblemConfig config = new ProblemConfig();
    ProblemDetailsResponseWriter writer = new ProblemDetailsResponseWriter(mapper, config);

    MockHttpServletRequest req = new MockHttpServletRequest();

    // Concurrency
    MockHttpServletResponse res1 = new MockHttpServletResponse();
    writer.writeProblem(req, res1, "concurrency-limit-exceeded", 429, 5L, null, null);
    JsonNode json1 = mapper.readTree(res1.getContentAsString());
    assertThat(json1.get("type").asText())
        .isEqualTo("urn:trafficcontrol:problem:concurrency-limit-exceeded");
    assertThat(json1.get("detail").asText()).isEqualTo("Too many concurrent requests.");

    // Store unavailable
    MockHttpServletResponse res2 = new MockHttpServletResponse();
    writer.writeProblem(req, res2, "store-unavailable", 429, 10L, null, null);
    JsonNode json2 = mapper.readTree(res2.getContentAsString());
    assertThat(json2.get("type").asText())
        .isEqualTo("urn:trafficcontrol:problem:store-unavailable");
    assertThat(json2.get("detail").asText()).isEqualTo("Rate limiting is temporarily unavailable.");

    // Identity required (no retryAfter)
    MockHttpServletResponse res3 = new MockHttpServletResponse();
    writer.writeProblem(req, res3, "identity-required", 429, null, null, null);
    JsonNode json3 = mapper.readTree(res3.getContentAsString());
    assertThat(json3.get("type").asText())
        .isEqualTo("urn:trafficcontrol:problem:identity-required");
    assertThat(json3.get("detail").asText())
        .isEqualTo("A client identity is required for this endpoint.");
    assertThat(json3.has("retryAfterSeconds")).isFalse();
    assertThat(res3.getHeader("Retry-After")).isNull();
  }

  @Test
  @DisplayName("TC-124: Expose details enabled includes policy and rule")
  void testExposeDetailsEnabled() throws Exception {
    ProblemConfig config = new ProblemConfig();
    config.setExposeDetails(true);
    config.setIncludeInstance(true);

    ProblemDetailsResponseWriter writer = new ProblemDetailsResponseWriter(mapper, config);
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.addHeader("X-Request-Id", "req-test-999");
    MockHttpServletResponse response = new MockHttpServletResponse();

    writer.writeProblem(
        request, response, "rate-limit-exceeded", 429, 15L, "custom-policy", "rule-abc");

    JsonNode json = mapper.readTree(response.getContentAsString());
    assertThat(json.get("policy").asText()).isEqualTo("custom-policy");
    assertThat(json.get("rule").asText()).isEqualTo("rule-abc");
    assertThat(json.get("instance").asText()).isEqualTo("urn:uuid:req-test-999");
  }
}
