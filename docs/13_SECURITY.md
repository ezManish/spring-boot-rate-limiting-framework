# 13 — Security

## 1. Security Goals
1. The limiter cannot be trivially bypassed (identity spoofing, header manipulation).
2. The limiter itself isn't a DoS vector (key explosion, expensive evaluation).
3. Sensitive endpoints stay protected during outages (FAIL_CLOSED).
4. Control plane (policy updates) is authenticated and tamper-evident.
5. No PII leakage via keys, logs or metrics.

## 2. Threat Model (STRIDE-lite)
| Threat | Example | Mitigation |
|---|---|---|
| Spoofing identity | Forged `X-Forwarded-For` to dodge IP limit | Trust forwarded headers only from configured proxies; use rightmost-trusted-hop logic |
| Spoofing user | Unauthenticated user claims another ID | Key USER only from authenticated principal, never raw header |
| Tampering policy | Attacker publishes policy on Redis pub/sub | Redis AUTH/ACL + TLS; signed/checksummed snapshots [Assumption]; schema validation |
| Repudiation | No audit of changes | Log every policy version change with actor/source |
| Info disclosure | PII in Redis keys/metrics | Hash identity; no identity tags |
| DoS via key cardinality | Attacker rotates IPs/keys to fill Redis | Idle TTL on all keys; memory cap/maxmemory policy; per-IP pre-limit at edge |
| DoS via expensive path | Large rule sets | Max rules per policy; validated at startup |
| Elevation | Unauthenticated policy admin API or reload | Admin API disabled by default; authentication + role check required; every change audit-logged (who, when, before, after) |
| Fail-open abuse | Attacker induces Redis failure to bypass | FAIL_CLOSED on sensitive routes; alert on degraded |

## 3. Redis Hardening
- Require AUTH/ACL user limited to needed commands (`EVALSHA`, `EVAL`, `HGET/HSET`, `ZADD/ZREM/ZREMRANGEBYSCORE/ZCARD`, `PUBLISH/SUBSCRIBE`, `GET/SET`, `EXPIRE`, `TIME`).
- TLS in transit; network isolation; no public exposure.
- Separate key prefix per environment/app.

## 4. Identity & Header Handling
- Config: `trusted-proxies`, `ip-header`.
- Normalize IPv6 (/64 grouping option [Assumption]).
- Fallback chain documented; unauthenticated traffic keyed by IP.

## 5. Brute-Force Posture
`login`-style policies: strict rate per IP **and** per account identifier (two rules, atomic), FAIL_CLOSED, optional exponential back-off [Assumption].

## 6. Data Protection
Keys: `SHA-256(identity)` truncated; logs show `key_hash`; metrics never carry identity; no request bodies logged.

## 7. Supply Chain
Maven dependency scanning (OWASP Dependency-Check), pinned versions, SBOM [Assumption], Bucket4j not in runtime dependency graph.

## 8. Security Tests
| Test | Ref |
|---|---|
| Forged XFF from untrusted source ignored | SEC-01 |
| User key cannot be set via header | SEC-02 |
| Admin API / reload requires auth and role | SEC-03 |
| Unsigned/invalid snapshot rejected | SEC-04 |
| Key cardinality flood stays bounded in Redis memory | SEC-05 |
| Fail-closed on login during Redis outage | TC-061 |
| No PII in metrics/logs | TC-083/084 |

## 9. Residual Risks
Distributed attackers with many IPs; shared NAT false positives; Redis failover counter resets. Documented in final report.

## 10. Error-Body Disclosure (added)
Rejection bodies are generic by default: no policy name, rule id, key, identity or request path (`05 §2.2`). `problem.expose-details` and `problem.include-instance` are opt-in and documented as unsuitable for public APIs. Fixed `detail` strings prevent the limiter from leaking internals or acting as an oracle (`identity-required` is a 429, not 401/403).

## 11. Admin API Controls (added)
Disabled by default; startup fails if enabled without a security filter chain; role `TRAFFICCONTROL_ADMIN`; optimistic concurrency via `If-Match`; every change audited (actor, before, after); the admin API is itself rate limited; validation errors reuse the startup validator and contain no secrets.

## 12. Supply Chain and CI (added)
Dependency vulnerability gate (CVSS ≥ 7 fails the build), CodeQL, Dependabot, pinned third-party actions, signed releases and SBOM (`18_CI_CD.md`, `22`).

## 13. Residual Risk: End-of-Life Spring Boot 3.5 (ADR-025)
The project builds on Spring Boot 3.5.x, which has had no free security fixes since 2026-06-30. Consequences: Spring CVEs found later stay unpatched in this line; the dependency gate uses an expiring suppression policy (`18 §4`); third-party libraries are patched through BOM overrides. The final report states this limitation and the Boot 4 migration path.
