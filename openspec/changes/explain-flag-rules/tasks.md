# Explain Flag Rules Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Show why a flag fired: each rule that fired with its measured value, threshold and points, plus the SEC names of 8-K items (issue #131).

**Architecture:** `Scorer.rules(Features)` defaults to an empty list; `RuleScorer` returns the row that counted per fired term. `FlagStage` stores them in an optional `Flag.Evidence.rules`, which old JSON parses as empty, so `SchemaVersions.FLAG` stays 1. The web client validates the list, the mock API serves labelled mock rules, and one new component renders rules and 8-K names in the Flags panel and the landing evidence panel.

**Tech Stack:** Java 21, Jackson records, Kafka Streams `TopologyTestDriver`, JUnit 5 + AssertJ, Spring `MockMvc`; Next.js 16 / React 19, `node --test` with `--experimental-strip-types`, Playwright MCP.

**Spec:** `openspec/changes/explain-flag-rules/proposal.md`, `design.md`, `specs/pump-risk-scoring/spec.md`, `specs/web-live-view/spec.md`, and GitHub issue #131 (`gh issue view 131`).

## Global Constraints

- No code comments of any kind in new code: no inline, block, Javadoc, TODO or "shortcut:" notes. Keep existing comments.
- Test first in every task: write the failing test, run it and see it fail, implement, run it and see it pass, commit.
- UI work MUST use the `impeccable` and `design-taste-frontend` skills against `web/DESIGN.md`: amber-on-black terminal, square corners, IBM Plex Mono data, amber labels, white values, no shadows, gradients, rounded corners, emojis or em dashes. Green and red mean price direction only, so rule points are never `text-up` or `text-down`. No Bloomberg name or logo.
- Mock mode shows labelled mock data (`mock-rules-v1`, a MOCK label). Never give buy or sell advice. No raw post text or authors anywhere in evidence.
- `SchemaVersions.FLAG` stays `1`. Do not bump it.
- Do not change the research service. `research/src/portfolio_risk/tools.py` is out of scope (conflicts with issue #121).
- Shared files are also edited by parallel branches (`web/components/live-view.tsx` ChartPanel: #130, #132; FlagFeed: #130, #133; `web/mock-api.mjs` and `web/lib/api.ts`: #132; `web/lib/flag-context.ts`: #130). This feature is built last: rebase on `origin/main` first, keep edits to those files small and local, and put new code in new files.
- Java from the repo root. During TDD run one module and named tests with `-pl ... -am -Dtest=... -Dsurefire.failIfNoSpecifiedTests=false`. The full `mvn -B verify` runs once in Task 6 (pipeline takes about 13 minutes). Do not override `java.io.tmpdir` (RocksDB fails on long Windows paths).
- Web from `web/`: `npm test`, `npm run lint`, `npm run build`. Use `127.0.0.1`, not `localhost`, in scripts and URLs.
- Commits: Conventional Commits, no `Co-Authored-By` or AI attribution line. Commit and push the feature branch `feat/flag-rule-evidence`; never push to `main`.

## Review Focus

- An 8-K code that is an `Object` prototype key (`toString`, `constructor`) must show the raw code, not a function. Pinned in Task 3 (`sec-items.test.mjs`).
- Item 5.02's name is about 170 characters; on a 375px phone it must wrap with no horizontal page scroll. Mock AMC carries 5.02 (Task 4) and Task 6 measures `scrollWidth` at 375px.
- A clamped score (rules sum 140, score 100; or rules sum -50, score 0) must not look like an error: no total row in the UI, and the pipeline test pins both sums (Task 2).
- A flag stored before this change, or from the ONNX model, has no `rules` (absent in older API bodies, empty from new Java). The UI must show "No rule detail for this flag." and not throw on `undefined`. Pinned in Task 3 (absent `rules` still parses) and Task 5 (component uses `evidence.rules ?? []`, Playwright check against a flag without rules).
- The Flags panel holds up to 70 flags (20 fetched plus 50 streamed); rule lists must stay collapsed by default so the feed stays scannable. Pinned in Task 5 (`details` not `open`).

---

### Task 1: Flag evidence carries optional rules

**Files:**
- Modify: `common/src/main/java/dev/pumpwatch/common/Flag.java`
- Modify: `common/src/main/java/dev/pumpwatch/common/Validator.java` (`validate(Flag)`)
- Test: `common/src/test/java/dev/pumpwatch/common/CommonTest.java`
- Test: `api/src/test/java/dev/pumpwatch/api/ApiControllerTest.java`
- Test: `api/src/test/java/dev/pumpwatch/api/NoRawContentTest.java`

**Interfaces:**
- Produces: `public record Flag.Rule(String name, String feature, double value, double threshold, double points) {}`
- Produces: `Flag.Evidence(double score, String modelVersion, List<Contributor> topContributors, long postCount, long tradeCount, CatalystCheck catalyst, List<Rule> rules)`; the compact constructor maps a `null` `rules` to `List.of()` and copies otherwise (same as `topContributors`).
- Produces: a second constructor `Flag.Evidence(double score, String modelVersion, List<Contributor> topContributors, long postCount, long tradeCount, CatalystCheck catalyst)` that delegates with `List.of()`. Existing callers stay unchanged.
- Produces: `Validator` messages `evidence.rules[<i>].name is required`, `evidence.rules[<i>].feature is required`, `evidence.rules[<i>].value must be a finite number`, `evidence.rules[<i>].threshold must be a finite number`, `evidence.rules[<i>].points must be a finite number`, in that order per rule, after the existing contributor checks.

- [ ] **Step 1: Write the failing tests in `CommonTest`**

Add `import dev.pumpwatch.common.Flag.Rule;`, a fixture, and three changes:

```java
static Flag flagWithRules() {
    var ev = new Evidence(55, "rules-v1", List.of(new Contributor("social", 40)), 120, 3400,
            new CatalystCheck(false, T.minusSeconds(3600), T, List.of()),
            List.of(new Rule("mention surge", "mentionRatio", 12.4, 10, 35), new Rule("8-K filing", "filings24h", 1, 1, -20)));
    return new Flag(1, T, T, "GME", T, ev);
}

static final String V1_FLAG_JSON = "{\"schemaVersion\":1,\"eventTime\":\"2025-01-02T14:30:00Z\",\"ingestTime\":\"2025-01-02T14:30:00Z\","
        + "\"ticker\":\"GME\",\"raisedAt\":\"2025-01-02T14:30:00Z\",\"evidence\":{\"score\":0.93,\"modelVersion\":\"rule-1\","
        + "\"topContributors\":[{\"featureGroup\":\"social\",\"delta\":0.4}],\"postCount\":120,\"tradeCount\":3400,"
        + "\"catalyst\":{\"found\":false,\"lookbackStart\":\"2025-01-02T13:30:00Z\",\"lookbackEnd\":\"2025-01-02T14:30:00Z\",\"items\":[]}}}";

@Test
void flagJsonFromBeforeRulesStillParsesAndValidates() {
    Flag old = Json.fromJson(V1_FLAG_JSON, Flag.class);
    assertThat(old).isEqualTo(flag());
    assertThat(old.evidence().rules()).isEmpty();
    assertThat(Validator.validate(old)).isEmpty();
    assertThat(SchemaVersions.FLAG).isEqualTo(1);
    String nullRules = V1_FLAG_JSON.replace("\"items\":[]}", "\"items\":[]},\"rules\":null");
    assertThat(Json.fromJson(nullRules, Flag.class).evidence().rules()).isEmpty();
}

@Test
void rejectsRulesWithMissingNamesOrNonFiniteNumbers() {
    var ev = new Evidence(60, "rules-v1", List.of(), 1, 1, new CatalystCheck(false, T, T, List.of()),
            List.of(new Rule("mention surge", "mentionRatio", 12.4, 10, 35),
                    new Rule(" ", null, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)));
    assertThat(Validator.validate(new Flag(1, T, T, "GME", T, ev))).containsExactly(
            "evidence.rules[1].name is required", "evidence.rules[1].feature is required",
            "evidence.rules[1].value must be a finite number", "evidence.rules[1].threshold must be a finite number",
            "evidence.rules[1].points must be a finite number");
}
```

Also add `flagWithRules()` to the `all` array in `jsonRoundTripsEveryRecordType`, and `assertThat(Validator.validate(flagWithRules())).isEmpty();` to `validRecordsHaveNoProblems`.

- [ ] **Step 2: Write the failing API tests**

In `ApiControllerTest` add:

```java
@Test
void flagsCarryFiredRulesAndOlderFlagsAnEmptyList() throws Exception {
    var withRules = new Flag.Evidence(72.5, "rules-v1", List.of(), 40, 12,
            new Flag.CatalystCheck(false, T.minusSeconds(3600), T, List.of()),
            List.of(new Flag.Rule("mention surge", "mentionRatio", 12.4, 10, 35)));
    when(repo.recentFlags("live", 20)).thenReturn(List.of(new FlagView(8, "AMC", T, withRules), flag()));
    mvc.perform(get("/api/v1/flags"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data[0].evidence.rules[0].name").value("mention surge"))
            .andExpect(jsonPath("$.data[0].evidence.rules[0].feature").value("mentionRatio"))
            .andExpect(jsonPath("$.data[0].evidence.rules[0].value").value(12.4))
            .andExpect(jsonPath("$.data[0].evidence.rules[0].threshold").value(10.0))
            .andExpect(jsonPath("$.data[0].evidence.rules[0].points").value(35.0))
            .andExpect(jsonPath("$.data[1].evidence.rules").isEmpty());
}
```

In `NoRawContentTest`:
- `noPublicResponseTypeHasATextOrAuthorField`: change the `seen` assertion to `contains(Flag.Evidence.class, Flag.Rule.class, Views.ScorePoint.class, Views.FlagMarker.class)`.
- `polluteInputNeverReachesWatchlistTimelineFlagsOrSseEvents`: build `evidence` with the seven-argument constructor and `List.of(new Flag.Rule("mention surge", "mentionRatio", 12.4, 10, 35))`; after `pollute(...)` also apply `.replace("\"name\":\"mention surge\"", "\"text\":\"" + POST_TEXT + "\",\"authorId\":\"" + AUTHOR + "\",\"name\":\"mention surge\"")` to `flagJson`; after the captor, assert `assertThat(f.getValue().evidence().rules()).containsExactly(new Flag.Rule("mention surge", "mentionRatio", 12.4, 10, 35));`. The existing `doesNotContain` loop stays.

- [ ] **Step 3: Run the tests to verify they fail**

Run (Git Bash, repo root): `mvn -B -pl common,api -am test -Dtest='CommonTest,ApiControllerTest,NoRawContentTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: BUILD FAILURE, test compilation error `cannot find symbol` for `Flag.Rule` / `Rule`.

- [ ] **Step 4: Implement `Flag.Rule`, `Evidence.rules` and the `Validator` checks**

`Flag.java`: add the `rules` component, the null-to-empty mapping, the six-argument constructor and the `Rule` record per the Interfaces block. `Validator.validate(Flag)`: loop `e.rules()` by index with the existing `text` and `finite` helpers, using the field prefix `"evidence.rules[" + i + "]."`. If the round-trip test fails because Jackson picks the six-argument constructor, annotate the canonical (compact) constructor with `@com.fasterxml.jackson.annotation.JsonCreator`.

- [ ] **Step 5: Run the tests to verify they pass**

Run: `mvn -B -pl common,api -am test -Dtest='CommonTest,ApiControllerTest,NoRawContentTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: BUILD SUCCESS, `Tests run: ..., Failures: 0, Errors: 0` for each of the three classes.

- [ ] **Step 6: Commit**

```bash
git add common/src api/src/test
git commit -m "feat(common): carry optional fired rules in flag evidence (#131)"
```

### Task 2: RuleScorer exposes fired rules and FlagStage stores them

**Files:**
- Modify: `pipeline/src/main/java/dev/pumpwatch/pipeline/scorer/Scorer.java`
- Modify: `pipeline/src/main/java/dev/pumpwatch/pipeline/scorer/RuleScorer.java` (`points` and new `rules`)
- Modify: `pipeline/src/main/java/dev/pumpwatch/pipeline/flagger/FlagStage.java` (`Dedup.process`, the `new Flag.Evidence(...)` call)
- Modify: `docs/ml/rule-scorer.md`
- Test: `pipeline/src/test/java/dev/pumpwatch/pipeline/scorer/RuleScorerTest.java`
- Test: `pipeline/src/test/java/dev/pumpwatch/pipeline/flagger/FlagStageTest.java`
- Test: `pipeline/src/test/java/dev/pumpwatch/pipeline/proof/SystemProofTest.java`

**Interfaces:**
- Consumes: `Flag.Rule`, the seven-argument `Flag.Evidence` (Task 1).
- Produces: `default List<Flag.Rule> rules(Features features)` on `Scorer`, returning `List.of()`.
- Produces: `RuleScorer.rules(Features f)`: one `Flag.Rule(term.name(), term.feature(), f.values().getOrDefault(term.feature(), 0.0), term.atLeast(), term.points())` per fired term, using the last matching row of each term name, in first-appearance (table) order. `points(f)` sums the same rows, so `score` is unchanged.
- Produces: `FlagStage` passes `scorer.rules(f)` as the evidence's `rules`.

- [ ] **Step 1: Write the failing `RuleScorerTest` tests**

```java
@Test
void rulesAreTheFiredRowOfEachTermInTableOrder() {
    assertThat(scorer.rules(f(5, 10, 5, 0.06, 0))).containsExactly(
            new Flag.Rule("mention surge", "mentionRatio", 10, 10, 35),
            new Flag.Rule("broad chatter", "distinctAuthors", 5, 5, 5),
            new Flag.Rule("price jump", "return", 0.06, 0.05, 20));
    assertThat(scorer.rules(f(5, 1, 1, 0.0, 10))).isEmpty();
}

@Test
void firedRulePointsSumToTheUnclampedScore() {
    for (Map<String, Double> v : List.of(
            Map.of("mentionRatio", 10.0, "distinctAuthors", 5.0, "return", 0.06),
            Map.of("mentionRatio", 10.0, "return", 0.2, "newsOutlets6h", 3.0, "filings24h", 2.0, "catalystPrecededMove", 1.0),
            Map.of("mentionRatio", 3.0, "newAccountShare", 0.6, "volumeRatio", 20.0))) {
        assertThat(points(scorer.rules(with(v)))).isEqualTo(scorer.score(with(v)).score());
    }
    Map<String, Double> maxed = Map.of("mentionRatio", 99.0, "distinctAuthors", 99.0, "newAccountShare", 0.9,
            "duplicateShare", 0.9, "return", 0.5, "volume", 5e6, "volumeRatio", 30.0);
    assertThat(points(scorer.rules(with(maxed)))).isEqualTo(140);
    assertThat(scorer.score(with(maxed)).score()).isEqualTo(100);
    Map<String, Double> explained = Map.of("filings24h", 1.0, "newsOutlets6h", 5.0);
    assertThat(points(scorer.rules(with(explained)))).isEqualTo(-50);
    assertThat(scorer.score(with(explained)).score()).isZero();
}

@Test
void anomalyRulesCarryTheZScoreFeatureAndValue() {
    assertThat(RuleScorer.anomaly().rules(with(Map.of("z.mentionRatio", 7.0, "z.return", 5.0)))).containsExactly(
            new Flag.Rule("mention surge", "z.mentionRatio", 7.0, 5, 35),
            new Flag.Rule("price jump", "z.return", 5.0, 5, 35));
}

private static double points(List<Flag.Rule> rules) {
    return rules.stream().mapToDouble(Flag.Rule::points).sum();
}
```

- [ ] **Step 2: Write the failing `FlagStageTest` and `SystemProofTest` changes**

`FlagStageTest`:
- Move the body of `setUp()` into `void use(Scorer scorer)` (same fields, `FlagStage.build(b, scorer, CFG, meters)`); `setUp()` calls `use(STUB)`.
- Add to `STUB`: `@Override public List<Flag.Rule> rules(Features f) { return List.of(new Flag.Rule("mention surge", "mentionRatio", 12.4, 10, 35)); }`
- Add:

```java
@Test
void evidenceCarriesTheScorersFiredRules() {
    window(at(10, 0), 72);
    assertThat(flags().get(0).evidence().rules())
            .containsExactly(new Flag.Rule("mention surge", "mentionRatio", 12.4, 10, 35));
}

@Test
void aScorerWithoutRuleTermsLeavesRulesEmpty() {
    d.close();
    use(new Scorer() {
        @Override public String modelVersion() { return "onnx-test"; }
        @Override public Score score(Features f) { return STUB.score(f); }
        @Override public List<Flag.Contributor> contributors(Features f) { return List.of(); }
    });
    window(at(10, 0), 72);
    Flag f = flags().get(0);
    assertThat(f.evidence().rules()).isEmpty();
    assertThat(Validator.validate(f)).isEmpty();
}

@Test
void realRuleScorerRulesSumToTheFlagScore() {
    d.close();
    RuleScorer rules = new RuleScorer();
    use(rules);
    Instant end = at(10, 0);
    Features f = new Features(1, end, end, "GME", end.minusSeconds(300), end, 5,
            Map.of("mentions", 40.0, "trades", 12.0, "mentionRatio", 10.0, "return", 0.2), null);
    features.pipeInput("GME", Json.toJson(f));
    scores.pipeInput("GME", Json.toJson(rules.score(f)));
    Flag.Evidence e = flags().get(0).evidence();
    assertThat(e.score()).isEqualTo(70);
    assertThat(e.rules()).extracting(Flag.Rule::name).containsExactly("mention surge", "price jump");
    assertThat(e.rules().stream().mapToDouble(Flag.Rule::points).sum()).isEqualTo(70);
}
```

`SystemProofTest.theDayFlagsExactlyThePumpWithNoCatalyst`: append

```java
double sum = f.evidence().rules().stream().mapToDouble(Flag.Rule::points).sum();
assertThat(f.evidence().rules()).isNotEmpty();
assertThat(Math.max(0, Math.min(100, sum))).isEqualTo(f.evidence().score());
```

- [ ] **Step 3: Run the tests to verify they fail**

Run: `mvn -B -pl pipeline -am test -Dtest='RuleScorerTest,FlagStageTest,SystemProofTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: BUILD FAILURE, compilation error `cannot find symbol: method rules(Features)` (the `@Override` in `STUB` and the `scorer.rules(...)` calls).

- [ ] **Step 4: Implement**

`Scorer`: add the default method. `RuleScorer`: a private `Map<String, Term> fired(Features f)` (`LinkedHashMap`, `put(t.name(), t)` when `value >= t.atLeast()`), `points` sums `fired(f).values()` points, and the public `rules(f)` maps the same values to `Flag.Rule` per the Interfaces block. `FlagStage`: pass `scorer.rules(f)` as the seventh `Flag.Evidence` argument. `docs/ml/rule-scorer.md`: after the paragraph about catalyst terms, add one paragraph with no `|` characters: "Each flag from `rules-v1` or `anomaly-v1` lists its fired rules in `evidence.rules`: one entry per term, from the row that counted, with the feature, the measured value, the threshold and the points. The points add up to the score before it is kept between 0 and 100. Other models leave the list empty."

- [ ] **Step 5: Run the tests to verify they pass**

Run: `mvn -B -pl pipeline -am test -Dtest='RuleScorerTest,FlagStageTest,SystemProofTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: BUILD SUCCESS, `Failures: 0, Errors: 0`; `documentedTablesMatchTheCode` still passes.

- [ ] **Step 6: Commit**

```bash
git add pipeline/src docs/ml/rule-scorer.md
git commit -m "feat(pipeline): record the fired rules behind each flag (#131)"
```

### Task 3: Web contract for rules, SEC item names and rule text

**Files:**
- Modify: `web/lib/api.ts` (`Flag` type, `validFlag`)
- Create: `web/lib/sec-items.ts`
- Create: `web/lib/fired-rules.ts`
- Test: `web/tests/api.test.mjs`, `web/tests/no-raw-content.test.mjs`
- Create test: `web/tests/sec-items.test.mjs`, `web/tests/fired-rules.test.mjs`

**Interfaces:**
- Produces (`api.ts`): `export type FiredRule = { name: string; feature: string; value: number; threshold: number; points: number };` and `rules?: FiredRule[]` on `Flag["evidence"]`. `validFlag` accepts `rules` absent, or an array of at most 20 entries, each an object with non-empty string `name` and `feature` and `Number.isFinite` `value`, `threshold`, `points`.
- Produces (`sec-items.ts`): `export const SEC_8K_ITEMS: Readonly<Record<string, string>>` and `export function itemLabel(code: string): string` returning `` `${code} ${SEC_8K_ITEMS[code]}` `` when `Object.hasOwn(SEC_8K_ITEMS, code)`, else `code`.
- Produces (`fired-rules.ts`): `ruleNumber(n: number): string` = `n.toLocaleString("en-US", { maximumFractionDigits: 3 })`; `rulePoints(n: number): string` = `n.toLocaleString("en-US", { maximumFractionDigits: 1, signDisplay: "exceptZero" })`; `ruleLine(r: FiredRule): string` = `` `${r.name} ${rulePoints(r.points)} (${r.feature} ${ruleNumber(r.value)}, at least ${ruleNumber(r.threshold)})` ``.

`SEC_8K_ITEMS` holds exactly these 33 entries:

| Code | Name |
| --- | --- |
| 1.01 | Entry into a Material Definitive Agreement |
| 1.02 | Termination of a Material Definitive Agreement |
| 1.03 | Bankruptcy or Receivership |
| 1.04 | Mine Safety - Reporting of Shutdowns and Patterns of Violations |
| 1.05 | Material Cybersecurity Incidents |
| 2.01 | Completion of Acquisition or Disposition of Assets |
| 2.02 | Results of Operations and Financial Condition |
| 2.03 | Creation of a Direct Financial Obligation or an Obligation under an Off-Balance Sheet Arrangement of a Registrant |
| 2.04 | Triggering Events That Accelerate or Increase a Direct Financial Obligation or an Obligation under an Off-Balance Sheet Arrangement |
| 2.05 | Costs Associated with Exit or Disposal Activities |
| 2.06 | Material Impairments |
| 3.01 | Notice of Delisting or Failure to Satisfy a Continued Listing Rule or Standard; Transfer of Listing |
| 3.02 | Unregistered Sales of Equity Securities |
| 3.03 | Material Modification to Rights of Security Holders |
| 4.01 | Changes in Registrant's Certifying Accountant |
| 4.02 | Non-Reliance on Previously Issued Financial Statements or a Related Audit Report or Completed Interim Review |
| 5.01 | Changes in Control of Registrant |
| 5.02 | Departure of Directors or Certain Officers; Election of Directors; Appointment of Certain Officers; Compensatory Arrangements of Certain Officers |
| 5.03 | Amendments to Articles of Incorporation or Bylaws; Change in Fiscal Year |
| 5.04 | Temporary Suspension of Trading Under Registrant's Employee Benefit Plans |
| 5.05 | Amendments to the Registrant's Code of Ethics, or Waiver of a Provision of the Code of Ethics |
| 5.06 | Change in Shell Company Status |
| 5.07 | Submission of Matters to a Vote of Security Holders |
| 5.08 | Shareholder Director Nominations |
| 6.01 | ABS Informational and Computational Material |
| 6.02 | Change of Servicer or Trustee |
| 6.03 | Change in Credit Enhancement or Other External Support |
| 6.04 | Failure to Make a Required Distribution |
| 6.05 | Securities Act Updating Disclosure |
| 6.06 | Static Pool |
| 7.01 | Regulation FD Disclosure |
| 8.01 | Other Events |
| 9.01 | Financial Statements and Exhibits |

- [ ] **Step 1: Write the failing tests**

`web/tests/sec-items.test.mjs`:

```js
import assert from "node:assert/strict";
import { test } from "node:test";
import { SEC_8K_ITEMS, itemLabel } from "../lib/sec-items.ts";

test("known 8-K item codes read with their SEC names", () => {
  assert.equal(itemLabel("2.02"), "2.02 Results of Operations and Financial Condition");
  assert.equal(itemLabel("1.01"), "1.01 Entry into a Material Definitive Agreement");
  assert.equal(itemLabel("9.01"), "9.01 Financial Statements and Exhibits");
  assert.equal(Object.keys(SEC_8K_ITEMS).length, 33);
  assert.ok(Object.values(SEC_8K_ITEMS).every((n) => n.length > 0 && !/[–—]/.test(n)));
});

test("unknown or odd 8-K codes show the raw code", () => {
  for (const code of ["9.99", "toString", "constructor", "__proto__", ""]) assert.equal(itemLabel(code), code);
});
```

`web/tests/fired-rules.test.mjs`:

```js
import assert from "node:assert/strict";
import { test } from "node:test";
import { ruleLine, ruleNumber, rulePoints } from "../lib/fired-rules.ts";

test("rule numbers and points read plainly", () => {
  assert.equal(ruleNumber(12.4), "12.4");
  assert.equal(ruleNumber(1000000), "1,000,000");
  assert.equal(ruleNumber(0.05), "0.05");
  assert.equal(ruleNumber(7.12345), "7.123");
  assert.equal(rulePoints(35), "+35");
  assert.equal(rulePoints(-20), "-20");
  assert.equal(rulePoints(0), "0");
  assert.equal(ruleLine({ name: "mention surge", feature: "mentionRatio", value: 12.4, threshold: 10, points: 35 }),
    "mention surge +35 (mentionRatio 12.4, at least 10)");
});
```

`web/tests/api.test.mjs`, new test:

```js
test("fired rules are optional and must be named with finite numbers", () => {
  const rule = { name: "mention surge", feature: "mentionRatio", value: 12.4, threshold: 10, points: 35 };
  const event = (rules) => JSON.stringify({ data: { ...flag, evidence: { ...flag.evidence, rules } } });
  assert.deepEqual(api.parseFlagEvent(event([rule])).evidence.rules, [rule]);
  assert.deepEqual(api.parseFlagEvent(event([])).evidence.rules, []);
  assert.equal(api.parseFlagEvent(JSON.stringify({ data: flag })).evidence.rules, undefined);
  for (const bad of [{ ...rule, value: null }, { ...rule, threshold: "10" }, { ...rule, points: Infinity }, { ...rule, name: "" }, { ...rule, feature: 3 }, null, "rule"])
    assert.equal(api.parseFlagEvent(event([bad])), null);
  assert.equal(api.parseFlagEvent(event(rule)), null);
  assert.equal(api.parseFlagEvent(event(Array(21).fill(rule))), null);
});
```

`web/tests/no-raw-content.test.mjs`: add to `polluted` the case `["/api/v1/flags", [{ ...flag, evidence: { ...flag.evidence, rules: [{ name: "mention surge", feature: "mentionRatio", value: 1, threshold: 1, points: 20, author: "alice" }] } }]]`. This case passes already (`hasRawContent` is recursive); it pins the contract.

- [ ] **Step 2: Run the tests to verify they fail**

Run (from `web/`): `node --experimental-strip-types --test tests/sec-items.test.mjs tests/fired-rules.test.mjs tests/api.test.mjs tests/no-raw-content.test.mjs`
Expected: FAIL. `sec-items` and `fired-rules` with `ERR_MODULE_NOT_FOUND`; the api test at the first `bad` case (`Expected values to be strictly equal: ... null`).

- [ ] **Step 3: Implement**

Add the type and the `rules` check to `validFlag` (one `&&` clause; a small `validRules` helper next to `validSwing` is fine). Create the two lib files per the Interfaces block.

- [ ] **Step 4: Run the tests to verify they pass**

Run: `node --experimental-strip-types --test tests/sec-items.test.mjs tests/fired-rules.test.mjs tests/api.test.mjs tests/no-raw-content.test.mjs`
Expected: `# fail 0`.

- [ ] **Step 5: Commit**

```bash
git add web/lib/api.ts web/lib/sec-items.ts web/lib/fired-rules.ts web/tests
git commit -m "feat(web): validate fired rules and name 8-K items (#131)"
```

### Task 4: Mock rules and the assistant context

**Files:**
- Modify: `web/mock-api.mjs` (`flagsAt` only; `SCORE` stays `[74, 66, 32]`)
- Modify: `web/lib/flag-context.ts`
- Test: `web/tests/mock-api.test.mjs`, `web/tests/flag-context.test.mjs`

**Interfaces:**
- Consumes: `FiredRule` and `parseFlagEvent` (`lib/api.ts`), `ruleLine` (`lib/fired-rules.ts`), `itemLabel` (`lib/sec-items.ts`) from Task 3.
- Produces: `flagContext(f)` appends `` ` Rules fired: ${rules.map(ruleLine).join("; ")}.` `` after the drivers sentence when `f.evidence.rules` is non-empty; output for flags with no or empty `rules` is unchanged. The 600-character cap and control-character strip still apply to the whole line.
- Produces: mock flags with this evidence (all other fields unchanged; `evidence.score` is the rule sum):
  - GME (index 0): score 75; `topContributors` `[{social, 40}, {market, 35}]`; `catalyst` `{ found: false, items: [] }`; rules `mention surge / mentionRatio / 12.4 / 10 / 35`, `new accounts / newAccountShare / 0.34 / 0.3 / 5`, `price jump / return / 0.18 / 0.15 / 35`.
  - AMC (index 1): score 65; `topContributors` `[{market, 45}, {social, 40}, {catalyst, -20}]`; `catalyst` `{ found: true, items: ["2.02", "5.02"] }`; rules `mention surge / mentionRatio / 11.2 / 10 / 35`, `broad chatter / distinctAuthors / 8 / 5 / 5`, `price jump / return / 0.17 / 0.15 / 35`, `volume surge / volumeRatio / 6.5 / 5 / 10`, `8-K filing / filings24h / 1 / 1 / -20`.
  - Watchlist and timeline scores keep using `SCORE` (74, 66, 32); timeline flag markers keep `f.evidence.score`.

- [ ] **Step 1: Write the failing tests**

`web/tests/mock-api.test.mjs`, new test (add `import { parseFlagEvent } from "../lib/api.ts";` and `import { itemLabel } from "../lib/sec-items.ts";`):

```js
test("mock flags carry labelled mock rules that add up to the flag score", async () => {
  const flags = (await (await fetch(`${base}/api/v1/flags`)).json()).data;
  assert.deepEqual(flags.map((f) => [f.ticker, f.evidence.score]), [["GME", 75], ["AMC", 65]]);
  for (const f of flags) {
    assert.equal(f.evidence.modelVersion, "mock-rules-v1");
    assert.ok(f.evidence.rules.length > 0);
    assert.equal(f.evidence.rules.reduce((s, r) => s + r.points, 0), f.evidence.score);
    assert.deepEqual(parseFlagEvent(JSON.stringify({ data: f })), f);
  }
  const amc = flags[1].evidence;
  assert.deepEqual(amc.catalyst, { found: true, items: ["2.02", "5.02"] });
  assert.ok(amc.catalyst.items.every((code) => itemLabel(code) !== code));
  assert.ok(amc.rules.some((r) => r.name === "8-K filing" && r.points === -20));
  const watchlist = (await (await fetch(`${base}/api/v1/watchlist`)).json()).data;
  assert.deepEqual(watchlist.map((s) => s.score), [74, 66, 32]);
});
```

`web/tests/flag-context.test.mjs`, new test:

```js
test("the flag context lists fired rules after the drivers", () => {
  const rules = [
    { name: "mention surge", feature: "mentionRatio", value: 12.4, threshold: 10, points: 35 },
    { name: "8-K filing", feature: "filings24h", value: 1, threshold: 1, points: -20 },
  ];
  assert.equal(flagContext(flag({ score: 15, postCount: 1, tradeCount: 2, catalyst: null, topContributors: [], rules })),
    "GME flag: score 15 of 100 (Low risk). 1 posts, 2 trades. catalyst check not run. Rules fired: mention surge +35 (mentionRatio 12.4, at least 10); 8-K filing -20 (filings24h 1, at least 1).");
  assert.equal(flagContext(flag({ score: 20, postCount: 1, tradeCount: 2, catalyst: null, topContributors: [], rules: [] })),
    "GME flag: score 20 of 100 (Low risk). 1 posts, 2 trades. catalyst check not run.");
});
```

- [ ] **Step 2: Run the tests to verify they fail**

Run (from `web/`): `node --experimental-strip-types --test tests/mock-api.test.mjs tests/flag-context.test.mjs`
Expected: FAIL. Mock test at the score assertion (`[["GME",74],["AMC",66]]` vs expected 75/65); flag-context test with the "Rules fired" suffix missing.

- [ ] **Step 3: Implement**

`mock-api.mjs`: a `MOCK_EVIDENCE` array (two entries with the data above) next to `SWING`; `flagsAt` reads `rules`, `topContributors` and `catalyst` from it and sets `score` to the rule sum. `flag-context.ts`: append the rules sentence per the Interfaces block.

- [ ] **Step 4: Run the tests to verify they pass**

Run: `npm test`
Expected: `# fail 0` across all web tests.

- [ ] **Step 5: Commit**

```bash
git add web/mock-api.mjs web/lib/flag-context.ts web/tests
git commit -m "feat(web): serve mock fired rules and send rules to the assistant (#131)"
```

### Task 5: Show fired rules in the Flags panel and the landing evidence panel

Use the `impeccable` and `design-taste-frontend` skills against `web/DESIGN.md` for this task. Load them before writing JSX.

**Files:**
- Create: `web/components/flag-rules.tsx`
- Modify: `web/components/live-view.tsx` (`FlagFeed` list item only, plus one import)
- Modify: `web/components/live-market.tsx` (the `flag ?` evidence branch, plus one import)

**Interfaces:**
- Consumes: `Flag`, `FiredRule` (`lib/api.ts`), `ruleNumber`, `rulePoints` (`lib/fired-rules.ts`), `itemLabel` (`lib/sec-items.ts`).
- Produces: `export function FlagRules({ evidence, mock }: { evidence: Flag["evidence"]; mock: boolean })` in `flag-rules.tsx`. It reads `evidence.rules ?? []` and renders:
  - A label "Rules fired" followed by " (MOCK)" when `mock`, then a list (`<ul>`, one `<li>` per rule) showing the name (amber label), `feature value, at least threshold` (feature mute, numbers white, via `ruleNumber`), and the points via `rulePoints` with tabular figures. Points are never `text-up` or `text-down`.
  - When the list is empty: one mute line "No rule detail for this flag."
  - When `evidence.catalyst?.found` and `items.length > 0`: a label "8-K items" and a list with one `itemLabel(code)` per item, wrapping long names (`break-words`).
- Produces: in `FlagFeed`, under the existing evidence `<p>` of each flag, a native `<details>` (closed by default) whose `<summary>` reads "Why this flag (N rule)" / "(N rules)" (just "Why this flag" when N is 0), at least 44px tall on phones (`min-h-11 lg:min-h-7`, same pattern as the row buttons), with a 2px yellow focus ring, containing `<FlagRules evidence={f.evidence} mock={mode === "mock"} />`.
- Produces: in `LiveMarket`, `<FlagRules evidence={flag.evidence} mock={data.mode === "mock"} />` directly after the "Flag contributions" paragraph, always shown (not collapsed).

- [ ] **Step 1: Start the app and write the failing browser checks**

Rebase on `origin/main` first. From `web/` with no `.env` (so `DATA_MODE=mock`), run `npm run dev` in the background. With Playwright MCP (`mcp__playwright__browser_navigate` to `http://127.0.0.1:3000/live`, then `browser_snapshot` or `browser_evaluate`), check:
- the Flags panel has a `summary` with text "Why this flag (3 rules)" in the GME row and "Why this flag (5 rules)" in the AMC row;
- `document.querySelectorAll('#flags details[open]').length === 0`.

- [ ] **Step 2: Run the checks to verify they fail**

Expected: no `summary` elements in `#flags`; the "Why this flag" text is not found.

- [ ] **Step 3: Implement `FlagRules` and wire it in**

Build per the Interfaces block and `web/DESIGN.md`. Keep the `live-view.tsx` diff to the import and the lines inside the `FlagFeed` list item; do not touch `ChartPanel`.

- [ ] **Step 4: Re-run the browser checks and add the open-state checks**

Expected: both summaries found, none open. Click the AMC summary, then check that the AMC row shows "8-K filing", "filings24h 1, at least 1", "-20", "Rules fired (MOCK)", "2.02 Results of Operations and Financial Condition" and the full 5.02 name. Navigate to `http://127.0.0.1:3000/` and check the WHY panel shows the GME rules open ("mention surge", "mentionRatio 12.4, at least 10", "+35") and no "8-K items" label.

- [ ] **Step 5: Lint and build**

Run (from `web/`): `npm run lint` then `npm run build`
Expected: lint exits 0 with no errors; build ends with the route table and no type errors.

- [ ] **Step 6: Commit**

```bash
git add web/components/flag-rules.tsx web/components/live-view.tsx web/components/live-market.tsx
git commit -m "feat(web): show fired rules and 8-K item names on flags (#131)"
```

### Task 6: Full checks and verification in the running app

**Files:** none changed unless a check fails (fix in the owning task's files, test first, separate `fix(...)` commit).

- [ ] **Step 1: Java suite**

Run (repo root): `mvn -B verify`
Expected: `BUILD SUCCESS` for every module (pipeline takes about 13 minutes).

- [ ] **Step 2: Web suite**

Run (from `web/`): `npm test`, `npm run lint`, `npm run build`
Expected: `# fail 0`; lint exit 0; build success.

- [ ] **Step 3: Running app with mock data (Playwright MCP)**

With `npm run dev` running and no `.env`, at `http://127.0.0.1:3000/live`:
1. Flags panel tag reads "MOCK STREAM"; rows GME 75 and AMC 65 show; both "Why this flag" disclosures are closed.
2. Expand GME: three rules in order "mention surge" (mentionRatio 12.4, at least 10, +35), "new accounts" (newAccountShare 0.34, at least 0.3, +5), "price jump" (return 0.18, at least 0.15, +35); label "Rules fired (MOCK)"; no "8-K items".
3. Expand AMC: five rules ending with "8-K filing" (-20); "8-K items" lists "2.02 Results of Operations and Financial Condition" and the full 5.02 name.
4. `browser_evaluate`: no element inside `#flags details` has class `text-up` or `text-down`; the points' computed colour is neither `rgb(25, 224, 107)` nor `rgb(255, 77, 77)`.
5. Keyboard: Tab to the GME summary, the 2px yellow focus ring shows, Enter toggles it.
6. Click "Ask about this flag" on AMC; in `browser_network_requests` the POST to `/api/v1/assistant/ask` has a `context` containing "Rules fired: mention surge +35 (mentionRatio 11.2, at least 10)". The answer itself may be an error if the research service is not running; that is fine.
7. `browser_resize` to 375x812, expand AMC: `document.documentElement.scrollWidth <= 375`; the 5.02 name wraps.
8. At `http://127.0.0.1:3000/` the WHY panel shows the GME rules open with "Rules fired (MOCK)" and "Risk signal only. Not investment advice." stays visible.
9. `browser_console_messages`: no errors.
10. A flag without rules (an older stored flag): with `browser_run_code_unsafe`, `await page.route('**/api/v1/flags/stream', (r) => r.abort())` and `await page.route('**/api/v1/flags?*', async (r) => { const res = await r.fetch(); const body = await res.json(); body.data.forEach((f) => delete f.evidence.rules); await r.fulfill({ response: res, json: body }); })`, then reload `/live`. Expected: the summaries read "Why this flag", expanding one shows "No rule detail for this flag.", the "Driven by" contributions line still shows, and the console has no errors. Remove the routes (`page.unrouteAll()`) afterwards.

Take a screenshot of the expanded AMC row at desktop and 375px width for the PR.

- [ ] **Step 4: Push and report**

```bash
git push -u origin feat/flag-rule-evidence
```

Report the commands run and their results, then open the PR with `superpowers:finishing-a-development-branch`.
