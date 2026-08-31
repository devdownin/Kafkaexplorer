# Flink jobs — audit (2026-08)

Everything this application asks the embedded Flink runtime to do becomes a *job*, and there is a
small subsystem whose whole purpose is to know which jobs exist, what became of them, and to let
one be stopped: `FlinkSqlService`'s in-memory registry (`heldJobs`, `JobInfo`, `buildJobSummary`,
`syncPersistedJobs`), the file it is mirrored into (`FlinkJobStore`, `data/flink-jobs.json`), the
four endpoints on `QueryController` behind `FlinkJobService`, and the "Flink SQL Jobs" panel of
`Dashboard.tsx`.

Four things read that subsystem's answer, and three of them *act* on it: `POST /api/config` refuses
a cluster repoint with 409 while jobs are running, `LineageService` draws a node per job,
`MetricSuggestionService` derives a pipeline edge from each, and the dashboard offers a Kill
button. It had never been reviewed, and `FlinkJobStore` had no test of its own.

This document is that review, in the shape the other scope documents here take: every item is
derived from the code, names where it comes from, and is ranked.

> **Status — the subsystem this reviews no longer exists.** `FlinkJobStore`, `FlinkJobService`,
> the four `QueryController` endpoints and the Dashboard's "Flink SQL Jobs" panel have all been
> removed. Flink Job mode — the continuous `INSERT INTO` submitted from the SQL editor — was the
> reason they existed; once it went, the panel listed not jobs but the synchronous reads the store
> recorded on the way past, all finished, and the store was a whole-list rewrite per planner answer
> for a reader that no longer showed anything. What survives is `heldJobs` and its accessor, which
> is what `POST /api/query/cancel/{queryId}`, the 409 repoint guard, `LineageService` and
> `MetricSuggestionService` actually read. The document is kept as the record of what was measured
> — the measured write costs below are the reason not to reintroduce the file — not as a plan.
>
> **Status of the items themselves.** **F1 through F8 had shipped**, in one change, with `FlinkJobStoreTest` (new) and
> three cases added to `FlinkSqlServiceJobRegistryTest`. What each section below describes is the
> state that work was done *from*; each ends with what replaced it. **F9 and F11 through F13 are
> recorded and not implemented** — the reason is given under each, and in two cases the reason is
> that the fix would be a behaviour change nobody has asked for.
>
> **A later change closed F10 and the first two items of the worklist.** The two uncalled
> endpoints are called now: the dashboard's job card opens onto the job's history, which is what
> made them worth keeping rather than deleting. And the live registry was renamed — `activeJobs`
> → `heldJobs`, `getActiveJobsDetails()` → `getHeldJobs()` — because "active" meant two different
> things on two methods a caller has to choose between.

> **What this audit is derived from.** The code and the shipped configuration, plus the test
> harness: `packages.confluent.io` is blocked from this sandbox, so the suite runs through
> `./verify-offline.sh` rather than `mvn verify`. **Every defect below was checked to fail against
> the behaviour it describes**: the eight fixes were reverted in one pass and the suite re-run, and
> exactly the nine cases that name them went red while the other twelve stayed green. The two that
> did not move are the ones that never claimed to — `anObservationThatChangedSomethingIsWritten`
> (the positive control for F6) and `aFileThatWillNotParseCostsItsContentAndNothingElse`, which
> pins behaviour the old `load()` also had and is a regression guard rather than a defect proof.
>
> **What is *not* measured here is cost.** Every figure below about what a poll or a write costs is
> arithmetic on the configured cadences, not an observation — see the last item of the worklist.

The single sentence, if there is only room for one: **three different states of the world were all
being written down as "the job is over"** — the job ended, the runtime no longer holds it, and we
could not read its status — and the last one is not an observation at all.

---

## F1 — A status that could not be read was filed as a job that had ended

`FlinkSqlService.buildJobSummary` polls `JobClient.getJobStatus()` with a 150 ms budget. It has
three exits, and until now two of them wrote the same thing:

```java
} catch (IllegalStateException gone) {
    // the MiniCluster is down: the job is over. UNKNOWN, endedAt set.
} catch (Exception e) {
    status = info.cancelRequested() ? "CANCEL_REQUESTED" : "UNKNOWN";
}
```

