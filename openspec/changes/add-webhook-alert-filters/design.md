# Design

## Context

See proposal.md and issue #134. Alerts are planned per channel (`webhook-N`, by URL position) in `AlertService.plan()`. `AlertPlanner.plan` splits a channel's pending flags into singles, one digest and suppressed. `JdbcAlertStore` records each decision once per channel in `alert_flags` (`queued`, `digested`, `suppressed`). `lastAlerted` ignores `suppressed` rows, so suppressed flags never start a cooldown.

## Goals / Non-Goals

**Goals:**
- Per-channel ticker allow-list and minimum score.
- Today's behaviour when no filter is set.
- Fail fast on bad configuration, without printing values.

**Non-Goals:**
- Filters by contributor, source or catalyst; deny-lists; filters edited at runtime or through the API.
- Re-delivering flags that were filtered before a filter changed.

## Decisions

**Filters bind from indexed environment variables.** `alerts.channel-filters` is a `List<ChannelFilter>`. Spring's relaxed binding maps `ALERTS_CHANNEL_FILTERS_0_TICKERS` and `ALERTS_CHANNEL_FILTERS_0_MIN_SCORE` to `alerts.channel-filters[0].tickers` and `.min-score`, so `application.yml` needs no new line (a YAML entry cannot express an open-ended indexed list from env vars). Checked against Spring Boot 3.5.16 while planning: the underscore-for-dash form binds only from the property source named `systemEnvironment`, so the unit test must build its `SystemEnvironmentPropertySource` with that name. A `Map<Integer, ChannelFilter>` was also tried and does not bind the underscore form, so a list it is.

**Our own check catches gaps and misspelled names.** Spring stops binding a list at the first missing index and ignores unknown names, so `ALERTS_CHANNEL_FILTERS_1_TICKERS` without a `_0_` entry, or `_0_TICKER`, would silently leave a channel unfiltered. When alerts are enabled, `AlertService`'s constructor passes the environment's variable names to `AlertProperties.checkFilterNames`. Every name starting with `ALERTS_CHANNEL_FILTERS_` must be `ALERTS_CHANNEL_FILTERS_<n>_TICKERS` or `_MIN_SCORE` with `n` below the number of bound filters. The error names the variable, never its value. Alternative: `ignoreUnknownFields = false`. Rejected: it would also reject `ALERTS_INTERVAL` and other env-mapped names that are not record components.

**`ChannelFilter` binds from strings, stores typed values.** A `@ConstructorBinding` constructor takes `List<String> tickers, String minScore` and delegates to the canonical `(Set<String> tickers, Double minScore)`. Binding a raw string means Spring never fails a type conversion, because a Spring conversion failure prints the raw value. Our own messages name the rule, not the value. Tickers are stripped, upper-cased, blanks dropped, and must match `ApiController.TICKER` (`[A-Z]{1,5}`). A blank min score means none. A min score must parse to a number from 0 to 100 inclusive (NaN and infinities fail). A flag passes when its ticker is in the list (or the list is empty) and its score is at least the min score (inclusive).

**More filters than webhooks fails startup, only when alerts are enabled.** A filter with no webhook is almost always an index typo. With alerts disabled, leftover filter settings must not stop the API.

**Filtering happens in `AlertPlanner`, before cooldown and rate.** Filtered flags go to `Plan.suppressed`, do not update the planner's cooldown map and do not use rate room. `AlertService` already calls `store.suppress` with that list, so filtered flags are decided once and never return as pending. Alternative: a new `filtered` outcome with a migration. Rejected: the issue asks for `suppressed`, and the existing `lastAlerted` query already does the right thing for it.

## Risks / Trade-offs

- [Filters set through YAML or `-D` properties instead of env vars] → the name check only sees env vars. A gap there is still silently ignored. Accepted: env vars are the documented way.
- [Reordering `ALERTS_WEBHOOK_URLS`] → filters follow positions, as channel names already do. Documented next to the variable.
- [Changing a filter] → only affects flags not yet decided. Flags already suppressed stay suppressed.
