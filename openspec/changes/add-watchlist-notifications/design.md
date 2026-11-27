# Design

## Context

`FlagFeed` in `web/components/live-view.tsx` opens one `EventSource` on `/api/v1/flags/stream` and merges `flag` events into the panel. The real API (`SseHub`) starts a fresh connection "from now" and, on reconnect, replays every flag after `Last-Event-ID` on its next 1 s poll. The mock stream (`web/mock-api.mjs`) writes its current two flags immediately on connect, then every 15 s, and the flag ids change every 30 s (GME 74, AMC 66). Neither stream marks an event as replayed.

Other branches change `FlagFeed` (#130, #131) and `ChartPanel` (#130, #132), so this change keeps its `live-view.tsx` edits to a few lines and puts the logic in `web/lib/flag-alerts.ts`.

## Decisions

**Backlog detection by a settle window.** After each stream `open`, events that arrive within `SETTLE_MS` (3000 ms) are recorded as seen and never notify. Before the first `open`, nothing notifies. This covers the mock's immediate burst and the real API's replay on its 1 s poll.
Alternatives considered:
1. Compare `raisedAt` with the open time. Rejected: mock flags are raised 3 and 18 minutes in the past, so mock mode would never notify, and it depends on client and server clocks agreeing.
2. Seed the seen set from the REST snapshot. Rejected: `retry()` refetches on open, so a new flag could reach the snapshot before the stream and then be suppressed.
Cost: a genuinely new flag in the first 3 s after a (re)connect is shown in the panel but not notified. This errs on the side of the issue's "never notify for backlog".

**Dedupe.** A per-page `Set` of flag ids lives in a tracker held by `FlagFeed`, which stays mounted across reconnects and workspace changes. Every id is added the first time it is seen, notified or not. Each notification uses `tag: "pumpwatch-flag-<id>"`, so two open tabs collapse into one notification.

**Effective state.** Notifications are on only when the saved preference is on and `Notification.permission` is `granted`. If the user later blocks the site, the toggle shows OFF and nothing fires.

**Storage.** `pumpwatch:notify:v1` holds `{ "enabled": boolean, "minScore": 50 | 65 | 80 }`. Invalid data reads as off at 65. If `localStorage` throws, the value is kept in module memory for the session and the UI says so, matching the workspace fallback.

**Labelling.** The title is prefixed `MOCK ` unless the data mode is exactly `live`, so an unknown mode never looks live. The body is `<posts> posts, <trades> trades. ` followed by `NOTICE` from `web/lib/api.ts`.

**Which watchlist.** The `symbols` list shown in panel 1: the browser-saved list once the user has edited it, otherwise the server default list.

**Commands.** `NOTIFY ON` and `NOTIFY OFF` call the same lib functions as the toggle. The toggle and the command line both subscribe to the same preference store, so they stay in sync. `ALRT` stays an alias of the Flags panel.

**No service worker.** `new Notification()` is used directly. Where the constructor throws (Android Chrome), the call is caught and nothing is shown.

## Risks

- Settle window: a real flag raised in the first 3 s after a reconnect is never notified.
- Android Chrome reports `Notification` support but throws on construction, so the toggle shows but notifications stay silent there.
- Notifications fire even when `/live` is the visible tab.
- `live-view.tsx` is edited on parallel branches; merge conflicts are likely in `FlagFeed`.
