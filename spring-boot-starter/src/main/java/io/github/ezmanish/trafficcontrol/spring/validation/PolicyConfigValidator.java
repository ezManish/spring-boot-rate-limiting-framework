package io.github.ezmanish.trafficcontrol.spring.validation;

import io.github.ezmanish.trafficcontrol.core.api.Algorithm;
import io.github.ezmanish.trafficcontrol.core.api.DurationParser;
import io.github.ezmanish.trafficcontrol.core.api.RateLimitKey;
import io.github.ezmanish.trafficcontrol.spring.autoconfigure.TrafficControlProperties;
import io.github.ezmanish.trafficcontrol.spring.autoconfigure.TrafficControlProperties.*;
import io.github.ezmanish.trafficcontrol.spring.web.proxy.CidrMatcher;
import java.time.Duration;
import java.util.*;
import java.util.regex.Pattern;

/** Validates TrafficControl configuration properties and policies against rules V-001..V-030. */
public class PolicyConfigValidator {

  private static final Pattern POLICY_NAME_PATTERN = Pattern.compile("^[a-z][a-z0-9-]{1,62}$");
  private static final Pattern RULE_ID_PATTERN = Pattern.compile("^[a-z0-9-]{1,32}$");
  private static final Pattern WINDOW_PATTERN = Pattern.compile("^\\d+(ms|s|m|h|d)$");
  private static final long MAX_ARITHMETIC_LIMIT = 4_000_000_000_000_000L; // 4 * 10^15
  private static final Set<RateLimitKey> VALID_COMPOSITE_COMPONENTS =
      Set.of(
          RateLimitKey.USER,
          RateLimitKey.IP,
          RateLimitKey.API_KEY,
          RateLimitKey.JWT_CLAIM,
          RateLimitKey.TENANT,
          RateLimitKey.ENDPOINT);

  public ValidationResult validate(TrafficControlProperties props) {
    return validate(props, false);
  }

  public ValidationResult validate(TrafficControlProperties props, boolean hasAdminSecurity) {
    ValidationResult result = new ValidationResult();
    if (props == null || !props.isEnabled()) {
      return result;
    }

    // V-027: admin.enabled requires a security filter chain on the admin path
    if (props.getAdmin() != null && props.getAdmin().isEnabled()) {
      if (!hasAdminSecurity) {
        result.addError(
            "V-027",
            "trafficcontrol.admin.enabled",
            "admin.enabled requires a SecurityFilterChain protecting the admin base path");
      }
    }

    // V-015: fail-closed-status
    int status = props.getFailClosedStatus();
    if (status != 429 && status != 503) {
      result.addError(
          "V-015",
          "trafficcontrol.fail-closed-status",
          "fail-closed-status must be 429 or 503, got: " + status);
    }

    // V-022: store: redis requires redis.url
    if ("redis".equalsIgnoreCase(props.getStore())) {
      if (props.getRedis() == null
          || props.getRedis().getUrl() == null
          || props.getRedis().getUrl().isBlank()) {
        result.addError(
            "V-022",
            "trafficcontrol.redis.url",
            "'store: redis' requires 'trafficcontrol.redis.url' to be configured");
      }
    }

    // V-025: trusted-proxies entries are valid CIDRs
    if (props.getTrustedProxies() != null) {
      for (int i = 0; i < props.getTrustedProxies().size(); i++) {
        String cidr = props.getTrustedProxies().get(i);
        if (!CidrMatcher.isValidCidr(cidr)) {
          result.addError(
              "V-025",
              "trafficcontrol.trusted-proxies[" + i + "]",
              "'" + cidr + "' is not a valid CIDR notation");
        }
      }
    }

    // V-026: headers.style
    if (props.getHeaders() != null && props.getHeaders().getStyle() != null) {
      String style = props.getHeaders().getStyle().toUpperCase();
      if (!Set.of("LEGACY", "IETF", "BOTH").contains(style)) {
        result.addError(
            "V-026",
            "trafficcontrol.headers.style",
            "headers.style must be LEGACY, IETF, or BOTH, got: " + style);
      }
    }

    // Validate policies
    Map<String, PolicyConfig> policies = props.getPolicies();
    if (policies != null) {
      for (Map.Entry<String, PolicyConfig> entry : policies.entrySet()) {
        String policyName = entry.getKey();
        PolicyConfig policy = entry.getValue();
        validatePolicy(policyName, policy, result);
      }
    }

    // Validate path rules (V-018)
    validatePathRules(props.getRules(), policies, result);

    return result;
  }

