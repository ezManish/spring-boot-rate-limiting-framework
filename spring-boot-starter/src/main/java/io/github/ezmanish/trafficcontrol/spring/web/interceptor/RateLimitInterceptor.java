package io.github.ezmanish.trafficcontrol.spring.web.interceptor;

import io.github.ezmanish.trafficcontrol.core.api.Decision;
import io.github.ezmanish.trafficcontrol.core.api.Policy;
import io.github.ezmanish.trafficcontrol.core.api.RateLimitEngine;
import io.github.ezmanish.trafficcontrol.core.api.RequestContext;
import io.github.ezmanish.trafficcontrol.core.spi.PlanResolver;
import io.github.ezmanish.trafficcontrol.core.spi.RateLimitContext;
import io.github.ezmanish.trafficcontrol.core.spi.RateLimitStore;
import io.github.ezmanish.trafficcontrol.spring.autoconfigure.TrafficControlProperties;
import io.github.ezmanish.trafficcontrol.spring.web.policy.CompiledPolicy;
import io.github.ezmanish.trafficcontrol.spring.web.policy.PolicyRegistry;
import io.github.ezmanish.trafficcontrol.spring.web.proxy.ClientIpResolver;
import io.github.ezmanish.trafficcontrol.spring.web.resolver.CompositeKeyResolverService;
import io.github.ezmanish.trafficcontrol.spring.web.resolver.MissingIdentityException;
import io.github.ezmanish.trafficcontrol.spring.web.response.ProblemDetailsResponseWriter;
import io.github.ezmanish.trafficcontrol.spring.web.response.RateLimitHeaderWriter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.*;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.AsyncHandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;

/**
 * Spring MVC HandlerInterceptor that intercepts controller requests, evaluates rate limit policies,
 * sets response headers, enforces concurrency limits, and writes RFC 9457 Problem Details on
 * rejection.
 */
public class RateLimitInterceptor implements AsyncHandlerInterceptor {

  public static final String PERMIT_ID_ATTR = "trafficcontrol.permit.id";
  public static final String PERMIT_KEY_ATTR = "trafficcontrol.permit.key";
  public static final String PERMIT_POLICY_ATTR = "trafficcontrol.permit.policy";

  private final TrafficControlProperties properties;
  private final PolicyRegistry policyRegistry;
  private final RateLimitEngine rateLimitEngine;
  private final RateLimitStore rateLimitStore;
  private final ClientIpResolver clientIpResolver;
  private final CompositeKeyResolverService keyResolverService;
  private final RateLimitHeaderWriter headerWriter;
  private final ProblemDetailsResponseWriter problemWriter;
  private final Map<String, PlanResolver> planResolvers;

  public RateLimitInterceptor(
      TrafficControlProperties properties,
      PolicyRegistry policyRegistry,
      RateLimitEngine rateLimitEngine,
      RateLimitStore rateLimitStore,
      ClientIpResolver clientIpResolver,
      CompositeKeyResolverService keyResolverService,
      RateLimitHeaderWriter headerWriter,
      ProblemDetailsResponseWriter problemWriter,
      Map<String, PlanResolver> planResolvers) {
    this.properties = properties;
    this.policyRegistry = policyRegistry;
    this.rateLimitEngine = rateLimitEngine;
    this.rateLimitStore = rateLimitStore;
    this.clientIpResolver = clientIpResolver;
    this.keyResolverService = keyResolverService;
    this.headerWriter = headerWriter;
    this.problemWriter = problemWriter;
    this.planResolvers = planResolvers != null ? planResolvers : Map.of();
  }

