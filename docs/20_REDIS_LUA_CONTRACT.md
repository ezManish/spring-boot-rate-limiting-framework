# 20 — Redis Lua Contract

Normative interface between `store-redis` (Java) and the decision script. Changing anything here requires bumping `SCRIPT_VERSION` and updating `19_ALGORITHM_SPECIFICATION.md`.

## 1. Scripts
| Script | Purpose | Round trips |
|---|---|---|
| `tc_decide` | Evaluate all rate rules + concurrency permit atomically | 1 per decision |
| `tc_lease_extend` | Extend a lease (heartbeat, Should) | 1 per heartbeat |
| *(no script)* `ZREM key leaseId` | Release a lease | 1 per request end |
`SCRIPT_VERSION = 1`. Loaded with `SCRIPT LOAD`, called with `EVALSHA`; on `NOSCRIPT` the client reloads and retries **once**.

## 2. `tc_decide` Input
**KEYS** (all in the same hash slot, enforced by Redis; the client builds them with one `{clientHash}` tag):
`KEYS[1..n]` = rate-rule state keys in rule order; `KEYS[n+1]` = concurrency key only if `has_conc = 1`.

**ARGV** (decimal strings):
| Index | Name | Meaning |
|---|---|---|
| 1 | `script_version` | Must equal 1, else `TC_ERR_VERSION` |
| 2 | `n` | Number of rate rules, 0 ≤ n ≤ 8 |
| 3 | `has_conc` | 0 or 1 |
| 4 | `cost` | Integer ≥ 1 |
| 5 | `event_id` | UUID (used for log members and as the lease id) |
| 6 … 5+5n | per rule | `algo_id, requests, window_ms, burst, reserved` (5 values per rule) |
| next 3 (if `has_conc`) | concurrency | `max, lease_ttl_ms, key_ttl_ms` |
`algo_id`: 1 TOKEN_BUCKET, 2 GCRA, 3 FIXED_WINDOW, 4 SLIDING_WINDOW_COUNTER, 5 SLIDING_WINDOW_LOG.
Invalid arguments → `redis.error_reply("TC_ERR_ARGS <detail>")`. The script never trusts types; the client validates first, the script re-checks ranges.

## 3. `tc_decide` Output (always 8 integers)
| Idx | Name | Meaning |
|---|---|---|
| 1 | `allowed` | 1 allow, 0 deny |
| 2 | `binding_rule` | 1-based rule index; 0 = concurrency |
| 3 | `reason` | 0 none, 1 rate, 2 concurrency |
| 4 | `remaining` | of the binding rule |
| 5 | `limit` | of the binding rule (`limit` per `19 §0`); concurrency: `max` |
| 6 | `reset_at_ms` | epoch ms |
| 7 | `retry_after_ms` | 0 on allow |
| 8 | `now_ms` | script clock, for client diagnostics |
Binding-rule selection: see `19 §0`. Concurrency denial: `reason = 2`, `retry_after_ms = 0` unless a lease expiry is known (then time to the earliest expiry).

## 4. Algorithm of the Script
```
now = TIME
Phase 1 (evaluate state; the only writes allowed are expiry maintenance, see §5; no decision-affecting writes):
  for each rate rule i: load state; compute (allowed_i, new_state_i, outputs_i) per doc 19
  if has_conc: ZREMRANGEBYSCORE conc -inf now   -- maintenance, see §5
               count = ZCARD conc;  allowed_c = (count < max)
Phase 2:
  if all allowed: write every new_state_i (+ PEXPIRE) and ZADD conc (now+lease_ttl) event_id
                  (+ PEXPIRE conc key_ttl_ms)
  else: write nothing
return 8-integer array
```
All-or-nothing applies to decision-affecting state: **either every rule's state changes or none does.** Expiry maintenance (§5) is the only exception.

## 5. Permitted Writes on Deny (maintenance only)
Removing entries that already expired (`ZREMRANGEBYSCORE` on logs and concurrency sets). These never change a decision outcome and are idempotent. No other write is allowed on the deny path; in particular no state key is created for a denied first request.

## 6. Lease Semantics (contract level)
- Lease id = `event_id` (UUID v4) returned to the caller by the client library; unique per request.
- Release: `ZREM conc <event_id>` → 1 (released) or 0 (already expired). 0 is counted as `lease.expired_before_release`.
- `tc_lease_extend`: `KEYS[1]=conc`, `ARGV[1]=event_id`, `ARGV[2]=lease_ttl_ms` → `ZADD XX` with new expiry (`TIME` + ttl) and `PEXPIRE`; returns 1 if the lease still existed, else 0.

## 7. Errors and Client Behavior
| Condition | Result | Client action |
|---|---|---|
| `NOSCRIPT` | error | `SCRIPT LOAD`, retry once |
| `TC_ERR_VERSION` | error | Fail startup / mark store unhealthy (version skew) |
| `TC_ERR_ARGS` | error | Programming error: log ERROR, apply failure mode |
| `CROSSSLOT` | error | Programming error: fail startup self-check, apply failure mode |
| Timeout / connection error | exception | Breaker + failure mode |

## 8. Replication and Cluster
Requires Redis 6.2+ (script effects replication; `TIME` followed by writes is allowed). No `redis.replicate_commands()` needed. Policy keys are never touched by `tc_decide`. A startup self-test runs the script with a canonical vector on the configured Redis and fails fast on mismatch.

## 9. Test Build
`tc_decide` is generated from a template. The production build reads `TIME`. A **test-only** build reads the clock from an extra ARGV, so Testcontainers tests can replay `19 §7` vectors deterministically. The test build has a different SHA and must never be shipped in the starter jar.

## 10. Generated Script Layout
`algo/token_bucket.lua`, `gcra.lua`, `fixed_window.lua`, `sliding_counter.lua`, `sliding_log.lua`, `concurrency.lua`, `main.lua`; the build concatenates them. Each fragment has unit tests.
