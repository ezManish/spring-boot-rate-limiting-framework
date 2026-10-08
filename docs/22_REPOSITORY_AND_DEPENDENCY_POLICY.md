# 22 — Repository, Dependency, Versioning and Release Policy

## 1. Coordinates (frozen)
| Item | Value |
|---|---|
| groupId | `io.github.ezmanish` |
| Java package root | `io.github.ezmanish.trafficcontrol` |
| Project name / YAML prefix / Redis key prefix | `trafficcontrol` / `trafficcontrol` / `tc` (configurable) |
| Version scheme | SemVer; `0.x` until the v1.0 release (day 110) |
| License | Apache-2.0 (SPDX header in each source file) |

## 2. Modules and Artifacts
| Directory | artifactId | Packages (under the package root) | Depends on |
|---|---|---|---|
| `core` | `trafficcontrol-core` | `.core.api`, `.core.algorithm`, `.core.concurrency`, `.core.adaptive`, `.core.spi` | nothing but the JDK (plus SLF4J API only if logging is needed [proposal]) |
| `store-local` | `trafficcontrol-store-local` | `.store.local` | `core`; Caffeine |
| `store-redis` | `trafficcontrol-store-redis` | `.store.redis` | `core`; Lettuce (`lettuce-core`) |
| `metrics` | `trafficcontrol-metrics` | `.metrics` | `core`; Micrometer core |
| `spring-boot-starter` | `trafficcontrol-spring-boot-starter` | `.spring.annotation`, `.spring.web`, `.spring.autoconfigure`, `.spring.admin` | `core`, `store-local`, `store-redis` (optional), `metrics` (optional), Spring Boot |
| `bench` | `trafficcontrol-bench` | `.bench` | all; JMH; Bucket4j; Resilience4j (never published) |
| `demo` | `trafficcontrol-demo` | `.demo` | starter (never published) |
| `bom` | `trafficcontrol-bom` | — | version alignment for users |
| parent | `trafficcontrol-parent` | — | pins versions, plugins, enforcer rules |

## 3. Dependency Direction (frozen; enforced by Maven Enforcer + ArchUnit)
```
                core
     ┌────────┬──┴──────────┬──────────┐
 store-local store-redis  metrics      (no module may depend on bench/demo)
     └────────┴──────┬──────┴──────────┘
              spring-boot-starter
                     ↑
                demo, bench
```
`core` never depends on any other module. Stores and metrics never depend on the starter or on each other.

## 4. Version Policy
| Component | Policy |
|---|---|
| Java | Build with 21 (`--release 21`); CI also runs tests on 25 (experimental, non-blocking) |
| Spring Boot | **3.5.x, latest available patch** (owner decision, ADR-025), imported through `spring-boot-dependencies` in the parent. This is the final 3.x line and has been out of OSS support since 2026-06-30: no further free security fixes for Spring Boot, Framework or Security. Mitigations: only the starter depends on Spring (`core`, `store-*`, `metrics` stay Spring-free, so a Boot 4 migration touches one module); use public APIs and `AutoConfiguration.imports` only; non-blocking CI job builds the starter against Boot 4.1; third-party libraries (Tomcat, Jackson, Netty/Lettuce) may override the BOM for security fixes, each listed in `DEPENDENCY_OVERRIDES.md`; migration to Boot 4 is documented future work |
| Maven | Wrapper pinned (3.9.x) |
| Redis | Tested: 6.2 (minimum), latest 7.x, latest 8.x. Valkey and managed Redis variants not tested in v1 |
| Lettuce, Micrometer, JUnit, Mockito | Version managed by the Boot BOM (overrides only for security fixes, recorded in `DEPENDENCY_OVERRIDES.md`; anything else needs an ADR) |
| Testcontainers, JMH, PIT, JaCoCo, Spotless, jqwik [flagged assumption] | Exact versions pinned in the parent POM properties in week 1 |
| Bucket4j, Resilience4j | `bench` only; pinned to the latest stable at project start |
Update rules: Dependabot weekly; patch/minor updates merge when CI is green; major updates and any BOM override need a decision-log entry. Spring Boot patch releases are adopted promptly. The exact resolved versions are recorded in the final report and in `bench/results/ENVIRONMENT.md`.

## 5. Compatibility Policy (public surface)
| Surface | Rule |
|---|---|
| Annotations (`@RateLimit`, `@RateLimitPolicy`, `@Limit`) | SemVer: no breaking change in a minor; deprecate for one minor before removal |
| YAML schema | Optional `schema-version: 1`; unknown keys are an error (fail fast) in `strict` mode (default) so typos never silently disable limits; new keys only in minors |
| SPIs | SemVer; new methods only as `default` methods in minors |
| `PolicySnapshot` | Carries `schemaVersion`; readers ignore unknown fields, reject a higher major, accept lower |
| Redis keys and state encoding | Stable within a major. `tc_decide` `SCRIPT_VERSION` may change in a minor only if the new script reads the old state; otherwise major + new key prefix |
| Metric names and tags | Stable within a major; additions allowed |
| HTTP contract (headers, problem types) | Stable within a major |
Rolling upgrade 1.0 → 1.1: old and new instances share Redis state safely because state encoding and the key layout are unchanged within the major; policy snapshots are forward-readable.

## 6. Release Packaging
Per release: main jars, sources jar, javadoc jar, `.asc` signatures, POM metadata required by Central (name, description, URL, license, developers, SCM), CycloneDX SBOM, `bom` artifact. Reproducible builds via `project.build.outputTimestamp`. Tag `vX.Y.Z`; `CHANGELOG.md` updated before tagging.
**Where to publish:** Maven Central through the Central Portal (namespace verification required) at v1.0; GitHub Releases/Packages for milestones. This can be decided later, but Central is recommended.

## 7. Code Standards
Spotless (google-java-format), no wildcard imports, `-Xlint:all -Werror` for `core`, SpotBugs on `core` and `store-*` [proposal], public API Javadoc required.