  private void validatePolicy(String name, PolicyConfig policy, ValidationResult result) {
    String path = "policies." + name;

    // V-001: policy name
    if (name == null || !POLICY_NAME_PATTERN.matcher(name).matches()) {
      result.addError(
          "V-001", path, "Policy name '" + name + "' must match ^[a-z][a-z0-9-]{1,62}$");
    }

    // V-002: at least one rate rule or concurrency block; at most 8 rules
    List<RuleConfig> rules = policy.getRules() != null ? policy.getRules() : List.of();
    boolean hasConcurrency =
        policy.getConcurrency() != null && policy.getConcurrency().getMax() > 0;
    if (rules.isEmpty() && !hasConcurrency) {
      result.addError(
          "V-002", path, "Policy must define at least one rate rule or a concurrency block");
    }
    if (rules.size() > 8) {
      result.addError(
          "V-002",
          path + ".rules",
          "Policy cannot define more than 8 rate rules (got " + rules.size() + ")");
    }

    // V-016: on-missing-key
    if (policy.getOnMissingKey() == null) {
      result.addError(
          "V-016",
          path + ".on-missing-key",
          "on-missing-key must be a known value: FALLBACK_IP, ANONYMOUS, SKIP, or REJECT");
    }

    // V-014, V-029, V-030: Key validation
    RateLimitKey key = policy.getKey() != null ? policy.getKey() : RateLimitKey.USER;
    if (key == RateLimitKey.CUSTOM) {
      if (policy.getKeyResolver() == null || policy.getKeyResolver().isBlank()) {
        result.addError(
            "V-014", path + ".key-resolver", "key 'CUSTOM' requires a bean name in 'key-resolver'");
      }
    } else if (key == RateLimitKey.API_KEY) {
      if (policy.getApiKeyHeader() == null || policy.getApiKeyHeader().isBlank()) {
        result.addError(
            "V-014", path + ".api-key-header", "key 'API_KEY' requires 'api-key-header'");
      }
    } else if (key == RateLimitKey.JWT_CLAIM) {
      if (policy.getClaim() == null || policy.getClaim().isBlank()) {
        result.addError("V-014", path + ".claim", "key 'JWT_CLAIM' requires 'claim'");
      }
    } else if (key == RateLimitKey.TENANT) {
      if (policy.getTenant() == null
          || policy.getTenant().getName() == null
          || policy.getTenant().getName().isBlank()) {
        result.addError("V-030", path + ".tenant.name", "key 'TENANT' requires 'tenant.name'");
      }
      if (policy.getTenant() != null && "HEADER".equalsIgnoreCase(policy.getTenant().getSource())) {
        result.addWarning(
            "V-030",
            path + ".tenant.source",
            "tenant source HEADER is client-controlled unless set by trusted gateway");
      }
    } else if (key == RateLimitKey.COMPOSITE) {
      List<RateLimitKey> comps = policy.getComponents();
      if (comps == null || comps.size() < 2 || comps.size() > 3) {
        result.addError(
            "V-029", path + ".components", "COMPOSITE requires 2 to 3 distinct components");
      } else {
        Set<RateLimitKey> unique = new HashSet<>(comps);
        if (unique.size() != comps.size()) {
          result.addError("V-029", path + ".components", "COMPOSITE components must be distinct");
        }
        for (RateLimitKey comp : comps) {
          if (!VALID_COMPOSITE_COMPONENTS.contains(comp)) {
            result.addError(
                "V-029", path + ".components", "Invalid component for COMPOSITE: " + comp);
          }
        }
      }
    }

    if (key != RateLimitKey.COMPOSITE
        && policy.getComponents() != null
        && !policy.getComponents().isEmpty()) {
      result.addError(
          "V-029", path + ".components", "'components' is invalid when key is not COMPOSITE");
    }

    // Validate individual rules
    Set<String> ruleIds = new HashSet<>();
    for (int i = 0; i < rules.size(); i++) {
      RuleConfig r = rules.get(i);
      String rulePath = path + ".rules[" + i + "]";
      validateRule(r, i, rulePath, policy.getAlgorithm(), ruleIds, result);
    }

    // V-028: identical rules warning
    for (int i = 0; i < rules.size(); i++) {
      for (int j = i + 1; j < rules.size(); j++) {
        RuleConfig r1 = rules.get(i);
        RuleConfig r2 = rules.get(j);
        if (Objects.equals(r1.getAlgorithm(), r2.getAlgorithm())
            && r1.getRequests() == r2.getRequests()
            && Objects.equals(r1.getWindow(), r2.getWindow())) {
          result.addWarning(
              "V-028",
              path,
              "Two identical rules in policy: '"
                  + (r1.getId() != null ? r1.getId() : "r" + (i + 1))
                  + "' and '"
                  + (r2.getId() != null ? r2.getId() : "r" + (j + 1))
                  + "'");
        }
      }
    }

    // V-017: plans validation
    if (policy.getPlans() != null) {
      for (Map.Entry<String, PlanConfig> pe : policy.getPlans().entrySet()) {
        String planPath = path + ".plans." + pe.getKey();
        PlanConfig p = pe.getValue();
        if (p.getRequests() < 1 || p.getRequests() > 1_000_000_000L) {
          result.addError(
              "V-017",
              planPath + ".requests",
              "Plan requests must be in 1..1,000,000,000, got: " + p.getRequests());
        }
        if (p.getWindow() == null || !WINDOW_PATTERN.matcher(p.getWindow()).matches()) {
          result.addError(
              "V-017",
              planPath + ".window",
              "'" + p.getWindow() + "' is not a valid duration format");
        } else {
          try {
            Duration w = DurationParser.parse(p.getWindow());
            if (w.toSeconds() < 1 || w.toSeconds() > 7 * 86400) {
              result.addError(
                  "V-017",
                  planPath + ".window",
                  "Plan window must be between 1s and 7d, got: " + p.getWindow());
            }
          } catch (Exception e) {
            result.addError(
                "V-017", planPath + ".window", "Failed to parse window: " + p.getWindow());
          }
        }
      }
    }

    // V-010, V-011, V-013: Concurrency validation
    if (policy.getConcurrency() != null) {
      ConcurrencyConfig c = policy.getConcurrency();
      String cPath = path + ".concurrency";
      if (c.getMax() < 1 || c.getMax() > 100_000) {
        result.addError(
            "V-010", cPath + ".max", "concurrency.max must be in 1..100,000, got: " + c.getMax());
      }
      if (c.getWait() != null) {
        try {
          Duration waitDur = DurationParser.parse(c.getWait());
          if (waitDur.toMillis() < 0 || waitDur.toSeconds() > 30) {
            result.addError(
                "V-013",
                cPath + ".wait",
                "concurrency.wait must be in 0..30s, got: " + c.getWait());
          }
        } catch (Exception e) {
          result.addError(
              "V-013",
              cPath + ".wait",
              "Invalid duration format for concurrency.wait: " + c.getWait());
        }
      }
      if (c.getLeaseTtl() != null) {
        try {
          Duration ttl = DurationParser.parse(c.getLeaseTtl());
          if (ttl.toSeconds() < 1 || ttl.toHours() > 1) {
            result.addError(
                "V-011",
                cPath + ".lease-ttl",
                "concurrency.lease-ttl must be between 1s and 1h, got: " + c.getLeaseTtl());
          }
        } catch (Exception e) {
          result.addError(
              "V-011",
              cPath + ".lease-ttl",
              "Invalid duration format for lease-ttl: " + c.getLeaseTtl());
        }
      }
    }

    // V-023: Adaptive validation
    if (policy.getAdaptive() != null && policy.getAdaptive().isEnabled()) {
      AdaptiveConfigProperties ad = policy.getAdaptive();
      String adPath = path + ".adaptive";
      if (ad.getMinMultiplier() <= 0.0 || ad.getMinMultiplier() >= 1.0) {
        result.addError(
            "V-023",
            adPath + ".min-multiplier",
            "adaptive.min-multiplier must be in (0, 1), got: " + ad.getMinMultiplier());
      }
      if (ad.getTargetP95() == null || ad.getTargetP95().isBlank()) {
        result.addError(
            "V-023",
            adPath + ".target-p95",
            "adaptive.target-p95 is required when adaptive is enabled");
      }
    }
  }

