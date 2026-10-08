# 02 — Technical Requirements Document (TRD)

## 1. Platform
| Item | Choice (from deck) |
|---|---|
| Language | Java 21 |
| Framework | Spring Boot 3.5.x (final OSS patch; exact version frozen in `22_...`) |
| Build | Maven (multi-module) |
| Distributed state | Redis 6+ ; Redis `TIME` as clock; Cluster hash tags |
| Observability | Micrometer → Prometheus |
| Testing | JUnit 5, Testcontainers |
| Baseline | Bucket4j (benchmark + optional adapter only) |

## 2. Module Layout
```
traffic-control/   # artifacts prefixed trafficcontrol-, groupId io.github.ezmanish
├── core                  # no Spring, no Redis: policy model, algorithms, SPIs
├── store-local           # in-memory RateLimitStore
├── store-redis           # Redis + Lua RateLimitStore
├── spring-boot-starter   # annotations, interceptor, auto-config, resolvers
├── metrics               # Micrometer binder
├── bench                 # JMH / load harness, Bucket4j baseline
└── demo                  # sample Spring Boot app
```

## 3. Core Technical Requirements
**TR-01 Algorithms.** Token bucket, fixed window, sliding window counter, sliding window log, GCRA (leaky-bucket-as-meter), behind `RateLimitAlgorithm`. Selectable per rule; windows parsed from strings (`"1m"`, `"30s"`). All pass one shared conformance suite.
**TR-02 Atomic multi-rule.** One Lua script evaluates all rate rules **and the concurrency permit**: compute would-be state for each, commit only if every rule passes; otherwise write nothing. No refund logic.
**TR-03 Clock.** All Redis time decisions use `redis.call('TIME')` inside the script, never app-instance clocks.
**TR-04 Cluster safety.** All keys touched by one evaluation share a hash tag, e.g. `tc:{<clientHash>}:<policy>:<ruleId>`, so all of a client's keys map to one slot (Must).
**TR-05 Concurrency limiter (distributed, Must).** Permit acquired inside the decision script (ZSET with lease expiry); released by a separate `ZREM` in `finally`; TTL reclaims permits after crashes; optional heartbeat for long requests.
**TR-06 Failure modes.** Per-policy `FAIL_OPEN` (allow) or `FAIL_CLOSED` (reject with 429 by default, status configurable; problem type `store-unavailable`). Circuit breaker on Redis calls (closed/open/half-open) with configurable thresholds.
**TR-07 Policy snapshots.** Immutable, versioned `PolicySnapshot`; update published via Redis pub/sub; instances atomically swap a volatile reference; old version retained until in-flight requests finish.
**TR-08 SPIs.** `RateLimitStore`, `RateLimitKeyResolver`, `PlanResolver`, `PolicyProvider`, `Clock`, `DecisionListener`, `FailureStrategy`.
**TR-09 HTTP.** `HandlerInterceptor` resolves annotation/policy → key → engine decision. On reject: 429 + `Retry-After` (delta-seconds) + `application/problem+json` body. Headers: `X-RateLimit-Limit`, `X-RateLimit-Remaining`, `X-RateLimit-Reset` (Unix epoch seconds); `headers.style` LEGACY (default) / IETF / BOTH.
**TR-10 Observability.** Counter/timer per decision, tagged policy, outcome, store, failure-mode; log line per decision (sampled at high volume).
**TR-12 Admin API (Should).** Secured REST API for policy list/get/put/delete/rollback with audit log; disabled by default; see `05_API_SPECIFICATION.md §5`.
**TR-11 Adaptive controller.** Reads load signals; scales effective limit within `[min, max]` bounds; must be disable-able and evaluated against static limits.

## 4. Configuration (illustrative; full schema in `06_POLICY_SPECIFICATION.md`)
```yaml
trafficcontrol:
  store: redis
  fail-mode: FAIL_OPEN
  fail-closed-status: 429
  policies:
    login:
      key: IP
      fail-mode: FAIL_CLOSED
      rules: [{ requests: 5, window: 1m }]
    products:
      key: USER
      rules:
        - { algorithm: GCRA, requests: 10, window: 1s }
        - { algorithm: SLIDING_WINDOW_COUNTER, requests: 1000, window: 1h }
      concurrency: { max: 10, lease-ttl: 30s }
```

## 5. Compatibility Matrix
| Component | Minimum | Tested |
|---|---|---|
| JDK | 21 | 21 |
| Spring Boot | 3.5.0 | latest 3.5.x |
| Redis | 6.0 | 6, 7, Cluster 3-node |

## 6. Performance Constraints [Assumption – finalize post-benchmark]
- Single Redis round trip per decision (one Lua call).
- No allocation hot-spots in core path. Targets: engine + local store decision p99 < 100 µs (JMH); starter end-to-end overhead p99 < 1 ms; Redis mode added p99 < 5 ms.

## 7. Security Constraints
See `13_SECURITY.md`. Keys hashed or length-bounded; no PII in metric tags.

## 8. Build & CI [Assumption]
GitHub Actions: build, unit tests, Testcontainers integration, benchmark smoke on tag.
