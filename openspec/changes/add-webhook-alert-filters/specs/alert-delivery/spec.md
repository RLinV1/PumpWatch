# Spec Delta

## Purpose

Lets each webhook channel receive only the flags its owner cares about, without changing delivery for channels that have no filter.

## ADDED Requirements

### Requirement: Per-channel alert filters
The system SHALL let each configured webhook, by its position, have an optional ticker allow-list and an optional minimum score. The system SHALL record a flag that fails a channel's filter as suppressed for that channel and SHALL NOT deliver it there. A filtered flag SHALL NOT start a ticker cooldown or use the channel's per-minute rate.

#### Scenario: Ticker not in the allow-list
- **WHEN** webhook 1 is filtered to `GME,AMC` and a live flag for `TSLA` is raised
- **THEN** no message for `TSLA` is sent to webhook 1, and the flag is recorded as suppressed for webhook 1

#### Scenario: Score below the minimum
- **WHEN** webhook 1 has a minimum score of 75 and a live flag scores 70
- **THEN** the flag is suppressed for webhook 1 and a flag scoring 75 or more is delivered

#### Scenario: Filtered flag is not pending again
- **WHEN** a flag was suppressed by a filter on one tick
- **THEN** it is not pending for that channel on the next tick

#### Scenario: Other channels are unaffected
- **WHEN** webhook 1 has a filter and webhook 2 has none
- **THEN** webhook 2 receives every flag, with today's cooldown, rate and digest rules

#### Scenario: Passing flags keep cooldown and digest rules
- **WHEN** flags pass a channel's filter
- **THEN** the ticker cooldown and the burst digest apply to them as they do without a filter

### Requirement: Filter configuration checked at startup
The system SHALL refuse to start when a filter ticker is not 1 to 5 letters, when a minimum score is not a number from 0 to 100, or when alerts are enabled and there are more filters than webhooks, a gap in filter positions, or an unknown filter variable name. The error SHALL name the setting and SHALL NOT contain the configured value. The system SHALL NOT write webhook URLs or filter values to logs.

#### Scenario: Invalid ticker
- **WHEN** `ALERTS_CHANNEL_FILTERS_0_TICKERS` contains `G1ME`
- **THEN** startup fails with a message naming `alerts.channel-filters[0]` and the ticker rule, and the message does not contain `G1ME`

#### Scenario: Invalid minimum score
- **WHEN** `ALERTS_CHANNEL_FILTERS_0_MIN_SCORE` is `abc` or `101`
- **THEN** startup fails with a message naming the 0 to 100 rule, and the message does not contain the value

#### Scenario: Filter without a webhook
- **WHEN** alerts are enabled with one webhook and filters at positions 0 and 1
- **THEN** startup fails with a message saying there are 2 filters for 1 webhook

#### Scenario: Gap or misspelled filter variable
- **WHEN** alerts are enabled and `ALERTS_CHANNEL_FILTERS_1_TICKERS` is set without a position 0 entry, or `ALERTS_CHANNEL_FILTERS_0_TICKER` is set
- **THEN** startup fails with a message naming that variable, not its value

#### Scenario: No filter
- **WHEN** no filter variables are set
- **THEN** every webhook receives every flag exactly as before
