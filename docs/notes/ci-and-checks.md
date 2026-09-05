# CI, checks and governance — design notes

Extracted from `CLAUDE.md`. What each workflow and each `docs/check-*.py` guards, and
why. The doc checks expire their own exemption lists, so read this before adding one.

## What each workflow builds, and what gates it


`ci.yml` triggers on `push` to **main only**, plus `pull_request`. Listing feature branches under `push` as well built every commit twice once its PR existed — the `push` and `pull_request` events both fired, and the `concurrency` guard cannot collapse them because `github.ref` differs (`refs/heads/<branch>` vs `refs/pull/<n>/merge`), putting them in separate groups. Branch work is covered by its PR; `workflow_dispatch` builds a branch before one is opened.

That `pull_request` carries **no base filter**, and the day it did is worth remembering. It read `branches: [ "main" ]`, which sounds like a harmless mirror of the `push` line and is not: a pull request whose base is *another branch* — the shape this repository uses whenever a series is reviewed in pieces — matched the filter and therefore ran nothing at all. Not a reduced gate, no gate: no build, no suite, no CodeQL, no dependency review. Three such pull requests were reviewed and merged that way, and `main` went red on **ten tests** the moment they met, none of which any run had ever executed; the only thing that had ever built those branches was somebody remembering to fire `workflow_dispatch` by hand, which is a gate that depends on a habit. The filter is gone from `ci.yml`, `codeql.yml` and `security.yml` alike — the three that carried it — and it costs no double build, `push` still naming main alone, so a stacked branch fires the `pull_request` event and nothing else.

Every job carries a `timeout-minutes` (the GitHub default is 360, so a wedged test would burn six hours of runner), and a failed build uploads `target/surefire-reports/` as an artifact — a run that is green locally but red here is expected from time to time, since CI resolves the real `io.confluent` jars while the local offline harness uses stubs.

`release.yml` runs `./mvnw -B clean verify -P build-frontend`, **not** `package -DskipTests`. A tag can be pushed at any commit and nothing verified that commit had ever been green, so a release could ship code no suite had run against; the `docker` job inherits the gate through `needs: build`.

The `docker` job builds **`Dockerfile.release` from the JAR the `build` job produced**, not the multi-stage `Dockerfile` from source. It used to do the latter, which meant the release recompiled everything a second time, *without* the test gate, and the published image could drift from the JAR attached to the Release; the `upload-artifact`/`download-artifact` hand-off between the jobs fed nothing. Now the artefact is downloaded to `dist/` (**not** `target/` — `.dockerignore` excludes that, so the JAR would be missing from the build context), staged as `./app.jar`, and copied into a runtime-only image. Both ends are guarded: the build fails if `target/` does not hold exactly one JAR (`spring-boot:repackage` leaves the plain one as `*.jar.original`, which the glob does not match), `upload-artifact` uses `if-no-files-found: error` and the release step `fail_on_unmatched_files: true` — the default `warn` would publish a Release whose only content is its notes. A `SHA256SUMS.txt` is attached alongside the JAR.

`ci.yml` builds the multi-stage `Dockerfile` on every run (`push: false`, GHA layer cache). Nothing built the image before a tag was pushed, and that is precisely how it broke: `vite.config.ts` sets `build.outDir: '../resources/static'` (the path Maven wants for an in-place build), so from `/app` the frontend stage wrote to `/resources/static` while the next stage copied `/app/dist` — `"/app/dist": not found`. main stayed green and **v1.2 shipped a Release JAR with no GHCR image behind it**. The Dockerfile now pins the output itself (`npm run build -- --outDir /app/dist --emptyOutDir`) rather than depending on that config value; keep it that way, and keep the CI image build, or the same class of breakage returns unnoticed.


## Testing

Tests use JUnit 5 + Mockito. Unit tests mock Kafka and Flink — no broker needed. Integration tests (`ApplicationContextTest`) use `@SpringBootTest` with `DynamicPropertySource` to inject test config.

**A case that leaves the Flink runtime busy fails itself, rather than its neighbour.** The two `FlinkSqlService` classes that drive a real MiniCluster share one runtime for the whole class (`@TestInstance(PER_CLASS)`), and a case whose budget expires cancels its job without waiting for the cancellation to land — during which the mutation in flight holds the coordinator's write lock and the *next* case, whatever it is, gets `The Flink runtime was busy`. It cost three rounds of diagnosis to see that, because the symptom lands on an innocent: `xmlExtractUdfIsRegisteredAndParsesXml` answering `Table 'xml_messages' not found. No matching Kafka topic exists.` — the other engine's sentence on a query the planner has always served — and only where the execution order puts it after the culprit, which meant in CI and nowhere else. An `@AfterEach` in each class waits, bounded, for the runtime to become free again (`FlinkSqlServiceTest`) or for the job registry to empty (`FlinkSqlServiceInsertVariantsTest`), so the case that leaks is the case that goes red and the one that names it. Cases that started nothing pay nothing — the first attempt succeeds immediately — and a case added later has nothing to remember. The circuit breaker is reset in `@BeforeEach` beside the mocks for the same reason: `flinkSelectDisabled` has the lifetime of the process, which is right in production and a leak in a class where several cases provoke an engine failure on purpose.

