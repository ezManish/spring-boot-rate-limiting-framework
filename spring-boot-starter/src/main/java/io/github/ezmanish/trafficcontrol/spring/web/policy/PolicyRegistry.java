package io.github.ezmanish.trafficcontrol.spring.web.policy;

import io.github.ezmanish.trafficcontrol.core.api.FailMode;
import io.github.ezmanish.trafficcontrol.spring.annotation.RateLimit;
import io.github.ezmanish.trafficcontrol.spring.annotation.RateLimitPolicy;
import io.github.ezmanish.trafficcontrol.spring.autoconfigure.TrafficControlProperties;
import io.github.ezmanish.trafficcontrol.spring.autoconfigure.TrafficControlProperties.PathRuleConfig;
import io.github.ezmanish.trafficcontrol.spring.autoconfigure.TrafficControlProperties.PolicyConfig;
import java.lang.reflect.Method;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.method.HandlerMethod;

/**
 * Registry holding compiled policies and matching incoming requests against annotations and path
 * rules. Uses AtomicReference<PolicySnapshot> for zero-downtime atomic hot swaps (TR-07,
 * TC-070..TC-074).
 */
public class PolicyRegistry {

  private static final Logger log = LoggerFactory.getLogger(PolicyRegistry.class);

  private final AtomicReference<PolicySnapshot> snapshotRef;
  private final Map<Method, CompiledPolicy> methodCache = new ConcurrentHashMap<>();
  private final Map<Class<?>, CompiledPolicy> classCache = new ConcurrentHashMap<>();
  private final FailMode globalFailMode;
  private final AntPathMatcher pathMatcher = new AntPathMatcher();

  public PolicyRegistry(TrafficControlProperties properties) {
    this.globalFailMode =
        properties.getFailMode() != null ? properties.getFailMode() : FailMode.FAIL_OPEN;
    Map<String, PolicyConfig> rawPolicies =
        properties.getPolicies() != null ? properties.getPolicies() : Map.of();
    List<PathRuleConfig> rules = properties.getRules() != null ? properties.getRules() : List.of();

    PolicySnapshot initial = compileSnapshot(1L, "startup", rawPolicies, rules);
    this.snapshotRef = new AtomicReference<>(initial);
  }

  public PolicySnapshot getSnapshot() {
    return snapshotRef.get();
  }

  /**
   * Atomically swaps the active snapshot if the new snapshot's version is strictly greater.
   * Monotonically increasing version check ensures stale or duplicate updates are ignored (TC-074).
   */
  public boolean updateSnapshot(PolicySnapshot newSnapshot) {
    Objects.requireNonNull(newSnapshot, "newSnapshot cannot be null");
    while (true) {
      PolicySnapshot current = snapshotRef.get();
      if (newSnapshot.version() <= current.version()) {
        log.warn(
            "Ignored policy update with version {} because current version is {}",
            newSnapshot.version(),
            current.version());
        return false;
      }

      if (snapshotRef.compareAndSet(current, newSnapshot)) {
        methodCache.clear();
        classCache.clear();
        log.info(
            "Successfully swapped policy snapshot to version {} (checksum={})",
            newSnapshot.version(),
            newSnapshot.checksum());
        return true;
      }
    }
  }

  public PolicySnapshot compileSnapshot(
      long version,
      String updatedBy,
      Map<String, PolicyConfig> policies,
      List<PathRuleConfig> pathRules) {
    Map<String, CompiledPolicy> compiled = new LinkedHashMap<>();
    if (policies != null) {
      for (Map.Entry<String, PolicyConfig> entry : policies.entrySet()) {
        CompiledPolicy cp =
            PolicyCompiler.compile(entry.getKey(), entry.getValue(), globalFailMode);
        compiled.put(entry.getKey(), cp);
      }
    }
    String checksum = PolicySnapshot.computeChecksum(compiled.keySet() + ":" + version);
    return new PolicySnapshot(
        version,
        checksum,
        Instant.now(),
        updatedBy != null ? updatedBy : "system",
        compiled,
        pathRules != null ? pathRules : List.of(),
        policies != null ? policies : Map.of());
  }

