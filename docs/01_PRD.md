# Product Requirements Document (PRD)

## Declarative Distributed API Traffic Control Framework for Java / Spring Boot

| Field | Value |
|---|---|
| Working title | Declarative Distributed API Traffic Control Framework |
| Codename | `trafficcontrol` (verify Maven Central / GitHub name availability in week 1; groupId `io.github.ezmanish`; verify the namespace in the Central Portal) |
| Document type | PRD v1.1 (final year project), aligned with `16_DECISION_LOG.md` |
| Team size | 2-3 students |
| Delivery plan | 110-day build plan (6 phases) plus 2-3 weeks of buffer; evaluation and report run alongside, within the academic year |
| Primary stack | Java 21, Spring Boot 3.5.x, Redis, Micrometer, Prometheus, Grafana |
| Status | Draft for faculty review |

---

## 1. Executive Summary

Modern APIs need protection from abuse, accidental request storms, brute-force attempts and overload. Mature Java libraries (Bucket4j, Resilience4j) and gateways (Spring Cloud Gateway, Envoy, Kong) already provide rate limiting. What is still painful for a Spring Boot developer is the surrounding work: deciding who is being limited, choosing an algorithm, keeping state consistent across many instances, expressing per-endpoint and per-plan policies, returning correct HTTP responses, changing limits without redeploying, and observing what is happening.

This project builds a **modular, developer-first traffic-control framework** for Spring Boot that turns those concerns into a single declarative policy model. A developer adds a starter dependency, writes an annotation or a YAML policy, and gets correct, distributed, observable API protection.

The project deliberately does **not** claim to invent rate limiting. Its contribution is:

1. A unified **policy model** (rate + concurrency + identity + endpoint + plan).
2. A **framework-independent core engine** with pluggable algorithms, stores and key resolvers.
3. A **correct and measured distributed implementation** (atomic Redis operations, multi-rule evaluation, failure modes).
4. **Dynamic policy management** without redeploys.
5. An **experimentally evaluated adaptive component** (load-aware limits), compared against static limits.
6. A reproducible **benchmark and accuracy evaluation** against existing tools.

---

## 2. Background and Problem Statement

### 2.1 Background

Rate limiting looks simple (`counter++; if counter > limit: reject`) but production use raises several hard questions:

| Question | Why it is hard |
|---|---|
| Who is limited? | IP, user, API key, JWT subject, organization, device, or a combination. IP is unreliable behind proxies and NAT. |
| What is limited? | Requests, expensive operations, bandwidth, or concurrent executions. |
| Where is the state? | Local memory breaks with multiple instances. Redis adds latency and a failure mode. |
| Which algorithm? | Fixed window, sliding window and token bucket differ in burst behavior, accuracy and memory. |
| What happens on excess? | Reject, delay, queue or fallback. |
| How is it changed? | Restarting an application to change a limit is unacceptable in production. |
| How is it observed? | Teams need to know who is being throttled and how close to the limit they are. |

### 2.2 Problem Statement

Developers currently assemble rate limiting from multiple libraries and custom code, re-solving the same infrastructure problems in every project. There is no lightweight, Spring-native framework that offers a single declarative policy model covering multi-dimensional limits, distributed correctness, concurrency limiting, dynamic policy updates and built-in observability.

### 2.3 Opportunity

Provide the "Spring Data" style experience for traffic control: declare intent, let the framework handle infrastructure.

---

## 3. Goals and Non-Goals

### 3.1 Goals

| ID | Goal |
|---|---|
| G1 | A developer can protect an existing Spring Boot endpoint in under 5 minutes with one dependency and one annotation or YAML block. |
| G2 | Configured limits are enforced correctly under concurrency and across multiple application instances. |
| G3 | The core engine is independent of Spring and reusable as plain Java. |
| G4 | Algorithms, stores and key resolvers are extensible through stable interfaces. |
| G5 | Policies can be changed at runtime without restarting instances. |
| G6 | Every decision is observable through metrics, and (optionally) an audit event stream. |
| G7 | Overhead and accuracy are quantified through a reproducible benchmark suite. |
| G8 | Adaptive, load-aware limiting is implemented and experimentally compared with static limiting. |
| G9 | Every common rate-limiting algorithm is available behind one SPI and one atomic Redis script, with measured accuracy and cost per algorithm. |

### 3.2 Non-Goals

- Inventing a new rate-limiting algorithm.
- Replacing API gateways, WAFs or DDoS protection services.
- Supporting non-JVM languages.
- Multi-region global consistency (listed as future scope).
- Full authentication or authorization (the framework only *consumes* identity).
- Custom LLM or ML model training (adaptive control uses classical feedback control).

---

## 4. Target Users and Personas

| Persona | Description | Primary need |
|---|---|---|
| **Backend developer (primary)** | Spring Boot developer building REST APIs | Add limits quickly without learning bucket internals. |
| **Platform / SRE engineer** | Runs multiple instances behind a load balancer | Correct global limits, dashboards, runtime policy changes, safe failure behavior. |
| **API product owner** | Sells tiered API plans (free / pro / enterprise) | Plan-based quotas and clear 429 behavior for clients. |
| **Security-minded engineer** | Protects login and sensitive endpoints | Brute-force protection, auditable throttling decisions. |
| **Evaluator / researcher (project context)** | Faculty and reviewers | Clear novelty, measurable results, sound methodology. |

---

## 5. Competitive Landscape and Differentiation

> **Important:** the comparison below reflects the intended positioning. Every claim about an existing tool must be **re-verified against its current version** during week 1 and again before final submission. Do not present unverified checkmarks in the final report.

### 5.1 Existing Solutions

