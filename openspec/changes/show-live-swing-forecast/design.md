## Context

`Score.swing` and `Flag.swing` are typed `Swing | null | undefined` in `web/lib/api.ts` and already validated by `validSwing` (regime accepted in any case). The landing panel renders the swing inline. The `/live` terminal (`live-view.tsx`) ignores it. The web tests run under `node --experimental-strip-types --test`, which cannot render TSX, so component behaviour is pinned by pure helpers plus source-inspection tests in the style of `tests/notices.test.mjs`.

Other branches are editing `live-view.tsx` in parallel (FlagFeed: #131, #133; ChartPanel: #132), so edits there stay as single inserted lines.

## Goals / Non-Goals

**Goals:**
- One component and one formatter for every swing surface, with identical copy.
- Terminal chart header and flag rows show the swing; the assistant context carries it.

**Non-Goals:**
- Any API, mock data or model change.
- Showing the swing in the watchlist, palette or ticker strip.
- Telling the assistant that a forecast is mock (the context has no data mode; the UI labels it).

## Decisions

### Shared formatter in `web/lib/swing.ts`
`swingRange(s: Swing): string` returns `about ${median.toFixed(1)}%, up to ${upperBand.toFixed(1)}% (high to low)`. Both the component and `flagContext` use it, so the numbers read the same on screen and to the assistant. A new three-line file avoids touching `lib/api.ts`, which other branches edit.

### `Swing` component in `term-panel.tsx`
`Swing({ swing, mock, className })` renders one short paragraph so it fits a dense flag row. The regime is a yellow uppercase span (yellow is the highlight colour in `web/DESIGN.md`); green and red are never used, because they mean price direction. The imported type is aliased `SwingForecast` to avoid the name clash.

### Chart header source
The chart reads the selected ticker's row from the watchlist scores LiveView already holds (`row = scores?.find((s) => s.ticker === ticker)`), so no new fetch. A ticker with no watchlist score shows "No forecast yet." The prop is typed `Score["swing"]` so the `@/lib/api` import line does not change.

### Flag rows use the flag's own swing
`f.swing` is the forecast at the moment the flag was raised, which is what the flag explains. Mock labelling uses the FlagFeed's own `mode`.

### Assistant context order
The swing sentence goes after the catalyst sentence and before "Driven by", because drivers are unbounded and the line is cut at 600 characters. The regime is lowercased there.

## Risks / Trade-offs

- [Merge conflicts in `live-view.tsx` and its `term-panel` import line] -> Keep each edit to one inserted line; the import change is a single added name.
- [Flag rows get taller] -> One short paragraph per row; the impeccable pass checks density.
- [The chart hides the swing while the timeline is loading or empty] -> Accepted: the chart body only renders with data, as today.
