# Changelog

All notable changes to Kafka SQL Explorer are documented here.

The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and the project
aims at [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

> **Releases before `v1.3.0` are listed for the record only.** This file was introduced
> after them, so rather than reconstruct summaries nobody verified, each links to its
> published release notes. Entries from `v1.3.0` onward are maintained here.

## [Unreleased]

### Added

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

### Fixed

- **A template metric took the whole Metrics page down.** Its `sql` is `null` by construction —
  the parameters are the query — and `MetricCard` called `metric.sql.replace(…)` on it, so the
  page rendered its error boundary instead of the metric. The type said `string`, which it had
  never been. Found by the screenshot harness, pinned by the page's first component test.
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
