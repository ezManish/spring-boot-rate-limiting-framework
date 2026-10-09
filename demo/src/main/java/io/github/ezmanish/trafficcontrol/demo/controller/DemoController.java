package io.github.ezmanish.trafficcontrol.demo.controller;

import io.github.ezmanish.trafficcontrol.core.api.Algorithm;
import io.github.ezmanish.trafficcontrol.core.api.RateLimitKey;
import io.github.ezmanish.trafficcontrol.spring.annotation.RateLimit;
import io.github.ezmanish.trafficcontrol.spring.annotation.RateLimitPolicy;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Showcase REST endpoints protected by TrafficControl rate limiting. */
@RestController
@RequestMapping("/api")
public class DemoController {

  @GetMapping("/public")
  @RateLimitPolicy("public-api")
  public ResponseEntity<Map<String, String>> publicEndpoint() {
    return ResponseEntity.ok(Map.of("message", "Welcome to public API"));
  }

  @PostMapping("/login")
  @RateLimitPolicy("login")
  public ResponseEntity<Map<String, String>> loginEndpoint() {
    return ResponseEntity.ok(Map.of("message", "Login successful"));
  }

  @GetMapping("/search")
  @RateLimit(requests = 10, window = "1m", algorithm = Algorithm.GCRA, key = RateLimitKey.IP)
  public ResponseEntity<Map<String, String>> searchEndpoint() {
    return ResponseEntity.ok(Map.of("query", "trafficcontrol", "status", "success"));
  }

  @GetMapping("/unprotected")
  public ResponseEntity<Map<String, String>> unprotectedEndpoint() {
    return ResponseEntity.ok(Map.of("message", "Unprotected endpoint"));
  }
}
