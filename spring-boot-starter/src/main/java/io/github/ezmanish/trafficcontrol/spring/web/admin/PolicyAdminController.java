package io.github.ezmanish.trafficcontrol.spring.web.admin;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.ezmanish.trafficcontrol.spring.autoconfigure.TrafficControlProperties;
import io.github.ezmanish.trafficcontrol.spring.autoconfigure.TrafficControlProperties.PathRuleConfig;
import io.github.ezmanish.trafficcontrol.spring.autoconfigure.TrafficControlProperties.PolicyConfig;
import io.github.ezmanish.trafficcontrol.spring.validation.PolicyConfigValidator;
import io.github.ezmanish.trafficcontrol.spring.validation.ValidationResult;
import io.github.ezmanish.trafficcontrol.spring.web.policy.PolicyRegistry;
import io.github.ezmanish.trafficcontrol.spring.web.policy.PolicySnapshot;
import io.github.ezmanish.trafficcontrol.spring.web.policy.update.PolicyUpdateBroadcaster;
import java.security.Principal;
import java.time.Instant;
import java.util.*;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * REST controller implementing the TrafficControl Admin API (05_API_SPECIFICATION.md §5). Secured
 * under trafficcontrol.admin.base-path, disabled by default.
 */
@RestController
@RequestMapping("${trafficcontrol.admin.base-path:/trafficcontrol/admin}")
public class PolicyAdminController {

  private final PolicyRegistry policyRegistry;
  private final PolicyConfigValidator validator;
  private final PolicyAuditLog auditLog;
  private final Optional<PolicyUpdateBroadcaster> broadcaster;
  private final ObjectMapper objectMapper;

  public PolicyAdminController(
      PolicyRegistry policyRegistry,
      PolicyConfigValidator validator,
      PolicyAuditLog auditLog,
      Optional<PolicyUpdateBroadcaster> broadcaster,
      ObjectMapper objectMapper) {
    this.policyRegistry = Objects.requireNonNull(policyRegistry, "policyRegistry cannot be null");
    this.validator = validator != null ? validator : new PolicyConfigValidator();
    this.auditLog = auditLog != null ? auditLog : new PolicyAuditLog();
    this.broadcaster = broadcaster != null ? broadcaster : Optional.empty();
    this.objectMapper = objectMapper != null ? objectMapper : new ObjectMapper();

    // Retain initial startup snapshot
    this.auditLog.retainSnapshot(policyRegistry.getSnapshot());
  }