The comment on the first branch is careful about exactly this distinction — *"what we do not know
is how it ended, so the status stays UNKNOWN"* — and the branch below it then reuses UNKNOWN for a
case where we do not know that it ended at all. `FlinkJobStore.isTerminal` counts UNKNOWN as
terminal, and `update()` stamps `endedAt` on anything terminal, so **one status call that ran out
of its 150 ms ended a running job on paper**: it left `getActiveJobs()` — which is the dashboard's
list, `listActive()` filtering on exactly that — and it went onto the retention clock, so a long
`INSERT INTO` could be pruned out of the store *while it was still running*.

It survived because the in-memory half is right: `JobInfo.endedAt` stays null on that branch, so
`heldJobs` keeps the job and `getHeldJobs()` — the 409 guard — still sees it.
`FlinkSqlServiceJobRegistryTest.aJobWhoseStatusTimesOutStaysActive` pinned that half, and only that
half.

**Shipped.** `FlinkSqlService.STATUS_UNAVAILABLE` (`"UNAVAILABLE"`), which the store does not count
as terminal, plus a DEBUG line naming the reason through `SqlErrorClassifier.explain`. The
dashboard renders it neutrally with no change: its badge falls through to `neutral`, and its Kill
button stays enabled, which is correct — the job may well still be running. Pinned by
`aStatusThatCouldNotBeReadDoesNotEndTheJob`.

## F2 — Cancelling a job that had already ended erased how it ended

`cancelQuery(queryId)` with no live `JobInfo` fell through to:

```java
flinkJobStore.update(queryId, "UNKNOWN",
    "Cancellation requested but no live Flink JobClient was available", …);
```

The store's one job is to remember what became of a statement. This overwrote a recorded `FINISHED`
or `FAILED` with `UNKNOWN` — and the gesture that triggers it is the most ordinary one there is:
pressing **Kill** on a dashboard card whose job completed between two five-second polls. The card is
still on screen, the job is gone, and the click destroys the only record of how it went, in the
store and in the history `GET /api/query/jobs/{id}` serves.

**Shipped.** The status is left alone when the stored job is already terminal (`null` status means
"keep what is there" to `update`), and the attempt is still recorded — `cancelRequested`, and a
history entry reading *"Cancellation requested after the job had already ended"* under the status
that was actually observed. `FlinkJobStore.isTerminal` became package-visible for it rather than
the caller re-deciding what terminal means. Pinned by `cancellingAJobThatAlreadyEndedKeepsHowItEnded`.

## F3 — A synchronous read never handed its job back

`executeViaFlinkPlanner` puts a `JobInfo` into `heldJobs` when the planner returns a `JobClient`,
and **nothing removed it when the query returned**. The only thing that ever swept it was
`syncPersistedJobs()`, which runs when somebody calls `getActiveJobs()`, `listRecentJobs()`,
`getJob()` or `getHeldJobs()` — that is, when a browser is looking.

So on a deployment with no browser open, the registry grew without bound, each entry holding a
`JobClient`; and the thing that feeds it is not the operator but a timer, since `MetricService`
refreshes on a thirty-second loop and any metric whose SQL the planner answers registers a job.
This is the same shape as the defect `getHeldJobs()` was fixed for, one method over, and
it stayed invisible for the same reason: an open dashboard is a sweeper.

**Shipped.** `releaseSyncJob(info)` from a `finally` on `executeViaFlinkPlanner`: a synchronous read
is over when the method returns, and nothing else can know that. The cancel endpoint still finds the
entry *while* the query runs, which is the entire point of a client-supplied query id. Whatever the
runtime still says about the job is recorded rather than invented — a job whose MiniCluster is
already gone is filed as UNKNOWN, exactly as the sweep would have filed it — and the bookkeeping is
wrapped so it can never replace the answer the caller is holding. Pinned by
`aSynchronousReadHandsItsJobBackWhenItReturns`, whose first assertion is that a job was registered
at all, or the second would be vacuous.

## F4 — The store is fed by every statement and was bounded only by time

`explorer.flink-job-retention-hours` (24) is the right rule for what this store was written for:
`INSERT INTO` statements submitted in Flink Job mode, a handful a day, which is why they are worth
keeping across a container recreate. It is the wrong rule for what actually writes here — *every*
statement the planner runs as a job, which is one record per Run in the SQL editor, one per
planner-answered metric refresh (≈2 900 a day, per metric, at the default 30 s cadence), and one per
boot for the warmup probe.

