# Detector Grafana Panels Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Show the detector's health (flag latency p95 against 60 s, scorer drops, late records, connector dead letters and freshness) on the local Grafana dashboard and alert on it in Prometheus.

**Architecture:** Widen the flag latency timer's histogram so p95 can exceed 30 s, add a "Detector" row to the provisioned `pumpwatch-health` dashboard JSON, add a `detector` rule group to `alerts.yml` with promtool unit tests, and extend the static and runtime checks so every query uses only metric names the Java code registers.

**Tech Stack:** Micrometer 1.15 (Spring Boot 3.5.16), Prometheus v3.5.0 / promtool, Grafana 12.1.0 dashboard JSON (schemaVersion 39), Node `node:test` (no dependencies), Docker Compose `observability` profile.

**Spec:** `openspec/changes/add-detector-grafana-panels/specs/observability/spec.md`, `design.md` in the same folder, GitHub issue #135.

## Global Constraints

- No code comments of any kind in new code: inline, block, Javadoc, YAML `#` or TODO. Keep existing comments.
- Local observability profile only. Do not run `terraform`, do not create cloud resources, put no Alpaca, Gemini or other keys in any config.
- Every `pumpwatch_*` name in the dashboard or rules must come from a `Counter`, `Timer` or `Gauge` registration in `pipeline/src/main/java` or `connectors/src/main/java`.
- Detector queries must not use Grafana variables (`$...`), so the check script can run them directly against Prometheus.
- Alert summaries use labels only, never `$value`, so promtool tests match exactly.
- Conventional Commits (`feat(ops): ...`, `test(ops): ...`), no `Co-Authored-By` or AI attribution line.
- Git Bash on Windows: prefix `docker run`/`docker exec` with `MSYS_NO_PATHCONV=1`. Use `127.0.0.1`, not `localhost`, in scripts and URLs.
- If Docker is not running, say so and do not claim promtool, `docker compose config` or stack checks passed. CI runs the promtool steps.

## Review Focus

- Idle stack (no input, no connector enabled): every Detector panel shows "No data" or zero, never a query error. Pinned by the runtime query check in Task 4, run in Task 5.
- Latency above 30 s: p95 must not flatten at the old 30 s top bucket. Pinned by the Task 1 bucket test and the Task 2 `FlagLatencyHigh` test with 573 s p95.
- Several connectors, one stalled: only the stalled connector alerts. Pinned by the `sec` series in the Task 2 `ConnectorStalled` test.
- Scrape labels (`job`, `instance`, `service`) splitting lines or alerts: each Detector query aggregates with `sum by`/`max by`. Pinned by the Task 3 node test `detector queries aggregate and use no variables`.
- Replay of old data makes latency equal the data's age and fires `FlagLatencyHigh`. No test; documented in Task 4 so the owner is not surprised.

---

### Task 1: Flag latency histogram covers the 60 s target

**Files:**
- Modify: `pipeline/src/main/java/dev/pumpwatch/pipeline/flagger/FlagStage.java:53`
- Test: `pipeline/src/test/java/dev/pumpwatch/pipeline/flagger/FlagStageTest.java`

**Interfaces:**
- Consumes: `FlagStage.build(StreamsBuilder, Scorer, FlagConfig, MeterRegistry)`, test fixtures `STUB` and `CFG` already in `FlagStageTest`.
- Produces: Prometheus series `pumpwatch_flag_latency_seconds_bucket` with an `le` bucket at 60 and finite buckets up to at least 300 (Tasks 2 and 3 query it).

- [ ] **Step 1: Write the failing test** `latencyHistogramHasBucketsAroundTheSixtySecondTarget` in `FlagStageTest`

```java
var prom = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
FlagStage.build(new StreamsBuilder(), STUB, CFG, prom);
String scrape = prom.scrape();
assertThat(scrape).containsPattern("pumpwatch_flag_latency_seconds_bucket\\{le=\"60(\\.0)?\"\\}");
double top = scrape.lines()
        .filter(l -> l.startsWith("pumpwatch_flag_latency_seconds_bucket{le=\"") && !l.contains("+Inf"))
        .mapToDouble(l -> Double.parseDouble(l.substring(l.indexOf("le=\"") + 4, l.indexOf("\"}"))))
        .max().orElse(0);
assertThat(top).isGreaterThanOrEqualTo(300);
```

Imports: `io.micrometer.prometheusmetrics.PrometheusConfig`, `io.micrometer.prometheusmetrics.PrometheusMeterRegistry` (already a pipeline compile dependency).

