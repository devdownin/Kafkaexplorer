# Changelog

All notable changes to Kafka SQL Explorer are documented here.

The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and the project
aims at [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

> **Releases before `v1.3.0` are listed for the record only.** This file was introduced
> after them, so rather than reconstruct summaries nobody verified, each links to its
> published release notes. Entries from `v1.3.0` onward are maintained here.

## [Unreleased]

### Added

- **A Flink job's own record is on screen.** The store keeps a `statusDetail`, an `errorMessage`
  and a dated history of every transition; the summary the dashboard receives drops all three, so a
  red `FAILED` badge had its reason only in `data/flink-jobs.json` — and `GET /api/query/jobs/{id}`,
  which serves them, was called by nothing. The job card opens onto them, **on the click and once**:
  the dashboard polls every five seconds by default, so a read per card per turn would make everyone
  pay for a detail nobody is looking at, and folding then reopening must not re-ask. A read that
  fails leaves the server's reason under the card rather than in a toast — it is what you came to
  read. `UNAVAILABLE` is described there as a status that could not be read, never as an ending.

### Changed

- **The live job registry is `heldJobs`, and its accessor `getHeldJobs()`.** "Active" meant two
  different things on two methods a caller has to choose between: `getActiveJobs()` answers from the
  *store*, filtered on `isTerminal`, while this map is the `JobClient`s the runtime holds right now.
  Its two populations have two lifetimes and the field says so — a Job-mode submission stays for as
  long as it runs, a synchronous read only for the duration of its HTTP request, which it must,
  since that is how `POST /api/query/cancel/{queryId}` finds it.
- **The job store is bounded in count as well as in time, and the count is the bound that binds.**
  `explorer.flink-job-retention-hours` is right for what the file was written for — Job-mode
  submissions, a handful a day — and wrong for what feeds it: every statement the planner runs as a
  job, one per Run in the editor and ~2 900 a day per planner-answered metric. `MAX_RETAINED_JOBS`
  (200) drops the oldest **ended** jobs first and never a running one. Measured, because both bounds
  had been argued rather than counted: a write serialises the whole list, so it costs 111 µs at 10
  records and **17.3 ms at 10 000** — a 5.3 MB file — where the count bound puts the ceiling at
  436 µs.
- **A status poll that changed nothing no longer rewrites the store.** Its caller is a five-second
  poll and a running job answers `RUNNING` every time, so the whole file was being rewritten, once
  per live job, to record a fact about the clock.

### Fixed

- **A status that could not be read was filed as a job that had ended.** The generic catch in
  `buildJobSummary` reported a status call that ran out of its 150 ms as `UNKNOWN`, which the store
  counts as *terminal* — so one slow answer stamped an `endedAt` on a running job, dropped it out of
  the dashboard's list and put it on the retention clock, where a long `INSERT INTO` could be pruned
  out of the store while it ran. `STATUS_UNAVAILABLE` is that third state and is not terminal; the
  branch above it, which really does mean the job is over, is unchanged.
- **Cancelling a job that had already ended erased how it ended.** With no live `JobClient` the
  fallback wrote `UNKNOWN` over the record, so pressing **Kill** on a dashboard card whose job
  finished between two five-second polls destroyed the only account of how it finished. The attempt
  is recorded as a history entry under the status that was actually observed; the verdict is left
  alone.
- **A synchronous read never handed its job back.** It stayed in the registry until some *other*
  caller ran the status sweep — which on a headless deployment is nobody, while the metric refresh
  loop adds one every 30 s for any metric the planner answers, each holding a `JobClient`. It is
  released when the request returns; the cancel endpoint still finds it *while* the query runs.
- **Retention only applied while something else was writing.** The prune ran on the way to a write,
  so a store nothing wrote to again kept its expired records for good and handed them back at the
  next boot. A prune that removed something now reaches the file, `load()` included.
- **The job store was written in place.** It is read at boot, which is exactly when an interrupted
  write from the previous run surfaces — as a file that will not parse, and so as the loss of every
  job record rather than of the one being written. It goes through `JsonStoreFile` like the two
  stores beside it: replaced atomically where the filesystem allows it, and owner-only.

### Security

- **A statement refused in Flink Job mode had its credentials written to disk and served back.**
  `submitJob` rejects a non-INSERT statement and files it *first*, so a hand-written `CREATE TABLE`
  pasted into the editor with Job mode selected put its SSL passwords and its Confluent
  `sasl.jaas.config` secret into `data/flink-jobs.json` and into `GET /api/query/jobs`. Everything
  else that returns or logs DDL goes through `DdlGeneratorService.maskSensitiveProperties`; this
  store was the exception, and it is masked at the door now — nothing here replays the SQL, so it
  costs nothing.


### Added

- **`explorer.consumer-pool-size`** (**0**, disabled) — the per-topic record readers can borrow a
  byte-array consumer instead of building one per read. Constructing a `KafkaConsumer` is a connect,
  an ApiVersions exchange and a metadata fetch, plus a handshake and an authentication round trip on
  a TLS or SASL cluster, and the audit reads once per topic. It ships **off**, which is a statement
  about evidence rather than about the code: the saving's size depends on the deployment, nothing
  here has measured it against a real cluster, and a pooled client handed out carrying a previous
  read's assignment or paused partitions answers the wrong question rather than answering slowly.
  A lease is exclusive, reset before it is handed on, and never returned by a read that threw.

### Changed

- **Reading a topic costs a fraction of what it did.** Four reads answered from records what the
  broker holds as metadata, or read the same thing twice.
  - **The dashboard's "Last Message" column no longer reads a record.** It assigned every non-empty
    partition of every topic on the cluster, seeked each to `end - 1` and polled until each had
    answered — and a fetch returns the *batch* the record sits in, up to `max.partition.fetch.bytes`
    (1 MiB by default) per partition, so a three-partition cluster of three hundred topics dragged
    the tail of nine hundred partitions across the network every 30 s to render relative dates.
    `ListOffsets(maxTimestamp)` states it from the broker's own index: one request, no consumer, no
    record. It is also the better measurement — the old code read the *last* record, whose timestamp
    is the newest only when producers stamp in order. A broker older than Kafka 3.0 does not
    implement the spec and takes the previous route, which is kept whole.
  - **`getLatestMessage` reads one partition instead of all of them.** The same spec says which
    partition holds the newest record and at which offset, so the read no longer fetches the tail of
    every partition to discard all but one.
  - **The record counts behind the dashboard's size column open no consumer.** They read only
    offsets, so the consumer was pure overhead; both bounds go through the admin client, issued
    before either is awaited so they overlap, and awaited per partition so one that does not answer
    costs its own contribution rather than the batch.
  - **The Topic Explorer opens on one read of the topic, not three.** `GET /api/topic/{name}`
    sampled the topic for format detection, sampled it again for the identical records for schema
    inference, then read the messages to display. The shared-sample overloads the audit uses have
    existed for exactly this; the one endpoint a user waits on was left on the un-shared path.
    Inference now reads the records the page displays, capped at `explorer.inference-sample-size`,
    which also stops the schema and the DDL describing records the panel below is not showing.
  - **An audited topic is read once, not twice.** The poison check's ten-record sample now comes
    out of the records the duplicate scan already holds, rather than a second read of the same end
    of the same topic — and only when both are reading the same end, since
    `audit-duplicate-scan-from: EARLIEST` points the scan at the oldest records, which answer a
    different question from the one every other check asks.
  - **`readRecord` places a record with one round trip rather than two**, and **`deserializeValue`
    keeps one Avro writer per schema** instead of building one per record — the audit drives that
    path ten thousand times for a single topic's duplicate scan.
  - **The dashboard's two bulk reads share one `describeTopics`** instead of each making their own.
  - **The per-topic record fetchers dropped a `describeTopics` round trip each**, taking their
    partition list off the consumer that is about to read anyway; **`drain()` and the Process Mining
    snapshot read stopped asking twice for end offsets** their callers already hold; and both, like
    the topic search, now **pause a partition they have finished with** — the fetcher keeps a
    request in flight for every assigned partition, so a drained one holds each poll for
    `fetch.max.wait.ms` while the partitions that still have records are already back.

### Fixed

- **A topic search could report `exhausted` with records still in flight.** `reachedEnd` compared
  `consumer.position(tp)` — the client's *fetch* position, which advances as responses are buffered
  rather than as records are delivered — against the end offsets. It reads the cursor the response
  already carries, which is the rule `TopicReadCursor` states and the third place this defect has
  been found.
- **An internal read could create a topic on the cluster under exploration.**
  `allow.auto.create.topics` defaults to *true*, so asking a consumer about a topic that does not
  exist creates it, with one partition and the broker's defaults. Every internal consumer is
  configured with it off, beside the group id and the commit switch that
  `ExplorerConsumerGroups.configure` already sets together.
- **One unknown topic emptied the dashboard's whole table.** Both halves of it — the record counts
  and the last-message timestamps — described their topics through `allTopicNames()`, which fails
  wholesale when any name is unknown, and a topic deleted between the `listTopics` that named it and
  the call that uses it is an ordinary race on a live cluster. They now share the per-topic-futures
  loop the activity sparkline had already been fixed with, so a stale name costs its own row.

### Fixed

- **A page refresh on Compare or Help answered 500.** `CompareController` mapped `GET /compare`
  returning the view name `"compare"` and `HelpController` did the same for `/help` — there is no
  template engine, so both shadowed `SpaController` and produced a circular-view-path error. It is
  the defect `StreamFlowController`, `ConfigController` and `TableController` were each fixed for;
  these two survived because nothing tested them.
- **A shared link to a topic answered 404.** `SpaController`'s patterns exclude a dot from the last
  segment — which is what lets `/assets/index-a1b2c3.js` reach the resource handler — and Kafka
  topic names contain dots, every one of the demo cluster's included. So `/topic/demo.orders.1.received`
  matched nothing and fell through: opening a shared Topic Explorer link, or pressing F5 on it, was
  broken for essentially every topic. `/topic/**` is now mapped explicitly, which fixes it without
  loosening the rule that keeps the asset paths working.
- **An unmapped API path answered the SPA instead of 404.** `SpaController`'s
  `/**/{path:[^\.]*}` matches any dotless path, `/api/` included — it only ever lost to a *mapped*
  endpoint on specificity — so `GET /api/topics`, the plural of the real `/api/topic`, came back
  **200, as HTML**, with `index.html`. A renamed or mistyped endpoint therefore reached axios as
  HTML and failed inside `JSON.parse` on `<!doctype`, which names neither the route nor the mistake.
  `unmappedApi` maps `/api/**` and throws a 404; measured against `PathPatternParser`, that pattern
  outranks the catch-all and is outranked by every mapping under `/api/` in the package, literal and
  path-variable alike, so it shadows none of them.
- **The guard for that rule could not fail on it.** `theApiIsNotForwardedToTheSpa` asserted on
  `/api/metrics`, which `MetricController` maps — so it proved a specific mapping beats the
  catch-all, which was never in doubt, while `SpaController`'s javadoc and `CLAUDE.md` both stated
  the stronger rule. It is asserted on an **unmapped** path now, at two depths.
- **The Lineage failure test could not distinguish the failure it names.**
  `reports a failed load instead of leaving the previous graph passing for current` asserted only
  `No lineage data available` — byte-identical to the empty-result case two tests above — and never
  rendered a successful graph first, so it passed against a page that silently kept stale data. On
  an error the page does not clear `data`; it toasts. The test now loads a graph, fails the reload,
  and asserts both halves: the error is reported *and* the previous graph is still on screen. The
  cancellation and sequencing the file's own header advertises are covered too, the sequencing half
  by resolving a superseded request last.
- **`@PathVariable` / `@RequestParam` names were missing from four more controllers.**
  `LineageController`, `MetricController`, `ProcessMiningController` and `QueryController` carried
  eleven unnamed ones between them — `QueryController` included, which the previous entry for this
  had credited as already following the convention. Under `verify-offline.sh`, which compiles with
  plain `javac` and no `-parameters`, those handlers fail at *request* time; Maven and CI are
  unaffected, which is why a partial pass fixed nothing an operator would notice.
- **`POST /api/audit/start` refused a partial body.** `AuditOptions` carried six primitive
  `boolean` components, so `{}` — or a body naming only `topicPrefix` — failed with a 400 quoting a
  Jackson internal, on an endpoint that accepts no body at all. A boxed `@JsonCreator` binds it
  now; an absent flag means the check runs, so `{}` means what sending nothing means.

### Added

- **`SpaRoutingTest`**, which boots the real web layer and walks the router of `App.tsx`: every
  client-side route must forward to `index.html`, and no path under `/api/` may. It replaces a guard that
  was spread over three controller tests as 404 assertions — each proving only that *that*
  controller does not map its own route, leaving `/audit`, `/lineage`, `/cluster` and `/compare`
  covered by nothing. It found the three defects above on its first run.
- **`AuditControllerTest` and `TopicControllerTest`**, the two largest endpoint families that had
  no test, pinning the status codes whose whole purpose is to keep two answers apart.

### Added

- **A component test for the four pages that had none** — `Cluster`, `Lineage`, `StreamFlow` and
  `ProcessMining` — closing the gap the Audit and Topic Explorer tests opened. Nineteen cases, each
  aimed at a decision the repository already documents and nothing verified: a group the
  application left on the cluster is marked rather than hidden; a lineage graph built partly by
  guessing says so, since a missing edge and an absent dependency look identical; a shared Stream
  Flow link replays its trace on open, and falls back to the non-streaming endpoint when the
  response body is not readable — a path production never takes; and Process Mining keeps
  "the profiling did not happen" apart from "it ran and found nothing", which send the operator to
  opposite places. Each was verified to fail against the defect it describes before being kept.

### Added

- **The Metrics page is split**, from 2 272 lines — the largest file in the tree — to 1 249, into
  `components/metrics/MetricCard.tsx`, `components/metrics/TemplateParamsEditor.tsx` and
  `pages/metricsEditor.ts` for what renders nothing. Same precedent as `components/query/`, and the
  same order: the page's 29 test cases existed before the split, not after. Nothing was rewritten
  in the move; the whole suite and a real production build were run after each of the three steps.
- **A component test for the Audit and Topic Explorer pages**, which had none. Both reunite the
  two failure modes this repository has already paid for: a hand-written response shape trusted at
  runtime (the Audit page narrows `globalStats` from a `Map<String, Object>` by a cast on a
  convention nothing verifies), and a page state that used to render a reassuring verdict from a
  run that measured nothing. Six cases and five, each pinned against a documented past defect —
  and the FAILED-run case was verified to fail against the defect it describes.
- **A first measurement of the shipped Process Mining prompt** (`theShippedPromptHoldsItsFormat`,
  in the opt-in `llm-eval` suite): how often it comes back in the format it asked for, on the model
  actually configured. `PROCESS-MINING-LLM-SCOPE.md` records the bilingual prompt as a suspicion
  nobody had measured; this is the half that can be measured without building anything. An English
  variant is deliberately not shipped to compare against — that would be a second production
  surface built on a belief, and a prompt that holds its format needs no variant.

### Fixed

- **An incomplete topic response took the whole Topic Explorer down.** The render dereferences
  `data.samples.length` and `data.schema` on every pass, and `samples` is the very field whose
  shape change killed the Compare page. Normalised once on the way in rather than guarded at each
  dereference; what is missing becomes empty, never invented. Found by the new page test.

### Changed

- **The compose tree went from sixteen files to twelve, and from five ways of starting the app
  to one.** `docker-compose.yml`, `-kafka4`, `-llm`, `-spectra` and `.release` were all *base*
  files, each carrying its own copy of the explorer service, and being separate bases cost more
  than duplication: Schema Registry and Ollama **could never run together**, because two bases
  cannot be layered. There is now one base at the repository root and everything else in
  `compose/`, as overlays that combine freely.
  - `docker-compose-kafka4.yml` → `compose/schema-registry.yml`. The old name advertised a
    choice that had stopped existing — every stack here runs Kafka 4.3 in KRaft, the base file
    included — on the stack the README recommends. What it adds is a Schema Registry.
  - `docker-compose-llm.yml` → `compose/ollama.yml`; `docker-compose.release.yml` →
    `compose/image.yml`, which now *pulls* the published image rather than rebuilding it
    locally from a downloaded JAR. That image is already published for both architectures,
    keylessly signed, with an SBOM and SLSA provenance, so `update.ps1` pulls it too.
  - `docker-compose-spectra.yml` is **deleted**; `compose/spectra-hub.yml` is the SpectraLLM
    stack. The deleted one needed a sibling SpectraLLM checkout, a Maven build and an npm
    build, and was the only file in the tree whose syntax CI could not resolve without
    fabricating a stub of somebody else's repository.
  - `docker-compose-spectra-hub.small.yml` is **deleted** in favour of four `.env` lines: every
    value it set already had an interpolated default in the stack file, so it expressed nothing
    a variable could not.
  - `compose/dev.yml` was the last verbatim copy of the broker — sixty lines of KRaft settings
    duplicated so that one thing could differ, the name of the data volume. It `extends:` the
    base now, with `volumes: !override` replacing the mount, and keeps its existing volume.
  - `docker compose config` is **unchanged** for every stack, with one deliberate exception:
    the explorer no longer waits on `schema-registry: service_healthy`, which contradicted the
    rule that the app depends on the broker and nothing else.
  - `docs/check-image-pins.py` now asserts the Explorer pin is the **same** in every file that
    carries it (two do), and `docs/check-doc-paths.py` gains a `RETIRED` list for a path the
    tree deliberately no longer has — it expires both when nothing cites it and when the file
    comes back, where `NOT_A_PATH` would have called a deleted file "not a path".
  - `CONTRIBUTING.md` gains a map of the compose files — which are bases, which are overlays
    (an overlay alone is not a stack and fails by construction), how they combine, and where
    relative paths resolve.

### Added

- **The three topics this application keeps its own state in are created, with the retention
  policy each one needs** (`InternalTopicProvisioner`, `explorer.internal-topic-provisioning`,
  on by default). Nothing here had ever created them: they were auto-created by the broker on
  the first write, and therefore with its defaults. That was wrong for all three.
  `internal.metrics.config` and `internal.field.mappings` are **keyed stores**, replayed by key
  at startup with only the last record per key mattering — the definition of a compacted topic —
  and both got `cleanup.policy=delete` plus whatever `retention.ms` the broker had, so a metric
  configured eight days earlier was deleted by the broker and the next restart came up without
  it, silently. `FieldMappingStore` said as much in its own warning ("The topic is meant to be
  compacted — check that it is"): the requirement was documented and enforced nowhere. And
  `internal.audit.history` is append-only and nothing ever trimmed it, so what bounded it was
  whichever retention the broker happened to apply — unbounded growth on one cluster, a "Past
  runs" list that stops a week back on another; `explorer.audit-history-retention-ms` (30 days,
  `0` to leave the broker's own alone) is a decision somebody took. It also removes the
  dependency on `auto.create.topics.enable`, which the bundled stacks set to `true` with a
  comment naming these very topics and which most production brokers turn off — there, the three
  writes simply failed. Scoped to this application's own topics, so a configured
  `explorer.internal-topic-prefix` is followed and nothing of the user's can be named; partitions
  and replication are left to the broker's defaults. An existing topic whose policy differs is
  **reported, not corrected** — it may have been configured deliberately —
  with `explorer.internal-topic-reconcile` (off) as the opt-in that fixes it, which is what a
  deployment upgraded from an auto-creating build wants once.

- **`docs/check-readme-parity.py`** — README.md against README.fr.md. It compares the four
  things a translation must not change and a one-sided edit always does: the sequence of
  heading levels, the number of fenced code blocks and the lines in each, the set of URLs,
  and the set of paths named inside backticks. Never the words — a shorter French paragraph
  is a better translation, not a defect. It exists because the duplicated section below sat
  in **both** files for an unknown length of time with nothing reading either. All four
  comparisons were verified to fail against a deliberately broken tree.
- **`docs/check-doc-paths.py` now reads five documents instead of two.** `README.md`,
  `README.fr.md` and `docs/DOCKERHUB.md` join the two maintainer files: 99 paths resolved
  where 80 were, and the Docker Hub page in particular is rendered *outside* the repository,
  so a wrong path there is invisible to anyone reading the repo. The seven references this
  surfaced were all legitimate non-paths (build platforms, an OpenRouter model shape, files
  the app creates at runtime under its gitignored `data/` volume) and are named in
  `NOT_A_PATH`, which expires its own entries.
- **`COMPOSE_FILE` is documented in `.env.example`** and pointed at from both READMEs, so the
  two-`-f` commands the reorganisation introduces cost nothing to anyone who wants a bare
  `docker compose up -d`. Written as an indented example rather than a knob to uncomment,
  deliberately: no compose file reads it — the CLI does — and its separator is `:` on Linux
  and macOS but `;` on Windows.

### Fixed (documentation)

- **The "Build and Development" section of both READMEs appeared twice, verbatim** — 23
  duplicated lines in each, unrelated to the change above and removed with it.
- The hot-reload stack was invoked with `docker-compose` (the end-of-life v1 binary) in both
  READMEs; `update.ps1` used it too. All now use `docker compose`.
- The "start the broker only" step named a stack file that added services it then did not
  start. It is `docker compose up -d kafka` — the base file carries the broker.

### Fixed

- **The two startup restores no longer steer by `consumer.position()`.** `MetricService` and
  `FieldMappingStore` each read their topic to its end offsets, comparing against the consumer's
  position — which is not a consumption watermark but the client's *fetch* position, advanced as
  responses are buffered in the background rather than as records are returned. Measured against
  the demo broker, one poll delivered two records while `position()` reported the log end for all
  eighteen partitions, so the loop could exit with records still in flight and log "Restored N" as
  though N were the total: metric configurations and field mappings silently missing at boot. It
  is the same defect already found and fixed twice here, in `KafkaAdminService.drain` and in
  `KafkaSnapshotReader`, each time with a private cursor; `TopicReadCursor` is now the one
  definition, seeded from where the read seeked and advanced only by records it was handed, and
  the two earlier copies delegate to it.

- **Deleting a metric writes a real tombstone.** It wrote a `MetricConfig(id, …, "DELETED")` — a
  record with a non-null value. The restore reads that correctly, so the metric did disappear from
  the page; what could never happen is compaction, which reclaims a key only on a null value. On a
  compacted topic every metric ever deleted stayed on the log for good, and "deleted" meant
  "hidden". The sentinel is still understood on the way in, since a topic written by an earlier
  build is exactly what the store exists to read.

- **The field-mapping topic is bounded like the store that reads it.** Every validation in Process
  Mining mints a *fresh* UUID, so that topic gained one new key per click and kept it for ever:
  compaction cannot reclaim a key that is never rewritten, and nothing had ever deleted one. A
  store bounded at 200 entries in memory and unbounded on disk is also incoherent in a way that
  shows — a restart resurrects mappings the running process had already stopped answering for. An
  entry the in-memory bound drops is now tombstoned; a *restore* is a replay and tombstones
  nothing, or every boot on a long topic would answer with a write storm against the store it is
  reading.

- **The cluster audit no longer audits this application's own topics.** It listed every topic and
  filtered only on the prefix an operator asked for, so `internal.metrics.config` — a keyed store,
  therefore full of repeated keys — was reported as a topic with duplicates, and the run wrote its
  own report to `internal.audit.history` while that topic was being read. It is the same defect
  `ExplorerConsumerGroups` removed for the consumer groups, the app reporting its own leftovers as
  findings, left standing for the topics. Naming their prefix explicitly still audits them, and
  what was left out is stated in `globalStats.scopeNotes` rather than silently narrowed.

- **A whole-cluster Stream Flow trace skips all of the explorer's own topics, not two of them.**
  The exclusion was a `Set.of()` naming `internal.audit.history` and `internal.metrics.config` by
  hand, so `internal.field.mappings` and the demo stack's `internal.demo.seeded` were traced — and
  a configured `explorer.internal-topic-prefix` moved the names out from under the literals.
  `ExplorerConfig.isInternalTopic` is the one answer to that question now, on both call sites.

- **The leftover-group cleanup runs on an interval, not once at startup**
  (`explorer.cleanup-own-groups-interval-ms`, one hour; `0` restores the old behaviour). Every
  bundled stack runs the application with `restart: unless-stopped`, so a deployment that stays up
  for months tidied up once, months ago — while the groups it removes keep being produced, a
  Process Mining live session leaving an empty group behind each time one ends. A pass costs one
  `ListGroups` and, normally, no deletion at all, and a failed pass no longer cancels the schedule.
  When the per-pass cap has to cut, it now removes the naming schemes that predate the group prefix
  first: those are the accumulated backlog no build running today can recreate, where the previous
  cut was alphabetical over identifiers that are UUIDs — a random cut dressed up as an order.

- **A Process Mining snapshot read no longer returns a random subset of the topics it was asked
  for.** Three separate faults, each hiding the next, and one symptom: profiling reported one topic
  of six as holding messages and the rest as empty, with the analysis then concluding exactly that.
  `seekToLatestN` clamped to offset `0` instead of the partition's beginning offset — on a log
  retention has trimmed, that position is out of range, `auto.offset.reset` (`latest`, in this
  mode) applies and the read jumps to the end, so a full topic reads as an empty one. Termination
  was then decided by an empty poll, which a fresh consumer very often returns while metadata
  resolves: three runs of one identical request sampled 1 topic, then 0, then 6. And the offsets it
  was made to steer by were `consumer.position(tp)`, which is the client's *fetch* position and
  runs ahead of what has been delivered — one poll handed over two records while it reported the
  log end for all eighteen partitions. The loop now keeps a per-partition cursor of its own,
  advanced only by records it was actually given, and says at DEBUG what it is about to read and
  what it collected: every one of the three ended at the one loop exit that logged nothing, which
  is what made them indistinguishable.

- **The audit's own record scan had the same defect.** `KafkaAdminService.drain()` compared
  `position(tp)` against the end offsets, and both of its callers assign every partition of the
  topic — which is how the audit samples one, for duplicate detection, poison detection, format
  inference and schema inference alike. A topic with enough partitions truncated the sample and the
  audit reported a confident number about records it never saw. Single-partition reads masked it.

- **A snapshot read's silence budget applies in the case it was written for.** The 5 s net exists
  for one topic being served normally beside a partition whose leader has gone away — but every
  poll counted as activity, including the ones carrying nothing but records of a topic already at
  its per-topic quota, which are discarded on arrival. The clock therefore never expired and the
  read waited out the whole 30 s wall clock, on the Process Mining hot path, spending the rest of
  its fetch bandwidth on records nobody would keep. The clock is now restarted by a record the read
  *kept* rather than one it was handed, and the partitions of a topic that has its quota are
  paused, so those polls mostly stop happening. Measured: 30 000 ms before, the silence budget
  after.

- **A missing end offset no longer inverts a timestamped read.** On the branch that means "every
  record in this partition predates the instant asked for, so read nothing from it", the seek
  target was `endOffsets.getOrDefault(tp, 0L)` — and a broker that answered partially therefore
  sent the read to offset `0`, returning the whole partition, every record of it older than the
  filter. The exact opposite of what the branch is for, from a measurement that was simply
  missing. The partition is now seeked to its end lazily and left out of the read's cursor, so it
  carries no bookkeeping entry rather than a wrong one. Both copies of the line are fixed —
  `KafkaSnapshotReader.seekToTimestamp` and `KafkaAdminService.getRecentRecords` — and in the
  reader's mode `auto.offset.reset` is `earliest`, so skipping the seek would have landed in the
  same wrong place.
  The same default sat on the `LATEST_N` branch beside it with a different symptom: that read is
  measured backwards from the end, so an end offset of `0` made the clamp collapse to the
  beginning and a partition answered a question about its *newest* records with its oldest —
  a wrong answer rather than a missing one.

- **A profiling run that did not happen is no longer reported as one that found nothing.** Every
  failure path — no API key, an unreachable endpoint, an unparseable answer — answered 200 with an
  empty topic list and the reason among the warnings, which is the same shape as a genuine
  profiling of topics that hold no messages. The two send you to opposite places: one is a cluster
  to go and look at, the other an endpoint, a model or a key to go and fix. `FieldProfileResult`
  now carries `error`, the distinction `ProcessMiningResult.error` already drew for the analysis
  half of the same pipeline, and the page acts on it.
  The model does not get to set that field: it is a record component and the answer is bound with
  unknown keys ignored, so a model volunteering an `"error"` beside twelve good profiles would have
  had its run reported as a hard failure and the profiles thrown away. It is stripped on the way
  out of the parser, the way the token accounting is attached rather than parsed — `error` is a
  fact about the call, not something the answer may assert about itself — and whatever the model
  wanted to say survives in `warnings`.

- **An answer that never arrived says the model ran out of budget.** A small reasoning model that
  spends its whole output allowance deliberating returns a 200, a well-formed body and no content;
  the old wording — *"LLM response choice carried no message content"* — sent an operator to check
  the endpoint, the model name and the key, all of which are correct. Three answers now, and the
  difference is what may be asserted: `finish_reason: "length"` is the provider stating the cap was
  hit, so the message says so and names `claude.max-tokens`; tokens generated with no content is
  the same symptom without that confirmation, reported as the counts it is; neither, and the old
  wording stands, because a gateway that omits the reason must not be paraphrased into one. Blank
  content counts as absent — every caller parses the answer as JSON, so an empty string only moves
  the failure one step on.

- **A 4xx a routing gateway relayed from an upstream provider is named as such.** On OpenRouter one
  model is served by several providers, the choice is made per call and is not announced, and that
  provider's failure comes back under the gateway's status. Read as an ordinary 400 it produced
  *"the endpoint rejected the request body"*, which sends you to audit a body that is provably
  fine: the byte-identical request failed through one provider and succeeded through another
  minutes later. The message now names the provider that failed and says another attempt may route
  elsewhere — and, the half that costs more, such a refusal is **no longer remembered against the
  model**, where it used to disable schema-constrained decoding for the client's whole lifetime on
  the strength of one provider's bad afternoon.

- **...and it no longer re-seeds on every start when it could not check.** The canary check reads
  offsets, and a read can fail — a broker still settling, a CLI absent from a different image.
  Counted as "the topic is empty", that looks like the safe direction and is not: it is paid at
  every `up` rather than once, and each replay adds a generation of duplicate records to a dataset
  the audit's duplicate detection and the Stream Flow traces are calibrated against — the exact
  damage the marker exists to prevent. The check now has four answers instead of two: `populated`
  and `empty` are measurements, `absent` means the topic itself is gone (topics do not expire,
  records do), and `unknown` means the question could not be asked, in which case the marker — which
  is evidence a seed once succeeded — stands, with the message naming the command that forces a
  re-seed. The topic list is read once for both questions and retried; if it cannot be read at all
  the script seeds nothing and says why, since it cannot see the marker either and a compose
  one-shot runs again at the next `up`.

- **The demo seeder no longer skips for ever once retention has emptied what it seeded.**
  `internal.demo.seeded` is a topic, and a topic never expires; the records it vouches for do. A
  stack brought back up past that point came back with eighty topic names, no records in any of
  them, and a seeder that skipped every time — while the dashboard, the health probes and the topic
  list all looked normal. The documented way out made it worse: `docker compose down -v` wipes
  every volume of the project, the explorer's own data volume and the settings entered on the
  Settings page included. The marker now answers "has this been seeded before" and a canary topic
  answers "is the data still there", and only both together mean there is nothing to do.

### Security

- **A stored API key no longer follows the LLM endpoint to a different host.** `POST /api/config`
  accepts any base URL with no validation and guards the key with `containsKey`, so a body naming a
  new host and omitting `llmApiKey` repointed the deployment while leaving the credential in place
  — and the next `test-llm` sent it there, reflecting up to 300 bytes of the answer back to the
  caller. Two unauthenticated calls, and the caller needed no key to begin with. It is the same
  defect CodeQL called critical on the candidate probe, one door over, where the taint crosses a
  persisted bean between requests and the scanner does not follow it. "An operator may repoint this
  application" and "anyone may have its credentials" are different statements, and only the first
  is what `SECURITY.md` accepts as a deployment constraint. The rule compares the **host** — a
  changed port or path is the same endpoint — and covers a derived move too, since switching
  provider fills in a new default base URL. What was cleared is named in `credentialsCleared`
  rather than done silently.

### Added

- **`GET /v1/models/user` is consulted when a model lookup fails**, and only then. An org key
  restricted to part of the catalogue is refused with the same 404 as a mistyped slug, and those
  send an operator to two different places. The per-key list takes no filters, so one generous page
  is read and a slug missing from a list that filled the page is reported as **inconclusive**
  rather than absent: `null` means the question could not be answered, and only an exhaustive list
  yields `false`. The UI shows the note on `false` alone.

- **The Test button says what is left on the API key** (OpenRouter). The credit was consulted
  nowhere: a key running out answers 402, read correctly by `remedyFor` but only *after* an analysis
  has failed. It is published, so the press that already asks what the model can do asks this too,
  under the same rules — best-effort, no retry, never on the analysis path. It is also the number
  `claude.session-cost-limit-usd` was missing, that cap shipping disabled because any figure would be
  arbitrary while nothing says what the budget is. Three states are kept apart, and the third is the
  one that matters: a key with **no limit** has a known usage and no remainder, because "unlimited
  minus what you spent" is not a number — rendering it as `0` would announce an exhausted key to
  somebody who has an unlimited one.

- **`claude.openrouter-max-price-usd-per-million`** — the most this deployment will pay a provider,
  refused at the routing layer rather than counted afterwards. It is the enforceable half of
  `session-cost-limit-usd`: that one accumulates and stops the session after the fact, so an
  expensive route has already been paid for by the time it notices. One value against both published
  prices, in the unit the catalogue publishes and the picker renders. `0` and off by default, on the
  rule its two siblings follow — a model every provider prices above the ceiling becomes unroutable,
  arriving as the same "no endpoints found" a restrictive data policy produces.

- **A 404 from OpenRouter now names all three things it can mean** and points at the button that
  tells them apart. It conflates a mistyped slug, a slug that no longer exists, a key not entitled to
  the model, and — since the routing policy exists — a model no provider serves under it.
  Deliberately *not* a catalogue lookup from the analysis path: that read is a considered gesture,
  and a live session that had begun failing would otherwise make one request per window.

- **`docs/verify-openrouter-contract.py`** — checks the requests this application makes against the
  live API. Deliberately not a `docs/check-*.py`, because those are discovered and run by CI and
  this one needs the network and a key. It exists because the filter and sort parameter names came
  from a published schema rather than an observed response: the parsing has unit tests, the request
  had nothing, and a parameter the gateway does not recognise is ignored rather than refused — so
  its first assertion is that the filters actually narrow the catalogue. Documented in
  `CONTRIBUTING.md`.

  It now covers the **completion** request too, behind `--chat` because those are real billed calls
  (sixteen output tokens on a cheap model). That body is where the same exposure sits and where it
  costs the most: `provider` and `usage` ride on the assumption that an unrecognised field is
  refused rather than ignored, and it is the wrong way round — an ignored field costs nothing and
  says nothing, so a privacy restriction that never applied and an accounting field that never came
  back both look exactly like success. The assertion that can change what the client sends is
  `usage.cost`: `LlmUsage.costUsd` reads it and `claude.session-cost-limit-usd` is enforced from it,
  so if it has to be asked for, every cost shown on this application's own default provider is null
  while the live session tells the operator *the provider reports none* — false about OpenRouter
  rather than merely unknown. The script settles that question in one run; it has not been run from
  the environment this was written in, where `openrouter.ai` is unreachable.

  It also asserts that `provider.max_price` **binds** rather than merely being tolerated — a ceiling
  below every published price must refuse the route, since a 200 there would mean the setting
  promises a bound it does not deliver — and it reports what `/models/user` actually is relative to
  `/models`. That last one is the question deciding whether the model shortlist may mark the rows a
  key cannot reach; it does not mark them today, for exactly that reason, since labelling a usable
  model unusable is a worse lie than the silence it would replace.

### Fixed

- **A rate limit is now waited out on the server's schedule instead of the backoff.** A 429 was
  handled like a 5xx — 500 ms, then 1 s, then give up — which spends all three attempts inside a
  second and a half, shorter than any rate limit worth the name. The caller was told "call failed
  with status 429" about a request that would have been accepted a few seconds later, having been
  refused three times to get there. `Retry-After` is read in both its legal forms and OpenRouter's
  `X-RateLimit-Reset` after it; the wait is bounded by 30 s and by the call's own timeout, and a
  delay beyond that is refused immediately *naming the delay*, since nothing here can shorten it. A
  gateway that sends no such header behaves exactly as before.

- **The model shortlist no longer offers, at the top, the models the default policy cannot route.**
  It is sorted cheapest-first, so free models come first — and under the shipped
  `openrouter-data-collection: DENY` those are usually the unroutable ones, the free endpoints being
  the ones that train. The first rows proposed were the ones that answer 404, explained only
  afterwards by `explainRoutingRefusal`. They now carry the caveat, keyed on a published price of
  zero (a real measurement) rather than on the `:free` suffix (a naming convention), and only when
  that policy is the one actually running.

- **The Settings page no longer draws a form over a response it never received.** The load effect
  swallowed its failure in an empty `catch` — commented "Backend may not expose REST config yet",
  which stopped being true long ago — set `loading` false, and rendered a complete-looking
  configuration having read nothing. That is the unverified claim this codebase keeps removing,
  left standing on the one screen whose whole purpose is data entry, where typing over an unknown
  baseline produces a save nobody can predict. It is an error panel with a retry now.

- **A model slug is refused when you save it, not at the first analysed window.** Syntactic and
  OpenRouter-only: it checks the shape of an address the gateway can resolve, never that a model
  exists — that is what Test is for. Elsewhere a model name is whatever the endpoint accepts.

- **One unknown topic no longer costs a whole Process Mining read.** `KafkaConsumer.partitionsFor`
  answers `null` for a topic that does not exist, `.stream()` on it threw, and the catch around the
  read returned an empty list *for every topic in the request*. So one deleted topic — or one typo
  in a selection of eight — produced a profiling run and an analysis of nothing at all, logged as
  an error nobody reads and rendered as a cluster with no messages in it. A topic with no metadata
  now costs that topic, is named, and the read continues.

- **A Process Mining analysis states what it was able to look at.** A flowchart and a list of
  anomalies cannot say what they rest on, and three things silently reduced the scope: topics that
  hold nothing, topics that do not resolve on the cluster, and — the invisible one — topics read in
  full whose records never fit the prompt's character budget, which was written *into the prompt*
  for the model and nowhere on screen. A run over eight topics where two never reached the model
  rendered exactly like one over eight, so the model's silence about them read as a finding about
  them rather than as the absence of a question. `ProcessMiningResult.coverage` now carries what
  was read and what of it reached the model, per topic, and the page states it above the diagram —
  on a failed analysis too, where "none of the selected topics held a message" and "the model did
  not answer" send an operator to opposite places.

- **A read that came back with nothing is no longer sent to the model.** Both steps of the pipeline
  built a prompt of headings and paid for an answer about the absence of data — an invented
  pipeline, or a mapping proposal about topics nobody had shown it, which the next step then
  invites the operator to validate. The reason is stated instead, and it names which of the three
  empties it was.

- **The Process Mining page says when the validated field mapping was not applied.** The mapping is
  what step 3 exists to produce — it is what says which field correlates a record across topics —
  and the store that holds it is bounded and restored best-effort at boot, so it can legitimately
  be gone by the time an analysis or a live session asks for it. That was said to the log and to
  nobody else, so the analysis ran on whatever the model inferred while the screen looked exactly
  like the one that had been configured.

- **A Process Mining request that names no topic is refused where it arrives.** It used to reach
  the read as `null` and come back as a 500 that said nothing about the missing field. The refusal
  travels in the same record shape the page already reads on a 200, so a validation failure and a
  provider failure land in the same panel. Topic names in that controller's logs are sanitised like
  every other one, which two of its three endpoints were not doing.


### Added

- **A model shortlist on the Settings page, cheapest first.** Choosing an OpenRouter model meant
  recalling a slug and typing it into a bare text box. The gateway filters *and* sorts its own
  catalogue, so "which models can do this job" is one request of about twenty rows rather than a
  download of several hundred: text output, schema support, and a context window large enough for
  this deployment's own prompt budget plus its answer — every filter a fact the application already
  knows about itself. Lazy and behind a button, since nothing whose only product is a form
  convenience belongs at page load. The criteria travel with the list, because a filtered view
  presented as "the models" is the same lie as a silently truncated one, and "we could not read the
  catalogue" is kept distinct from "nothing matches".

- **What a window would cost, per row.** Published per-token prices times the prompt budget and
  `claude.max-tokens`. It is labelled a **projection** wherever it appears, and that is not
  pedantry: every other money figure here is *read* from the provider precisely because no price
  table lives in this application, whereas this one is a multiplication over the same deliberately
  optimistic token floor, so it can understate. Half a published price yields no figure rather than
  a cheaper-looking model, and a free model shows `$0.00`, which is a real measurement.

### Changed

- **Test LLM no longer applies the form.** It began with `POST /api/config`, so *trying* a model
  repointed the running deployment and, with `explorer.settings-persistence` on, wrote it to
  `settings.json` — exploring and committing were the same gesture, which is why comparing two
  models was never worth the risk. The probe now carries the form's provider, base URL, key and
  model in its body and the server builds a throw-away configuration from them: no bean mutated,
  nothing reaching the settings store. The answer carries `candidate`, so "reachable" does not
  claim to describe the deployment when it describes something else.

  **Only the model is overridable, and that is the security boundary rather than a simplification.**
  A first draft let the body carry the provider, base URL and key as well. That is a server-side
  request forgery on an application with no authentication — the client hands the response body
  back to the caller in its error message — and, because a blank key falls through to the
  configured one, a single call that changed no state and left nothing on disk would have posted
  the operator's API key to any host. The endpoint and the credential now always come from the
  saved configuration; repointing the deployment stays the job of `POST /api/config`, which is
  deliberate, validated and persisted. The shortlist endpoint takes no connection parameters for
  the same reason, and the accepted cost is stated beside the Test button: a provider changed in
  the form has to be saved before the probe and the list describe the new endpoint.

- **The Settings page reads the shipped defaults from the server instead of restating them.**
  `Config.tsx` carried `openai/gpt-4o-mini` twice and a table mirroring `defaultBaseUrl` beside it
  — the mirror-drift pattern this codebase keeps removing, and one that bites the day a shipped
  default moves: the form offers one model while the engine runs another. `GET /api/config` now
  serves the base URL and model of every provider, and the form's initial base URL and model are
  empty, which is the true value: the page sits behind a loading guard, so nothing is displayed
  before the server has said what is actually in force.


- **The Test LLM button says what the configured model can do, not only that it answered.**
  Everywhere else in this application a model's capabilities are found out by provoking a failure:
  the schema latch learns a model refuses `response_format` from the 400 it returns, and a slug
  that cannot emit text is discovered on the first analysed window — reported as the same 404
  OpenRouter uses for a mistyped name, which sends an operator to check a model name that was
  correct. OpenRouter publishes all of it per model, so one small `GET /v1/model/{author}/{slug}`
  on that provider turns four guesses into facts: the model emits text, its schema support, its
  context window against the prompt budget, and whether reasoning can be turned off.
  The one worth the most is invisible to the running code — `response_format` and
  `structured_outputs` are listed **separately**, and a model with the first and not the second
  *accepts* the schema and ignores it, raising no error, so nothing latches and the deployment
  believes decoding is constrained when it is not. Hence four grades rather than a boolean, and
  hence nothing acting on any of it: the operator is told and picks, the same restraint
  `structured-output: AUTO` already applies to an endpoint it does not know. Read only when the
  button is pressed, only against OpenRouter's own host, with no retry and a short deadline; a
  lookup that fails names why and never changes the reachability verdict beside it. Wire field
  names come from the published SDK schema, `openrouter.ai` being unreachable from the build
  environment.

- **The prompt budget is checked against the model's context window, on the one provider that
  publishes it.** `CLAUDE.md` states that the window belongs to the endpoint and nothing here can
  check that the 120 000-character budget fits — true of every provider but OpenRouter, whose
  catalogue reports `context_length`. The comparison includes `claude.max-tokens`, since the
  answer is generated into the same window, and is stated as a **floor**: the four-characters-per-token
  ratio is the deliberately optimistic one `docs/check-compose.py` already uses, so a budget it
  passes may still not fit while one it rejects certainly does not. The word is in the sentence
  the page renders, because without it the phrase would promise what only the model's tokeniser
  can decide. An over-long prompt is usually truncated in silence rather than refused, which is
  why this is worth saying before a call instead of diagnosing after one.

- **`reasoningTokens` on `LlmUsage`** (`completion_tokens_details.reasoning_tokens`), the
  symmetric breakdown to the cached-prompt figure. It is a breakdown of the output tokens, not an
  addition to them, so what it buys is the *explanation* of a cost: two analyses with identical
  answers can differ several-fold and nothing else on screen would say why. It matters here
  because reasoning models are routine on this path — `LlmJsonSupport` exists to strip their
  traces — and because it turns a diagnosis into a measurement: the case where a model spends its
  whole output budget thinking and never reaches the JSON is already reported, but only once the
  run has failed; this shows the budget being eaten on a run that succeeded. Nullability reads the
  other way round from the cache figure — `0` is the ordinary case, a real measurement meaning the
  model did not deliberate — so the UI shows it only above zero. The field name was taken from
  `@openrouter/sdk`'s own zod schema rather than assumed, `openrouter.ai` being unreachable from
  the build environment.

### Fixed

- **Four documents still described the LLM setup as it was before OpenRouter became the default.**
  The provider guide, the Docker Hub page and both READMEs were updated with the change itself;
  these were not, and three of them are published. `docs/FEATURES.md` listed the providers without
  OpenRouter; `docs/architecture.md` did the same in its context diagram *and* still implied the
  boundary it draws is only crossed by choice, which stopped being true the day the default became
  a hosted gateway; the website (`docs/index.html`) advertised "Claude, a local Ollama, or
  SpectraLLM"; and `docs/LLM_OPEN_SOURCE_GUIDE.md` — reachable from the website, so a page a
  visitor lands on — still presented the local setup as the norm rather than as the option that
  keeps everything on your machine. Its note on constrained output also predated the per-model
  latch. The website's "LLM Guide" card now opens `LLM-PROVIDERS.md`, the maintained entry point,
  which links the open-source guide as its deeper dive: one door instead of two that drift.

- **Three pages claimed the published-images stack needed no checkout.** `docs/DOCKERHUB.md`
  was corrected when the entrypoints moved into `scripts/spectra-hub/`; `README.md`,
  `README.fr.md` and `docs/LLM-PROVIDERS.md` still said "no checkout" flatly, which was never
  true — that stack has always mounted the demo seeder from the repository. They now say what
  is true: no SpectraLLM checkout, nothing built, and this repository is needed. The Docker Hub
  page also stopped promising it "in one command" above a snippet that is four, and gained a
  troubleshooting entry for the symptom of downloading the file on its own — Docker creates a
  *directory* where a bind-mount source is missing, so the seeder is handed a directory instead
  of a script.
- **The SpectraLLM stack was described as having nine services.** It has eleven, and
  `…limits.yml` bounds the seven long-running ones — the four it leaves out are one-shots that
  exit. The figure was wrong in `CLAUDE.md`, in `docs/LLM-PROVIDERS.md` and in this file's
  unreleased notes. No check catches a claim of that kind; it was found by counting.

### Changed

- **OpenRouter is now the default LLM provider**, replacing Ollama at
  `http://localhost:11434/v1`. That default only ever worked in one situation — a developer
  running this application outside a container with Ollama installed on the same machine —
  because inside every image published here `localhost` is the container, where no Ollama runs,
  so the shipped default answered a connection refused to itself. A default is what the largest
  number of people meet first, and this one takes a key and nothing else:
  `OPENROUTER_API_KEY=sk-or-v1-…`. `claude.model` follows to `openai/gpt-4o-mini`, and
  `claude.base-url` to `https://openrouter.ai/api/v1`.

  **It is a hosted endpoint, so the message digests Process Mining builds now leave the host by
  default**, and that is stated rather than implied — in `application.yml`, on the Docker Hub
  page, in both READMEs, in the provider guide and on the Settings banner, which reads it off the
  resolved address rather than the provider's name. A deployment that must keep everything
  in-house sets `CLAUDE_PROVIDER=OLLAMA` or `SPECTRA`; `docker-compose-llm.yml` and the SpectraLLM
  stacks name their provider explicitly and are unaffected. One thing the move fixes in passing:
  the 120 000-character prompt budget and the shipped provider finally agree, where the budget was
  previously sized for a hosted API the default was not.
- **The profiling call's cost is counted.** The pipeline makes two model calls and only the
  second reported anything: the figure on screen understated every run. `FieldProfileResult`
  carries its `usage`, the page shows it, and the total now covers the whole run — the rule
  already enforced between live windows, applied between the two steps of one pipeline.
- **A live session can be given a spend limit** (`claude.session-cost-limit-usd`, `0` = off). It
  calls the model on every window for up to twelve hours, so a tab left open overnight is on the
  order of a thousand analyses — free while the shipped provider was a local Ollama, a real bill
  now that it bills per token, and bounded by nothing. Off by default on purpose: any figure would
  be arbitrary, and what makes that defensible is that the running total is now on screen, so a cap
  can be chosen from a measurement. It bounds a session's analyses, not the profiling call before
  them, and where a provider reports no cost the session says the limit cannot apply rather than
  counting calls it cannot price. Reaching it stops the session through its own event, not through
  `ANALYSIS_ERROR`: a budget doing its job is not a broken analysis, and the page renders it in
  amber beside the error, never in its place.
- **How much of a prompt was served from the provider's cache is reported** (`cachedInputTokens`,
  from `prompt_tokens_details.cached_tokens`), beside the tokens and the cost. A measurement, not
  a promise: nothing here claims a saving, and `0` — a genuine miss — is distinguished from a
  provider that counts nothing. No cache breakpoint is sent yet; that only pays once the stable
  part of the prompt is a long enough prefix, which is a prompt-restructuring decision rather than
  plumbing, and this is the number that will say whether it was worth making.
- **Both pages that call a model now say what becomes of the message content.** The question was
  answered by halves: Settings spoke only when the news was good — `DENY` displayed its restriction
  while `ALLOW` fell back to a generic "remote inference" line, so the one setting that *widens*
  exposure was the one that showed nothing — and Process Mining, the page where the content
  actually leaves, said nothing beyond "digests are sent to this endpoint". One tested module
  (`pages/llmPolicy.ts`) now produces the sentence for both, in four cases: it stays on this host,
  no retention (enforced), retention allowed, or governed by the endpoint. Two rules hold it. A
  policy is asserted only where it is **enforceable** — OpenRouter imposes it at the routing layer,
  while on Anthropic, an arbitrary gateway or a remote Ollama this application can neither impose
  nor observe one, and says exactly that rather than guessing. And it describes what the deployment
  **enforces**, never what a model **declares**: the second would be a third-party claim rendered
  as our own verdict.
- **What an analysis cost in money is shown, not just in tokens.** `LlmUsage` carried token counts
  and a duration; OpenRouter prices every response and that figure was being dropped — on the
  provider now shipped by default, which bills per token. It is **read, never derived**: no price
  table lives in this application, so a figure on screen is one the provider stood behind, and a
  provider that prices nothing (the OpenAI API, Ollama, SpectraLLM) shows nothing rather than a
  zero. Process Mining renders it beside the tokens for the last window and as a running session
  total, and a session containing one unpriced call reports **no** total instead of one that
  understates the bill.
- **`claude.openrouter-data-collection: DENY` — the routing layer answers "where does my data go".**
  Until now the Settings banner could say a deployment was remote and no more: what an upstream
  vendor does with a Kafka message digest is outside anything this application can observe.
  OpenRouter enforces it at its own layer, so the shipped configuration restricts routing to
  providers that do not retain or train on what is sent, and the banner states that property
  instead of merely warning. The cost is stated where it is paid: a model served only by
  data-collecting providers stops being routable, and since the gateway reports that with the same
  404 it uses for a mistyped slug, the error names the setting — otherwise an operator checks a
  model name that was correct all along. `ALLOW` widens the choice of models back.
  `claude.openrouter-require-parameters` is the sibling knob and is deliberately **off**: it would
  make structured output a routing guarantee, but a model whose providers lack it becomes
  *unroutable* rather than degrading, and that arrives as "no endpoints found" — not as the 400 or
  422 the per-model latch can act on.
- **A 4xx from the model now names the thing to go and change.** Every client error read "check
  base URL, model and API key", which on a metered gateway is three things that are all fine: a 402
  is an account out of credit or past a spending cap, and a 403 a moderation or permission refusal.
  Both now say so, with the provider's own words still following — that is the half that says which
  cap or which guardrail. 401, 404 and 413 get the same treatment.
- **`OPENROUTER_API_KEY` is read, so a key set under the name its own provider documents is not
  silently ignored.** `claude.api-key` is bound through a placeholder, so `StoredSettingsInitializer`
  has to be told which environment variables name it — and it knew exactly one,
  `ANTHROPIC_API_KEY`. With OpenRouter as the default, a key exported under the obvious name would
  have been outranked by a stored one: the identical defect that single alias was added to fix, on
  the identical field. The chain is now `${OPENROUTER_API_KEY:${ANTHROPIC_API_KEY:}}`, and
  `CLAUDE_API_KEY` outranks both — the unambiguous form on a machine that exports several.
- **A model that refuses a JSON Schema no longer disables constrained decoding for the next
  one.** The "this endpoint does not implement `response_format`" latch was one flag per client,
  and a client outlives a model change — `LlmClientProvider` fingerprints provider, base URL and
  key, and the model is in none of them. That was survivable while every provider was one
  endpoint serving one model, and is exactly wrong on a routing gateway: OpenRouter puts hundreds
  of models behind one base URL and one key, only some of which (served by only some upstream
  providers) support schemas, so the first schema-less model tried would have run the whole
  deployment unconstrained from then on, silently. The refusal is now remembered against the
  model that provoked it, which is what lets `OPENROUTER` join `ANTHROPIC` and `OLLAMA` in the
  `structured-output: AUTO` set at all.
- **The published-images stack is now smoke-tested on the pull requests that touch it.** It ran
  on `main` only, because it pulls ~2 GB to exercise a deployment file whose content does not
  move with the code — right for every pull request, wrong for the handful that edit those
  files, and the cost was measured rather than guessed: five of six consecutive `main` runs were
  red on that job, every failure found *after* a merge. A `hub-changes` job decides from a plain
  `git diff`, and it is a job with an `if:` rather than a `paths:` filter on purpose: `paths:`
  makes a job skip, and a required check that skips blocks a merge for ever.
- **A HuggingFace outage can no longer redden the default branch.** The smoke test downloads its
  model from `huggingface.co`, and a red `main` meaning "that host was unavailable" is
  indistinguishable from one meaning "the stack broke". A failed *transfer* now warns and skips
  the end-to-end assertion; every other failure of the fetcher still fails the job — the outcome
  is read from the fetcher's own message rather than its exit code, since a mismatched digest is
  a substituted file and not a network problem. Same rule `check-image-pins.py --published`
  already applies to the registry: "we asked and it is stale" and "we could not ask" are
  different answers.
- **The CI model is pinned by digest**, observed on two independent downloads before being
  written down. A substituted or truncated file is refused rather than served to the assertion,
  and the fetcher's verification branch — what `SPECTRA_*_MODEL_SHA256` exists for — stops being
  code CI never executes.
- **The documentation checks are discovered rather than listed** (`for check in
  docs/check-*.py`). Six `- run:` lines meant a seventh script would have been run by nothing
  until somebody remembered to add one — the same structural argument `compose-lint` was
  rewritten for, left standing one job below it.

### Removed

- **A dead `TableController`.** Its only mapping, `GET /table/{name}`, returned the view name
  `"table-detail"` — a template that exists nowhere, in an application with no template engine.
  Nothing linked to it, no client-side route matched it and no test named it, yet it took
  `/table/*` away from the SPA's catch-all, and it built `"SELECT * FROM " + name` from the path
  variable and submitted it to the query engine on an unauthenticated GET, discarding the rows
  into a model nothing rendered. A table's live endpoint is `/api/query/table/{name}`, under
  `/api` like every other domain endpoint.

### Added

- **OpenRouter as an LLM provider for Process Mining** — and, see below, as the *default* one.
  One key in front of most hosted vendors, which is the cheapest way to try several models
  against your own topics without an account per vendor. It
  speaks the OpenAI `/chat/completions` API verbatim, so it deliberately has **no client class of
  its own** — what is specific to it lives in the configuration: a default base URL of
  `https://openrouter.ai/api/v1`, and a key that `isApiKeyRequired()` treats as mandatory, since
  an anonymous request there is a 401 and a blank key is a deployment that cannot analyse
  anything rather than "optional credentials" as on a local Ollama. Requests carry OpenRouter's
  two attribution headers (`HTTP-Referer`, `X-Title`) naming this project, sent to OpenRouter
  alone and saying nothing about the deployment, the cluster or the messages.
- **`docs/check-compose.py` — the compose files, against `.env.example` and against
  themselves.** `.env.example` exists so that changing where a stack is published does not mean
  editing six compose files, which only holds if it lists them all: five variables had a default
  in compose and no line there (`SPECTRALLM_DIR`, `SPECTRA_JAVA_OPTS`, `LLM_EMBED_MODEL_NAME`,
  `LLM_EMBED_PARALLEL`, `LLM_EMBED_EXTRA_ARGS`), and nothing noticed — `check-config-table.py`
  resolves `application.yml` and the Dockerfiles and never reads a stack. The five are now
  documented and the gap cannot reopen. The reverse is checked too, a documented knob no stack
  reads being an invitation to set a value that changes nothing. And a third pass is not
  documentation at all: it asserts that `PROCESS_MINING_PROMPT_CHAR_BUDGET` fits the window the
  stack serves — the whole context for Ollama, the context divided by `--parallel` slots for
  llama.cpp. Those two halves are written in three files, each carrying a comment saying it is
  "kept in step" with the others, and nothing executes a comment; a prompt past the window is
  dropped in silence and logged at DEBUG rather than refused.

### Changed

- **The compose-lint combinations are generated from a declaration instead of hand-listed.**
  Eighteen command lines became eight lines saying, per base, which overlays it accepts — and
  nineteen combinations come out, the extra one being `docker-compose.yml` with *both* its
  overlays layered together, which the hand-written list had never covered. A declaration rather
  than a rule read off the file names, which was the tempting version and does not work: the
  names do not say that `docker-compose.limits.yml` serves four bases, nor that
  `docker-compose.release.yml` has an overlay's name and is a base, so deriving from the
  convention would have silently dropped three combinations and misclassified a stack. The guard
  that fails on a compose file no combination covers is now structural — a file is checked
  because it is named in that declaration.
- **The three shell entrypoints of the published-images stack moved to
  `scripts/spectra-hub/`.** Compose interpolates `${…}` inside a YAML entrypoint, so every shell
  variable had to be written `$${…}`: around forty escapes, where writing a single `$` yields an
  empty string at runtime rather than an error — a defect class with no symptom. The compose file
  loses 111 lines and the shell becomes shell. The cost is stated where it is paid: that stack
  needs this repository checked out. It already did — it mounts the demo seeder — but
  `docs/DOCKERHUB.md` claimed otherwise and documented a `curl -O` of the single file, which
  leaves Docker creating directories where those files should be; the page now says `git clone`.
- **Three more stacks stopped carrying their own copy of the broker.** `kafka`,
  `kafka-data-init` and `demo-setup` were restated verbatim in `docker-compose-kafka4.yml`,
  `docker-compose-llm.yml` and `docker-compose.release.yml` — 235 lines that `docker compose
  config` reported byte-identical to `docker-compose.yml`'s, each with its own copy of the
  reasoning beside it. They `extends:` that file now, which changes no command: `extends` reuses
  a single service and does not turn a stack into an overlay. `docker compose config` resolves
  the same project for all three, character for character. `docker-compose-dev.yml` stays out
  (its named volumes shadow the bind mounts on purpose) and so does
  `docker-compose-spectra-hub.yml`, the one stack meant to be downloaded on its own.
- **The developer SpectraLLM stack no longer carries its own copy of the broker.**
  `docker-compose-spectra.yml` restated sixty lines of `docker-compose.yml`'s KRaft service —
  the healthcheck interval and what it costs at 5s, the grace period a flushing broker needs,
  `unless-stopped`, the single-partition `__consumer_offsets`, the fixed cluster id — each with
  its reasoning duplicated beside it. The two resolved services differed in exactly two
  variables, the two that embed the service name, so the block is `extends`ed and only those
  two are overridden; `docker compose config` resolves to a byte-identical project. The service
  keeps the name `explorer-kafka`, which is load-bearing and now documented as such rather than
  assumed: the `include:` carries SpectraLLM's own profile-gated `kafka`, a same-named service
  merges with it and *inherits its profile*, and the broker then does not exist unless that
  profile is activated — `depends_on` stops resolving and the project is rejected outright.
  `extends` is what reuses a definition under a different name, which a second `-f` cannot do.

### Fixed

- **A `grep -q` at the end of a pipe reported a line it had just found as absent.** The stack
  smoke test waited for `[llm-chat] serving` with `docker compose logs … | grep -q`, under
  `set -o pipefail`. `grep -q` exits on its first match and closes the pipe; the producer then
  dies of SIGPIPE, and `pipefail` reports the whole pipeline as failed — with the pattern
  found. Whether it happens at all depends on whether the producer had finished writing, so
  the same command answered 0 on one iteration and 141 on the next: the wait loop broke on a
  match and the identical check right after it announced that llama-server had never picked up
  the model, while the line sat in the very log the failure handler went on to dump. The end of
  the chain — a Process Mining call travelling explorer → spectra-api → llm-chat — was never
  reached, on a stack where every container was working. The log is read into a file and the
  file is grepped, so there is no pipe to signal, and the wait and the verdict now go through
  one function rather than two copies of a pipeline that could disagree.
- **The stack smoke test asserted three things that come up at three different moments.** It
  waited for the Spectra API to answer and then, in the same breath, required the UI and the
  UI's proxy to answer too — but `docker compose up -d` returns when the containers have
  started, not when a JVM has finished booting, and the frontend is gated on the API's
  *healthcheck*, which lags its readiness. It passed on a slow runner and failed on a fast one:
  a race in the test, on a stack that was fine. Each endpoint is polled now, and a probe that
  gives up prints the last reply — the previous failure had to be dug out of a thousand lines of
  container logs, which is a cost this job should never impose twice.
- **The image-pin check asked the git tags what the registry serves.** It demanded a bump to
  `kafkaexplorer:1.8.9` while `1.8.9` was still being built and pushed — taking it would have
  pointed the stack at a manifest that did not exist — and a release whose publication *failed*
  (which has happened here) would have left a tag with no image behind it, blocking every pull
  request on a bump that could never be made. The offline run now only refuses a pin that is
  *ahead* of the newest tag; whether it has gone stale is `--published`, which asks Docker Hub
  and runs in the stack job, where the network is already a dependency and an unreachable
  registry is reported rather than failing the build. The pin itself is now `1.8.9`, which the
  registry does serve.
- **The model fetcher of the published-images stack ran a `curl` that image does not have.** The
  Spectra image installs curl and wget to run the llmfit installer, then ends that same layer
  with `apt-get purge -y curl` — which is why its own healthcheck is a wget. So
  `spectra-models` exited with `/bin/sh: curl: not found` on every boot and the embedding model
  was never fetched, on a stack that otherwise came up healthy. It now uses wget, and
  deliberately does **not** resume a partial transfer: `wget -c` with `-O` appends blindly when
  the server ignores a Range request, which yields a file of the right size and the wrong bytes
  — a failed transfer restarts from zero, which is the argument for pinning
  `SPECTRA_*_MODEL_SHA256` on a large model. Verification is skipped rather than faked if the
  image has no `sha256sum`, but a digest that was *asked* for and cannot be computed is a
  refusal. Found by the CI boot added the day before, the only way such a thing is ever found:
  by running it. The end-to-end step that caught it no longer waits on the llama.cpp image's
  healthcheck either — it waits on the entrypoint's own line and then retries the real call,
  since depending on a tool being present in somebody else's image is exactly what put the curl
  there.
- **Process Mining was reasoning on a truncated prompt against a local model, and said nothing.**
  The prompt budget is 120 000 characters — about 30 000 tokens — while Ollama gives a model
  4 096 tokens unless the machine has the VRAM for more, and the OpenAI-compatible request
  carries no `num_ctx` (that endpoint would not read one from the body). Ollama does not refuse
  the excess: it drops the oldest messages until the prompt fits, and logs that at debug level,
  i.e. nowhere on a default install. So every analysis on `docker-compose-llm.yml` — and on any
  Ollama an operator points the app at — was answering from a fraction of what it had been
  given, with nothing naming the fraction. The bundled stacks now set the window and the budget
  together and say so; the application default is unchanged, since a hosted model can afford it,
  and now carries the rule beside it. The same file's `ollama-pull-model` was pinned while it
  was open: it ran `curlimages/curl:latest`, two services below the comment claiming that Ollama
  image was "the only floating tag left in the tree".
- **`docker-compose-kafka4.yml` starts again.** The stack this project recommends had been
  refusing to come up at all since two volume mounts were added to its explorer service without
  the matching top-level declarations: `service "explorer" refers to undefined volume
  explorer_logs: invalid compose project`, before a single container was created. Nothing caught
  it because nothing parsed these files — a `compose-lint` job now resolves every stack and every
  overlay combination on each build, and fails on a compose file that no combination names.

### Added

- **The published-images stack is booted in CI** (on main and `workflow_dispatch`, since it pulls
  ~2 GB to test a deployment file whose content does not move with the code). It runs without the
  models, because the assertion worth making about a missing model is that the containers wait for
  it; and it pins the wiring nothing else can — that `GET /api/config` really reports the SPECTRA
  provider and the right base URL, and that the Spectra UI reaches its API through the nginx proxy
  whose upstream is baked into the published image.
- **Kafka Explorer and SpectraLLM as one stack, from Docker Hub** —
  `docker-compose-spectra-hub.yml`. The existing `docker-compose-spectra.yml` needs a SpectraLLM
  checkout beside the repository and builds the explorer from source; both projects publish their
  images under `compagnonsdudev`, so a machine with only Docker can now run the pair: broker with
  the demo topics, the explorer pointed at Spectra's `POST /api/query`, and the full Spectra stack
  (ChromaDB, the two llama.cpp servers, the API, the UI). Nothing waits on the ~4.8 GB of model
  weights the first boot downloads — the API installs the chat model itself, a one-shot fetches the
  embedding GGUF it does not install, and the llama.cpp containers wait for their file instead of
  crash-looping, so both interfaces are up in seconds. The two prompt budgets are sized against
  each other (8 192 tokens per slot against a prompt budget lowered to 16 000 characters: 30k
  tokens do not fit in that window, and what a model cannot see it does not report as missing),
  and `SPECTRA_API_KEY` must stay empty — Spectra's filter reads `X-API-Key` while the explorer
  sends `Authorization: Bearer`, so a key there would leave the stack looking healthy while every
  Process Mining call answered 401.
- **A fourth overlay, and a fetcher that verifies.** `…small.yml` serves a 3B chat model instead
  of the default 7B — ~2 GB instead of 4.7, half the memory, an answer in a fraction of the time,
  which is what makes the 300 s timeouts stop being load-bearing. It is also why the model
  one-shot (now `spectra-models`) fetches two models rather than one: `spectra-api` installs the
  default chat model itself and only that one, so serving another means naming its URL. Both
  downloads take an optional `SPECTRA_*_MODEL_SHA256`, and a file that fails its digest is
  deleted rather than served — a resumed transfer would otherwise resume onto the bad bytes for
  ever. With no digest pinned the one-shot prints the one it obtained, ready to copy.
- **The images the stacks pull are checked** (`docs/check-image-pins.py`): nothing floats, the
  llama.cpp CPU and CUDA tags name the same build — the GPU overlay must change the hardware,
  not the engine's revision — and the Explorer image the hub stack pulls is the current release.
  That default is hand-written and Dependabot cannot read a `${VAR:-1.8.8}` form, so nothing
  else would ever move it; the check fails on a *release* rather than on a change, which is when
  the reminder is due.
- **The end-to-end call is asserted in CI.** The stack smoke test now drops a 0.5B model into the
  volume — through the stack's own fetcher, so that code is exercised rather than bypassed — and
  requires `POST /api/config/test-llm` to answer `ok`. A Process Mining call really travelling
  explorer → spectra-api → llm-chat and coming back is what would have caught the `X-API-Key` /
  `Bearer` mismatch this pairing documents, instead of leaving it a paragraph nobody executes.
- **Three overlays beside it.** `…gpu.yml` moves both llama.cpp servers onto CUDA, pinned to the
  same build as the CPU image — the change that turns minutes per analysis into seconds.
  `…limits.yml` bounds the seven long-running services of its eleven — the four it leaves out are
  one-shots that exit (the shared limits overlay names two, and a service named in an overlay but
  absent from its base file fails the whole `up`), with no `cpus` on the
  inference servers, whose throughput *is* the core count. `…ingest.yml` has SpectraLLM index the
  topics themselves, so the corpus answers questions about what is in the messages with cited
  sources, and the explorer's audits can read it. That last one is an overlay rather than a flag
  because the flag alone gets two ordering problems wrong: a consumer subscribing to a topic that
  does not exist yet creates it with one partition instead of three, and a record that cannot be
  embedded yet goes to `<topic>.DLT` while the model is still downloading.
- **The developer SpectraLLM stack no longer holds the Explorer behind the model.**
  `docker-compose-spectra.yml` made the app wait for `spectra-api: service_healthy` — the rule
  every other stack here is written against, since this application needs no model to boot, only
  when somebody opens Process Mining — and carried neither the prompt budget nor the timeout a
  local model needs. Both are aligned with the published-images stack now: one pairing described
  in two files is how the two come to behave differently.
- **The dashboard's activity curve leads somewhere, and says what it is worth.** Three follow-ups
  to the sparkline column, all of them things the curve could not say on its own:
  - **Clicking a bucket opens that period's messages.** Seeing a spike and reading what was in it
    are the same question asked twice, and the second half was being done by hand. The curve is now
    a button: a click opens the topic's explorer with the search primed at that hour, `Enter` opens
    the peak — the bucket the accessible name already names. The instant travels in the URL
    (`?start=TIMESTAMP&at=…`), so the link is shareable, and the Topic Explorer gained
    `seedFromQuery` to pose a form from a URL that carries a start but nothing to run.
  - **The peak is written beside the curve**, not only in the tooltip. The scale is per row, so two
    curves of equal height can be 40/h and 40 000/h — and a figure reachable only by hovering is
    reachable only with a mouse, which is the defect `title=""` was replaced for elsewhere.
    Hovering a bucket replaces that number with the bucket's own value instead of opening another
    tooltip.
  - **A topic that produced and stopped is badged** `silent 6 h+`. It is the one thing a curve of
    this size does not show — a series fallen to zero and a low series look alike — and
    "Last Message" gives the instant without the fact that there was a regime before it. Guarded
    against noise (a prior regime, a silence covering at least 15 % of the window) and worded as a
    dated fact rather than a verdict; the duration is a floor, which the `+` carries.
  - **The week is read at 3 h, not 6 h.** `Last 7 days` was 28 points; a day reduced to four of
    them shows no daily cycle, which is the whole question a week-long window asks. It is 56 now,
    which the server's cap of 60 buckets accommodates.
  - **A log scale is offered beside the window.** A burst a hundred times the ordinary regime
    crushes everything else onto the baseline, and the rest of the time is usually what one is
    reading for. An option and not the default — it changes what the image asserts — and the
    column header names it when it is on, an undeclared scale being what makes a chart mislead.
  - **The current regime is shown beside the peak** when it departs from the topic's own
    (`▲ 2.4×` against the window's median). The peak describes the busiest moment of the window,
    which may be twenty hours old; this answers "is it running above its ordinary rate *now*".
    The median rather than the mean, which the very burst one is situating would pull — and it
    stays quiet below a factor two, an indicator that lights up always being one that stops
    being read.
  - **The Topic Explorer gained an `Activity` tab**, the same measurement with the room a cell
    does not have: a time axis, a hover that names the bucket in words, and a click that primes
    the search at that instant — on this page it needs no navigation at all. Mounted only when
    opened, like the consumers panel, since the measurement costs broker round trips.

- **An activity curve per topic in the dashboard's table.** The table said how many messages a
  topic holds and when the last one arrived — a level and an instant, neither of which says
  whether the topic is *working*: "1 200 messages, 3 min ago" reads the same on a topic doing
  forty a minute and on one that took a burst yesterday and stopped. Each row now carries a
  sparkline of what it produced over the last hour, day or week, with the window (and `Off`) a
  control in the toolbar.
  - **Measured from offsets, not from records.** `GET /api/dashboard/activity` resolves each
    bucket boundary to an offset per partition (`listOffsets(forTimestamp)`, every boundary issued
    before any is awaited) and subtracts. No record is read, no consumer group is created, and the
    wall clock is one round trip's latency rather than one per bucket — which is what makes a
    curve per row affordable on a page that refreshes on a timer. What a bucket counts is
    therefore offsets produced, which on a compacted topic is deliberately not the same number as
    the records it holds today.
  - **It only measures the rows on screen**, on its own 30 s cadence rather than the dashboard's
    5 s poll, and is bounded twice — `explorer.activity-max-topics` and
    `explorer.activity-max-lookups` (partitions × boundaries, the real unit of work). Whatever a
    budget cuts is named in the response: an absent curve otherwise reads as a topic that produced
    nothing.
  - **Nothing is drawn where nothing was measured.** The stretch of the window retention has
    deleted is greyed rather than traced at zero, a series short of a partition is dashed and says
    it is a floor, a topic that could not be read says `not measured`, and a failed read replaces
    the column with its reason. A flat line is a claim that a topic is quiet, and this is the one
    thing an unanswered question must not be able to produce.
  - The window is aligned on the bucket width and ends at the last *completed* bucket, so the
    curve does not wobble as the clock moves and the last point is not a half-filled bucket that
    reads as a collapse in traffic.


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
