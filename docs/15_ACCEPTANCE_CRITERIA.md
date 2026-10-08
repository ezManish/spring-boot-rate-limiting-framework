# 15 — Acceptance Criteria

Release is accepted only when every **Must** criterion is verified with evidence (test report, benchmark output, or demo recording).

## A. Functional
| ID | Criterion | Evidence |
|---|---|---|
| AC-01 | `@RateLimit(requests=100, window="1m", key=USER)` limits per user; request 101 → 429 | TC-001, TC-010 |
| AC-02 | `@RateLimitPolicy("login")` enforces 5/min per IP; FAIL_CLOSED returns 429 with `store-unavailable` type | TC-014, TC-061 |
| AC-03 | 429 includes `Retry-After`; allowed responses include `X-RateLimit-Limit/Remaining` | TC-010, TC-011 |
| AC-04 | USER, IP (+ tenant/plan, custom resolver) keys work | TC-012, 013, 017 |
| AC-05 | Multi-rule policies are all-or-nothing; no partial token loss | TC-030..033 |
| AC-06 | Distributed concurrency limit evaluated atomically with rate rules; lease TTL reclaims crashed permits | TC-050..054 |
| AC-07 | Policies live-update across instances without redeploy; invalid update rejected | TC-070..073 |

## B. Distributed Correctness
| ID | Criterion | Evidence |
|---|---|---|
| AC-08 | With N≥3 instances, global limit holds (admitted ≤ limit + documented tolerance) | TC-040..042 |
| AC-09 | Redis Cluster supported (hash tags Must; no CROSSSLOT) | TC-023 |
| AC-10 | Redis TIME used; instance clock skew has no effect | TC-021 |

## C. Resilience
| ID | Criterion | Evidence |
|---|---|---|
| AC-11 | FAIL_OPEN / FAIL_CLOSED behave per policy during Redis outage | TC-060, 061 |
| AC-12 | Circuit breaker opens, half-opens, closes correctly | TC-063..065 |

## D. Observability
| ID | Criterion | Evidence |
|---|---|---|
| AC-13 | Metrics + log emitted for every decision; no PII/identity tags | TC-080..084 |
| AC-14 | Prometheus endpoint exposes all metrics in `11_OBSERVABILITY.md` | Scrape output |
| AC-15 | Dashboards (Should) show allowed/rejected, latency, breaker, degraded | Screenshots |

## E. Adaptive (Should)
| AC-16 | Adaptive stays within bounds and is measured against static in benchmark; results reported even if not superior | TC-090..093, benchmark report |

## F. Performance & Evaluation
| ID | Criterion | Evidence |
|---|---|---|
| AC-17 | Benchmark suite runs reproducibly vs Bucket4j baseline | `bench/` + report |
| AC-18 | Targets in `10_BENCHMARK_PLAN.md §7` met or deviation explained | Report |

## G. Developer Experience
| ID | Criterion | Evidence |
|---|---|---|
| AC-19 | A new developer protects an endpoint in < 5 minutes using README only (3 trial users) | Timed trial log |
| AC-20 | Core module has no Spring/Redis dependency; Bucket4j not a runtime dependency | Dependency tree / build check |
| AC-21 | Invalid policy fails fast with clear message | TC-015 |

## H. Security
| AC-22 | Security tests SEC-01..05 pass | Test report |

## I. Delivery
| AC-23 | Demo app, docs, final report, tagged v1.0 release | Repository |
| AC-24 | Core + algorithms line coverage ≥ 85%; PIT mutation score target 70% | Coverage + PIT reports |

## Sign-off
| Role | Name | Date |
|---|---|---|
| Team lead | | |
| Faculty guide | | |

## J. Algorithms & Contract (added)
| ID | Criterion | Evidence |
|---|---|---|
| AC-25 | All five algorithms (token bucket, fixed window, sliding window counter, sliding window log, GCRA) pass the shared conformance suite locally and in Redis (sliding log may be cut per cut order and then documented) | TC-110..116 |
| AC-26 | Rejections use `application/problem+json`; `X-RateLimit-Reset` epoch seconds; `Retry-After` delta-seconds | TC-120..123 |
| AC-27 | Admin API secured, audit-logged, off by default (if not cut) | TC-130..132 |
| AC-28 | Engine + local store decision p99 < 100 µs (JMH); starter p99 < 1 ms; Redis added p99 < 5 ms, or deviation explained | Benchmark report |

## K. Engineering Rigor (added after review)
| ID | Criterion | Evidence |
|---|---|---|
| AC-29 | CI runs on every PR with all gates in `18 §4` (format, architecture, unit, IT on Redis 6.2/7/8, coverage, PIT, vulnerability scan); `main` protected | Workflow runs, branch protection settings |
| AC-30 | Local, Lua and reference model agree on all vectors and property tests in `19 §7–8` | TC-110..116, TC-173 |
| AC-31 | Lua contract (`20`) honored: 8-integer result, version check, NOSCRIPT retry, no writes on deny except maintenance | TC-170..175 |
| AC-32 | All config validation rules V-001..V-030 enforced with the specified report | TC-150..155 |
| AC-33 | Rejection body is generic by default; no policy/rule/key/identity leaks | TC-120, TC-124 |
| AC-34 | Lease release failure, early expiry and heartbeat behave as specified | TC-055..05A |
| AC-35 | Local store respects max keys, idle TTL, pinning and log cap | TC-140..143 |
| AC-36 | Admin API follows `05 §5` (If-Match, 422/412/428/409, audit) | TC-130..139 |
| AC-37 | Versions frozen in the parent POM; resolved versions recorded in the final report; compatibility policy followed; Boot 3.5 EOL risk and mitigations stated in the report | POM, report |
| AC-38 | `QUICKSTART.md` validated by 2 outsiders | Trial log |
