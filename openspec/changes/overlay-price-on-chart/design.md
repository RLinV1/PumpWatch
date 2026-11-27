# Design

## Context

The `/live` Score chart (`ChartPanel` in `web/components/live-view.tsx`) polls `GET /api/v1/tickers/:ticker/timeline?from=&to=` for the last 6 hours through `useApi(path, 30000, nonce, 6, refreshKey)`, which appends `from` and `to` as ISO strings. `LiveChart` (`web/components/live-chart.tsx`) draws the score in a stretched 0-100 SVG. Last price already comes from `web/market-quotes.mjs` (Alpaca snapshots, server-side keys). This change adds intraday price beside the score on the same time axis.

## Goals / Non-Goals

**Goals:**
- Price and score on one time axis, so the order of the moves is visible.
- Keys stay on the web host. Mock and public deployments use invented, labelled data.
- Missing price is shown as missing, never estimated.

**Non-Goals:**
- Candles, volume, other timeframes, a price axis with ticks, the landing page chart, price in the Spring API, persistence of bars.

## Decisions

**Alpaca single-symbol bars endpoint.** `GET https://data.alpaca.markets/v2/stocks/{ticker}/bars?timeframe=5Min&start=<iso>&end=<iso>&feed=<feed>&limit=100`, headers `APCA-API-KEY-ID` and `APCA-API-SECRET-KEY`. Response `{ bars: [{ t, o, h, l, c, v, n, vw }] | null, symbol, next_page_token }`. `bars: null` means no trades in the window. Six hours of 5-minute bars is at most 73, so one page with `limit=100` is enough and paging is not needed. This shape comes from the Alpaca market data v2 documentation; the repo has no bars caller yet, so tests pin it with a fake fetch. Feed comes from `ALPACA_FEED` (default `iex`), like quotes.

**Only `{ at, close }` leaves the server.** The chart draws the 5-minute close. Open, high, low and volume are dropped (YAGNI). Bars with a non-positive or non-finite close, an unparsable time or a time outside `[from, to]` are dropped. Bars are sorted by time. Gaps are never filled.

**Cache per 5-minute window.** The client asks for a window ending "now" on every poll, so the cache key is `ticker:floor(from/300000):floor(to/300000)`. Hits live 60 seconds, failures cool down 5 seconds, at most 50 entries each, oldest evicted, and concurrent reads share one request, as in `createQuotesLoader`. The cached body keeps the `from` and `to` of the read that filled it; the chart clips bars to the timeline's own window.

**One window parser for live and mock.** `parseBarsWindow(searchParams, now)` in `web/price-bars.mjs` mirrors the timeline defaults (`to` = now, `from` = `to` - 6 hours) and returns `null` for unparsable values, `from >= to` or a window over 6 hours. `context-api.mjs` and `mock-api.mjs` both use it.

**Route on the existing context door.** `context-api.mjs` already serves per-ticker `news` and `sentiment`; `bars` joins that pattern, so it gets the notice, the `live` data-mode header, the read-only check, the `runId` rejection and the sanitized 503 for free.

**Price on its own scale, cyan.** `web/DESIGN.md` reserves cyan for secondary data series and green and red for price direction only, so the line is cyan, not green or red. Closes map to y 10..90 (y 50 when flat) so the line keeps clear of the frame. Pure geometry lives in `web/lib/price-line.ts` so Node tests can run it without JSX.

**Gaps break the line.** Consecutive bars more than 5 minutes apart start a new segment. A segment of one bar is drawn across its own 5 minutes (clipped to the window) so it is visible. Joining across a gap would imply prices that were never traded.

**Failure hides the line.** `useApi` keeps the last good data when a poll fails. For price the chart shows "Price unavailable" and no line whenever the latest read failed, because the issue says a provider failure shows that text and a stale line could read as current.

**Small edits to shared files.** `live-view.tsx` gets one new `import type` line, one `useApi` line and a changed `<LiveChart>` call inside `ChartPanel`. `lib/api.ts` gets a type, one validator and one branch placed before the existing context regex. `mock-api.mjs` gets one new block. Parallel branches (#130, #131, #133) touch nearby code.

## Risks / Trade-offs

- [The Alpaca response shape is taken from documentation, not from repo code] → The loader rejects any body whose `bars` is neither an array nor null, so a shape change shows "Price unavailable" instead of a wrong line. The owner's first live run confirms it.
- [The free `iex` feed is thin for small caps, so many 5-minute bars are missing] → The line breaks into short segments, which is honest. `ALPACA_FEED=sip` needs a plan that allows recent SIP data.
- [Price lags quotes by up to 60 seconds plus the open bar] → Acceptable for a 6-hour context line; the header still shows the last trade from quotes.
- [Clients can send many distinct windows] → Cache and cooldown maps are capped at 50 entries.
