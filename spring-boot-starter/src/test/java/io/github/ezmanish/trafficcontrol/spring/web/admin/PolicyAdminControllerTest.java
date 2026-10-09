package io.github.ezmanish.trafficcontrol.spring.web.admin;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.ezmanish.trafficcontrol.spring.autoconfigure.TrafficControlProperties;
import io.github.ezmanish.trafficcontrol.spring.autoconfigure.TrafficControlProperties.PathRuleConfig;
import io.github.ezmanish.trafficcontrol.spring.autoconfigure.TrafficControlProperties.PolicyConfig;
import io.github.ezmanish.trafficcontrol.spring.autoconfigure.TrafficControlProperties.RuleConfig;
import io.github.ezmanish.trafficcontrol.spring.validation.PolicyConfigValidator;
import io.github.ezmanish.trafficcontrol.spring.web.policy.PolicyRegistry;
import java.security.Principal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

class PolicyAdminControllerTest {

  private PolicyRegistry registry;
  private PolicyConfigValidator validator;
  private PolicyAuditLog auditLog;
  private PolicyAdminController controller;
  private ObjectMapper objectMapper;
  private Principal principal;

  @BeforeEach
  void setUp() {
    objectMapper = new ObjectMapper();
    validator = new PolicyConfigValidator();
    auditLog = new PolicyAuditLog();
    principal = () -> "superadmin";

    TrafficControlProperties props = new TrafficControlProperties();
    PolicyConfig policy = new PolicyConfig();
    RuleConfig rule = new RuleConfig();
    rule.setRequests(50);
    rule.setWindow("1m");
    policy.setRules(List.of(rule));
    props.setPolicies(Map.of("api", policy));

    PathRuleConfig pr = new PathRuleConfig();
    pr.setPath("/api/**");
    pr.setPolicy("api");
    props.setRules(List.of(pr));

    registry = new PolicyRegistry(props);
    controller =
        new PolicyAdminController(registry, validator, auditLog, Optional.empty(), objectMapper);
  }

  @Test
  @DisplayName("GET /policies returns active snapshot and ETag header matching version")
  void testGetPoliciesReturnsSnapshotAndETag() {
    ResponseEntity<Map<String, Object>> response = controller.getPolicies();

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getHeaders().getFirst(HttpHeaders.ETAG)).isEqualTo("\"1\"");
    assertThat(response.getBody()).containsKeys("version", "checksum", "policies");
    assertThat(response.getBody().get("version")).isEqualTo(1L);
  }

  @Test
  @DisplayName("GET /policies/{name} returns 200 for existing and 404 for missing policy")
  void testGetPolicyByName() {
    ResponseEntity<?> ok = controller.getPolicy("api");
    assertThat(ok.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(ok.getHeaders().getFirst(HttpHeaders.ETAG)).isEqualTo("\"1\"");

    ResponseEntity<?> notFound = controller.getPolicy("missing");
    assertThat(notFound.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
  }

  @Test
  @DisplayName("PUT /policies/{name} rejects request with 428 when If-Match header is missing")
  void testPutRequiresIfMatchHeader() {
    PolicyConfig newPolicy = new PolicyConfig();
    ResponseEntity<?> response = controller.putPolicy("new-policy", newPolicy, null, principal);

    assertThat(response.getStatusCode().value()).isEqualTo(428);
  }

  @Test
  @DisplayName("PUT /policies/{name} rejects request with 412 when If-Match version mismatches")
  void testPutVersionMismatchReturns412() {
    PolicyConfig newPolicy = new PolicyConfig();
    ResponseEntity<?> response = controller.putPolicy("new-policy", newPolicy, "\"99\"", principal);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.PRECONDITION_FAILED);
  }

  @Test
  @DisplayName(
      "PUT /policies/{name} returns 422 Problem Details when configuration fails validation")
  void testPutInvalidPolicyReturns422() {
    PolicyConfig invalid = new PolicyConfig();
    RuleConfig r = new RuleConfig();
    r.setRequests(-10); // violates V-004
    r.setWindow("0s"); // violates V-006
    invalid.setRules(List.of(r));

    ResponseEntity<?> response = controller.putPolicy("bad-policy", invalid, "\"1\"", principal);

    assertThat(response.getStatusCode().value()).isEqualTo(422);
    Map<?, ?> body = (Map<?, ?>) response.getBody();
    assertThat(body.get("type")).isEqualTo("urn:trafficcontrol:problem:invalid-policy");
    assertThat(body.get("errors")).isNotNull();
  }

  @Test
  @DisplayName("PUT /policies/{name} creates new policy and returns 201 with updated ETag")
  void testPutCreatesNewPolicy() {
    PolicyConfig valid = new PolicyConfig();
    RuleConfig r = new RuleConfig();
    r.setRequests(200);
    r.setWindow("1m");
    valid.setRules(List.of(r));

    ResponseEntity<?> response = controller.putPolicy("checkout", valid, "\"1\"", principal);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    assertThat(response.getHeaders().getFirst(HttpHeaders.ETAG)).isEqualTo("\"2\"");
    assertThat(registry.getSnapshot().version()).isEqualTo(2L);
    assertThat(registry.hasPolicy("checkout")).isTrue();

    // Verify audit log recorded creation
    List<PolicyAuditEntry> audits = auditLog.getEntries(10);
    assertThat(audits).isNotEmpty();
    assertThat(audits.get(0).action()).isEqualTo("CREATE");
    assertThat(audits.get(0).policy()).isEqualTo("checkout");
  }

  @Test
  @DisplayName(
      "DELETE /policies/{name} returns 409 Conflict if policy is bound to an active path rule")
  void testDeletePolicyInUseReturns409() {
    ResponseEntity<?> response = controller.deletePolicy("api", "\"1\"", principal);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    Map<?, ?> body = (Map<?, ?>) response.getBody();
    assertThat(body.get("type")).isEqualTo("urn:trafficcontrol:problem:policy-in-use");
    assertThat(registry.hasPolicy("api")).isTrue();
  }

  @Test
  @DisplayName("DELETE /policies/{name} deletes unreferenced policy and returns 204")
  void testDeleteUnreferencedPolicyReturns204() {
    // First create an unreferenced policy
    PolicyConfig valid = new PolicyConfig();
    RuleConfig r = new RuleConfig();
    r.setRequests(10);
    r.setWindow("10s");
    valid.setRules(List.of(r));
    controller.putPolicy("temp", valid, "\"1\"", principal);

    // Now delete it with If-Match: "2"
    ResponseEntity<?> response = controller.deletePolicy("temp", "\"2\"", principal);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    assertThat(registry.hasPolicy("temp")).isFalse();
    assertThat(registry.getSnapshot().version()).isEqualTo(3L);
  }

  @Test
  @DisplayName("POST /rollback restores previously retained snapshot")
  void testRollbackRestoresPreviousVersion() {
    // Current is v1. Add policy to create v2.
    PolicyConfig valid = new PolicyConfig();
    RuleConfig r = new RuleConfig();
    r.setRequests(15);
    r.setWindow("5s");
    valid.setRules(List.of(r));
    controller.putPolicy("v2-policy", valid, "\"1\"", principal);
    assertThat(registry.hasPolicy("v2-policy")).isTrue();

    // Rollback to v1
    ResponseEntity<?> response = controller.rollback(Map.of("toVersion", 1L), "\"2\"", principal);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(registry.getSnapshot().version()).isEqualTo(3L);
    assertThat(registry.hasPolicy("v2-policy")).isFalse();
    assertThat(registry.hasPolicy("api")).isTrue();
  }
}
