## ADDED Requirements

### Requirement: Opt-in watchlist flag notifications

The `/live` Flags panel SHALL offer a "Notify me" toggle that is off by default. The page SHALL NOT request notification permission on load and SHALL request it only when the user turns notifications on, from the toggle or with `NOTIFY ON`. The toggle SHALL be keyboard-operable and SHALL expose its on or off state to assistive technology. The toggle SHALL be hidden when the browser has no `Notification` API or the page is not a secure context.

#### Scenario: No prompt on load
- **WHEN** a user opens `/live`
- **THEN** no notification permission request is made

#### Scenario: Turning notifications on
- **WHEN** the user activates "Notify me" with the keyboard and grants permission
- **THEN** the toggle reports pressed, shows ON, and the preference is saved as on

#### Scenario: Permission denied
- **WHEN** the user turns notifications on and the browser denies permission
- **THEN** the toggle stays off and a message says notifications are not allowed and how to allow them in site settings

#### Scenario: Unsupported browser
- **WHEN** `Notification` is undefined or the page is not a secure context
- **THEN** the toggle is not rendered

### Requirement: Which flags notify

When notifications are on and permission is granted, the web view SHALL show one browser notification for a flag received on the SSE stream whose ticker is on the watchlist shown in panel 1 and whose score is at or above the minimum score (50, 65 or 80; default 65). Flags received before the stream's first open or within 3 seconds after any open or reconnect SHALL NOT notify. Each flag id SHALL notify at most once per page, including across stream reconnects.

#### Scenario: Watchlist flag above the minimum
- **WHEN** a streamed flag for a watchlist ticker scores 65 or more and notifications are on at the default minimum
- **THEN** one notification is shown

#### Scenario: Backlog on reconnect
- **WHEN** the stream reconnects and replays flags raised while it was down
- **THEN** none of the replayed flags notify

#### Scenario: Duplicate id
- **WHEN** the same flag id arrives again after a reconnect
- **THEN** no second notification is shown

#### Scenario: Ticker not on the watchlist
- **WHEN** a streamed flag is for a ticker that is not on the watchlist
- **THEN** no notification is shown

### Requirement: Notification content and click

A notification title SHALL start with `MOCK ` unless the data mode is `live`, followed by the ticker, the rounded score and the verdict label. The body SHALL give the post and trade counts and the risk-only notice and SHALL NOT contain buy or sell advice. Clicking a notification SHALL focus the tab and select the flag's ticker.

#### Scenario: Mock flag
- **WHEN** a mock flag for GME scoring 74 notifies
- **THEN** the title is `MOCK GME flag: 74 Pump likely` and the body ends with the notice "Risk signal only. Not investment advice."

#### Scenario: Click
- **WHEN** the user clicks the notification
- **THEN** the tab is focused, the ticker is selected and the notification closes

### Requirement: Notification preferences stay in the browser

Notification preferences SHALL be stored only in this browser under `pumpwatch:notify:v1`. Corrupted data SHALL read as off at the default minimum. When browser storage is unavailable, the choice SHALL apply for the current session and the UI SHALL say it is session-only.

#### Scenario: Storage unavailable
- **WHEN** `localStorage` throws and the user turns notifications on
- **THEN** notifications work for this session and a message says the setting is kept for this session only