Nothing bounded the count, and every write serialises the whole list pretty-printed, so the file and
the cost of each write grow together with the day's traffic.

**Shipped.** `FlinkJobStore.MAX_RETAINED_JOBS` = 200, dropping the oldest **ended** jobs first and
never a job that is still running — same shape as `AuditService.MAX_RETAINED_RUNS` and
`FieldMappingStore.MAX_ENTRIES`. A running job is precisely the row the dashboard, the 409 guard and
the lineage graph are asking about, so the bound must not be what removes it; pinned both ways
(`theStoreIsBoundedInCount`, `aRunningJobSurvivesTheCountBound`).

## F5 — A refused statement's credentials went to disk, and back out through the API

`DdlGeneratorService.maskSensitiveProperties` exists because a Flink `CREATE TABLE` embeds Kafka
client properties — the SSL passwords, the Confluent `sasl.jaas.config` secret — and this codebase's
rule, stated in `CLAUDE.md` and enforced on four endpoints, is that DDL returned to the UI or written
to a log goes through it. `FlinkJobStore` did neither: it wrote `sql` verbatim into `data/` and
served it verbatim from `GET /api/query/jobs`.

The case that makes it concrete is the one that looks harmless. `submitJob` **rejects** a non-INSERT
statement — and files it first:

```java
flinkJobStore.create(queryId, null, statementType, "ASYNC_JOB", "FAILED",
    "Rejected before execution", strippedSql, startedAt, "Only INSERT INTO …");
throw new IllegalArgumentException(…);
```

So pasting a hand-written `CREATE TABLE` into the editor with Flink Job mode selected — an easy
mistake, and the error message invites you to notice it *after* the fact — persists its passwords.

**Shipped.** Masked in `FlinkJobStore.create`, i.e. at the door, so every path into the store is
covered rather than the two known ones. Nothing here replays the SQL (unlike `FlinkTableStore`,
which must keep it exact), so masking costs nothing. Pinned by `aStoredStatementCarriesNoCredentials`,
which asserts on the served record *and* on the file.

## F6 — The store rewrote its whole file to record that nothing had happened

`update()` always built a new record with `lastUpdatedAt = now` and always flushed. Its caller is
`syncPersistedJobs()`, which runs on every dashboard poll — five seconds by default — once per live
job, and a running job answers `RUNNING` every time. So the whole file was rewritten on every poll
per job, to record a fact about the clock.

Beside it, `submitJob` and `executeViaFlinkPlanner` each called `create(...)` and then
`persistJobSnapshot(...)` with the same status and the same detail: a second full rewrite,
immediately, changing nothing.

**Shipped.** `update()` compares substance — everything a reader would notice, `lastUpdatedAt`
excluded — and hands the existing record back untouched when nothing moved; `lastUpdatedAt` means
"when this record last changed", and a record that did not change did not change. Both redundant
`persistJobSnapshot` calls are gone. Pinned by `anObservationThatChangedNothingIsNotWritten` and its
opposite number.

## F7 — Retention only applied while something else was writing

`pruneExpired()` ran from `listAll()`, `findById()` and `flush()`, and only `flush()` writes. A store
whose last job ended long ago therefore kept its expired records on disk for good: they were pruned
in memory at each boot, and written back the first time anything else happened to change. Retention
that applies only while the application is busy is not retention.

**Shipped.** `prune()` returns whether it removed anything, and the readers flush when it did —
including `load()`, so an expired record is gone from the file at the next boot even if nothing else
is ever written. Pinned by `anExpiredRecordIsGoneFromTheFileAtBoot`.

## F8 — The file was written in place and world-readable, next to the class written for that

`JsonStoreFile` exists in the same package, and its own javadoc says why: *"Two stores need exactly
the same thing and for the same reasons"* — replaced rather than written in place, because the one
moment either file is read is a boot, which is exactly when an interrupted write from the previous
run surfaces; and owner-only, because `data/` is a volume an operator may mount beside other things.

There are three stores under `data/`, and this was the one not using it. The consequence is not
hypothetical for a file rewritten every five seconds: a crash or a full disk mid-write leaves JSON
that will not parse, and `load()` then starts empty — the loss of every job record, rather than of
the one being written.