- [ ] **Step 2: Run it and see it fail**

Run (repo root): `mvn -B -q -pl pipeline -am test -Dtest=FlagStageTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: FAIL in `latencyHistogramHasBucketsAroundTheSixtySecondTarget`, "Expecting actual ... to contain pattern ... le=\"60".

- [ ] **Step 3: Change the timer builder** at `FlagStage.java:53` to `Timer.builder("pumpwatch.flag.latency").publishPercentileHistogram().serviceLevelObjectives(Duration.ofSeconds(60)).maximumExpectedValue(Duration.ofMinutes(10)).register(meters)`. Keep the existing Javadoc line above it.

- [ ] **Step 4: Run the pipeline tests**

Run: `mvn -B -q -pl pipeline -am verify`
Expected: BUILD SUCCESS, all `FlagStageTest` tests pass, including the existing count/max assertions.

- [ ] **Step 5: Commit**

```bash
git add pipeline/src/main/java/dev/pumpwatch/pipeline/flagger/FlagStage.java pipeline/src/test/java/dev/pumpwatch/pipeline/flagger/FlagStageTest.java
git commit -m "feat(pipeline): resolve flag latency above 30 s for the 60 s target (#135)"
```

### Task 2: Detector alert rules with promtool tests

**Files:**
- Create: `deploy/observability/alerts.test.yml`
- Modify: `deploy/observability/alerts.yml` (append a second group)

**Interfaces:**
- Consumes: series from Task 1 and the existing scorer and connector metrics (see `design.md` table).
- Produces: alert names `FlagLatencyHigh`, `ScorerDropping`, `ConnectorDeadLetters`, `ConnectorStalled` (Task 3 node test and Task 4 runtime check look them up by name).

- [ ] **Step 1: Write the failing rule tests** in `deploy/observability/alerts.test.yml`

Top level: `rule_files: [alerts.yml]`, `evaluation_interval: 1m`. Each test group uses `interval: 1m`. Groups:

| Group | `input_series` (series: values) | `alert_rule_test` (eval_time, alertname: exp_alerts) |
| --- | --- | --- |
| slow flags | `pumpwatch_flag_latency_seconds_bucket{le="60.0"}`: `0x30`; `{le="600.0"}`: `0+1x30`; `{le="+Inf"}`: `0+1x30` | 10m, FlagLatencyHigh: one alert, labels `severity: warning`, summary `Flag latency p95 is above 60 seconds` |
| flags within target | same three buckets all `0+1x30` | 10m, FlagLatencyHigh: `[]` |
| idle latency | same three buckets all `0x30` | 10m, FlagLatencyHigh: `[]` |
| scorer drops | `pumpwatch_scorer_dropped_total{model="rules-v1"}`: `0+1x20` | 10m, ScorerDropping: labels `model: rules-v1`, `severity: warning`, summary `Scorer rules-v1 is dropping unreadable input` |
| scorer idle | `pumpwatch_scorer_dropped_total{model="rules-v1"}`: `0x20` | 10m, ScorerDropping: `[]` |
| dead letters | `pumpwatch_connector_dead_letter_total{connector="rss"}`: `0x5 1x20` | 8m, ConnectorDeadLetters: labels `connector: rss`, `severity: warning`, summary `Connector rss is sending records to the dead-letter topic`; 20m, ConnectorDeadLetters: `[]` |
| stalled connector | `pumpwatch_connector_last_fetch_age_seconds{connector="rss"}`: `30+60x15`; `{connector="sec"}`: `30x15` | 10m, ConnectorStalled: only `connector: rss`, `severity: warning`, summary `Connector rss has not fetched for over 2 minutes` |

- [ ] **Step 2: Run them and see them fail**

Run (Git Bash, repo root): `MSYS_NO_PATHCONV=1 docker run --rm -v "$PWD/deploy/observability:/etc/prometheus:ro" --entrypoint promtool prom/prometheus:v3.5.0 test rules /etc/prometheus/alerts.test.yml`
Expected: `FAILED:` with `alertname: FlagLatencyHigh` (and the other three) showing expected alerts but got none. If Docker is off, record "not run: Docker off" and continue; CI runs it in Task 4.

- [ ] **Step 3: Append group `detector` to `alerts.yml`**, same style as group `website` (`labels: { severity: warning }`, `annotations.summary`):

| alert | expr | for | summary |
| --- | --- | --- | --- |
| `FlagLatencyHigh` | `histogram_quantile(0.95, sum by (le) (rate(pumpwatch_flag_latency_seconds_bucket[15m]))) > 60` | 5m | `Flag latency p95 is above 60 seconds` |
| `ScorerDropping` | `sum by (model) (rate(pumpwatch_scorer_dropped_total[5m])) > 0` | 5m | `Scorer {{ $labels.model }} is dropping unreadable input` |
| `ConnectorDeadLetters` | `sum by (connector) (increase(pumpwatch_connector_dead_letter_total[10m])) > 0` | 1m | `Connector {{ $labels.connector }} is sending records to the dead-letter topic` |
| `ConnectorStalled` | `max by (connector) (pumpwatch_connector_last_fetch_age_seconds) > 120` | 5m | `Connector {{ $labels.connector }} has not fetched for over 2 minutes` |

- [ ] **Step 4: Run the rule tests and rule check**

Run: the Step 2 command, then `MSYS_NO_PATHCONV=1 docker run --rm -v "$PWD/deploy/observability:/etc/prometheus:ro" --entrypoint promtool prom/prometheus:v3.5.0 check rules /etc/prometheus/alerts.yml`
Expected: `SUCCESS` for the unit tests; `SUCCESS: 8 rules found`.

- [ ] **Step 5: Commit**

```bash
git add deploy/observability/alerts.yml deploy/observability/alerts.test.yml
git commit -m "feat(ops): alert on flag latency, scorer drops and connector health (#135)"
```

### Task 3: Detector row on the health dashboard

**Files:**
- Create: `scripts/observability-config.test.mjs`
- Modify: `deploy/observability/grafana/dashboards/pumpwatch-health.json`

**Interfaces:**
- Consumes: alert names from Task 2; metric names registered in Java.
- Produces: a row panel titled exactly `Detector` (id 104) whose following panels, up to the next row, are the Detector panels (Task 4 finds them the same way).

- [ ] **Step 1: Write the failing node tests** in `scripts/observability-config.test.mjs` with `node:test` and `node:assert/strict`, reading files relative to `new URL("..", import.meta.url)`:

`exported()`: walk `pipeline/src/main/java` and `connectors/src/main/java` with `readdirSync(dir, { recursive: true })`, match `/(Counter|Timer|Gauge)\.builder\("([a-z0-9_.]+)"/g`, replace `.` with `_`, then Counter → `name_total`; Timer → `name_seconds_bucket`, `_seconds_count`, `_seconds_sum`, `_seconds_max`; Gauge → `name`. `detector(panels)`: panels after the row titled `Detector` until the next `type: "row"`.

| test | assertion |
| --- | --- |
| `detector row has the five panels in order` | titles deep-equal `["Flag latency p95 (target 60 s)", "Scorer windows per second", "Late records per second by topic", "Connector dead letters (last 10 min)", "Connector last fetch age"]` |
| `every pumpwatch metric used is registered by the Java code` | every `/\bpumpwatch_[a-z0-9_]+/g` match in all dashboard `targets[].expr` and in `alerts.yml` is in `exported()`; the set of matches is non-empty |
| `detector queries aggregate and use no variables` | every Detector `targets[].expr` matches `/\b(sum|max) by \(/` and contains no `$` |
| `panel ids are unique and panels do not overlap` | no duplicate `id`; no two `gridPos` rectangles intersect |
| `alerts.yml defines the detector alerts` | `alerts.yml` contains `- alert: X` for each of the four Task 2 names |

- [ ] **Step 2: Run them and see them fail**

Run: `node --test scripts/observability-config.test.mjs`
Expected: `detector row has the five panels in order` fails (no `Detector` row); the other four pass.

- [ ] **Step 3: Add the Detector row** to the JSON. Same panel shape as panel 20 (`datasource {type: prometheus, uid: prometheus}`, `refId` A/B/C, `legendFormat`, `fieldConfig.defaults.unit`, plain-language `description`). Insert after panel 21 and move the Logs row down by 17: row 103 to `y: 72`, panel 30 to `y: 73`, panel 31 to `y: 79`.

| id | type | gridPos x,y,w,h | title | expr (legend) | unit |
| --- | --- | --- | --- | --- | --- |
| 104 | row | 0,55,24,1 | Detector | none | none |
| 40 | timeseries | 0,56,12,8 | Flag latency p95 (target 60 s) | `histogram_quantile(0.95, sum by (le) (rate(pumpwatch_flag_latency_seconds_bucket[15m])))` (`p95`) | s |
| 41 | timeseries | 12,56,12,8 | Scorer windows per second | A `sum by (model) (rate(pumpwatch_scorer_scored_total[5m]))` (`{{model}} scored`), B same with `pumpwatch_scorer_dropped_total` (`{{model}} dropped`), C same with `pumpwatch_scorer_stale_total` (`{{model}} with stale sources`) | cps |
| 42 | timeseries | 0,64,8,8 | Late records per second by topic | `sum by (topic) (rate(pumpwatch_late_records_total[5m]))` (`{{topic}}`) | cps |
| 43 | timeseries | 8,64,8,8 | Connector dead letters (last 10 min) | `sum by (connector) (increase(pumpwatch_connector_dead_letter_total[10m]))` (`{{connector}}`) | short |
| 44 | timeseries | 16,64,8,8 | Connector last fetch age | `max by (connector) (pumpwatch_connector_last_fetch_age_seconds)` (`{{connector}}`) | s |

Panels 40 and 44 draw a red threshold line: `"thresholds": {"mode": "absolute", "steps": [{"color": "green", "value": null}, {"color": "red", "value": N}]}` and `"custom": {"thresholdsStyle": {"mode": "line"}}` in `fieldConfig.defaults`, N = 60 for 40 and 120 for 44.

Descriptions: 40 `95% of flags were emitted within this time of their window's end, over the last 15 minutes. The red line is the 60 s target. Empty until a flag is emitted. Replays of old data show the data's age.` 41 `Windows the scorer scored, dropped as unreadable, and scored while a source was stale, per second by model. Dropped should stay at zero.` 42 `Records that arrived after their windows closed, per second by input topic. They never change an emitted feature.` 43 `Records each connector sent to the dead-letter topic because they failed validation. Empty when no connector is enabled.` 44 `Seconds since each connector's last successful fetch. Above the red line (120 s) the connector reports DOWN. Empty when no connector is enabled.`

- [ ] **Step 4: Run the node tests and JSON parse**

Run: `node --test scripts/observability-config.test.mjs && node -e "JSON.parse(require('fs').readFileSync('deploy/observability/grafana/dashboards/pumpwatch-health.json','utf8'))"`
Expected: `# pass 5`, `# fail 0`; the parse prints nothing.

- [ ] **Step 5: Commit**

```bash
git add scripts/observability-config.test.mjs deploy/observability/grafana/dashboards/pumpwatch-health.json
git commit -m "feat(ops): add Detector row to the health dashboard (#135)"
```

### Task 4: Runtime check, CI and docs

**Files:**
- Modify: `scripts/check-observability.mjs`
- Modify: `.github/workflows/ci.yml` (infrastructure job, after the `promtool check config` step at line 119)
- Modify: `docs/operations/observability.md`

**Interfaces:**
- Consumes: the `Detector` row (Task 3), the four alert names (Task 2).
- Produces: `node scripts/check-observability.mjs` exit 0 only when, in addition to today's checks, the detector metrics exist, Detector queries run, and the detector rules are loaded.

This script needs a live stack, so it runs in Task 5. The static counterpart is Task 3's node test.

- [ ] **Step 1: Extend `problems()` in `check-observability.mjs`**
  - `DETECTOR_METRICS = ["pumpwatch_flag_latency_seconds_bucket", "pumpwatch_scorer_scored_total", "pumpwatch_scorer_dropped_total", "pumpwatch_scorer_stale_total", "pumpwatch_late_records_total"]`; each missing from `GET ${PROMETHEUS}/api/v1/label/__name__/values` adds `Prometheus has no <name>`. Connector metrics are not required: connectors are off by default.
  - `DETECTOR_ALERTS = ["FlagLatencyHigh", "ScorerDropping", "ConnectorDeadLetters", "ConnectorStalled"]`; each missing from the rule names in `GET ${PROMETHEUS}/api/v1/rules?type=alert` adds `Prometheus has no alert rule <name>`.
  - Replace the bare dashboard fetch with its result. No row titled `Detector` adds `dashboard has no Detector row`. For each Detector panel target, `GET ${PROMETHEUS}/api/v1/query?query=<encoded expr>`; a thrown error adds `panel "<title>" query failed: <message>`.
  - Success line: `observability ok: targets up, probes passing, logs flowing, dashboard and detector checks present`.

- [ ] **Step 2: Add CI steps** after line 119, same image and mount:

```yaml
      - run: docker run --rm -v "$PWD/deploy/observability:/etc/prometheus:ro" --entrypoint promtool prom/prometheus:v3.5.0 check rules /etc/prometheus/alerts.yml
      - run: docker run --rm -v "$PWD/deploy/observability:/etc/prometheus:ro" --entrypoint promtool prom/prometheus:v3.5.0 test rules /etc/prometheus/alerts.test.yml
      - run: node --test scripts/observability-config.test.mjs
```

- [ ] **Step 3: Update `docs/operations/observability.md`**
  - Dashboard panels: a **Detector** section between **Java API** and **Logs** with one line per panel, using the Task 3 descriptions. Note: latency is measured from the scored window's end, so it includes the 2 minute feature grace, and replays of old data show the data's age.
  - Alerts table: four rows. `FlagLatencyHigh`: flag latency p95 over 15 minutes stays above 60 seconds for 5 minutes. `ScorerDropping`: the scorer drops unreadable input for 5 minutes. `ConnectorDeadLetters`: a connector dead-letters a record in the last 10 minutes. `ConnectorStalled`: a connector's last successful fetch is older than 2 minutes for 5 minutes.
  - Check script paragraph: add the detector metrics, the Detector queries and the four rules. Add `alerts.test.yml` and `node --test scripts/observability-config.test.mjs` under a short "Checks without the stack" line.

- [ ] **Step 4: Run the static checks**

Run (repo root): `node --check scripts/check-observability.mjs && node --test scripts/observability-config.test.mjs`, then `docker compose --profile apps --profile observability config -q` (needs a `.env`; copy `.env.example` only if none exists, never print it).
Expected: no output from `--check`; `# fail 0`; compose prints nothing and exits 0. If Docker is off, say compose config was not run.

- [ ] **Step 5: Commit**

```bash
git add scripts/check-observability.mjs .github/workflows/ci.yml docs/operations/observability.md
git commit -m "feat(ops): check detector metrics and rules, document the Detector row (#135)"
```

### Task 5: Verify in the running stack

**Files:** none changed unless a check fails (then fix in the owning task's files and re-run).

- [ ] **Step 1: Check Docker**

Run: `docker info --format '{{.ServerVersion}}'`
Expected: a version. If it errors, stop here and report "stack not verified: Docker off". Do not claim Steps 2 to 6.

- [ ] **Step 2: Start the stack**

Run (Git Bash): `MSYS_NO_PATHCONV=1 docker compose -p pumpwatch --profile apps --profile observability up -d --build --wait`
Expected: every service healthy or running; `kafka-init` exited 0.

- [ ] **Step 3: Rule and config checks against the repo files**

Run: `MSYS_NO_PATHCONV=1 docker run --rm -v "$PWD/deploy/observability:/etc/prometheus:ro" --entrypoint promtool prom/prometheus:v3.5.0 check config /etc/prometheus/prometheus.yml`, then the Task 2 `check rules` and `test rules` commands.
Expected: `SUCCESS` for config and both rule commands, `8 rules found`.

- [ ] **Step 4: Runtime check**

The runtime script has no deterministic red step; Task 3's node test is the failing-first gate for names and panels.
Run: `node scripts/check-observability.mjs`
Expected: `observability ok: targets up, probes passing, logs flowing, dashboard and detector checks present`, exit 0.

- [ ] **Step 5: Bucket check on the live scrape**

Run: `curl -s --get http://127.0.0.1:9090/api/v1/query --data-urlencode 'query=count(pumpwatch_flag_latency_seconds_bucket{le=~"60(\\.0)?"})'`
Expected: `"result":[{"metric":{},"value":[...,"1"]}]`.

- [ ] **Step 6: Render the Detector row with Playwright MCP**
  - `mcp__playwright__browser_navigate` to `http://127.0.0.1:3001/d/pumpwatch-health?orgId=1&from=now-1h&to=now`.
  - `mcp__playwright__browser_wait_for` text `Detector`, then `mcp__playwright__browser_snapshot`.
  - Expected: the row `Detector` and all five Task 3 titles are present; Detector panels show a line, zero or "No data"; no panel shows an error marker. Take `mcp__playwright__browser_take_screenshot` of the Detector row and keep it outside the repo (local data must not land in README images).
  - `mcp__playwright__browser_navigate` to `http://127.0.0.1:9090/alerts` and confirm the `detector` group lists the four rules as inactive on an idle stack.

- [ ] **Step 7: Leave the stack as the owner wants it**

Report which steps ran and their output. Stop the observability profile only if the owner asks: `docker compose -p pumpwatch --profile observability stop`.
