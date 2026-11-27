## Why

The `/live` score chart plots only the risk score. A user cannot see whether the score rose before, with or after the price move, and that timing is the central evidence for a pump (GitHub issue #132).

## What Changes

- New server-side reader `web/price-bars.mjs`: 5-minute Alpaca bars for one ticker, server-only keys, a 5-second deadline, shared in-flight reads, a 60-second cache per 5-minute window and a 5-second failure cooldown, the same shape as the quotes loader.
- New read-only route `GET /api/v1/tickers/:ticker/bars?from=&to=`. The ticker must match `^[A-Z]{1,5}$` and the window is at most 6 hours. Responses carry the shared notice and the `X-Pumpwatch-Data-Mode` header.
- Mock mode serves an invented, deterministic 5-minute series with `feed: "mock"`, labelled MOCK on the chart.
- The score chart draws price (5-minute close) as a second, cyan line on its own scale, broken at gaps, with its high and low printed on the left.
- A provider failure shows "Price unavailable" and no line. An empty window shows "No price bars in this window". Live mode never invents or interpolates prices.
- No buy or sell wording anywhere. The landing page chart is unchanged.

## Impact

- New: `web/price-bars.mjs`, `web/lib/price-line.ts`, `web/tests/price-bars.test.mjs`, `web/tests/price-line.test.mjs`, `web/tests/price-chart.test.mjs`.
- Modified: `web/context-api.mjs`, `web/server.mjs`, `web/mock-api.mjs`, `web/lib/api.ts`, `web/components/live-chart.tsx`, `web/components/live-view.tsx` (two lines in `ChartPanel`, one import line), `web/README.md`.
- Shared files with parallel work (#130, #131, #133): edits in `live-view.tsx`, `mock-api.mjs` and `lib/api.ts` are small, additive and local.
