# 08 — Test Plan

## 1. Objectives
Prove (a) correctness of limits, (b) atomic multi-rule behavior, (c) distributed consistency, (d) resilience behavior, (e) HTTP contract, (f) observability output. Correctness and evaluation are never cut (see plan buffer rule).

## 2. Test Levels
| Level | Tooling | Scope |
|---|---|---|
| Unit | JUnit 5 | core: all algorithms, parsing, policy validation, key resolvers, decision logic with fake `Clock` |
| Algorithm conformance | JUnit 5 parameterized | One suite run against every algorithm, local and Lua |
| Mutation | PIT | core + algorithms; target 70% mutation score |
| Contract | Testcontainers | Lua contract (`20`), test-build script replaying `19 §7` vectors |
| Config validation | JUnit | Every rule V-001..V-030 (`06 §9`) |
| CI | GitHub Actions | Pipeline and gates in `18_CI_CD.md` |
| Integration (local) | Spring Boot Test, MockMvc | annotation → interceptor → 429/headers |
| Integration (Redis) | Testcontainers (Redis 6, 7) | Lua script, TIME, TTL, pub/sub |
| Cluster | Testcontainers Redis Cluster (3 nodes) | hash-tag slot safety |
| Multi-instance | 2–3 app instances + shared Redis | global limit correctness |
| Concurrency/stress | JUnit + executor, jqwik [Assumption] | atomicity, no over-admission |
| Fault injection | Toxiproxy (Testcontainers) | latency, drop, Redis stop |
| Performance | JMH + load tool (k6/wrk) | `10_BENCHMARK_PLAN.md` |
| Observability | MeterRegistry assertions | metrics emitted per decision |

## 3. Test Areas & Risk
| Area | Risk | Depth |
|---|---|---|
| Atomic multi-rule (rate + concurrency) | Partial token/permit loss | High (property + stress) |
| Five algorithms in one Lua script | Fragment interaction bugs | High (conformance suite) |
| Redis Cluster | CROSSSLOT errors | High |
| Clock | Skew across instances | Medium (simulated skew) |
| Concurrency lease | Leaked permits | High |
| Fail modes / breaker | Wrong open/closed behavior | High |
| Live policy swap | Torn reads, bad snapshot | High |
| Adaptive | Oscillation | Medium |

## 4. Entry / Exit Criteria
**Entry:** feature merged to main, unit tests green.
**Exit per phase:** all Must test cases for phase pass; no open Sev-1/2; core + algorithms ≥ 85% line coverage (owner decision), PIT target 70% (recommended).
**Release exit:** all of `15_ACCEPTANCE_CRITERIA.md`.

## 5. Environments
Local (Docker), CI (GitHub Actions + Docker service), perf environment (fixed-spec VM/laptop documented) **[Assumption]**.

## 6. Test Data
Synthetic identities (users `u1..uN`), plan fixtures (free/pro), fixed seeds for randomized tests.

## 7. Defect Policy
Sev-1 (wrong limit/over-admission, crash) blocks merge; Sev-2 fix within phase; Sev-3 backlog.

## 8. Traceability
Each case in `09_TEST_CASES.md` references FR/US IDs.

## 9. Schedule
Tests written alongside each phase (Phase 1 unit → Phase 3 distributed → Phase 6 full regression + benchmarks).