| Solution | Type | What it does well | Where this project differs |
|---|---|---|---|
| **Bucket4j** | Java rate-limiting library | Mature token-bucket implementation, clustering support, multiple bandwidths | Low-level library; policy management, HTTP handling and observability are largely left to the integrator. |
| **Resilience4j** | Resilience toolkit | Rate limiter, circuit breaker, bulkhead, retry; Spring annotations; Micrometer | Broad resilience focus; rate limiter is per-instance oriented and not a distributed policy engine. |
| **Alibaba Sentinel** | Flow control / degradation | Cluster flow control, dynamic rules, rich SPI | Heavier, different ecosystem conventions; useful comparison for dynamic rules and cluster mode. |
| **Spring Cloud Gateway RequestRateLimiter** | Gateway filter | Redis token bucket, key resolver, HTTP 429 | Operates at the gateway layer, not inside the application. Can coexist with this framework. |
| **Envoy / Istio / Kong / cloud gateways** | Infrastructure | Rate limiting via config, no code | Infrastructure-level, coarse identity context; app-level business identity (plan, tenant) is harder. |
| **Hystrix** | Legacy | Historic circuit breaker | Maintenance mode; not a target. |

### 5.2 Differentiators (What Is New Here)

| Differentiator | Description | Strength of claim |
|---|---|---|
| Unified policy model | One model for rate, concurrency, identity, endpoint and plan | Design contribution |
| Multi-dimensional limits with atomic evaluation | A request passes only if all applicable limits pass, with no partial consumption | Engineering contribution |
| Concurrency + rate in one policy | Prevents slow requests from overloading despite a low request rate | Engineering contribution |
| Runtime policy management | Versioned policy propagation to all instances without restart | Engineering contribution |
| Adaptive limits (research component) | Feedback-controlled limit multiplier based on system load, evaluated against static limits | Research contribution (must be backed by data) |
| Reproducible evaluation | Accuracy, latency and throughput compared with Bucket4j and no-limiter baselines | Evaluation contribution |

**Honest positioning statement for the report:** novelty of the *algorithms* is low; novelty of the *integration, policy model, distributed correctness analysis and adaptive evaluation* is moderate and defensible provided the evaluation is real.

---

## 6. Scope and Prioritization

Priorities use MoSCoW: **M**ust, **S**hould, **C**ould, **W**on't (this version).

### 6.1 Release Plan

| Release | Contents | Priority |
|---|---|---|
| **MVP (v0.1)** | Core engine, token bucket, local store, `@RateLimit` in Spring Boot, key resolvers, HTTP 429 + headers | Must |
| **v0.5** | Fixed + sliding window, Redis store (atomic Lua), multi-rule policies, YAML policies, plan-based limits, Micrometer metrics | Must |
| **v1.0** | Concurrency limiter, dynamic policy management (admin API + propagation), fail-open/closed, benchmark suite, documentation, demo app | Must / Should |
| **v1.x (research)** | Adaptive load-aware limiting with experimental evaluation | Should |
| **Stretch** | Overflow strategies (delay / queue / fallback), Grafana dashboard pack, WebFlux support, admin UI | Could |
| **Future** | gRPC, GraphQL, multi-region, Kubernetes operator, LLM token/cost-aware limits | Won't (this version) |

Release-to-phase mapping (see Section 15): MVP = end of Phase 2 (day 35); v0.5 = end of Phase 4 (day 70); v1.0 = end of Phase 6 (day 110).

### 6.2 Scope Control Rule

Do not start a "Could" item until all "Must" items have passing tests and benchmark results. A smaller system that is correct and measured scores higher than a larger one that is half-finished.

---

## 7. User Stories

| ID | As a... | I want... | So that... |
|---|---|---|---|
| US-01 | Backend developer | to annotate an endpoint with a limit | I can protect it without writing infrastructure code |
| US-02 | Backend developer | to define named policies in YAML | limits are separated from business code |
| US-03 | Backend developer | to choose the client key (IP, user, API key, custom) | limits apply to the right entity |
| US-04 | Backend developer | standard 429 responses with `Retry-After` and rate-limit headers | clients know when to retry |
| US-05 | Product owner | different limits per subscription plan | free and paid users are treated differently |
| US-06 | Platform engineer | limits enforced globally across all instances | scaling out does not multiply the allowed rate |
| US-07 | Platform engineer | to change a policy at runtime | I can react to incidents without redeploying |
| US-08 | Platform engineer | to choose fail-open or fail-closed when Redis is down | behavior matches the risk profile of each endpoint |
| US-09 | Platform engineer | Prometheus metrics and dashboards | I can see throttling and utilization |
| US-10 | Security engineer | strict limits on login endpoints | brute-force attempts are slowed |
| US-11 | Backend developer | to cap concurrent executions of an expensive endpoint | slow requests cannot exhaust threads |
| US-12 | Platform engineer | limits that tighten automatically under high load | the service degrades gracefully instead of collapsing |
| US-13 | Library author | to plug in my own algorithm, store or resolver | the framework fits unusual needs |

---

## 8. Functional Requirements

### 8.1 Core Engine

| ID | Requirement | Priority |
|---|---|---|
| FR-01 | Provide a framework-independent `RateLimiter` API: `RateLimitDecision check(RateLimitContext ctx)`. | Must |
| FR-02 | The core module has **no dependency** on Spring, Servlet or Redis client libraries. | Must |
| FR-03 | Decisions expose: allowed flag, limit, remaining, reset time, retry-after, and the rule that caused rejection. | Must |
| FR-04 | Engine is thread-safe and non-blocking for the local store. | Must |
| FR-05 | Engine evaluates **all** rules of a policy; a request is allowed only if all pass. | Must |
| FR-06 | Multi-rule evaluation is atomic: a rejected request must not consume tokens, window counts or concurrency permits from any rule that would have passed. Rate rules and the concurrency rule are evaluated in the same atomic step. | Must |

### 8.2 Algorithms

| ID | Requirement | Priority |
|---|---|---|
| FR-10 | Token Bucket (capacity, refill rate, burst). | Must |
| FR-11 | Fixed Window Counter. | Must |
| FR-12 | Sliding Window Counter (weighted two-window approximation). | Must |
| FR-13 | Sliding Window Log (exact, higher memory). | Must (4th in the pre-agreed cut order, Section 15) |
| FR-14 | Algorithms implement a common `RateLimitAlgorithm` SPI. | Must |
| FR-15 | GCRA (Generic Cell Rate Algorithm; single-timestamp state). Also serves as the leaky-bucket-as-meter implementation (`LEAKY_BUCKET` is an alias). | Must |
| FR-16 | Every algorithm has a local implementation and a Redis Lua implementation inside the same atomic multi-rule script, and passes one shared conformance test suite. | Must |
| FR-17 | Algorithm is selectable per rule, so one policy can mix algorithms (for example GCRA per second plus sliding window counter per hour). | Must |
| FR-18 | Leaky bucket as a queue (traffic shaping: delay instead of reject) is covered by overflow strategies, not by the limiter algorithms. | Could (last in cut order) |

