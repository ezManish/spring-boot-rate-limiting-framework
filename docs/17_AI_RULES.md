# 17 — AI Rules (for AI coding assistants working in this repo)

Use this file as the instruction set for Claude/Copilot/Cursor-style assistants. Read docs 00–23 and QUICKSTART before changing code. If a request conflicts with them, flag it rather than silently deviating.

## 1. Non-Negotiable Architecture Rules
1. `core` must **never** import Spring, Redis, Micrometer, or servlet classes.
2. Integrations go through SPIs (`RateLimitStore`, `RateLimitKeyResolver`, `PlanResolver`, `PolicyProvider`, `Clock`, `DecisionListener`, `FailureStrategy`).
3. Bucket4j must **not** appear in any runtime dependency except the optional adapter module and `bench`.
4. Redis evaluation = **one Lua script per decision**; evaluate all rate rules **and the concurrency permit**, commit only if all pass, otherwise write nothing. No refund logic.
5. Time inside Redis logic comes from `redis.call('TIME')` — never from the app clock.
6. Every Redis key touched by one script shares the hash tag; layout is `tc:{<clientHash>}:<policy>:<ruleId>`; never put a raw user ID or IP in a key.
6a. Every algorithm implements `RateLimitAlgorithm`, has a local and a Lua fragment, and must pass the shared conformance suite before merge.
6b. FAIL_CLOSED returns 429 (configurable) with problem type `store-unavailable`; rejections use `application/problem+json`.
7. Policies are **immutable snapshots**; updates swap a reference atomically.
8. Concurrency permits always have a **lease TTL**; always release in `afterCompletion`.
9. Failure mode (FAIL_OPEN/FAIL_CLOSED) is per policy and must be covered by tests.
10. The admin API must stay disabled by default, authenticated, role-checked and audit-logged.

## 2. Coding Standards
- Java 21 (records, sealed interfaces, pattern matching welcome); Spring Boot 3.5.x; Maven. Keep Spring types out of everything except the starter so a later Boot 4 migration touches one module.
- Constructor injection; no field `@Autowired`.
- No static mutable state; thread-safety documented on public classes.
- Fail fast on invalid config with messages naming policy + field.
- No blocking calls without timeouts; no unbounded caches/maps.
- Java package root `io.github.ezmanish.trafficcontrol` (groupId `io.github.ezmanish`); artifacts `trafficcontrol-*`.

## 3. Testing Rules
- Every behavior change adds/updates tests mapped to `09_TEST_CASES.md` IDs.
- Use fake `Clock` for deterministic time in core tests.
- Redis behavior tested with Testcontainers, not mocks.
- Never weaken or delete a failing test to pass CI; explain and fix.
- Concurrency tests must be repeatable (seeds, bounded waits).

## 4. Security & Privacy Rules
- Never put user IDs, IPs, or tokens in metric tags or unhashed logs/keys.
- Never trust `X-Forwarded-For` unless from a configured trusted proxy.
- Don't commit secrets; Redis creds via config/env.
- Secure any actuator/management endpoint by default.

## 5. Observability Rules
- Every decision emits metric + log (see `11_OBSERVABILITY.md`).
- Keep tag cardinality bounded; use documented metric names.

## 6. Scope Discipline
- Implement only what the current phase in `14_IMPLEMENTATION_PLAN.md` requires.
- Do not add new algorithms, modules, or dependencies without a decision-log entry.
- If behind schedule, cut in this order: adaptive → admin UI/dashboards → distributed concurrency → sliding window log → overflow strategies. **Never** cut correctness tests or benchmarks.
- Mark unknowns as `[Assumption]` instead of inventing facts.

## 7. Workflow for AI Assistants
1. Identify requirement IDs (FR/US/TC/AC) affected.
2. State plan briefly; list files to change.
3. Implement smallest coherent change.
4. Add/adjust tests; run `mvn -q verify`.
5. Update docs/decision log if behavior or design changed.
6. Summarize: what changed, tests run, open questions.

## 8. Things to Refuse / Escalate
- Adding Spring/Redis types to `core`.
- Replacing the atomic Lua approach with multi-call logic.
- Disabling security checks or fail-closed on sensitive policies.
- Fabricating benchmark numbers — only report measured results from `bench/`.

## 9. Definition of Done
Compiles • tests green • docs updated • metrics/logs in place • no new warnings • decision log updated if needed.

## 10. Prompt Snippet
> You are working on the API Traffic Control Framework. Follow `/docs/17_AI_RULES.md`. Respect the module boundaries in `03_ARCHITECTURE.md`. Work only on phase N tasks. Reference requirement/test IDs. Ask before deviating.

## 11. Contract and Spec Rules (added after review)
11. Implement algorithms exactly as in `19_ALGORITHM_SPECIFICATION.md` and the script exactly as in `20_REDIS_LUA_CONTRACT.md`. Local, Lua and the reference model must agree on every vector; never "fix" a test by changing a vector without updating the spec and bumping `SCRIPT_VERSION`.
12. Rejection bodies stay generic (`05 §2.2`): never add policy names, rule ids, keys, identities or paths unless the opt-in flags are set.
13. Local state is bounded (`07 §11`): no raw `ConcurrentHashMap` keyed by client identity.
14. Lease release never fails or blocks a request (`07 §10`).
15. Configuration errors are reported using codes V-001..V-030 (`06 §9`); do not invent ad-hoc messages.
16. Obey the module dependency direction and version policy (`22`); new dependencies need a decision-log entry; keep CI green (`18`).
17. Do not claim adaptive tuning values are final; they are hypotheses (`21`).