  @GetMapping(value = "/policies", produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<Map<String, Object>> getPolicies() {
    PolicySnapshot snapshot = policyRegistry.getSnapshot();
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("version", snapshot.version());
    body.put("checksum", snapshot.checksum());
    body.put("updatedAt", snapshot.updatedAt().toString());
    body.put("updatedBy", snapshot.updatedBy());
    body.put("policies", snapshot.rawConfigs());

    return ResponseEntity.ok()
        .header(HttpHeaders.ETAG, "\"" + snapshot.version() + "\"")
        .body(body);
  }

  @GetMapping(value = "/policies/{name}", produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<?> getPolicy(@PathVariable("name") String name) {
    PolicySnapshot snapshot = policyRegistry.getSnapshot();
    PolicyConfig config = snapshot.rawConfigs().get(name);
    if (config == null) {
      return ResponseEntity.status(HttpStatus.NOT_FOUND)
          .contentType(MediaType.APPLICATION_PROBLEM_JSON)
          .body(
              createProblem(
                  "urn:trafficcontrol:problem:policy-not-found",
                  "Policy Not Found",
                  404,
                  "Policy '" + name + "' does not exist"));
    }

    return ResponseEntity.ok()
        .header(HttpHeaders.ETAG, "\"" + snapshot.version() + "\"")
        .body(config);
  }

  @PutMapping(
      value = "/policies/{name}",
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = {MediaType.APPLICATION_JSON_VALUE, MediaType.APPLICATION_PROBLEM_JSON_VALUE})
  public ResponseEntity<?> putPolicy(
      @PathVariable("name") String name,
      @RequestBody PolicyConfig config,
      @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
      Principal principal) {

    PolicySnapshot current = policyRegistry.getSnapshot();
    String actor = principal != null ? principal.getName() : "admin";

    // 1. Concurrency control: If-Match check
    if (ifMatch == null || ifMatch.isBlank()) {
      return ResponseEntity.status(428)
          .contentType(MediaType.APPLICATION_PROBLEM_JSON)
          .body(
              createProblem(
                  "urn:trafficcontrol:problem:precondition-required",
                  "Precondition Required",
                  428,
                  "Header If-Match is required"));
    }
    String cleanIfMatch = ifMatch.replace("\"", "").trim();
    if (!"*".equals(cleanIfMatch) && !String.valueOf(current.version()).equals(cleanIfMatch)) {
      return ResponseEntity.status(HttpStatus.PRECONDITION_FAILED)
          .contentType(MediaType.APPLICATION_PROBLEM_JSON)
          .body(
              createProblem(
                  "urn:trafficcontrol:problem:version-mismatch",
                  "Precondition Failed",
                  412,
                  "If-Match version mismatch. Current version: " + current.version()));
    }

    // 2. Validate policy via PolicyConfigValidator
    TrafficControlProperties validationProps = new TrafficControlProperties();
    Map<String, PolicyConfig> candidatePolicies = new LinkedHashMap<>(current.rawConfigs());
    boolean isNew = !candidatePolicies.containsKey(name);
    candidatePolicies.put(name, config);
    validationProps.setPolicies(candidatePolicies);

    ValidationResult validation = validator.validate(validationProps);
    if (validation.hasErrors()) {
      Map<String, Object> problem = new LinkedHashMap<>();
      problem.put("type", "urn:trafficcontrol:problem:invalid-policy");
      problem.put("title", "Invalid policy");
      problem.put("status", 422);
      List<Map<String, String>> errorList = new ArrayList<>();
      for (io.github.ezmanish.trafficcontrol.spring.validation.ValidationError err :
          validation.getErrors()) {
        errorList.add(Map.of("code", err.code(), "path", err.path(), "message", err.message()));
      }
      problem.put("errors", errorList);
      return ResponseEntity.status(422)
          .contentType(MediaType.APPLICATION_PROBLEM_JSON)
          .body(problem);
    }

    // 3. Compile and atomically swap
    long nextVersion = current.version() + 1;
    PolicySnapshot nextSnapshot =
        policyRegistry.compileSnapshot(nextVersion, actor, candidatePolicies, current.pathRules());
    policyRegistry.updateSnapshot(nextSnapshot);

    // 4. Retain snapshot & record audit
    auditLog.retainSnapshot(nextSnapshot);
    String beforeJson = isNew ? null : serializeQuietly(current.rawConfigs().get(name));
    String afterJson = serializeQuietly(config);
    auditLog.record(
        new PolicyAuditEntry(
            UUID.randomUUID().toString(),
            nextVersion,
            Instant.now(),
            actor,
            isNew ? "CREATE" : "UPDATE",
            name,
            beforeJson,
            afterJson));

    // 5. Broadcast to Redis pub/sub if available
    broadcaster.ifPresent(
        b -> b.broadcastUpdate(nextSnapshot, serializeQuietly(candidatePolicies)));

    Map<String, Object> metadata = new LinkedHashMap<>();
    metadata.put("version", nextVersion);
    metadata.put("checksum", nextSnapshot.checksum());
    metadata.put("updatedAt", nextSnapshot.updatedAt().toString());
    metadata.put("updatedBy", actor);

    HttpStatus status = isNew ? HttpStatus.CREATED : HttpStatus.OK;
    return ResponseEntity.status(status)
        .header(HttpHeaders.ETAG, "\"" + nextVersion + "\"")
        .body(metadata);
  }

  @DeleteMapping(
      value = "/policies/{name}",
      produces = {MediaType.APPLICATION_JSON_VALUE, MediaType.APPLICATION_PROBLEM_JSON_VALUE})
  public ResponseEntity<?> deletePolicy(
      @PathVariable("name") String name,
      @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
      Principal principal) {

    PolicySnapshot current = policyRegistry.getSnapshot();
    String actor = principal != null ? principal.getName() : "admin";

    if (ifMatch == null || ifMatch.isBlank()) {
      return ResponseEntity.status(428)
          .contentType(MediaType.APPLICATION_PROBLEM_JSON)
          .body(
              createProblem(
                  "urn:trafficcontrol:problem:precondition-required",
                  "Precondition Required",
                  428,
                  "Header If-Match is required"));
    }
    String cleanIfMatch = ifMatch.replace("\"", "").trim();
    if (!"*".equals(cleanIfMatch) && !String.valueOf(current.version()).equals(cleanIfMatch)) {
      return ResponseEntity.status(HttpStatus.PRECONDITION_FAILED)
          .contentType(MediaType.APPLICATION_PROBLEM_JSON)
          .body(
              createProblem(
                  "urn:trafficcontrol:problem:version-mismatch",
                  "Precondition Failed",
                  412,
                  "If-Match version mismatch. Current version: " + current.version()));
    }

    if (!current.rawConfigs().containsKey(name)) {
      return ResponseEntity.status(HttpStatus.NOT_FOUND)
          .contentType(MediaType.APPLICATION_PROBLEM_JSON)
          .body(
              createProblem(
                  "urn:trafficcontrol:problem:policy-not-found",
                  "Policy Not Found",
                  404,
                  "Policy '" + name + "' does not exist"));
    }

    // Check if policy is in use by any path rule
    for (PathRuleConfig pr : current.pathRules()) {
      if (name.equals(pr.getPolicy())) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
            .contentType(MediaType.APPLICATION_PROBLEM_JSON)
            .body(
                createProblem(
                    "urn:trafficcontrol:problem:policy-in-use",
                    "Policy In Use",
                    409,
                    "Policy '" + name + "' is referenced by path rule: " + pr.getPath()));
      }
    }

    long nextVersion = current.version() + 1;
    Map<String, PolicyConfig> candidatePolicies = new LinkedHashMap<>(current.rawConfigs());
    PolicyConfig removedConfig = candidatePolicies.remove(name);

    PolicySnapshot nextSnapshot =
        policyRegistry.compileSnapshot(nextVersion, actor, candidatePolicies, current.pathRules());
    policyRegistry.updateSnapshot(nextSnapshot);

    auditLog.retainSnapshot(nextSnapshot);
    auditLog.record(
        new PolicyAuditEntry(
            UUID.randomUUID().toString(),
            nextVersion,
            Instant.now(),
            actor,
            "DELETE",
            name,
            serializeQuietly(removedConfig),
            null));

    broadcaster.ifPresent(
        b -> b.broadcastUpdate(nextSnapshot, serializeQuietly(candidatePolicies)));

    return ResponseEntity.noContent().build();
  }

  @PostMapping(
      value = "/rollback",
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = {MediaType.APPLICATION_JSON_VALUE, MediaType.APPLICATION_PROBLEM_JSON_VALUE})
  public ResponseEntity<?> rollback(
      @RequestBody Map<String, Object> request,
      @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
      Principal principal) {

    PolicySnapshot current = policyRegistry.getSnapshot();
    String actor = principal != null ? principal.getName() : "admin";

    if (ifMatch == null || ifMatch.isBlank()) {
      return ResponseEntity.status(428)
          .contentType(MediaType.APPLICATION_PROBLEM_JSON)
          .body(
              createProblem(
                  "urn:trafficcontrol:problem:precondition-required",
                  "Precondition Required",
                  428,
                  "Header If-Match is required"));
    }
    String cleanIfMatch = ifMatch.replace("\"", "").trim();
    if (!"*".equals(cleanIfMatch) && !String.valueOf(current.version()).equals(cleanIfMatch)) {
      return ResponseEntity.status(HttpStatus.PRECONDITION_FAILED)
          .contentType(MediaType.APPLICATION_PROBLEM_JSON)
          .body(
              createProblem(
                  "urn:trafficcontrol:problem:version-mismatch",
                  "Precondition Failed",
                  412,
                  "If-Match version mismatch. Current: " + current.version()));
    }

    Object targetVerObj = request.get("toVersion");
    if (targetVerObj == null) {
      return ResponseEntity.badRequest().body("toVersion is required");
    }
    long toVersion = Long.parseLong(targetVerObj.toString());

    Optional<PolicySnapshot> targetSnapshotOpt = auditLog.getRetainedSnapshot(toVersion);
    if (targetSnapshotOpt.isEmpty()) {
      return ResponseEntity.status(HttpStatus.NOT_FOUND)
          .contentType(MediaType.APPLICATION_PROBLEM_JSON)
          .body(
              createProblem(
                  "urn:trafficcontrol:problem:version-not-retained",
                  "Version Not Retained",
                  404,
                  "Version "
                      + toVersion
                      + " is no longer in retention history (last 20 retained)"));
    }

    PolicySnapshot target = targetSnapshotOpt.get();
    long nextVersion = current.version() + 1;
    PolicySnapshot nextSnapshot =
        policyRegistry.compileSnapshot(nextVersion, actor, target.rawConfigs(), target.pathRules());
    policyRegistry.updateSnapshot(nextSnapshot);

    auditLog.retainSnapshot(nextSnapshot);
    auditLog.record(
        new PolicyAuditEntry(
            UUID.randomUUID().toString(),
            nextVersion,
            Instant.now(),
            actor,
            "ROLLBACK",
            "all",
            "version:" + current.version(),
            "restored_from:" + toVersion));

    broadcaster.ifPresent(
        b -> b.broadcastUpdate(nextSnapshot, serializeQuietly(target.rawConfigs())));

    Map<String, Object> metadata = new LinkedHashMap<>();
    metadata.put("version", nextVersion);
    metadata.put("checksum", nextSnapshot.checksum());
    metadata.put("updatedAt", nextSnapshot.updatedAt().toString());
    metadata.put("updatedBy", actor);

    return ResponseEntity.ok().header(HttpHeaders.ETAG, "\"" + nextVersion + "\"").body(metadata);
  }

  @GetMapping(value = "/audit", produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<Map<String, Object>> getAudit(
      @RequestParam(value = "limit", defaultValue = "50") int limit) {
    List<PolicyAuditEntry> entries = auditLog.getEntries(limit);
    return ResponseEntity.ok(Map.of("entries", entries));
  }

  private Map<String, Object> createProblem(String type, String title, int status, String detail) {
    Map<String, Object> map = new LinkedHashMap<>();
    map.put("type", type);
    map.put("title", title);
    map.put("status", status);
    map.put("detail", detail);
    return map;
  }

  private String serializeQuietly(Object obj) {
    if (obj == null) return null;
    try {
      return objectMapper.writeValueAsString(obj);
    } catch (Exception e) {
      return String.valueOf(obj);
    }
  }
}
