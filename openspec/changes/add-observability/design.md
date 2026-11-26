# Design

## Context

Seven application containers sit behind Caddy on one compose network. The Java services already run Spring Boot Actuator, and the connectors already expose `/actuator/prometheus`. The public VM is an `e2-standard-2` with 8 GB, which the stack already fills, so this change targets the owner's machine first.

## Decisions

**Approaches considered.**
1. Prometheus, Loki, Alloy and Grafana as a compose profile (chosen). It is self-hosted, free, works offline and matches the existing compose workflow.
2. Grafana Cloud free tier with Alloy as the only local container. It is lighter on memory but sends logs and metrics to a third party, which conflicts with keeping real Alpaca data on the owner's machine.
3. An external uptime service only. It answers "is the page up" but gives no logs or service metrics to find the cause.

**Opt-in profile.** The `observability` profile keeps default and `apps` runs unchanged, and keeps the VM unchanged because `deploy/up.sh` only enables `apps`.

**Probe through Caddy.** The blackbox exporter calls `http://proxy/`, `http://proxy/api/v1/watchlist` and `http://proxy/api/v1/flags?limit=1`, so a probe fails when any hop a visitor uses fails. The live stream is not probed because an SSE response never ends.

**Logs from the Docker socket.** Alloy uses `loki.source.docker`, so no service needs a logging change. The socket is mounted read-only. Socket access still grants control of Docker, which is acceptable on the owner's machine and is one reason the profile stays off the public VM.

**Grafana access.** Grafana and Prometheus bind to `127.0.0.1` only. Grafana allows anonymous viewers so the dashboard opens without a login. The admin password comes from `GRAFANA_ADMIN_PASSWORD`.

**Container resources.** cAdvisor runs its containerd handler in the `moby` namespace, because Docker Desktop's containerd image store breaks its Docker handler. That handler does not see Compose labels, so Prometheus derives the `service` label from the image name.

**Retention.** Prometheus and Loki keep 7 days on named volumes.

**API latency.** The API publishes the `http.server.requests` histogram so the dashboard can show p95 latency.

## Risks

- Memory: the profile measured about 0.8 GB on Docker Desktop. On the VM this would need a larger machine type.
- Alerts are not delivered anywhere. The owner sees them only by opening Grafana or Prometheus.
