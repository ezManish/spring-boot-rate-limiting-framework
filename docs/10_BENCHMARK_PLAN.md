# 10 — Benchmark Plan

Purpose: **prove and measure** (slide 5). Bucket4j is the baseline, not a dependency.

## 1. Questions to Answer
1. Overhead of our engine vs Bucket4j (local and Redis-backed)?
2. Distributed throughput / latency with 1, 3, 5 app instances?
3. Cost of multi-rule atomic evaluation vs single rule?
4. Does adaptive limiting outperform static limits under overload?
5. Behavior during Redis degradation (fail-open/closed, breaker)?

## 2. Systems Under Test
| ID | Setup |
|---|---|
| S0 | No rate limiting (control) |
| S1 | Our engine, local store |
| S2 | Bucket4j local (baseline) |
| S3 | Our engine, Redis (Lua) |
| S4 | Bucket4j + Redis (baseline) |
| S5 | Resilience4j RateLimiter (per-instance reference) |
| S6 | Our engine + adaptive vs. static (S3) |
| S7 | Each algorithm (token bucket, GCRA, fixed, sliding counter, sliding log), local and Lua |

## 3. Workloads
| Workload | Description |
|---|---|
| W1 Microbench | JMH: single `evaluate()` call, hot key, many keys |
| W2 Uniform HTTP | k6/wrk constant arrival rate, many users |
| W3 Hot key | 80% traffic on 1% keys |
| W4 Multi-rule | 1, 2, 4 rules per policy |
| W5 Overload spike | 3× capacity burst for 60s |
| W6 Redis fault | Kill/latency injection mid-run |
| W7 Algorithm sweep | Same load across all algorithms; measure ns/op, allocations, Redis bytes/key, accuracy (overshoot %) |
| W8 Concurrency | Slow endpoint with and without distributed concurrency cap |

## 4. Metrics
Throughput (req/s), latency p50/p95/p99/p99.9, CPU, Redis ops/s, admission accuracy (admitted vs theoretical limit), error rate, SLO violations for adaptive test (e.g. backend p99 under target).

## 5. Methodology
- JMH: warmup 5×1s, measure 10×1s, 3 forks; blackhole results.
- HTTP: 60s warmup discard, 5 min measure, 5 repetitions; report median + CI.
- Pinned versions: Java 21, Redis 7.x, Spring Boot 3.5.x (exact versions recorded in the report); fixed hardware documented; Redis on separate host/container with limited noise.
- Coordinated omission avoided via constant-arrival-rate tools.
- All scripts, configs, and raw results committed in `bench/`.

## 6. Adaptive vs Static Experiment
Backend with a simulated capacity (e.g. fixed worker pool). Compare: static limit tuned for normal load vs adaptive. Success = lower backend p99 / fewer timeouts at equal or higher useful throughput under W5. Report where adaptive loses (honesty).

## 7. Acceptance Targets [Assumption – finalize after first run]
| Metric | Target |
|---|---|
| Engine + local store decision p99 (JMH) | < 100 µs |
| Starter end-to-end overhead p99 | < 1 ms |
| S1 vs S2 overhead | within ±20% |
| S3 decision p99 (local network) | < 5 ms |
| Admission accuracy S3 | ≥ 99.9% (no over-admission beyond tolerance) |
| Multi-rule (4 rules) vs 1 rule | < 2× latency |

## 8. Outputs
Charts + table in final report; reproducible `make bench` / script; threats-to-validity section.

## 9. Schedule
Harness skeleton in Phase 3; full runs Phase 6 (D92–110).

## 10. Mapping to PRD Experiments
E1 overhead → S0/S1/S2 • E2 Redis cost → S3/S4 • E3 accuracy → W7 • E4 boundaries → TC-111 + W7 • E5 Redis failure → W6 • E6 policy propagation → TC-070..075 timing • E7 concurrency → W8 • E8 adaptive → S6 • E9 per-algorithm cost → S7/W7.