  private void validateRule(
      RuleConfig r,
      int index,
      String rulePath,
      Algorithm defaultAlgo,
      Set<String> ruleIds,
      ValidationResult result) {
    String id = r.getId() != null ? r.getId() : "r" + (index + 1);
    if (!RULE_ID_PATTERN.matcher(id).matches()) {
      result.addError(
          "V-003", rulePath + ".id", "Rule id '" + id + "' must match ^[a-z0-9-]{1,32}$");
    }
    if (!ruleIds.add(id)) {
      result.addError("V-003", rulePath + ".id", "Duplicate rule id '" + id + "' in policy");
    }

    // V-004: requests
    if (r.getRequests() < 1 || r.getRequests() > 1_000_000_000L) {
      result.addError(
          "V-004",
          rulePath + ".requests",
          "requests must be in 1..1,000,000,000, got: " + r.getRequests());
    }

    // V-005 & V-006: window
    Duration windowDur = null;
    if (r.getWindow() == null || !WINDOW_PATTERN.matcher(r.getWindow()).matches()) {
      result.addError(
          "V-005",
          rulePath + ".window",
          "'" + r.getWindow() + "' is not a valid duration format (use ms|s|m|h|d)");
    } else {
      try {
        windowDur = DurationParser.parse(r.getWindow());
        long winSec = windowDur.toSeconds();
        if (winSec < 1 || winSec > 7 * 86400) {
          result.addError(
              "V-006",
              rulePath + ".window",
              "window must be between 1s and 7d, got: " + r.getWindow());
        }
      } catch (Exception e) {
        result.addError("V-005", rulePath + ".window", "Failed to parse window: " + r.getWindow());
      }
    }

    Algorithm algo =
        r.getAlgorithm() != null
            ? r.getAlgorithm()
            : (defaultAlgo != null ? defaultAlgo : Algorithm.TOKEN_BUCKET);
    long burst = r.getBurst() > 0 ? r.getBurst() : r.getRequests();

    // V-007: burst only for TOKEN_BUCKET/GCRA
    if (r.getBurst() > 0 && algo != Algorithm.TOKEN_BUCKET && algo != Algorithm.GCRA) {
      result.addError(
          "V-007", rulePath + ".burst", "burst is only valid for TOKEN_BUCKET and GCRA algorithms");
    }

    if (windowDur != null) {
      long winMs = windowDur.toMillis();
      if (burst * winMs > MAX_ARITHMETIC_LIMIT || r.getRequests() * winMs > MAX_ARITHMETIC_LIMIT) {
        result.addError(
            "V-007", rulePath, "burst * window_ms exceeds 4x10^15 integer safety bound");
      }

      // V-008: GCRA ceil(window_us / requests) >= 1
      if (algo == Algorithm.GCRA) {
        long winUs = winMs * 1000L;
        if (r.getRequests() > winUs) {
          result.addError("V-008", rulePath, "GCRA emission interval T is less than 1us");
        }
      }
    }

    // V-009: SLIDING_WINDOW_LOG requests <= 10,000
    if (algo == Algorithm.SLIDING_WINDOW_LOG && r.getRequests() > 10_000) {
      result.addError(
          "V-009",
          rulePath + ".requests",
          "SLIDING_WINDOW_LOG requests must be <= 10,000 to bound memory");
    }
  }

