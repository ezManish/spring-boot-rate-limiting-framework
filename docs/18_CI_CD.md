# 18 — CI/CD Specification

Goal: every merge to `main` is built, tested against real Redis, measured for coverage and mutation score, scanned for vulnerable dependencies and packaged, with no manual steps. Version numbers below are policy; exact values live in the parent POM (`22_REPOSITORY_AND_DEPENDENCY_POLICY.md`).

## 1. Environment
| Item | Value |
|---|---|
| Runner OS | `ubuntu-24.04` (Docker preinstalled, required by Testcontainers) |
| JDK | Temurin 21 (build and test); extra matrix job on Temurin 25, tests only and non-blocking until Boot 3.5 on Java 25 is confirmed |
| Maven | Maven Wrapper (`./mvnw`), version pinned in `.mvn/wrapper/maven-wrapper.properties` (3.9.x) |
| Redis in CI | Testcontainers images: oldest supported `redis:6.2`, `redis:7.x` latest, newest stable `redis:8.x` (verify tags in week 1). Cluster tests: 3-node Redis Cluster container setup |
| Caching | `actions/setup-java` Maven cache keyed on `**/pom.xml` |
| Parallelism | Surefire `forkCount=1C`, `reuseForks=true`; Failsafe `forkCount=1` (containers shared per JVM via Testcontainers reuse in CI disabled for isolation) |
| Action pinning | Third-party actions pinned to commit SHA; Dependabot keeps them current |

## 2. Workflows
| File | Trigger | Jobs |
|---|---|---|
| `.github/workflows/ci.yml` | `pull_request`, `push` to `main` | `build-unit` → `integration` (Redis matrix) → `quality` → `security` → `package` |
| `.github/workflows/nightly.yml` | cron (02:00 UTC) + manual | Redis Cluster tests, stress/concurrency suite, full PIT, benchmark smoke (JMH 1 fork), Toxiproxy fault suite, non-blocking forward-compat build of the starter against Spring Boot 4.1 |
| `.github/workflows/release.yml` | tag `v*.*.*` | verify → sign → publish to Maven Central → GitHub Release with notes + SBOM |
| `.github/workflows/codeql.yml` | weekly + PR | CodeQL (Java) |
| `.github/dependabot.yml` | weekly | Maven + GitHub Actions updates |

## 3. `ci.yml` Pipeline
```
checkout → setup JDK 21 (+cache)
→ ./mvnw -B verify -DskipITs            # compile, unit tests, architecture rules, formatting check
→ ./mvnw -B verify -Pintegration        # Failsafe ITs with Testcontainers; matrix: redis 6.2 | 7.x | 8.x
→ JaCoCo report + gate; PIT (incremental on PRs)
→ OWASP Dependency-Check / vulnerability scan
→ ./mvnw -B package -DskipTests         # attach jars; upload test reports + coverage as artifacts
```
Minimal skeleton:
```yaml
name: ci
on: { pull_request: {}, push: { branches: [main] } }
permissions: { contents: read }
jobs:
  build-unit:
    runs-on: ubuntu-24.04
    strategy: { matrix: { java: [21, 25] } }
    steps:
      - uses: actions/checkout@<sha>
      - uses: actions/setup-java@<sha>
        with: { distribution: temurin, java-version: "${{ matrix.java }}", cache: maven }
      - run: ./mvnw -B verify -DskipITs
  integration:
    needs: build-unit
    runs-on: ubuntu-24.04
    strategy: { matrix: { redis: ["6.2", "7", "8"] } }
    env: { TC_REDIS_IMAGE: "redis:${{ matrix.redis }}" }
    steps:
      - uses: actions/checkout@<sha>
      - uses: actions/setup-java@<sha>
        with: { distribution: temurin, java-version: 21, cache: maven }
      - run: ./mvnw -B verify -Pintegration
```

## 4. Quality Gates (build fails if violated)
| Gate | Rule | Tool |
|---|---|---|
| Formatting | Spotless check (google-java-format) | Spotless |
| Architecture | `core` has no Spring/Redis/Servlet/Micrometer imports; module dependency direction (see `22 §3`) | Maven Enforcer `bannedDependencies` + ArchUnit |
| Unit + IT | All pass on every Redis version in the matrix | Surefire / Failsafe |
| Coverage | `core` + algorithms: line ≥ 85%, branch ≥ 75% [branch target is my proposal]; other modules ≥ 70% line [proposal] | JaCoCo `check` |
| Mutation | PIT on `core` + algorithms: mutation score ≥ 70%; PRs run incremental (changed classes), nightly runs full | PIT |
| Vulnerabilities | Fail on CVSS ≥ 7.0 in runtime/compile dependencies; suppressions need a reviewed entry with expiry. **Boot 3.5 is EOL:** Spring CVEs published after 2026-06-30 have no fix on this line; each is suppressed with reason `EOL line, no fix, accepted risk (ADR-025)`, 90-day expiry, and listed in the final report. Fixable third-party CVEs (Tomcat, Jackson, Netty/Lettuce) are fixed by BOM overrides recorded in `DEPENDENCY_OVERRIDES.md` | OWASP Dependency-Check (NVD API key as secret) |
| Conformance | Algorithm conformance suite (model = local = Lua) green | Failsafe |
| Docs | Markdown link check on `docs/` | lychee [Assumption] |
| Dependency policy | No Bucket4j in runtime scope (only `bench` and optional adapter) | Enforcer |

## 5. Branching and Review
Trunk-based. `main` protected: PR required, 1 approving review, required checks = `build-unit`, `integration`, `quality`, `security`; linear history; squash merge; Conventional Commit titles (`feat:`, `fix:`, `docs:`, `test:`, `chore:`). With 2–3 people, CODEOWNERS routes core/Lua changes to engineer A or B.

## 6. Release Pipeline (`release.yml`)
1. Tag `vX.Y.Z` on `main` (after CHANGELOG updated).
2. Re-run full `verify` + integration.
3. Build with `-Prelease`: sources jar, javadoc jar, CycloneDX SBOM, GPG signatures (key in GitHub Environment secret), reproducible timestamps.
4. Publish through the Central Portal publishing plugin (namespace must be verified; credentials in the `release` environment, manual approval required).
5. Create GitHub Release with notes, SBOM and benchmark summary.
Pre-1.0 versions (`0.x`) may be published as `-SNAPSHOT`/milestones to GitHub Packages [optional]; Central gets only release versions.

## 7. Secrets and Permissions
`permissions: contents: read` by default; release job alone gets `contents: write`. Secrets: `NVD_API_KEY`, `GPG_PRIVATE_KEY`, `GPG_PASSPHRASE`, `CENTRAL_USERNAME`, `CENTRAL_PASSWORD`. No secrets on PRs from forks.

## 8. Repository Hygiene Files
`README.md`, `QUICKSTART.md`, `LICENSE` (Apache-2.0), `CHANGELOG.md`, `CONTRIBUTING.md`, `SECURITY.md` (private vulnerability reporting), `CODEOWNERS`, `.editorconfig`, `.gitignore`, `.github/PULL_REQUEST_TEMPLATE.md`.

## 9. Definition of Done for the Pipeline
A new contributor can open a PR and, within about 15 minutes [target], see all required checks pass or fail with actionable output; main is always releasable.
