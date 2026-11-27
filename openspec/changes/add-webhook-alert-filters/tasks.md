# Per-Webhook Alert Filters Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let each alert webhook carry an optional ticker allow-list and minimum score, so a channel gets only the flags it asked for (issue #134).

**Architecture:** `AlertProperties` gains `List<ChannelFilter> channelFilters`, bound by position from `ALERTS_CHANNEL_FILTERS_<n>_TICKERS` / `_MIN_SCORE`. `AlertPlanner.plan` checks the channel's filter before cooldown and rate and puts failing flags in `Plan.suppressed`. `AlertService` already records `suppressed` through `store.suppress`, so filtered flags are decided once and never return. No migration.

**Tech Stack:** Java 21, Spring Boot 3.5.16 (`@ConfigurationProperties` records, `Binder`), JUnit 5 + AssertJ, Testcontainers Postgres 16, JDK `com.sun.net.httpserver.HttpServer`.

**Spec:** `openspec/changes/add-webhook-alert-filters/specs/alert-delivery/spec.md`, `design.md`, GitHub issue #134.

## Global Constraints

- No code comments of any kind in new or changed code: no inline, block, Javadoc or TODO lines. Keep existing comments as they are. `.env.example` keeps its existing `#` documentation style. That is user docs, not code.
- Test first in every task: write the failing test, run it and see it fail, implement, run it and see it pass, then commit.
- Webhook URLs and filter values are never logged and never appear in exception messages we write. Our startup errors name the setting (`alerts.channel-filters[0]`, an env var name, a position), never its value.
- Invalid filter config fails startup with a clear message. No filter means today's behaviour, byte for byte.
- Never print `.env` values. Never use real webhook URLs. Tests use `https://hooks.example.com/...` or `http://127.0.0.1:<port>/...`.
- Use `127.0.0.1`, not `localhost`, in local scripts and test URLs.
- Checks, run from the repo root in Git Bash: `mvn -B -pl api -am verify` during work, and `mvn -B verify` at the end. The pipeline module takes about 13 minutes. Do not override `java.io.tmpdir` to a long path.
- `JdbcAlertStoreDbTest` uses `@Testcontainers(disabledWithoutDocker = true)`. If Docker is off, it is skipped. Say "skipped, Docker off", never "passed".
- Do not run `terraform` or create cloud resources. Do not push. Commits use Conventional Commits with no `Co-Authored-By` or AI attribution line.
- Compose checks use only the throwaway project `-p pumpwatch-e2e` and end with `docker compose -p pumpwatch-e2e --profile apps down -v`. Never start, stop or write to the owner's `pumpwatch` project or its volumes.
- Env var names are fixed by the issue: `ALERTS_CHANNEL_FILTERS_<n>_TICKERS` (comma list) and `ALERTS_CHANNEL_FILTERS_<n>_MIN_SCORE` (number), `<n>` = 0-based position in `ALERTS_WEBHOOK_URLS`.
- Ticker rule: strip, upper-case (`Locale.ROOT`), drop blanks, then match `ApiController.TICKER` (`[A-Z]{1,5}`). Score rule: blank = none. Otherwise parse as a double that must satisfy `0 <= s <= 100` (NaN and infinities fail). A flag passes when `tickers.isEmpty() || tickers.contains(flag.ticker())` and `minScore == null || flag.evidence().score() >= minScore`.
- Exact error messages:
  - `tickers must be 1 to 5 letters A-Z`
  - `min-score must be a number from 0 to 100`
  - `alerts.channel-filters has <k> entries for <m> webhook(s)`
  - `<NAME> is not a filter setting; use ALERTS_CHANNEL_FILTERS_<n>_TICKERS or ALERTS_CHANNEL_FILTERS_<n>_MIN_SCORE`
  - `<NAME> has no filter entry; filter positions start at 0 with no gaps`

## Review Focus

Checked against Spring Boot 3.5.16 in a scratch harness during planning:

- **Index gaps and misspelled names are silently ignored by Spring.** `ALERTS_CHANNEL_FILTERS_1_TICKERS` with no `_0_` entry, or `_0_TICKER` (singular), binds nothing, so the channel would get every flag. Expect startup to fail, naming the env var. Pinned by `AlertPropertiesTest.filterNamesMustBeKnownAndContiguous`, enforced in `AlertService`'s constructor (Task 1).
- **The underscore form binds only from the source named `systemEnvironment`.** A binder test that names its source anything else proves nothing. Expect the documented names to bind in the real app. Pinned by `AlertPropertiesTest.bindsDocumentedEnvironmentVariables`, which uses `StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME` (Task 1).
- **Blank values in `.env` (`ALERTS_CHANNEL_FILTERS_0_TICKERS=`).** Expect "no restriction", not "block everything" and not a startup failure. Pinned by `AlertPropertiesTest.blankFilterValuesMeanNoFilter` (Task 1).
- **Filtered flags must not change what passing flags get.** A filtered flag must not start a ticker cooldown or use rate room, or a later passing flag for the same ticker would be suppressed or pushed to a digest. Pinned by `AlertPlannerTest.filteredFlagStartsNoCooldownAndUsesNoRate` (Task 2).
- **Two channels, one filtered.** The other channel must still get every flag. `FakeStore.lastAlerted` currently ignores the channel and would make this test lie, so Task 2 makes it per channel, as the SQL is. Pinned by `AlertServiceTest.filteredFlagsAreSuppressedOnlyOnTheirChannel` (Task 2) and end to end in Task 3.

---

### Task 1: Bind and validate channel filters

**Files:**
- Modify: `api/src/main/java/dev/pumpwatch/api/ApiController.java:30` (`static final Pattern TICKER` becomes `public static final Pattern TICKER`)
- Modify: `api/src/main/java/dev/pumpwatch/api/alerts/AlertProperties.java`
- Modify: `api/src/main/java/dev/pumpwatch/api/alerts/AlertService.java:33-39` (4-arg constructor)
- Modify: `api/src/test/java/dev/pumpwatch/api/alerts/AlertServiceTest.java:89-92` (`props` helper, so it compiles)
- Create: `api/src/test/java/dev/pumpwatch/api/alerts/AlertPropertiesTest.java`

**Interfaces:**
- Consumes: `ApiController.TICKER`, `Views.FlagView`, `AlertServiceTest.flag(long, String, Instant, double)`.
- Produces:
  - `public record AlertProperties(..., Duration maxFlagAge, @DefaultValue List<ChannelFilter> channelFilters)`, a new last component. The compact constructor keeps the URL stripping, adds `channelFilters = List.copyOf(channelFilters)`, and, only when `enabled`, throws `IllegalArgumentException("alerts.channel-filters has " + k + " entries for " + m + " webhook(s)")` if `k > m`. `toString()` is unchanged.
  - `public ChannelFilter filter(int index)`: the entry at `index`, or `ChannelFilter.NONE` when there is none.
  - `static void checkFilterNames(Set<String> envNames, int filterCount)`: every name that starts with `ALERTS_CHANNEL_FILTERS_` must match `ALERTS_CHANNEL_FILTERS_(\d+)_(TICKERS|MIN_SCORE)` and its index must be `< filterCount`. Otherwise it throws `IllegalArgumentException` with the two messages from Global Constraints.
  - Nested `public record ChannelFilter(Set<String> tickers, Double minScore)`:
    - `public static final ChannelFilter NONE = new ChannelFilter(Set.of(), null)`
    - compact canonical constructor: `tickers = Set.copyOf(tickers)`
    - `@ConstructorBinding public ChannelFilter(@DefaultValue List<String> tickers, String minScore)` delegates to the canonical constructor through two private static parsers that apply the Global Constraints rules and messages. Spring binds through this constructor, so it binds raw strings and never runs a type conversion that would print a value.
    - `public boolean allows(FlagView flag)`
  - `AlertService(AlertStore, WebhookSender, AlertProperties, Clock)` calls `AlertProperties.checkFilterNames(System.getenv().keySet(), props.channelFilters().size())` before its log line.