  public Optional<CompiledPolicy> resolvePolicy(
      HandlerMethod handlerMethod, String requestUri, String httpMethod) {
    PolicySnapshot snapshot = snapshotRef.get();
    Method method = handlerMethod.getMethod();
    Class<?> beanType = handlerMethod.getBeanType();

    // 1. Method-level annotations (precedence 1)
    if (method.isAnnotationPresent(RateLimitPolicy.class)) {
      String policyName = method.getAnnotation(RateLimitPolicy.class).value();
      CompiledPolicy cp = snapshot.namedPolicies().get(policyName);
      if (cp != null) {
        return Optional.of(cp);
      }
      throw new IllegalArgumentException(
          "Unknown policy '" + policyName + "' referenced by @RateLimitPolicy on " + method);
    }

    if (method.isAnnotationPresent(RateLimit.class)) {
      CompiledPolicy cp =
          methodCache.computeIfAbsent(
              method,
              m -> {
                String name = "method:" + m.getDeclaringClass().getSimpleName() + "#" + m.getName();
                return PolicyCompiler.compileFromAnnotation(
                    name, m.getAnnotation(RateLimit.class), globalFailMode);
              });
      return Optional.of(cp);
    }

    // 2. Class-level annotations (precedence 2)
    if (beanType.isAnnotationPresent(RateLimitPolicy.class)) {
      String policyName = beanType.getAnnotation(RateLimitPolicy.class).value();
      CompiledPolicy cp = snapshot.namedPolicies().get(policyName);
      if (cp != null) {
        return Optional.of(cp);
      }
      throw new IllegalArgumentException(
          "Unknown policy '"
              + policyName
              + "' referenced by @RateLimitPolicy on class "
              + beanType.getName());
    }

    if (beanType.isAnnotationPresent(RateLimit.class)) {
      CompiledPolicy cp =
          classCache.computeIfAbsent(
              beanType,
              c -> {
                String name = "class:" + c.getSimpleName();
                return PolicyCompiler.compileFromAnnotation(
                    name, c.getAnnotation(RateLimit.class), globalFailMode);
              });
      return Optional.of(cp);
    }

    // 3. Path rules (precedence 3 - most specific pattern)
    Optional<String> matchedPolicy = matchPathRule(snapshot, requestUri, httpMethod);
    if (matchedPolicy.isPresent()) {
      return Optional.ofNullable(snapshot.namedPolicies().get(matchedPolicy.get()));
    }

    return Optional.empty();
  }

  public Optional<CompiledPolicy> getNamedPolicy(String name) {
    return Optional.ofNullable(snapshotRef.get().namedPolicies().get(name));
  }

  public boolean hasPolicy(String name) {
    return snapshotRef.get().namedPolicies().containsKey(name);
  }

  public Set<String> getPolicyNames() {
    return Collections.unmodifiableSet(snapshotRef.get().namedPolicies().keySet());
  }

  private Optional<String> matchPathRule(
      PolicySnapshot snapshot, String requestUri, String httpMethod) {
    List<PathRuleConfig> matchingRules = new ArrayList<>();
    for (PathRuleConfig pr : snapshot.pathRules()) {
      if (pr.getMethod() != null
          && !pr.getMethod().equalsIgnoreCase(httpMethod)
          && !pr.getMethod().equals("*")) {
        continue;
      }
      if (pathMatcher.match(pr.getPath(), requestUri)) {
        matchingRules.add(pr);
      }
    }

    if (matchingRules.isEmpty()) {
      return Optional.empty();
    }

    if (matchingRules.size() == 1) {
      return Optional.of(matchingRules.get(0).getPolicy());
    }

    // Sort by specificity
    matchingRules.sort(
        (r1, r2) ->
            pathMatcher.getPatternComparator(requestUri).compare(r1.getPath(), r2.getPath()));
    return Optional.of(matchingRules.get(0).getPolicy());
  }
}
