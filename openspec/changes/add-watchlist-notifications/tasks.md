# Watchlist Flag Notifications Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Opt-in browser notifications for new streamed flags on the user's watchlist, at or above a minimum score (issue #133).

**Architecture:** A pure, node-testable `web/lib/flag-alerts.ts` owns preferences, the backlog/dedupe tracker, notification text, permission and the click handler. A thin `web/components/notify-toggle.tsx` adds the React hook and the toggle. `FlagFeed` in `web/components/live-view.tsx` gets a few lines of wiring; `web/lib/commands.ts` gets `NOTIFY ON|OFF`.

**Tech Stack:** Next.js 16 (webpack mode), React 19, TypeScript, Tailwind 4, `node --test` with `--experimental-strip-types`, Playwright MCP for the browser check.

**Spec:** `openspec/changes/add-watchlist-notifications/specs/web-live-view/spec.md`, with `design.md` and `proposal.md` beside it. Issue #133 in RLinV1/PumpWatch is the approved design.

## Global Constraints

- No code comments of any kind in new code: no inline, block, JSDoc or TODO lines. Keep existing comments untouched.
- Test first for every task: write the failing test, run it and see it fail, implement, see it pass, commit.
- All UI work uses the `impeccable` and `design-taste-frontend` skills against `web/DESIGN.md`: amber-on-black terminal, square corners, 1px rules, IBM Plex Mono, no shadows, gradients, rounding or emoji, and no Bloomberg name or logo.
- Accessibility: the toggle is a keyboard-operable `<button>` whose accessible name is exactly `Notify me` and whose state is `aria-pressed`. Visible 2px yellow focus ring. 44px touch targets below `lg`.
- No permission prompt on page load. `Notification.requestPermission()` is called only from the toggle click or the `NOTIFY ON` command.
- Notification titles start with `MOCK ` unless the data mode is exactly `live`. The body ends with `NOTICE` from `web/lib/api.ts`. No buy or sell wording anywhere.
- Preferences key: `pumpwatch:notify:v1`, value `{"enabled":boolean,"minScore":50|65|80}`. Default `{ enabled: false, minScore: 65 }`. Settle window `SETTLE_MS = 3000`.
- Do not reuse or change the `ALRT` alias; it keeps opening the Flags panel.
- Keep `web/components/live-view.tsx` edits to the lines named in Task 4. Other branches (#130, #131, #132) edit `FlagFeed` and `ChartPanel`.
- Lib imports use relative `.ts` paths (`./api.ts`, `./verdict.ts`), as in `web/lib/flag-context.ts`, so `node --test` can load them. Type-only imports use `import type`.
- Checks from `web/`: `npm test`, `npm run lint`, `npm run build`. Run `npm ci` first if `web/node_modules` is missing. Use `127.0.0.1`, never `localhost`, in scripts and URLs.
- Commits: Conventional Commits, no `Co-Authored-By` or AI attribution line. Do not push unless the controller says so.

## Review Focus

1. Stream reconnect replays flags raised during the gap: none notify. Pinned in Task 1 (`flags replayed right after the stream opens or reopens never notify`).
2. `localStorage` throws (private mode, blocked storage): turning on still works for the session and says so; reads never throw. Pinned in Task 2 (`storage failure keeps the choice for this session only`).
3. Permission revoked in browser settings after the user turned notifications on: nothing fires and the toggle shows OFF. Pinned in Task 1 (`permission` cases in `only streamed watchlist flags at or above the minimum score notify`) and Task 4 (effective state).
4. `new Notification()` throws (Android Chrome "Illegal constructor"): the stream handler must not crash. Pinned in Task 2 (`a notification click focuses the tab and selects the ticker; a throwing constructor is ignored`).
5. Data mode unknown because the REST read failed while the stream works: the title must still say MOCK. Pinned in Task 1 (`notificationFor(flag(42), undefined)`).

## Notification stub for Playwright

Tasks 4 and 5 use this with `mcp__playwright__browser_run_code_unsafe`. `answer` is what `requestPermission` returns; `supported: false` removes the API. Permission is kept in `localStorage` under `stub:permission` so it survives reloads.

```js
async (page) => {
  await page.addInitScript(({ answer, supported }) => {
    if (!supported) { Object.defineProperty(window, "Notification", { value: undefined, configurable: true }); return; }
    window.__asked = 0;
    window.__notes = [];
    class StubNotification {
      static permission = localStorage.getItem("stub:permission") ?? "default";
      static async requestPermission() { window.__asked++; localStorage.setItem("stub:permission", answer); StubNotification.permission = answer; return answer; }
      constructor(title, options) { this.title = title; this.options = options; this.closed = false; window.__notes.push(this); }
      close() { this.closed = true; }
    }
    Object.defineProperty(window, "Notification", { value: StubNotification, configurable: true, writable: true });
  }, { answer: "granted", supported: true });
  await page.goto("http://127.0.0.1:3000/live");
}
```

For a check in a fresh browser state, run the same body against `const ctx = await page.context().browser().newContext(); const p = await ctx.newPage();`, return the values, then `await ctx.close()`.

---

### Task 1: Preferences, backlog tracker and notification text

**Files:**
- Create: `web/lib/flag-alerts.ts`
- Test: `web/tests/flag-alerts.test.mjs`

**Interfaces:**
- Consumes: `type Flag`, `type DataMode`, `NOTICE` from `web/lib/api.ts`; `verdictOf(score).label` from `web/lib/verdict.ts`.
- Produces:
  - `export const NOTIFY_KEY = "pumpwatch:notify:v1"`
  - `export const MIN_SCORES = [50, 65, 80] as const`
  - `export const DEFAULT_MIN_SCORE = 65`
  - `export const SETTLE_MS = 3000`
  - `export type NotifyPrefs = { enabled: boolean; minScore: number }`
  - `export type NotifySupport = "unsupported" | "default" | "granted" | "denied"`
  - `export type AlertTracker = { seen: Set<number>; quietUntil: number }`
  - `export function parseNotifyPrefs(raw: string | null): NotifyPrefs`
  - `export function createAlertTracker(): AlertTracker`
  - `export function markOpen(tracker: AlertTracker, now: number): void`
  - `export function shouldNotify(tracker: AlertTracker, flag: Flag, context: { prefs: NotifyPrefs; permission: NotifySupport; watchlist: string[]; now: number }): boolean`
  - `export function notificationFor(flag: Flag, mode: DataMode | undefined): { title: string; options: { body: string; tag: string } }`

- [ ] **Step 0: Install dependencies if missing**

Run from `web/`: `npm ci` (only when `web/node_modules` does not exist).
Expected: exits 0.

- [ ] **Step 1: Write the failing tests**

```js
import assert from "node:assert/strict";
import { test } from "node:test";
import { DEFAULT_MIN_SCORE, NOTIFY_KEY, SETTLE_MS, createAlertTracker, markOpen, notificationFor, parseNotifyPrefs, shouldNotify } from "../lib/flag-alerts.ts";

const flag = (id, ticker = "GME", score = 74) => ({ id, ticker, raisedAt: "2026-01-01T00:00:00Z", evidence: { score, modelVersion: "mock-rules-v1", topContributors: [], postCount: 80, tradeCount: 200, catalyst: null } });
const on = { prefs: { enabled: true, minScore: 65 }, permission: "granted", watchlist: ["GME", "AMC"] };

test("saved notify preferences default to off at 65 and reject corrupted data", () => {
  assert.equal(NOTIFY_KEY, "pumpwatch:notify:v1");
  assert.equal(DEFAULT_MIN_SCORE, 65);
  assert.deepEqual(parseNotifyPrefs('{"enabled":true,"minScore":80}'), { enabled: true, minScore: 80 });
  for (const raw of [null, "{broken", "[]", '{"enabled":"yes","minScore":65}', '{"enabled":true,"minScore":64}', '{"enabled":true}']) {
    assert.deepEqual(parseNotifyPrefs(raw), { enabled: false, minScore: 65 }, String(raw));
  }
});

test("only streamed watchlist flags at or above the minimum score notify", () => {
  const t = createAlertTracker();
  markOpen(t, 0);
  const now = SETTLE_MS;
  assert.equal(shouldNotify(t, flag(1, "GME", 65), { ...on, now }), true);
  assert.equal(shouldNotify(t, flag(2, "GME", 64.9), { ...on, now }), false);
  assert.equal(shouldNotify(t, flag(3, "SNDL", 90), { ...on, now }), false);
  assert.equal(shouldNotify(t, flag(4), { ...on, prefs: { enabled: false, minScore: 65 }, now }), false);
  assert.equal(shouldNotify(t, flag(5, "AMC", 66), { ...on, prefs: { enabled: true, minScore: 80 }, now }), false);
  ["default", "denied", "unsupported"].forEach((permission, i) => assert.equal(shouldNotify(t, flag(10 + i), { ...on, permission, now }), false, permission));
});

test("each flag id notifies at most once, across reconnects", () => {
  const t = createAlertTracker();
  markOpen(t, 0);
  assert.equal(shouldNotify(t, flag(7), { ...on, now: SETTLE_MS }), true);
  assert.equal(shouldNotify(t, flag(7), { ...on, now: SETTLE_MS + 1 }), false);
  markOpen(t, 10000);
  assert.equal(shouldNotify(t, flag(7), { ...on, now: 10000 + SETTLE_MS }), false);
});

test("flags replayed right after the stream opens or reopens never notify", () => {
  const t = createAlertTracker();
  assert.equal(shouldNotify(t, flag(1), { ...on, now: 0 }), false);
  markOpen(t, 1000);
  assert.equal(shouldNotify(t, flag(2), { ...on, now: 1000 + SETTLE_MS - 1 }), false);
  assert.equal(shouldNotify(t, flag(2), { ...on, now: 1000 + SETTLE_MS }), false);
  assert.equal(shouldNotify(t, flag(3), { ...on, now: 1000 + SETTLE_MS }), true);
  markOpen(t, 20000);
  assert.equal(shouldNotify(t, flag(4), { ...on, now: 20500 }), false);
});

test("notification text is labelled, carries the notice and never advises", () => {
  const n = notificationFor(flag(42, "GME", 74.4), "mock");
  assert.equal(n.title, "MOCK GME flag: 74 Pump likely");
  assert.equal(n.options.tag, "pumpwatch-flag-42");
  assert.equal(n.options.body, "80 posts, 200 trades. Risk signal only. Not investment advice. Pumpwatch is a non-commercial project.");
  assert.equal(notificationFor(flag(42), "live").title, "GME flag: 74 Pump likely");
  assert.match(notificationFor(flag(42), undefined).title, /^MOCK /);
  assert.doesNotMatch(n.title + n.options.body, /\b(buy|sell)\b/i);
});
```

- [ ] **Step 2: Run the tests to verify they fail**

Run from `web/`: `node --experimental-strip-types --test tests/flag-alerts.test.mjs`
Expected: FAIL with `ERR_MODULE_NOT_FOUND` for `lib/flag-alerts.ts`.

- [ ] **Step 3: Implement the Task 1 interfaces in `web/lib/flag-alerts.ts`**

- `parseNotifyPrefs`: `JSON.parse` in `try`; valid only when `enabled` is a boolean and `minScore` is in `MIN_SCORES`; anything else returns `{ enabled: false, minScore: DEFAULT_MIN_SCORE }`.
- `createAlertTracker` starts with `quietUntil: Number.POSITIVE_INFINITY`, so nothing notifies before the first open. `markOpen` sets `quietUntil = now + SETTLE_MS`.
- `shouldNotify` records `flag.id` in `seen` on every call before deciding. It returns true only when the id was new, `now >= quietUntil`, `prefs.enabled`, `permission === "granted"`, `watchlist.includes(flag.ticker)` and `flag.evidence.score >= prefs.minScore` (raw score, not rounded).
- `notificationFor`: title `${mode === "live" ? "" : "MOCK "}${ticker} flag: ${Math.round(score)} ${verdictOf(score).label}`, body `${postCount} posts, ${tradeCount} trades. ${NOTICE}`, tag `pumpwatch-flag-${id}`.

- [ ] **Step 4: Run the tests to verify they pass**

Run from `web/`: `node --experimental-strip-types --test tests/flag-alerts.test.mjs`
Expected: `# pass 5`, `# fail 0`.

- [ ] **Step 5: Commit**

```bash
git add web/lib/flag-alerts.ts web/tests/flag-alerts.test.mjs
git commit -m "feat(web): add flag alert filter, dedupe and backlog skip (#133)"
```

### Task 2: Browser glue: storage, permission and click

**Files:**
- Modify: `web/lib/flag-alerts.ts`
- Test: `web/tests/flag-alerts.test.mjs`

**Interfaces:**
- Consumes: Task 1 exports.
- Produces:
  - `export type NotifyResult = "on" | "off" | "session" | "denied" | "unsupported"`
  - `export function readNotifyPrefs(): string | null`: `localStorage.getItem(NOTIFY_KEY)`; when that throws, the module-level session value (initially `null`). Never throws.
  - `export function saveNotifyPrefs(prefs: NotifyPrefs): boolean`: writes JSON to `localStorage` and returns true; when that throws, keeps the JSON as the session value and returns false. Dispatches `new Event(NOTIFY_KEY)` on `window` either way.
  - `export function subscribeNotifyPrefs(onChange: () => void): () => void`: listens to `storage` and `NOTIFY_KEY` on `window`, as `subscribeWatchlist` does in `web/lib/watchlist.ts`.
  - `export function notifySupport(): NotifySupport`: `"unsupported"` when `typeof Notification === "undefined"` or `!globalThis.isSecureContext`, else `Notification.permission`.
  - `export async function enableNotify(): Promise<NotifyResult>`: `"unsupported"` without asking when unsupported; calls `Notification.requestPermission()` only when support is `"default"`; any result other than `"granted"` returns `"denied"` and saves nothing; on granted saves `{ ...current, enabled: true }` and returns `"on"`, or `"session"` when the save returned false.
  - `export function disableNotify(): NotifyResult`: saves `{ ...current, enabled: false }`, returns `"off"`.
  - `export function notifyMessage(result: NotifyResult, minScore: number): string`, exact copy:
    - `on`: `Notifications on for watchlist flags scoring ${minScore} or more.`
    - `session`: `Notifications on for this session only. Allow browser storage to keep the setting.`
    - `off`: `Notifications off.`
    - `denied`: `Notifications are not allowed for this site. Allow them in your browser's site settings, then try again.`
    - `unsupported`: `This browser cannot show notifications here.`
  - `export function showFlagNotification(flag: Flag, mode: DataMode | undefined, onPick: (ticker: string) => void): void`: builds `new Notification(title, options)` from `notificationFor`; its `onclick` calls `window.focus()`, `onPick(flag.ticker)` and `close()`. A throwing constructor is caught and ignored.

- [ ] **Step 1: Append the failing tests to `web/tests/flag-alerts.test.mjs`**

Extend the import with `disableNotify, enableNotify, notifyMessage, notifySupport, readNotifyPrefs, showFlagNotification`. Keep the storage-failure test last: the session value is module state.

```js
function browser({ permission = "default", answer = "granted", secure = true, storageFails = false, throws = false } = {}) {
  const store = new Map();
  const calls = { asked: 0, focus: 0, shown: [] };
  const target = new EventTarget();
  globalThis.window = { addEventListener: target.addEventListener.bind(target), removeEventListener: target.removeEventListener.bind(target), dispatchEvent: target.dispatchEvent.bind(target), focus: () => { calls.focus++; } };
  globalThis.isSecureContext = secure;
  Object.defineProperty(globalThis, "localStorage", { configurable: true, get() { if (storageFails) throw new Error("denied"); return { getItem: (k) => store.get(k) ?? null, setItem: (k, v) => { store.set(k, v); } }; } });
  globalThis.Notification = class {
    static permission = permission;
    static async requestPermission() { calls.asked++; this.permission = answer; return answer; }
    constructor(title, options) { if (throws) throw new TypeError("Illegal constructor"); this.title = title; this.options = options; this.closed = false; calls.shown.push(this); }
    close() { this.closed = true; }
  };
  return { store, calls };
}

test("support is unsupported without the Notification API or a secure context", () => {
  browser({ permission: "granted" });
  assert.equal(notifySupport(), "granted");
  browser({ permission: "denied" });
  assert.equal(notifySupport(), "denied");
  browser({ secure: false });
  assert.equal(notifySupport(), "unsupported");
  browser();
  delete globalThis.Notification;
  assert.equal(notifySupport(), "unsupported");
});

test("turning notifications on asks for permission only once and saves the choice", async () => {
  const { store, calls } = browser();
  assert.equal(calls.asked, 0);
  assert.equal(await enableNotify(), "on");
  assert.equal(calls.asked, 1);
  assert.deepEqual(JSON.parse(store.get(NOTIFY_KEY)), { enabled: true, minScore: 65 });
  assert.equal(await enableNotify(), "on");
  assert.equal(calls.asked, 1);
  store.set(NOTIFY_KEY, '{"enabled":true,"minScore":80}');
  assert.equal(disableNotify(), "off");
  assert.deepEqual(JSON.parse(store.get(NOTIFY_KEY)), { enabled: false, minScore: 80 });
});

test("a denied or unsupported browser stays off with a clear message", async () => {
  const denied = browser({ answer: "denied" });
  assert.equal(await enableNotify(), "denied");
  assert.equal(denied.store.has(NOTIFY_KEY), false);
  const blocked = browser({ permission: "denied" });
  assert.equal(await enableNotify(), "denied");
  assert.equal(blocked.calls.asked, 0);
  const insecure = browser({ secure: false });
  assert.equal(await enableNotify(), "unsupported");
  assert.equal(insecure.calls.asked, 0);
  assert.equal(notifyMessage("denied", 65), "Notifications are not allowed for this site. Allow them in your browser's site settings, then try again.");
  assert.equal(notifyMessage("unsupported", 65), "This browser cannot show notifications here.");
  assert.equal(notifyMessage("on", 80), "Notifications on for watchlist flags scoring 80 or more.");
  assert.equal(notifyMessage("off", 65), "Notifications off.");
});

test("a notification click focuses the tab and selects the ticker; a throwing constructor is ignored", () => {
  const { calls } = browser({ permission: "granted" });
  const picked = [];
  showFlagNotification(flag(9), "mock", (t) => picked.push(t));
  assert.equal(calls.shown.length, 1);
  assert.equal(calls.shown[0].title, "MOCK GME flag: 74 Pump likely");
  assert.equal(calls.shown[0].options.tag, "pumpwatch-flag-9");
  calls.shown[0].onclick();
  assert.equal(calls.focus, 1);
  assert.deepEqual(picked, ["GME"]);
  assert.equal(calls.shown[0].closed, true);
  browser({ permission: "granted", throws: true });
  assert.doesNotThrow(() => showFlagNotification(flag(10), "mock", () => {}));
});

test("storage failure keeps the choice for this session only", async () => {
  browser({ storageFails: true });
  assert.equal(readNotifyPrefs(), null);
  assert.equal(await enableNotify(), "session");
  assert.deepEqual(parseNotifyPrefs(readNotifyPrefs()), { enabled: true, minScore: 65 });
  assert.equal(notifyMessage("session", 65), "Notifications on for this session only. Allow browser storage to keep the setting.");
  assert.equal(disableNotify(), "off");
  assert.equal(parseNotifyPrefs(readNotifyPrefs()).enabled, false);
});
```

- [ ] **Step 2: Run the tests to verify they fail**

Run from `web/`: `node --experimental-strip-types --test tests/flag-alerts.test.mjs`
Expected: FAIL with a `SyntaxError` naming a missing export such as `enableNotify`.

- [ ] **Step 3: Implement the Task 2 interfaces in `web/lib/flag-alerts.ts`**

- [ ] **Step 4: Run the tests to verify they pass**

Run from `web/`: `node --experimental-strip-types --test tests/flag-alerts.test.mjs`
Expected: `# pass 10`, `# fail 0`.

- [ ] **Step 5: Commit**

```bash
git add web/lib/flag-alerts.ts web/tests/flag-alerts.test.mjs
git commit -m "feat(web): add notification permission, storage and click handling (#133)"
```

### Task 3: `NOTIFY ON|OFF` command

**Files:**
- Modify: `web/lib/commands.ts` (the `Command` union and `parseCommand`)
- Test: `web/tests/commands.test.mjs`

**Interfaces:**
- Produces: `Command` gains `{ type: "notify"; on: boolean }`. `NOTIFY` with anything other than exactly `ON` or `OFF` (including nothing) returns `{ type: "error", message: "USAGE: NOTIFY ON OR NOTIFY OFF." }`. Parsed on the uppercased, `<GO>`-stripped `text`, before the `ADD|RM|SEL|LOOK|DES` verb check.

- [ ] **Step 1: Append the failing test**

```js
test("NOTIFY ON and NOTIFY OFF switch flag notifications; ALRT still opens the flags panel", () => {
  for (const input of ["NOTIFY ON", "notify on", "NOTIFY ON <GO>", "notify on go"]) assert.deepEqual(parseCommand(input), { type: "notify", on: true }, input);
  assert.deepEqual(parseCommand("notify off"), { type: "notify", on: false });
  for (const input of ["NOTIFY", "NOTIFY MAYBE", "NOTIFY ON OFF"]) assert.equal(parseCommand(input).message, "USAGE: NOTIFY ON OR NOTIFY OFF.", input);
  assert.deepEqual(parseCommand("ALRT"), { type: "panel", panel: "flags" });
});
```

- [ ] **Step 2: Run it to verify it fails**

Run from `web/`: `node --experimental-strip-types --test tests/commands.test.mjs`
Expected: FAIL in the new test; `parseCommand("NOTIFY ON")` returns an `UNKNOWN COMMAND` error.

- [ ] **Step 3: Add the `notify` command to `web/lib/commands.ts`**

- [ ] **Step 4: Run it to verify it passes**

Run from `web/`: `node --experimental-strip-types --test tests/commands.test.mjs`
Expected: `# fail 0`, all existing command tests still pass.

- [ ] **Step 5: Commit**

```bash
git add web/lib/commands.ts web/tests/commands.test.mjs
git commit -m "feat(web): add NOTIFY ON and NOTIFY OFF commands (#133)"
```

### Task 4: Toggle, FlagFeed wiring, command case and README

**Files:**
- Create: `web/components/notify-toggle.tsx`
- Modify: `web/components/live-view.tsx` (imports, `HELP`, `FlagFeed` props and stream effect, the `<FlagFeed>` element, the `run` switch)
- Modify: `web/README.md` (one paragraph after the `/live` terminal paragraph)

**Interfaces:**
- Consumes: every Task 1 and Task 2 export; `{ type: "notify"; on: boolean }` from Task 3.
- Produces, in `web/components/notify-toggle.tsx` (`"use client"`):
  - `export type FlagAlerts = { open: () => void; flag: (f: Flag) => void }`
  - `export function useFlagAlerts(symbols: string[], mode: DataMode | undefined, onPick: (ticker: string) => void): FlagAlerts`: tracker from `useState(createAlertTracker)`; latest `{ symbols, mode, onPick }` kept in a ref written inside `useEffect`; the returned object is memoised on the tracker only, so its identity never changes (it joins the `EventSource` effect deps, and a new identity would reconnect the stream). `open` calls `markOpen(tracker, Date.now())`. `flag` calls `shouldNotify` with `parseNotifyPrefs(readNotifyPrefs())`, `notifySupport()`, the latest `symbols` and `Date.now()`, then `showFlagNotification(f, mode, onPick)` when true.
  - `export function NotifyToggle()`, no props: `raw = useSyncExternalStore(subscribeNotifyPrefs, readNotifyPrefs, () => null)`, `support = useSyncExternalStore(subscribeNotifyPrefs, notifySupport, () => "unsupported")`, `on = prefs.enabled && support === "granted"`. Returns `null` when `support === "unsupported"`. Clicking when on shows `notifyMessage(disableNotify(), minScore)`; when off awaits `enableNotify()` and shows its message. A `<label>`led `Min score` `<select>` with `MIN_SCORES` calls `saveNotifyPrefs({ ...prefs, minScore })`. The message is a `<p role="status">`.

- [ ] **Step 1: Load the design skills**

Invoke `impeccable` and `design-taste-frontend` with the Skill tool and read `web/DESIGN.md`. The toggle row sits directly under the Flags title bar: `border-b border-line px-2 py-1 text-xs`, same density as the Watchlist add form. ON is amber reverse video (selection style), OFF is a 1px amber outline. Visible `ON`/`OFF` text inside the button is `aria-hidden`, so the accessible name stays `Notify me`. Label `Min score` in amber above or beside a 1px `border-line-strong` select on black. Square corners, no shadow, `min-h-11` below `lg` and `lg:min-h-7`, 2px yellow focus ring, status text in `text-mute` (`text-yellow` for `denied`).

- [ ] **Step 2: Write the failing browser check**

Run from `web/` in the background: `npm run dev`. Wait until `http://127.0.0.1:3000/live` responds. Run the Notification stub with `answer: "granted"`, then `mcp__playwright__browser_snapshot`.
Expected: FAIL, the Flags panel has no `button "Notify me"`.

- [ ] **Step 3: Create `web/components/notify-toggle.tsx`**

- [ ] **Step 4: Wire it into `web/components/live-view.tsx`**

Only these edits:
- Imports: `NotifyToggle, useFlagAlerts` from `@/components/notify-toggle`; `disableNotify, enableNotify, notifyMessage, parseNotifyPrefs, readNotifyPrefs` from `@/lib/flag-alerts`.
- `HELP`: add `["NOTIFY ON  /  NOTIFY OFF", "Browser notifications for new flags on your watchlist at or above the minimum score (65 by default). Asks for permission only when you turn it on."]` after the `ADD AMC  /  RM AMC` row.
- `FlagFeed` props: add `symbols: string[]`. After the `useApi` line: `const alerts = useFlagAlerts(symbols, mode, onPick);`.
- Stream effect: `alerts.open()` first in `es.onopen`; `alerts.flag(f)` after `if (!f) return;`; add `alerts` to the deps.
- `FlagFeed` body: `<NotifyToggle />` as the first child of `<Panel>`, before the existing conditional (wrap both in a fragment).
- `<FlagFeed ...>` in `LiveView`: add `symbols={symbols}`.
- `run` switch: `case "notify":` runs `cmd.on ? enableNotify() : Promise.resolve(disableNotify())`, then sets `out` to `{ kind: result === "denied" || result === "unsupported" ? "error" : "info", text: notifyMessage(result, parseNotifyPrefs(readNotifyPrefs()).minScore).toUpperCase() }`.

- [ ] **Step 5: Add the README paragraph to `web/README.md`**

> **Notify me** in the Flags panel turns on browser notifications for new flags on your watchlist at or above a minimum score (65 by default; 50 and 80 are also offered). The browser asks for permission only when you turn it on. Flags replayed when the stream connects or reconnects never notify, each flag notifies once, and mock flags are titled MOCK. Clicking a notification focuses the tab and selects the ticker. `NOTIFY ON` and `NOTIFY OFF` do the same from the command line. The setting is saved in this browser under `pumpwatch:notify:v1`. The toggle is hidden where the browser has no notification support or the page is not a secure context (HTTPS, 127.0.0.1 or localhost).

- [ ] **Step 6: Run the browser check to verify it passes**

Reload with the stub, then `mcp__playwright__browser_snapshot` and `mcp__playwright__browser_evaluate` with `() => ({ asked: window.__asked, pref: localStorage.getItem("pumpwatch:notify:v1") })`.
Expected: `button "Notify me"` not pressed, `combobox "Min score"` showing 65, and `{ asked: 0, pref: null }`.

- [ ] **Step 7: Run all checks**

Run from `web/`: `npm test`, then `npm run lint`, then `npm run build`.
Expected: `# fail 0`; eslint prints no errors or warnings; `next build` ends with the route table and no type errors.

- [ ] **Step 8: Commit**

```bash
git add web/components/notify-toggle.tsx web/components/live-view.tsx web/README.md
git commit -m "feat(web): add opt-in watchlist flag notifications to the Flags panel (#133)"
```

### Task 5: Verify in the running app with mock data

**Files:** none unless a check fails. A failure is fixed test-first in the task that owns the code, then this task is rerun.

No `.env` means `DATA_MODE=mock`. The mock stream sends GME (74) and AMC (66) with new ids every 30 s, and both are on the default watchlist. Keep `npm run dev` running from `web/`; all URLs use `http://127.0.0.1:3000/live`. Load the design skills again before judging the screenshots.

- [ ] **Step 1: No prompt on load.** Stub with `answer: "granted"`, then evaluate `() => window.__asked`. Expected: `0`.
- [ ] **Step 2: Keyboard toggle.** Evaluate `() => document.querySelector("#flags button[aria-pressed]").focus()`, then `mcp__playwright__browser_press_key` with `Space`. Snapshot. Expected: `button "Notify me" [pressed]`, status text `Notifications on for watchlist flags scoring 65 or more.`, `__asked` is `1`, and `localStorage["pumpwatch:notify:v1"]` is `{"enabled":true,"minScore":65}`.
- [ ] **Step 3: Backlog on load.** Reload the page (the stub keeps `granted`, the preference stays on), wait 5 s, evaluate `() => window.__notes.length`. Expected: `0`, although the stream already delivered two flags.
- [ ] **Step 4: Live notification.** In `browser_run_code_unsafe`, `await page.waitForFunction(() => window.__notes.some((n) => n.title.includes("AMC")), null, { timeout: 50000 })`, then return `window.__notes.map((n) => ({ title: n.title, tag: n.options.tag, body: n.options.body }))`. Expected: titles are `MOCK GME flag: 74 Pump likely` or `MOCK AMC flag: 66 Pump likely`, tags match `/^pumpwatch-flag-\d+$/` and are unique, each body ends with `Risk signal only. Not investment advice. Pumpwatch is a non-commercial project.`, and no text matches `/\b(buy|sell)\b/i`.
- [ ] **Step 5: Click.** Evaluate `() => { const n = window.__notes.find((x) => x.title.includes("AMC")); n.onclick(); return n.closed; }`, then snapshot. Expected: `true`, the AMC watchlist row is `[pressed]`, and the chart panel title reads `AMC score, last 6 hours`.
- [ ] **Step 6: Reconnect.** In `browser_run_code_unsafe`: record `__notes.length`, `await page.context().setOffline(true)`, wait 35 s, `setOffline(false)`, wait 2.5 s, return the new length and whether all tags are unique. Expected: the length is unchanged and tags are unique. If the footer `LINK` never left `STREAMING` (the stream was not dropped), record "reconnect not exercised in the browser" and rely on the Task 1 tests.
- [ ] **Step 7: Minimum score.** `mcp__playwright__browser_select_option` on `Min score` with `80`, wait 35 s. Expected: no new entries in `__notes`, and storage holds `"minScore":80`.
- [ ] **Step 8: Commands.** Type `NOTIFY OFF` and press Enter in the `Command line` combobox. Expected: `OK NOTIFICATIONS OFF.` and the toggle not pressed. Then `NOTIFY ON`. Expected: `OK NOTIFICATIONS ON FOR WATCHLIST FLAGS SCORING 80 OR MORE.` Then `NOTIFY`. Expected: `ERR: USAGE: NOTIFY ON OR NOTIFY OFF.` Then `ALRT`. Expected: `OK PANEL 3: FLAGS`.
- [ ] **Step 9: Removed ticker.** Set `Min score` to 65, run `RM AMC`, wait 35 s. Expected: no new AMC entries; GME entries still appear.
- [ ] **Step 10: Denied, in a fresh context.** Stub with `answer: "denied"` in a new context, click `Notify me`, return its `aria-pressed` and the status text. Expected: `"false"` and `Notifications are not allowed for this site. Allow them in your browser's site settings, then try again.`
- [ ] **Step 11: Unsupported, in a fresh context.** Stub with `supported: false`, count `getByRole("button", { name: "Notify me" })`. Expected: `0`, and the flag list still renders.
- [ ] **Step 12: Look.** `mcp__playwright__browser_take_screenshot` of the Flags panel at 1440x900 and at 375x812 (`mcp__playwright__browser_resize`). Expected per `web/DESIGN.md`: amber on black, square corners, no shadow, mono uppercase toggle, 44px tall toggle and select at 375 px, the yellow focus ring visible on the toggle, no horizontal scroll, no Bloomberg name or logo.
- [ ] **Step 13: Final checks.** Stop the dev server. Run from `web/`: `npm test`, `npm run lint`, `npm run build`. Expected: all pass. Report each step's result, including any step recorded as not exercised.