- [ ] **Step 1: Write the failing tests**

`AlertPropertiesTest` binds the way the app does:

```java
static AlertProperties bind(Map<String, Object> env) {
    var source = new SystemEnvironmentPropertySource(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, env);
    return new Binder(ConfigurationPropertySources.from(source)).bindOrCreate("alerts", AlertProperties.class);
}

static String chain(Throwable t) {
    var sb = new StringBuilder();
    for (; t != null; t = t.getCause()) sb.append(t.getMessage()).append('\n');
    return sb.toString();
}
```

Tests (`T`, `flag` from `AlertServiceTest`):

- `bindsDocumentedEnvironmentVariables`: env `ALERTS_ENABLED=true`, `ALERTS_WEBHOOK_URLS=https://hooks.example.com/a,https://hooks.example.com/b`, `ALERTS_CHANNEL_FILTERS_0_TICKERS=" gme, AMC ,"`, `ALERTS_CHANNEL_FILTERS_0_MIN_SCORE=75`.
  - `assertThat(p.filter(0).tickers()).containsExactlyInAnyOrder("GME", "AMC")`
  - `assertThat(p.filter(0).minScore()).isEqualTo(75.0)`
  - `assertThat(p.filter(1)).isSameAs(AlertProperties.ChannelFilter.NONE)`
  - `assertThat(p.filter(7)).isSameAs(AlertProperties.ChannelFilter.NONE)`
- `noFilterVariablesMeanEveryFlagPasses`: env enabled with one URL. `assertThat(p.channelFilters()).isEmpty()` and `assertThat(p.filter(0).allows(flag(1, "TSLA", T, 61))).isTrue()`.
- `blankFilterValuesMeanNoFilter`: env `ALERTS_CHANNEL_FILTERS_0_TICKERS=""`, `ALERTS_CHANNEL_FILTERS_0_MIN_SCORE=""`, enabled with one URL. `assertThat(p.channelFilters()).hasSize(1)` and `assertThat(p.filter(0).allows(flag(1, "TSLA", T, 1))).isTrue()`.
- `allowsChecksTickerAndInclusiveMinScore`:
  - `var f = new AlertProperties.ChannelFilter(List.of("GME"), "75")`
  - `f.allows(flag(1,"GME",T,75))` is true, `f.allows(flag(2,"GME",T,74.9))` is false, `f.allows(flag(3,"AMC",T,99))` is false
  - `new ChannelFilter(List.of(), "75").allows(flag(4,"AMC",T,80))` is true
  - `new ChannelFilter(List.of("gme"), null).allows(flag(5,"GME",T,1))` is true
- `invalidTickerFailsWithoutEchoingIt`: for each of `"GME,G1ME"`, `"GME,TOOLONG"`, `"GME,BRK.B"` as `ALERTS_CHANNEL_FILTERS_0_TICKERS`:
  - `assertThatThrownBy(() -> bind(env)).isInstanceOf(BindException.class).hasMessageContaining("alerts.channel-filters[0]").hasRootCauseMessage("tickers must be 1 to 5 letters A-Z")`
  - `assertThat(chain(thrown)).doesNotContain(bad)`, where `bad` is `G1ME`, `TOOLONG` or `BRK.B`
