# TrafficControl: Declarative Distributed API Traffic Control for Spring Boot

[![CI](https://github.com/ezmanish/spring-boot-rate-limiting-framework/actions/workflows/ci.yml/badge.svg)](https://github.com/ezmanish/spring-boot-rate-limiting-framework/actions/workflows/ci.yml)
[![License](https://img.shields.io/badge/License-Apache_2.0-blue.svg)](https://opensource.org/licenses/Apache-2.0)

**TrafficControl** is a modular, developer-first traffic control and rate-limiting framework for Java and Spring Boot applications. It unifies rate limiting, concurrency limiting, identity resolution, plan tiers, and dynamic live policy updates into a single declarative policy model.

## Features

- **Declarative & Ergonomic**: Protect endpoints with `@RateLimit(...)` or named `@RateLimitPolicy("policy-name")` annotations or YAML path rules.
- **Atomic Multi-Rule Evaluation**: Evaluates multiple rate rules (e.g. per-second and per-hour) and distributed concurrency permits in a single atomic Lua script—never partially consumes tokens if any rule fails.
- **Five Exact Algorithms**: Token Bucket, GCRA (Generic Cell Rate Algorithm / Leaky Bucket), Fixed Window, Sliding Window Counter, and Sliding Window Log behind a unified SPI and conformance suite.
- **Framework-Independent Core**: Core engine (`trafficcontrol-core`) has zero dependencies on Spring, Redis, or Servlets.
- **Distributed & Cluster-Safe**: Native Redis support with cluster-safe hash-tags (`{clientHash}`) and Redis `TIME` synchronization.
- **Fail-Safe & Resilient**: Per-policy `FAIL_OPEN` and `FAIL_CLOSED` handling with built-in circuit breaker.
- **RFC 9457 Problem Details & Standard Headers**: Standard HTTP 429 response bodies (`application/problem+json`), `Retry-After`, and `X-RateLimit-*` headers.
- **Full Observability**: Micrometer metrics and structured logging built-in.

## Documentation

Full architectural and design specifications are located in the [`docs/`](docs/) directory:
- [00 — Project Charter](docs/00_PROJECT_CHARTER.md)
- [01 — PRD](docs/01_PRD.md)
- [02 — TRD](docs/02_TRD.md)
- [03 — Architecture](docs/03_ARCHITECTURE.md)
- [05 — API Specification](docs/05_API_SPECIFICATION.md)
- [06 — Policy Specification](docs/06_POLICY_SPECIFICATION.md)
- [14 — Implementation Plan](docs/14_IMPLEMENTATION_PLAN.md)
- [17 — AI Rules](docs/17_AI_RULES.md)
- [19 — Algorithm Specification](docs/19_ALGORITHM_SPECIFICATION.md)
- [20 — Redis Lua Contract](docs/20_REDIS_LUA_CONTRACT.md)
- [QUICKSTART Guide](docs/QUICKSTART.md)

## License

Licensed under the Apache License, Version 2.0. See [LICENSE](LICENSE) for details.
