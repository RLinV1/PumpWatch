## 1. Check first

- [x] 1.1 Add `scripts/check-observability.mjs` and confirm it fails before the stack exists

## 2. Metrics

- [x] 2.1 Add `micrometer-registry-prometheus` to `api`, `pipeline` and `replay`
- [x] 2.2 Expose `prometheus` in each service's Actuator endpoints; publish the API request histogram

## 3. Stack

- [x] 3.1 Add Prometheus config, scrape jobs and alert rules
- [x] 3.2 Add blackbox exporter probes for the home page, watchlist and flags APIs through Caddy, and research health
- [x] 3.3 Add Loki and Alloy configs for container logs
- [x] 3.4 Add Grafana data sources and the "Pumpwatch health" dashboard
- [x] 3.5 Add the six services and volumes to `docker-compose.yml` under the `observability` profile
- [x] 3.6 Add cAdvisor and resource panels (host and per-container CPU, memory, network; JVM CPU, GC, threads)

## 4. Docs and verification

- [x] 4.1 Write `docs/operations/observability.md` and link it from `docs/README.md` and `docs/architecture/containers.md`
- [x] 4.2 Run the stack with `apps` and `observability` and pass `scripts/check-observability.mjs`
- [x] 4.3 Run the Java tests and `docker compose config`