- `invalidMinScoreFailsWithoutEchoingIt`: for each of `"xyzzy"`, `"101"`, `"-1"`, `"NaN"`, `"Infinity"` as `ALERTS_CHANNEL_FILTERS_0_MIN_SCORE`: the same three assertions with root cause `min-score must be a number from 0 to 100`, and `chain(thrown)` does not contain `xyzzy`, `101`, `-1`, `NaN` or `Infinity`.
- `moreFiltersThanWebhooksFailsOnlyWhenEnabled`: env one URL, `ALERTS_CHANNEL_FILTERS_0_TICKERS=GME`, `ALERTS_CHANNEL_FILTERS_1_TICKERS=AMC`.
  - With `ALERTS_ENABLED=true`: `hasRootCauseMessage("alerts.channel-filters has 2 entries for 1 webhook(s)")`
  - With `ALERTS_ENABLED=false`: `assertThat(bind(env).channelFilters()).hasSize(2)`
- `filterNamesMustBeKnownAndContiguous`:
  - `checkFilterNames(Set.of("ALERTS_CHANNEL_FILTERS_1_TICKERS"), 0)` throws `IllegalArgumentException` with message `ALERTS_CHANNEL_FILTERS_1_TICKERS has no filter entry; filter positions start at 0 with no gaps`
  - `checkFilterNames(Set.of("ALERTS_CHANNEL_FILTERS_0_TICKER"), 1)` throws with message `ALERTS_CHANNEL_FILTERS_0_TICKER is not a filter setting; use ALERTS_CHANNEL_FILTERS_<n>_TICKERS or ALERTS_CHANNEL_FILTERS_<n>_MIN_SCORE`
  - `assertThatCode(() -> checkFilterNames(Set.of("ALERTS_CHANNEL_FILTERS_0_TICKERS", "ALERTS_CHANNEL_FILTERS_0_MIN_SCORE", "ALERTS_ENABLED", "PATH"), 1)).doesNotThrowAnyException()`
- `toStringHidesUrlsAndFilters`: `new AlertProperties(true, List.of(AlertServiceTest.SECRET), "http://127.0.0.1:3000", Duration.ofMinutes(30), 5, 3, Duration.ofSeconds(5), Duration.ofMinutes(1), Duration.ofHours(1), List.of(new ChannelFilter(List.of("ZZZZ"), "88"))).toString()` does not contain `SECRETTOKEN`, `ZZZZ` or `88`.

In `AlertServiceTest`, change `props(int)` to pass `List.of()` as the new last argument.

- [ ] **Step 2: Run the tests and see them fail**

Run: `mvn -B -pl api -am test -Dtest=AlertPropertiesTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: `COMPILATION ERROR`, `cannot find symbol` for `ChannelFilter` / `checkFilterNames`.

- [ ] **Step 3: Implement**

Make `ApiController.TICKER` public. Add the `AlertProperties` members and the `AlertService` constructor call from the Interfaces block. The Spring Boot parent already compiles with `-parameters`, which constructor binding needs.

- [ ] **Step 4: Run the tests and see them pass**

Run: `mvn -B -pl api -am test -Dtest='AlertPropertiesTest,AlertServiceTest,AlertPlannerTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: `Tests run: ..., Failures: 0, Errors: 0` and `BUILD SUCCESS`. If `bindsDocumentedEnvironmentVariables` fails because nothing bound, stop and report. The env var naming is then an owner decision.

- [ ] **Step 5: Run the module checks**

Run: `mvn -B -pl api -am verify`
Expected: `BUILD SUCCESS`. Note any `Skipped` count from `JdbcAlertStoreDbTest` and whether Docker was on.

- [ ] **Step 6: Commit**

```bash
git add api/src/main/java/dev/pumpwatch/api/ApiController.java api/src/main/java/dev/pumpwatch/api/alerts/AlertProperties.java api/src/main/java/dev/pumpwatch/api/alerts/AlertService.java api/src/test/java/dev/pumpwatch/api/alerts/AlertPropertiesTest.java api/src/test/java/dev/pumpwatch/api/alerts/AlertServiceTest.java
git commit -m "feat(api): bind and validate per-webhook alert filters (#134)"
```

---

### Task 2: Apply filters when planning, and document them