`AuditServiceTest` overrides `persistAuditHistory()` to skip real Kafka writes.

**The Process Mining eval has two halves, and only one of them is a unit test** (`eval/`). Nothing
measured whether the prompt work does what it claims, and that failure mode is silent by
construction: a plausible answer about an invented pipeline reads exactly like a correct one. The
way to tell them apart is a dataset whose right answer is known, and `setup-demo.sh` seeds one.
`DemoPipelineFixture` loads it from `src/test/resources/eval/demo-order-pipeline.json`, which holds
**records rather than digests** — a digest is what this application computes, so committing one
would let the fixture and the digester drift together and agree with each other about a payload
neither had read. It is also **not captured from a live cluster**: a capture is a snapshot nobody
can regenerate without a broker, so it is written from the seeder and resolved against it by
`docs/check-eval-fixture.py` (every topic, every order id, every redelivery, the two corrupt
payloads verbatim, and the assertion that the payments and shipments still carry no order id in
their bodies). `ProcessModelEvalTest` asserts the aggregate exactly and runs in `mvn verify`;
`LlmAnalysisEvalTest` calls a real model, asserts loosely, carries `@Tag("llm-eval")` — excluded by
surefire's `excludedGroups` **and** by `verify-offline.sh`'s `--exclude-tag`, since it costs money
and needs the network — and **skips rather than fails** when nothing is configured, a test that goes
red for want of an API key being one people learn to ignore. Writing it is what found the
truncated-record defect described under `ProcessModelBuilder`.

**It also carries the first half of the bilingual-prompt experiment** (`theShippedPromptHoldsItsFormat`,
`-Dllm.eval.runs`, default 3): the system prompt is English and the user prompt's headings are
French, small models are reputed to hold a format less well across a language switch, and nothing
had measured that *on this prompt*. It measures the shipped prompt and reports the rate — a parse
failure, an answer that parsed with no flowchart, and an unreachable endpoint being three different
answers. An English variant is deliberately **not** shipped to compare against: a second production
prompt is a second surface to keep in step for ever, built on a belief, and a prompt that holds the
format every time on the model under test answers the suspicion without one. Only a run showing
failures justifies building it, and it would then be justified by a number.

`KafkaClusterIntegrationTest` carries `withStartupAttempts(2)` and a three-minute startup timeout, and neither is decoration: a launch that fails is not a test that failed, and it took the whole `mvn verify` down twice in twelve hours on hosted runners — once on a pull request, once on a push to main — with this class's own assertions never having run. That is survivable on a pull request, where a re-run costs minutes; it is not on `release.yml`, which gates a tag on the same `verify` and offers no retry short of cutting the version again. The retry covers the *launch* only — a broker that started and then misbehaved is a finding, and retrying that would hide exactly what the class exists to catch.

**The third failure came *with* that retry already in place**, which is what took the diagnosis past "flaky": two attempts inside the same minute failing identically is not a random hiccup. Two things followed. `src/test/resources/logback-test.xml` raises the Kafka clients to WARN **for tests only** and leaves `org.testcontainers` at INFO — when the container does not start, every consumer and admin client still open spends its teardown retrying against an address that answers nothing, thousands of lines of it, and the Testcontainers exception naming the cause ends up above the tail GitHub's job-log API serves; all three diagnoses had had to stop at "the container did not start". And the `build` job now authenticates to Docker Hub when the secrets exist (same guard as `release.yml`, so a fork skips it and a refused login cannot fail the build), on the hypothesis that anonymous pulls from a shared runner IP were hitting the per-IP rate limit — which is what an immediate retry does not forgive.

**That hypothesis is unrefuted rather than confirmed, and the distinction is the point.** Measured ten days later: over the thirty most recent completed CI runs on `main`, three failed, and **none of the three was the `build` job** (they were the SpectraLLM stack smoke test, the documentation-link check and the demo-seeder test). So the failure has not recurred — three times in thirteen hours before, zero since — but no log ever named a pull limit, because there was no failure left to name it. If it returns, the logging is what will say whether the guess was right; do not promote it to a cause in the meantime.


**That class is also where the real broker gets asked the questions a mock cannot answer.** The three snapshot-read faults described under `KafkaSnapshotReader` were all found by hand against a live stack, and not one of them is reproducible against `MockConsumer`: it emulates neither an out-of-range seek nor the `auto.offset.reset` that follows one, and it delivers exactly what it was handed rather than prefetching in the background. So the suite — which already runs a Kafka 4.3 broker through Testcontainers — now seeds the state that produces them: a three-partition topic with four records per partition and everything below offset 2 removed with `deleteRecords`, which is what retention leaves behind. Three assertions on it: a trimmed topic read in full through `KafkaSnapshotReader`, a multi-topic snapshot returning **every** topic it was asked for, and the audit's own `getEarliestRecords` path over the same trimmed topic. Verified to bite — with the beginning-offset clamp reverted, two of the three fail — where the unit tests beside them pin the same rules against a mock and could not have caught the clamp at all. The general rule it states: a defect produced by the client's own behaviour belongs in the integration suite, because a mock that cannot fail is not coverage.

