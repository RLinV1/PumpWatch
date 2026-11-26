# Proposal

## Why

The owner wants to know that the website works without opening it by hand. Today every service has a Docker healthcheck, but nothing records whether the public page answers, how fast it answers, or what the services logged when something broke. Only the connectors export Prometheus metrics, and logs live only in `docker logs` until the container is removed.

## What Changes

- Add an opt-in `observability` compose profile with Prometheus, the Prometheus blackbox exporter, cAdvisor, Loki, Grafana Alloy and Grafana.
- The blackbox exporter probes the site the way a visitor reaches it: the home page, the watchlist API and the flags API through Caddy, plus the research health endpoint. Each check carries a plain-language name and is explained on the dashboard.
- Prometheus scrapes `/actuator/prometheus` on all four Java services. The API, pipeline and replay services gain the Micrometer Prometheus registry and expose the endpoint.
- cAdvisor reports CPU, memory and network per container, including containers with no metrics of their own.
- Alloy reads every compose container's logs from the Docker socket and ships them to Loki, labelled by compose service.
- Grafana starts with both data sources and one "Pumpwatch health" dashboard: site checks, service status, Docker host and per-container CPU, memory and network, JVM CPU, heap, garbage collection and threads, API traffic and latency, and logs.
- Prometheus alert rules mark the site down, a service down, slow probes and API 5xx errors. They are visible in Prometheus and Grafana. No notifications are sent.
- Add `scripts/check-observability.mjs`, which passes only when every target is up, every probe succeeds, Loki holds logs for the website path and the dashboard exists.
- Out of scope: running the profile on the public VM, notification channels (Alertmanager), Node and Python service metrics, Kafka and Postgres exporters, tracing.

## Capabilities

### New Capabilities
- `observability`: Local metrics, synthetic probes, central logs and a health dashboard for the whole stack.

### Modified Capabilities
<!-- None. -->

## Impact

- New files under `deploy/observability/`, `scripts/check-observability.mjs`, `docs/operations/observability.md`.
- `docker-compose.yml` gains six services under the `observability` profile and three volumes. Default and `apps` runs are unchanged.
- `api`, `pipeline` and `replay` gain `micrometer-registry-prometheus` and expose `/actuator/prometheus` on their private ports.
- `deploy/up.sh` does not enable the profile, so the public VM is unchanged.
