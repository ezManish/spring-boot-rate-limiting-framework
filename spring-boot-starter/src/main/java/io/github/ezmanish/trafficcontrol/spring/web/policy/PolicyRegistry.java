package io.github.ezmanish.trafficcontrol.spring.web.policy;

import io.github.ezmanish.trafficcontrol.core.api.FailMode;
import io.github.ezmanish.trafficcontrol.spring.annotation.RateLimit;
import io.github.ezmanish.trafficcontrol.spring.annotation.RateLimitPolicy;
import io.github.ezmanish.trafficcontrol.spring.autoconfigure.TrafficControlProperties;
import io.github.ezmanish.trafficcontrol.spring.autoconfigure.TrafficControlProperties.PathRuleConfig;
import io.github.ezmanish.trafficcontrol.spring.autoconfigure.TrafficControlProperties.PolicyConfig;
import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.method.HandlerMethod;

/**
 * Registry holding compiled policies and matching incoming requests against annotations and path
 * rules.
 */
public class PolicyRegistry {

  private final Map<String, CompiledPolicy> namedPolicies = new ConcurrentHashMap<>();
  private final Map<Method, CompiledPolicy> methodCache = new ConcurrentHashMap<>();
  private final Map<Class<?>, CompiledPolicy> classCache = new ConcurrentHashMap<>();
  private final List<PathRuleConfig> pathRules;
  private final FailMode globalFailMode;
  private final AntPathMatcher pathMatcher = new AntPathMatcher();

  public PolicyRegistry(TrafficControlProperties properties) {
    this.globalFailMode =
        properties.getFailMode() != null ? properties.getFailMode() : FailMode.FAIL_OPEN;
    this.pathRules = properties.getRules() != null ? properties.getRules() : List.of();

    if (properties.getPolicies() != null) {
      for (Map.Entry<String, PolicyConfig> entry : properties.getPolicies().entrySet()) {
        CompiledPolicy compiled =
            PolicyCompiler.compile(entry.getKey(), entry.getValue(), globalFailMode);
        namedPolicies.put(entry.getKey(), compiled);
      }
    }
  }

  public Optional<CompiledPolicy> resolvePolicy(
      HandlerMethod handlerMethod, String requestUri, String httpMethod) {
    Method method = handlerMethod.getMethod();
    Class<?> beanType = handlerMethod.getBeanType();

    // 1. Method-level annotations (precedence 1)
    if (method.isAnnotationPresent(RateLimitPolicy.class)) {
      String policyName = method.getAnnotation(RateLimitPolicy.class).value();
      CompiledPolicy cp = namedPolicies.get(policyName);
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
      CompiledPolicy cp = namedPolicies.get(policyName);
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
    Optional<String> matchedPolicy = matchPathRule(requestUri, httpMethod);
    if (matchedPolicy.isPresent()) {
      return Optional.ofNullable(namedPolicies.get(matchedPolicy.get()));
    }

    return Optional.empty();
  }

  public Optional<CompiledPolicy> getNamedPolicy(String name) {
    return Optional.ofNullable(namedPolicies.get(name));
  }

  public boolean hasPolicy(String name) {
    return namedPolicies.containsKey(name);
  }

  public Set<String> getPolicyNames() {
    return Collections.unmodifiableSet(namedPolicies.keySet());
  }

  private Optional<String> matchPathRule(String requestUri, String httpMethod) {
    List<PathRuleConfig> matchingRules = new ArrayList<>();
    for (PathRuleConfig pr : pathRules) {
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
