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

- **Every GitHub Action is pinned to a commit SHA** rather than a mutable tag, with the version
  kept in a trailing comment. Dependabot continues to bump them.
- `SECURITY.md` now states a supported-version policy that matches reality — it claimed `0.0.1`
  was the supported release, eleven releases after that stopped being true — adds private
  advisory reporting, and documents that the application ships with no authentication.
- `CONTRIBUTING.md` documents the real gate. It told contributors to run `mvn test`, which runs
  neither ESLint nor Vitest, so a contributor could be green locally and red in CI.

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
