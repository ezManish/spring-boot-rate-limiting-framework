# 06 — Policy Specification

A **policy** = rate rules + concurrency + identity + endpoint + plan. Policies are immutable, versioned snapshots. YAML shape follows `01_PRD.md §10.3`.

## 1. Schema
```yaml
trafficcontrol:
  enabled: true
  store: redis                      # local | redis
  fail-mode: FAIL_OPEN              # default when store unavailable
  fail-closed-status: 429
  trusted-proxies: ["10.0.0.0/8"]
  headers: { enabled: true, style: LEGACY }   # LEGACY | IETF | BOTH

  policies:
    <name>:
      key: USER | IP | API_KEY | JWT_CLAIM | TENANT | ENDPOINT | GLOBAL | COMPOSITE | CUSTOM
      components: [USER, ENDPOINT]      # only with key: COMPOSITE (2-3 entries)
      api-key-header: X-API-Key         # API_KEY (default X-API-Key)
      claim: sub                        # JWT_CLAIM (default sub)
      tenant: { source: CLAIM, name: tenant_id }   # TENANT; source CLAIM | HEADER
      key-resolver: myKeyResolver       # CUSTOM (bean name)
      plan-resolver: myPlanResolver     # optional (bean name); selects an entry of `plans`
      algorithm: TOKEN_BUCKET         # default for rules without own algorithm
      fail-mode: FAIL_OPEN | FAIL_CLOSED
      on-missing-key: FALLBACK_IP | ANONYMOUS | SKIP | REJECT   # default FALLBACK_IP
      rules:                          # ALL must pass (AND, atomic)
        - id: per-minute              # optional, defaults to r1, r2...
          algorithm: GCRA             # optional per-rule override
          requests: 100
          window: 1m
          burst: 100                  # token bucket / GCRA only
      concurrency:                    # evaluated in the SAME atomic step as rules
        max: 3
        wait: 0ms                     # 0 = reject immediately
        lease-ttl: 30s
      plans:                          # optional overrides by plan
        FREE: { requests: 100,   window: 1h }
        PRO:  { requests: 10000, window: 1h }
      adaptive: { enabled: false, min-multiplier: 0.25 }

  rules:                              # annotation-free matching
    - { path: /api/login, method: POST, policy: login }
    - { path: /api/reports/**, policy: heavy-report }
```

## 2. Algorithms
| Value | State per key | Notes |
|---|---|---|
| `TOKEN_BUCKET` | tokens, lastRefill | Bursts up to `burst`; default |
| `GCRA` (alias `LEAKY_BUCKET`) | theoretical arrival time (TAT) | One value per key; smooth rate; low memory; leaky-bucket-as-meter |
| `FIXED_WINDOW` | count, windowStart | Cheapest; up to 2× burst at window edge |
| `SLIDING_WINDOW_COUNTER` | prevCount, currCount, windowStart | Weighted estimate; bounded error |
| `SLIDING_WINDOW_LOG` | timestamp set | Exact; O(n) memory per key; first to cut among algorithms |
Leaky-bucket-as-queue (shaping) is an **overflow strategy**, not an algorithm (Could, last in cut order).

## 3. Field Rules
| Field | Rule |
|---|---|
| `window` | `^\d+(ms|s|m|h|d)$`, ≥ 1s |
| `requests`, `concurrency.max` | integer ≥ 1 |
| `burst` | ≥ 1; default `requests`; rejected for window algorithms |
| `concurrency.lease-ttl` | required in Redis mode; > expected max handler time |
| `fail-mode` | enum; missing → global default |
| `rules[].id` | unique within policy |
| `algorithm` | enum above; unknown → startup failure |
Startup validation fails fast with policy + field in the message (FR-46).

