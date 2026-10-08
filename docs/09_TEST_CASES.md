# 09 — Test Cases

Legend — Type: U=unit, I=integration, D=distributed, S=stress, F=fault, P=perf. Priority: M/S.

## A. Core / Token Bucket
| ID | Case | Expected | Type | Ref |
|---|---|---|---|---|
| TC-001 | 100 requests in 1m window, request 101 | 101st rejected | U | FR-04 |
| TC-002 | Advance fake clock 60s after exhaustion | Full capacity restored | U | FR-04 |
| TC-003 | Partial refill (30s of 1m window) | ~50% tokens refilled | U | FR-04 |
| TC-004 | Burst > requests configured | Burst allowed up to capacity | U | FR-04 |
| TC-005 | Window strings `30s,1m,1h,2d`; invalid `1x`,`-1m`,`0s` | Valid parse / invalid rejected | U | FR-01 |
| TC-006 | Retry-After computed | Equals time until next token | U | FR-09 |

## B. Spring Integration
| ID | Case | Expected | Ref |
|---|---|---|---|
| TC-010 | `@RateLimit` on endpoint, exceed limit | 429 + Retry-After | FR-01, FR-09 |
| TC-011 | Allowed request headers | Limit & Remaining present, decrementing | FR-09 |
| TC-012 | Key USER: two users independent | Separate counters | FR-03 |
| TC-013 | Key IP: same IP different users | Shared counter | FR-03 |
| TC-014 | `@RateLimitPolicy("login")` resolves YAML | 5/min per IP enforced | FR-02 |
| TC-015 | Unknown policy name | Context fails to start | FR-02 |
| TC-016 | Method vs class annotation precedence | Method wins | FR-01 |
| TC-017 | Custom `RateLimitKeyResolver` bean | Used for key | FR-03 |
| TC-018 | Missing user → fallback key | Falls back to IP | FR-03 |

## C. Redis / Lua
| ID | Case | Expected | Type |
|---|---|---|---|
| TC-020 | Single rule via Lua | Same results as local store | I |
| TC-021 | Time source | Script uses Redis TIME; app clock skewed ±5 min has no effect | I |
| TC-022 | Idle key TTL | Key expires after idle period | I |
| TC-023 | Redis Cluster, multi-rule | No CROSSSLOT error; hash tags in use | D |
| TC-024 | Redis 6 and 7 | Identical behavior | I |

## D. Multi-Rule Atomicity
| ID | Case | Expected | Type |
|---|---|---|---|
| TC-030 | Rules A (10/s) & B (1000/h); A fails | B tokens unchanged | I |
| TC-031 | Rule B fails, A passes | A tokens unchanged (no partial loss) | I |
| TC-032 | 1000 parallel threads, 2 rules | Admitted ≤ min(limits); counters consistent | S |
| TC-033 | Property test: random rule sets/sequences | State equals sequential model | S |

## E. Distributed Correctness
| ID | Case | Expected | Type |
|---|---|---|---|
| TC-040 | 3 app instances, limit 100/min, 500 req round-robin | Total admitted ≤ 100 (+ documented tolerance) | D |
| TC-041 | Instance added mid-test | Limit still global | D |
| TC-042 | Instance restart | No counter reset | D |

## F. Concurrency Limiter
| ID | Case | Expected |
|---|---|---|
| TC-050 | max=2, 3 concurrent slow requests | 3rd rejected |
| TC-051 | Request completes | Permit released; next admitted |
| TC-052 | Instance killed mid-request | Permit reclaimed after lease TTL |
| TC-053 | Exception in handler | Permit still released |
| TC-054 | Rate + concurrency in the same Lua script; concurrency fails | Rate state unchanged (no refund needed); and rate fails → no permit added |

## G. Resilience
| ID | Case | Expected | Type |
|---|---|---|---|
| TC-060 | Redis stopped, policy FAIL_OPEN | Requests allowed, degraded metric | F |
| TC-061 | Redis stopped, policy FAIL_CLOSED | 429 + problem type `store-unavailable` (status configurable) | F |
| TC-062 | Redis latency 2s via Toxiproxy | Timeout → failure mode applied | F |
| TC-063 | Breaker opens after N failures | Subsequent calls skip Redis | F |
| TC-064 | Half-open probe success | Breaker closes; normal service | F |
| TC-065 | Redis recovers | Counters resume correctly | F |

