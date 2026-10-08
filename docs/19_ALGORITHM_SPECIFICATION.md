# 19 — Algorithm Specification (exact semantics)

Normative. The local implementation, the Lua implementation and the reference model used in tests must produce identical `(allowed, remaining, limit, resetAtMs, retryAfterMs)` for identical inputs. Any difference is a bug in one of them.

## 0. Conventions
| Item | Definition |
|---|---|
| Time source | Redis mode: `TIME` → `now_us = sec*10^6 + usec`, `now_ms = floor(now_us/1000)`. Local mode: `Clock` SPI (monotonic-anchored; fake clock in tests). |
| Clock regression | `elapsed = max(0, now - ts)`. State never moves backwards. |
| Cost | Integer `c ≥ 1`, default 1. v1 only uses 1 via annotations; custom resolvers/handlers may set it. |
| Params | `R` = `requests`, `W` = `window_ms`, `B` = `burst` (default `R`; token bucket and GCRA only). |
| Integer safety | All Lua math stays below 2^53. Config validation enforces: token bucket `R*W ≤ 4×10^15` and `B*W ≤ 4×10^15`; GCRA `T_us ≥ 1`; sliding log `R ≤ 10,000`. Violations fail startup. |
| `limit` (header value) | `B` for token bucket and GCRA, `R` for window algorithms |
| `remaining` | Non-negative integer, in units of cost-1 requests, computed **after** consumption on allow, current value on deny |
| `resetAtMs` | Epoch ms when the rule's state is fully replenished (see each algorithm). Header: `X-RateLimit-Reset = ceil(resetAtMs/1000)` |
| `retryAfterMs` | 0 on allow. On deny, minimum wait until a request of the same cost would be allowed *if nothing else happens*. Header: `Retry-After = max(1, ceil(retryAfterMs/1000))` |
| Binding rule (multi-rule) | Allow: rule with lowest `remaining/limit` (tie: earliest rule). Deny: failing rule with the largest `retryAfterMs` (tie: earliest). Headers and body describe the binding rule. |
| State TTL | Every state key gets `PEXPIRE` on every write (value per algorithm). Idle keys expire; no state is created on deny. |

## 1. TOKEN_BUCKET
Units: 1 token = `W` units, so refill is an exact integer `R` units per ms.
State: `t` (units), `ts` (ms). Initial (no key): `t = cap`, `ts = now_ms`. `cap = B*W`.
```
elapsed = min(max(0, now_ms - ts), ceil(cap / R))
t = min(cap, t + elapsed * R);  ts = now_ms
need = c * W
if t >= need: t -= need; allowed
else: denied (no state change except the refill above is NOT persisted)
remaining   = floor(t / W)
resetAtMs   = now_ms + ceil((cap - t) / R)          -- time to full
retryAfterMs= allowed ? 0 : ceil((need - t) / R)
state TTL   = ceil(cap / R) + 1000 ms
```
If `c > B` the request can never succeed: deny with `retryAfterMs = W` and metric reason `cost_exceeds_capacity`.

## 2. GCRA (alias LEAKY_BUCKET as meter)
`W_us = W*1000`, `T = ceil(W_us / R)` µs (ceil ⇒ never admits more than `R` per `W`), `τ = B*T`.
State: `tat` (µs). Initial: absent ⇒ `tat = now_us`.
```
tat0    = max(tat, now_us)
new_tat = tat0 + c*T
if new_tat - now_us <= τ:  allowed; tat = new_tat
else: denied; state unchanged
remaining    = floor((τ - ((allowed ? new_tat : tat0) - now_us)) / T), min 0
resetAtMs    = ceil((allowed ? new_tat : tat0) / 1000)
retryAfterMs = allowed ? 0 : ceil((new_tat - τ - now_us) / 1000)
state TTL    = ceil(max(tat - now_us, 0)/1000) + 1000 ms
```
Accuracy: rounding error ≤ 1 µs per request, relative error ≤ 1/T_us.

