# Spec Delta

## Purpose

Lets the owner see whether the website works, how fast it answers and what the services logged, from one local dashboard.

## ADDED Requirements

### Requirement: Synthetic site probes
The system SHALL probe the page and the API through the public proxy at a fixed interval and record success and duration for each probe.

#### Scenario: Site goes down
- **WHEN** the proxy, the web server or the API stops answering
- **THEN** the matching probe records failure within one probe interval and the site-down alert fires after one minute

### Requirement: Service metrics
The system SHALL collect Prometheus metrics from every Java service and SHALL mark a service down when its metrics endpoint stops answering.

#### Scenario: Service stops
- **WHEN** a Java service container stops
- **THEN** its target shows as down and the service-down alert fires after one minute

### Requirement: Central logs
The system SHALL collect the logs of every compose container, labelled by compose service, and keep them for 7 days.

#### Scenario: Find an error
- **WHEN** the owner filters logs by service in the dashboard
- **THEN** the logs of that service appear, including lines from containers that have since restarted

### Requirement: Local-only access
The system SHALL bind Grafana and Prometheus to the loopback address and SHALL NOT expose Loki, Alloy or the blackbox exporter on any host port.

#### Scenario: Remote access
- **WHEN** another machine connects to the host's Grafana or Prometheus port
- **THEN** the connection is refused

### Requirement: Opt-in
The system SHALL start the observability services only when the `observability` profile is enabled.

#### Scenario: Default run
- **WHEN** the owner runs `docker compose --profile apps up`
- **THEN** no observability container starts
