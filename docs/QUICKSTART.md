# Quickstart (DRAFT — validate each step once v0.1 exists)

Goal: protect an endpoint in under 5 minutes (AC-19).

1. **Add the dependency**
```xml
<dependency>
  <groupId>io.github.ezmanish</groupId>
  <artifactId>trafficcontrol-spring-boot-starter</artifactId>
  <version>0.1.0</version>   <!-- replace with the released version -->
</dependency>
```
2. **Start Redis** (skip for local mode)
```bash
docker run -d --name redis -p 6379:6379 redis:7
```
3. **Configure** (`application.yml`)
```yaml
trafficcontrol:
  store: redis            # or: local
  redis: { url: redis://localhost:6379 }
  policies:
    login: { key: IP, fail-mode: FAIL_CLOSED, rules: [{ requests: 5, window: 1m }] }
```
4. **Annotate an endpoint**
```java
@RateLimit(requests = 100, window = "1m", key = RateLimitKey.USER)
@GetMapping("/api/products")
public List<Product> products() { ... }

@RateLimitPolicy("login")
@PostMapping("/api/login")
public LoginResponse login(@RequestBody LoginRequest r) { ... }
```
5. **Run and test**
```bash
for i in $(seq 1 6); do curl -s -o /dev/null -w "%{http_code}\n" -X POST localhost:8080/api/login; done
# 200 (or your normal status) ×5, then 429
curl -i -X POST localhost:8080/api/login      # shows Retry-After and X-RateLimit-* headers, problem+json body
```
6. **See metrics**: `GET /actuator/prometheus` → `trafficcontrol_requests_total{policy="login",result="rejected"}`.
7. **Next:** change limits live (admin API), add a concurrency cap, choose an algorithm per rule. See `06_POLICY_SPECIFICATION.md`.
