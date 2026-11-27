# Proposal

## Why

A flag's evidence lists only feature-group deltas (social, market, catalyst) and a count of catalyst items. A user cannot see which signal crossed which threshold, or what an 8-K item code like 2.02 means. GitHub issue #131 is the approved design.

## What Changes

- `Scorer` gains `rules(Features)`, empty by default. `RuleScorer` returns the row that counted for each term that fired: name, feature, measured value, threshold and points. Their points add up to the unclamped score.
- `Flag.Evidence` gains an optional `rules` list (`Flag.Rule`). `FlagStage` fills it from the scorer, so `rules-v1` and `anomaly-v1` flags carry rules and ONNX flags carry an empty list. `Validator` checks each rule. `SchemaVersions.FLAG` stays 1.
- The web client accepts an optional `rules` array and rejects entries with a missing name or feature, or non-finite numbers. Raw-content keys stay rejected at any depth.
- New `web/lib/sec-items.ts` maps 8-K item codes to their SEC names. Unknown codes show the raw code.
- The terminal Flags panel shows a collapsed "Why this flag" list per flag. The landing evidence panel shows the list open. Both show the SEC names of 8-K items when a catalyst was found.
- The assistant context for "Ask about this flag" lists the fired rules.
- Mock flags carry labelled mock rules from the real rule table. Their points add up to the mock flag score.
- Aggregate values only: no post text or authors.
- Out of scope: rules in webhook alerts, the research `tools.py` payload (conflicts with issue #121), a schema bump.

## Capabilities

### New Capabilities
<!-- None. -->

### Modified Capabilities
- `pump-risk-scoring`: flag evidence carries the fired rules of the transparent scorers.
- `web-live-view`: the terminal and landing show fired rules and 8-K item names.

## Impact

- Java: `common` (`Flag`, `Validator`), `pipeline` (`Scorer`, `RuleScorer`, `FlagStage`), tests in `common`, `pipeline` and `api`. `docs/ml/rule-scorer.md` gains one paragraph.
- Web: `lib/api.ts`, `lib/flag-context.ts`, `mock-api.mjs`, `components/live-view.tsx` (FlagFeed only), `components/live-market.tsx`, new `lib/sec-items.ts`, `lib/fired-rules.ts`, `components/flag-rules.tsx`.
- Data: old flags (Kafka, recordings, `api_flags.evidence`) parse and validate unchanged, with `rules` empty.