**The metric templates' scan bounds are asked here too, and the answer overturned a shipped fix on its first run.** A mock cannot refuse a setting it has never heard of, so `MetricService`'s bounded-scan option had been added on a reading of the connector's documentation. Asked of a real broker, `flink-connector-kafka:5.0.0-2.2` refuses `scan.bounded.mode` — and refuses it *quietly*, because `FlinkSqlService` classifies that as an engine failure and falls back, so the query returns rows, no error, and `engine: KAFKA_DIRECT`. The test asserts that answer rather than working around it: the day a connector bump supports the option, it fails and says so. The sibling case pins the path a count-delta side really takes — `directSql` to the direct reader, one row rather than a changelog, a number that had to come out of the broker — and it is why the wrong option was survivable: the count was right all along, by the other route. Both build their own local Flink cluster on demand rather than as a field, since the rest of the class talks to the broker directly and should not pay for one.
`AuditControllerTest` and `TopicControllerTest` pin the two endpoint families whose whole contract is in their status codes, and where a wrong code is a different *answer* rather than a rougher one: a second audit refused with **409 carrying the in-flight run's id** (so the page attaches instead of reporting a failure), no run yet as **204** and not an empty report, a comparison of two pre-graded-severity runs as **409** and not 404, a record offset that holds nothing as **404** (compacted or out of range, which a caller must tell from a failure), and — the distinction the consumer-lag work was done for — a consumer or delay read that *failed* answering **200 with `available: false` and its reason**, since "nobody reads this" and "we could not ask" must not arrive as the same payload. Writing the first of them found that **`AuditOptions` carried six primitive `boolean` components**: Jackson binds a record through its canonical constructor, so `{}` or a body naming only a prefix failed the whole request with a 400 quoting a Jackson internal — on an endpoint that declares `@RequestBody(required = false)`, which made accepting *no* body while refusing `{}` incoherent. It has a boxed `@JsonCreator` now, where an absent flag means the check runs, so `{}` means exactly what sending nothing means. Same defect and same fix as `StreamFlowRequest`. **Every `@PathVariable` / `@RequestParam` in `web/` carries an explicit name.** That began with these two controllers, on a rationale that was wrong twice over — it was credited to a convention `QueryController` already followed, and `QueryController` was one of the four that did not follow it (`LineageController`, `MetricController` and `ProcessMiningController` being the others, eleven sites between them). The reason itself holds: `verify-offline.sh` compiles with plain `javac` and passes no `-parameters`, and Spring 6.1 removed `LocalVariableTableParameterNameDiscoverer`, so an unnamed one fails at *request* time under that harness with "Name for argument of type [java.lang.String] not specified" — never at compile time, and never under Maven, where `spring-boot-starter-parent` supplies the flag. A pass over part of `web/` therefore fixes nothing an operator would notice; the rule is the package, and it is cheap enough to keep.

`MetricControllerTest` pins `POST /api/metrics/suggestions`, whose body is **optional** and whose record grew a component (`fieldMappingId`) after it shipped: no body at all, `{"flowChains":[]}`, `{"fieldMappingId":"x"}` alone, and a whole trace with hops that omit half their fields. Jackson binds a record through its canonical constructor, so an absent property arrives as `null` — the same class of failure `StreamFlowControllerTest` was written for, on the one endpoint-bearing controller that had no test.

`KafkaAdminServiceActivityTest` drives the sparkline's read through a mocked AdminClient that **behaves like a log** rather than returning a canned response: each partition is a list of record timestamps, and a boundary resolves to the first record at or after it — or to no offset at all when every record predates it, which is the case a caller must not read as "offset 0". What it pins is mostly what the curve must not say: a quiet topic and an unreadable one are two different answers and only one is a row of zeros, a window retention has eaten into is reported as such, and a partition that did not answer makes the series a floor with the note saying so. The instant is a parameter (`getTopicActivity(..., nowMs)`, package-private beside the public method that reads the clock): the window is derived from the clock, so a test computing the alignment a microsecond before the method does would fail whenever the two land either side of a bucket boundary.

`KafkaAdminServiceLastMessageTest` is its sibling on the dashboard's other column, and holds a mocked `AdminClient` **and nothing else** — which is half the assertion: the read it covers used to open a consumer and poll every partition of every topic, so a revision that went back to that answers nothing at all here. The rest is the shape of the answer rather than its cost, because the cost is not observable from outside: an empty partition's `-1` is not an instant (1970 rendered as "56 years ago" is a worse answer than the absence the UI already knows how to show), a partition that did not answer costs its own contribution and never the topic beside it, and a topic that could not be described costs its own row — a name deleted between the `listTopics` that produced it and the call that uses it being an ordinary race on a live cluster. `asksTheBrokerForTheMaxTimestampRatherThanReadingRecords` names the spec explicitly, since "answers correctly" alone would also be true of a far more expensive route. Five of its six cases were checked to fail against the revision they describe; the sixth asserts an absence the broken path also produces, and is a regression guard rather than a defect proof.

