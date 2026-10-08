# 04 — User Stories

Format: **As a** persona, **I want** capability, **so that** value. Priority: M/S/C (Must/Should/Could). Phase refers to `14_IMPLEMENTATION_PLAN.md`.

## Epic A — Developer Experience
| ID | Story | Acceptance hint | P | Phase |
|---|---|---|---|---|
| US-01 | As a developer, I want to add `@RateLimit(requests=100, window="1m", key=USER)` to an endpoint so that it is protected without writing Redis code | Endpoint returns 429 after 100 calls/min per user | M | 2 |
| US-02 | As a developer, I want to add the starter dependency and have auto-configuration work so that setup is under 5 minutes | Timed onboarding test | M | 2 |
| US-03 | As a developer, I want `@RateLimitPolicy("login")` to reference a YAML policy so that limits live in config | Policy resolved by name; unknown name fails at startup | M | 2/4 |
| US-04 | As a developer, I want clear startup errors for invalid windows/limits so that misconfig is caught early | Fail-fast message names the policy and field | S | 4 |

## Epic B — Identity & Keys
| ID | Story | P | Phase |
|---|---|---|---|
| US-05 | As a developer, I want to limit by USER, IP, API key, JWT claim, tenant, endpoint, global or a composite of these (and select plan-specific limits through a PlanResolver) so that the right party is throttled | M | 2 |
| US-06 | As a developer, I want a custom `KeyResolver` so that I can limit by API key or header | S | 2 |
| US-07 | As a product owner, I want plan-based limits (free vs pro) so that tiers get different quotas | S | 4 |

## Epic C — Limit Correctness
| ID | Story | P | Phase |
|---|---|---|---|
| US-08 | As an operator, I want limits enforced globally across N instances so that scaling out doesn't multiply the limit | M | 3 |
| US-09 | As a developer, I want multiple rules (e.g. 10/s and 1000/h) evaluated all-or-nothing so that a denied request consumes no tokens | M | 3/4 |
| US-10 | As a developer, I want a distributed concurrency limit (evaluated atomically with rate rules, lease TTL) so that slow endpoints can't exhaust threads and crashed requests don't leak permits | M (cut-order 3) | 4 |

## Epic D — HTTP Behavior
| ID | Story | P | Phase |
|---|---|---|---|
| US-11 | As an API client, I want 429 with `Retry-After` so that I know when to retry | M | 2 |
| US-12 | As an API client, I want `X-RateLimit-Limit/Remaining` on responses so that I can self-throttle | M | 2 |

## Epic E — Resilience & Operations
| ID | Story | P | Phase |
|---|---|---|---|
| US-13 | As a security engineer, I want login limits to FAIL_CLOSED when Redis is down so that brute-force protection never disappears | M | 3 |
| US-14 | As an SRE, I want read endpoints to FAIL_OPEN so that a Redis outage doesn't take down the API | M | 3 |
| US-15 | As an SRE, I want a circuit breaker on Redis so that latency from a dead store doesn't cascade | M | 3 |
| US-16 | As an SRE, I want to change policies live without redeploy so that I can respond to abuse quickly | S | 4 |

## Epic F — Observability & Adaptivity
| ID | Story | P | Phase |
|---|---|---|---|
| US-17 | As an SRE, I want Prometheus metrics (allowed, rejected, latency, errors) per policy so that I can build dashboards | M | 5 |
| US-18 | As an SRE, I want a log line per decision so that I can audit throttling | M | 5 |
| US-19 | As an operator, I want limits to adapt to system load so that the service sheds load gracefully | S | 5 |

## Epic G — Proof
| ID | Story | P | Phase |
|---|---|---|---|
| US-20 | As an evaluator, I want benchmarks against Bucket4j and static limits so that claims are measurable | M | 6 |
| US-21 | As a new user, I want a demo app and docs so that I can see it working | M | 6 |

## Epic H — Algorithms & Admin (added from full PRD)
| ID | Story | P | Phase |
|---|---|---|---|
| US-22 | As a developer, I want to choose the algorithm per rule (token bucket, fixed window, sliding window counter/log, GCRA) so that I can trade burstiness, accuracy and memory | M | 1–5 |
| US-23 | As a library author, I want to plug in my own algorithm, store or resolver via SPI | M | 1 |
| US-24 | As a platform engineer, I want a secured admin API with audit log and rollback for policies | S | 4 |
| US-25 | As an API client developer, I want a problem+json body that tells me whether I hit a quota, a concurrency cap or a store outage | M | 2/3 |
| US-26 | As a security engineer, I want login throttling by IP before authentication and by user after it | M | 2 |