**Files:**
- Modify: `api/src/main/java/dev/pumpwatch/api/alerts/AlertPlanner.java:21-38`
- Modify: `api/src/main/java/dev/pumpwatch/api/alerts/AlertService.java:51-63` (`plan()`)
- Modify: `api/src/test/java/dev/pumpwatch/api/alerts/AlertPlannerTest.java`
- Modify: `api/src/test/java/dev/pumpwatch/api/alerts/AlertServiceTest.java` (`FakeStore.last` per channel, new `props` overload, new tests)
- Modify: `api/src/test/java/dev/pumpwatch/api/alerts/JdbcAlertStoreDbTest.java`
- Modify: `.env.example:71-73`, `docs/architecture/overview.md:142-144` and the env table near line 279

**Interfaces:**
- Consumes: `AlertProperties.ChannelFilter` (`NONE`, `allows(FlagView)`, `(List<String>, String)` constructor), `AlertProperties.filter(int)` from Task 1.
- Produces: `static Plan plan(List<FlagView> pending, AlertProperties.ChannelFilter filter, Map<String, Instant> lastAlerted, int sentLastMinute, Duration cooldown, int maxPerMinute)`. The first check in the loop is `if (!filter.allows(f)) { suppressed.add(f); continue; }`, before the cooldown lookup. `AlertService.plan()` passes `props.filter(i)`. Test helper `AlertServiceTest.props(int maxPerMinute, List<String> urls, List<AlertProperties.ChannelFilter> filters)`. `props(int)` delegates to it with `List.of(SECRET), List.of()`. Constant `AlertServiceTest.SECRET2 = "https://hooks.example.com/services/T000/B000/SECONDTOKEN"`.

- [ ] **Step 1: Write the failing tests**

`AlertPlannerTest` (existing two tests pass `AlertProperties.ChannelFilter.NONE` as the new second argument, and their assertions stay the same):

- `filterSuppressesOtherTickersAndLowScores`: filter `new ChannelFilter(List.of("GME"), "75")`. Pending: `flag(1,"AMC",T,90)`, `flag(2,"GME",T,70)`, `flag(3,"GME",T.plusSeconds(1),80)`. Call `plan(pending, filter, Map.of(), 0, Duration.ofMinutes(30), 5)`.
  - `suppressed` ids `containsExactly(1L, 2L)`
  - `singles` ids `containsExactly(3L)`
  - `digest` is empty
- `filteredFlagStartsNoCooldownAndUsesNoRate`: filter `new ChannelFilter(List.of(), "75")`, `maxPerMinute` 1. Pending: `flag(1,"AAA",T,70)`, `flag(2,"AAA",T.plusSeconds(60),80)`, `flag(3,"BBB",T.plusSeconds(60),90)`.
  - `singles` ids `containsExactly(2L)`
  - `digest` ids `containsExactly(3L)`
  - `suppressed` ids `containsExactly(1L)`

`AlertServiceTest`:

- First, make `FakeStore.last` a `Map<String, Map<String, Instant>>` keyed by channel. `lastAlerted(ch)` returns that channel's map, and `decide` merges into it, matching `JdbcAlertStore.lastAlerted`'s `WHERE channel = ?`. The existing five tests must still pass.
- `filteredFlagsAreSuppressedOnlyOnTheirChannel`: flags `flag(1,"GME",T,80)`, `flag(2,"TSLA",T,90)`, `flag(3,"AMC",T,60)`. `props(5, List.of(SECRET, SECRET2), List.of(new ChannelFilter(List.of("GME","AMC"), "75")))`. The sender records `(url, msg)` pairs and returns 200. Call `tick()`.
  - Posts to `SECRET`: `hasSize(1)`, and that one contains `GME`
  - Posts to `SECRET2`: `hasSize(3)`
  - `store.ledger` contains `"webhook-1" + 2` and `"webhook-1" + 3`
  - `store.pendingFlags("webhook-1", T.minusSeconds(3600), 200)` is empty
  - A second `tick()` leaves both counts the same
