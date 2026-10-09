# 14 — Implementation Plan

**110 days of planned work, 6 phases, plus 2–3 weeks buffer** (≈ 4–4.5 months calendar). Mirrors `01_PRD.md §15`.

## Cut order if behind (pre-agreed)
1. Adaptive refinements 2. Admin UI / dashboard polish 3. Distributed concurrency 4. Sliding window log 5. Overflow strategies.
**Never cut:** correctness tests, atomicity tests, the evaluation suite.

## Timeline
| Phase | Days | Name | Output |
|---|---|---|---|
| 1 | 1–14 | Core Engine | Core API, Algorithm SPI, token bucket, fixed window, local store, conformance suite |
| 2 | 15–35 | Spring Integration | Starter, `@RateLimit`, key resolvers, 429 problem+json + headers; sliding window counter + GCRA (local); local concurrency |
| 3 | 36–56 | Distributed Mode | Redis + atomic Lua (4 algorithms), hash tags, fail modes, breaker, multi-instance + fault tests |
| 4 | 57–70 | Policy Engine | Multi-rule YAML, plans, distributed concurrency in the same script, live updates, minimal admin API |
| 5 | 71–91 | Observability + Adaptive | Metrics, logs, dashboards, adaptive controller, sliding window log |
| 6 | 92–110 | Benchmark + Polish | E1–E9, security review, docs, demo, v1.0 |

## Phase Detail
### Phase 1 (D1–14)
**Days 1–3 repository bootstrap (everything in `18` and `22`):** verify the namespace and name, parent POM with frozen versions, module skeleton and dependency-direction rules, Spotless, `ci.yml` with the quality gates (they start failing the build from day 1), README/LICENSE/CONTRIBUTING, issue/PR templates.
Maven multi-module skeleton + CI; core model; `RateLimitAlgorithm` and `RateLimitStore` SPIs; token bucket and fixed window; local store; fake `Clock`; **shared conformance suite** every algorithm must pass (limit respected, refill/rollover, boundaries, concurrency, no overshoot). Tests TC-001..006, TC-110..114.
**Exit:** core has no Spring/Redis deps (enforced by build rule).

### Phase 2 (D15–35)
Starter, auto-config, annotations, interceptor, key resolvers (USER/IP/API key/JWT/composite + SPI), problem+json, headers, trusted-proxy handling, demo v0. In parallel (core engineer): sliding window counter, GCRA, local concurrency semaphore.
**Also in Phase 2:** configuration validation V-001..V-030 and the failure report (TC-150..155), key-resolution behavior (TC-160..163), final HTTP contract (TC-120..126), `QUICKSTART.md` validated end to end.
**Exit:** US-01, 02, 03, 05, 11, 12; onboarding < 5 min; TC-010..018.

### Phase 3 (D36–56)
`store-redis`; Lua fragments for token bucket, fixed window, sliding counter, GCRA composed into one script; Redis `TIME`; hash tags; NOSCRIPT handling; breaker; FAIL_OPEN/CLOSED (429); Testcontainers incl. Cluster; multi-instance tests; Toxiproxy; bench harness skeleton.
**Exit:** TC-020..024, 030..033, 040..042, 060..065. **Go/no-go checkpoint at day 56:** if behind, apply cut order now.

### Buffer (~1 week here)
Absorbs Lua/Cluster debugging.

### Phase 4 (D57–70)
YAML policies, path rules, plans, startup validation; concurrency acquired in the same Lua script; live updates (snapshot, pub/sub, poll fallback, atomic swap); minimal secured admin API + audit.
**Exit:** TC-014..016, 050..054, 070..075; E6, E7 first results.

### Phase 5 (D71–91) [COMPLETED]
Micrometer binder, logs, health indicator, dashboards; adaptive controller with kill switch; sliding window log local + Lua.
**Exit:** TC-080..084, 090..093, 115. All implemented and verified.

### Phase 6 (D92–110) [COMPLETED]
Run E1–E9, benchmarks (JMH), security tests SEC-01..05, demo application showcase, docs, evaluation report (24_EVALUATION_REPORT.md). v1.0 Release Candidate ready.

## Team Split (3 people; with 2, merge B+C and cut earlier)
| Role | Primary | Secondary |
|---|---|---|
| A: Core & algorithms | core, algorithms, conformance suite, adaptive | benchmarks |
| B: Distributed & infra | Redis store, Lua, breaker, policy propagation, CI | fault tests |
| C: Integration & evaluation | starter, YAML, metrics, benchmarks, docs, demo | security tests |

## Milestones
M1 D14 core demo • M2 D35 MVP annotation demo • M3 D56 multi-instance demo (go/no-go) • M4 D70 live policy + concurrency demo • M5 D91 dashboard demo • M6 D110 final.

## Risks to Schedule
Five algorithms in one Lua script (mitigation: fragments + conformance suite), Cluster debugging, adaptive tuning, benchmark noise.

## Definition of Done (per task)
Reviewed • tests added (mapped to IDs) • docs updated • metrics/logs considered • decision-log entry if design changed.

## Spec Documents Added After Review
`18_CI_CD`, `19_ALGORITHM_SPECIFICATION`, `20_REDIS_LUA_CONTRACT`, `21_ADAPTIVE_SPECIFICATION`, `22_REPOSITORY_AND_DEPENDENCY_POLICY`, `23_OPERATIONS_RUNBOOK`, `QUICKSTART.md`. Implementation must follow 19 and 20 exactly; any deviation needs a contract version bump and a decision-log entry.
