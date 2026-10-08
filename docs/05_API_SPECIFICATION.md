# 05 — API Specification

Covers (1) annotations, (2) HTTP contract, (3) core/SPI Java API, (4) optional management endpoints. Names are proposals consistent with the deck.

## 1. Annotations
```java
@Target({METHOD, TYPE}) @Retention(RUNTIME)
public @interface RateLimit {
    long requests();                       // permits per window
    String window();                       // "30s", "1m", "1h"
    RateLimitKey key() default RateLimitKey.USER;
    RateLimitKey[] components() default {};   // only with key = COMPOSITE (2-3 entries)
    Algorithm algorithm() default Algorithm.TOKEN_BUCKET;   // TOKEN_BUCKET, GCRA, FIXED_WINDOW, SLIDING_WINDOW_COUNTER, SLIDING_WINDOW_LOG
    Limit[] rules() default {};            // multiple rules: @RateLimit(rules={@Limit(requests=10,window="1s"), ...})
    String keyExpression() default "";     // [Assumption] SpEL for custom keys
    String onFailure() default "";         // FAIL_OPEN | FAIL_CLOSED | "" = global default
}

@Target({METHOD, TYPE}) @Retention(RUNTIME)
public @interface RateLimitPolicy {
    String value();                        // named YAML policy, e.g. "login"
}

public enum RateLimitKey { USER, IP, API_KEY, JWT_CLAIM, TENANT, ENDPOINT, GLOBAL, COMPOSITE, CUSTOM }
// Final (ADR-037). PLAN is not a key type: plans select limits via PlanResolver; a shared cap per plan is a CUSTOM resolver.
```
Precedence: method annotation > class annotation > global default. Both annotations on one element → startup error.

## 2. HTTP Contract (final)

### 2.1 Response headers (when `headers.enabled`, style LEGACY)
| Header | Present on | Value |
|---|---|---|
| `X-RateLimit-Limit` | every decision with a result | `limit` of the binding rule (integer) |
| `X-RateLimit-Remaining` | same | `remaining` of the binding rule (integer ≥ 0) |
| `X-RateLimit-Reset` | same | **Unix epoch seconds** (integer) when the binding rule is fully replenished: `ceil(resetAtMs/1000)` |
| `Retry-After` | every 429 | **Delta-seconds** (integer ≥ 1): `max(1, ceil(retryAfterMs/1000))` |
| `Cache-Control: no-store` | every 429 | fixed |
Not sent when the decision is degraded (fail-open or store-unavailable): there is no result to report. `RateLimit` / `RateLimit-Policy` are emitted only for `headers.style` IETF/BOTH (draft-ietf-httpapi-ratelimit-headers, still a draft). When several rules apply, headers describe the binding rule (`19 §0`).

### 2.2 Problem body (RFC 9457, `Content-Type: application/problem+json`)
Default body (security-minimal; no policy names, rule ids, keys or identities):
```json
{
  "type": "urn:trafficcontrol:problem:rate-limit-exceeded",
  "title": "Too Many Requests",
  "status": 429,
  "detail": "Request rate limit exceeded.",
  "retryAfterSeconds": 23
}
```
| Member | Rule |
|---|---|
| `type` | URN `urn:trafficcontrol:problem:<kind>` (base configurable via `problem.base-uri`) |
| `title` | Fixed text per kind: `Too Many Requests` |
| `status` | HTTP status (429 for all kinds; `fail-closed-status` may change store-unavailable) |
| `detail` | **Fixed generic text per kind**, never request-specific |
| `retryAfterSeconds` | Integer, equals the `Retry-After` header; omitted for `identity-required` |
| `policy`, `rule` | **Omitted by default.** Included only when `problem.expose-details: true` (intended for internal APIs) |
| `instance` | **Omitted by default.** If `problem.include-instance: true`: `urn:uuid:<request id>` taken from `X-Request-Id` or the trace id; never the path, key or user |
Kinds:
| `<kind>` | When | `detail` |
|---|---|---|
| `rate-limit-exceeded` | a rate rule rejected | `Request rate limit exceeded.` |
| `concurrency-limit-exceeded` | concurrency rule rejected | `Too many concurrent requests.` |
| `store-unavailable` | FAIL_CLOSED and store down/breaker open | `Rate limiting is temporarily unavailable.` |
| `identity-required` | `on-missing-key: REJECT` | `A client identity is required for this endpoint.` |
`Retry-After` for `store-unavailable` = remaining breaker open time (≥ 1 s). `identity-required` is deliberately 429 (not 401/403) so the limiter never acts as an authentication oracle.

### 2.3 Status code summary
429 for every limiter rejection; the limiter never emits 401/403/5xx. Responses to allowed requests are unchanged apart from headers.

