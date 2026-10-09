package io.github.ezmanish.trafficcontrol.spring.validation;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.ezmanish.trafficcontrol.core.api.Algorithm;
import io.github.ezmanish.trafficcontrol.core.api.RateLimitKey;
import io.github.ezmanish.trafficcontrol.spring.annotation.RateLimit;
import io.github.ezmanish.trafficcontrol.spring.annotation.RateLimitPolicy;
import io.github.ezmanish.trafficcontrol.spring.autoconfigure.TrafficControlProperties;
import io.github.ezmanish.trafficcontrol.spring.autoconfigure.TrafficControlProperties.ConcurrencyConfig;
import io.github.ezmanish.trafficcontrol.spring.autoconfigure.TrafficControlProperties.PathRuleConfig;
import io.github.ezmanish.trafficcontrol.spring.autoconfigure.TrafficControlProperties.PolicyConfig;
import io.github.ezmanish.trafficcontrol.spring.autoconfigure.TrafficControlProperties.RuleConfig;
import java.lang.reflect.Method;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PolicyConfigValidatorTest {

  private PolicyConfigValidator validator;
  private TrafficControlProperties properties;

  @BeforeEach
  void setUp() {
    validator = new PolicyConfigValidator();
    properties = new TrafficControlProperties();
  }

  @Test
  @DisplayName("V-001: Policy name must match regex ^[a-z][a-z0-9-]{1,62}$")
  void testV001_InvalidPolicyName() {
    PolicyConfig policy = new PolicyConfig();
    policy.getRules().add(createRule("r1", 10, "1m"));
    properties.getPolicies().put("Invalid_Name!", policy);

    ValidationResult result = validator.validate(properties);
    assertThat(result.hasErrors()).isTrue();
    assertThat(result.getErrors()).anyMatch(e -> e.code().equals("V-001"));
  }

  @Test
  @DisplayName("V-002: Policy must have at least one rate rule or concurrency block")
  void testV002_EmptyPolicy() {
    PolicyConfig policy = new PolicyConfig();
    properties.getPolicies().put("login", policy);

    ValidationResult result = validator.validate(properties);
    assertThat(result.hasErrors()).isTrue();
    assertThat(result.getErrors()).anyMatch(e -> e.code().equals("V-002"));
  }

  @Test
  @DisplayName("V-003: Duplicate rule IDs are rejected")
  void testV003_DuplicateRuleIds() {
    PolicyConfig policy = new PolicyConfig();
    policy.getRules().add(createRule("r1", 10, "1m"));
    policy.getRules().add(createRule("r1", 100, "1h"));
    properties.getPolicies().put("login", policy);

    ValidationResult result = validator.validate(properties);
    assertThat(result.hasErrors()).isTrue();
    assertThat(result.getErrors()).anyMatch(e -> e.code().equals("V-003"));
  }

  @Test
  @DisplayName("V-004: Requests out of range (0 or > 1 billion) rejected")
  void testV004_RequestsOutOfRange() {
    PolicyConfig policy = new PolicyConfig();
    policy.getRules().add(createRule("r1", 0, "1m"));
    properties.getPolicies().put("login", policy);

    ValidationResult result = validator.validate(properties);
    assertThat(result.hasErrors()).isTrue();
    assertThat(result.getErrors()).anyMatch(e -> e.code().equals("V-004"));
  }

  @Test
  @DisplayName("V-005 & V-006: Invalid window format and out of range window rejected")
  void testV005_V006_InvalidWindow() {
    PolicyConfig policy1 = new PolicyConfig();
    policy1.getRules().add(createRule("r1", 10, "1x"));
    properties.getPolicies().put("login", policy1);

    ValidationResult result = validator.validate(properties);
    assertThat(result.hasErrors()).isTrue();
    assertThat(result.getErrors()).anyMatch(e -> e.code().equals("V-005"));

    properties.getPolicies().clear();
    PolicyConfig policy2 = new PolicyConfig();
    policy2.getRules().add(createRule("r1", 10, "10d")); // > 7 days
    properties.getPolicies().put("login", policy2);

    ValidationResult result2 = validator.validate(properties);
    assertThat(result2.hasErrors()).isTrue();
    assertThat(result2.getErrors()).anyMatch(e -> e.code().equals("V-006"));
  }

  @Test
  @DisplayName("V-007: Burst on non-token-bucket algorithm is rejected")
  void testV007_BurstOnFixedWindow() {
    PolicyConfig policy = new PolicyConfig();
    RuleConfig rule = createRule("r1", 10, "1m");
    rule.setAlgorithm(Algorithm.FIXED_WINDOW);
    rule.setBurst(20);
    policy.getRules().add(rule);
    properties.getPolicies().put("login", policy);

    ValidationResult result = validator.validate(properties);
    assertThat(result.hasErrors()).isTrue();
    assertThat(result.getErrors()).anyMatch(e -> e.code().equals("V-007"));
  }

  @Test
  @DisplayName("V-010 & V-011: Concurrency limits validation")
  void testV010_V011_ConcurrencyValidation() {
    PolicyConfig policy = new PolicyConfig();
    ConcurrencyConfig cc = new ConcurrencyConfig();
    cc.setMax(0); // invalid
    cc.setLeaseTtl("5d"); // invalid (> 1h)
    policy.setConcurrency(cc);
    properties.getPolicies().put("login", policy);

    ValidationResult result = validator.validate(properties);
    assertThat(result.hasErrors()).isTrue();
    assertThat(result.getErrors()).anyMatch(e -> e.code().equals("V-010"));
    assertThat(result.getErrors()).anyMatch(e -> e.code().equals("V-011"));
  }

  @Test
  @DisplayName("V-014: CUSTOM key without keyResolver bean is rejected")
  void testV014_CustomKeyWithoutResolver() {
    PolicyConfig policy = new PolicyConfig();
    policy.setKey(RateLimitKey.CUSTOM);
    policy.getRules().add(createRule("r1", 10, "1m"));
    properties.getPolicies().put("login", policy);

    ValidationResult result = validator.validate(properties);
    assertThat(result.hasErrors()).isTrue();
    assertThat(result.getErrors()).anyMatch(e -> e.code().equals("V-014"));
  }

  @Test
  @DisplayName("V-018: Path rules referencing unknown policy")
  void testV018_UnknownPolicyInPathRule() {
    PathRuleConfig pr = new PathRuleConfig();
    pr.setPath("/api/login");
    pr.setPolicy("unknown-policy");
    properties.getRules().add(pr);

    ValidationResult result = validator.validate(properties);
    assertThat(result.hasErrors()).isTrue();
    assertThat(result.getErrors()).anyMatch(e -> e.code().equals("V-018"));
  }

  @Test
  @DisplayName("V-020: @RateLimitPolicy referencing unknown policy with 'did you mean' hint")
  void testV020_UnknownPolicyAnnotation() throws Exception {
    PolicyConfig policy = new PolicyConfig();
    policy.getRules().add(createRule("r1", 10, "1m"));
    properties.getPolicies().put("login", policy);

    ValidationResult result = new ValidationResult();
    Method method = SampleController.class.getMethod("misspelledPolicy");
    validator.validateMethod(method, SampleController.class, properties.getPolicies(), result);

    assertThat(result.hasErrors()).isTrue();
    assertThat(result.getErrors())
        .anyMatch(e -> e.code().equals("V-020") && e.message().contains("did you mean 'login'?"));
  }

  @Test
  @DisplayName("V-021: Both @RateLimit and @RateLimitPolicy on the same method is rejected")
  void testV021_ConflictingAnnotations() throws Exception {
    ValidationResult result = new ValidationResult();
    Method method = SampleController.class.getMethod("conflictingAnnotations");
    validator.validateMethod(method, SampleController.class, properties.getPolicies(), result);

    assertThat(result.hasErrors()).isTrue();
    assertThat(result.getErrors()).anyMatch(e -> e.code().equals("V-021"));
  }

  @Test
  @DisplayName("V-025: Invalid trusted-proxies CIDR notation")
  void testV025_InvalidCidr() {
    properties.setTrustedProxies(List.of("192.168.1.500/24"));
    ValidationResult result = validator.validate(properties);

    assertThat(result.hasErrors()).isTrue();
    assertThat(result.getErrors()).anyMatch(e -> e.code().equals("V-025"));
  }

  @Test
  @DisplayName("V-028: Warning when two identical rules exist in policy")
  void testV028_IdenticalRulesWarning() {
    PolicyConfig policy = new PolicyConfig();
    policy.getRules().add(createRule("r1", 10, "1m"));
    policy.getRules().add(createRule("r2", 10, "1m"));
    properties.getPolicies().put("login", policy);

    ValidationResult result = validator.validate(properties);
    assertThat(result.hasErrors()).isFalse();
    assertThat(result.getWarnings()).anyMatch(w -> w.code().equals("V-028"));
  }

  @Test
  @DisplayName("V-029: COMPOSITE requires 2 to 3 distinct valid components")
  void testV029_CompositeValidation() {
    PolicyConfig policy = new PolicyConfig();
    policy.setKey(RateLimitKey.COMPOSITE);
    policy.setComponents(List.of(RateLimitKey.USER)); // Only 1 component!
    policy.getRules().add(createRule("r1", 10, "1m"));
    properties.getPolicies().put("login", policy);

    ValidationResult result = validator.validate(properties);
    assertThat(result.hasErrors()).isTrue();
    assertThat(result.getErrors()).anyMatch(e -> e.code().equals("V-029"));
  }

  @Test
  @DisplayName("TC-155: V-027 admin enabled without security fails validation")
  void testV027_AdminEnabledWithoutSecurity() {
    properties.getAdmin().setEnabled(true);
    ValidationResult result = validator.validate(properties, false);
    assertThat(result.hasErrors()).isTrue();
    assertThat(result.getErrors()).anyMatch(e -> e.code().equals("V-027"));

    ValidationResult resultWithSec = validator.validate(properties, true);
    assertThat(resultWithSec.hasErrors()).isFalse();
  }

  @Test
  @DisplayName("V-016: Unknown on-missing-key strategy fails validation")
  void testV016_UnknownOnMissingKey() {
    PolicyConfig policy = new PolicyConfig();
    policy.getRules().add(createRule("r1", 10, "1m"));
    policy.setOnMissingKey(null);
    properties.getPolicies().put("login", policy);

    ValidationResult result = validator.validate(properties);
    assertThat(result.hasErrors()).isTrue();
    assertThat(result.getErrors()).anyMatch(e -> e.code().equals("V-016"));
  }

  private RuleConfig createRule(String id, long requests, String window) {
    RuleConfig r = new RuleConfig();
    r.setId(id);
    r.setRequests(requests);
    r.setWindow(window);
    return r;
  }

  static class SampleController {
    @RateLimitPolicy("logn")
    public void misspelledPolicy() {}

    @RateLimit(requests = 10, window = "1m")
    @RateLimitPolicy("login")
    public void conflictingAnnotations() {}
  }
}
