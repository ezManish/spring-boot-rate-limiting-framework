package io.github.ezmanish.trafficcontrol.spring.web.resolver;

import io.github.ezmanish.trafficcontrol.core.api.RateLimitKey;
import io.github.ezmanish.trafficcontrol.core.spi.RateLimitContext;
import io.github.ezmanish.trafficcontrol.core.spi.RateLimitKeyResolver;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Resolves client identity keys based on configured RateLimitKey strategy and on-missing-key
 * policy.
 */
public class CompositeKeyResolverService {

  private final Map<String, RateLimitKeyResolver> customResolvers;

  public CompositeKeyResolverService(Map<String, RateLimitKeyResolver> customResolvers) {
    this.customResolvers = customResolvers != null ? customResolvers : Map.of();
  }

  public CompositeKeyResolverService() {
    this(Map.of());
  }

  public Optional<String> resolveKey(
      RateLimitContext ctx,
      RateLimitKey keyType,
      List<RateLimitKey> components,
      String apiKeyHeader,
      String claimName,
      String tenantSource,
      String tenantName,
      String customResolverBean,
      OnMissingKeyStrategy onMissingKey) {

    Optional<String> resolved =
        doResolve(
            ctx,
            keyType,
            components,
            apiKeyHeader != null ? apiKeyHeader : "X-API-Key",
            claimName != null ? claimName : "sub",
            tenantSource != null ? tenantSource : "CLAIM",
            tenantName != null ? tenantName : "tenant_id",
            customResolverBean);

    if (resolved.isPresent() && !resolved.get().isBlank()) {
      return Optional.of(KeyHashUtil.hash(resolved.get()));
    }

    // Apply on-missing-key strategy
    OnMissingKeyStrategy strategy =
        onMissingKey != null ? onMissingKey : OnMissingKeyStrategy.FALLBACK_IP;

    return switch (strategy) {
      case FALLBACK_IP -> {
        String ip = ctx.clientIp();
        yield Optional.of(KeyHashUtil.hash(ip != null ? ip : "anonymous"));
      }
      case ANONYMOUS -> Optional.of(KeyHashUtil.hash("anonymous"));
      case SKIP -> Optional.empty();
      case REJECT ->
          throw new MissingIdentityException("A client identity is required for this endpoint.");
    };
  }

  private Optional<String> doResolve(
      RateLimitContext ctx,
      RateLimitKey keyType,
      List<RateLimitKey> components,
      String apiKeyHeader,
      String claimName,
      String tenantSource,
      String tenantName,
      String customResolverBean) {

    if (keyType == null) {
      keyType = RateLimitKey.USER;
    }

    switch (keyType) {
      case USER -> {
        return Optional.ofNullable(ctx.principal());
      }
      case IP -> {
        return Optional.ofNullable(ctx.clientIp());
      }
      case API_KEY -> {
        return ctx.header(apiKeyHeader);
      }
      case JWT_CLAIM -> {
        return ctx.claim(claimName).map(Object::toString);
      }
      case TENANT -> {
        if ("HEADER".equalsIgnoreCase(tenantSource)) {
          return ctx.header(tenantName);
        } else {
          return ctx.claim(tenantName).map(Object::toString);
        }
      }
      case ENDPOINT -> {
        String method = ctx.httpMethod() != null ? ctx.httpMethod() : "GET";
        String route = ctx.routeTemplate() != null ? ctx.routeTemplate() : "/";
        return Optional.of(method + ":" + route);
      }
      case GLOBAL -> {
        return Optional.of("global");
      }
      case COMPOSITE -> {
        if (components == null || components.isEmpty()) {
          return Optional.empty();
        }
        List<String> parts = new ArrayList<>();
        for (RateLimitKey comp : components) {
          Optional<String> part =
              doResolve(
                  ctx, comp, List.of(), apiKeyHeader, claimName, tenantSource, tenantName, null);
          if (part.isEmpty() || part.get().isBlank()) {
            return Optional
                .empty(); // If any component of a composite key is missing, composite is unresolved
          }
          parts.add(part.get());
        }
        return Optional.of(String.join("|", parts));
      }
      case CUSTOM -> {
        if (customResolverBean != null && customResolvers.containsKey(customResolverBean)) {
          return customResolvers.get(customResolverBean).resolve(ctx);
        }
        return Optional.empty();
      }
    }

    return Optional.empty();
  }
}