### 8.3 Storage

| ID | Requirement | Priority |
|---|---|---|
| FR-20 | `RateLimitStore` SPI with local in-memory implementation (bounded size, TTL eviction). | Must |
| FR-21 | Redis store using **atomic Lua scripts**; no read-modify-write from the client. | Must |
| FR-22 | Redis time (`TIME`) is the time source in distributed mode to avoid client clock skew. | Must |
| FR-23 | Redis Cluster compatible key design using hash tags so all keys of one client map to one slot. | Must |
| FR-24 | Optional local + Redis hybrid mode (local pre-check, Redis authority) for latency reduction. | Could |

### 8.4 Identity / Key Resolution

| ID | Requirement | Priority |
|---|---|---|
| FR-30 | Built-in key types: USER, IP, API_KEY, JWT_CLAIM, TENANT, ENDPOINT, GLOBAL, COMPOSITE, CUSTOM (final list: `06 §10`). | Must |
| FR-31 | Composite keys (e.g. `user + endpoint`). | Must |
| FR-32 | Custom resolver through `RateLimitKeyResolver` SPI. | Must |
| FR-33 | Trusted-proxy configuration for `X-Forwarded-For` parsing (no blind trust of client headers). | Must |
| FR-34 | Keys stored in Redis are hashed or namespaced to avoid leaking raw identifiers. | Should |

### 8.5 Policies

| ID | Requirement | Priority |
|---|---|---|
| FR-40 | Policies declared in `application.yml` with name, rules, key strategy and algorithm. | Must |
| FR-41 | Policies attachable via `@RateLimit` (inline) and `@RateLimitPolicy("name")` (named). | Must |
| FR-42 | Path/method matching rules in YAML (annotation-free protection). | Should |
| FR-43 | Multiple rules per policy (per second, per minute, per hour). | Must |
| FR-44 | Plan-based limits: a `PlanResolver` SPI resolves the subscription plan and selects the limit set (plan is not a key type). | Should |
| FR-45 | Policy resolution order is deterministic and documented (annotation > path rule > default). | Must |
| FR-46 | Startup validation of policies with clear error messages. | Must |

### 8.6 HTTP Behavior

| ID | Requirement | Priority |
|---|---|---|
| FR-50 | Return `429 Too Many Requests` when rejected. | Must |
| FR-51 | Set `Retry-After` on 429. | Must |
| FR-52 | Set `X-RateLimit-Limit`, `X-RateLimit-Remaining`, `X-RateLimit-Reset` (Unix epoch seconds) on responses (configurable on/off). `Retry-After` carries relative delta-seconds. | Must |
| FR-53 | Configurable response body/handler; default body is RFC 9457 `application/problem+json`. | Must |
| FR-54 | Rate limiting executes **before** controller logic, and configurable relative to authentication (see Section 13). | Must |
| FR-55 | `headers.style` option: `LEGACY` (default, `X-RateLimit-*`), `IETF` (`RateLimit` and `RateLimit-Policy` fields from draft-ietf-httpapi-ratelimit-headers, still an Internet-Draft), or `BOTH`. | Could |

### 8.7 Concurrency Limiting

| ID | Requirement | Priority |
|---|---|---|
| FR-60 | Limit concurrent in-flight requests per key/policy (local semaphore). | Must (v1.0) |
| FR-61 | Distributed concurrency limiting using leases with TTL in Redis so crashed instances do not leak permits. Permit acquisition happens inside the same atomic Lua script as the rate rules. Release is a separate call. | Must (3rd in the pre-agreed cut order) |
| FR-62 | Permit is always released in a `finally` path, including on exceptions and client aborts. | Must |
| FR-63 | Configurable behavior when the limit is hit: reject immediately or wait up to a timeout. | Should |

### 8.8 Dynamic Policy Management

| ID | Requirement | Priority |
|---|---|---|
| FR-70 | Policies stored in a policy store (Redis or database) with a monotonically increasing version. | Should |
| FR-71 | Instances pick up changes via pub/sub notification, with polling as a fallback. | Should |
| FR-72 | Instances swap policies atomically using an immutable snapshot (no partially applied policy). | Must (if FR-70) |
| FR-73 | Secured admin REST API: list, get, create, update, delete, rollback policies. | Should |
| FR-74 | Every policy change is recorded in an audit log (who, when, before, after). | Should |
| FR-75 | Invalid policy updates are rejected without affecting running policies. | Must (if FR-70) |

### 8.9 Adaptive Limiting (Research Component)

| ID | Requirement | Priority |
|---|---|---|
| FR-80 | Collect system signals: CPU utilization, in-flight requests, p95 latency, error rate. | Should |
| FR-81 | Maintain a limit multiplier `m` in `[m_min, 1.0]` applied to configured limits. | Should |
| FR-82 | Control law: AIMD-style (multiplicative decrease on overload, additive increase on recovery) with hysteresis and cooldown to avoid oscillation. | Should |
| FR-83 | Adaptive behavior can be enabled per policy and is off by default. | Should |
| FR-84 | Multiplier and its inputs are exported as metrics for analysis. | Should |

### 8.10 Failure Handling

| ID | Requirement | Priority |
|---|---|---|
| FR-90 | Configurable `FAIL_OPEN` / `FAIL_CLOSED` per policy when the store is unavailable. | Must |
| FR-91 | Store operations have short, configurable timeouts. | Must |
| FR-92 | Circuit-breaker style protection around the Redis store so a slow Redis does not stall requests. | Should |
| FR-93 | Failure events are counted and exported as metrics. | Must |
| FR-94 | `FAIL_CLOSED` rejections return HTTP 429 (status configurable via `fail-closed-status`) with problem `type` `.../store-unavailable`, so clients and dashboards can tell an outage from a quota rejection. | Must |

