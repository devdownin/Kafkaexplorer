# Changelog

All notable changes to Kafka SQL Explorer are documented here.

The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and the project
aims at [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

> **Releases before `v1.3.0` are listed for the record only.** This file was introduced
> after them, so rather than reconstruct summaries nobody verified, each links to its
> published release notes. Entries from `v1.3.0` onward are maintained here.

## [Unreleased]

### Removed

- **A dead `TableController`.** Its only mapping, `GET /table/{name}`, returned the view name
  `"table-detail"` — a template that exists nowhere, in an application with no template engine.
  Nothing linked to it, no client-side route matched it and no test named it, yet it took
  `/table/*` away from the SPA's catch-all, and it built `"SELECT * FROM " + name` from the path
  variable and submitted it to the query engine on an unauthenticated GET, discarding the rows
  into a model nothing rendered. A table's live endpoint is `/api/query/table/{name}`, under
  `/api` like every other domain endpoint.

### Added

- **What the Settings page is used to enter now survives a restart.** `POST /api/config` applied
  its values to two in-memory singletons and wrote nothing anywhere: the one screen whose entire
  purpose is data entry was the only one whose input did not outlive the process. The bootstrap
  address, the connection mode, the SSL paths and passwords, the Confluent Cloud credentials and
  the whole LLM configuration all reverted to `application.yml` on the next boot — and did so
  *silently*, the page then showing the YAML values as if they were the operator's own. They are
  kept in `data/settings.json` (a file rather than a Kafka topic, because these settings *contain*
  the broker address, so a topic could neither receive a save that repoints the cluster nor be
  found at boot).
  - **The environment still wins.** Precedence is environment variable / `-D` / command line, then
    the saved file, then `application.yml`, and the startup log names any setting the environment
    overrode. That ordering is what stops a file written weeks ago from overruling a
    `KAFKA_BOOTSTRAP_SERVERS` just changed in a compose file, and it is the way back out of a saved
    address pointing at a cluster that no longer answers.
  - **Only the fields actually changed are taken over**, so a default that moves in a later version
    still reaches a deployment that never touched it.
  - **Credentials are written too**, readable by the owner alone and never returned by the API —
    keeping everything except the passwords would restore the mode and the keystore path and leave
    the connection failing for a credential nothing said had been dropped.
    `EXPLORER_SETTINGS_STORE_SECRETS=false` keeps them off disk and *names* what it left out;
    `EXPLORER_SETTINGS_PERSISTENCE=false` stores nothing at all.
  - The page says which of those it is, and a save that was applied but could not be written says
    so on screen rather than under a three-second "Saved!".
- **A hand-written `CREATE TABLE` is replayed into Flink at startup.** Losing it produced no error
  but a *substitution*: the definition died with the process, the next query on that name
  auto-registered a **generated** table under it, and the query still returned rows — minus a
  watermark, a chosen subset of columns or a connector option, with nothing saying the definition
  had changed. Tables auto-registered from a topic are not stored, since they are re-derived on
  demand; what needed keeping is what somebody typed. A table can now be dropped from the schema
  browser (`DELETE /api/query/table/{name}`), because restarting used to be the only way to clear
  the catalogue and a store that could only grow would be worse than the defect it fixes.
- **`GET /api/config` returns the connection settings that are not credentials** — the truststore
  and keystore paths, the Confluent key — plus a boolean per password. Those sections of the
  Settings page could be written and never read back, so they opened empty whatever the application
  was running on.
- **A Data Model page (`/data-model`, `POST /api/data-model`) — a set of topics read as an
  entity-relation diagram.** Each topic becomes a table card carrying its inferred columns, and the
  relations between them are deduced from key-column names. Kafka has no foreign keys, so every
  edge is a claim rather than a fact: it is graded `HIGH` / `MEDIUM` / `LOW`, drawn in a line style
  that says which, and states its evidence in plain words. The key column is *detected, never
  invented* — an entity with no id-like field simply has no key, words merely ending in "id"
  (`paid`, `valid`) are not identifiers, and a name echoing its own topic is identity rather than a
  reference. Cardinality travels in crow's-foot notation so the line style is free to mean
  confidence and nothing else, and each edge is anchored on the row of the column that carries it,
  which is what makes a link legible without reading its label.
- **A relation, or a whole subgraph, opens as a query.** A `HIGH` relation *is* a join predicate,
  and the diagram is the only place in the application where that predicate is already known.
  Several entities added to a join set yield one query, built from a spanning tree so that every
  `JOIN` predicate cites a table already introduced. It refuses rather than inventing a predicate:
  a set the deduced relations do not connect has no join, and the unreachable entity is named.
- **Reading a large model**: the confidence legend doubles as a filter (each grade a checkbox with
  its count) that hides lines without rearranging the diagram; entities no relation touches are set
  aside rather than diluting it; a minimap appears only when the graph overflows the viewport; a
  "jump to an entity" search centres one by name; and a field-highlight box answers "who else
  carries this key?" with no request. A column that reads as a foreign key but produced no relation
  is flagged, so a diagram that looks incomplete says why.
- **Shareable, saveable, exportable**: the selection round-trips through the URL and replays on
  open, the unrun selection survives leaving the page, named selections are kept by the browser,
  and the diagram exports as SVG, PNG or a Mermaid `erDiagram` — the textual one for what the
  images cannot do, be re-read and diffed. Every export carries the coverage line and states what
  is *not* drawn: a diagram detached from the application cannot be interrogated, so one that does
  not state its bounds reads as a complete model.
- **Contextual KPI suggestions on the Metrics page** (`POST /api/metrics/suggestions`). The page knew
  nothing about the cluster it measures: its quick-start cards posed a `COUNT(*)` on the first table
  found, identical everywhere. Proposals are now derived from what has actually been observed — the
  cluster audit (flow hops it timed, throughput drops, duplicates, the busiest topics, consumer
  findings) and Stream Flow traces kept by the browser (per-hop latency, end-to-end completeness).
  Every card names the run and the measurement it rests on, thresholds are multiples of something
  measured and say which, and nothing is created: a card opens the editor pre-filled for a preview
  and an explicit save. With no audit and no trace the panel says nothing has been measured yet and
  links to the two pages that change that, rather than concluding the cluster needs no KPI.
- **`CONSUMER_TIME_LAG` metric template — a consumer group's backlog in time rather than in
  records.** The same 4 000 messages are four seconds of traffic on one topic and four days on
  another; only the second is actionable. The value is the age of the oldest message the group has
  not read, taken from committed offsets and record timestamps — the one template that runs no SQL,
  since neither number is in a payload. Bounded to 64 partitions and an 8 s budget, and a partition
  whose record could not be read is reported as unknown, never as zero: zero means "caught up", and
  a gauge saying so while nothing could be read silences the alert it exists to raise.
- **A Metrics screenshot, and the harness that makes it reproducible.** `docs/img/metrics.png` is
  generated like the other six, over fixtures shaped exactly as `MetricSuggestionService` produces
  them. The capture now pins the browser clock to the fixtures' instant: the README claimed a
  fixed instant was enough for a re-run to produce the same image, but every relative reading
  compared it to the real clock, so the screens aged daily and the Metrics shot grew an amber
  "62-day-old audit" banner that says nothing about the product.
- **Lineage and Process Mining feed the KPI suggestions too.** A running `INSERT INTO` job
  *declares* a pipeline edge, so it yields a gap KPI on a pair nobody had to infer — a job reading
  several sources is refused, with the reason stated, since two inputs against one output have no
  ratio worth a threshold. A validated Process Mining field mapping names each topic's real
  correlation key, which now beats the schema guess and the `id` convention on every card that
  needs one (each says which of the three it used), and its status field becomes a KPI grouping by
  status — one Prometheus series per value, no threshold, because which status matters is the one
  thing the application cannot know for you. The field-mapping cache moved out of the controller
  into a bounded `FieldMappingStore`: nothing ever evicted an entry, and the mapping was reachable
  from nowhere else.
- **The backlog in time, where the question is asked.** The Topic Explorer's Consumers tab gets a
  per-group "how long has it been waiting?" button (`GET /api/topic/{name}/time-lag?group=`), on a
  button rather than on load because it reads a record per lagging partition where the rest of the
  panel reads metadata. `explorer.lag-metrics-time` (off by default) exports the same measurement
  as `kafka_consumer_group_lag_seconds` for the watched topics — removed rather than frozen when a
  refresh cannot measure it, since an age that stops being measured gets more wrong every minute,
  unlike a count.
- **The audit dates a stalled backlog.** A STALLED finding now carries the age of the oldest
  waiting message beside its record count — the one case worth a costlier measurement, budgeted
  per run and stated in a scope note. A measurement that fails leaves the finding unchanged.
- **The demo cluster seeds consumer groups.** `setup-demo.sh` created none, so a fresh demo had
  nothing to show in the Consumers tab, no consumer finding in the audit, no delay KPI proposed
  and nothing for the lag gauges to export. Two groups on `demo.orders.1.received`: one caught up,
  one that read four records and left.
- **Stream Flow traces are kept as observations** (`kse:flow-chains`), not only as criteria: a
  completed trace records the chain it found — topics in first-sighting order, per-hop latency — so
  the Metrics page can derive KPIs from the path a key really took. Versioned envelope, seven-day
  expiry, five entries de-duplicated on the route.
- `CHANGELOG.md` (this file), `SUPPORT.md`, `.github/CODEOWNERS`, `.github/ISSUE_TEMPLATE/config.yml`,
  `.editorconfig` and `.gitattributes`.
- **CodeQL static analysis** (`.github/workflows/codeql.yml`) over Java and TypeScript, on push,
  pull request and weekly. Nothing analysed the source before: the Trivy scan in `ci.yml` reads
  the runtime image's packages, never a line of code.
- **Dependency review and secret scanning** (`.github/workflows/security.yml`): new dependencies
  are checked against advisories and against licences incompatible with AGPL on every pull
  request, and TruffleHog scans the full history for live credentials.
- **Signed releases.** The JAR now carries a Sigstore build-provenance attestation, verifiable
  with `gh attestation verify <jar> --repo devdownin/Kafkaexplorer`. It previously had only a
  checksum published on the same page as the file it describes — which answers "did this arrive
  intact", never "did this come from here". Keyless, so there is no key to store or rotate.
  `SECURITY.md` documents verification for the JAR and the image alike.
- **OpenSSF Scorecard** (`.github/workflows/scorecard.yml`), weekly and on branch-protection
  changes, publishing to the Security tab and to the public OpenSSF API — the badge is in both
  READMEs. It grades the properties no build ever fails on: whether releases are signed, whether
  actions are pinned, whether branch protection exists.

### Changed

- **The unused Lombok dependency is gone.** It was declared in `pom.xml` — with a matching
  `spring-boot-maven-plugin` exclude that existed only for it, itself redundant beside
  `<optional>true</optional>` — while being referenced by exactly zero source files. It cost an
  annotation processor on the compiler command line of every build for nothing. The `<configuration>`
  block went with it, since the exclude was all it held; the packaged JAR still carries
  `JarLauncher`, its four layers in the documented order, and leaves the plain jar as
  `*.jar.original` that the release glob depends on.
- `CLAUDE.md` named the wrong Kafka connector. It documented `flink-connector-kafka:4.0.1-2.0`
  where the pom carries `5.0.0-2.2`, and claimed the `-2.0` suffix "covers the whole Flink 2.x
  line" — it does not: the suffix names the Flink minor the connector was built against, which is
  precisely why no `-2.3` build exists and `5.0.0-2.2` is the newest published release.
- **The backend targets Java 25** (`java.version` in `pom.xml`, with `requireJavaVersion` in the
  enforcer plugin raised to match, and the CI, release and CodeQL workflows plus
  `docker-compose-build.yml` moved to a JDK 25 toolchain). The repository already contradicted its
  own documentation on this point: both runtime images have shipped on an
  `eclipse-temurin:25-jre-alpine` base for several releases and the backend builder stage on a
  JDK 26 Maven image, so the JAR was *executing* on a JVM 25 while `CLAUDE.md` and
  `CONTRIBUTING.md` stated that Flink 2.x supported "Java 17/21, **not** 25". The bump moves the
  bytecode target and the build toolchain; the runtime had already moved. The full suite passes on
  25, Flink planner path included, and no `--add-opens` is added — the two warnings that do appear
  (Flink's shaded Guava reaching for `sun.misc.Unsafe`, Testcontainers' JNA for a restricted
  `System::load`) belong to those dependencies, and a flag added pre-emptively outlives its reason.
- **Every GitHub Action is pinned to a commit SHA** rather than a mutable tag, with the version
  kept in a trailing comment. Dependabot continues to bump them.
- `SECURITY.md` now states a supported-version policy that matches reality — it claimed `0.0.1`
  was the supported release, eleven releases after that stopped being true — adds private
  advisory reporting, and documents that the application ships with no authentication.
- `CONTRIBUTING.md` documents the real gate. It told contributors to run `mvn test`, which runs
  neither ESLint nor Vitest, so a contributor could be green locally and red in CI.

- **The KPI suggestions are ranked by relevance before being capped.** The cap was there, but it
  cut the list in the order the sources happened to be consulted — audit, traces, lineage, field
  mapping — so a pipeline edge a running `INSERT` job *declares*, and a status KPI resting on a
  mapping an operator *validated*, were dropped before a routine volume count. Proposals now sort
  by what they are about, then by whether their thresholds were derived from a measurement, then by
  how few assumptions they carry; the source is only a tiebreak and the id is last, so two
  identical audits produce the same order (a browser-side dismissal is keyed on that id). Two
  smaller defects went with it: marking now precedes the cut — a proposal an existing metric
  already covered could take one of the 24 slots and push out a fresh one — and the truncation note
  counts what it dropped by kind, where it used to assert the remainder were "of the same kinds, on
  other topics", which nothing checked.
- **A finished Flink job no longer counts as running.** `getActiveJobsDetails()` handed back the
  live registry without reconciling it, where its sibling `getActiveJobs()` always did — and the
  three callers of that method are precisely the ones that act on the answer: `POST /api/config`
  refuses a cluster repoint with **409** while jobs run, the lineage graph draws a node per job,
  and the KPI suggestions derive a pipeline edge from each. So a query the operator had run and
  watched finish could go on refusing their next config save, in the name of a job that was over,
  until some other screen happened to call the sibling. The Dashboard polls that sibling every
  30 s, which is why an open browser hid the defect and why it surfaced only when a warmup probe
  ran with no browser open at all. Both halves are pinned: a finished job is dropped, a running
  one is kept — the 409 guard has to keep protecting what it exists for.
- **The planner is warmed up at startup, so the first query no longer pays for it**
  (`FlinkWarmupService`, `explorer.flink-warmup-enabled`, default on). Measured rather than
  assumed: the first SELECT of a process took **~5.5 s** against ~1.2 s warm, and the difference
  is one-off — Calcite class loading, Janino codegen, the first job graph. A throw-away
  table-less `SELECT 1` after `ApplicationReadyEvent` brings it to ~1.6 s. Both candidate probes
  were timed before choosing: an `EXPLAIN` only reaches ~3.0 s, because the cost is in code
  generation and the job lifecycle, not in parsing. It runs on a daemon thread so readiness is
  never delayed, needs no table and no reachable broker, and a failure is logged and forgotten.
- **The documentation checks now audit their own exemption lists.** `NOT_A_PATH`, `EXTERNAL` and
  `HISTORICAL` exist so that stepping around a check is a decision rather than a hole — but nothing
  made the decision expire, and a hand-maintained list only grows. An exemption nobody needs is a
  standing licence for a claim nobody is checking. Both scripts now fail on one, which is what
  `--report-unused-disable-directives` already does for this repo's ESLint directives, applied to
  the checks' own escape hatches. It found that **16 of the 37 `NOT_A_PATH` entries** had stopped
  doing anything — twelve whose prose was gone, four unreachable because `looks_like_path` rejects
  the token before the list is consulted — and that `EXTERNAL`'s only entry, `JAVA_TOOL_OPTIONS`,
  had been redundant for as long as both runtime images have set it as an `ENV`. All removed;
  `EXTERNAL` is now empty and says why. Deliberately **not** reported: a `NOT_A_PATH` entry whose
  token would resolve as a real path — half that list is generated or gitignored, so the verdict
  would depend on whether a build had run, green on a clean checkout and red on a developer's tree.
- **`verify-offline.sh` derives the JUnit console version instead of pinning it.** It carried a
  hand-written `CONSOLE_VERSION="6.0.3"` beside a JUnit that Spring Boot's BOM resolves — in step
  today, by hand, with nothing holding them together. The day Boot bumps JUnit, the harness would
  run a launcher of a different version from the engines on its classpath, which is the kind of
  local-only failure that CI cannot reproduce and nobody can diagnose. The version now comes from
  `junit-platform-commons-<ver>.jar` on the resolved test classpath — platform and Jupiter share
  one version from JUnit 6 on — so the drift cannot happen rather than being reported after the
  fact. The pin remains as a fallback, and a divergence is announced rather than applied silently.
- **The two deprecated Kafka test APIs are gone**, and with them the last compilation warnings the
  build emitted from this project's own code. `MemberDescription`'s five-argument constructor is
  deprecated **for removal** — four of its five overloads are, leaving only the nine-argument one,
  so the call site is spelled out with the accessor names beside each argument rather than left as
  an unreadable row of `Optional.empty()`. And `KafkaAdminServiceTimeLagTest` was the last place
  still passing the deprecated `OffsetResetStrategy` enum to `MockConsumer`; the other three tests
  in the tree already used the `String` overload it now uses too.
- **`docs/check-config-table.py` resolves the dependency versions the documentation states in
  prose or in a badge** against `pom.xml` — Flink, Spring Boot, `kafka-clients`, `io.confluent`,
  `flink-connector-kafka`, `anthropic-java`, and the Java and Kafka badges. This is the class of
  claim that rots most quietly here, and it had been caught three times by reading rather than by
  CI; writing the check found the third itself, a section documenting `anthropic-java 2.16.1`
  against a pom on `2.53.0` — since fixed independently in `ed308e4`, so what lands here is the
  guard rather than the correction. Claims are **enumerated, not discovered**: a blind scan for
  version-shaped numbers would
  flag React 19, JUnit 5 and "Kafka 2.1+ brokers", and a check with false positives is one people
  learn to ignore — so the run prints how many it resolved, making an unlisted claim visibly
  unchecked rather than silently blessed. Abbreviations resolve by prefix at a component boundary
  ("Flink 2.3" against `2.3.0`) and never by fuzzy match, which is what keeps `1.18` from
  resolving against `2.3.0`. Prose that describes the past on purpose is exempted by name in
  `HISTORICAL`, and `DOCKER-AUDIT.md` is excluded entirely, being a record of what was fixed.
- **`docs/check-config-table.py` also resolves the Java badge** against `<java.version>` in
  `pom.xml`. A shields.io badge is static — the version is hand-written text in the URL path,
  derived from nothing — so it drifts exactly as quietly as the base-image line the script was
  written for. Both halves are checked, the alt text and the URL, since they are two copies of
  one number and either can be edited alone. The Kafka badge is deliberately left out:
  `kafka.version` is `4.3.1` where the badge reads `4.3_KRaft`, so checking it would need a
  fuzzy-match rule, and a check that blesses two different values teaches nothing.
- **`docs/check-doc-paths.py`** resolves every repository path that `CLAUDE.md` and
  `CONTRIBUTING.md` name in prose. `check-links.py` only ever saw markdown *links*, and these two
  files refer to the codebase in backticks — so three references rotted unnoticed.

### Changed

- **The API contract check covers more of the surface**: 9 hand-written interfaces became 20
  records verified against `domain/*.java` — `TopicSearchResponse`, `QueryInitResponse`,
  `MetricConfig`, then the whole `AuditReport` family (`TopicAudit`, `TopicIssue`, `FlowAudit`,
  `StepInfo`, `HealthStatus`, `AuditStatus`). `AuditReport.globalStats` is typed
  `Record<string, unknown>`, which is what the Java record promises; the page's much richer
  reading of those keys is a convention written by `AuditService`, not a contract, so it is
  narrowed explicitly in one commented line of `Audit.tsx` rather than asserted in the shared
  type. `check-api-types.py` also read only the first record per file, which made a nested one
  (`FlowAudit.StepInfo`) invisible and reported the correct declaration as an error. The anonymous response shapes declared at call sites — the exact pattern
  that killed the Compare page — now live in `api/types.ts`, and three literal duplicates under
  other names (`SchemaInfo`, and local copies of `TopicSearchResponse` and `MetricConfig`) are
  aliases or imports, so there is one shape per endpoint. `check-api-types.py` now accepts a
  string-literal union where Java declares `String`: widening the frontend to `string` to satisfy
  the script would have deleted real type safety in the name of a check that exists to provide it.

### Added

- **The Process Mining field mappings are persisted** to `internal.field.mappings`, keyed by
  mapping id. It was the only artefact this application produces by *correcting a model* and then
  threw away: a restart lost every mapping, the KPI suggestions reported one they could no longer
  resolve, and getting it back meant replaying two model calls to re-derive something an operator
  had already fixed by hand. The restore is bounded and best-effort — driven by the end offsets, an
  unreadable record costs that record and not the restore, and a broker that cannot be reached at
  startup leaves an empty store and a log line rather than a boot that hangs.
- **A "this screen needs a wider window" notice on the SQL editor**, under `lg`, naming the screens
  that do work at that width and what each answers. The page was not broken in the usual sense —
  nothing overflows, nothing overlaps — it was unusable without saying so, which is worse: the
  operator concludes the application is down. It dismisses, and the dismissal sticks.
- **The Metrics page says when the audit its proposals rest on has moved on.** The panel derived on
  page load and never again, so an audit run in another tab left thresholds computed from the
  previous run without a word. It now distinguishes the three cases that call for different
  gestures: a run in flight (not evidence yet — the server refuses a `RUNNING` report), a first
  audit (which unlocks cards that did not exist), and a newer run (which replaces what the
  thresholds rest on), with a re-derive button beside the sentence.

### Fixed

- **A tablet in landscape was worse off than a phone.** At exactly 768 px the shell's navigation
  stopped being an off-canvas drawer and took 256 px in the flow, while the SQL editor's schema
  browser kept its fixed 288 px — so Monaco fell from 64 px of rendered width at 640 px to **5 px**
  at 768. Nobody had chosen that; two independent width decisions met. The shell's threshold is now
  `lg`, and the measured effect is 768 → 192 px and 900 → 324 px.
- **`layout-probe.mjs` printed a ceiling where a measurement was expected.** Its list of clipped
  containers was cut to eight entries *before* being counted, so five of seven pages reported
  "8 clipped" at every viewport width, and `MOBILE-LAYOUT-SCOPE.md` read that constancy as evidence
  that the clipping was width-independent truncation by design. The probe now counts the whole set,
  excludes `sr-only` (which clips by construction), and reports whether each clipped element's
  remaining content can be reached at all.
- **Truncated values with no way to read them.** Following from the above: a metric card's name,
  its description and its SQL line all carried `truncate` with no `title` anywhere — 280 px of
  metric name rendered into 147 px, with the rest unreachable — and the Cluster page's property
  names overflowed a grid cell with neither ellipsis nor title. The codebase's own convention is
  that a compacted value keeps its exact form in a `title`; these had not followed it.
- **A failed container launch could fail a release.** `KafkaClusterIntegrationTest` started its
  Testcontainers broker with no startup retry, and a launch that failed — twice in twelve hours on
  hosted runners, once on a pull request and once on a push to `main` — took the whole `mvn verify`
  down with it, its own assertions never having run. Survivable on a pull request, where a re-run
  costs minutes; not on `release.yml`, which gates a tag on the same `verify`. One retry on the
  launch, and a startup timeout sized for a cold image pull. The retry deliberately does not cover
  the assertions: a broker that started and then misbehaved is a finding.
- **The KPI suggestions read every running Flink job on every load of the Metrics page.** Resolving
  a statement is a Flink parse taken under the runtime's read lock, and the lineage family was the
  only one of the five that was not capped. It now resolves the 12 most recently started
  `INSERT INTO` jobs and counts the rest in a note — by start time rather than in map order, since
  `getActiveJobsDetails()` returns a `Map.copyOf` whose iteration order would have made the jobs
  read vary between two calls.
- **A template metric took the whole Metrics page down.** Its `sql` is `null` by construction —
  the parameters are the query — and `MetricCard` called `metric.sql.replace(…)` on it, so the
  page rendered its error boundary instead of the metric. The type said `string`, which it had
  never been. Found by the screenshot harness, pinned by the page's first component test.
- `POST /api/metrics/suggestions` had no test, on a body that is optional and a record that grew a
  component after it shipped — the exact binding failure `StreamFlowControllerTest` exists to
  catch. `MetricControllerTest` pins the four shapes the browser and a hand-written call produce.
- **Three dead references in `CLAUDE.md`.** `AUDIT.md` and `CONSUMER-GROUPS-AUDIT.md` were
  described as documents to read before refactoring, and `deploy/kraft-platform/` was cited in a
  rule about `container_name`; all three had been deleted from the tree, two of them months
  earlier. The findings they carried are kept in prose, now with the commit that removed each
  report so the reasoning is still reachable.
- `pom.xml` carried template placeholders: `<url>` and all three `<scm>` entries pointed at
  `github.com/yourusername/kafka-sql-explorer`.
- The SPDX licence header the project mandates was missing from 18 Java test files and from all
  113 frontend sources, which ship inside the AGPL-licensed jar and image.
- `package.json` declared no `license`, `description`, `repository` or `author`.

## [1.7.0] — 2026-08-14

See [the release notes](https://github.com/devdownin/Kafkaexplorer/releases/tag/v1.7.0).

## [1.6.3] — 2026-08-13

See [the release notes](https://github.com/devdownin/Kafkaexplorer/releases/tag/v1.6.3).

## [1.6.2] — 2026-08-13

See [the release notes](https://github.com/devdownin/Kafkaexplorer/releases/tag/v1.6.2).

## [1.6.1] — 2026-08-12

See [the release notes](https://github.com/devdownin/Kafkaexplorer/releases/tag/v1.6.1).

## [1.6.0] — 2026-08-12

See [the release notes](https://github.com/devdownin/Kafkaexplorer/releases/tag/v1.6.0).

## [1.5.2] — 2026-08-10

See [the release notes](https://github.com/devdownin/Kafkaexplorer/releases/tag/v1.5.2).

## [1.5.1] — 2026-08-10

See [the release notes](https://github.com/devdownin/Kafkaexplorer/releases/tag/v1.5.1).

## [1.5.0] — 2026-08-09

See [the release notes](https://github.com/devdownin/Kafkaexplorer/releases/tag/v1.5.0).

## [1.4.0] — 2026-08-09

See [the release notes](https://github.com/devdownin/Kafkaexplorer/releases/tag/v1.4.0).

Note for the record: this tag published **no container image**. An expired Docker Hub token
failed the publish job before anything was built, taking the GitHub Container Registry push
down with it even though that registry was reachable and authenticated. `release.yml` now
treats Docker Hub as strictly optional — a refused login degrades the release to GHCR alone
and emits a warning.

## [1.3.0] — 2026-08-09

See [the release notes](https://github.com/devdownin/Kafkaexplorer/releases/tag/v1.3.0).

---

## Earlier releases

These predate both this file and the tag-naming policy that `release.yml`'s `guard` job now
enforces. The drift is visible in the names themselves — `0.0.1` and `0.0.2` carry no `v`,
`v1.1` is not semver, and a `V1.3` was once pushed in uppercase and silently matched no
workflow trigger at all, since tag filters are case-sensitive. Tags are now validated before
a release builds anything.

| Tag | Date | Notes |
| --- | --- | --- |
| [`v1.1`](https://github.com/devdownin/Kafkaexplorer/releases/tag/v1.1) | 2026-03-23 | "release 1" — two-component tag, so no versioned image tag was published |
| [`v0.0.3`](https://github.com/devdownin/Kafkaexplorer/releases/tag/v0.0.3) | 2026-03-23 | |
| [`0.0.2`](https://github.com/devdownin/Kafkaexplorer/releases/tag/0.0.2) | 2026-03-12 | Audit services and demo scripts |
| [`0.0.1`](https://github.com/devdownin/Kafkaexplorer/releases/tag/0.0.1) | 2026-03-10 | Initial pre-release |

[Unreleased]: https://github.com/devdownin/Kafkaexplorer/compare/v1.7.0...HEAD
[1.7.0]: https://github.com/devdownin/Kafkaexplorer/compare/v1.6.3...v1.7.0
[1.6.3]: https://github.com/devdownin/Kafkaexplorer/compare/v1.6.2...v1.6.3
[1.6.2]: https://github.com/devdownin/Kafkaexplorer/compare/v1.6.1...v1.6.2
[1.6.1]: https://github.com/devdownin/Kafkaexplorer/compare/v1.6.0...v1.6.1
[1.6.0]: https://github.com/devdownin/Kafkaexplorer/compare/v1.5.2...v1.6.0
[1.5.2]: https://github.com/devdownin/Kafkaexplorer/compare/v1.5.1...v1.5.2
[1.5.1]: https://github.com/devdownin/Kafkaexplorer/compare/v1.5.0...v1.5.1
[1.5.0]: https://github.com/devdownin/Kafkaexplorer/compare/v1.4.0...v1.5.0
[1.4.0]: https://github.com/devdownin/Kafkaexplorer/compare/v1.3.0...v1.4.0
[1.3.0]: https://github.com/devdownin/Kafkaexplorer/releases/tag/v1.3.0
