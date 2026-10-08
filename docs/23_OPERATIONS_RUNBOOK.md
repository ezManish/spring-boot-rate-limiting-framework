# 23 — Deployment and Operations Runbook

Audience: platform/SRE engineers running an application that uses the framework. Values marked [proposal] are starting points to validate with benchmarks.

## 1. Redis Topologies
| Topology | Use | Notes |
|---|---|---|
| Single instance | Dev, demo, small | No HA; the framework's fail mode applies during outages |
| Primary + replica with Sentinel | Production default | Failover may reset some counters (documented); breaker absorbs the blip |
| Redis Cluster | Scale-out | Hash tags `{clientHash}` keep one client on one slot; policy-store keys are separate |
Do not use replica reads for decisions; all decisions go to the primary/slot owner.

## 2. Redis Configuration
| Setting | Recommendation |
|---|---|
| `maxmemory` | Set explicitly; size from the capacity estimate in `07 §9` and benchmark E9 |
| `maxmemory-policy` | `volatile-lru` (all rate/lease keys have TTLs; policy-store keys have none, so they are protected). Never `noeviction` for the limiter (writes would fail under pressure) and avoid `allkeys-*` unless the policy store lives elsewhere |
| Persistence | Not needed for rate state. Enable AOF if the policy store must survive restarts |
| ACL | Dedicated user limited to the commands in `13_SECURITY.md §3`; TLS on |
| Dedicated instance | Recommended for the limiter, so unrelated workloads cannot evict or slow it |

## 3. Client Settings [proposal]
Command timeout 50–100 ms; connect timeout 500 ms; pooled connections sized to request concurrency; breaker: 50% failures over 20 calls, 10 s open, 3 half-open probes; policy poll interval 30 s (fallback for missed pub/sub).

## 4. Rollout Procedures
**First deployment:** deploy with a policy in observe-only mode if available [Could]; otherwise start with generous limits, watch `trafficcontrol.requests` reject ratio, then tighten.
**Policy change:** validate in staging → apply via admin API (`If-Match` version) → watch `trafficcontrol.policy.version` converge on all instances (alert if instances differ for > 2 min) → watch reject ratio → roll back with the rollback endpoint if needed.
**Library upgrade:** follow `22 §5`; upgrade a canary instance first; mixed versions are supported within a major.
**Redis maintenance/failover:** expect brief breaker activity; FAIL_CLOSED policies reject (429, `store-unavailable`) during the blip; no action needed unless it exceeds a few seconds.

## 5. Alert → Action
| Alert | Likely cause | Action |
|---|---|---|
| Breaker open > 1 min | Redis down/slow/network | Check Redis health and latency; confirm fail modes behave as intended; restore Redis |
| Degraded decisions on FAIL_CLOSED policy | Same | Confirm users see 429 `store-unavailable`; consider incident comms |
| Reject spike | Real abuse, bad policy change, or client bug | Compare with policy version changes; roll back if recent |
| Policy version drift | Missed pub/sub, bad snapshot | Check instance logs for rejected snapshots; force reload |
| Lease expired-before-release rising | Handlers outlive `lease-ttl` | Increase `lease-ttl` or enable heartbeat |
| Redis memory growth | Key cardinality flood | Check key counts; add edge limits; confirm TTLs; tune `maxmemory` |

## 6. Capacity Planning
Decisions/s ≈ requests/s (one Redis round trip each). Redis memory ≈ active keys × bytes per key (`07 §9`). Hot clients map to one slot; extremely hot keys are a documented limit. Load-test with the `bench` module before production sizing.

## 7. Backup and DR
Rate state is ephemeral; no backup. Policies are source-controlled (YAML) and/or exported through the admin API. Recovery = redeploy + reload policies.

## 8. Troubleshooting Checklist
1. Is Redis reachable and is the breaker closed? 2. Do all instances report the same policy version? 3. Which policy/rule is rejecting (`rule` tag, logs with `key_hash`)? 4. Is the client behind a proxy and are `trusted-proxies` correct? 5. Are the clocks irrelevant (Redis `TIME`)? yes — skew is not a cause.