## 3. Core Java API
```java
public interface RateLimitEngine {
    Decision evaluate(RequestContext ctx, Policy policy);
}
public record Decision(Outcome outcome, long limit, long remaining,
                       Duration retryAfter, String policyName, String ruleId, boolean degraded) {}
public enum Outcome { ALLOW, REJECT }
```

## 4. SPIs
```java
public interface RateLimitStore {
    StoreResult tryConsume(String key, List<Rule> rules, long cost);   // atomic, all-or-nothing
    void release(String key, String permitId);                         // concurrency
}
public interface RateLimitAlgorithm {
    Decision evaluate(RequestContext ctx, List<Rule> rules, RateLimitStore store);
}
public interface RateLimitResponseHandler { void reject(HttpServletResponse res, Decision d); }
public interface RateLimitKeyResolver { Optional<String> resolve(RateLimitContext ctx); }   // core: framework-neutral, no Servlet types
public interface PlanResolver { Optional<String> resolvePlan(RateLimitContext ctx); }        // selects an entry of `plans`
// RateLimitContext carries neutral attributes (principal, clientIp, headers, claims, endpoint template + method).
// The starter builds it from HttpServletRequest; core never sees Servlet types.
public interface PolicyProvider { PolicySnapshot load(); void onChange(Consumer<PolicySnapshot> cb); }
public interface Clock { long nowMillis(); }                           // Redis TIME in redis store
public interface DecisionListener { void onDecision(Decision d, RequestContext c); }
public interface FailureStrategy { Decision onStoreFailure(Policy p, Throwable t); }
```

## 5. Admin API (Should; final schema)
**Enablement:** off by default. `trafficcontrol.admin.enabled=true` requires a Spring Security `SecurityFilterChain` protecting the base path, otherwise startup fails. Base path `trafficcontrol.admin.base-path` (default `/trafficcontrol/admin`). Required role `TRAFFICCONTROL_ADMIN` (configurable). The admin API is itself rate limited (60 requests/min per actor). Actor = authenticated principal name.

**Media types:** requests/responses `application/json`; errors `application/problem+json`.

**Policy representation** (mirrors the YAML in `06`):
```json
{
  "name": "login",
  "key": "IP",
  "algorithm": "TOKEN_BUCKET",
  "failMode": "FAIL_CLOSED",
  "onMissingKey": "FALLBACK_IP",
  "rules": [ { "id": "r1", "algorithm": "GCRA", "requests": 5, "window": "1m", "burst": 5 } ],
  "concurrency": { "max": 3, "wait": "0ms", "leaseTtl": "30s" },
  "plans": { "FREE": { "requests": 100, "window": "1h" } },
  "adaptive": { "enabled": false, "minMultiplier": 0.25 }
}
```
**Snapshot metadata:** `{ "version": 12, "checksum": "sha256:…", "updatedAt": "2026-10-07T10:00:00Z", "updatedBy": "alice" }`

| Method | Path | Request | Success | Errors |
|---|---|---|---|---|
| GET | `/policies` | — | 200 `{version, checksum, policies:[…]}`, `ETag: "12"` | 401, 403 |
| GET | `/policies/{name}` | — | 200 policy, `ETag` | 404 |
| PUT | `/policies/{name}` | policy body, header `If-Match: "<version>"` (use `*` to create) | 200 (updated) or 201 (created) + snapshot metadata. Identical content = 200, version unchanged | 400 malformed JSON; **422** invalid policy; 412 version mismatch; 428 `If-Match` missing |
| DELETE | `/policies/{name}` | `If-Match` | 204 | 404; 409 `policy-in-use` (referenced by a path rule); 412; 428 |
| POST | `/rollback` | `{"toVersion": 11}` + `If-Match` | 200 snapshot metadata (new version N+1 with the old content) | 404 version not retained (last 20 retained); 412; 428 |
| GET | `/audit?limit=50&cursor=` | — | 200 `{entries:[{id, version, timestamp, actor, action, policy, before, after}], nextCursor}` | 401, 403 |
Validation failures (422) use the same validator as startup (`06 §9`):
```json
{ "type":"urn:trafficcontrol:problem:invalid-policy","title":"Invalid policy","status":422,
  "errors":[ {"code":"V-006","path":"rules[0].window","message":"'1x' is not a valid duration"} ] }
```
**Semantics:** a successful write creates snapshot version N+1, stores it, appends an audit entry, publishes `{version, checksum}` on the update channel. Optimistic concurrency through `If-Match` prevents lost updates between two admins. Audit entries are kept in a capped Redis stream (10,000) and logged.

## 6. Error Codes
| Condition | Result |
|---|---|
| Limit exceeded | 429 + Retry-After |
| Store failure + FAIL_CLOSED | 429 + `store-unavailable` problem type |
| Store failure + FAIL_OPEN | Allow, `degraded=true`, metric incremented |
| Unknown policy name | Startup failure |
| Key unresolvable | Per `on-missing-key` (default `FALLBACK_IP`), see `06 §8` |