### 8.11 Observability

| ID | Requirement | Priority |
|---|---|---|
| FR-100 | Micrometer metrics (see Section 12). | Must |
| FR-101 | Structured logs for rejections with policy name and (hashed) key. | Should |
| FR-102 | Optional decision event stream (for audit or analytics). | Could |
| FR-103 | Ready-made Grafana dashboard JSON. | Could |

### 8.12 Extensibility

| ID | Requirement | Priority |
|---|---|---|
| FR-110 | Public SPIs: `RateLimitAlgorithm`, `RateLimitStore`, `RateLimitKeyResolver`, `RateLimitResponseHandler`, `PolicyProvider`. | Must |
| FR-111 | Spring Boot auto-configuration with conditional beans so users can override any component. | Must |
| FR-112 | Optional adapter that delegates to Bucket4j (used as a baseline and as a reference implementation). | Could |

---

## 9. Non-Functional Requirements

> Numeric targets below are **initial hypotheses** to be validated or revised by measurement. They are not promises.

| ID | Category | Requirement |
|---|---|---|
| NFR-01 | Correctness | Under concurrent load, the number of allowed requests per window does not exceed the configured limit by more than the algorithm's documented tolerance (target: 0 overshoot for token bucket and fixed window with atomic ops; bounded, documented error for sliding window counter). |
| NFR-02 | Latency (local) | Engine + local store decision p99 under 100 µs (JMH). Starter end-to-end overhead p99 under 1 ms per request. |
| NFR-03 | Latency (Redis) | Added p99 latency under 5 ms with Redis in the same network (single round trip per decision). |
| NFR-04 | Throughput | Local mode sustains at least 10,000 decisions/s per node on a developer-class machine. |
| NFR-05 | Round trips | At most one Redis round trip per request regardless of number of rules. |
| NFR-06 | Memory | Local store bounded by configurable max keys; eviction prevents unbounded growth. |
| NFR-07 | Availability | Store failure never crashes the host application; behavior follows FAIL_OPEN/FAIL_CLOSED. |
| NFR-08 | Compatibility | Java 21 (25 experimental), Spring Boot 3.5.x (final 3.x line, out of OSS support since 2026-06-30; accepted risk, ADR-025), Servlet-based Spring MVC (WebFlux is stretch). Redis 6.2+ (Lua scripting). See `22_REPOSITORY_AND_DEPENDENCY_POLICY.md`. |
| NFR-09 | Maintainability | Modules have clear boundaries, at least 85% line coverage on core and algorithms, plus PIT mutation testing on core, static analysis in CI. |
| NFR-10 | Usability | Getting-started guide reproducible in under 5 minutes. |
| NFR-11 | Security | No raw secrets or PII in metrics labels; admin API authenticated (see Section 13). |
| NFR-12 | Reproducibility | All benchmarks scripted and runnable with Docker Compose. |

---

## 10. Developer Experience (API Design)

### 10.1 Getting Started

```xml
<dependency>
    <groupId>io.github.ezmanish</groupId>
    <artifactId>trafficcontrol-spring-boot-starter</artifactId>
    <version>1.0.0</version>
</dependency>
```

### 10.2 Annotation Usage

```java
// Inline
@RateLimit(requests = 100, window = "1m", key = RateLimitKey.USER)
@GetMapping("/api/products")
public List<Product> products() { ... }

// Named policy
@RateLimitPolicy("login")
@PostMapping("/api/login")
public LoginResponse login(@RequestBody LoginRequest req) { ... }

// Multiple rules
@RateLimit(rules = {
    @Limit(requests = 10,   window = "1s"),
    @Limit(requests = 100,  window = "1m"),
    @Limit(requests = 1000, window = "1h")
})
@GetMapping("/api/search")
public List<Result> search(...) { ... }
```

### 10.3 YAML Configuration

```yaml
trafficcontrol:
  enabled: true
  store: redis              # local | redis
  fail-mode: FAIL_OPEN      # default when store is unavailable
  fail-closed-status: 429   # status returned by FAIL_CLOSED policies
  trusted-proxies: ["10.0.0.0/8"]
  headers:
    enabled: true
    style: LEGACY          # LEGACY | IETF | BOTH

  policies:
    standard-api:
      key: USER
      algorithm: TOKEN_BUCKET
      rules:
        - requests: 100
          window: 1m
    login:
      key: IP
      fail-mode: FAIL_CLOSED
      rules:
        - requests: 5
          window: 1m
    heavy-report:
      key: USER
      rules:
        - requests: 20
          window: 1m
      concurrency:
        max: 3
        wait: 0ms
    premium-api:
      key: USER
      plans:
        FREE:       { requests: 100,    window: 1h }
        PRO:        { requests: 10000,  window: 1h }
        ENTERPRISE: { requests: 100000, window: 1h }
      adaptive:
        enabled: true
        min-multiplier: 0.25

  rules:                     # annotation-free matching
    - path: /api/login
      method: POST
      policy: login
    - path: /api/reports/**
      policy: heavy-report
```

### 10.4 Programmatic (Plain Java, No Spring)

```java
RateLimiter limiter = RateLimiter.builder()
    .store(new LocalMemoryStore())
    .policy(Policy.of("api", Rule.of(100, Duration.ofMinutes(1))))
    .build();

RateLimitDecision d = limiter.check(RateLimitContext.forKey("user-123", "api"));
if (!d.allowed()) { /* reject, d.retryAfter() */ }
```

### 10.5 HTTP Contract

Allowed response:

```
HTTP/1.1 200 OK
X-RateLimit-Limit: 100
X-RateLimit-Remaining: 73
X-RateLimit-Reset: 1726498123
```

Rejected response:

```
HTTP/1.1 429 Too Many Requests
Retry-After: 23
X-RateLimit-Limit: 100
X-RateLimit-Remaining: 0
X-RateLimit-Reset: 1726498123
Content-Type: application/problem+json

{"type":"urn:trafficcontrol:problem:rate-limit-exceeded",
 "title":"Too Many Requests","status":429,
 "detail":"Request rate limit exceeded.","retryAfterSeconds":23}
```

