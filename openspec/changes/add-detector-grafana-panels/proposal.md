# Proposal

## Why

The pipeline already exports flag latency, scorer scored/dropped/stale counters, late records and connector dead-letter and freshness metrics, but the local "Pumpwatch health" dashboard and the Prometheus alert rules ignore them. Nobody can see whether the PRD target (flag latency p95 of 60 seconds or less) holds or whether the scorer is dropping input. Issue #135.

The flag latency histogram also cannot answer the question yet. Micrometer timers publish buckets from 1 ms to 30 s by default, so `histogram_quantile` never reports more than 30 s and an alert at 60 s can never fire.

## What Changes

- `pumpwatch.flag.latency` gains a 60 s service-level bucket and a 10 minute maximum expected value, so the histogram resolves latencies on both sides of the target.
- A "Detector" row on the `pumpwatch-health` dashboard: flag latency p95 against a 60 s line, scorer scored/dropped/stale rates by model, late records by topic, connector dead letters and connector last-fetch age.
- Four alert rules in `deploy/observability/alerts.yml`: `FlagLatencyHigh`, `ScorerDropping`, `ConnectorDeadLetters`, `ConnectorStalled`, with promtool unit tests.
- A node test that checks the Detector row, the alert names, and that every `pumpwatch_*` metric the dashboard and rules use is one the Java code registers.
- `scripts/check-observability.mjs` also requires the pipeline's detector metrics in Prometheus, every Detector query to run without error, and the four rules to be loaded.
- CI runs `promtool check rules`, `promtool test rules` and the node test.
- Out of scope: notifications (Alertmanager), Kafka consumer-lag or Postgres exporters, changing how flag latency is measured, the public VM.

## Capabilities

### New Capabilities
<!-- None. -->

### Modified Capabilities
- `observability`: adds detector panels and detector alert rules to the local health dashboard.

## Impact

- `pipeline/src/main/java/dev/pumpwatch/pipeline/flagger/FlagStage.java`: one builder change on the latency timer.
- `deploy/observability/grafana/dashboards/pumpwatch-health.json`, `deploy/observability/alerts.yml`, new `deploy/observability/alerts.test.yml`.
- `scripts/check-observability.mjs`, new `scripts/observability-config.test.mjs`, `.github/workflows/ci.yml` (infrastructure job), `docs/operations/observability.md`.
- No new containers, ports, keys or cloud resources. Default and `apps` runs are unchanged.