`SettingsStoreTest` and `StoredSettingsInitializerTest` cover the two halves of keeping what the Settings page is used to enter, and most of what they pin is a way for the fix itself to fail quietly: a field taken over that nobody entered (which would freeze this release's defaults into the file), a credential dropped without a word, a file from another version read as if it were this one, and — the one the whole design turns on — the precedence between an environment variable and a stored value, in **both** directions, per field, and through the `ANTHROPIC_API_KEY` alias. The last test of the pair runs the real round trip: the store writes, the initializer reads, which is the seam where a format written one way and read another would show up. `FlinkTableRestoreTest` does the same for table definitions by building a *second* `FlinkSqlService` over a fresh Flink `TableEnvironment` pointed at the same store — the only honest way to simulate a restart — and asserts that the schema that comes back is the one that was written rather than a generated one. Two of its cases were wrong on the first run for a reason worth keeping: Flink resolves a connector only when a table is *read*, so `WITH ('connector' = 'no-such-connector')` is accepted at DDL time; a definition that genuinely fails needs a type that does not exist.

`StreamFlowControllerTest` uses **standalone MockMvc** (no Spring context): nothing there needs one, and registering only that controller is what makes its "GET /stream-flow answers 404" assertion meaningful — that mapping once shadowed `SpaController` and turned a page refresh into a 500. It also pins the 400-with-a-message contract on both endpoints, and it is what caught the record-binding failure on a minimal JSON body.

`FlinkSqlServiceTest` and `FlinkDdlValidationTest` **pass on Flink 2.3**. Before the migration these suites were broken (SELECT was routed to `kafkaDirectSelect()`, so tests against in-memory `createTemporaryView()` tables failed with "Table not found"; DDL validation hit a Calcite `SqlParserException`). With the Flink planner path restored (the `THREAD_PROVIDERS` fix, see above), SELECT resolves in-memory views through Flink and the whole suite is green. The Flink-native SELECT tests that were once `@Disabled("KAFKA_DIRECT")` (in-memory views, multi-topic JOIN, the `XmlExtract` UDF) are enabled again — they run against the restored planner, so the suite has no skipped tests.

`InternalTopicProvisionerTest` and the new cases in `FieldMappingStoreTest`, `MetricServiceTest`,
`AuditServiceTest`, `StreamFlowServiceTest` and `ExplorerGroupCleanupServiceTest` cover the
application's own resources on the cluster — the topics it writes and the groups it leaves — and
what they mostly pin is what those must *not* do: create anything of the user's, alter a topic
nobody asked to have altered, audit or trace themselves, or delete on a broker that never answered.
The two that describe a real defect were verified to fail against the revision they describe:
`aPrefetchPositionAtTheLogEndDoesNotEndTheRestore` (which needs a `MockConsumer` that lies the way a
real client lies, reporting the log end while handing over nothing — `MockConsumer` otherwise
advances its position only on delivered records and could never reproduce it) and
`anEvictedMappingIsTombstonedSoTheTopicFollowsTheSameBound`.

Test classes are in `src/test/java/com/compagnonsdudev/kafkasqlexplorer/`.


## Project governance & supply chain

The community-health files are the ones GitHub looks for, and the ones a stranger reads before
filing anything: `CONTRIBUTING.md`, `CODE_OF_CONDUCT.md`, `SECURITY.md`, `SUPPORT.md`,
`CHANGELOG.md`, `.github/CODEOWNERS`, the two issue templates plus `ISSUE_TEMPLATE/config.yml`,
and `pull_request_template.md`.

- **`CONTRIBUTING.md` names `mvn verify`, never `mvn test`.** It documented the latter, which runs
  neither ESLint nor Vitest — so a contributor following the file was green locally and red in CI.
  It also carries the `verify-offline.sh` escape hatch, since a contributor behind a proxy that
  blocks `packages.confluent.io` cannot build at all otherwise.
- **`SECURITY.md` supports the latest release only**, and says so rather than naming a version. It
  named `0.0.1` for eleven releases after that stopped being true. It also states plainly that the
  app has no authentication and that `POST /api/config` repoints the cluster at runtime — that is
  a deployment constraint, not a vulnerability, and a report saying otherwise can be answered by
  pointing at it.
- **`ISSUE_TEMPLATE/config.yml` keeps blank issues enabled.** The two templates cover a bug and a
  feature request; a usage question fits neither, and forcing it into one produces a worse issue.
  There is deliberately **no Discussions link**: the feature is disabled on the repository, and a
  contact link that 404s is worse than none — enable Discussions and add the link together.
- **`CODEOWNERS` requests reviews, it does not require them.** Requiring them is a branch
  protection rule on `main` ("Require review from Code Owners"), which lives in the repository
  settings and is the thing that turns the file into a gate.
- **`CHANGELOG.md` is closed by the release, not by hand.** `release.yml`'s follow-up step renames
  `[Unreleased]` to the version and opens a fresh one, on the same pull request as the Explorer pin
  bump — both go stale at the instant the `docker` job publishes. Before that step existed nothing
  did it and nothing checked it, so thirty releases' worth of entries sat in a section asserting
  they had not shipped, `v1.7.1` through `v1.9.11`; those sections were reconstructed by first
  appearance across the tags, which is exact because entries were only ever appended. The contrast
  with the pin is the lesson worth keeping: a stale pin eventually reddens an unrelated pull
  request, a stale changelog reddens nothing, and the one with no backstop is the one that ran for
  eight weeks. `docs/notes/docker-and-stacks.md` carries the step's guarantees.

Three workflows beyond `ci.yml` / `release.yml` / `dockerhub-description.yml`:

- **`codeql.yml`** — static analysis of the *source*, on push, pull request and weekly. Trivy in
  `ci.yml` scans the released image, which means its OS packages and the jars it ships; it never
  reads a line of Java or TypeScript. That gap mattered here more than most: this app parses
  untrusted XML, assembles SQL, masks credentials inside generated DDL, and ships unauthenticated.
  The Java half uses `build-mode: manual` with `./mvnw -DskipTests -P '!build-frontend' compile` —
  `autobuild` would activate `build-frontend` (it is `activeByDefault`) and download a whole Node
  toolchain to rebuild a SPA the javascript-typescript half already reads from source.
  **It also copies its findings into the job log** — severity, query id, file and line, one row
  each. The check a pull request shows says `8 new alerts including 1 high severity` and nothing
  else: the detail lives in the Security tab, which needs write access on the repository, and the
  annotations API is out of reach from a CI environment. So "CodeQL is red" was diagnosed by
  guessing, and a guessed fix to an injection query is precisely the change that removes a
  deliberate behaviour to silence a check. Knowing *which* rows they are is also what lets them be
  crossed against the diff — which is how it was established that not one of the tree's twenty-nine
  security findings sits on a line the branch reporting them had written. The step cannot fail the
  job (`if: always()` so a red `analyze` is still explained, `exit 0` so a missing or unexpected
  SARIF stays a silence rather than a breakage), uploads nothing, and changes no verdict.
- **`security.yml`** — `dependency-review` on pull requests (fails on a **newly introduced** high
  severity advisory, and on licences that cannot ship inside an AGPL artifact), plus TruffleHog
  over the full history with `--only-verified`. The severity gate is narrow on purpose: it says
  nothing about the existing tree, so it cannot start failing builds on a morning nobody chose.
  TruffleHog **does** fail the build where Trivy only reports, and the asymmetry is deliberate —
  Trivy's findings are often unfixable transitive CVEs, whereas a verified secret is a live
  credential in a public repository.

- **`scorecard.yml`** — OpenSSF Scorecard, weekly plus on every `branch_protection_rule` event
  (that one change otherwise leaves no trace in the repository). It overlaps with nothing else
  here on purpose: CodeQL reads the code, Trivy the image, dependency-review what a pull request
  adds, and Scorecard the *project* — signed releases, pinned actions, branch protection, a
  published security policy. Those decay silently, because no build ever fails when they do.
  `publish_results: true` is what makes the README badge resolve. The Branch-Protection check
  needs a classic PAT as the optional `SCORECARD_TOKEN` secret — the default `GITHUB_TOKEN`
  cannot read those settings, so without it that one check reports unknown while the rest score
  normally; it stays optional so a fork is not failed on a secret it cannot have.

- **`image-pins.yml`** — `check-image-pins.py --published`, weekly plus `workflow_dispatch`. The
  Explorer pin of the published-image stacks is hand-written as `${EXPLORER_IMAGE_TAG:-<version>}`,
  which Dependabot cannot read, so the only thing that moves it is `release.yml`'s bump pull
  request — and that step is `continue-on-error` by design, the image being published by the time
  it runs. The hole is not that posture but its consequence: when the step opens nothing,
  **nothing says so**, and the pin goes stale in silence until the next pull request touching a
  file `hub-changes` gates on fails a check about something it did not change. `release.yml`'s own
  comment records that happening twice in one day; it then happened again across v1.9.3 and
  v1.9.4. So the release keeps trying to fix it and this asks the question on a timer, which is
  the same argument Scorecard rests on — what decays silently needs something looking at it when
  nothing else is happening. It deliberately does **not** open the bump pull request: that logic
  lives in `release.yml`, and a second copy is the drift this repository keeps removing. A
  registry that cannot be reached is a `::warning::` inside the script rather than a red run, so
  a failure here means the pin really is behind, and its message names the three files.

**The JAR is signed, keylessly** (`actions/attest-build-provenance` in `release.yml`'s `build`
job, hence the `id-token: write` + `attestations: write` on it). The image had a full SLSA
provenance and an SBOM while the JAR beside it on the same Release had only `SHA256SUMS.txt` —
and a checksum published in the same place as the file it describes answers "did this arrive
intact", never "did this come from here". Sigstore rather than GPG because there is no key to
store, leak or rotate: the signing identity *is* this workflow at this commit, in a public
transparency log. Consumers verify with `gh attestation verify <jar> --repo devdownin/Kafkaexplorer`;
`SECURITY.md` carries that, the image equivalent, and the reason to pin by digest in production.

**Every action is pinned to a commit SHA**, with the version in a trailing comment
(`actions/checkout@3d3c42e… # v7`). A tag is mutable and `softprops`, `peter-evans`,
`aquasecurity` and `trufflesecurity` all run with credentials or write scope. Dependabot updates
SHA pins and rewrites the comment, so this costs nothing to maintain — when adding an action,
pin it the same way rather than reaching for the tag.

`.gitattributes` normalises text to LF in the repository while checking `*.bat` / `*.cmd` /
`*.ps1` out as CRLF. The tree mixes Unix entry points (`mvnw`, the `setup-*.sh` seeders,
everything the images run) with Windows ones, and a CRLF that reaches a shell script is the
classic `bad interpreter: /bin/sh^M` — inside a container, where it is awkward to diagnose.
`.editorconfig` describes what the tree already does (Java 4 spaces, frontend/YAML/shell 2,
`pom.xml` tabs); it is not an invitation to reformat.


## Documentation and API-contract checks

These run in the `docs-links` job of `ci.yml` — no network, no `needs`, seconds each.

- **`docs/check-links.py` résout tous les liens de la doc qui pointent vers le dépôt** (job `docs-links` de `ci.yml`, sans `needs` et sans réseau). `docs/DOCKERHUB.md` est rendu **hors du dépôt** : ses liens sont tous absolus, aucun n'est vérifiable en lisant la page, et un lien pourri reste invisible jusqu'à ce qu'un visiteur clique et tombe sur un 404 — sur la page censée vendre l'image. Le script résout les trois formes (chemin relatif, `github.com/…/blob/main/…`, et une URL Pages vers `docs/…`) et **ignore les commentaires HTML** : les notes de maintenance de `DOCKERHUB.md` explicitent la forme des URLs Pages en `…/img/…`, dont l'ellipse était signalée comme image cassée.
- **`docs/check-api-types.py` fait pour le contrat d'API ce que les deux précédents font pour les liens et la configuration** (même job `docs-links`, même absence de réseau). Le front affirmait ses types au point d'appel — `axios.get<{ samples: string[] }>(…)` — et TypeScript croit une annotation écrite à la main : le jour où `GET /api/topic/{name}` a cessé de renvoyer des chaînes pour renvoyer des `TopicMessage`, rien n'a échoué à la compilation et la page Compare est morte en production sur l'erreur React #31, des mois plus tard. Les formes de réponse vivent donc dans **`src/main/webapp/src/api/types.ts`**, une par record Java, chacune marquée `@java <Record>` ; le script les résout contre `domain/*.java` — noms de champs **dans les deux sens** (un champ que le back a et que le front ignore, un champ que le front lit et qui n'existe pas) et types via une correspondance explicite (`String`→`string`, `List<X>`→`X[]`, `Map<K,V>`→`Record<…>`, un record→son interface, une enum→son alias d'union). La nullabilité est laissée au front **à toute profondeur** — toute référence Java est nullable, et `Record<string, string | null>` pour les headers Kafka est le type juste, pas un relâchement — sauf sur un primitif, où `null` décrit un cas qui ne peut pas se produire. Un type Java inconnu de la correspondance est **signalé**, jamais ignoré : c'est ainsi que ce contrôle-là pourrirait à son tour. La résolution consulte d'abord **les correspondances que les marqueurs `@java` déclarent déjà**, et l'identité de nom seulement ensuite : sans cela un record imbriqué devait garder son nom simple côté front — `ProcessModel.Activity` imposait une interface `Activity` dans un fichier de types partagé, à côté de `Edge`, `Variant` et `Endpoint`, des noms assez généraux pour entrer en collision avec la fonctionnalité suivante. L'ordre compte : l'ensemble comparé mêle noms d'interfaces TS et noms d'enums Java, donc une enum Java se résolvait sur son propre nom et le marqueur qui la renommait n'était jamais atteint. Un nom simple revendiqué par deux déclarations sous deux noms TS différents est **abandonné** plutôt que résolu au dernier arrivé — un contrôle qui tranche en silence est pire qu'un contrôle qui dit qu'il ne sait pas. Une interface sans marqueur `@java` reste non vérifiée, ce qui est permis mais visible. La couverture est ce qui compte : elle est passée de 9 interfaces à 20 records vérifiés (`TopicSearchResponse`, `QueryInitResponse`, `MetricConfig`, puis toute la famille `AuditReport` — `TopicAudit`, `TopicIssue`, `FlowAudit`, `StepInfo`, `HealthStatus`, `AuditStatus`), et les formes anonymes déclarées au point d'appel — le motif exact qui a tué Compare — ont été rassemblées dans ce fichier. Trois d'entre elles étaient des copies littérales sous un autre nom (`SchemaInfo` = `QueryInitResponse`, la `TopicSearchResponse` de `topicSearch.ts`, le `MetricConfig` de `Metrics.tsx`) : elles sont maintenant des alias ou des imports, une seule forme par endpoint. Une union de littéraux de chaîne est acceptée là où Java déclare `String` — le serveur n'émet que cinq `stopReason` et l'UI branche dessus ; élargir le front à `string` pour satisfaire le script supprimerait de la sûreté de type au nom d'un contrôle qui existe pour en apporter. L'inverse reste une erreur : `string` en face d'une *enum* Java, où l'ensemble fermé existe des deux côtés. Le script lisait aussi **un seul record par fichier** (`search`, pas `finditer`), ce qui rendait invisible un record imbriqué : `FlowAudit.StepInfo` est déclaré dans `FlowAudit`, donc marquer une interface `StepInfo` répondait « matches no record in domain/ » — une erreur qu'on ne distingue pas d'une faute de frappe, sur un type qui est là. Un angle mort dans un contrôle est pire qu'un trou de couverture : il ne se contente pas de rater la dérive, il plaide contre la déclaration correcte.

**`AuditReport.globalStats` est un `Record<string, unknown>`, et c'est un arbitrage.** Le record Java déclare `Map<String, Object>` ; la page en a une lecture bien plus riche (`GlobalStats` : phase, progression, `stopReason`, `healthScore`, `scopeNotes`…), mais cette forme n'est promise par aucun record — elle est assemblée clé par clé dans `AuditService`. La déclarer typée dans `api/types.ts` ferait passer une convention pour un contrat, ce qui est exactement l'affirmation écrite à la main que ce fichier existe pour supprimer. Le rétrécissement est donc explicite et tient en **une ligne commentée** de `Audit.tsx`, au seul endroit qui lit ces clés.
- **`docs/check-doc-paths.py` résout les chemins que `CLAUDE.md` et `CONTRIBUTING.md` citent en prose** (même job `docs-links`). `check-links.py` ne voit que les *liens* markdown ; ces deux fichiers-là désignent le code entre backticks, au fil du texte, et rien ne vérifiait que la cible existe encore. Elle n'existait plus : `AUDIT.md` et `CONSUMER-GROUPS-AUDIT.md` étaient décrits comme des documents à lire avant de refactorer, et `deploy/kraft-platform/` était cité dans une règle sur les `container_name` — les trois supprimés du dépôt, deux depuis des mois. La résolution est **en avant et volontairement généreuse** (plusieurs bases, plus un index des noms de fichiers), parce que ces documents nomment les chemins relativement à ce dont ils parlent : `pages/helpContent.ts` veut dire `src/main/webapp/src/pages/helpContent.ts`. Ce qu'on cherche est le chemin qui ne résout **nulle part** ; un faux positif ici apprendrait à ignorer le contrôle. Ce qui n'est pas un chemin du dépôt — une image de conteneur, une référence d'action, un répertoire généré — s'ajoute nommément à `NOT_A_PATH`, même règle que l'`EXTERNAL` ci-dessous. **Et cette décision expire** : `unused_exemptions()` échoue sur une entrée que plus aucun document ne cite, ou que `looks_like_path` rejette déjà — donc jamais consultée. Seize des trente-sept entrées étaient dans ce cas, la liste n'ayant jamais été taillée depuis sa création. Ce qui n'est **pas** signalé, c'est une entrée dont le jeton résoudrait comme vrai chemin : la moitié de la liste est générée ou gitignorée (`target/`, `dist/`, `node_modules/`), donc ce verdict dépendrait de l'exécution préalable d'un build — vert sur un checkout propre, rouge sur l'arbre d'un développeur, la seule chose qu'un contrôle ne doit jamais être.
- **`docs/check-config-table.py` fait pour les tableaux de configuration ce que `check-links.py` fait pour les liens** (même job `docs-links`, même absence de réseau). Les tableaux de variables avaient exactement la même exposition et aucun contrôle : c'est comme ça que la page a annoncé `eclipse-temurin:21-jre-alpine` pendant deux majeures de l'image réelle, erreur trouvée en lisant, pas par la CI. Le script résout chaque variable documentée **en avant**, depuis les sources de vérité vers le nom de variable — `application.yml`, les classes `@ConfigurationProperties` (où vit une propriété absente du YAML, comme `explorer.stream-flow-max-topics`) et les lignes `ENV` des deux images runtime — puis compare les défauts documentés au YAML et l'image de base aux deux Dockerfiles. En avant, parce que le *relaxed binding* de Spring est plusieurs-vers-un et ne s'inverse pas : `explorer.search-max-scan` donne exactement `EXPLORER_SEARCH_MAX_SCAN`, alors que ce nom seul pourrait désigner plusieurs propriétés. Un tiret cadratin dans la colonne « défaut » veut dire « pas de défaut » et n'est pas comparé ; une variable qui n'est ni une propriété ni un `ENV` doit être ajoutée à `EXTERNAL` — nommément, pour que ce soit une décision et pas un trou. **La décision expire là aussi** (`stale_exemptions()`) : une entrée `EXTERNAL` qu'une autre source résout déjà, ou qu'aucun tableau ne documente plus, échoue le contrôle — comme une exemption `HISTORICAL` qui ne supprime plus aucun désaccord. `EXTERNAL` est d'ailleurs vide aujourd'hui : elle portait `JAVA_TOOL_OPTIONS`, que les deux images posent en `ENV`, donc elle ne masquait rien depuis l'existence de ces lignes. C'est ce que `--report-unused-disable-directives` fait pour les directives ESLint du dépôt, appliqué aux échappatoires des contrôles eux-mêmes : une liste tenue à la main ne fait que croître si rien ne l'élague, et une exemption dont personne n'a besoin est un permis permanent sur une affirmation que plus rien ne vérifie. Il résout aussi les **badges de version** contre le pom — Java contre `<java.version>`, Kafka contre `<kafka.version>`, Flink contre `<flink.version>` : un badge shields.io est *statique*, la version est du texte écrit à la main dans le chemin de l'URL, et lors du passage à 25 elle a été déplacée à la main dans le même commit que le pom — si on l'avait oubliée, rien ne l'aurait rattrapée. Les **deux moitiés** sont vérifiées, le texte alternatif et l'URL, parce que ce sont deux copies d'un même nombre et que l'une s'édite sans l'autre : un badge dont le libellé annonce 25 au-dessus d'une image qui affiche 21 est un troisième mode de défaillance, pas une variante des deux premiers. Il résout enfin les **versions de dépendances affirmées en prose ou en badge** contre le pom (`VERSION_CLAIMS`, `BADGES`) : c'est la classe d'affirmation qui pourrit le plus silencieusement ici, et elle a été prise trois fois en lisant plutôt que par la CI — « Flink 2.x supporte 17/21, pas 25 », `flink-connector-kafka:4.0.1-2.0` face à `5.0.0-2.2` au pom, et `anthropic-java 2.16.1` face à `2.53.0` — cette dernière trouvée par le contrôle lui-même en l'écrivant, et corrigée entre-temps par `ed308e4` : le contrôle est donc le garde-fou, pas la correction. Les affirmations sont **énumérées, pas découvertes**, pour la raison qui gouverne la passe des variables : un balayage aveugle des nombres qui ressemblent à des versions signalerait React 19, JUnit 5 ou « brokers Kafka 2.1+ », et un contrôle à faux positifs est un contrôle qu'on apprend à ignorer. Le coût est visible au lieu d'être caché — le run annonce combien d'affirmations il a résolues, donc une affirmation absente de la table est *non vérifiée* plutôt que tacitement bénie. Une forme abrégée résout par préfixe **à une frontière de composant** (« Flink 2.3 » contre `2.3.0`, le badge `Kafka-4.3_KRaft` contre `4.3.1`), jamais par correspondance floue : c'est ce qui fait que `1.18` ne résout pas contre `2.3.0`. Et ce qui décrit sciemment le passé s'exempte **nommément** dans `HISTORICAL` — « le NPE se reproduisait sur Flink 1.18 » est vrai et ne doit pas être tiré vers le présent ; `DOCKER-AUDIT.md` n'est pour la même raison pas dans `VERSION_DOCS` du tout, étant un compte rendu de ce qui a été corrigé.
- **Les captures d'écran sont générées, pas prises à la main** : `docs/screenshots/` (`server.mjs` + `fixtures.mjs` + `capture.mjs`, voir son README) sert le SPA compilé au-dessus de réponses d'API figées et le pilote avec Playwright/Chromium vers `docs/img/*.png`. L'UI photographiée est la vraie (les composants compilés depuis `src/main/webapp`) ; seules les **données** sont figées, calquées sur ce que `setup-demo.sh` sème réellement — monter Kafka + Flink + un cluster semé pour prendre une photo n'est ni reproductible ni faisable depuis un build de doc. Les horodatages dérivent d'un instant fixe, donc une reprise produit la même image plutôt qu'un diff illisible. Huit écrans sont photographiés, Data Model compris — sa sélection fait l'aller-retour par la query string, donc la capture emprunte le chemin d'un lien partagé comme les autres. `CHROMIUM_PATH` désigne un binaire déjà présent, sur le modèle de `PNGQUANT` : sur une image qui fournit son Chromium (`PLAYWRIGHT_BROWSERS_PATH`) alors que playwright a été installé à part, les deux numéros de version divergent et le lancement réclame un téléchargement que l'image interdit. Les écrans sont atteints **par URL** (les pages font l'aller-retour de tout leur état par la query string, et un lien partagé se rejoue à l'ouverture), pas par des clics : la capture emprunte le même chemin de code qu'un lien collé par un collègue et ne casse pas le jour où un bouton bouge. Les fixtures suivent les contrats TypeScript du front — un champ manquant se voit à l'écran (`NaN`, panneau vide) et non par une erreur.
- **`docs/img/` est publié par GitHub Pages** (le job `deploy` de `ci.yml` téléverse `./docs`), et c'est ce qui rend les captures atteignables depuis Docker Hub : `docs/DOCKERHUB.md` les charge en URL absolue `https://devdownin.github.io/Kafkaexplorer/img/…`, un chemin relatif au dépôt s'y affichant en image cassée. Les deux publications partent du même push sur `main`, donc une toute première synchro peut précéder de quelques secondes le déploiement Pages ; ça se résout tout seul. Les README, eux, utilisent le chemin relatif — GitHub les rend depuis le dépôt.

## Secrets & CI

- **Ne jamais mettre de vraie clé en fallback Spring** : `${ANTHROPIC_API_KEY:sk-ant-...}` expose la clé dans git. Utiliser `${ANTHROPIC_API_KEY:}` (fallback vide).
- Si une clé est commitée : `git reset --soft HEAD~N` pour réécrire le commit, puis recommiter proprement.
- **Rebase conflicts** : dans un `git rebase`, `--theirs` = le commit local rejoué, `--ours` = la branche upstream. Pour accepter tous les fichiers conflictuels en faveur du commit local : `git checkout --theirs <fichiers> && git add -u && git rebase --continue`.

