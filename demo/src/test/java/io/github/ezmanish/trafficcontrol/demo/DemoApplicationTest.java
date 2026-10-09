package io.github.ezmanish.trafficcontrol.demo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
class DemoApplicationTest {

  @Autowired private MockMvc mockMvc;

  @Test
  @DisplayName(
      "Demo: Rate limit enforced on public endpoint; returns 429 and problem details when exhausted")
  void testPublicEndpointRateLimiting() throws Exception {
    String clientIp = "198.51.100.42";

    // First 5 requests allowed
    for (int i = 0; i < 5; i++) {
      mockMvc
          .perform(
              get("/api/public")
                  .with(
                      req -> {
                        req.setRemoteAddr(clientIp);
                        return req;
                      }))
          .andExpect(status().isOk())
          .andExpect(header().exists("RateLimit-Remaining"))
          .andExpect(header().exists("X-RateLimit-Remaining"))
          .andExpect(jsonPath("$.message").value("Welcome to public API"));
    }

    // 6th request rejected with 429 and RFC 9457 problem details
    MvcResult result =
        mockMvc
            .perform(
                get("/api/public")
                    .with(
                        req -> {
                          req.setRemoteAddr(clientIp);
                          return req;
                        }))
            .andExpect(status().isTooManyRequests())
            .andExpect(header().string("RateLimit-Remaining", "0"))
            .andExpect(header().exists("Retry-After"))
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
            .andExpect(jsonPath("$.status").value(429))
            .andReturn();

    assertThat(result.getResponse().getStatus()).isEqualTo(429);
  }

  @Test
  @DisplayName("Demo: Unprotected endpoint serves traffic freely without rate limit headers")
  void testUnprotectedEndpoint() throws Exception {
    mockMvc
        .perform(get("/api/unprotected"))
        .andExpect(status().isOk())
        .andExpect(header().doesNotExist("RateLimit-Limit"))
        .andExpect(jsonPath("$.message").value("Unprotected endpoint"));
  }

  @Test
  @DisplayName("Demo: Actuator health endpoint exposes rate limiting health indicator")
  void testActuatorHealthIndicator() throws Exception {
    mockMvc
        .perform(get("/actuator/health"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.components.trafficControl.status").value("UP"))
        .andExpect(jsonPath("$.components.trafficControl.details.store").value("local"))
        .andExpect(jsonPath("$.components.trafficControl.details.policiesCount").value(3));
  }
}
