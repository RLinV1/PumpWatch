# Spec Delta

## Purpose

Lets the owner see whether the detector meets its latency target and whether the scorer and connectors are healthy, from the local health dashboard.

## ADDED Requirements

### Requirement: Detector dashboard row
The health dashboard SHALL have a "Detector" row with flag latency p95 against a 60 second line, scorer scored, dropped and stale rates by model, late records by topic, connector dead letters by connector and connector last-fetch age by connector. Every query SHALL use only metric names the Java services register.

#### Scenario: Idle stack
- **WHEN** the stack runs with no input data and no connector enabled
- **THEN** each Detector panel shows "No data" or zero, and no panel shows a query error

#### Scenario: Latency above target
- **WHEN** flags are emitted with latencies above 60 seconds
- **THEN** the flag latency p95 line rises above the 60 second line instead of flattening at 30 seconds

### Requirement: Detector alert rules
The system SHALL evaluate the alerts `FlagLatencyHigh`, `ScorerDropping`, `ConnectorDeadLetters` and `ConnectorStalled`, visible in Prometheus and Grafana, without sending notifications.

#### Scenario: Slow flags
- **WHEN** the flag latency p95 over 15 minutes stays above 60 seconds for 5 minutes
- **THEN** `FlagLatencyHigh` fires

#### Scenario: Scorer drops input
- **WHEN** the scorer drops unreadable input continuously for 5 minutes
- **THEN** `ScorerDropping` fires with the model label

#### Scenario: Connector problems
- **WHEN** a connector dead-letters a record, or its last successful fetch is older than 120 seconds for 5 minutes
- **THEN** `ConnectorDeadLetters` or `ConnectorStalled` fires with the connector label

#### Scenario: Quiet stack
- **WHEN** no flags, drops, dead letters or stale fetches occur
- **THEN** none of the four alerts fires