- `filterValuesNeverReachLogs`: same logger `ListAppender` setup as `webhookUrlNeverReachesLogsOrStoredRecords`. Filter `new ChannelFilter(List.of("ZZZZ"), "88")`, flags `flag(1,"ZZZZ",T,90)` and `flag(2,"QQQQ",T,95)`, and a sender that throws `IllegalArgumentException("bad uri " + url)`. Tick twice. The formatted log messages are not empty and none contains `ZZZZ`, `QQQQ` or `88`.

`JdbcAlertStoreDbTest`:

- `suppressedFlagIsDecidedAndStartsNoCooldown`: `save(repo, "live", "flags", 3, "NVDA")`, then take `f` from `store.pendingFlags("webhook-7", T.minusSeconds(60), 10)`. Call `store.suppress("webhook-7", List.of(f))`.
  - `store.pendingFlags("webhook-7", T.minusSeconds(60), 10)` is empty
  - `store.lastAlerted("webhook-7")` `doesNotContainKey("NVDA")`
  - `jdbc.queryForObject("SELECT outcome FROM alert_flags WHERE channel = 'webhook-7' AND flag_id = ?", String.class, f.id())` is `"suppressed"`

This pins an existing store contract the feature relies on. It is expected to pass without store changes. Under Docker it must run, not skip.

- [ ] **Step 2: Run the tests and see them fail**

Run: `mvn -B -pl api -am test -Dtest='AlertPlannerTest,AlertServiceTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: `COMPILATION ERROR` on the new `plan` argument. After the signature exists but before the `allows` check, the planner and service filter tests fail on their `suppressed`/`hasSize` assertions.

- [ ] **Step 3: Implement**

Add the `filter` parameter and the `allows` check to `AlertPlanner.plan`, and pass `props.filter(i)` in `AlertService.plan()`. Docs:
- `.env.example`, after `ALERTS_MAX_PER_MINUTE=5`: one `#` line saying each webhook can have an optional filter by its 0-based position in `ALERTS_WEBHOOK_URLS`, that flags failing it are never sent to that webhook, and that positions start at 0 with no gaps. Then two commented examples: `# ALERTS_CHANNEL_FILTERS_0_TICKERS=GME,AMC` and `# ALERTS_CHANNEL_FILTERS_0_MIN_SCORE=75`.
- `docs/architecture/overview.md`: one sentence after "Replay-run flags are excluded from alert planning." saying each webhook can have an optional ticker allow-list and minimum score by position, and that flags failing it are recorded as suppressed for that channel and never sent there. Add a table row after `ALERTS_ENABLED`: `| \`ALERTS_CHANNEL_FILTERS_<n>_TICKERS\`, \`ALERTS_CHANNEL_FILTERS_<n>_MIN_SCORE\` | Optional per-webhook filter (0-based position) | Unset means every flag; invalid values stop startup |`.
- `application.yml` is not changed. The indexed env vars bind by relaxed binding.

- [ ] **Step 4: Run the tests and see them pass**

