## Why

New flags only appear inside the `/live` tab. A user who is not looking at it misses a "Pump likely" flag on a stock they watch (issue #133).

## What Changes

- An opt-in "Notify me" toggle at the top of the Flags panel (panel 3), with a "Min score" select (50, 65, 80; default 65). Browser notification permission is requested only when the user turns it on.
- A browser notification for each flag that arrives on the SSE stream for a ticker on the watchlist shown in panel 1, at or above the minimum score.
- Flags replayed when the stream opens or reconnects never notify. Each flag id notifies at most once.
- Notification titles start with `MOCK` unless the data mode is `live`, and the body carries the risk-only notice. No buy or sell wording.
- Clicking a notification focuses the tab and selects the flag's ticker.
- `NOTIFY ON` and `NOTIFY OFF` commands do the same as the toggle. `ALRT` keeps opening the Flags panel.
- Preferences stay in this browser only, under `pumpwatch:notify:v1`. When storage is unavailable the choice lasts for the session.
- The toggle is hidden when `Notification` is unsupported or the page is not a secure context.

## Impact

- New: `web/lib/flag-alerts.ts`, `web/components/notify-toggle.tsx`, `web/tests/flag-alerts.test.mjs`.
- Changed: `web/lib/commands.ts`, `web/tests/commands.test.mjs`, `web/components/live-view.tsx` (small: FlagFeed wiring, HELP line, `notify` command case), `web/README.md`.
- No API, server or mock-stream change.