**Shipped.** `FlinkJobStore` reads and replaces through `JsonStoreFile`, and a file that will not
parse costs its content and nothing else. Pinned by `aFileThatWillNotParseCostsItsContentAndNothingElse`
and `theFileIsNotWorldReadable` (which returns early where the filesystem has no POSIX permissions —
that is `JsonStoreFile`'s documented posture, not an omission).

---

## Recorded, not implemented

### F9 — The query id is client-supplied, and a collision silently replaces a live job's handle

`resolveQueryId` accepts any client-supplied `queryId` matching `[A-Za-z0-9_-]{8,64}`, which a UUID
matches — and job-mode ids *are* UUIDs. A second request naming an id already held would
`heldJobs.put` over the live `JobInfo` and `flinkJobStore.create` over its record: the running job
becomes unreachable and uncancellable, and its history restarts.

Not fixed, because every fix is worse than the defect at today's severity. Minting a fresh id
silently breaks Stop for the request that asked; refusing the second request changes what a
well-formed call does, and the editor's `Run all` sends statements sequentially under ids this
audit has not established are distinct. It is not reachable by accident — two callers must choose
the same 8-to-64-character id — and the application has no authentication anyway, which is a
deployment constraint `SECURITY.md` already states. Worth revisiting if the editor ever runs
statements concurrently.

### F10 — Two of the four job endpoints have no caller

`GET /api/query/jobs` and `GET /api/query/jobs/{queryId}` are called by nothing: the SPA uses
`POST /api/query/jobs` and `POST /api/query/jobs/{id}/cancel`, and reads the job list off
`GET /api/dashboard`. This repository deletes uncalled endpoints on a stated rule — *"an endpoint
nobody calls is an entry point nobody guards"*, which took out `TableController` and
`POST /api/metrics/preview`.

They were left, and the distinction from those two is worth writing down: both of those were a
*second* path to a behaviour another endpoint already offered, which is how two paths to one
behaviour drift. These two are the only way to reach a job's history and status detail at all —
which is real content nothing else serves, since `FlinkJobSummary` drops `statusDetail`,
`errorMessage` and `history`. The right resolution is to use them, not to delete them.

**Shipped.** The dashboard's job card opens onto the record: `components/dashboard/FlinkJobCard.tsx`
reads `GET /api/query/jobs/{queryId}` **on the click and once**, and renders the outcome, the error
message, the execution mode and the dated transitions with how long each state lasted. On the
click, because the dashboard polls every five seconds by default and a read per card per turn would
make everyone pay for a detail nobody is looking at; once, because folding and reopening should not
re-ask. A read that fails leaves the server's reason under the card rather than a toast — it is what
you came to read. The reading logic is `pages/flinkJobHistory.ts`, pure and tested, and it keeps the
distinction the server side was fixed for: `UNAVAILABLE` is described as a status that could not be
read, explicitly *not* as an ending.

### F11 — Two definitions of "terminal", and a `substring` on a field that can be null

`Dashboard.tsx`'s `isTerminalStatus` lists `FINISHED / FAILED / CANCELED / CANCELLED`;
`FlinkJobStore.isTerminal` adds `UNKNOWN`. And the card runs `job.flinkJobId.substring(0, 16)` on a
field the server can send as `null` — `create` is called with a null Flink job id on both submission
failure paths.

Both are unobservable today, and by the same mechanism: those records are `FAILED` or `UNKNOWN`,
therefore terminal, therefore filtered out of `getActiveJobs()`, which is the only thing feeding
that panel. They are recorded because the mechanism is one filter, one screen away from each of
them, and because the pattern — a rule written on both sides of the wire — is one this codebase
keeps removing.

### F12 — The panel's empty state describes something narrower than what it lists

*"Long-running Flink SQL statements will appear here while they execute."* What it lists is every
statement the planner runs as a job, including a sub-second `SELECT` and, for the first moments of a
boot, the warmup probe. This is cosmetic and stops mattering with F3 (a synchronous read now leaves
the registry when its request returns, so its window on that panel is genuinely the time it ran), so
it is left as an observation rather than a wording change nobody asked for.

### F13 — The status sweep fans out one thread per job, per poll

`syncPersistedJobs()` submits one `CompletableFuture` per live job to a cached thread pool and joins
them all, on every call — and `getActiveJobs()` is on the dashboard's five-second poll. The comment
explains the parallelism (serial, it would block up to N × 150 ms) and it is right. It is recorded
because F3 and F4 change the arithmetic it was written against: the registry no longer accumulates
synchronous reads, so N is now the number of genuinely running jobs, which is small. Nothing to do
unless that stops being true.

---

## What the suite would have caught before this change: none of it

`FlinkJobStore` had no test. `FlinkSqlServiceJobRegistryTest` covered the in-memory registry
carefully — including the two exits of `buildJobSummary` that were *right* — and never asserted
anything about what reached the store, which is where F1, F2, F5, F6, F7 and F8 all live. The new
`FlinkJobStoreTest` is eight cases against the store itself; the three added to the registry test
are the service-level halves of F1, F2 and F3.

## What I would do next, in this order

1. ~~**Give the job history a screen** (F10).~~ **Done** — see F10 above.
2. ~~**Decide what the live registry is for** (F3, F13).~~ **Done, and the framing in the first
   draft of this list was wrong.** It said the map "holds submitted jobs and nothing else", which
   it does not: a synchronous read is in it for the duration of its request, and has to be, or
   `POST /api/query/cancel/{queryId}` could not find it. What was actually wrong was the *word* —
   `activeJobs` beside `getActiveJobs()`, which answers from the store on a different definition of
   active. It is `heldJobs` / `getHeldJobs()` now, with the two populations and their two lifetimes
   written down where the field is declared.
3. ~~**Measure a poll.**~~ **Done — see below.** It closes F13 and it is what turns F4 and F6 from
   arguments into arithmetic.

---

## The measurement, taken

Everything above about cost was arithmetic on the configured cadences. These are numbers.

**Method.** A throwaway JUnit harness under `./verify-offline.sh`, since that is the only way to
build here: a real `FlinkJobStore` on a temporary file, a `FlinkSqlService` around it with the rest
mocked, and `heldJobs` populated with N `JobInfo`s whose `JobClient` answers `RUNNING` from an
already-completed future. Median of 100–200 iterations after 20 warm-up calls. The harness is not
committed — a benchmark CI never runs is the same dead weight as an endpoint nobody calls — so what
is kept is the numbers and what produced them.

**What it does not measure**, and the omission matters in one direction only: the status call
answers instantly, where the real one is bounded at 150 ms; the store file is on this sandbox's
temporary filesystem, not a named volume; and the JVM is warm. So these are floors on a real
deployment, and the first of them is the reason the poll is fanned out at all.

### One `getActiveJobs()` poll

| held jobs | nothing changed | something changed |
|---|---|---|
| 1 | 161 µs | 195 µs |
| 5 | 128 µs | 242 µs |
| 20 | 258 µs | 372 µs |
| 50 | 420 µs | 601 µs |

**F13 is closed by this, as an observation rather than as work.** The fan-out costs about **8 µs per
job** in submit-and-join overhead, against the up-to-150 ms of *serialised* waiting it exists to
avoid on a runtime that is slow to answer. At fifty live jobs — far more than this application
produces now that a synchronous read hands its job back — the whole bookkeeping half of a poll is
under half a millisecond. There is nothing here to fix.

### What one record's write costs, against how many the store holds

| records retained | file | one write |
|---|---|---|
| 10 | 15 kB | 111 µs |
| 200 (`MAX_RETAINED_JOBS`) | 116 kB | 436 µs |
| 2 000 | 1.07 MB | 3.5 ms |
| 10 000 | 5.31 MB | 17.3 ms |

**This is F4 and F6 in one table**, and it is the measurement that was missing when both were argued
from first principles. Every write serialises the whole list, so the cost is linear in what the
store has accumulated — and what accumulates is not the handful of submitted jobs the file was
written for but every statement the planner runs as a job. Four planner-answered metrics on the
default 30 s loop reach ~11 500 records inside the 24 h retention window: a 5 MB file, **17 ms per
write**, and — before F6 — one write per live job on every five-second dashboard poll, to record
that a running job was still running.

The count bound puts the ceiling at the second row, where a write is under half a millisecond; F6
removes the write entirely in the case that was producing almost all of them. Neither number is
alarming on its own, which is precisely why an unbounded store went unnoticed: the file has to grow
for a day before the cost is visible, and then it is visible every five seconds.
