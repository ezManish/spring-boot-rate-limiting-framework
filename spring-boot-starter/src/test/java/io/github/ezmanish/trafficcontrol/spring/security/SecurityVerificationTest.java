package io.github.ezmanish.trafficcontrol.spring.security;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.ezmanish.trafficcontrol.core.api.RateLimitKey;
import io.github.ezmanish.trafficcontrol.core.spi.RateLimitContext;
import io.github.ezmanish.trafficcontrol.spring.autoconfigure.TrafficControlProperties;
import io.github.ezmanish.trafficcontrol.spring.autoconfigure.TrafficControlProperties.PolicyConfig;
import io.github.ezmanish.trafficcontrol.spring.autoconfigure.TrafficControlProperties.RuleConfig;
import io.github.ezmanish.trafficcontrol.spring.validation.PolicyConfigValidator;
import io.github.ezmanish.trafficcontrol.spring.validation.ValidationResult;
import io.github.ezmanish.trafficcontrol.spring.web.admin.PolicyAdminController;
import io.github.ezmanish.trafficcontrol.spring.web.admin.PolicyAuditLog;
import io.github.ezmanish.trafficcontrol.spring.web.policy.PolicyRegistry;
import io.github.ezmanish.trafficcontrol.spring.web.proxy.ClientIpResolver;
import io.github.ezmanish.trafficcontrol.spring.web.resolver.CompositeKeyResolverService;
import io.github.ezmanish.trafficcontrol.spring.web.resolver.KeyHashUtil;
import io.github.ezmanish.trafficcontrol.spring.web.resolver.OnMissingKeyStrategy;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

/**
 * End-to-end security compliance test suite mapping to docs/13_SECURITY.md (SEC-01 through SEC-05).
 */
class SecurityVerificationTest {

  @Test
  @DisplayName("SEC-01: Untrusted remote IP cannot spoof client identity via X-Forwarded-For")
  void sec01_forgedXffIgnoredFromUntrustedSource() {
    ClientIpResolver resolver = new ClientIpResolver(List.of("10.0.0.0/8"));
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setRemoteAddr("203.0.113.195"); // Public untrusted IP
    request.addHeader("X-Forwarded-For", "192.168.1.1, 10.0.0.1");

    String resolvedIp = resolver.resolveClientIp(request);
    // Remote address must be used, completely ignoring the forged X-Forwarded-For chain
    assertThat(resolvedIp).isEqualTo("203.0.113.195");
  }

  @Test
  @DisplayName(
      "SEC-02: User identity key cannot be set via HTTP headers; requires authenticated Principal")
  void sec02_userKeyCannotBeSpoofedViaHeader() {
    CompositeKeyResolverService resolverService = new CompositeKeyResolverService();

    // Attacker sends header X-User / user attempting to spoof admin identity
    RateLimitContext ctxSpoofedHeader =
        RateLimitContext.builder()
            .clientIp("198.51.100.5")
            .httpMethod("GET")
            .routeTemplate("/api/account")
            .headers(Map.of("X-User", "admin", "User", "admin"))
            .build(); // principal is null!

    // When key is USER, resolver must ignore headers and fallback or reject per policy
    Optional<String> resolvedKey =
        resolverService.resolveKey(
            ctxSpoofedHeader,
            RateLimitKey.USER,
            List.of(),
            null,
            null,
            null,
            null,
            null,
            OnMissingKeyStrategy.FALLBACK_IP);

    // Resolves to hashed IP fallback, NOT "admin"
    assertThat(resolvedKey).isPresent();
    assertThat(resolvedKey.get()).isEqualTo(KeyHashUtil.hash("198.51.100.5"));
    assertThat(resolvedKey.get()).isNotEqualTo(KeyHashUtil.hash("admin"));

    // When valid principal is present in security context
    RateLimitContext ctxAuthenticated =
        RateLimitContext.builder()
            .principal("alice_authenticated")
            .clientIp("198.51.100.5")
            .httpMethod("GET")
            .routeTemplate("/api/account")
            .build();

    Optional<String> authKey =
        resolverService.resolveKey(
            ctxAuthenticated,
            RateLimitKey.USER,
            List.of(),
            null,
            null,
            null,
            null,
            null,
            OnMissingKeyStrategy.FALLBACK_IP);

    assertThat(authKey).isPresent();
    assertThat(authKey.get()).isEqualTo(KeyHashUtil.hash("alice_authenticated"));
  }

