package io.github.ezmanish.trafficcontrol.spring.web.interceptor;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import io.github.ezmanish.trafficcontrol.core.api.RateLimitKey;
import io.github.ezmanish.trafficcontrol.core.spi.RateLimitKeyResolver;
import io.github.ezmanish.trafficcontrol.spring.annotation.RateLimit;
import io.github.ezmanish.trafficcontrol.spring.annotation.RateLimitPolicy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@SpringBootTest(
    classes = RateLimitInterceptorIntegrationTest.TestApplication.class,
    properties = {
      "trafficcontrol.enabled=true",
      "trafficcontrol.store=local",
      "trafficcontrol.headers.enabled=true",
      "trafficcontrol.headers.style=LEGACY",
      "trafficcontrol.policies.login.key=IP",
      "trafficcontrol.policies.login.rules[0].id=r1",
      "trafficcontrol.policies.login.rules[0].requests=2",
      "trafficcontrol.policies.login.rules[0].window=1m",
      "trafficcontrol.policies.concurrent.key=GLOBAL",
      "trafficcontrol.policies.concurrent.concurrency.max=1",
      "trafficcontrol.policies.custom-key.key=CUSTOM",
      "trafficcontrol.policies.custom-key.key-resolver=tenantKeyResolver",
      "trafficcontrol.policies.custom-key.rules[0].requests=5",
      "trafficcontrol.policies.custom-key.rules[0].window=1m"
    })
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class RateLimitInterceptorIntegrationTest {

  @Autowired private MockMvc mockMvc;

  @Test
  @DisplayName(
      "TC-010 & TC-011: @RateLimit on endpoint decrements remaining and rejects when exceeded")
  void testRateLimitEndpointExceeded() throws Exception {
    // Request 1: allowed, remaining = 1
    mockMvc
        .perform(get("/api/hello").principal(() -> "user1"))
        .andExpect(status().isOk())
        .andExpect(header().string("X-RateLimit-Limit", "2"))
        .andExpect(header().string("X-RateLimit-Remaining", "1"));

    // Request 2: allowed, remaining = 0
    mockMvc
        .perform(get("/api/hello").principal(() -> "user1"))
        .andExpect(status().isOk())
        .andExpect(header().string("X-RateLimit-Limit", "2"))
        .andExpect(header().string("X-RateLimit-Remaining", "0"));

    // Request 3: rejected 429
    mockMvc
        .perform(get("/api/hello").principal(() -> "user1"))
        .andExpect(status().isTooManyRequests())
        .andExpect(header().string("Cache-Control", "no-store"))
        .andExpect(header().exists("Retry-After"))
        .andExpect(content().contentType("application/problem+json;charset=UTF-8"))
        .andExpect(jsonPath("$.type").value("urn:trafficcontrol:problem:rate-limit-exceeded"))
        .andExpect(jsonPath("$.status").value(429));
  }

  @Test
  @DisplayName("TC-012: USER key provides isolation between different users")
  void testUserIsolation() throws Exception {
    // user1 uses 2 requests -> exhausts quota
    mockMvc.perform(get("/api/hello").principal(() -> "user1")).andExpect(status().isOk());
    mockMvc.perform(get("/api/hello").principal(() -> "user1")).andExpect(status().isOk());
    mockMvc
        .perform(get("/api/hello").principal(() -> "user1"))
        .andExpect(status().isTooManyRequests());

    // user2 is isolated and has fresh quota
    mockMvc
        .perform(get("/api/hello").principal(() -> "user2"))
        .andExpect(status().isOk())
        .andExpect(header().string("X-RateLimit-Remaining", "1"));
  }

  @Test
  @DisplayName("TC-013: IP key shares quota across users from the same IP")
  void testIpSharedQuota() throws Exception {
    // user1 from 192.0.2.1
    mockMvc
        .perform(
            get("/api/login")
                .with(
                    req -> {
                      req.setRemoteAddr("192.0.2.1");
                      return req;
                    })
                .principal(() -> "user1"))
        .andExpect(status().isOk());

    // user2 from the SAME IP
    mockMvc
        .perform(
            get("/api/login")
                .with(
                    req -> {
                      req.setRemoteAddr("192.0.2.1");
                      return req;
                    })
                .principal(() -> "user2"))
        .andExpect(status().isOk());

    // 3rd request from same IP is rejected
    mockMvc
        .perform(
            get("/api/login")
                .with(
                    req -> {
                      req.setRemoteAddr("192.0.2.1");
                      return req;
                    })
                .principal(() -> "user3"))
        .andExpect(status().isTooManyRequests());
  }

  @Test
  @DisplayName("TC-014: @RateLimitPolicy resolves YAML policy and enforces it")
  void testNamedPolicyResolution() throws Exception {
    mockMvc
        .perform(
            get("/api/login")
                .with(
                    req -> {
                      req.setRemoteAddr("10.0.0.100");
                      return req;
                    }))
        .andExpect(status().isOk())
        .andExpect(header().string("X-RateLimit-Limit", "2"));
  }

  @Test
  @DisplayName("TC-016: Method annotation takes precedence over class annotation")
  void testMethodAnnotationPrecedence() throws Exception {
    // Class has requests=10, method overrides to requests=1
    mockMvc
        .perform(get("/api/class-precedence/override").principal(() -> "alice"))
        .andExpect(status().isOk())
        .andExpect(header().string("X-RateLimit-Limit", "1"))
        .andExpect(header().string("X-RateLimit-Remaining", "0"));

    mockMvc
        .perform(get("/api/class-precedence/override").principal(() -> "alice"))
        .andExpect(status().isTooManyRequests());
  }

  @Test
  @DisplayName("TC-017: Custom RateLimitKeyResolver bean is used when key is CUSTOM")
  void testCustomKeyResolver() throws Exception {
    mockMvc
        .perform(get("/api/custom").header("X-Tenant", "acme-corp"))
        .andExpect(status().isOk())
        .andExpect(header().string("X-RateLimit-Limit", "5"));
  }

  @Test
  @DisplayName("TC-018: Missing user falls back to IP")
  void testMissingUserFallbackToIp() throws Exception {
    // Unauthenticated request (no principal) -> falls back to remote IP
    mockMvc
        .perform(
            get("/api/hello")
                .with(
                    req -> {
                      req.setRemoteAddr("198.51.100.25");
                      return req;
                    }))
        .andExpect(status().isOk())
        .andExpect(header().string("X-RateLimit-Remaining", "1"));
  }

  @Test
  @DisplayName("TC-050 & TC-051: Concurrency limiting and permit release")
  void testConcurrencyLimitingAndRelease() throws Exception {
    // Single permit available; after completion permit is released
    mockMvc.perform(get("/api/concurrent")).andExpect(status().isOk());
    // Second sequential call succeeds because first permit was released!
    mockMvc.perform(get("/api/concurrent")).andExpect(status().isOk());
  }

  @SpringBootApplication(scanBasePackages = "io.github.ezmanish.trafficcontrol")
  @org.springframework.context.annotation.Import({
    TestEndpoints.class,
    ClassPrecedenceController.class
  })
  static class TestApplication {

    @Bean
    public RateLimitKeyResolver tenantKeyResolver() {
      return ctx -> ctx.header("x-tenant");
    }
  }

  @RestController
  static class TestEndpoints {

    @GetMapping("/api/hello")
    @RateLimit(requests = 2, window = "1m", key = RateLimitKey.USER)
    public String hello() {
      return "hello";
    }

    @GetMapping("/api/login")
    @RateLimitPolicy("login")
    public String login() {
      return "login-ok";
    }

    @GetMapping("/api/concurrent")
    @RateLimitPolicy("concurrent")
    public String concurrent() {
      return "concurrent-ok";
    }

    @GetMapping("/api/custom")
    @RateLimitPolicy("custom-key")
    public String custom() {
      return "custom-ok";
    }
  }

  @RestController
  @RateLimit(requests = 10, window = "1m", key = RateLimitKey.USER)
  static class ClassPrecedenceController {

    @GetMapping("/api/class-precedence/override")
    @RateLimit(requests = 1, window = "1m", key = RateLimitKey.USER)
    public String overrideMethod() {
      return "override";
    }
  }
}
