# 07 — Data Model

## 1. In-Memory (core) Model
```java
record Policy(String name, RateLimitKey key, List<RateLimitKey> components, Algorithm algorithm, FailMode failMode, List<RateRule> rules,
              ConcurrencyRule concurrency, Map<String,PlanOverride> plans, AdaptiveConfig adaptive) {}
record RateRule(String id, Algorithm algorithm, long requests, Duration window, long burst) {}
record ConcurrencyRule(int max, Duration wait, Duration leaseTtl) {}
record PolicySnapshot(long version, String checksum, Map<String,Policy> policies, Instant loadedAt) {}
record RequestContext(String clientKey, String policyName, String plan, long cost) {}
enum Algorithm { TOKEN_BUCKET, GCRA, FIXED_WINDOW, SLIDING_WINDOW_COUNTER, SLIDING_WINDOW_LOG }
```

## 2. Redis Keyspace
Prefix `tc` is configurable. The hash tag is the **client hash only**, so every rule and every policy of one client lands in one Cluster slot and can be evaluated by one script.

| Purpose | Key pattern | Type | TTL |
|---|---|---|---|
| Rate rule state | `tc:{<clientHash>}:<policy>:<ruleId>` | Hash or String (per algorithm, below) | window × 2 idle expiry |
| Concurrency leases | `tc:{<clientHash>}:<policy>:conc` | ZSET `leaseId → expiryMs` | lease-ttl + slack |
| Policy store | `tc:policies` | Hash + version field | none |
| Policy snapshot version | `tc:policies:version` | String | none |
| Update channel | `tc:policies:channel` | Pub/Sub | n/a |
| Audit log (Should) | `tc:policies:audit` | Stream | capped |
Policy-store keys are untagged (not touched by the decision script).

`clientHash` = truncated SHA-256 of the resolved identity (no raw IDs or IPs in Redis).

## 3. State per Algorithm (value of the rule key)
| Algorithm | Fields | Update rule |
|---|---|---|
| TOKEN_BUCKET | `t` (token·ms units: 1 token = `window_ms` units), `ts` (ms) | `t = min(B*W, t + elapsed_ms*R)`; consume `c*W` if available (normative: `19 §1`) |
| GCRA | `tat` (**µs**, epoch) | `tat0 = max(tat, now_us)`; allow if `tat0 + c*T - now_us ≤ B*T`; then `tat = tat0 + c*T`; `T = ceil(window_µs / requests)` (normative: `19 §2`) |
| FIXED_WINDOW | `count`, `windowStart` | reset when `now ≥ windowStart + window` |
| SLIDING_WINDOW_COUNTER | `prev`, `curr`, `windowStart` | estimate `prev × (1 - elapsed/window) + curr` |
| SLIDING_WINDOW_LOG | ZSET `member=eventId, score=ts` | `ZREMRANGEBYSCORE` older than window; `ZCARD < limit` |
Units are normative in `19 §0`: milliseconds for token bucket and the window algorithms, **microseconds for GCRA only**. Integer arithmetic avoids float drift.

## 4. Concurrency State
Acquire (inside the decision script): `ZREMRANGEBYSCORE conc -inf now` → `ZCARD < max` → tentative `ZADD conc now+lease leaseId`, committed only if all rate rules pass. Release: separate `ZREM`. Optional heartbeat extends long leases (Should).

## 5. Lua Script I/O
Defined normatively by `20_REDIS_LUA_CONTRACT.md` (KEYS, ARGV, the 8-integer result, errors). This document does not repeat it. The script is generated from per-algorithm fragments (each unit-tested), loaded with `SCRIPT LOAD` and called with `EVALSHA` (reload and retry once on NOSCRIPT).

## 6. Local Store Model
See §11 for the frozen limits. All-or-nothing is implemented with one lock per key.

## 7. Metrics Data Model
See `11_OBSERVABILITY.md`.

## 8. Retention and Privacy
Rate keys expire on their own; no persistent business data; no raw identifiers.

## 9. Capacity Estimate [Assumption — validate with E9]
Token bucket / GCRA / fixed window: ≈ 100–200 bytes per active key. Sliding window log: ~40 bytes × events in window (the reason it is capped by a per-rule maximum and is last in the cut order).

## 10. Concurrency Lease Semantics (frozen)
| Topic | Rule |
|---|---|
| Lease id | `event_id`: UUID v4 generated per request (122 random bits, effectively unique); also used as log-member prefix |
| Expiry score | `now_ms + lease_ttl_ms` set by the script at acquire |
| `lease-ttl` | 1 s – 1 h, default 30 s; must exceed the longest expected handler time; recommended ≥ 2 × server request timeout (V-012 warns) |
| Key TTL | `lease_ttl + 10 s` slack, refreshed on each acquire |
| Heartbeat (Should) | While a request is in flight, extend every `lease_ttl/3` via `tc_lease_extend`, up to `max-lease-lifetime` (default 10 min) |
| Release trigger | `afterCompletion`, plus `AsyncListener` onComplete/onError/onTimeout for async requests (covers exceptions and client aborts) |
| Release call | `ZREM`, executed on a bounded background executor (queue 10,000) with a 50 ms timeout so response latency is unaffected |
| **Release fails** (error, timeout) | Request is **not** failed. Log WARN (rate-limited), count `concurrency.release_failures{reason}`, retry once after 100 ms in the background, then rely on TTL. Cost: that permit stays unavailable for at most the remaining TTL (bounded capacity loss ≤ `max` permits per key for ≤ `lease-ttl`) |
| Release queue full | Drop the release, count `concurrency.release_dropped`, rely on TTL |
| Instance crashes before release | TTL reclaims the permit |
| Lease expires before the request ends | Permit is freed early (temporary over-admission); `ZREM` later returns 0 → count `lease.expired_before_release`; mitigation: heartbeat or larger TTL |
| Acquire fails because the store is down | Per policy: FAIL_OPEN → proceed without a lease (no release needed); FAIL_CLOSED → reject |
| `wait > 0` | Client-side polling: retry the full atomic decision with backoff 5 ms → 50 ms (+jitter) until the deadline; rate tokens are never consumed by a failed attempt; blocks the request thread (use virtual threads on Java 21) |
| Local mode | `Semaphore` per key + `finally` release; no TTL |

## 11. Local Store Limits (frozen)
| Setting | Value |
|---|---|
| Implementation | Caffeine in `store-local` (never in `core`) |
| `max-keys` | Default 100,000 per store; configurable 1,000 – 10,000,000 |
| Eviction | Size-based (Caffeine W-TinyLFU) + `expireAfterAccess` |
| Idle TTL per key | `max(2 × longest rule window, 1 min)`, capped at 24 h |
| Cleanup | Caffeine maintenance on writes + scheduled `cleanUp()` every 60 s |
| At capacity | Evict by policy (default `on-capacity: EVICT`). Evicting an active key resets its counters (extra admissions) — documented; metrics `trafficcontrol.local.evictions{policy}` and `trafficcontrol.local.size`; alert on a sustained eviction rate (cardinality flood) |
| Pinned keys | Keys with in-flight concurrency permits are never evicted |
| Sliding window log | Per key `≤ requests ≤ 10,000` entries; global cap `max-log-events` default 2,000,000; exceeding it is treated as a store failure (per-policy fail mode) |
| Footprint estimate | ≈ 150–300 bytes per key [validate in E9]; 100,000 keys ≈ 15–30 MB |
Rule for implementers: **no raw `ConcurrentHashMap` keyed by client identity** — all per-key state lives in the bounded cache.