When several rules exist, headers describe the most restrictive rule (lowest remaining ratio). This must be documented.

Header semantics (decision D11): `X-RateLimit-Reset` is a Unix epoch timestamp in seconds (GitHub convention), while `Retry-After` is relative delta-seconds. The IETF draft instead expresses reset as delta-seconds in a parameter of the `RateLimit` field; it is optional via `headers.style`.

Policy and rule names are omitted by default (`problem.expose-details` opts in); the exact contract is in `05_API_SPECIFICATION.md §2`. Fail-closed (store outage) example: same 429 status, `Retry-After` set to the breaker wait time, problem `type` `urn:trafficcontrol:problem:store-unavailable`.

### 10.6 Core SPIs (Sketch)

```java
public interface RateLimitAlgorithm {
    RateLimitDecision evaluate(RateLimitContext ctx, List<Rule> rules, RateLimitStore store);
}

public interface RateLimitStore {
    // Atomic multi-rule evaluation; implementations must guarantee all-or-nothing consumption.
    StoreResult tryConsume(String key, List<RuleState> rules, long cost, TimeSource time);
}

public interface RateLimitKeyResolver {
    Optional<String> resolve(RateLimitContext ctx);
}

public interface PolicyProvider {
    PolicySnapshot current();
    void subscribe(Consumer<PolicySnapshot> listener);
}
```

Design note: the store SPI is deliberately **atomic and multi-rule** rather than `get`/`save`, because a get-then-save interface cannot be made race-free across instances.

---

## 11. System Architecture

### 11.1 Module Structure

```
trafficcontrol/
├── trafficcontrol-core/            # no Spring, no Redis client
│   ├── api/                     # RateLimiter, Decision, Context, Rule, Policy
│   ├── algorithm/               # TokenBucket, FixedWindow, SlidingWindow
│   ├── concurrency/             # ConcurrencyLimiter (local)
│   ├── adaptive/                # LoadSignal, AdaptiveController
│   └── spi/                     # Algorithm, Store, KeyResolver, PolicyProvider
├── trafficcontrol-store-local/     # in-memory store (bounded, TTL)
├── trafficcontrol-store-redis/     # Redis store, Lua scripts, policy pub/sub
├── trafficcontrol-spring-boot-starter/
│   ├── annotation/              # @RateLimit, @RateLimitPolicy, @Limit
│   ├── web/                     # interceptor/filter, response handler, resolvers
│   ├── autoconfigure/           # properties, conditional beans
│   └── admin/                   # policy management REST API
├── trafficcontrol-metrics/         # Micrometer binding
├── trafficcontrol-bench/           # JMH + k6/Gatling + Docker Compose
└── trafficcontrol-demo/            # demo Spring Boot app
```

### 11.2 Request Flow

```
Client
  │
  ▼
Servlet Filter / Interceptor
  │
  ├─► Policy Resolver ──► (annotation > path rule > default)
  │
  ├─► Key Resolver ─────► IP / user / API key / composite
  │
  ├─► Adaptive Controller (optional) ─► effective limits = configured × multiplier
  │
  ├─► Rate Limit Engine ─► Algorithm(s) ─► Store (local | Redis)
  │      Redis: ONE atomic Lua script = all rate rules + concurrency permit
  │
  ▼
Decision
  ├─ ALLOW ─► Controller ─► (finally: release permit) ─► Response + headers
  └─ REJECT ─► 429 + Retry-After + headers
  │
  ▼
Metrics / Logs / Events
```

Ordering decision (D6): rate rules and the concurrency permit are evaluated in one atomic Lua script, so a rejection by either consumes nothing and no token refund logic exists. The permit is released after the response completes in a `finally` path (separate call); lease TTL reclaims it if the release never arrives. In local mode the same all-or-nothing contract is implemented with one lock per key.

### 11.3 Algorithm Specifications

| Algorithm | State per key | Behavior | Strengths | Weaknesses |
|---|---|---|---|---|
| Token Bucket | `tokens`, `lastRefill` | Refill at rate `r`, capacity `b`; allow if tokens ≥ cost | Supports bursts, O(1) state | Burst size must be reasoned about |
| Fixed Window | `count`, `windowStart` | Count per aligned window | Simplest, cheap | Up to 2× burst at window boundaries |
| Sliding Window Counter | `prevCount`, `currCount`, `windowStart` | Weighted estimate: `prev × overlap + curr` | Smooths boundary bursts, O(1) | Approximate (bounded error) |
| Sliding Window Log | Timestamp list / sorted set | Exact count of events in last `W` | Exact | O(n) memory per key |

### 11.4 Redis Design

**Key layout** (hash tag keeps all data for a client in one cluster slot):

```
tc:{<clientKeyHash>}:<policy>:<ruleId>        # per-rule state (hash)
tc:{<clientKeyHash>}:<policy>:conc            # concurrency leases (sorted set: leaseId -> expiry)
tc:policies                                   # policy store (hash) + version
tc:policies:channel                           # pub/sub channel for updates
```

**Key design notes:** the prefix `tc` is configurable. The hash tag is the client hash only, so all rules and policies of one client share a slot and can be evaluated by one script. Policy-store and channel keys are untagged. A single extremely hot client maps to one slot (documented limitation, Section 20).

**Atomic multi-rule evaluation (one round trip):** a single Lua script receives all rules for the key. It (1) reads Redis `TIME`, (2) computes the would-be state of each rule, (3) if **all** pass, commits all updates, otherwise commits nothing, (4) returns `allowed`, the binding rule, `remaining`, and `retryAfterMs`. Keys get a TTL slightly longer than the rule window so idle keys expire.

Pseudo-logic:

```lua
local now = redis.call('TIME')            -- authoritative clock
for each rate rule: compute token refill / window rollover / GCRA theoretical arrival time / log trim (per algorithm), tentative new state
concurrency rule: reap expired leases, check cardinality, tentative lease add
if any rule would exceed its limit:
    return {0, bindingRule, remaining, retryAfterMs}   -- no writes
for each rule: persist tentative state, set PEXPIRE (and ZADD the lease)
return {1, tightestRule, remaining, 0}
```

