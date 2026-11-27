# Design

## Context

The local `observability` profile (change `add-observability`) scrapes `/actuator/prometheus` on all four Java services and adds a `service` label per target. The detector metrics already exist:

| Micrometer name | Prometheus series | Tags | Registered |
| --- | --- | --- | --- |
| `pumpwatch.flag.latency` (Timer, percentile histogram) | `pumpwatch_flag_latency_seconds_bucket`, `_count`, `_sum`, `_max` | none | pipeline, at topology build |
| `pumpwatch.scorer.scored` / `.dropped` / `.stale` (Counter) | `pumpwatch_scorer_scored_total`, `pumpwatch_scorer_dropped_total`, `pumpwatch_scorer_stale_total` | `model` | pipeline, at startup |
| `pumpwatch.late.records` (Counter) | `pumpwatch_late_records_total` | `topic` | pipeline, at topology build |
| `pumpwatch.connector.dead_letter` (Counter) | `pumpwatch_connector_dead_letter_total` | `connector` | connectors, per enabled connector |
| `pumpwatch.connector.last_fetch.age.seconds` (Gauge) | `pumpwatch_connector_last_fetch_age_seconds` | `connector` | connectors, per enabled connector |

Every connector is disabled in `.env.example`, so on a default stack the connector series do not exist and their panels show "No data". The pipeline series always exist, at zero.

## Decisions

**Widen the latency histogram.** Micrometer timers default to buckets between 1 ms and 30 s (checked in micrometer-core 1.15 `AbstractTimerBuilder`). A quantile above the top finite bucket is reported as that bucket's bound, so p95 would flatten at 30 s. The timer gets `.serviceLevelObjectives(Duration.ofSeconds(60)).maximumExpectedValue(Duration.ofMinutes(10))`. The 60 s bucket makes the target an exact bucket edge; 10 minutes keeps resolution above it. Alternatives: alert on `pumpwatch_flag_latency_seconds_max` (a maximum, not p95) or on the 30 s bucket (a stricter target the PRD does not state). Both were rejected.

**Queries.** Rates use `[5m]`; flag latency uses `[15m]` because flags are rare (30 minute cooldown per ticker). Each query aggregates with `sum by` or `max by` on the metric's own tag, so the scrape labels (`job`, `instance`, `service`) do not split lines. On an idle stack `histogram_quantile` returns nothing and counters return zero, so panels show "No data" or a flat zero, never an error.

**Alert thresholds.**
- `FlagLatencyHigh`: p95 over 15 minutes above 60 s for 5 minutes. The PRD number.
- `ScorerDropping`: any dropped (unreadable) input for 5 minutes. A drop is a schema or bug problem, so any sustained rate matters.
- `ConnectorDeadLetters`: any dead letter in the last 10 minutes, for 1 minute.
- `ConnectorStalled`: last-fetch age above 120 s for 5 minutes. 120 s is the connectors' `max-fetch-age` default, which already turns their health DOWN.

All are `severity: warning`, matching `SiteSlow` and `ApiErrors`. Summaries use only labels, so promtool tests can match them exactly.

**Tests.** `promtool test rules` proves each alert fires and stays quiet on idle input. A dependency-free `node --test` file reads the Java sources for `Counter/Timer/Gauge.builder("...")` names, converts them to Prometheus names, and fails when the dashboard or rules use any other `pumpwatch_*` name. The runtime check script confirms the same names against the running Prometheus.

## Risks

- Flag latency is measured from the scored window's end. Feature panes wait a 2 minute grace before closing, so live p95 is likely above 60 s and `FlagLatencyHigh` may fire whenever live data flows. The alert reports the target honestly; changing the measurement or the target is a separate owner decision.
- Replay keeps historic event times, so latency during a replay equals the data's age and `FlagLatencyHigh` fires. The docs say so.
- Adding buckets adds about 10 series to the pipeline's scrape. Negligible.
