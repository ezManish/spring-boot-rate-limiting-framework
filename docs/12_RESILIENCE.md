# 12 — Resilience

Principle: the rate limiter must never be the reason the application fails unexpectedly — and for sensitive routes must never silently stop protecting.

## 1. Failure Modes & Responses
| Failure | Detection | Response |
|---|---|---|
| Redis unreachable | connect/IO exception | Apply policy failure mode |
| Redis slow | command timeout (e.g. 50–100 ms [Assumption]) | Treated as failure |
| Repeated failures | breaker threshold | Breaker OPEN: skip Redis, apply failure mode instantly |
| Redis recovers | half-open probe | Close breaker on success |
| Lua script missing (NOSCRIPT after restart/failover) | error code | Reload script (EVALSHA→EVAL fallback), retry once |
| Redis failover (replica promoted) | transient errors | Breaker + retry; counters may reset (documented) |
| Cluster slot move (MOVED/ASK) | redirect | Client handles; hash tags keep keys co-located |
| Instance crash mid-request | n/a | Concurrency lease TTL reclaims permit |
| Lease release call fails | error/timeout | Request unaffected; WARN + metric; one background retry; TTL reclaims (`07 §10`) |
| Lease expires before request ends | `ZREM` returns 0 | Metric; heartbeat/larger TTL; temporary over-admission accepted |
| Bad policy update (admin API or pub/sub) | validation failure | Reject snapshot, keep last-known-good; record in audit log |
| Pub/sub message lost | version poll | Converge via periodic poll |
| Clock skew | n/a | Redis TIME only |

## 2. FAIL_OPEN vs FAIL_CLOSED
| Mode | Use for | Trade-off |
|---|---|---|
| FAIL_OPEN | Read/product APIs | Availability over protection; abuse possible in outage |
| FAIL_CLOSED | login, OTP, payments | Protection over availability; returns 429 (configurable) with problem type `store-unavailable` so it is distinguishable from quota rejections |
Optional **local fallback** [Assumption]: during outage, use per-instance local limiter (limit/N instances) instead of pure open/closed.

## 4. Circuit Breaker
States: CLOSED → (failure rate ≥ threshold over window) → OPEN → (wait duration) → HALF_OPEN → (k probe successes) → CLOSED / (failure) → OPEN.
Defaults [Assumption]: 50% failures over 20 calls, 10s open, 3 probes. Configurable; state exported as metric.

## 5. Concurrency Lease Safety
Permits carry expiry; acquired inside the same atomic script as the rate rules and reaped on each acquire. TTL must exceed max handler time; otherwise permit expires early (documented trade-off). Release in `afterCompletion` always.

## 6. Policy Snapshot Safety
Immutable snapshots + atomic reference swap → no torn reads. Version monotonic; older versions ignored. In-flight requests unaffected.

## 7. Adaptive Safety
Bounded by min/max factor; rate-of-change limit to prevent oscillation; kill switch via config; defaults off.

## 8. Backpressure on Redis
Single Lua call per decision; connection pool bounded; timeouts mandatory; no retries on hot path beyond one NOSCRIPT retry.

## 9. Chaos Tests
Mapped to TC-060..065, TC-052, TC-071..073 using Toxiproxy and container stop/start.

## 10. Runbook (short)
| Symptom | Check | Action |
|---|---|---|
| All requests allowed unexpectedly | `trafficcontrol.breaker.state`, `trafficcontrol.degraded` | Check Redis; fix; consider FAIL_CLOSED for critical |
| Sudden 429s | policy version drift | Verify snapshot, rollback version |
| Permits stuck | `trafficcontrol.concurrency.leaked` | Lower lease TTL; check handler durations |
