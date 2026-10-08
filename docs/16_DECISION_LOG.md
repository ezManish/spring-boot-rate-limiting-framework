# 16 — Decision Log (ADR Register)

"Owner decision" = made by the project owner. "Adjusted" = owner's choice kept, with a refinement. "Recommended" = proposed by the assistant, accepted unless changed.

| ID | Decision | Source | Rationale | Alternatives | Consequence |
|---|---|---|---|---|---|
| ADR-001 | Framework-independent core; integrate via SPIs | Deck | Testability, reuse | Spring-coupled | More modules |
| ADR-002 | One atomic Lua script for **all rate rules AND the concurrency permit**; commit only if all pass | Owner decision | No partial loss, no refund races, one round trip | Rate then concurrency with refund; MULTI/EXEC | Script complexity; mitigated by per-algorithm fragments |
| ADR-003 | Redis `TIME` as the clock | Deck | No skew | App clocks | — |
| ADR-004 | Hash tags **Must**; key layout `tc:{<clientHash>}:<policy>:<ruleId>` | Owner decision (prefix `rc` → `tc`: adjusted) | Multi-key script needs one slot; client-only tag also allows cross-policy atomicity | Tag per policy+client | Hot client = hot slot (documented) |
| ADR-005 | Per-policy FAIL_OPEN / FAIL_CLOSED | Deck | Route risk differs | Global | — |
| ADR-006 | FAIL_CLOSED returns **429** (configurable), with problem `type` `store-unavailable` and `Retry-After` | Owner decision (adjusted: distinguishing type added) | Uniform client handling; type lets clients/dashboards separate outage from quota | 503 | Slightly less semantically pure; mitigated by problem type + metric |
| ADR-007 | Circuit breaker on Redis | Deck | No cascading latency | Timeout only | Tuning |
| ADR-008 | Versioned snapshots, pub/sub, atomic swap, poll fallback | Deck | Live updates | Restart | — |
| ADR-009 | Lease TTL for permits; **distributed concurrency Must** | Deck + owner decision | Crash safety | Local only | Cut-order item 3 |
| ADR-010 | Bucket4j = benchmark baseline + optional adapter only | Deck | Fair comparison | Build on it | Own implementation |
| ADR-011 | Stack: Java 21, Spring Boot 3.5.x (see ADR-025), Maven, Redis 6.2+, Micrometer/Prometheus, JUnit 5, Testcontainers | Deck | — | Gradle | — |
| ADR-012 | **All common algorithms**: token bucket, fixed window, sliding window counter, sliding window log, GCRA (= leaky-bucket-as-meter). Leaky-bucket-as-queue is an overflow strategy | Owner decision (adjusted: "every algorithm" defined as these five + shaping) | Leaky bucket meter is mathematically a GCRA/token-bucket variant; GCRA is the efficient form; queueing is shaping | Only token bucket | One SPI + one conformance suite keep cost low; sliding log is cut-order item 4 |
| ADR-013 | 110-day plan + 2–3 weeks buffer; go/no-go checkpoint at day 56 | Owner decision (adjusted: checkpoint added) | Scope of Musts is large for 110 days | 10-month plan | Needs 3 people or earlier cuts |
| ADR-014 | Cut order: adaptive → admin UI/dashboards → distributed concurrency → sliding log → overflow strategies | Owner decision | Protects correctness/evaluation | — | — |
| ADR-015 | Project name `trafficcontrol`; artifacts `trafficcontrol-*`; YAML prefix `trafficcontrol`; groupId `io.github.ezmanish`, Java package root `io.github.ezmanish.trafficcontrol` | Owner decision | — | `ratecontrol` | Verify the `io.github.ezmanish` namespace in the Central Portal in week 1 |
| ADR-016 | Local engine decision p99 < 100 µs (JMH); starter end-to-end p99 < 1 ms; Redis added p99 < 5 ms | Owner decision (adjusted: two tiers) | Microbench and HTTP-level numbers differ; only JMH can reliably show 100 µs | One number | Targets validated after first benchmark run |
| ADR-017 | Core + algorithms line coverage ≥ 85%; PIT mutation score target 70% on core | Owner decision (PIT: recommended) | Coverage alone can be gamed | Coverage only | PIT adds CI time |
| ADR-018 | Reject body: RFC 9457 `application/problem+json` | Owner decision | Standard error format | Custom JSON | — |
| ADR-019 | Headers: `X-RateLimit-Limit/Remaining/Reset`; **Reset = Unix epoch seconds**; `Retry-After` = delta-seconds; optional `headers.style` IETF/BOTH | Recommended (owner unsure) | Epoch is GitHub's de facto convention and matches PRD example; IETF draft (still an Internet-Draft; defines `RateLimit` + `RateLimit-Policy` fields) is optional until it becomes an RFC | Delta-seconds reset; IETF only | Two reset conventions exist in the wild; documented |
| ADR-020 | Identity hashed in keys/logs; never a metric tag | Recommended | Privacy, cardinality | Raw | `key_hash` for debugging |
| ADR-021 | Servlet/MVC only in v1 | PRD | Scope | WebFlux | Future work |
| ADR-022 | Adaptive: local per instance, AIMD, default OFF, kill switch | PRD | Safety | Global controller | Future work |
| ADR-023 | Micro-token integer arithmetic in Lua | Recommended | No float drift | Floats | — |
| ADR-024 | Apache 2.0 license | PRD default | Common for libraries | MIT | — |
| ADR-025 | **Spring Boot 3.5.x** (final 3.x line) | **Owner decision** (supersedes my earlier recommendation of 4.x) | Matches the deck and the owner's stack. Risk accepted and recorded: Boot 3.5 left OSS support on 2026-06-30, so no further free fixes for Spring Boot/Framework/Security | Boot 4.1 | Mitigations: Spring types only in the starter; public APIs and `AutoConfiguration.imports` only; non-blocking CI job building the starter against Boot 4.1; BOM overrides allowed for third-party security fixes; documented suppression policy for unfixable Spring CVEs; limitation stated in the final report; Boot 4 migration is future work |
| ADR-026 | Minimal `problem+json` body: `type` (URN), `title`, `status`, generic `detail`, `retryAfterSeconds`; `policy`/`rule`/`instance` opt-in | Recommended (reviewer input) | No internal disclosure | Verbose body | Harder client-side debugging; mitigated by metrics/logs and opt-in |
| ADR-027 | `on-missing-key` default `FALLBACK_IP`; options ANONYMOUS, SKIP, REJECT (429 `identity-required`) | Recommended | Never fail open silently on missing identity | Skip by default | IP fallback may over-group NAT users |
| ADR-028 | Lease semantics: UUID v4 ids, release best-effort off the request path with one retry then TTL, heartbeat Should, `wait` by client polling | Recommended | Response latency unaffected; bounded capacity loss | Synchronous release | Temporary capacity loss on release failure |
| ADR-029 | Local store: Caffeine in `store-local`, 100k keys, W-TinyLFU + idle TTL, pinned permit keys, log cap | Recommended | Prevents unbounded growth | `ConcurrentHashMap` | Eviction resets an active key (documented) |
| ADR-030 | Exact arithmetic: ms units for token bucket/windows, µs for GCRA with ceil(T); integer-safety validation | Recommended (supersedes ADR-023) | Cross-implementation equality | Doubles | Config limits (`R×W ≤ 4×10^15`) |
| ADR-031 | Versioned Lua contract (`SCRIPT_VERSION`, 8-integer result, test build with injected clock) | Recommended | Deterministic tests | Ad-hoc script | Contract bump needed for changes |
| ADR-032 | GitHub Actions CI with hard quality gates (coverage, PIT, vuln scan, architecture) | Recommended (reviewer input) | Quality from day 1 | Manual checks | CI maintenance |
| ADR-033 | SemVer, strict YAML, stable Redis encoding within a major, `schemaVersion` on snapshots | Recommended | Safe rolling upgrades | Loose config | Typos fail startup |
| ADR-034 | Lettuce as the Redis client in `store-redis` | Recommended [assumption] | Boot default, async, cluster-aware | Jedis | Revisit if benchmarks disagree |
| ADR-035 | Admin API: off by default, security required, `If-Match` optimistic concurrency, audit, 422 validation | Recommended | Safe live changes | Open endpoint | More code (cuttable per cut order) |
| ADR-036 | Adaptive defaults (T=5 s, β=0.7, α=0.05, hysteresis 2/6, cooldown 15 s) are hypotheses to tune in E8 | Recommended | Deterministic, testable | Unspecified | May change after E8 |
| ADR-037 | Final `RateLimitKey` enum: USER, IP, API_KEY, JWT_CLAIM, TENANT, ENDPOINT, GLOBAL, COMPOSITE, CUSTOM. `PLAN` is **not** a key type; plans select limits via a `PlanResolver` SPI. Core SPI is framework-neutral (`RateLimitContext`, no Servlet types) | Recommended | A plan is an attribute that selects limits, not an identity; a PLAN key would silently create one bucket per plan | PLAN as key type | A shared per-plan cap needs a CUSTOM resolver |

## Template
```
### ADR-XXX Title
Date: • Status: • Deciders:
Context / Decision / Alternatives / Consequences
```

## Open Questions (after review)
Resolved by ADR-025..036: Lua contract, algorithm semantics, key-resolution failure, config validation, admin API contract, lease/release semantics, local store limits, CI/CD, dependency policy.
Still open (can wait): 1. Exact Spring Boot 3.5.x patch (latest on Maven Central in week 1) and the Boot 4 migration plan after submission. 2. Maven Central vs GitHub Packages (Central recommended at v1.0). 3. Adaptive tuning after E8. 4. Dashboard polish. 5. jqwik (flagged assumption). 6. Extended deployment documentation.
