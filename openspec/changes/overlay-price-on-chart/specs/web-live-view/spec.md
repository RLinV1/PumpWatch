## ADDED Requirements

### Requirement: Intraday price bars route
The web host SHALL serve `GET /api/v1/tickers/:ticker/bars?from=&to=` with 5-minute bars as `{ ticker, from, to, timeframe: "5Min", feed, bars: [{ at, close }] }`. The ticker MUST match `^[A-Z]{1,5}$` and the window MUST be at most 6 hours. Provider keys MUST stay on the server. Every response SHALL carry the shared notice and the `X-Pumpwatch-Data-Mode` header.

#### Scenario: Valid request in live mode
- **WHEN** a client requests the bars of `GME` for the last 6 hours and the provider answers
- **THEN** the response is 200 with `mode: "live"`, the notice, the `X-Pumpwatch-Data-Mode: live` header and the bars in time order, and no key appears in it

#### Scenario: Invalid ticker or window
- **WHEN** the ticker is lowercase or the window is longer than 6 hours, unparsable or empty
- **THEN** the response is 400 and the provider is not called

#### Scenario: Provider failure
- **WHEN** the provider fails, times out or returns an unreadable body
- **THEN** the response is 503 with a generic error and no provider detail

### Requirement: Live price is never invented
In live mode the server SHALL return only bars the provider reported, and SHALL drop bars without a positive close or a valid time inside the window. It SHALL NOT fill, interpolate or estimate missing bars.

#### Scenario: Gaps and bad bars
- **WHEN** the provider omits some 5-minute bars and returns one bar with a zero close
- **THEN** the response leaves the gap and omits the zero-close bar

#### Scenario: No trades
- **WHEN** the provider reports no bars for the window
- **THEN** the response has an empty `bars` list and the chart says "No price bars in this window"

### Requirement: Mock price series is labelled
In mock mode the web host SHALL serve an invented, deterministic 5-minute series with `feed: "mock"` and `mode: "mock"`, and the chart SHALL label it MOCK.

#### Scenario: Mock chart
- **WHEN** `/live` runs with `DATA_MODE=mock`
- **THEN** the Score chart shows a cyan price line and a MOCK tag next to the price legend

### Requirement: Price overlay on the score chart
The `/live` Score chart SHALL draw the 5-minute close as a second line on its own scale, on the same time axis as the score, broken where bars are missing, and SHALL show "Price unavailable" and no price line when the latest price read failed. It SHALL stay readable with zero, one or many bars and SHALL NOT give buy or sell advice.

#### Scenario: One bar
- **WHEN** the window holds a single bar
- **THEN** the chart draws a short level line across that bar's 5 minutes

#### Scenario: Provider down
- **WHEN** the bars request fails, even after earlier successful reads
- **THEN** the chart shows "Price unavailable" and keeps drawing the score
