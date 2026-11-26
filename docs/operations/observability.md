# Observability

Metrics, uptime checks, container resources and logs for the local stack, in one Grafana
dashboard. It answers three questions: is the website up, is anything running out of CPU or
memory, and what did a service log when it broke.

It is an opt-in Compose profile for your own machine. `deploy/up.sh` never starts it, so the
public VM is unchanged.

## Start and open

```bash
docker compose --profile apps --profile observability up -d --build --wait
node scripts/check-observability.mjs     # passes when targets are up, probes pass, logs flow
```

| What | Address | Notes |
| --- | --- | --- |
| Grafana dashboard | http://127.0.0.1:3001 | Opens on "Pumpwatch health". Viewing needs no login. Admin: `admin` / `GRAFANA_ADMIN_PASSWORD` (default `admin`). |
| Prometheus | http://127.0.0.1:9090 | Raw queries, targets (`/targets`) and alert state (`/alerts`). |

Both bind to `127.0.0.1` only. Loki, Alloy, cAdvisor and the blackbox exporter publish no port.

Stop it with `docker compose --profile observability stop`. Data stays in the `promdata`,
`lokidata` and `grafanadata` volumes for 7 days.

## Containers it adds

| Container | Image | Why it exists |
| --- | --- | --- |
| `prometheus` | `prom/prometheus:v3.5.0` | Stores every metric as a time series and evaluates the alert rules in `deploy/observability/alerts.yml`. |
| `blackbox` | `prom/blackbox-exporter:v0.27.0` | Fetches the site's addresses the way a visitor's request travels, and reports whether each answered and how fast. Without it nothing tests the site end to end. |
| `cadvisor` | `gcr.io/cadvisor/cadvisor:v0.52.1` | Reads CPU, memory and network use of every container from the kernel's cgroups. Covers containers that export no metrics of their own (Kafka, Postgres, web, research, Caddy). |
| `loki` | `grafana/loki:3.5.3` | Stores container logs for 7 days, so logs survive a container restart or removal. |
| `alloy` | `grafana/alloy:v1.10.0` | Reads each Pumpwatch container's output from the Docker socket and sends it to Loki, labelled with the Compose service name. The observability containers' own logs are left out. |
| `grafana` | `grafana/grafana:12.1.0` | Shows the dashboard. Data sources and the dashboard are provisioned from `deploy/observability/grafana/`. |

Measured on Docker Desktop, the six containers use about 0.8 GB of memory together. That is
why they stay off the 8 GB public VM.

## What each check means

The blackbox exporter calls each address from inside the Compose network every 15 seconds. A
check is UP when the answer is HTTP 2xx within 10 seconds.

| Check | Address probed | Path it tests | DOWN means |
| --- | --- | --- | --- |
| Home page | `http://proxy/` | Caddy, then the Next.js web server renders the dashboard page | Visitors get an error or nothing at all |
| Watchlist API | `http://proxy/api/v1/watchlist` | Caddy, then web. In `DATA_MODE=live` web forwards it to the Java `api`, which reads Postgres. In `mock` mode web answers it from its built-in mock API | The dashboard loads but shows no risk scores |
| Flags API | `http://proxy/api/v1/flags?limit=1` | Same path as the watchlist, for the latest pump-and-dump flags | The flag list is empty or fails |
| Research service | `http://research:8090/healthz` | The FastAPI process behind stock lookup and the flag assistant. Not public; only web calls it | Lookup and the assistant fail; the rest of the site still works |

Not probed: `/api/v1/flags/stream`. It is a live SSE stream that never finishes, so a probe
cannot time it. The `api` service's own health covers it.

In mock mode the Java services are not on the page's request path, so the API panels stay
empty. Switch to `DATA_MODE=live` to see API traffic.

## Metric endpoints scraped

| Target | Endpoint | Gives |
| --- | --- | --- |
| `connectors`, `replay`, `pipeline`, `api` | `/actuator/prometheus` on 8080, 8081, 8082, 8083 | JVM CPU, heap, garbage collection, threads, HTTP requests (api: with latency histogram), Kafka client metrics, connector counters |
| `cadvisor` | `cadvisor:8080/metrics` | CPU, memory and network per container, plus Docker host totals |
| `blackbox` | `blackbox:9115/probe?target=...` | `probe_success` and `probe_duration_seconds` per check |
| `prometheus` | `localhost:9090/metrics` | Prometheus's own health |

The Java endpoints are on the services' private ports. Caddy and the web server do not route
`/actuator`, so they are not reachable from the public port.

## Dashboard panels

**Is the website up?**
- *What each check means*: the table above in short form.
- *Site checks*: UP or DOWN per check, from the last probe.
- *Java services*: UP when Prometheus could read the service's metrics on the last scrape.
- *Check response time*: seconds each probe took.

**Resources**
- *Docker host CPU* and *Docker host memory*: share of the Docker VM's cores and memory in use.
  Orange from 70% CPU or 80% memory, red from 90%.
- *CPU by container*: 100% equals one full core, so a busy container can exceed 100%.
- *Memory by container*: working set, the memory a container cannot give back.
- *Network by container*: received above zero, sent below zero.
- *JVM CPU*, *JVM heap used*, *JVM garbage collection pause*, *JVM live threads*: per Java service.

**Java API**: requests per second by HTTP status, and p95 latency per route.

**Logs**: error lines per minute per service, and all log lines. Use the *Log service* filter at
the top to pick services.

## Alerts

Rules live in `deploy/observability/alerts.yml`. They show in Grafana (Alerting, then Alert
rules) and at http://127.0.0.1:9090/alerts. Nothing sends a notification.

| Alert | Fires when |
| --- | --- |
| `SiteDown` | A check fails for 1 minute |
| `SiteSlow` | A check takes over 2 seconds for 5 minutes |
| `ServiceDown` | A Java service's metrics endpoint does not answer for 1 minute |
| `ApiErrors` | The Java API returns more than 0.1 server errors per second for 5 minutes |

## Check script

`scripts/check-observability.mjs` retries for up to 3 minutes (`CHECK_TIMEOUT_MS`) and fails
unless every Prometheus target is up, every probe passes, Loki holds logs for `proxy`, `web` and
`api`, and the dashboard exists. Override the addresses with `PROMETHEUS_URL` and `GRAFANA_URL`.

## Known limits

- cAdvisor uses its containerd handler (`--containerd-namespace=moby`) because Docker Desktop's
  containerd image store breaks its Docker handler. The containerd handler does not see Compose
  labels, so Prometheus names each container from its image (`pumpwatch-api` becomes `api`,
  `caddy` becomes `proxy`). `kafka` and `kafka-init` share an image; `kafka-init` exits at start,
  so the `kafka` series is the broker.
- cAdvisor runs privileged with read-only host mounts, and Alloy mounts the Docker socket.
  Both are root-level access to Docker, which is acceptable on your own machine and is another
  reason the profile stays off the public VM.
- The web (Node) and research (Python) processes export no metrics of their own. Their CPU and
  memory come from cAdvisor, and their availability from the probes.
- No Kafka consumer-lag or Postgres exporter yet.