  private void validatePathRules(
      List<PathRuleConfig> pathRules, Map<String, PolicyConfig> policies, ValidationResult result) {
    if (pathRules == null) {
      return;
    }
    Set<String> seen = new HashSet<>();
    for (int i = 0; i < pathRules.size(); i++) {
      PathRuleConfig pr = pathRules.get(i);
      String path = "rules[" + i + "]";

      if (pr.getPath() == null || pr.getPath().isBlank()) {
        result.addError("V-018", path + ".path", "Path pattern cannot be empty");
      }

      String method = pr.getMethod() != null ? pr.getMethod().toUpperCase() : "*";
      String key = method + ":" + pr.getPath();
      if (!seen.add(key)) {
        result.addError(
            "V-018",
            path,
            "Duplicate path rule for method " + method + " and path " + pr.getPath());
      }

      if (pr.getPolicy() == null || policies == null || !policies.containsKey(pr.getPolicy())) {
        result.addError(
            "V-018",
            path + ".policy",
            "Path rule references unknown policy '" + pr.getPolicy() + "'");
      }
    }
  }

  public void validateClass(
      Class<?> clazz, Map<String, PolicyConfig> policies, ValidationResult result) {
    if (clazz == null) {
      return;
    }
    boolean hasLimit =
        clazz.isAnnotationPresent(
            io.github.ezmanish.trafficcontrol.spring.annotation.RateLimit.class);
    boolean hasPolicy =
        clazz.isAnnotationPresent(
            io.github.ezmanish.trafficcontrol.spring.annotation.RateLimitPolicy.class);

    // V-021: @RateLimit and @RateLimitPolicy on the same element
    if (hasLimit && hasPolicy) {
      result.addError(
          "V-021",
          clazz.getName(),
          "@RateLimit and @RateLimitPolicy cannot both be present on the same element");
    }

    // V-020: named policy exists
    if (hasPolicy) {
      String policyName =
          clazz
              .getAnnotation(
                  io.github.ezmanish.trafficcontrol.spring.annotation.RateLimitPolicy.class)
              .value();
      if (policies == null || !policies.containsKey(policyName)) {
        String suggestion =
            findClosestMatch(policyName, policies != null ? policies.keySet() : Set.of());
        String hint = suggestion != null ? " (did you mean '" + suggestion + "'?)" : "";
        result.addError(
            "V-020",
            "@RateLimitPolicy(\"" + policyName + "\") on " + clazz.getSimpleName(),
            "unknown policy" + hint);
      }
    }
  }

