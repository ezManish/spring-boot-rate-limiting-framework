# 00 — Project Charter

**Project:** Rate Limiting Framework for Spring Boot (API Traffic Control Framework), codename `trafficcontrol`
**Source:** The 6-slide deck and the full PRD (`01_PRD.md`). Decisions are recorded in `16_DECISION_LOG.md`. Items tagged **[Assumption]** need team/faculty confirmation.

## 1. Vision
Adding rate limiting is not just counting requests. Developers must decide who is limited, where state lives, and how endpoints differ. This project delivers a Spring Boot framework that handles all of it through annotations and configuration.

**Success statement (from deck):** *Protect an endpoint in under 5 minutes, with correct global limits across many instances.*

## 2. Problem
Without the framework a developer hand-writes, per endpoint: the algorithm, Redis/Lua calls, header construction, and key/identity logic (slide 2: "huge no. of lines"). Open questions each team re-solves:
- Who should be limited?
- Where should the limit be stored?
- How should different endpoints have different limits?

## 3. Solution
- A single policy model: **rate + concurrency + identity + endpoint + plan**.
- Developer experience: `@RateLimit(requests = 100, window = "1m", key = USER)` and `@RateLimitPolicy("login")` (named YAML policy, e.g. 5/min per IP, FAIL_CLOSED).
- ~10 lines of code vs. a hand-rolled implementation; policy declared once, engine handles the rest.

## 4. Positioning
"We do not invent rate limiting. We unify, prove and measure it."
| Existing | Gap | Our addition |
|---|---|---|
| Bucket4j | Low-level; policies, HTTP, observability left to you | Declarative policy model with HTTP + metrics built in |
| Resilience4j | Per-instance limiter | Distributed, atomic multi-rule engine |
| Spring Cloud Gateway | Gateway-level, coarse identity | In-app identity: user, plan, tenant |

Differentiators: multi-rule atomic (all-or-nothing), rate + concurrency, live policy updates (no redeploy), adaptive limits (tested vs static).

## 5. Scope
**In scope:** core engine with all common algorithms (token bucket, fixed window, sliding window counter, sliding window log, GCRA/leaky-bucket-as-meter), local + Redis stores, Spring Boot starter, annotations + YAML policies, multi-rule atomic evaluation, concurrency limiter, fail-open/closed, circuit breaker, live policy updates, Micrometer/Prometheus metrics, adaptive controller, benchmark vs Bucket4j, demo app, docs.
**Out of scope [Assumption]:** API gateway product, WAF/DDoS protection at network layer, non-JVM clients, a hosted management UI, billing/plan management itself.

## 6. Team & Timeline
- Team of 2–3 **[from project notes]**; academic year available, but delivery condensed to a **3–4 month (≈110-day) plan** with 2–3 weeks buffer.
- Phases: Core Engine (D1–14) → Spring Integration (D15–35) → Distributed Mode (D36–56) → Policy Engine (D57–70) → Observability + Adaptive (D71–91) → Benchmark + Polish (D92–110).

## 7. Roles [Assumption]
| Role | Focus |
|---|---|
| Engineer A (lead) | core, Redis/Lua, resilience |
| Engineer B | Spring starter, policy engine, YAML/config |
| Engineer C (or shared) | metrics, benchmarks, docs, demo |

## 8. Success Metrics
1. Protect an endpoint in < 5 min (timed onboarding test).
2. Global limit correctness across N instances (no over-admission beyond defined tolerance).
3. Multi-rule atomicity: zero partial token loss under concurrency tests.
4. Measured overhead vs Bucket4j baseline published in benchmark report.
5. Adaptive limiting shown better than static on defined scenarios.

## 9. Constraints
Java 21, Spring Boot 3.5.x (owner decision; out of OSS support, see ADR-025), Maven, Redis 6.2+. Bucket4j only as benchmark baseline / optional adapter — **not a dependency**.

## 10. Top Risks
| Risk | Mitigation |
|---|---|
| Schedule slip (large Must list for 110 days) | 2–3 weeks buffer; go/no-go at day 56; cut order: adaptive → admin UI/dashboards → distributed concurrency → sliding log → overflow strategies; never correctness/evaluation |
| Redis Cluster slot errors in Lua | Hash tags; Testcontainers cluster tests |
| Clock skew across instances | Redis `TIME` as single clock |
| Scope creep | Charter scope list + decision log |
| One Lua script for 5 algorithms + concurrency | Per-algorithm fragments, shared conformance suite, E9 benchmark |

## 11. Governance
Weekly review; decisions recorded in `16_DECISION_LOG.md`; acceptance via `15_ACCEPTANCE_CRITERIA.md`.