Run: `mvn -B -pl api -am test -Dtest='AlertPlannerTest,AlertServiceTest,AlertPropertiesTest,JdbcAlertStoreDbTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: `Failures: 0, Errors: 0`, `BUILD SUCCESS`. `JdbcAlertStoreDbTest` shows `Skipped: 0` with Docker on, or is skipped and reported as "skipped, Docker off".

- [ ] **Step 5: Run the module checks**

Run: `mvn -B -pl api -am verify`
Expected: `BUILD SUCCESS`.

- [ ] **Step 6: Commit**

```bash
git add api/src/main/java/dev/pumpwatch/api/alerts/AlertPlanner.java api/src/main/java/dev/pumpwatch/api/alerts/AlertService.java api/src/test/java/dev/pumpwatch/api/alerts/AlertPlannerTest.java api/src/test/java/dev/pumpwatch/api/alerts/AlertServiceTest.java api/src/test/java/dev/pumpwatch/api/alerts/JdbcAlertStoreDbTest.java .env.example docs/architecture/overview.md
git commit -m "feat(api): apply per-webhook alert filters when planning (#134)"
```

---

### Task 3: Verify end to end

**Files:**
- Create: `api/src/test/java/dev/pumpwatch/api/alerts/AlertFilterDeliveryTest.java`
- Scratch only, not committed: `receiver.py`, `alerts-e2e.override.yml`, `e2e.sql` in the session scratchpad (`$S` below)

**Interfaces:**
- Consumes: `HttpWebhookSender` (package-private, same package), `AlertServiceTest.FakeStore`, `MutableClock`, `flag`, `props(int, List<String>, List<ChannelFilter>)`, `AlertProperties.ChannelFilter`.
- Produces: nothing for later tasks.

- [ ] **Step 1: Write the delivery test**

`realWebhookPostsReachOnlyMatchingChannels`:
- Start `HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0)` with contexts `/one` and `/two`. Each appends the request body (UTF-8) to its own `CopyOnWriteArrayList<String>` and answers `204` with `-1` length. Stop the server in `finally`.
- URLs: `http://127.0.0.1:<port>/one`, `http://127.0.0.1:<port>/two`.
- Filter `new ChannelFilter(List.of("GME"), "75")`. Flags `flag(1,"GME",T,80)`, `flag(2,"TSLA",T,90)`, `flag(3,"AMC",T,70)`.
- Build `new AlertService(store, new HttpWebhookSender(), props(5, urls, filters), new MutableClock())` and call `tick()`.
- Assertions:
  - `one` `hasSize(1)`, and `one.get(0)` contains `GME`, does not contain `TSLA`, does not contain `AMC`
  - `two` `hasSize(3)`
  - after a second `tick()`, the sizes are still 1 and 3

- [ ] **Step 2: Run it**

Run: `mvn -B -pl api -am test -Dtest=AlertFilterDeliveryTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: `Tests run: 1, Failures: 0`. It passes on first run, because Tasks 1 and 2 built the behaviour. Then, to prove it can fail, temporarily change the filter in the test to `ChannelFilter.NONE`, see `one` `hasSize(1)` fail with size 3, and revert.

- [ ] **Step 3: Commit**

```bash
git add api/src/test/java/dev/pumpwatch/api/alerts/AlertFilterDeliveryTest.java
git commit -m "test(api): deliver filtered alerts to a local webhook receiver (#134)"
```

- [ ] **Step 4: Check Docker and the host ports**

The compose run uses a throwaway project, `-p pumpwatch-e2e`, with its own containers, network (`pumpwatch-e2e_default`) and volumes. It never touches the owner's `pumpwatch` project or its database volume. It still publishes the same fixed host ports, so it must not start while anything else holds them.

Run: `docker info --format '{{.ServerVersion}}'`
- If Docker is off, skip Steps 5 to 8. Report "compose run skipped, Docker off". Step 2 stands as the end-to-end proof.

Run (Git Bash):
```bash
for p in 9092 5432 8080 8081 8082 8083 "${PROXY_PORT:-80}"; do
  netstat -ano -p TCP | grep LISTENING | grep -qE "[:.]$p[[:space:]]" && echo "port $p in use"
done
```
Expected: no output. If any port is reported in use, stop and report which ports are in use. Do not stop or change whatever holds them.

- [ ] **Step 5: Start the throwaway stack with a private receiver**

The receiver runs as a container on the e2e compose network with no published port, so only `api` can reach it. Inside a container, `127.0.0.1` is the container itself, so it cannot be the target. `receiver.py` (in `$S`) is a `http.server.BaseHTTPRequestHandler` with `do_POST` that appends `"<path>\t<body>\n"` to `/r/received.log` and answers 204, served on `0.0.0.0:9911`. `alerts-e2e.override.yml`:

```yaml
services:
  api:
    environment:
      ALERTS_ENABLED: "true"
      ALERTS_WEBHOOK_URLS: http://pw-hook:9911/one,http://pw-hook:9911/two
      ALERTS_CHANNEL_FILTERS_0_TICKERS: GME
      ALERTS_CHANNEL_FILTERS_0_MIN_SCORE: "75"
