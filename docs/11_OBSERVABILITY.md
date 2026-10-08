# 11 — Observability

Every decision produces **metrics and logs** (architecture slide).

## 1. Metrics (Micrometer → Prometheus)
| Name | Type | Tags | Meaning |
|---|---|---|---|
| `trafficcontrol.requests` | Counter | `policy, outcome(allow/reject), rule, algorithm, reason(rate/concurrency/store), degraded` | Decisions |
| `trafficcontrol.decision.latency` | Timer (histogram) | `policy, store` | Engine time |
| `trafficcontrol.store.errors` | Counter | `store, error_type` | Store failures |
| `trafficcontrol.breaker.state` | Gauge | `store` (0 closed,1 half-open,2 open) | Circuit breaker |
| `trafficcontrol.breaker.transitions` | Counter | `from,to` | State changes |
| `trafficcontrol.degraded` | Counter | `policy, mode` | Fail-open/closed events |
| `trafficcontrol.concurrency.inflight` | Gauge | `policy` | Active permits (local view) |
| `trafficcontrol.concurrency.leaked` | Counter | `policy` | Permits reclaimed by TTL |
| `trafficcontrol.concurrency.release_failures` | Counter | `policy, reason` | Release errors/timeouts |
| `trafficcontrol.concurrency.release_dropped` | Counter | `policy` | Releases dropped (queue full) |
| `trafficcontrol.lease.expired_before_release` | Counter | `policy` | Lease TTL ended before request completion |
| `trafficcontrol.local.evictions` | Counter | `policy` | Local-store evictions |
| `trafficcontrol.local.size` | Gauge | none | Keys in local store |
| `trafficcontrol.adaptive.signal_errors` | Counter | `signal` | Missing/NaN adaptive inputs |
| `trafficcontrol.policy.version` | Gauge | none | Active snapshot version |
| `trafficcontrol.policy.reloads` | Counter | `result(success/failure)` | Updates |
| `trafficcontrol.adaptive.factor` | Gauge | `policy` | Current multiplier |

Tag rules: **never** tag identity/user/IP (cardinality + PII). Policy names are bounded.

## 2. Logs
Structured (JSON) per decision at DEBUG, sampled INFO for rejects:
```
{"event":"rate_limit","policy":"login","outcome":"reject","rule":"per-minute",
 "key_hash":"a1b2c3","remaining":0,"retry_after_ms":23000,"degraded":false,"trace_id":"..."}
```
- Rejects and degraded decisions always logged (rate-capped to avoid log floods).
- Breaker transitions and policy swaps logged at WARN/INFO.

## 3. Tracing [Assumption]
Span attribute `ratelimit.outcome`, `ratelimit.policy` via Micrometer Tracing when present.

## 4. Dashboards (Grafana) — first to cut if behind
1. Allowed vs rejected rate by policy.
2. Reject ratio and top limiting rules.
3. Decision latency p50/p99.
4. Store errors + breaker state.
5. Degraded decisions (fail-open/closed).
6. Policy version per instance (detect drift).
7. Adaptive factor over time.

## 4b. Alerts (examples)
| Alert | Condition |
|---|---|
| Breaker open | `trafficcontrol.breaker.state == 2` for > 1m |
| High degraded | `rate(trafficcontrol.degraded[5m]) > 0` on FAIL_CLOSED policy |
| Policy drift | instances report differing `trafficcontrol.policy.version` > 2m |
| Reject spike | reject ratio > X% vs baseline |

## 5. Health
Actuator health indicator `rateLimit`: store reachability, breaker state, policy version.

## 6. Verification
Covered by TC-080..084.
