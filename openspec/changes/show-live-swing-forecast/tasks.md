# Live Swing Forecast Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Show the next-hour swing forecast (median, upper band, regime) in the `/live` score chart header and on each flag row, share one `Swing` component with the landing panel, and add the swing to the flag assistant context.

**Architecture:** A three-line `swingRange` formatter in `web/lib/swing.ts` feeds both a shared `Swing` component in `web/components/term-panel.tsx` and `flagContext`. The landing panel swaps its inline copy for the component. `live-view.tsx` gets one inserted line in ChartPanel, one in FlagFeed and a two-line change in LiveView. No API or mock change.

**Tech Stack:** Next.js 16, React 19, TypeScript, Tailwind 4, `node --experimental-strip-types --test` tests (`.mjs`, no TSX rendering), Playwright MCP for the final check.

**Spec:** `openspec/changes/show-live-swing-forecast/proposal.md`, `design.md`, `specs/web-live-view/spec.md`, and GitHub issue #130 (approved design).

## Global Constraints

- No code comments of any kind in new or changed code (owner rule). Keep existing comments as they are.
- Test-first for every task: write the failing test, run it and see it fail, implement, run it and see it pass, commit.
- Every UI task (2 and 3) MUST be done with the `impeccable` and `design-taste-frontend` skills against `web/DESIGN.md`: amber-on-black terminal style, labels amber, values white, regime in yellow, square corners, no shadows or gradients, no Bloomberg name or logo, 4.5:1 contrast, 44px touch targets on phones.
- Green (`text-up`) and red (`text-down`) mean price direction only; the swing never uses them, `dirTone`, `signed`, arrows, a plus or minus sign, or buy/sell wording.
- Exact copy: "Expected range next hour: about X%, up to Y% (high to low). REGIME for this ticker." / "No forecast yet." / "Mock forecast, not a model output." / "Size of move, not direction. Not investment advice."
- Mock mode always labels the forecast as mock. Public and mock deployments show only labelled mock data.
- Assistant context stays at 600 characters or fewer (`research/api/service.py` `MAX_CONTEXT = 600`).
- Other branches edit `web/components/live-view.tsx` in parallel (FlagFeed: #131, #133; ChartPanel: #132). Only touch the lines named in Task 3. Do not reformat, reorder or rename anything else in that file.
- Checks run from `web/`: `npm test`, `npm run lint`, `npm run build`. Run `npm ci` first if `node_modules` is missing. Use `127.0.0.1`, not `localhost`, in scripts and URLs.
- Commits: Conventional Commits (`feat(web): ...`, `test(web): ...`), no `Co-Authored-By` or AI attribution line. Commit on `feat/live-swing-forecast`; never push or merge to `main`.

## Review Focus

- Regime may arrive uppercase ("CALM" passes `validSwing`): the context lowercases it, the component uppercases it with CSS. Pinned in Task 1 (`EXTREME` input).
- 100 long drivers must not push the swing out of the 600 character context: the swing sits before "Driven by". Pinned in Task 1.
- A zero forecast (median 0, upper band 0) is a real forecast, shown as "about 0.0%, up to 0.0%", not "No forecast yet". Pinned in Task 1.
- A selected ticker with no watchlist score (picked from movers or a flag) must show "No forecast yet.", never another ticker's swing: the chart reads the row matching `ticker`. Pinned in Task 3.
- A streamed flag must show its own swing, not the current score's, with the mock label from the feed's mode. Pinned in Task 3.

---

### Task 1: Swing formatter and assistant context

**Files:**
- Create: `web/lib/swing.ts`
- Modify: `web/lib/flag-context.ts:6-8`
- Test: `web/tests/swing.test.mjs` (new), `web/tests/flag-context.test.mjs`

**Interfaces:**
- Consumes: `type Swing = { median: number; upperBand: number; regime: "calm" | "elevated" | "extreme" }` and `type Flag` (with `swing?: Swing | null`) from `web/lib/api.ts`.
- Produces: `export function swingRange(s: Swing): string` in `web/lib/swing.ts`, imported as `./swing.ts` from `lib/` and `@/lib/swing` from components. `flagContext(f: Flag): string` keeps its signature.

- [ ] **Step 1: Write the failing tests**

`web/tests/swing.test.mjs`:

```js
import assert from "node:assert/strict";
import { test } from "node:test";
import { swingRange } from "../lib/swing.ts";

test("swingRange gives median and upper band to one decimal with no sign", () => {
  assert.equal(swingRange({ median: 3.2, upperBand: 9.5, regime: "extreme" }), "about 3.2%, up to 9.5% (high to low)");
  assert.equal(swingRange({ median: 0.6, upperBand: 12, regime: "calm" }), "about 0.6%, up to 12.0% (high to low)");
  assert.equal(swingRange({ median: 0, upperBand: 0, regime: "calm" }), "about 0.0%, up to 0.0% (high to low)");
});
```

In `web/tests/flag-context.test.mjs`, update the two existing exact strings in the first test (those flags have no swing) to:

```js
"GME flag: score 74 of 100 (Pump likely). 120 posts, 45 trades. 2 possible catalyst(s) found, score discounted. No next-hour swing forecast yet. Driven by volume (+12.3)."
"GME flag: score 20 of 100 (Low risk). 1 posts, 2 trades. catalyst check not run. No next-hour swing forecast yet."
```

and add:

```js
const base = { score: 74, postCount: 120, tradeCount: 45, catalyst: { found: false, items: [] }, topContributors: [] };
const swing = { median: 3.2, upperBand: 9.5, regime: "EXTREME" };

test("the flag context includes the swing in lowercase regime", () => {
  assert.equal(flagContext({ ...flag(base), swing }), "GME flag: score 74 of 100 (Pump likely). 120 posts, 45 trades. no catalyst found. Next-hour swing: about 3.2%, up to 9.5% (high to low), extreme for this ticker. Size of move, not direction.");
});

test("the flag context says no forecast when the swing is null or absent", () => {
  const none = "GME flag: score 74 of 100 (Pump likely). 120 posts, 45 trades. no catalyst found. No next-hour swing forecast yet.";
  assert.equal(flagContext({ ...flag(base), swing: null }), none);
  assert.equal(flagContext(flag(base)), none);
});

test("the swing survives the 600 character cap when drivers are long", () => {
  const many = Array.from({ length: 100 }, (_, i) => ({ featureGroup: `group-${i}`, delta: 1 }));
  const text = flagContext({ ...flag({ ...base, topContributors: many }), swing });
  assert.ok(text.length <= 600);
  assert.ok(text.includes("Next-hour swing: about 3.2%, up to 9.5% (high to low), extreme for this ticker."));
});
```

- [ ] **Step 2: Run the tests to verify they fail**

Run (from `web/`): `node --experimental-strip-types --test tests/swing.test.mjs tests/flag-context.test.mjs`
Expected: FAIL. `swing.test.mjs` fails with `ERR_MODULE_NOT_FOUND` for `lib/swing.ts`; the flag-context tests fail on the missing swing sentence.

- [ ] **Step 3: Implement**

`web/lib/swing.ts`: `import type { Swing } from "./api.ts";` and `export function swingRange(s: Swing): string` returning `` `about ${s.median.toFixed(1)}%, up to ${s.upperBand.toFixed(1)}% (high to low)` ``.

`web/lib/flag-context.ts`: import `swingRange` from `./swing.ts`. Build the sentence `Next-hour swing: ${swingRange(f.swing)}, ${f.swing.regime.toLowerCase()} for this ticker. Size of move, not direction.` when `f.swing` is truthy, else `No next-hour swing forecast yet.`, and insert it (with one leading space) between `${catalyst}.` and the drivers part of `line`. Keep the control-character replace, `.slice(0, 600)` and the existing comment.

- [ ] **Step 4: Run the tests to verify they pass**

Run (from `web/`): `node --experimental-strip-types --test tests/swing.test.mjs tests/flag-context.test.mjs`
Expected: PASS, `# fail 0`.

- [ ] **Step 5: Commit**

```bash
git add web/lib/swing.ts web/lib/flag-context.ts web/tests/swing.test.mjs web/tests/flag-context.test.mjs
git commit -m "feat(web): add the swing forecast to the flag assistant context (#130)"
```

### Task 2: Shared `Swing` component and the landing panel

Use the `impeccable` and `design-taste-frontend` skills against `web/DESIGN.md` for this task.

**Files:**
- Modify: `web/components/term-panel.tsx` (imports at lines 1-5, new export after `Stale`)
- Modify: `web/components/live-market.tsx:43-53`
- Test: `web/tests/swing-display.test.mjs` (new)

**Interfaces:**
- Consumes: `swingRange(s: Swing): string` from `@/lib/swing` (Task 1).
- Produces: `export function Swing({ swing, mock, className }: { swing?: SwingForecast | null; mock?: boolean; className?: string })` in `web/components/term-panel.tsx`, where `SwingForecast` comes from `import type { DataMode, Swing as SwingForecast } from "@/lib/api"` (extend the existing type import).

- [ ] **Step 1: Write the failing tests**

`web/tests/swing-display.test.mjs`:

```js
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { test } from "node:test";

const web = resolve(import.meta.dirname, "..");
const read = (p) => readFileSync(resolve(web, p), "utf8");
const block = (src, head) => {
  const start = src.indexOf(head);
  if (start < 0) return "";
  const end = src.slice(start + head.length).search(/\n(?:export )?function /);
  return src.slice(start, end < 0 ? undefined : start + head.length + end);
};

test("the shared Swing component carries the forecast copy and no direction cue", () => {
  const swing = block(read("components/term-panel.tsx"), "export function Swing(");
  assert.ok(swing, "Swing is exported from term-panel.tsx");
  for (const copy of ["Expected range next hour: ", "swingRange(", "for this ticker.", "No forecast yet.", "Mock forecast, not a model output.", "Size of move, not direction.", "Not investment advice."]) {
    assert.ok(swing.includes(copy), copy);
  }
  assert.doesNotMatch(swing, /text-up|text-down|dirTone|signed\(|[↑↓▲▼]|\b(?:buy|sell|bullish|bearish)\b/i);
});

test("the landing evidence panel renders the shared Swing component", () => {
  const src = read("components/live-market.tsx");
  assert.match(src, /<Swing swing=\{score\?\.swing\} mock=\{data\.mode === "mock"\}/);
  assert.doesNotMatch(src, /Expected range next hour/);
});
```

- [ ] **Step 2: Run the tests to verify they fail**

Run (from `web/`): `node --experimental-strip-types --test tests/swing-display.test.mjs`
Expected: FAIL with "Swing is exported from term-panel.tsx" and the `<Swing` match failure.

- [ ] **Step 3: Implement**

`Swing` renders one `<p>` with `data-testid="swing"`, `text-xs text-mute` and `className`. With a swing: a white span `Expected range next hour: {swingRange(swing)}.`, a `uppercase text-yellow` span with `{swing.regime}`, white ` for this ticker.`, then ` Mock forecast, not a model output.` only when `mock`, then ` Size of move, not direction. Not investment advice.` Without a swing: `No forecast yet. Size of move, not direction. Not investment advice.` Exact markup and spacing are the implementer's call under the design skills; the copy is fixed.

`live-market.tsx`: keep the `<p className="text-accent">Expected swing, next hour</p>` heading and replace the conditional block and the trailing "Size of move" paragraph under it with `<Swing swing={score?.swing} mock={data.mode === "mock"} className="mt-1" />`. Import `Swing` from `./term-panel`.

- [ ] **Step 4: Run the tests to verify they pass**

Run (from `web/`): `npm test`
Expected: PASS, `# fail 0` (includes `notices.test.mjs`, which still finds `{NOTICE}` in `live-market.tsx`).

- [ ] **Step 5: Lint and type-check**

Run (from `web/`): `npm run lint` then `npm run build`
Expected: lint prints no errors; build ends with the route table and no type errors.

- [ ] **Step 6: Commit**

```bash
git add web/components/term-panel.tsx web/components/live-market.tsx web/tests/swing-display.test.mjs
git commit -m "feat(web): share one Swing component for the landing forecast (#130)"
```

### Task 3: Swing in the terminal chart header and flag rows

Use the `impeccable` and `design-taste-frontend` skills against `web/DESIGN.md` for this task. Keep the `live-view.tsx` diff to the lines below.

**Files:**
- Modify: `web/components/live-view.tsx`: line 13 (add `Swing` to the existing `@/components/term-panel` import), the ChartPanel signature at line 160 plus one line after its score/verdict/quote `div` (ends near line 177), FlagFeed one line after the evidence `<p>` (ends near line 245), the LiveView `stale` line near 325 and the `<ChartPanel ...>` call near line 530.
- Test: `web/tests/swing-display.test.mjs`

**Interfaces:**
- Consumes: `Swing` component from `@/components/term-panel` (Task 2); the `Score` type already imported from `@/lib/api`.
- Produces: `ChartPanel` gains prop `swing?: Score["swing"]` (no change to the `@/lib/api` import line).

- [ ] **Step 1: Write the failing tests**

Append to `web/tests/swing-display.test.mjs`:

```js
test("the terminal chart header shows the selected ticker's own swing", () => {
  const src = read("components/live-view.tsx");
  assert.match(block(src, "function ChartPanel("), /<Swing swing=\{swing\} mock=\{mode === "mock"\}/);
  assert.match(src, /const row = scores\?\.find\(\(s\) => s\.ticker === ticker\);/);
  assert.match(src, /<ChartPanel [^>]*swing=\{row\?\.swing\}/);
});

test("each terminal flag row shows that flag's own swing", () => {
  const feed = block(read("components/live-view.tsx"), "function FlagFeed(");
  assert.match(feed, /<Swing swing=\{f\.swing\} mock=\{mode === "mock"\}/);
});
```

- [ ] **Step 2: Run the tests to verify they fail**

Run (from `web/`): `node --experimental-strip-types --test tests/swing-display.test.mjs`
Expected: FAIL on the two new tests; the Task 2 tests still pass.

- [ ] **Step 3: Implement**

- LiveView: replace `const stale = scores?.find((s) => s.ticker === ticker)?.staleSources ?? [];` with `const row = scores?.find((s) => s.ticker === ticker);` and `const stale = row?.staleSources ?? [];`. Add `swing={row?.swing}` to the `<ChartPanel ...>` call.
- ChartPanel: add `swing` to the destructured props and `swing?: Score["swing"]` to its type. Insert `<Swing swing={swing} mock={mode === "mock"} className="mb-2" />` right after the score/verdict/quote `div`, before `<SentimentBeside`.
- FlagFeed: insert `<Swing swing={f.swing} mock={mode === "mock"} />` inside each `<li>`, after the evidence `<p>`.

- [ ] **Step 4: Run the tests to verify they pass**

Run (from `web/`): `npm test`
Expected: PASS, `# fail 0`.

- [ ] **Step 5: Lint and type-check**

Run (from `web/`): `npm run lint` then `npm run build`
Expected: no lint errors; build succeeds with no type errors.

- [ ] **Step 6: Commit**

```bash
git add web/components/live-view.tsx web/tests/swing-display.test.mjs
git commit -m "feat(web): show the next-hour swing in the live chart and flag rows (#130)"
```

### Task 4: Verify in the running app with mock data

**Files:** none, unless a check fails. Then fix it in the owning task's files, failing test first, and commit as `fix(web): ...`.

**Interfaces:**
- Consumes: everything above. Mock data in `web/mock-api.mjs`: GME median 3.2 / upper 9.5 / extreme, AMC 2.1 / 6.4 / elevated, SNDL 0.6 / 1.8 / calm; flags exist only for GME and AMC.

- [ ] **Step 1: Full checks**

Run (from `web/`): `npm ci` (only if `node_modules` is missing), `npm test`, `npm run lint`, `npm run build`
Expected: `# fail 0`; no lint errors; build succeeds.

- [ ] **Step 2: Start the app in mock mode**

Confirm `web/.env` does not exist (no `.env` means `DATA_MODE=mock`). Run (from `web/`, in the background): `npm run dev`. Wait until `http://127.0.0.1:3000/live` answers.

- [ ] **Step 3: Check `/live` with Playwright MCP**

`mcp__playwright__browser_navigate` to `http://127.0.0.1:3000/live`, then use `browser_snapshot` and `browser_evaluate` to confirm:
- The status line shows `DATA MOCK`.
- The chart panel (`#score-chart`) for the default ticker GME contains "Expected range next hour: about 3.2%, up to 9.5% (high to low).", the regime "extreme" (case-insensitive; rendered uppercase), "Mock forecast, not a model output." and "Size of move, not direction."
- Click AMC in the watchlist: the chart shows "about 2.1%, up to 6.4%" and "elevated". Click SNDL: "about 0.6%, up to 1.8%" and "calm".
- The Flags panel (`#flags`) GME row shows 3.2% / 9.5% / extreme and the AMC row 2.1% / 6.4% / elevated, each with the mock label.
- No direction colour: `[...document.querySelectorAll('[data-testid=swing], [data-testid=swing] *')].map(e => getComputedStyle(e).color)` contains neither `rgb(25, 224, 107)` nor `rgb(255, 77, 77)`.
- Click "Ask about the GME flag"; in `browser_network_requests` the POST to `/api/v1/assistant/ask` carries a `context` that contains "Next-hour swing: about 3.2%, up to 9.5% (high to low), extreme for this ticker." and is at most 600 characters. The response may be 503 when no research service runs; only the request matters.
- `browser_console_messages` shows no errors from the app.
- `browser_resize` to 375x812 and reload: `document.documentElement.scrollWidth <= window.innerWidth` is true and the swing text on flag rows wraps inside the panel. Take a screenshot, then resize back.

- [ ] **Step 4: Check the landing page**

Navigate to `http://127.0.0.1:3000/`. The evidence panel under "Expected swing, next hour" shows the shared component's copy for its ticker with "Mock forecast, not a model output.", and the footer still shows "MOCK DATA."

- [ ] **Step 5: Stop the dev server and mark the change done**

Stop `npm run dev`. Tick every box in this file and commit:

```bash
git add openspec/changes/show-live-swing-forecast/tasks.md
git commit -m "docs(openspec): mark live swing forecast tasks done (#130)"
```
