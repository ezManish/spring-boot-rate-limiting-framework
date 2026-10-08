# 03 — Architecture

## 1. Principle
**Framework-independent core, pluggable through SPIs.** The core knows nothing about Spring or Redis.

## 2. Request Flow
```
Client → Interceptor → Policy + Key Resolver → Adaptive Controller → Rate Limit Engine
                                                                         │
                          ┌──────────────────────────────────────────────┤
                          ▼                                              ▼
              ALLOW → controller + headers                REJECT → 429 + Retry-After
                          └────────── Metrics and logs for every decision ──────────┘
```
1. **Interceptor** (starter) catches the request, finds `@RateLimit` / `@RateLimitPolicy`.
2. **Policy + Key Resolver** builds the effective policy and identity key (USER / IP / tenant / plan).
3. **Adaptive Controller** optionally adjusts effective limits from load signals.
4. **Rate Limit Engine** (core) evaluates all rate rules and the concurrency rule through `RateLimitAlgorithm` implementations and the atomic `RateLimitStore` SPI.
5. **Decision** → allow (continue, add headers) or reject (429 + `Retry-After`).
6. **Metrics/logs** emitted for every decision.

## 3. Modules
| Module | Responsibility |
|---|---|
| core | Policy model, all algorithms, engine, SPIs; no Spring, no Redis |
| store-local | In-memory `RateLimitStore` |
| store-redis | Redis `RateLimitStore`, Lua scripts, circuit breaker, pub/sub |
| spring-boot-starter | Annotations, interceptor, auto-config, resolvers, YAML binding, secured admin API (off by default) |
| metrics | Micrometer binder |
| bench | Benchmarks vs Bucket4j / static limits |
| demo | Showcase app |

## 4. Atomic Redis Lua
One script evaluates all rules: compute the would-be state for each rule, **commit only if every rule passes, otherwise write nothing** — no partial token loss. Time from Redis `TIME`; keys share a hash tag for Cluster.

```
for each rate rule: load state, compute per algorithm (token refill / GCRA TAT / window rollover / log trim)
concurrency rule: reap expired leases, check cardinality
if any fails: return {DENY, reason, retry_after_ms, remaining...}   -- no writes
else: write all new states (+ lease) with TTL; return {ALLOW, remaining...}
```

## 5. Resilience
- Per-policy FAIL_OPEN / FAIL_CLOSED.
- Circuit breaker on Redis (open → apply failure mode immediately).
- Versioned policy snapshots via pub/sub, atomic swap.
- Lease TTL for concurrency permits.

## 6. Component Diagram (text)
```
[Spring MVC app]
   └─ starter ── interceptor ── RateLimitKeyResolver / PolicyRegistry
                    │
                  core: Engine ── RateLimitStore (SPI)
                                   ├─ store-local
                                   └─ store-redis ──► Redis (Lua, TIME, pub/sub)
                    └─ metrics ──► Micrometer ──► Prometheus ──► dashboards
```

## 7. Key Design Decisions
See `16_DECISION_LOG.md` (ADR-001…ADR-024).

## 8. Quality Attributes
| Attribute | Approach |
|---|---|
| Correctness | Single atomic script, Redis clock |
| Availability | Fail mode + circuit breaker |
| Extensibility | SPIs |
| Operability | Metrics, logs, live policy swap |
| Testability | Core isolated; Testcontainers for Redis |
