## Why

Every configured webhook receives every live flag. `AlertPlanner` applies only the ticker cooldown and the per-minute rate, so a user cannot limit a chat channel to the tickers they follow or to high scores (GitHub issue #134).

## What changes

- Each webhook, by its position in `ALERTS_WEBHOOK_URLS`, can have an optional filter: a ticker allow-list and a minimum score, for example `ALERTS_CHANNEL_FILTERS_0_TICKERS=GME,AMC` and `ALERTS_CHANNEL_FILTERS_0_MIN_SCORE=75`.
- A flag that fails a channel's filter is recorded as `suppressed` for that channel and is never delivered there. It does not come back as pending on the next tick and does not start a ticker cooldown.
- A channel with no filter behaves exactly as today. Cooldown, rate and digest rules are unchanged for flags that pass.
- Invalid filter configuration (a bad ticker, a min score that is not a number from 0 to 100, more filters than webhooks, a gap in positions or a misspelled filter variable) stops the API at startup with a message that names the setting but not its value.
- Webhook URLs and filter values are never logged.

## Impact

- Code: `api/src/main/java/dev/pumpwatch/api/alerts/AlertProperties.java`, `AlertPlanner.java`, `AlertService.java`; `ApiController.TICKER` becomes public so the filter reuses the API's ticker rule.
- Tests: `AlertPropertiesTest` (new), `AlertPlannerTest`, `AlertServiceTest`, `AlertFilterDeliveryTest` (new), `JdbcAlertStoreDbTest`.
- Config and docs: `.env.example`, `docs/architecture/overview.md`. No database migration: filtered flags use the existing `suppressed` outcome.