## H. Live Policy Updates
| ID | Case | Expected |
|---|---|---|
| TC-070 | Change limit 100→10 via update | All instances adopt without restart |
| TC-071 | Invalid snapshot published | Rejected; old policy stays |
| TC-072 | Update during load | No torn reads / errors |
| TC-073 | Missed pub/sub message | Poll fallback converges |
| TC-074 | Stale or duplicate version message | Ignored; active version never goes backwards |
| TC-075 | Propagation time after admin update (3+ instances) | Measured p50/p95; all instances converge within target (E6) |

## I. Observability
| ID | Case | Expected |
|---|---|---|
| TC-080 | Allow & reject decisions | Counters tagged policy/outcome increment |
| TC-081 | Decision latency timer | Recorded |
| TC-082 | Degraded decision | `trafficcontrol.degraded` increments |
| TC-083 | Log per decision | Contains policy, key hash, outcome |
| TC-084 | No PII in tags | Identity never a metric tag |

## J. Adaptive
| ID | Case | Expected |
|---|---|---|
| TC-090 | Simulated high load | Effective limit decreases within bounds |
| TC-091 | Load recovers | Limit returns gradually |
| TC-092 | Bounds | Never below min-factor/above max |
| TC-093 | Adaptive disabled | Static behavior identical |

## K. Performance Smoke
| ID | Case | Expected |
|---|---|---|
| TC-100 | Local store throughput | Meets documented baseline |
| TC-101 | Redis decision p99 | Meets documented target |

## L. Algorithm Conformance (run for every algorithm, local and Redis)
| ID | Case | Expected |
|---|---|---|
| TC-110 | N+1 requests inside one window | Exactly N allowed (token bucket/GCRA/fixed/log); sliding counter within documented bound |
| TC-111 | Boundary burst (requests at end of window + start of next) | Fixed window ≤ 2N; others per spec |
| TC-112 | Refill/rollover with fake clock | State matches reference model |
| TC-113 | 1000 threads on one key | No overshoot beyond tolerance |
| TC-114 | Mixed algorithms in one policy (GCRA + sliding counter) | All-or-nothing holds |
| TC-115 | Sliding window log memory bounded by per-rule max | No unbounded growth |
| TC-116 | `LEAKY_BUCKET` alias | Resolves to GCRA behavior |

## M. HTTP Contract
| ID | Case | Expected |
|---|---|---|
| TC-120 | Reject body (defaults) | `application/problem+json` with `type` URN, `title`, `status` 429, generic `detail`, `retryAfterSeconds`; **no** `policy`, `rule`, `instance` |
| TC-121 | `X-RateLimit-Reset` | Unix epoch seconds; `Retry-After` delta-seconds |
| TC-122 | `headers.style` IETF / BOTH | `RateLimit` / `RateLimit-Policy` emitted accordingly |
| TC-123 | Quota vs concurrency vs outage rejections | Distinct problem `type` values |
| TC-124 | `problem.expose-details: true` | `policy` and `rule` present |
| TC-125 | Degraded decision (fail-open / store-unavailable) | No `X-RateLimit-*` headers; 429 case has `Retry-After` ≥ 1 |
| TC-126 | `Cache-Control: no-store` on 429 | Present |

## N. Admin API & Policy Store
| ID | Case | Expected |
|---|---|---|
| TC-130 | Admin call without auth | 401/403; disabled by default |
| TC-131 | PUT valid policy | New version; all instances adopt; audit record written |
| TC-132 | Rollback | Previous snapshot active everywhere |

## O. Security (referenced by `13_SECURITY.md`)
| ID | Case | Expected |
|---|---|---|
| SEC-01 | Forged `X-Forwarded-For` from an untrusted source | Ignored; real remote address used |
| SEC-02 | Try to set the USER key via a request header | Key comes only from the authenticated principal |
| SEC-03 | Admin API / reload without authentication or role | 401/403; endpoints disabled by default |
| SEC-04 | Unsigned or invalid policy snapshot published | Rejected; last-known-good stays active |
| SEC-05 | Key-cardinality flood (millions of unique keys) | Redis memory bounded by TTLs; local store bounded by max keys |

