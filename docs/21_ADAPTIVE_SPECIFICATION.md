# 21 — Adaptive Controller Specification

Status: Should (cut-order item 1). Values below are **initial hypotheses** to be tuned and judged in experiment E8; they are specified exactly so implementation is deterministic and testable.

## 1. Scope
Local per-instance controller. Scales **rate** limits of policies that opt in (`adaptive.enabled: true`). Does not scale concurrency limits and does not coordinate across instances (future work). Default OFF, with a global kill switch.

## 2. Inputs (sampled every `T = 5 s`)
| Signal | Source | Smoothing |
|---|---|---|
| `cpu` | JVM process CPU load (`OperatingSystemMXBean`), 0..1 | EWMA α = 0.3 |
| `inflight_ratio` | in-flight HTTP requests / configured max threads (Tomcat) | EWMA α = 0.3 |
| `p95_ms` | Micrometer `http.server.requests` p95 over the last interval | none (histogram) |
| `err5xx` | 5xx / total requests over the last interval (ignore if < 20 requests) | none |
Latency target `adaptive.target-p95` is **required** when adaptive is enabled (no default; avoids a meaningless guess).

## 3. Overload / Healthy Definitions (hysteresis)
| State | Condition |
|---|---|
| overloaded interval | any of: `cpu > 0.85`, `inflight_ratio > 0.80`, `p95_ms > target-p95`, `err5xx > 0.05` |
| healthy interval | all of: `cpu < 0.70`, `inflight_ratio < 0.60`, `p95_ms < 0.8 × target-p95`, `err5xx < 0.01` |
| neutral | otherwise (multiplier unchanged) |
A decrease needs **2 consecutive** overloaded intervals; an increase needs **6 consecutive** healthy intervals (30 s).

## 4. Control Law (AIMD)
```
m ∈ [m_min, 1.0], initial m = 1.0, m_min = adaptive.min-multiplier (default 0.25)
every T:
  if 2 consecutive overloaded and now ≥ cooldown_until:  m = max(m_min, m × 0.7);  cooldown_until = now + 15 s
  else if 6 consecutive healthy:                          m = min(1.0, m + 0.05)
```
Effective limits: `R_eff = max(1, floor(R × m))`, `B_eff = max(1, floor(B × m))`; window unchanged. Applied by the engine when building the rule arguments; no Redis state is rewritten.

## 5. Safeguards
Kill switch: `trafficcontrol.adaptive.enabled=false` (global) or per policy; `adaptive.freeze=true` pins the current `m`. Multiplier is never below `m_min` or above 1.0. A missing/NaN signal is treated as neutral and counted in `trafficcontrol.adaptive.signal_errors`.

## 6. Observability
Gauges: `trafficcontrol.adaptive.factor{policy}`, `trafficcontrol.adaptive.signal{name}`; counters: `...adaptive.decreases`, `...adaptive.increases`. Each change logs INFO with the triggering signal.

## 7. Tests
TC-090..093 plus: deterministic replay of a signal trace → expected multiplier trace (golden file); no oscillation under constant load at the threshold (cooldown + hysteresis); frozen/disabled behaviour identical to static.

## 8. Evaluation (E8)
Static vs adaptive under step, ramp and spike load; report goodput, p99, rejection rate, recovery time, oscillation count; report negative results.
