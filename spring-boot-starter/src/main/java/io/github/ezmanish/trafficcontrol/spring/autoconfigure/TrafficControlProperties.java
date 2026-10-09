package io.github.ezmanish.trafficcontrol.spring.autoconfigure;

import io.github.ezmanish.trafficcontrol.core.api.Algorithm;
import io.github.ezmanish.trafficcontrol.core.api.FailMode;
import io.github.ezmanish.trafficcontrol.core.api.RateLimitKey;
import io.github.ezmanish.trafficcontrol.spring.web.resolver.OnMissingKeyStrategy;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Configuration properties for TrafficControl framework (prefix: trafficcontrol). */
@ConfigurationProperties(prefix = "trafficcontrol")
public class TrafficControlProperties {

  private boolean enabled = true;
  private String store = "local"; // local | redis
  private FailMode failMode = FailMode.FAIL_OPEN;
  private int failClosedStatus = 429;
  private List<String> trustedProxies = new ArrayList<>();
  private HeadersConfig headers = new HeadersConfig();
  private RedisConfig redis = new RedisConfig();
  private AdminConfig admin = new AdminConfig();
  private Map<String, PolicyConfig> policies = new LinkedHashMap<>();
  private List<PathRuleConfig> rules = new ArrayList<>();
  private ProblemConfig problem = new ProblemConfig();

  public boolean isEnabled() {
    return enabled;
  }

  public void setEnabled(boolean enabled) {
    this.enabled = enabled;
  }

  public String getStore() {
    return store;
  }

  public void setStore(String store) {
    this.store = store;
  }

  public FailMode getFailMode() {
    return failMode;
  }

  public void setFailMode(FailMode failMode) {
    this.failMode = failMode;
  }

  public int getFailClosedStatus() {
    return failClosedStatus;
  }

  public void setFailClosedStatus(int failClosedStatus) {
    this.failClosedStatus = failClosedStatus;
  }

  public List<String> getTrustedProxies() {
    return trustedProxies;
  }

  public void setTrustedProxies(List<String> trustedProxies) {
    this.trustedProxies = trustedProxies;
  }

  public HeadersConfig getHeaders() {
    return headers;
  }

  public void setHeaders(HeadersConfig headers) {
    this.headers = headers;
  }

  public RedisConfig getRedis() {
    return redis;
  }

  public void setRedis(RedisConfig redis) {
    this.redis = redis;
  }

  public AdminConfig getAdmin() {
    return admin;
  }

  public void setAdmin(AdminConfig admin) {
    this.admin = admin;
  }

  public Map<String, PolicyConfig> getPolicies() {
    return policies;
  }

  public void setPolicies(Map<String, PolicyConfig> policies) {
    this.policies = policies;
  }

  public List<PathRuleConfig> getRules() {
    return rules;
  }

  public void setRules(List<PathRuleConfig> rules) {
    this.rules = rules;
  }

  public ProblemConfig getProblem() {
    return problem;
  }

  public void setProblem(ProblemConfig problem) {
    this.problem = problem;
  }

  public static class HeadersConfig {
    private boolean enabled = true;
    private String style = "LEGACY"; // LEGACY | IETF | BOTH

    public boolean isEnabled() {
      return enabled;
    }

    public void setEnabled(boolean enabled) {
      this.enabled = enabled;
    }

    public String getStyle() {
      return style;
    }

    public void setStyle(String style) {
      this.style = style;
    }
  }

  public static class RedisConfig {
    private String url;

    public String getUrl() {
      return url;
    }

    public void setUrl(String url) {
      this.url = url;
    }
  }

  public static class AdminConfig {
    private boolean enabled = false;
    private String basePath = "/trafficcontrol/admin";
    private String role = "TRAFFICCONTROL_ADMIN";

    public boolean isEnabled() {
      return enabled;
    }

    public void setEnabled(boolean enabled) {
      this.enabled = enabled;
    }

    public String getBasePath() {
      return basePath;
    }

    public void setBasePath(String basePath) {
      this.basePath = basePath;
    }

    public String getRole() {
      return role;
    }

    public void setRole(String role) {
      this.role = role;
    }
  }

  public static class PolicyConfig {
    private RateLimitKey key = RateLimitKey.USER;
    private List<RateLimitKey> components = new ArrayList<>();
    private String apiKeyHeader = "X-API-Key";
    private String claim = "sub";
    private TenantConfig tenant = new TenantConfig();
    private String keyResolver;
    private String planResolver;
    private Algorithm algorithm = Algorithm.TOKEN_BUCKET;
    private FailMode failMode;
    private OnMissingKeyStrategy onMissingKey = OnMissingKeyStrategy.FALLBACK_IP;
    private List<RuleConfig> rules = new ArrayList<>();
    private ConcurrencyConfig concurrency;
    private Map<String, PlanConfig> plans = new LinkedHashMap<>();
    private AdaptiveConfigProperties adaptive = new AdaptiveConfigProperties();

    public RateLimitKey getKey() {
      return key;
    }

    public void setKey(RateLimitKey key) {
      this.key = key;
    }

    public List<RateLimitKey> getComponents() {
      return components;
    }

    public void setComponents(List<RateLimitKey> components) {
      this.components = components;
    }

    public String getApiKeyHeader() {
      return apiKeyHeader;
    }

    public void setApiKeyHeader(String apiKeyHeader) {
      this.apiKeyHeader = apiKeyHeader;
    }

    public String getClaim() {
      return claim;
    }

    public void setClaim(String claim) {
      this.claim = claim;
    }

    public TenantConfig getTenant() {
      return tenant;
    }