**Concurrency leases:** acquire runs inside the same atomic script as the rate rules (it adds `leaseId` with expiry `now + leaseTTL` after removing expired leases and checking cardinality, and only if every rate rule also passes); release removes the lease; a periodic heartbeat extends long-running leases. A crashed instance's leases expire automatically.

### 11.5 Dynamic Policy Propagation

```
Admin API ──► validate ──► write new PolicySnapshot (version n+1) to policy store
                                   │
                                   ├─► publish "version n+1" on channel
                                   ▼
Instances: receive notification (or poll every T seconds)
        ──► fetch snapshot ──► validate ──► atomic reference swap
        ──► in-flight requests finish under the old snapshot
```

Requirements: immutable snapshots, version numbers to ignore stale/duplicate messages, last-known-good policy retained if the store is unreachable, audit record per change.

### 11.6 Adaptive Controller

Signals: CPU utilization, in-flight requests / queue depth, p95 latency, 5xx rate. Control law (proposal):

```
every T seconds:
    if overloaded(signals):           m = max(m_min, m × β)        # multiplicative decrease, β ≈ 0.7
    else if healthy for K intervals:  m = min(1.0, m + α)          # additive increase, α ≈ 0.05
effective_limit = configured_limit × m
```

Safeguards: hysteresis thresholds, cooldown after each decrease, floor `m_min`, per-policy opt-in, and a kill switch. The controller is **local per instance** in v1 (each instance protects itself); a global controller is future scope.

### 11.7 Failure Modes and Behavior

| Failure | Detection | Behavior |
|---|---|---|
| Redis unreachable / timeout | Client timeout, breaker opens | Per-policy `FAIL_OPEN` (allow) or `FAIL_CLOSED` (reject with 429 + `store-unavailable` problem type); optional degrade to local store with a divided limit |
| Redis slow | Latency above threshold | Breaker opens, use fail mode, half-open probes |
| Clock skew between nodes | N/A | Avoided by using Redis `TIME` |
| Instance crash holding permits | Lease TTL | Leases expire |
| Invalid policy update | Validation | Rejected; last-known-good stays active |
| Key cardinality explosion | Metrics on key count | Bounded local store, TTLs, per-policy key cap, sampling of key labels |

---

## 12. Observability

| Metric | Type | Labels |
|---|---|---|
| `trafficcontrol_requests_total` | Counter | `policy`, `result` (allowed / rejected), `algorithm`, `reason` (rate / concurrency / store), `degraded` |
| `trafficcontrol_rejections_total` | Counter | `policy`, `rule` |
| `trafficcontrol_decision_seconds` | Timer / histogram | `policy`, `store` |
| `trafficcontrol_remaining_ratio` | Gauge / summary | `policy` |
| `trafficcontrol_store_errors_total` | Counter | `store`, `mode` |
| `trafficcontrol_concurrency_inflight` | Gauge | `policy` |
| `trafficcontrol_concurrency_rejections_total` | Counter | `policy` |
| `trafficcontrol_adaptive_multiplier` | Gauge | `policy` |
| `trafficcontrol_policy_version` | Gauge | none |

Rules: never use raw user IDs or IPs as metric labels (unbounded cardinality and privacy risk). "Top throttled clients" is served from logs or a bounded top-K structure, not labels.

Dashboards: request rate, rejection rate by policy, decision latency percentiles, store errors, in-flight concurrency, adaptive multiplier over time.

---

## 13. Security Considerations

| Concern | Mitigation |
|---|---|
| `X-Forwarded-For` spoofing | Only honor forwarded headers from configured trusted proxies. |
| Limit-then-authenticate vs authenticate-then-limit | Make ordering explicit: apply an IP-based limit before authentication (brute-force protection) and identity-based limits after. Document both. |
| Memory exhaustion via unique keys | Bounded local store, key TTLs, optional max-keys-per-policy. |
| Admin API abuse | Authentication and role check required; disabled by default; audit log for every change. |
| PII in Redis and logs | Hash keys, avoid raw identifiers in metrics and logs by default. |
| Redis access | Support authentication and TLS in configuration. |
| Rate limiting as a security claim | Position as traffic control and abuse mitigation, not complete security. |

---

## 14. Testing and Evaluation Plan

### 14.1 Test Levels

| Level | Scope | Tools |
|---|---|---|
| Unit | Algorithms (refill, rollover, boundaries), key resolvers, policy parsing and validation | JUnit 5, Mockito, property-based tests (jqwik) |
| Concurrency | Many threads against one key; no overshoot beyond tolerance; permit release on exceptions | JUnit, jcstress or custom harness |
| Integration | Spring Boot slice tests, HTTP contract (429, headers), Redis behavior | Spring Boot Test, Testcontainers |
| Distributed | 3-5 instances behind a load balancer sharing Redis; global limit enforced | Docker Compose, k6 |
| Failure injection | Redis down, latency injected, network partition | Toxiproxy or Testcontainers network controls |
| Dynamic policy | Policy change propagates to all instances within target time | Integration harness |

### 14.2 Evaluation Experiments

| ID | Question | Method | Metrics |
|---|---|---|---|
| E1 | What is the overhead of the framework? | Compare no limiter vs framework (local) vs Bucket4j directly | Throughput, p50/p95/p99 latency, CPU |
| E2 | What is the added cost of Redis mode? | Local vs Redis, 1/3/5 instances | Latency, Redis ops/s, throughput |
| E3 | How accurate is enforcement? | Send N× the limit concurrently for a fixed window, count allowed requests | Overshoot %, undershoot % per algorithm |
| E4 | Do algorithms behave differently at boundaries? | Boundary-timed bursts for fixed vs sliding vs token bucket | Max requests allowed in any window-length interval |
| E5 | How does the system behave when Redis fails? | Kill/slow Redis mid-test | Error rate, latency, recovery time, fail-open vs fail-closed impact |
| E6 | How fast do policy changes propagate? | Change policy, measure time until all instances enforce it | Propagation latency (p50/p95) |
| E7 | Does concurrency limiting protect slow endpoints? | Slow endpoint with and without concurrency cap | Thread usage, tail latency, error rate |
| E8 | Does adaptive limiting help? | Static vs adaptive under step, ramp and spike load | Goodput, p99 latency, rejection rate, recovery time, oscillation |
| E9 | What does each algorithm cost? | JMH per algorithm (local and Lua), many keys and hot key | ns/op, allocations, Redis memory per key |