## P. Concurrency Lease Semantics (`07 §10`)
| ID | Case | Expected |
|---|---|---|
| TC-055 | Release call fails (Redis error) | Request succeeds; WARN logged; `release_failures` +1; one background retry; permit reclaimed by TTL |
| TC-056 | Lease expires before request ends | Over-admission is bounded; `ZREM` returns 0 → `lease.expired_before_release` +1 |
| TC-057 | Release queue full | Release dropped, `release_dropped` +1, TTL reclaims |
| TC-058 | `wait=200ms`, permit frees after 100ms | Request admitted; no rate tokens consumed by failed attempts |
| TC-059 | Heartbeat keeps a long request's lease alive | Lease not reaped until completion or `max-lease-lifetime` |
| TC-05A | Async request (timeout / client abort) | Permit released via AsyncListener |

## Q. Local Store Limits (`07 §11`)
| ID | Case | Expected |
|---|---|---|
| TC-140 | Insert > `max-keys` distinct keys | Size stays ≤ max; evictions counted |
| TC-141 | Key with in-flight permits under eviction pressure | Never evicted |
| TC-142 | Idle key past idle TTL | Removed within one cleanup interval |
| TC-143 | Sliding log global cap exceeded | Treated as store failure per fail-mode |

## R. Config Validation (`06 §9`)
| ID | Case | Expected |
|---|---|---|
| TC-150 | One test per rule V-001..V-030 | Correct code, path and message; E fails startup, W only logs |
| TC-151 | Several errors at once | One report listing all |
| TC-152 | Unknown YAML key (strict) | V-024 |
| TC-153 | `@RateLimit` + `@RateLimitPolicy` on one method | V-021 |
| TC-154 | Typo in policy name | V-020 with "did you mean" |
| TC-155 | Admin enabled without security | V-027 |

## S. Key Resolution (`06 §8`)
| ID | Case | Expected |
|---|---|---|
| TC-160 | USER key, unauthenticated, default | Falls back to IP bucket |
| TC-161 | `ANONYMOUS` | One shared bucket |
| TC-162 | `SKIP` | No limit; outcome `skipped` counted |
| TC-163 | `REJECT` | 429 `identity-required` |

## T. Admin API (`05 §5`)
| ID | Case | Expected |
|---|---|---|
| TC-133 | PUT without `If-Match` | 428 |
| TC-134 | PUT with stale version | 412 |
| TC-135 | PUT invalid policy | 422, `errors[]` with V-codes; nothing applied |
| TC-136 | PUT identical content | 200, version unchanged |
| TC-137 | DELETE policy used by a path rule | 409 `policy-in-use` |
| TC-138 | Rollback to retained / non-retained version | 200 new version / 404 |
| TC-139 | Audit entry per change | actor, before, after present |

## U. Lua Contract (`20`)
| ID | Case | Expected |
|---|---|---|
| TC-170 | Wrong `script_version` | `TC_ERR_VERSION` |
| TC-171 | Out-of-range args | `TC_ERR_ARGS` |
| TC-172 | `NOSCRIPT` after `SCRIPT FLUSH` | Reload + one retry succeeds |
| TC-173 | Replay `19 §7` vectors on the test build | Exact outputs |
| TC-174 | Denied request | No state key created; only maintenance writes |
| TC-175 | Startup self-test vector | Passes; fails fast on mismatch |

## V. Adaptive (`21`)
| ID | Case | Expected |
|---|---|---|
| TC-094 | Golden signal trace | Expected multiplier trace |
| TC-095 | Constant load at threshold | No oscillation (hysteresis + cooldown) |

## W. Key Types (`06 §10`)
| ID | Case | Expected |
|---|---|---|
| TC-164 | Each key type resolves from its documented source | Correct key; hashed in Redis |
| TC-165 | `COMPOSITE` USER+ENDPOINT | Separate counters per (user, endpoint) |
| TC-166 | `ENDPOINT` with path variables (`/items/1`, `/items/2`) | Same key (route template), not raw URL |
| TC-167 | `TENANT` from claim vs spoofed header | Claim wins; HEADER source only when configured |
| TC-168 | `PlanResolver` selects `plans` entry; absent plan | Plan limits / base rules |
| TC-169 | Invalid COMPOSITE / TENANT config | V-029 / V-030 |