  @Override
  public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
      throws Exception {
    if (!properties.isEnabled()) {
      return true;
    }

    if (!(handler instanceof HandlerMethod handlerMethod)) {
      return true;
    }

    String requestUri = request.getRequestURI();
    String httpMethod = request.getMethod();

    Optional<CompiledPolicy> policyOpt =
        policyRegistry.resolvePolicy(handlerMethod, requestUri, httpMethod);
    if (policyOpt.isEmpty()) {
      return true;
    }

    CompiledPolicy compiled = policyOpt.get();
    Policy corePolicy = compiled.policy();

    // Build neutral RateLimitContext
    RateLimitContext rlContext = buildRateLimitContext(request);

    // Resolve subscription plan
    String plan = resolvePlan(compiled, rlContext);

    // Resolve client key
    Optional<String> clientKeyOpt;
    try {
      clientKeyOpt =
          keyResolverService.resolveKey(
              rlContext,
              compiled.keyType(),
              compiled.components(),
              compiled.apiKeyHeader(),
              compiled.claimName(),
              compiled.tenantSource(),
              compiled.tenantName(),
              compiled.keyResolverBean(),
              compiled.onMissingKey());
    } catch (MissingIdentityException e) {
      // on-missing-key: REJECT -> 429 identity-required
      problemWriter.writeProblem(
          request, response, "identity-required", 429, null, corePolicy.name(), null);
      return false;
    }

    if (clientKeyOpt.isEmpty()) {
      // on-missing-key: SKIP -> proceed without rate limit
      return true;
    }

    String clientKey = clientKeyOpt.get();
    RequestContext reqCtx = new RequestContext(clientKey, corePolicy.name(), plan, 1L);

    Decision decision = rateLimitEngine.evaluate(reqCtx, corePolicy);

    // Write rate limit headers (if not degraded)
    headerWriter.writeHeaders(response, decision, properties.getHeaders());

    if (decision.allowed()) {
      if (decision.permitId() != null) {
        request.setAttribute(PERMIT_ID_ATTR, decision.permitId());
        request.setAttribute(PERMIT_KEY_ATTR, clientKey);
        request.setAttribute(PERMIT_POLICY_ATTR, corePolicy.name());
      }
      return true;
    }

    // Rejection handling
    String kind;
    int status = 429;
    if (decision.degraded()) {
      kind = "store-unavailable";
      status = properties.getFailClosedStatus();
    } else if ("concurrency".equals(decision.ruleId())) {
      kind = "concurrency-limit-exceeded";
    } else {
      kind = "rate-limit-exceeded";
    }

    long retryAfterSec =
        decision.retryAfter() != null && !decision.retryAfter().isZero()
            ? Math.max(1, (decision.retryAfter().toMillis() + 999) / 1000)
            : 1L;

    problemWriter.writeProblem(
        request, response, kind, status, retryAfterSec, decision.policyName(), decision.ruleId());
    return false;
  }

  @Override
  public void afterCompletion(
      HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
    Object permitId = request.getAttribute(PERMIT_ID_ATTR);
    Object key = request.getAttribute(PERMIT_KEY_ATTR);
    Object policy = request.getAttribute(PERMIT_POLICY_ATTR);

    if (permitId instanceof String pid && key instanceof String k && policy instanceof String pol) {
      try {
        rateLimitStore.release(k, pol, pid);
      } catch (Throwable ignored) {
        // Background release or lease TTL will clean up if this fails
      }
    }
  }

  private RateLimitContext buildRateLimitContext(HttpServletRequest request) {
    String principal =
        request.getUserPrincipal() != null ? request.getUserPrincipal().getName() : null;
    String clientIp = clientIpResolver.resolveClientIp(request);

    Object bestPattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
    String routeTemplate = bestPattern instanceof String s ? s : request.getRequestURI();

    Map<String, String> headers = new HashMap<>();
    Enumeration<String> headerNames = request.getHeaderNames();
    if (headerNames != null) {
      while (headerNames.hasMoreElements()) {
        String name = headerNames.nextElement();
        headers.put(name.toLowerCase(), request.getHeader(name));
      }
    }

    Map<String, Object> claims = new HashMap<>();
    // Extract claims from request attributes if JWT/OAuth2 populated them
    Enumeration<String> attrNames = request.getAttributeNames();
    if (attrNames != null) {
      while (attrNames.hasMoreElements()) {
        String attr = attrNames.nextElement();
        if (attr.startsWith("claim.") || attr.startsWith("jwt.")) {
          claims.put(attr.substring(attr.indexOf('.') + 1), request.getAttribute(attr));
        }
      }
    }

    return RateLimitContext.builder()
        .principal(principal)
        .clientIp(clientIp)
        .routeTemplate(routeTemplate)
        .httpMethod(request.getMethod())
        .headers(headers)
        .claims(claims)
        .build();
  }

  private String resolvePlan(CompiledPolicy compiled, RateLimitContext ctx) {
    if (compiled.planResolverBean() != null
        && planResolvers.containsKey(compiled.planResolverBean())) {
      return planResolvers.get(compiled.planResolverBean()).resolvePlan(ctx).orElse(null);
    }
    if (!planResolvers.isEmpty()) {
      return planResolvers.values().iterator().next().resolvePlan(ctx).orElse(null);
    }
    return ctx.claim("plan").map(Object::toString).orElse(null);
  }
}
