package io.github.ezmanish.trafficcontrol.spring.web.resolver;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.ezmanish.trafficcontrol.core.api.RateLimitKey;
import io.github.ezmanish.trafficcontrol.core.spi.RateLimitContext;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class CompositeKeyResolverServiceTest {

  private final CompositeKeyResolverService resolver = new CompositeKeyResolverService();

  @Test
  @DisplayName("TC-160: USER key unauthenticated falls back to IP")
  void testUserKeyFallbackToIp() {
    RateLimitContext ctx = RateLimitContext.builder().clientIp("192.0.2.1").build();

    Optional<String> key =
        resolver.resolveKey(
            ctx,
            RateLimitKey.USER,
            List.of(),
            "X-API-Key",
            "sub",
            "CLAIM",
            "tenant_id",
            null,
            OnMissingKeyStrategy.FALLBACK_IP);

    assertThat(key).isPresent();
    assertThat(key.get()).isEqualTo(KeyHashUtil.hash("192.0.2.1"));
  }

  @Test
  @DisplayName("TC-161: Missing key with ANONYMOUS strategy yields shared anonymous hash")
  void testAnonymousStrategy() {
    RateLimitContext ctx = RateLimitContext.builder().build();

    Optional<String> key =
        resolver.resolveKey(
            ctx,
            RateLimitKey.USER,
            List.of(),
            "X-API-Key",
            "sub",
            "CLAIM",
            "tenant_id",
            null,
            OnMissingKeyStrategy.ANONYMOUS);

    assertThat(key).isPresent();
    assertThat(key.get()).isEqualTo(KeyHashUtil.hash("anonymous"));
  }

  @Test
  @DisplayName("TC-162: Missing key with SKIP strategy returns empty")
  void testSkipStrategy() {
    RateLimitContext ctx = RateLimitContext.builder().build();

    Optional<String> key =
        resolver.resolveKey(
            ctx,
            RateLimitKey.USER,
            List.of(),
            "X-API-Key",
            "sub",
            "CLAIM",
            "tenant_id",
            null,
            OnMissingKeyStrategy.SKIP);

    assertThat(key).isEmpty();
  }

  @Test
  @DisplayName("TC-163: Missing key with REJECT strategy throws MissingIdentityException")
  void testRejectStrategy() {
    RateLimitContext ctx = RateLimitContext.builder().build();

    assertThatThrownBy(
            () ->
                resolver.resolveKey(
                    ctx,
                    RateLimitKey.USER,
                    List.of(),
                    "X-API-Key",
                    "sub",
                    "CLAIM",
                    "tenant_id",
                    null,
                    OnMissingKeyStrategy.REJECT))
        .isInstanceOf(MissingIdentityException.class);
  }

  @Test
  @DisplayName("TC-164: API_KEY and JWT_CLAIM extraction")
  void testApiKeyAndJwtClaim() {
    RateLimitContext ctx =
        RateLimitContext.builder()
            .headers(Map.of("x-api-key", "secret-key-123"))
            .claims(Map.of("sub", "user-uuid-456"))
            .build();

    Optional<String> apiKey =
        resolver.resolveKey(
            ctx,
            RateLimitKey.API_KEY,
            List.of(),
            "x-api-key",
            "sub",
            "CLAIM",
            "tenant_id",
            null,
            OnMissingKeyStrategy.REJECT);
    assertThat(apiKey).contains(KeyHashUtil.hash("secret-key-123"));

    Optional<String> jwtClaim =
        resolver.resolveKey(
            ctx,
            RateLimitKey.JWT_CLAIM,
            List.of(),
            "x-api-key",
            "sub",
            "CLAIM",
            "tenant_id",
            null,
            OnMissingKeyStrategy.REJECT);
    assertThat(jwtClaim).contains(KeyHashUtil.hash("user-uuid-456"));
  }

  @Test
  @DisplayName("TC-165: COMPOSITE key of USER and ENDPOINT produces combined hash")
  void testCompositeKey() {
    RateLimitContext ctx =
        RateLimitContext.builder()
            .principal("alice")
            .httpMethod("POST")
            .routeTemplate("/api/checkout")
            .build();

    Optional<String> key =
        resolver.resolveKey(
            ctx,
            RateLimitKey.COMPOSITE,
            List.of(RateLimitKey.USER, RateLimitKey.ENDPOINT),
            "X-API-Key",
            "sub",
            "CLAIM",
            "tenant_id",
            null,
            OnMissingKeyStrategy.REJECT);

    assertThat(key).contains(KeyHashUtil.hash("alice|POST:/api/checkout"));
  }

  @Test
  @DisplayName("TC-166: ENDPOINT uses route template and not raw URI")
  void testEndpointRouteTemplate() {
    RateLimitContext ctx1 =
        RateLimitContext.builder().httpMethod("GET").routeTemplate("/api/items/{id}").build();

    RateLimitContext ctx2 =
        RateLimitContext.builder().httpMethod("GET").routeTemplate("/api/items/{id}").build();

    Optional<String> key1 =
        resolver.resolveKey(
            ctx1,
            RateLimitKey.ENDPOINT,
            List.of(),
            null,
            null,
            null,
            null,
            null,
            OnMissingKeyStrategy.REJECT);
    Optional<String> key2 =
        resolver.resolveKey(
            ctx2,
            RateLimitKey.ENDPOINT,
            List.of(),
            null,
            null,
            null,
            null,
            null,
            OnMissingKeyStrategy.REJECT);

    assertThat(key1).isEqualTo(key2);
    assertThat(key1).contains(KeyHashUtil.hash("GET:/api/items/{id}"));
  }
}