## 3. FIXED_WINDOW
Windows are epoch-aligned: `ws = floor(now_ms / W) * W` (same on every instance because the clock is Redis).
State: `count`, `ws`. If stored `ws ≠` current `ws` ⇒ `count = 0`.
```
if count + c <= R: count += c; allowed
remaining    = R - count (min 0)
resetAtMs    = ws + W
retryAfterMs = allowed ? 0 : ws + W - now_ms
state TTL    = (ws + W - now_ms) + 1000 ms
```
Documented weakness: up to `2R` requests across a window boundary.

## 4. SLIDING_WINDOW_COUNTER
State: `prev`, `curr`, `ws`. Roll-over on each request: `ws_now = floor(now_ms/W)*W`;
same `ws` ⇒ keep; `ws == ws_now - W` ⇒ `prev = curr, curr = 0`; otherwise `prev = curr = 0`; set `ws = ws_now`.
```
e   = now_ms - ws
est = floor(prev * (W - e) / W) + curr
if est + c <= R: curr += c; allowed
remaining    = R - (allowed ? est + c : est), min 0
resetAtMs    = ws + W
-- deny: m = R - curr - c
retryAfterMs = (m >= 0 and prev > 0) ? (W - ceil((m+1)*W/prev) + 1) - e   (clamped to ≥ 1)
             : ws + W - now_ms                                            (next window; may need re-check)
state TTL    = 2W + 1000 ms
```
Error bound: the true sliding count differs from `est` by at most `prev*(1 - (W-e)/W)`; worst case is an adversarial burst at the end of the previous window. The error is documented and measured in E3/E4.

## 5. SLIDING_WINDOW_LOG
ZSET, score = event `now_ms`, member = `<event_id>:<i>` (`event_id` is a UUID supplied per request; `i = 1..c`).
```
ZREMRANGEBYSCORE key -inf (now_ms - W)        -- window is (now-W, now]
count = ZCARD key
if count + c <= R: ZADD c members; allowed
remaining    = R - count - (allowed ? c : 0)
resetAtMs    = (newest score) + W              -- window empty
-- deny: k = count + c - R
retryAfterMs = score(rank k-1, ascending) + W - now_ms
state TTL    = W + 1000 ms
```
Exact, O(R) memory per key. `R ≤ 10,000` enforced. Cuttable (cut-order item 4).

## 6. Mixed Algorithms in One Policy
Each rule keeps its own state key; all are evaluated against the same `now`; commit only if all allow (see `20_REDIS_LUA_CONTRACT.md`). Mixed algorithms are allowed (FR-17).

## 7. Test Vectors (must pass in model, local and Lua)
| Algorithm | Params | Inputs | Expected |
|---|---|---|---|
| TOKEN_BUCKET | R=10, W=1000, B=10 | 10 requests at t=0; 11th at t=0; 12th at t=100 | 10 allowed (remaining 9…0); 11th denied `retry=100`; 12th allowed `remaining=0`, `reset=1100` |
| GCRA | R=10, W=1000, B=10 | same | identical to token bucket (T=100,000 µs) |
| FIXED_WINDOW | R=3, W=1000 | 4 requests at t=1500 | 3 allowed (remaining 2,1,0); 4th denied `retry=500`, `reset=2000` |
| SLIDING_WINDOW_COUNTER | R=10, W=1000 | prev=10, requests at t=1500 | 5 allowed; 6th denied `retry=1` (est floors to 9 at e=501) |
| SLIDING_WINDOW_LOG | R=3, W=1000 | events at t=0,100,200; 4th at t=300 | 3 allowed; 4th denied `retry=700`; at t=1000 the t=0 event has expired |
| Multi-rule | TB(R=1,W=1000) + FIXED(R=100,W=60000) | 2 requests at t=0 | 1st allowed; 2nd denied by TB; fixed count stays 1 |

## 8. Required Property Tests
For random request sequences and rule sets: (1) model = local = Lua; (2) a denied request never changes any rule's state; (3) admitted count in any window never exceeds the algorithm's documented bound; (4) `remaining` is monotonic non-increasing between refills.