## 4. Evaluation Semantics
1. Resolve policy: method annotation > class annotation > path rule (most specific) > default > none.
2. Resolve identity key; apply plan override.
3. Apply adaptive multiplier to rate limits (if enabled).
4. Evaluate **all rate rules and the concurrency rule in one atomic step** (Redis: one Lua script; local: one lock per key). Commit only if every rule passes; otherwise write nothing. No token refund logic exists or is needed.
5. The decision reports the **most restrictive** rule (lowest remaining ratio) for headers and `rule` in the problem body.
6. Permit released in `finally` (separate call); lease TTL reclaims lost permits.

## 5. Failure Semantics
| Mode | Behavior when store unreachable / breaker open |
|---|---|
| FAIL_OPEN | Allow, `degraded=true`, `trafficcontrol.degraded` counter |
| FAIL_CLOSED | Reject with **429** (status configurable), problem type `store-unavailable`, `Retry-After` = breaker wait |
Examples: `login` → FAIL_CLOSED; `products` → FAIL_OPEN.

## 6. Live Updates
Validate → compile to `PolicySnapshot(version=N+1)` → store + publish `{version, checksum}` → instances fetch, validate, **atomically swap**. Invalid snapshot rejected, last-known-good kept. Poll fallback if a pub/sub message is missed. In-flight requests finish on the snapshot they started with. Admin API (Should) is secured, disabled by default, audit-logged.

## 7. Examples
```yaml
login:   { key: IP,   fail-mode: FAIL_CLOSED, rules: [{requests: 5, window: 1m}] }
search:  { key: USER, rules: [{algorithm: GCRA, requests: 10, window: 1s},
                              {algorithm: SLIDING_WINDOW_COUNTER, requests: 1000, window: 1h}] }
export:  { key: USER, rules: [{requests: 20, window: 1m}], concurrency: {max: 2, lease-ttl: 5m} }
```

## 8. Key-Resolution Failure Behavior (`on-missing-key`)
Applies when the configured key type cannot be resolved (for example `USER` on an unauthenticated request, missing API-key header, missing JWT claim).
| Value | Behavior |
|---|---|
| `FALLBACK_IP` (default) | Use the client IP key; metric tag `key_source=fallback` |
| `ANONYMOUS` | Use one shared bucket per policy for all unidentified callers |
| `SKIP` | No limit applied (counted in `trafficcontrol.requests{outcome=skipped}`); use only for optional-auth routes |
| `REJECT` | 429 with problem kind `identity-required` |
If even the IP cannot be determined (no remote address), the value is treated as unresolved and the same setting applies (`FALLBACK_IP` degrades to `ANONYMOUS`). IP resolution always honors `trusted-proxies`.

