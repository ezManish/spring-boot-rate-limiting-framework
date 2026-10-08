# Contributing to TrafficControl

Thank you for contributing to TrafficControl!

## Coding Standards & Architectural Invariants

Before making code changes, please review the documentation in [`docs/`](docs/), specifically:
- [`docs/17_AI_RULES.md`](docs/17_AI_RULES.md)
- [`docs/03_ARCHITECTURE.md`](docs/03_ARCHITECTURE.md)
- [`docs/22_REPOSITORY_AND_DEPENDENCY_POLICY.md`](docs/22_REPOSITORY_AND_DEPENDENCY_POLICY.md)

Key rules to keep in mind:
1. `trafficcontrol-core` must **never** import Spring, Redis, Lettuce, Micrometer, or Servlet classes.
2. Integrations go through SPIs (`RateLimitStore`, `RateLimitKeyResolver`, `PlanResolver`, `Clock`, etc.).
3. Redis evaluation is always one atomic Lua script per decision (`tc_decide`).
4. Rejections return HTTP 429 using RFC 9457 `application/problem+json`.
5. Code style is formatted with Spotless (`mvn spotless:apply`).

## Building & Testing

```bash
# Build and run unit tests
mvn clean verify -DskipITs

# Run all tests including Testcontainers integration tests
mvn clean verify -Pintegration
```

## Commit Conventions

Use Conventional Commits (`feat:`, `fix:`, `docs:`, `test:`, `refactor:`, `chore:`).