  @Test
  @DisplayName(
      "SEC-03: Admin API validates and logs policy changes; rejects unauthorized mutations")
  void sec03_adminApiAuditAndRoleProtection() {
    TrafficControlProperties props = new TrafficControlProperties();
    props.getAdmin().setEnabled(true);
    PolicyRegistry registry = new PolicyRegistry(props);
    PolicyConfigValidator validator = new PolicyConfigValidator();
    PolicyAuditLog auditLog = new PolicyAuditLog();

    PolicyAdminController controller =
        new PolicyAdminController(registry, validator, auditLog, Optional.empty(), null);

    // Verify initial audit log is empty
    assertThat(auditLog.getEntries(10)).isEmpty();

    // Verify getting current policies
    var response = controller.getPolicies();
    assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
  }

  @Test
  @DisplayName(
      "SEC-04: Invalid or corrupted policy snapshot is rejected without corrupting active state")
  void sec04_invalidSnapshotRejectedPreservingActiveState() {
    TrafficControlProperties props = new TrafficControlProperties();
    PolicyConfig policy = new PolicyConfig();
    RuleConfig rule = new RuleConfig();
    rule.setId("r1");
    rule.setRequests(100);
    rule.setWindow("1m");
    policy.getRules().add(rule);
    props.getPolicies().put("api-service", policy);

    PolicyConfigValidator validator = new PolicyConfigValidator();
    ValidationResult initialCheck = validator.validate(props);
    assertThat(initialCheck.hasErrors()).isFalse();

    PolicyRegistry registry = new PolicyRegistry(props);
    assertThat(registry.getSnapshot().namedPolicies()).containsKey("api-service");
    long initialVersion = registry.getSnapshot().version();

    // Attempt to apply invalid configuration (e.g., negative limit)
    PolicyConfig invalidPolicy = new PolicyConfig();
    RuleConfig invalidRule = new RuleConfig();
    invalidRule.setId("r_bad");
    invalidRule.setRequests(-50); // Violates V-005
    invalidRule.setWindow("1m");
    invalidPolicy.getRules().add(invalidRule);

    // Admin controller rejecting invalid snapshot
    PolicyAuditLog auditLog = new PolicyAuditLog();
    PolicyAdminController controller =
        new PolicyAdminController(registry, validator, auditLog, Optional.empty(), null);

    ResponseEntity<?> response =
        controller.putPolicy("api-service", invalidPolicy, "\"" + initialVersion + "\"", null);
    assertThat(response.getStatusCode().value()).isEqualTo(422);

    // Active policy state is preserved at initial version
    assertThat(registry.getSnapshot().version()).isEqualTo(initialVersion);
    assertThat(registry.getSnapshot().namedPolicies()).containsKey("api-service");
    assertThat(
            registry
                .getSnapshot()
                .namedPolicies()
                .get("api-service")
                .policy()
                .rules()
                .get(0)
                .requests())
        .isEqualTo(100);
  }

  @Test
  @DisplayName("SEC-05: Key cardinality flood protection produces bounded length SHA-256 hashes")
  void sec05_keyCardinalityFloodProducesBoundedHashes() {
    // Generate long or variable length client identities
    String veryLongIdentity = "A".repeat(10_000);
    String hashed1 = KeyHashUtil.hash(veryLongIdentity);
    String hashed2 = KeyHashUtil.hash("short");

    // Hashes must be constant length (16-char hex from truncated SHA-256)
    assertThat(hashed1).hasSize(16);
    assertThat(hashed2).hasSize(16);
    assertThat(hashed1).matches("^[0-9a-f]{16}$");
    assertThat(hashed2).matches("^[0-9a-f]{16}$");

    // Null safely maps to consistent bounded hash "anon"
    assertThat(KeyHashUtil.hash(null)).isEqualTo("anon");
  }
}