    public void setTenant(TenantConfig tenant) {
      this.tenant = tenant;
    }

    public String getKeyResolver() {
      return keyResolver;
    }

    public void setKeyResolver(String keyResolver) {
      this.keyResolver = keyResolver;
    }

    public String getPlanResolver() {
      return planResolver;
    }

    public void setPlanResolver(String planResolver) {
      this.planResolver = planResolver;
    }

    public Algorithm getAlgorithm() {
      return algorithm;
    }

    public void setAlgorithm(Algorithm algorithm) {
      this.algorithm = algorithm;
    }

    public FailMode getFailMode() {
      return failMode;
    }

    public void setFailMode(FailMode failMode) {
      this.failMode = failMode;
    }

    public OnMissingKeyStrategy getOnMissingKey() {
      return onMissingKey;
    }

    public void setOnMissingKey(OnMissingKeyStrategy onMissingKey) {
      this.onMissingKey = onMissingKey;
    }

    public List<RuleConfig> getRules() {
      return rules;
    }

    public void setRules(List<RuleConfig> rules) {
      this.rules = rules;
    }

    public ConcurrencyConfig getConcurrency() {
      return concurrency;
    }

    public void setConcurrency(ConcurrencyConfig concurrency) {
      this.concurrency = concurrency;
    }

    public Map<String, PlanConfig> getPlans() {
      return plans;
    }

    public void setPlans(Map<String, PlanConfig> plans) {
      this.plans = plans;
    }

    public AdaptiveConfigProperties getAdaptive() {
      return adaptive;
    }

    public void setAdaptive(AdaptiveConfigProperties adaptive) {
      this.adaptive = adaptive;
    }
  }

  public static class TenantConfig {
    private String source = "CLAIM"; // CLAIM | HEADER
    private String name = "tenant_id";

    public String getSource() {
      return source;
    }

    public void setSource(String source) {
      this.source = source;
    }

    public String getName() {
      return name;
    }

    public void setName(String name) {
      this.name = name;
    }
  }

  public static class RuleConfig {
    private String id;
    private Algorithm algorithm;
    private long requests;
    private String window;
    private long burst;

    public String getId() {
      return id;
    }

    public void setId(String id) {
      this.id = id;
    }

    public Algorithm getAlgorithm() {
      return algorithm;
    }

    public void setAlgorithm(Algorithm algorithm) {
      this.algorithm = algorithm;
    }

    public long getRequests() {
      return requests;
    }

    public void setRequests(long requests) {
      this.requests = requests;
    }

    public String getWindow() {
      return window;
    }

    public void setWindow(String window) {
      this.window = window;
    }

    public long getBurst() {
      return burst;
    }

    public void setBurst(long burst) {
      this.burst = burst;
    }
  }

  public static class ConcurrencyConfig {
    private int max;
    private String wait = "0ms";
    private String leaseTtl = "30s";

    public int getMax() {
      return max;
    }

    public void setMax(int max) {
      this.max = max;
    }

    public String getWait() {
      return wait;
    }

    public void setWait(String wait) {
      this.wait = wait;
    }

    public String getLeaseTtl() {
      return leaseTtl;
    }

    public void setLeaseTtl(String leaseTtl) {
      this.leaseTtl = leaseTtl;
    }
  }

  public static class PlanConfig {
    private long requests;
    private String window;
    private long burst;
    private Algorithm algorithm;

    public long getRequests() {
      return requests;
    }

    public void setRequests(long requests) {
      this.requests = requests;
    }

    public String getWindow() {
      return window;
    }

    public void setWindow(String window) {
      this.window = window;
    }

    public long getBurst() {
      return burst;
    }

    public void setBurst(long burst) {
      this.burst = burst;
    }

    public Algorithm getAlgorithm() {
      return algorithm;
    }

    public void setAlgorithm(Algorithm algorithm) {
      this.algorithm = algorithm;
    }
  }

  public static class AdaptiveConfigProperties {
    private boolean enabled = false;
    private double minMultiplier = 0.25;
    private String targetP95 = "200ms";

    public boolean isEnabled() {
      return enabled;
    }

    public void setEnabled(boolean enabled) {
      this.enabled = enabled;
    }

    public double getMinMultiplier() {
      return minMultiplier;
    }

    public void setMinMultiplier(double minMultiplier) {
      this.minMultiplier = minMultiplier;
    }

    public String getTargetP95() {
      return targetP95;
    }

    public void setTargetP95(String targetP95) {
      this.targetP95 = targetP95;
    }
  }

  public static class PathRuleConfig {
    private String path;
    private String method;
    private String policy;

    public String getPath() {
      return path;
    }

    public void setPath(String path) {
      this.path = path;
    }

    public String getMethod() {
      return method;
    }

    public void setMethod(String method) {
      this.method = method;
    }

    public String getPolicy() {
      return policy;
    }

    public void setPolicy(String policy) {
      this.policy = policy;
    }
  }

  public static class ProblemConfig {
    private String baseUri = "urn:trafficcontrol:problem:";
    private boolean exposeDetails = false;
    private boolean includeInstance = false;

    public String getBaseUri() {
      return baseUri;
    }

    public void setBaseUri(String baseUri) {
      this.baseUri = baseUri;
    }

    public boolean isExposeDetails() {
      return exposeDetails;
    }

    public void setExposeDetails(boolean exposeDetails) {
      this.exposeDetails = exposeDetails;
    }

    public boolean isIncludeInstance() {
      return includeInstance;
    }

    public void setIncludeInstance(boolean includeInstance) {
      this.includeInstance = includeInstance;
    }
  }
}