```

Run (Git Bash):
```bash
export MSYS_NO_PATHCONV=1
docker compose -p pumpwatch-e2e -f docker-compose.yml -f "$S/alerts-e2e.override.yml" --profile apps up -d --build
docker compose -p pumpwatch-e2e images research
docker run -d --rm --name pw-hook --network pumpwatch-e2e_default -v "$(cygpath -w "$S"):/r" --entrypoint python pumpwatch-e2e-research /r/receiver.py
```
If `images research` shows a different repository name, use that name in `docker run`.
Expected: `docker compose -p pumpwatch-e2e ps api` shows `healthy`. `docker logs pumpwatch-e2e-api-1 2>&1 | grep -c "alerts enabled with 2 webhook(s)"` prints `1`.

- [ ] **Step 6: Insert three live flags into the throwaway database and wait**

The e2e database starts empty, so the test flags are inserted there. `e2e.sql` inserts into `api_flags (run_id, topic, kafka_partition, kafka_offset, ticker, raised_at, evidence)` the rows `('live','flags',0,990001,'GME',now(),…score 80…)`, `(…990002,'TSLA',…score 90…)` and `(…990003,'AMC',…score 70…)`. Evidence is `{"score":<s>,"modelVersion":"e2e-1","topContributors":[],"postCount":5,"tradeCount":2,"catalyst":{"found":false,"lookbackStart":"2026-01-01T00:00:00Z","lookbackEnd":"2026-01-01T00:00:00Z","items":[]}}`.

Run: `docker exec -i pumpwatch-e2e-postgres-1 sh -c 'psql -U "$POSTGRES_USER" -d pumpwatch' < "$S/e2e.sql"`, then wait with an until-loop (timeout 60 s) for `grep '^/two' "$S/received.log" | grep -c e2e-1` to reach 3. Every message carries the model version, so `e2e-1` keeps any other flags out of the counts.
Expected:
- `grep '^/one' "$S/received.log" | grep e2e-1 | grep -c GME` is `1`
- `grep '^/one' "$S/received.log" | grep e2e-1 | grep -cE 'TSLA|AMC'` is `0`
- `grep '^/two' "$S/received.log" | grep -c e2e-1` is `3`
- `docker logs pumpwatch-e2e-api-1 2>&1 | grep -cE 'pw-hook|9911'` is `0`

- [ ] **Step 7: Confirm a bad filter stops startup without echoing it**

Set `ALERTS_CHANNEL_FILTERS_0_MIN_SCORE: "xyzzy"` in the override, then run `docker compose -p pumpwatch-e2e -f docker-compose.yml -f "$S/alerts-e2e.override.yml" --profile apps up -d api`.
Expected: the api container exits. `docker logs pumpwatch-e2e-api-1 2>&1 | grep -c "min-score must be a number from 0 to 100"` is at least `1`, and `grep -c xyzzy` on the same logs is `0`.

- [ ] **Step 8: Tear down the throwaway stack**

```bash
docker rm -f pw-hook
docker compose -p pumpwatch-e2e --profile apps down -v
```

Expected: `docker compose -p pumpwatch-e2e ps -a` lists nothing, and `docker volume ls --filter name=pumpwatch-e2e` lists nothing. The owner's `pumpwatch` project is untouched.

- [ ] **Step 9: Full build**

Run: `mvn -B verify` from the repo root (about 13 minutes, so run it in the background).
Expected: `BUILD SUCCESS` for every module. Report the `Tests run / Failures / Errors / Skipped` lines for `api` and whether Docker was on.

- [ ] **Step 10: Commit anything left**

If Steps 4 to 9 changed nothing tracked, there is nothing to commit. Never commit scratch files or `.env`.