### 14.3 Benchmark Methodology Requirements

- Fixed hardware description recorded; warmup periods; multiple repetitions; report mean and variance (or percentiles).
- JMH for microbenchmarks; k6 or Gatling for end-to-end load.
- Baselines: no limiter and Bucket4j (and optionally Resilience4j RateLimiter).
- All scripts and Docker Compose files committed so results are reproducible.
- Report negative results honestly (e.g., if adaptive limiting does not help in some scenarios).

---

## 15. Milestones and Timeline

Plan: **110 days of planned work in 6 phases, plus 2-3 weeks of buffer** (about 4 to 4.5 months of calendar time in total). Evaluation, report and demo preparation run in parallel, inside the academic year.

| Phase | Days | Name | Deliverables | Exit criteria |
|---|---|---|---|---|
| 1 | 1-14 | Core Engine | Repo, CI, core API, `RateLimitAlgorithm` SPI, token bucket, fixed window, local store, fake clock, shared algorithm conformance suite | Unit and concurrency tests green; first JMH numbers |
| 2 | 15-35 | Spring Integration | Starter, `@RateLimit`, interceptor, key resolvers, 429 `problem+json` + headers, demo v0. In parallel: sliding window counter, GCRA, local concurrency semaphore | MVP: getting-started works in 5 minutes |
| 3 | 36-56 | Distributed Mode | Redis store, atomic Lua (token bucket, fixed, sliding counter, GCRA), hash tags, Redis `TIME`, fail modes, circuit breaker, multi-instance and fault tests, bench harness skeleton | Global limit holds on 3+ instances; E2, E3, E5 first results. **Go/no-go checkpoint at day 56.** |
| 4 | 57-70 | Policy Engine | Multi-rule YAML, plans, path rules, distributed concurrency in the same Lua script, live policy updates (snapshots, pub/sub, atomic swap), minimal secured admin API | v0.5 features complete; E6, E7 results |
| 5 | 71-91 | Observability + Adaptive | Micrometer metrics, logs, dashboards, adaptive controller, sliding window log (local + Lua) | Metrics visible; E8 results (positive or negative) |
| 6 | 92-110 | Benchmark + Polish | Full evaluation suite E1-E9, security review, docs, final demo, v1.0 tag | All Must items verified |

**Buffer:** 2-3 weeks, mostly after Phase 3 (highest technical risk) with the remainder before submission.

**Algorithm schedule:** token bucket and fixed window (Phase 1), sliding window counter and GCRA (Phase 2 local, Phase 3 Redis), sliding window log (Phase 5). All five share one SPI and one conformance suite, which keeps the marginal cost of each extra algorithm low.

**Cut order if behind (pre-agreed):** 1. adaptive refinements, 2. admin UI and dashboard polish, 3. distributed concurrency, 4. sliding window log, 5. overflow strategies. Correctness tests and the evaluation suite are never cut.

**Feasibility note:** the Must list is only realistic with three people working in parallel. With two people, apply the cut order at the day-56 checkpoint rather than later.

### 15.1 Suggested Team Split (2-3 Members)

| Role | Responsibilities |
|---|---|
| **A: Core and algorithms** | Core API, algorithms, local store, concurrency tests, adaptive controller |
| **B: Distributed and infrastructure** | Redis store, Lua scripts, failure handling, policy propagation, Docker/CI |
| **C: Integration and evaluation** | Spring starter, YAML/annotations, metrics and dashboards, benchmark suite, documentation |

With a team of two, merge B and C and move dashboards and documentation to the "Could" category.

---

## 16. Risks and Mitigations

| ID | Risk | Likelihood | Impact | Mitigation |
|---|---|---|---|---|
| R1 | Reviewers see it as "another rate limiter" | High | High | Position around policy model, distributed correctness and measured evaluation; keep the verified competitor table and benchmark results front and center. |
| R2 | Scope creep | High | High | MoSCoW rule in Section 6.2; weekly scope review. |
| R3 | Race conditions / incorrect distributed behavior | Medium | High | Atomic Lua scripts, concurrency tests from Phase 1, multi-instance tests early. |
| R4 | Redis becomes a bottleneck or single point of failure | Medium | Medium | Single round trip design, breaker, fail modes, optional hybrid mode. |
| R5 | Adaptive control oscillates or shows no benefit | Medium | Medium | Hysteresis and cooldown, per-policy opt-in, treat as research; report results honestly. |
| R6 | Benchmarking is noisy or unfair | Medium | Medium | Warmup, repetitions, fixed environment, scripted runs, compare against baselines under identical config. |
| R7 | Team member unavailable | Medium | Medium | Modular ownership with cross-review; documented interfaces. |
| R8 | Spring / dependency version drift | Low | Medium | Pin versions, CI matrix for one or two Boot versions. |
| R9 | Competitor claims turn out wrong | Medium | Medium | Re-verify in week 1 and before submission. |
| R10 | 110-day plan is too tight for the full Must list (5 algorithms, distributed concurrency, live policies) | High | High | Algorithms share one SPI and one conformance suite; go/no-go checkpoint at day 56; pre-agreed cut order (Section 15); 2-3 weeks buffer. |
| R11 | One Lua script with five algorithms plus concurrency becomes hard to maintain or slow | Medium | Medium | Generate script from per-algorithm fragments, unit-test each fragment, benchmark E9. |

---

## 17. Success Metrics and Acceptance Criteria

### 17.1 Definition of Done (Project Level)

The project is complete when all of the following are true:

- [ ] All **Must** functional requirements are implemented and covered by automated tests.
- [ ] A developer can follow the getting-started guide and protect an endpoint in under 5 minutes (validated by at least 2 people outside the team).
- [ ] Global limits are enforced correctly with 3+ instances and Redis (E3 evidence).
- [ ] Redis failure behavior matches configured FAIL_OPEN / FAIL_CLOSED (E5 evidence).
- [ ] Benchmark suite is scripted and reproducible; results for E1-E4 are in the report.
- [ ] Metrics and a sample dashboard are available.
- [ ] Documentation covers installation, configuration reference, SPIs, and architecture.
- [ ] Demo application showcases all major features.
- [ ] Verified competitor comparison is included in the final report.

### 17.2 Quantitative Targets (To Be Validated)

| Metric | Target |
|---|---|
| Engine + local store decision p99 (JMH) | < 100 µs |
| Starter end-to-end overhead p99 | < 1 ms |
| Redis mode added p99 | < 5 ms (same network) |
| Enforcement overshoot (token bucket, fixed window, atomic) | 0 requests beyond limit |
| Sliding window counter error | Within documented bound (measure and report) |
| Policy propagation time | Within a few seconds (measure and report p95) |
| Core + algorithms test coverage | ≥ 85% line, PIT mutation score target 70% |

---

## 18. Deliverables

1. Source code (multi-module Maven/Gradle project) in a public or institutional repository.
2. Core library (framework-independent).
3. Spring Boot starter.
4. Redis store with Lua scripts.
5. Policy engine with YAML and annotation support.
6. Dynamic policy management (admin API).
7. Concurrency limiter.
8. Adaptive controller (research component).
9. Metrics integration and Grafana dashboard.
10. Demo application.
11. Benchmark suite with Docker Compose and raw result data.
12. Documentation site or README set (quick start, configuration reference, architecture, SPI guide).
13. Final project report, presentation and demo video.

---

## 19. Open Questions and Decisions to Confirm

| ID | Question | Recommended default |
|---|---|---|
| D1 | Implement algorithms from scratch or wrap Bucket4j? | Implement your own (academic depth) and use Bucket4j as the **baseline** in benchmarks and an optional adapter. Confirm with faculty. |
| D2 | Servlet (Spring MVC) only, or WebFlux too? | MVC first; WebFlux is stretch. |
| D3 | Maven or Gradle? | Maven (widely used in college and Java ecosystems). |
| D4 | Policy store: Redis or database? | Redis first (already required), abstract behind `PolicyProvider`. |
| D5 | Is adaptive limiting local or global? | Local per instance for v1. |
| D6 | Refund rate token if concurrency permit fails? | **Resolved:** not needed. Rate and concurrency are evaluated in one atomic Lua script. |
| D7 | Which rule's headers are shown when multiple rules apply? | Most restrictive (lowest remaining ratio). |
| D8 | Open-source license? | Apache 2.0. |
| D9 | Publish to Maven Central? | Optional; GitHub Packages or JitPack is enough for submission. |
| D10 | Final project name | **Resolved:** `trafficcontrol`; verify availability in week 1. |
| D11 | Which `X-RateLimit-Reset` format? | **Resolved:** Unix epoch seconds by default (GitHub convention, matches the example above); `Retry-After` is delta-seconds; IETF-style fields optional via `headers.style`. |
| D12 | Status code for FAIL_CLOSED? | **Resolved:** 429, configurable, with distinguishing problem `type`. |
| D13 | Which algorithms? | **Resolved:** token bucket, fixed window, sliding window counter, sliding window log, GCRA (leaky-bucket-as-meter); leaky-bucket-as-queue is an overflow strategy. |

---

## 20. Limitations (For the Report)

1. Rate limiting does not stop volumetric DDoS attacks; those need network-level defenses.
2. Distributed enforcement depends on the availability and latency of the shared store.
3. IP-based identification is unreliable behind proxies and NAT without correct proxy configuration.
4. Sliding window counter is an approximation with bounded error.
5. Adaptive limiting needs careful tuning and may not help under every workload.
6. Initial support is limited to Java/Spring Boot (Servlet stack).
7. Local adaptive control does not coordinate across instances.

---

## 21. Future Scope

- WebFlux and reactive support
- gRPC and GraphQL support
- Multi-region and geo-distributed limiting
- Global (cross-instance) adaptive controller
- Cost-aware and token-based limits for LLM/AI APIs
- Organization-level quotas and billing integration
- Admin web UI and policy simulation ("what would have been blocked")
- Kubernetes operator / sidecar deployment
- Gateway integration (publish policies to Envoy or Spring Cloud Gateway)

---

## 22. Glossary

| Term | Meaning |
|---|---|
| Rate limiting | Restricting how many requests a client can make in a time period |
| Concurrency limiting | Restricting how many requests execute at the same time |
| Token bucket | Algorithm where tokens refill over time and each request consumes tokens |
| Sliding window | Windowing approach that avoids boundary bursts of fixed windows |
| Policy | Named set of rules, key strategy and behavior applied to endpoints |
| SPI | Service Provider Interface, an extension point for custom implementations |
| Fail-open / fail-closed | Allow or reject requests when the limiter's backing store is unavailable |
| AIMD | Additive Increase, Multiplicative Decrease control law |
| Hash tag | Redis Cluster `{...}` syntax forcing keys into the same hash slot |
| Overshoot | Requests allowed beyond the configured limit |

---

## 23. References to Verify and Read (week 1)

- Bucket4j documentation and cluster integration docs
- Resilience4j RateLimiter, Bulkhead and Spring Boot integration docs
- Spring Cloud Gateway `RequestRateLimiter` and Redis rate limiter implementation
- Alibaba Sentinel flow control and cluster flow control docs
- Envoy global rate limiting documentation
- Redis documentation: Lua scripting, `TIME`, Cluster hash tags, key expiry
- IETF draft on standardized RateLimit header fields (compare with `X-RateLimit-*` convention)
- Literature on rate-limiting algorithms (token bucket, leaky bucket, sliding window) and congestion/adaptive control (AIMD)
- Netflix concurrency-limits library (adaptive concurrency limits) as related work for the adaptive component
