## Why

The volatility forecaster's `swing` (median, upper band, regime) is served on every score and flag, but the `/live` terminal never shows it. Only the landing evidence panel does, with its own inline copy. The flag assistant is also never told the swing, so it cannot explain it. Approved design: GitHub issue #130.

## What Changes

- A shared `Swing` component in `web/components/term-panel.tsx` renders "Expected range next hour: about X%, up to Y% (high to low). REGIME for this ticker.", "No forecast yet." when the swing is null or absent, a mock label in mock mode, and "Size of move, not direction. Not investment advice." It carries no direction cue.
- The landing evidence panel (`live-market.tsx`) uses the shared component instead of its inline copy.
- The `/live` score chart header shows the selected ticker's swing from its watchlist score, and each flag row shows that flag's own swing.
- `flagContext` adds the swing (or "No next-hour swing forecast yet.") before the drivers, so it survives the 600 character cap.
- A tiny `swingRange` helper in `web/lib/swing.ts` formats the range once for the component and the context.

## Impact

- Code: `web/lib/swing.ts` (new), `web/lib/flag-context.ts`, `web/components/term-panel.tsx`, `web/components/live-market.tsx`, `web/components/live-view.tsx` (ChartPanel, FlagFeed and one line in LiveView).
- Tests: `web/tests/swing.test.mjs` (new), `web/tests/flag-context.test.mjs`, `web/tests/swing-display.test.mjs` (new).
- No API, schema or server change. Mock data already carries GME extreme, AMC elevated, SNDL calm.
