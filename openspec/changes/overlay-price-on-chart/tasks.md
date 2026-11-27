# Price Overlay on the Live Score Chart Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Draw the 5-minute price close as a second line on the `/live` Score chart, served by a new server-side bars route, with a labelled mock series and an honest "Price unavailable" state.

**Architecture:** `web/price-bars.mjs` reads Alpaca 5-minute bars with server-side keys (cached and coalesced like `web/market-quotes.mjs`). `web/context-api.mjs` exposes it at `GET /api/v1/tickers/:ticker/bars`, `web/mock-api.mjs` serves an invented series in mock mode, and `web/lib/api.ts` validates it in the browser. `web/lib/price-line.ts` turns bars into SVG segments that `web/components/live-chart.tsx` draws in cyan on its own scale.

**Tech Stack:** Node 22 ESM (`node --experimental-strip-types --test`), Next 16, React 19, Tailwind 4, Playwright MCP for the final check.

**Spec:** `openspec/changes/overlay-price-on-chart/proposal.md`, `design.md`, `specs/web-live-view/spec.md`, and GitHub issue #132 (approved design).

## Global Constraints

- No code comments of any kind in new code: no inline, block, JSDoc or TODO. Keep existing comments untouched.
- Test first for every task: write the failing test, run it and see it fail, implement, run it and see it pass, commit.
- All UI work uses the `impeccable` and `design-taste-frontend` skills against `web/DESIGN.md`: amber-on-black terminal, square corners, no shadows, gradients, blur or rounded shapes, IBM Plex Mono for data, cyan `var(--cyan)` for the secondary series, green and red only for price direction. No Bloomberg name or logo.
- Ticker must match `^[A-Z]{1,5}$`; the window is at most 6 hours (21600000 ms).
- Alpaca keys stay server-side. Tests use a fake `fetchImpl` and never touch the network. Never print keys or `.env` values.
- Mock mode serves an invented series with `feed: "mock"` and the chart labels it MOCK. Live mode never invents, fills or interpolates prices.
- Responses carry the shared notice `Risk signal only. Not investment advice. Pumpwatch is a non-commercial project.` and the `X-Pumpwatch-Data-Mode` header. No buy or sell wording.
- Checks run from `web/`: `npm test`, `npm run lint`, `npm run build`. Run `npm ci` first if `node_modules` is missing. Use `127.0.0.1`, not `localhost`, in scripts and URLs.
- Parallel branches also edit `web/components/live-view.tsx` (#130 ChartPanel header, #131 and #133 FlagFeed), `web/mock-api.mjs` and `web/lib/api.ts` (#131). Keep edits there small, additive and local; do not reformat or reorder existing lines.
- Conventional Commits, no `Co-Authored-By` or AI attribution line. Commit on `feat/price-overlay`; do not push to `main`.

## Review Focus

- Market closed, weekend or a halted ticker: Alpaca returns `bars: null`; the route returns `bars: []` and the chart says "No price bars in this window", not an error. Pinned in Task 1 (`treats a null bar list as an empty window`) and Task 4 (`priceStatus`).
- A provider failure after earlier good reads: `useApi` keeps the old data with `failed: true`; the chart must hide the line and say "Price unavailable". Pinned in Task 4 (`priceStatus reports unavailable whenever the latest read failed`).
- Sub-dollar tickers (SNDL-like, or 0.0012 to 0.0013): the scale still spans 10..90 and labels use `money()` with 4 decimals. Pinned in Task 4 (`sub-dollar closes still span the scale`).
- Many distinct windows from clients: cache and cooldown maps stay at 50 entries. Pinned in Task 1 (`evicts the oldest cached window past 50 entries`).
- A lone bar at the right edge of the window draws a zero-length segment: the price polyline uses `strokeLinecap="square"` so it shows as a dot. Pinned in Task 4 (`a lone bar at the right edge is clipped to the window`) and Task 5 (source test asserts `strokeLinecap="square"`).

---

### Task 1: Bars loader

**Files:**
- Create: `web/price-bars.mjs`
- Test: `web/tests/price-bars.test.mjs`

**Interfaces:**
- Consumes: nothing.
- Produces:
  - `export const MAX_BARS_WINDOW_MS = 21600000`
  - `export function parseBarsWindow(params: URLSearchParams, now: number): { from: number; to: number } | null`. `to` = `Date.parse(params.get("to"))` if present else `now`; `from` = `Date.parse(params.get("from"))` if present else `to - MAX_BARS_WINDOW_MS`. Returns `null` when either is not finite, `from >= to`, or `to - from > MAX_BARS_WINDOW_MS`.
  - `export function createBarsLoader({ fetchImpl = fetch, keyId, secretKey, feed = "iex", now = Date.now } = {}): (ticker: string, from: number, to: number) => Promise<PriceBars>` where `PriceBars = { ticker, from: string /* ISO */, to: string /* ISO */, timeframe: "5Min", feed: string, bars: { at: string, close: number }[] }`.
  - Errors: `Error("Invalid bars request")` for a bad ticker or window, before any fetch. `Error("Price bars unavailable")` for missing credentials, a feed not in `iex | sip | delayed_sip`, non-ok status, deadline, a body that is not an object, or `bars` that is neither an array nor `null`.

- [ ] **Step 1: Write the failing tests**

```js
import assert from "node:assert/strict";
import { test } from "node:test";
import { MAX_BARS_WINDOW_MS, createBarsLoader, parseBarsWindow } from "../price-bars.mjs";

const FROM = Date.parse("2026-10-29T14:00:00Z");
const TO = FROM + MAX_BARS_WINDOW_MS;
const iso = (ms) => new Date(ms).toISOString();
const bar = (ms, c) => ({ t: iso(ms).replace(".000", ""), o: c, h: c, l: c, c, v: 100, n: 3, vw: c });
function upstream(body) {
  const calls = [];
  const fetchImpl = async (url, options) => { calls.push({ url: new URL(url), options }); return Response.json(body); };
  return { calls, fetchImpl };
}
const loader = (fetchImpl, extra = {}) => createBarsLoader({ fetchImpl, keyId: "key", secretKey: "secret", feed: "iex", ...extra });

test("parses the chart window and caps it at six hours", () => {
  assert.equal(MAX_BARS_WINDOW_MS, 21600000);
  assert.deepEqual(parseBarsWindow(new URLSearchParams(""), TO), { from: FROM, to: TO });
  assert.deepEqual(parseBarsWindow(new URLSearchParams({ from: iso(FROM), to: iso(TO) }), 0), { from: FROM, to: TO });
  for (const q of [{ from: iso(FROM - 1), to: iso(TO) }, { from: iso(TO), to: iso(TO) }, { from: "nope" }, { to: "nope" }]) {
    assert.equal(parseBarsWindow(new URLSearchParams(q), TO), null, JSON.stringify(q));
  }
});

test("requests 5-minute bars for one ticker with server-side keys", async () => {
  const { calls, fetchImpl } = upstream({ bars: [bar(FROM, 24.1)], symbol: "GME", next_page_token: null });
  const data = await loader(fetchImpl, { feed: "sip" })("GME", FROM, TO);
  assert.equal(calls.length, 1);
  assert.equal(calls[0].url.origin + calls[0].url.pathname, "https://data.alpaca.markets/v2/stocks/GME/bars");
  assert.deepEqual(Object.fromEntries(calls[0].url.searchParams), { timeframe: "5Min", start: iso(FROM), end: iso(TO), feed: "sip", limit: "100" });
  assert.equal(calls[0].options.headers["APCA-API-KEY-ID"], "key");
  assert.equal(calls[0].options.headers["APCA-API-SECRET-KEY"], "secret");
  assert.deepEqual(data, { ticker: "GME", from: iso(FROM), to: iso(TO), timeframe: "5Min", feed: "sip", bars: [{ at: bar(FROM, 1).t, close: 24.1 }] });
});

test("drops unusable bars, sorts by time and never fills gaps", async () => {
  const m = 300000;
  const { fetchImpl } = upstream({ bars: [bar(FROM + 6 * m, 25), bar(FROM, 24), bar(FROM + m, 0), { ...bar(FROM + 2 * m, 1), c: "x" }, { ...bar(FROM + 3 * m, 1), t: "nope" }, bar(FROM - m, 23), bar(TO + m, 26), bar(FROM + m * 1, 24.2)] });
  const data = await loader(fetchImpl)("GME", FROM, TO);
  assert.deepEqual(data.bars.map((b) => b.close), [24, 24.2, 25]);
});

test("treats a null bar list as an empty window", async () => {
  assert.deepEqual((await loader(upstream({ bars: null, symbol: "GME", next_page_token: null }).fetchImpl)("GME", FROM, TO)).bars, []);
});

test("shares in-flight reads and caches each five-minute window for 60 seconds", async () => {
  const { calls, fetchImpl } = upstream({ bars: [bar(FROM, 24)] });
  let now = 0;
  const load = loader(fetchImpl, { now: () => now });
  const [a, b] = await Promise.all([load("GME", FROM, TO), load("GME", FROM, TO)]);
  assert.strictEqual(a, b);
  now = 59999;
  assert.strictEqual(await load("GME", FROM + 1000, TO + 1000), a);
  assert.equal(calls.length, 1);
  await load("GME", FROM + 300000, TO + 300000);
  assert.equal(calls.length, 2);
  now = 60000;
  await load("GME", FROM, TO);
  assert.equal(calls.length, 3);
});

test("evicts the oldest cached window past 50 entries", async () => {
  const { calls, fetchImpl } = upstream({ bars: [] });
  const load = loader(fetchImpl, { now: () => 0 });
  for (let i = 0; i <= 50; i++) await load("GME", FROM + i * 300000, TO + i * 300000);
  assert.equal(calls.length, 51);
  await load("GME", FROM, TO);
  assert.equal(calls.length, 52);
});

test("rejects bad requests before calling upstream and sanitizes failures with a cooldown", async () => {
  let calls = 0;
  let now = 0;
  let broken = true;
  const load = loader(async () => { calls++; return broken ? new Response("private upstream test-secret", { status: 503 }) : Response.json({ bars: [] }); }, { now: () => now });
  for (const [t, f, e] of [["gme", FROM, TO], ["TOOLONG", FROM, TO], ["GME", FROM - 1, TO], ["GME", TO, TO], ["GME", NaN, TO]]) {
    await assert.rejects(load(t, f, e), { message: "Invalid bars request" });
  }
  assert.equal(calls, 0);
  await assert.rejects(load("GME", FROM, TO), { message: "Price bars unavailable" });
  await assert.rejects(load("GME", FROM, TO), { message: "Price bars unavailable" });
  assert.equal(calls, 1);
  now = 5000;
  broken = false;
  assert.deepEqual((await load("GME", FROM, TO)).bars, []);
  for (const body of [[], { bars: {} }, { bars: "x" }]) {
    await assert.rejects(loader(async () => Response.json(body))("GME", FROM, TO), { message: "Price bars unavailable" });
  }
});

test("has a five-second deadline that aborts upstream and needs credentials", async () => {
  let signal;
  const start = Date.now();
  await assert.rejects(loader((_u, o) => { signal = o.signal; return new Promise(() => {}); })("GME", FROM, TO), { message: "Price bars unavailable" });
  assert.equal(signal.aborted, true);
  assert.ok(Date.now() - start < 6500);
  let calls = 0;
  await assert.rejects(createBarsLoader({ fetchImpl: async () => { calls++; }, keyId: "", secretKey: "" })("GME", FROM, TO), { message: "Price bars unavailable" });
  await assert.rejects(loader(async () => { calls++; }, { feed: "otc" })("GME", FROM, TO), { message: "Price bars unavailable" });
  assert.equal(calls, 0);
});
```

- [ ] **Step 2: Run the tests to verify they fail**

Run (from `web/`): `node --experimental-strip-types --test tests/price-bars.test.mjs`
Expected: FAIL with `ERR_MODULE_NOT_FOUND` for `price-bars.mjs`.

- [ ] **Step 3: Implement `parseBarsWindow` and `createBarsLoader` in `web/price-bars.mjs`**

Copy the structure of `createQuotesLoader` in `web/market-quotes.mjs` (credential and feed check, `AbortController` with a 5000 ms deadline raced against the request, `cache: "no-store"`, `inFlight` map, 50-entry `cache` and `failedUntil` maps with oldest-first eviction, 5000 ms cooldown). Differences: cache key `` `${ticker}:${Math.floor(from / 300000)}:${Math.floor(to / 300000)}` ``, TTL 60000 ms, query `{ timeframe: "5Min", start: iso(from), end: iso(to), feed, limit: "100" }`, path `/v2/stocks/${ticker}/bars`. Map each bar to `{ at: t, close: c }`, keep only `Number.isFinite(c) && c > 0` and `Date.parse(t)` finite within `[from, to]`, sort by `Date.parse(at)`. `bars: null` becomes `[]`. Do not import from or edit `market-quotes.mjs`.

- [ ] **Step 4: Run the tests to verify they pass**

Run: `node --experimental-strip-types --test tests/price-bars.test.mjs`
Expected: PASS, 8 tests, 0 failures.

- [ ] **Step 5: Commit**

```bash
git add web/price-bars.mjs web/tests/price-bars.test.mjs
git commit -m "feat(web): add server-side 5-minute price bars loader (#132)"
```

### Task 2: Live bars route

**Files:**
- Modify: `web/context-api.mjs` (route regex, destructured `loadBars`, window check, dispatch)
- Modify: `web/server.mjs` (create the loader, pass `loadBars`)
- Modify: `web/README.md` (one sentence after the quotes sentence in the `/live` paragraph)
- Test: `web/tests/price-bars.test.mjs` (append)

**Interfaces:**
- Consumes: `parseBarsWindow(params, now)`, `createBarsLoader(...)` from Task 1.
- Produces: `createContextApi({ ..., loadBars })` where `loadBars(ticker: string, from: number, to: number) => Promise<PriceBars>`. Route `GET /api/v1/tickers/:ticker/bars?from=&to=`: 400 `"Invalid context request"` for a ticker failing `^[A-Z]{1,5}$` (existing check), 400 `"Invalid time range (maximum 6 hours)"` when `parseBarsWindow(url.searchParams, Date.now())` is `null`, 200 live envelope on success, 503 `"Data is temporarily unavailable"` with `Retry-After: 5` on failure (existing path).

- [ ] **Step 1: Write the failing test** (append to `web/tests/price-bars.test.mjs`; add `import { createContextApi } from "../context-api.mjs";` to the imports)

```js
function reply() {
  const out = { headers: {}, status: 0, body: "" };
  return Object.assign(out, { setHeader(k, v) { out.headers[k] = v; }, writeHead(s) { out.status = s; }, end(b) { out.body = JSON.parse(b); } });
}
const flush = () => new Promise((r) => setTimeout(r, 10));

test("bars route validates ticker and window, passes the window to the loader and never leaks upstream detail", async () => {
  const reads = [];
  const handle = createContextApi({ loadBars: async (ticker, from, to) => { reads.push([ticker, from, to]); if (ticker === "BAD") throw new Error("PRIVATE"); return { ticker, from: iso(from), to: iso(to), timeframe: "5Min", feed: "iex", bars: [] }; } });
  const win = `from=${iso(FROM)}&to=${iso(TO)}`;
  for (const url of ["/api/v1/tickers/gme/bars", `/api/v1/tickers/GME/bars?from=${iso(FROM - 3600000)}&to=${iso(TO)}`, "/api/v1/tickers/GME/bars?from=nope", `/api/v1/tickers/GME/bars?${win}&runId=r1`]) {
    const r = reply();
    assert.equal(handle({ url, method: "GET" }, r), true);
    await flush();
    assert.equal(r.status, 400, url);
  }
  const post = reply();
  handle({ url: `/api/v1/tickers/GME/bars?${win}`, method: "POST" }, post);
  assert.equal(post.status, 405);
  assert.equal(reads.length, 0);
  const ok = reply();
  handle({ url: `/api/v1/tickers/GME/bars?${win}`, method: "GET" }, ok);
  await flush();
  assert.equal(ok.status, 200);
  assert.equal(ok.headers["X-Pumpwatch-Data-Mode"], "live");
  assert.equal(ok.body.mode, "live");
  assert.match(ok.body.notice, /Not investment advice/);
  assert.deepEqual(reads, [["GME", FROM, TO]]);
  const failed = reply();
  handle({ url: `/api/v1/tickers/BAD/bars?${win}`, method: "GET" }, failed);
  await flush();
  assert.equal(failed.status, 503);
  assert.equal(failed.headers["Retry-After"], "5");
  assert.ok(!JSON.stringify(failed.body).includes("PRIVATE"));
});
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `node --experimental-strip-types --test tests/price-bars.test.mjs`
Expected: FAIL in the new test (the route returns `false` or 503 because `bars` is not matched; `reads` stays empty).

- [ ] **Step 3: Implement the route and wiring**

In `web/context-api.mjs`: widen the per-ticker regex to `(news|sentiment|bars)`, take `loadBars` from the options, add the window check after the existing sentiment-sample check, and dispatch `match[2] === "bars"` to `loadBars(match[1], win.from, win.to)` inside the existing promise chain. In `web/server.mjs`: `import { createBarsLoader } from "./price-bars.mjs";` and pass `loadBars: createBarsLoader({ ...credentials, feed: process.env.ALPACA_FEED ?? "iex" })` to `createContextApi`. In `web/README.md`, after the sentence ending "...cached for 15 seconds).", add: "The Score chart overlays the 5-minute close from `GET /api/v1/tickers/:ticker/bars?from=&to=` (at most 6 hours, Alpaca bars on the same feed, server-side keys, cached for 60 seconds). Missing bars stay gaps, and a provider failure shows `Price unavailable`."

- [ ] **Step 4: Run the tests to verify they pass**

Run: `node --experimental-strip-types --test tests/price-bars.test.mjs tests/context-http.test.mjs tests/context-api.test.mjs tests/market-quotes.test.mjs`
Expected: PASS, 0 failures.

- [ ] **Step 5: Commit**

```bash
git add web/context-api.mjs web/server.mjs web/README.md web/tests/price-bars.test.mjs
git commit -m "feat(web): serve price bars at /api/v1/tickers/:ticker/bars (#132)"
```

### Task 3: Mock series and browser validator

**Files:**
- Modify: `web/mock-api.mjs` (one new block before the timeline block; import `parseBarsWindow`)
- Modify: `web/lib/api.ts` (types after `Quotes`, `validPriceBars` after `validQuotes`, one branch in `validPublicData` before the `context` regex line)
- Test: `web/tests/price-bars.test.mjs` (append)

**Interfaces:**
- Consumes: `parseBarsWindow` from Task 1.
- Produces in `web/lib/api.ts`:
  - `export type PriceBar = { at: string; close: number };`
  - `export type PriceBars = { ticker: string; from: string; to: string; timeframe: "5Min"; feed: string; bars: PriceBar[] };`
  - `api.get<PriceBars>("/api/v1/tickers/:T/bars?...")` accepts data only when: `ticker` equals the path ticker, `from` and `to` are valid dates with `from < to` and `to - from <= 6 * 3600e3`, `timeframe === "5Min"`, `feed` is a string, `bars` is an array of at most 73, each bar has a valid `at` within `[from, to]` strictly after the previous bar and a finite `close > 0`, and `hasRawContent(data)` is false.
- Mock route `GET /api/v1/tickers/:T/bars` (any `[A-Z]{1,5}` ticker): 400 `"invalid time range (maximum 6 hours)"` when `parseBarsWindow` returns `null`; else 200 `{ ticker, from, to, timeframe: "5Min", feed: "mock", bars }` with bars on 5-minute marks from `Math.ceil(from / 300000) * 300000` to `to`, skipping marks 30, 31 and 32 (a visible gap), closes positive and rounded to cents (4 decimals below $1), deterministic for a given `now` and window. Bases: GME 24.5, AMC 3.7, SNDL 1.86, other tickers from the same seed formula as mock quotes.

- [ ] **Step 1: Write the failing tests** (append; add `import { serveMockApi } from "../mock-api.mjs";` and `import * as api from "../lib/api.ts";`)

```js
const mockBars = (path, now = TO) => { const r = reply(); serveMockApi({ url: path, method: "GET", headers: {} }, r, now); return r; };
const WIN = `from=${iso(FROM)}&to=${iso(TO)}`;

test("mock bars are an invented, labelled 5-minute series with a gap", () => {
  const r = mockBars(`/api/v1/tickers/GME/bars?${WIN}`);
  assert.equal(r.status, 200);
  assert.equal(r.headers["X-Pumpwatch-Data-Mode"], "mock");
  assert.equal(r.body.mode, "mock");
  const d = r.body.data;
  assert.equal(d.feed, "mock");
  assert.equal(d.timeframe, "5Min");
  assert.ok(d.bars.length >= 60 && d.bars.length <= 73, String(d.bars.length));
  const at = d.bars.map((b) => Date.parse(b.at));
  assert.ok(d.bars.every((b) => b.close > 0));
  assert.ok(at.every((t, i) => t >= FROM && t <= TO && (i === 0 || t > at[i - 1])));
  assert.ok(at.some((t, i) => i > 0 && t - at[i - 1] > 300000));
  assert.deepEqual(mockBars(`/api/v1/tickers/GME/bars?${WIN}`).body, r.body);
  assert.equal(mockBars(`/api/v1/tickers/ZZZ/bars?${WIN}`).status, 200);
  assert.equal(mockBars(`/api/v1/tickers/GME/bars?from=${iso(FROM - 1)}&to=${iso(TO)}`).status, 400);
});

test("browser validator accepts mock bars and rejects bad ones", async () => {
  const body = mockBars(`/api/v1/tickers/GME/bars?${WIN}`).body;
  const original = globalThis.fetch;
  const serve = (data, mode = "mock") => { globalThis.fetch = async () => new Response(JSON.stringify({ ...body, mode, data }), { headers: { "X-Pumpwatch-Data-Mode": mode } }); };
  const path = `/api/v1/tickers/GME/bars?${WIN}`;
  try {
    serve(body.data);
    assert.equal((await api.get(path)).data.bars.length, body.data.bars.length);
    const d = body.data;
    const first = d.bars[0];
    for (const bad of [
      { ...d, ticker: "AMC" }, { ...d, timeframe: "1Min" }, { ...d, from: iso(FROM - 3600000) },
      { ...d, bars: [{ ...first, close: -1 }] }, { ...d, bars: [{ ...first, close: 0 }] },
      { ...d, bars: [d.bars[1], d.bars[0]] }, { ...d, bars: [first, first] },
      { ...d, bars: [{ ...first, at: iso(FROM - 300000) }] }, { ...d, bars: [{ ...first, text: "raw" }] },
      { ...d, bars: Array.from({ length: 74 }, (_, i) => ({ at: iso(FROM + i * 1000), close: 1 })) },
    ]) {
      serve(bad);
      await assert.rejects(api.get(path), { message: "Invalid API data" });
    }
  } finally { globalThis.fetch = original; }
});
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `node --experimental-strip-types --test tests/price-bars.test.mjs`
Expected: FAIL in both new tests (mock returns 404 `unknown endpoint`; the validator falls through to the timeline check and rejects good data).

- [ ] **Step 3: Implement the mock block and the validator**

Mock: a separate `const bars = /^\/api\/v1\/tickers\/([A-Z]{1,5})\/bars$/.exec(url.pathname);` block; the close formula is free as long as it is deterministic and positive. Validator: `function validPriceBars(data: PriceBars, ticker: string): boolean` next to `validQuotes`, and in `validPublicData` a new first-in-order line before `const context = ...`: `const barsTicker = /^\/api\/v1\/tickers\/([A-Z]{1,5})\/bars$/.exec(endpoint)?.[1];` then `if (barsTicker) return validPriceBars(data as PriceBars, barsTicker);`. Do not edit the existing `context` regex or the import line other tasks touch.

- [ ] **Step 4: Run the tests to verify they pass**

Run: `node --experimental-strip-types --test tests/price-bars.test.mjs tests/mock-api.test.mjs tests/api.test.mjs tests/no-raw-content.test.mjs`
Expected: PASS, 0 failures.

- [ ] **Step 5: Commit**

```bash
git add web/mock-api.mjs web/lib/api.ts web/tests/price-bars.test.mjs
git commit -m "feat(web): add labelled mock price bars and validate bars in the client (#132)"
```

### Task 4: Price line geometry

**Files:**
- Create: `web/lib/price-line.ts`
- Test: `web/tests/price-line.test.mjs`

**Interfaces:**
- Consumes: `PriceBars`, `PriceBar`, `DataMode` types from `web/lib/api.ts` (`import type ... from "./api"`).
- Produces:
  - `export type PriceState = { data?: PriceBars; mode?: DataMode; failed: boolean; done: boolean };` (the `useApi<PriceBars>` result is assignable to it)
  - `export function priceLine(bars: PriceBar[], from: number, to: number): { segments: string[]; low: number; high: number } | null`. Keeps bars with `at` in `[from, to]`; `null` if none. `x = ((Date.parse(at) - from) / (to - from) * 100).toFixed(2)`, `y = (high === low ? 50 : 10 + 80 * (high - close) / (high - low)).toFixed(2)`, points joined `"x,y"` with single spaces. A new segment starts when a bar is more than 300000 ms after the previous one. A one-bar segment gets a second point at `min(at + 300000, to)` with the same y.
  - `export function priceStatus(price: PriceState, from: number, to: number): "loading" | "unavailable" | "empty" | "ready"`: `!done` → loading; `failed` → unavailable (even with data); no data or `priceLine(...) === null` → empty; else ready.

- [ ] **Step 1: Write the failing tests**

```js
import assert from "node:assert/strict";
import { test } from "node:test";
import { priceLine, priceStatus } from "../lib/price-line.ts";

const FROM = Date.parse("2026-10-29T14:00:00Z");
const TO = FROM + 21600000;
const M = 300000;
const b = (ms, close) => ({ at: new Date(ms).toISOString(), close });

test("maps closes onto their own scale between 10 and 90", () => {
  assert.deepEqual(priceLine([b(FROM, 10), b(FROM + M, 20)], FROM, TO), { segments: ["0.00,90.00 1.39,10.00"], low: 10, high: 20 });
});

test("splits at gaps longer than five minutes and widens a lone bar to its five minutes", () => {
  assert.deepEqual(priceLine([b(FROM, 10), b(FROM + M, 20), b(FROM + 12 * M, 15)], FROM, TO).segments, ["0.00,90.00 1.39,10.00", "16.67,50.00 18.06,50.00"]);
});

test("a flat series sits mid-height", () => {
  assert.deepEqual(priceLine([b(FROM + 12 * M, 5)], FROM, TO), { segments: ["16.67,50.00 18.06,50.00"], low: 5, high: 5 });
});

test("sub-dollar closes still span the scale", () => {
  assert.deepEqual(priceLine([b(FROM, 0.0012), b(FROM + M, 0.0013)], FROM, TO).segments, ["0.00,90.00 1.39,10.00"]);
});

test("a lone bar at the right edge is clipped to the window", () => {
  assert.deepEqual(priceLine([b(TO, 5)], FROM, TO).segments, ["100.00,50.00 100.00,50.00"]);
});

test("drops bars outside the window and returns null when none remain", () => {
  assert.equal(priceLine([], FROM, TO), null);
  assert.equal(priceLine([b(FROM - M, 5), b(TO + M, 5)], FROM, TO), null);
  assert.deepEqual(priceLine([b(FROM - M, 99), b(FROM, 5)], FROM, TO), { segments: ["0.00,50.00 1.39,50.00"], low: 5, high: 5 });
});

test("priceStatus reports unavailable whenever the latest read failed", () => {
  const data = { ticker: "GME", from: new Date(FROM).toISOString(), to: new Date(TO).toISOString(), timeframe: "5Min", feed: "mock", bars: [b(FROM, 5)] };
  assert.equal(priceStatus({ done: false, failed: false }, FROM, TO), "loading");
  assert.equal(priceStatus({ done: true, failed: true }, FROM, TO), "unavailable");
  assert.equal(priceStatus({ done: true, failed: true, data }, FROM, TO), "unavailable");
  assert.equal(priceStatus({ done: true, failed: false, data: { ...data, bars: [] } }, FROM, TO), "empty");
  assert.equal(priceStatus({ done: true, failed: false, data }, FROM, TO), "ready");
});
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `node --experimental-strip-types --test tests/price-line.test.mjs`
Expected: FAIL with `ERR_MODULE_NOT_FOUND` for `lib/price-line.ts`.

- [ ] **Step 3: Implement `priceLine` and `priceStatus` in `web/lib/price-line.ts`**

`low` and `high` come from the in-window bars only.

- [ ] **Step 4: Run the tests to verify they pass**

Run: `node --experimental-strip-types --test tests/price-line.test.mjs`
Expected: PASS, 7 tests, 0 failures.

- [ ] **Step 5: Commit**

```bash
git add web/lib/price-line.ts web/tests/price-line.test.mjs
git commit -m "feat(web): add price line geometry for the score chart (#132)"
```

### Task 5: Price overlay on the Score chart (UI)

Use the `impeccable` and `design-taste-frontend` skills against `web/DESIGN.md` for this task. Amber-on-black terminal, cyan for the price series, IBM Plex Mono `text-xs` for legend and labels, square corners, no Bloomberg name or logo, no green or red on the price line (those mean direction only).

**Files:**
- Modify: `web/components/live-chart.tsx`
- Modify: `web/components/live-view.tsx` (inside `ChartPanel` only, plus one new import line)
- Test: `web/tests/price-chart.test.mjs`

**Interfaces:**
- Consumes: `priceLine`, `priceStatus`, `PriceState` from Task 4; `PriceBars` from Task 3; `money` from `@/components/term-panel`.
- Produces: `LiveChart({ t, tall = false, price }: { t: Timeline; tall?: boolean; price?: PriceState })`. With `price` undefined the output is exactly today's (the landing page `live-market.tsx` keeps `<LiveChart t={timeline} />`).

Rendering when `price` is given (window = `Date.parse(t.from)`..`Date.parse(t.to)`):
- A legend row above the plot: a white `SCORE` key, then by `priceStatus`: `loading` → `PRICE --` in mute; `unavailable` → `Price unavailable` in yellow; `empty` → `No price bars in this window` in mute; `ready` → cyan `PRICE 5-MIN CLOSE`, followed by a `MOCK` tag (amber fill, black text, like the Tape MOCK tag) when `price.mode === "mock"`. The row wraps on narrow screens.
- When `ready`: one `<polyline>` per segment, drawn before the score polyline, `fill="none" stroke="var(--cyan)" strokeWidth="1.25" strokeLinecap="square" vectorEffect="non-scaling-stroke"`; cyan `money(high)` at the top-left (y 10%) and `money(low)` at the bottom-left (y 90%), absolutely positioned like the existing threshold labels.
- The SVG `aria-label` becomes `` `Score and price timeline for ${t.ticker}` `` when `price` is given.
- No line, axis label or text for price in any status other than `ready`.

- [ ] **Step 1: Write the failing test**

```js
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { test } from "node:test";

const read = (p) => readFileSync(resolve(import.meta.dirname, "..", p), "utf8");

test("the score chart draws price in cyan with honest empty, failure and mock states", () => {
  const chart = read("components/live-chart.tsx");
  for (const s of [/Price unavailable/, /No price bars in this window/, /PRICE 5-MIN CLOSE/, /MOCK/, /var\(--cyan\)/, /strokeLinecap="square"/, /priceLine\(/, /priceStatus\(/, /Score and price timeline for/]) assert.match(chart, s);
  assert.doesNotMatch(chart, /--up|--down|text-up|text-down/);
  assert.doesNotMatch(chart, /\b(buy|sell)\b/i);
});

test("the live view feeds bars into the chart and the landing chart is unchanged", () => {
  const view = read("components/live-view.tsx");
  assert.match(view, /useApi<PriceBars>\(`\/api\/v1\/tickers\/\$\{ticker\}\/bars`, 60000, nonce, 6, refreshKey\)/);
  assert.match(view, /<LiveChart t=\{data\} tall price=\{price\} \/>/);
  assert.match(read("components/live-market.tsx"), /<LiveChart t=\{timeline\} \/>/);
});
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `node --experimental-strip-types --test tests/price-chart.test.mjs`
Expected: FAIL on the first `assert.match` (`Price unavailable` not found) and on the `useApi<PriceBars>` match.

- [ ] **Step 3: Implement the overlay**

In `live-view.tsx`: add a new line `import type { PriceBars } from "@/lib/api";` (do not edit the existing `@/lib/api` import line), add `const price = useApi<PriceBars>(`/api/v1/tickers/${ticker}/bars`, 60000, nonce, 6, refreshKey);` right after the timeline `useApi` line in `ChartPanel`, and change `<LiveChart t={data} tall />` to `<LiveChart t={data} tall price={price} />`. Touch nothing else in `ChartPanel` (its header belongs to #130). In `live-chart.tsx`: implement the rendering above. Keep its existing comments.

- [ ] **Step 4: Run the test, then the full checks**

Run (from `web/`): `node --experimental-strip-types --test tests/price-chart.test.mjs`
Expected: PASS, 2 tests.
Run: `npm test` then `npm run lint` then `npm run build`
Expected: all tests pass, ESLint prints no errors, `next build` ends with the route table and exit code 0.

- [ ] **Step 5: Commit**

```bash
git add web/components/live-chart.tsx web/components/live-view.tsx web/tests/price-chart.test.mjs
git commit -m "feat(web): overlay price on the live score chart (#132)"
```

### Task 6: Verify in the running app with mock data

Use the `impeccable` and `design-taste-frontend` skills to judge the result against `web/DESIGN.md`. No code changes unless a check fails; a fix goes back through the owning task's test first.

**Files:** none.

- [ ] **Step 1: Start the app in mock mode**

Run (Git Bash, from `web/`, in the background): `DATA_MODE=mock PORT=3000 npm run dev`. If port 3000 is taken by a parallel branch, use `PORT=3132` and that port in every URL below.
Expected log line: `Pumpwatch ready on port 3000 (mock data)`.

- [ ] **Step 2: Check the chart with Playwright MCP**

1. `browser_navigate` to `http://127.0.0.1:3000/live`; `browser_wait_for` text `PRICE 5-MIN CLOSE`.
2. `browser_snapshot`: the Score chart panel shows `SCORE`, `PRICE 5-MIN CLOSE` and a `MOCK` tag beside it; the panel tag still says `MOCK`; the notice `Risk signal only. Not investment advice.` is visible.
3. `browser_evaluate`: `[...document.querySelectorAll('svg[aria-label^="Score and price timeline"] polyline[stroke="var(--cyan)"]')].length` is at least 2 (the mock gap splits the line) and one white score polyline exists.
4. `browser_network_requests`: a `GET /api/v1/tickers/GME/bars?from=...&to=...` returned 200; `browser_network_request` on it shows `X-Pumpwatch-Data-Mode: mock`, `feed: "mock"`, and no `APCA` text.
5. Type `AMC` in the `PW>` command line and press Enter; the chart redraws with a new cyan line and new high/low labels. Repeat for `SNDL` (labels show 2 decimals; values under $1 would show 4).
6. `browser_run_code_unsafe`: `await page.route('**/api/v1/tickers/*/bars*', (r) => r.fulfill({ status: 503, contentType: 'application/json', body: '{"notice":"n","mode":"mock","error":"Data is temporarily unavailable"}' }));` then reload; the chart shows `Price unavailable`, no cyan polyline, and the score line still draws. Then `await page.unroute('**/api/v1/tickers/*/bars*')`.
7. `browser_run_code_unsafe`: route the bars call to return a valid mock body with `bars: []`; the chart shows `No price bars in this window`. Unroute.
8. `browser_resize` to 375x812, reload: the legend wraps, labels do not overlap the threshold labels, and `document.documentElement.scrollWidth <= window.innerWidth` is true. Resize back to 1440x900.
9. `browser_console_messages`: no errors.
10. `browser_take_screenshot` of the Score chart panel for the PR description.

- [ ] **Step 3: Stop the dev server and confirm the tree is clean**

Run: `git status --short`
Expected: no output.