## 9. Validation Rules (exact)
All errors are collected, then startup fails once with one report (and the admin API returns them as `errors[]`). Severity E = error, W = warning (logged, startup continues).
| Code | Sev | Rule |
|---|---|---|
| V-001 | E | Policy name matches `^[a-z][a-z0-9-]{1,62}$` |
| V-002 | E | A policy has at least one rate rule or a `concurrency` block; at most 8 rate rules |
| V-003 | E | Rule `id` unique in the policy, `^[a-z0-9-]{1,32}$` (defaults `r1`, `r2`, …) |
| V-004 | E | `requests` integer in 1..1,000,000,000 |
| V-005 | E | `window` matches `^\d+(ms|s|m|h|d)$` |
| V-006 | E | Window between 1 s and 7 d |
| V-007 | E | `burst` only for TOKEN_BUCKET/GCRA, ≥ 1; `burst × window_ms ≤ 4×10^15` and `requests × window_ms ≤ 4×10^15` |
| V-008 | E | GCRA: `ceil(window_µs / requests) ≥ 1` |
| V-009 | E | SLIDING_WINDOW_LOG: `requests ≤ 10,000` |
| V-010 | E | `concurrency.max` in 1..100,000 |
| V-011 | E | `concurrency.lease-ttl` in 1 s..1 h (default 30 s in Redis mode) |
| V-012 | W | `lease-ttl` below the configured server/async request timeout (leases could expire before requests finish) |
| V-013 | E | `concurrency.wait` in 0..30 s |
| V-014 | E | `key` is a known type; `CUSTOM` needs `key-resolver: <bean name>` that exists; `API_KEY` needs `api-key-header`; `JWT_CLAIM` needs `claim` |
| V-015 | E | `fail-mode` ∈ {FAIL_OPEN, FAIL_CLOSED}; `fail-closed-status` ∈ {429, 503} |
| V-016 | E | `on-missing-key` is a known value |
| V-017 | E | `plans`: overrides reference existing rule ids and satisfy V-004..V-009 |
| V-018 | E | Path rules: valid pattern, existing policy, no duplicate (path, method) |
| V-019 | W | Overlapping path rules of equal specificity |
| V-020 | E | Annotation references an existing named policy |
| V-021 | E | `@RateLimit` and `@RateLimitPolicy` on the same element |
| V-022 | E | `store: redis` requires `redis.url`; concurrency with `store: local` is allowed (local semaphore) |
| V-023 | E | `adaptive.min-multiplier` in (0, 1); `target-p95` present when adaptive is enabled |
| V-024 | E | Unknown configuration keys (strict mode, default) |
| V-025 | E | `trusted-proxies` entries are valid CIDRs |
| V-026 | E | `headers.style` ∈ {LEGACY, IETF, BOTH} |
| V-027 | E | `admin.enabled` requires a security filter chain on the admin path |
| V-028 | W | Two identical rules in one policy |
| V-029 | E | `COMPOSITE` requires 2–3 distinct `components` from {USER, IP, API_KEY, JWT_CLAIM, TENANT, ENDPOINT}; no nesting; `components` is invalid with any other key type |
| V-030 | E | `TENANT` requires `tenant.source` and `tenant.name`; `source: HEADER` is allowed but logs a warning that the header is client-controlled unless set by a trusted gateway |
**Report format:**
```
Invalid trafficcontrol configuration (2 errors, 1 warning):
  [V-006] policies.login.rules[0].window: '1x' is not a valid duration (use ms|s|m|h|d, 1s to 7d)
  [V-020] @RateLimitPolicy("logn") on AuthController#login: unknown policy (did you mean 'login'?)
Action: fix the properties above and restart.
```
Implemented as a Spring `FailureAnalyzer`, so it prints the report instead of a stack trace.

## 10. Key Types (final, ADR-037)
Every key is resolved to a string, then hashed (`clientHash`, `07 §2`). Resolution failure follows `on-missing-key` (§8).
| Type | Resolved from | Notes |
|---|---|---|
| `USER` | Authenticated principal name | Needs authentication; otherwise unresolved |
| `IP` | Client IP honoring `trusted-proxies` | Never trusts `X-Forwarded-For` from untrusted sources |
| `API_KEY` | Header `api-key-header` (default `X-API-Key`) | Stored only as a hash |
| `JWT_CLAIM` | Claim `claim` (default `sub`) of the authenticated JWT | |
| `TENANT` | `tenant.source: CLAIM` (default) reads claim `tenant.name`; `HEADER` reads that header | HEADER is spoofable unless set by a trusted gateway (V-030) |
| `ENDPOINT` | Matched route template + HTTP method (never the raw URL) | Bounded cardinality; typically used inside `COMPOSITE` |
| `GLOBAL` | Constant `global` | One shared bucket per policy |
| `COMPOSITE` | Concatenation of 2–3 `components`, separated by `\|`, then hashed | |
| `CUSTOM` | Bean `key-resolver` implementing `RateLimitKeyResolver` | Core SPI is framework-neutral (`RateLimitContext`) |
**Plans:** `PLAN` is deliberately not a key type. Plan-specific limits come from `plans:` selected by a `PlanResolver` (default: JWT claim `plan`, absent ⇒ base rules). A shared cap per plan is expressed with a `CUSTOM` key that returns the plan name.
