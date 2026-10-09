package io.github.ezmanish.trafficcontrol.spring.web.response;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.ezmanish.trafficcontrol.core.api.Decision;
import io.github.ezmanish.trafficcontrol.spring.autoconfigure.TrafficControlProperties.HeadersConfig;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;

class RateLimitHeaderWriterTest {

  private final RateLimitHeaderWriter writer = new RateLimitHeaderWriter();

  @Test
  @DisplayName("TC-121: LEGACY headers with epoch reset seconds")
  void testLegacyHeaders() {
    HeadersConfig config = new HeadersConfig();
    config.setEnabled(true);
    config.setStyle("LEGACY");

    Instant resetTime = Instant.ofEpochSecond(1760000000L);
    Decision decision = Decision.allow(100, 95, resetTime, "login", "r1", null);

    MockHttpServletResponse response = new MockHttpServletResponse();
    writer.writeHeaders(response, decision, config);

    assertThat(response.getHeader("X-RateLimit-Limit")).isEqualTo("100");
    assertThat(response.getHeader("X-RateLimit-Remaining")).isEqualTo("95");
    assertThat(response.getHeader("X-RateLimit-Reset")).isEqualTo("1760000000");
    assertThat(response.getHeader("RateLimit-Limit")).isNull();
  }

  @Test
  @DisplayName("TC-122: BOTH style emits both LEGACY and IETF headers")
  void testBothHeaderStyles() {
    HeadersConfig config = new HeadersConfig();
    config.setEnabled(true);
    config.setStyle("BOTH");

    Instant resetTime = Instant.now().plusSeconds(60);
    Decision decision = Decision.allow(10, 8, resetTime, "search", "r1", null);

    MockHttpServletResponse response = new MockHttpServletResponse();
    writer.writeHeaders(response, decision, config);

    assertThat(response.getHeader("X-RateLimit-Limit")).isEqualTo("10");
    assertThat(response.getHeader("X-RateLimit-Remaining")).isEqualTo("8");
    assertThat(response.getHeader("RateLimit-Limit")).isEqualTo("10");
    assertThat(response.getHeader("RateLimit-Remaining")).isEqualTo("8");
    assertThat(response.getHeader("RateLimit-Reset")).isNotNull();
  }

  @Test
  @DisplayName("TC-125: Degraded decisions emit NO rate limit headers")
  void testDegradedDecisionEmitsNoHeaders() {
    HeadersConfig config = new HeadersConfig();
    config.setEnabled(true);
    config.setStyle("BOTH");

    Decision degradedDecision = Decision.degradedAllow("login");

    MockHttpServletResponse response = new MockHttpServletResponse();
    writer.writeHeaders(response, degradedDecision, config);

    assertThat(response.getHeader("X-RateLimit-Limit")).isNull();
    assertThat(response.getHeader("X-RateLimit-Remaining")).isNull();
    assertThat(response.getHeader("RateLimit-Limit")).isNull();
  }
}