  public void validateMethod(
      java.lang.reflect.Method method,
      Class<?> targetClass,
      Map<String, PolicyConfig> policies,
      ValidationResult result) {
    if (method == null) {
      return;
    }
    boolean hasLimit =
        method.isAnnotationPresent(
            io.github.ezmanish.trafficcontrol.spring.annotation.RateLimit.class);
    boolean hasPolicy =
        method.isAnnotationPresent(
            io.github.ezmanish.trafficcontrol.spring.annotation.RateLimitPolicy.class);

    // V-021: @RateLimit and @RateLimitPolicy on the same element
    if (hasLimit && hasPolicy) {
      result.addError(
          "V-021",
          targetClass.getSimpleName() + "#" + method.getName(),
          "@RateLimit and @RateLimitPolicy cannot both be present on the same element");
    }

    // V-020: named policy exists
    if (hasPolicy) {
      String policyName =
          method
              .getAnnotation(
                  io.github.ezmanish.trafficcontrol.spring.annotation.RateLimitPolicy.class)
              .value();
      if (policies == null || !policies.containsKey(policyName)) {
        String suggestion =
            findClosestMatch(policyName, policies != null ? policies.keySet() : Set.of());
        String hint = suggestion != null ? " (did you mean '" + suggestion + "'?)" : "";
        result.addError(
            "V-020",
            "@RateLimitPolicy(\""
                + policyName
                + "\") on "
                + targetClass.getSimpleName()
                + "#"
                + method.getName(),
            "unknown policy" + hint);
      }
    }
  }

  private String findClosestMatch(String input, Set<String> candidates) {
    if (input == null || candidates == null || candidates.isEmpty()) {
      return null;
    }
    String bestMatch = null;
    int minDistance = Integer.MAX_VALUE;
    for (String candidate : candidates) {
      int dist = levenshteinDistance(input.toLowerCase(), candidate.toLowerCase());
      if (dist < minDistance && dist <= 3) {
        minDistance = dist;
        bestMatch = candidate;
      }
    }
    return bestMatch;
  }

  private int levenshteinDistance(String s1, String s2) {
    int[] prev = new int[s2.length() + 1];
    for (int j = 0; j <= s2.length(); j++) {
      prev[j] = j;
    }
    for (int i = 1; i <= s1.length(); i++) {
      int[] curr = new int[s2.length() + 1];
      curr[0] = i;
      for (int j = 1; j <= s2.length(); j++) {
        int cost = (s1.charAt(i - 1) == s2.charAt(j - 1)) ? 0 : 1;
        curr[j] = Math.min(Math.min(curr[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
      }
      prev = curr;
    }
    return prev[s2.length()];
  }
}
