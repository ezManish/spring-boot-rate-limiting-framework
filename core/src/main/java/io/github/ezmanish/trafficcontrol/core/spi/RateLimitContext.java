package io.github.ezmanish.trafficcontrol.core.spi;

import java.util.Collections;
import java.util.Map;
import java.util.Optional;

/**
 * Framework-neutral request context for key and plan resolution. Contains no Servlet or Spring
 * types.
 */
public record RateLimitContext(
    String principal,
    String clientIp,
    String routeTemplate,
    String httpMethod,
    Map<String, String> headers,
    Map<String, Object> claims,
    Map<String, Object> attributes) {

  public RateLimitContext {
    headers = headers != null ? Map.copyOf(headers) : Map.of();
    claims = claims != null ? Map.copyOf(claims) : Map.of();
    attributes = attributes != null ? Map.copyOf(attributes) : Map.of();
  }

  public static Builder builder() {
    return new Builder();
  }

  public Optional<String> header(String name) {
    if (name == null) {
      return Optional.empty();
    }
    for (Map.Entry<String, String> entry : headers.entrySet()) {
      if (entry.getKey().equalsIgnoreCase(name)) {
        return Optional.ofNullable(entry.getValue());
      }
    }
    return Optional.empty();
  }

  public Optional<Object> claim(String name) {
    return Optional.ofNullable(claims.get(name));
  }

  public static class Builder {
    private String principal;
    private String clientIp;
    private String routeTemplate;
    private String httpMethod;
    private Map<String, String> headers = Collections.emptyMap();
    private Map<String, Object> claims = Collections.emptyMap();
    private Map<String, Object> attributes = Collections.emptyMap();

    public Builder principal(String principal) {
      this.principal = principal;
      return this;
    }

    public Builder clientIp(String clientIp) {
      this.clientIp = clientIp;
      return this;
    }

    public Builder routeTemplate(String routeTemplate) {
      this.routeTemplate = routeTemplate;
      return this;
    }

    public Builder httpMethod(String httpMethod) {
      this.httpMethod = httpMethod;
      return this;
    }

    public Builder headers(Map<String, String> headers) {
      this.headers = headers;
      return this;
    }

    public Builder claims(Map<String, Object> claims) {
      this.claims = claims;
      return this;
    }

    public Builder attributes(Map<String, Object> attributes) {
      this.attributes = attributes;
      return this;
    }

    public RateLimitContext build() {
      return new RateLimitContext(
          principal, clientIp, routeTemplate, httpMethod, headers, claims, attributes);
    }
  }
}
