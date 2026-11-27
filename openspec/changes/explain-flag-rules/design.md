# Design

## Context

`RuleScorer` sums the points of the highest matching row per term, then clamps to 0..100. `Occlusion` turns that into group deltas, which is all `Flag.Evidence` carries today. `FlagStage` builds the evidence from the scorer and the window's features. Flags flow to Kafka (`flags`), the replay recorder (`recorded_events`), the API store (`api_flags.evidence`, JSON from `Json.toJson`), the SSE stream and the web client.

## Open question from #131: does `rules` need a schema bump?

No. Findings, from the code on `origin/main` (f54db90):

1. `Validator.validate(Flag)` checks named fields of the record (`modelVersion`, `score`, contributor deltas, catalyst lookback). It never sees JSON, so it cannot reject an unknown field. Its only version rule is `schemaVersion == SchemaVersions.FLAG` (strict equality).
2. Every reader uses `Json.newMapper()`, which disables `FAIL_ON_UNKNOWN_PROPERTIES`. A reader built before this change ignores `rules`. Spring's own mapper in `api` also ignores unknown properties by default.
3. A missing `rules` property arrives as `null` in the record constructor (Jackson does not fail on missing creator properties by default). The compact constructor maps `null` to `List.of()`, the same way `topContributors` and `items` already work.
4. Bumping `FLAG` to 2 is what would break old data: `Ingest` validates every flag and would drop all stored and recorded v1 flags as "schemaVersion must be 2 but was 1".

Decision: keep `SchemaVersions.FLAG = 1` and add `rules` as an optional, additive field. `CommonTest` pins this with a literal v1 flag JSON string from before the change.

Replay note: the replayer skips derived topics (`flags` included) and regenerates them, so regenerated flags carry `rules` while the recorded audit copies do not. Nothing compares the two today.

## Decisions

**Where the rules come from.** Approaches considered:
1. `Scorer.rules(Features)` as a default method returning `List.of()`, overridden by `RuleScorer` (chosen). ONNX gets the empty list without code, the ONNX fallback to `RuleScorer` gets rules, and `FlagStage` does not branch on model version.
2. `FlagStage` checks `modelVersion` and casts to `RuleScorer`. It couples the stage to one scorer and breaks the existing test stub pattern.
3. Recompute rules in the web client from feature values. The client never sees feature values, and the table would live in two languages.

**One fired row per term.** `RuleScorer` keeps a `LinkedHashMap<String, Term>` of the last matching row per term name, in table order. `score` and `rules` both read it, so the sum of rule points is the unclamped score by construction.

**Record shape.** `Flag.Rule(String name, String feature, double value, double threshold, double points)`. `threshold` is the JSON name the web reads; it is `Term.atLeast`. No `group` field: the term name already reads as plain words, and the group deltas stay in `topContributors`.

**Source compatibility.** `Flag.Evidence` keeps a six-argument constructor that passes `List.of()`, so the many existing tests and any branch built in parallel still compile. Jackson 2.18+ uses the canonical record constructor; the round-trip test proves it.

**Mock data.** Rule points come in multiples of 5, so the current mock flag scores (74, 66) cannot be rule sums. Mock flag evidence scores become 75 (GME) and 65 (AMC), the sums of their mock rules. Watchlist and timeline scores stay 74, 66, 32: the watchlist shows the latest score and a flag shows the score when it was raised. The research eval fixture mirrors the watchlist numbers and stays unchanged. Mock group deltas change to match the mock rules. AMC carries a found catalyst with 8-K items 2.02 and 5.02, so the item names (one of them long) show in mock mode.

**Web display.** One component, `FlagRules`, renders the rules and the 8-K items. The Flags panel wraps it in a native `<details>` (collapsed, since the feed holds up to 70 flags). The landing panel shows it open. No total row: a clamped score (rules summing to 140, score 100) would make a total look wrong.

**8-K names.** A static table of the 33 current Form 8-K items with their SEC titles, looked up with `Object.hasOwn`. Plain hyphens replace the SEC's en dash, since DESIGN.md bans dashes of that kind in copy.

## Risks

- Parallel branches (#130, #132, #133) edit `live-view.tsx`, `mock-api.mjs`, `lib/api.ts` and `lib/flag-context.ts`. This change is built last and keeps edits in those files to a few local lines; the new code sits in new files.
- Long 8-K names (5.02 is about 170 characters) on a 375px phone. The component wraps them; the final check measures horizontal scroll.
- The mock flag score (75) and the watchlist score (74) differ by one point on screen. This is honest (different times) but may read as a bug.
