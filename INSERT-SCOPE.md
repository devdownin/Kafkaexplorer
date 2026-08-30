# Running an `INSERT` — scope

Today this application cannot run an `INSERT`. Three guards refuse one, in this order: the SQL
editor refuses before anything leaves the browser, `FlinkSqlService.executeSync` refuses on the
server, and `executeSql`'s whitelist would refuse it a third time. `CREATE TABLE … AS SELECT` is
refused separately, being an INSERT wearing a CREATE TABLE hat.

Underneath those refusals, `FlinkSqlService.submitJob` submits real continuous jobs and is one of
the best-tested methods in this repository. Nothing reachable over HTTP calls it.

This document sizes the gap. It implements nothing.

> **Read the next section before the estimates.** The reason this feature was removed is not the
> reason it would cost anything to bring back, and the two were nearly conflated.

## What actually happened, from the commit dates

`85a4bcf` removed Flink Job mode from the editor with one sentence of justification: *"It did not
work, and a gesture whose only possible outcome is a failure is worth less than no gesture."* That
is true of the state it was written against. It is worth reading what that state was.

| When | Commit | What |
|---|---|---|
| 08-29 **18:05** | `735b900` | *Make the Flink engine run, and make it say so when it does not.* Names the failure exactly: **"An INSERT INTO in Flink Job mode answered `Internal Server Error` with no body. Three defects sat behind it."** All three are fixed in that commit. |
| 08-29 **18:34** | `8ad47d6` | `submitJob` registers its source table, like a read does — the "Object not found" on a correct topic name. |
| 08-29 **19:00** | `85a4bcf` | Job mode removed from the editor: *"it did not work"*. **55 minutes after the fix.** |
| 08-29 **19:15** | `b7b0c84` | `POST /api/query/jobs` and `submitJob` deleted as a path with no caller. |
| 08-30 **05:56** | `810008b` | `submitJob` **restored**, and enumerated against a real MiniCluster — 21 cases, two further defects found and fixed. |
| 08-30 **10:12** | `e50cd27` | The editor's `SubmittedJobPanel` removed (it no longer compiled). |
| 08-30 **later** | `ff09c00` | The Dashboard's job table, the three job reads and `FlinkJobStore` removed. |

The three defects `735b900` fixed were:

1. `json.ignore-parse-errors` written without its `value.` prefix, which this connector refuses
   when it builds a source or a sink — never at `CREATE TABLE`. A SELECT swallowed this as an
   engine failure and fell back to the direct reader; **an INSERT has no fallback**, so it was the
   only statement that surfaced it.
2. `NUM_TASK_SLOTS` = 8 against a default parallelism of the host's core count, so on any machine
   past eight cores every subtask sat in `SCHEDULED` until the budget expired.
3. The collection loop calling `hasNext()` before checking the row quota.

None of the three is about job submission. Two were degrading **every** SELECT in silence, and the
INSERT was simply the statement with nothing to hide behind. So the judgment "it did not work" was
correct about a build in which the planner answered essentially nothing, and was never re-taken
against the build that landed 55 minutes earlier.

**This is not an argument that the feature should come back.** It is the correction of the premise:
the estimates below are for exposing a tested service path, not for rebuilding something that
failed.

## What exists today

| Piece | State |
|---|---|
| `FlinkSqlService.submitJob` | 63 lines. Classifies the statement past a leading CTE, auto-registers **every** source (`FROM` and each `JOIN`), validates, submits, registers the `JobClient` under the caller's own query id. |
| `FlinkSqlServiceInsertVariantsTest` | 584 lines, **21 cases**, real jobs on a local MiniCluster over bounded sources and a `blackhole` sink. Column lists in and out of sink order, `VALUES` with no source at all, aggregates, joins, unions, derived tables, a CTE inside the INSERT, lowercase, leading comments, a trailing semicolon, `PARTITION`, `INSERT OVERWRITE`, `STATEMENT SET`, and the refusal side — *who* is declared at fault, since that decides the HTTP status. |
| `explorer.max-concurrent-jobs` | 10, 0 removes the cap. Refusal names the count and the setting. |
| `POST /api/query/cancel/{queryId}` | Works. Reads the in-memory registry; `CancelOutcome` distinguishes "cancelled" from "nothing to cancel". |
| `heldJobs` + `getHeldJobs()` | The live registry, swept on read. Three consumers already act on it: `ConfigController` refuses a cluster repoint with 409 while jobs run, `LineageService` draws a node per job, `MetricSuggestionService` derives a pipeline edge from each. **All three currently see only synchronous reads**; a real submission is what they were written for. |
| `SqlErrorClassifier` | Splits planner failures into `USER_ERROR` (400) and `ENGINE_ERROR` (500), including the two wordings Flink uses for a projection that does not fit the sink. |

So the engine, the cap, the cancel, the error classification and the three downstream consumers all
exist and are tested. What is missing is a door and a window.

## The ceiling this feature cannot exceed

Say it before sizing anything, because it decides whether any of this is worth doing.

**A submitted job lives in an embedded MiniCluster inside the process that serves the UI.** Measured
on this runtime: **~80 threads and ~6 MB of heap per job** (six held jobs: 482 threads). It dies
when the application restarts, and no store can resurrect it — the old `FlinkJobStore` persisted
*records*, never jobs, which is why a restart left a file describing jobs that no longer existed.

That is the honest shape of the feature: **a pipeline you can start from the UI and lose on the next
deploy.** For anything meant to keep running, the answer is and stays a Flink cluster of your own,
which is exactly what the current refusal says. What this feature buys is the *other* case —
trying a pipeline out, seeing rows land in a target topic, without leaving the tool.

## Work items

Sizes are days of focused work by someone who knows this codebase, tests and documentation
included, at this repository's standard (a defect is fixed with the paragraph explaining it, every
new `explorer.*` key has a reader, `mvn verify` green).

### I1 — `POST /api/query/jobs`, and nothing else — **0.5 day**

One `@PostMapping` calling `submitJob`, with the 400/500 split from `SqlErrorClassifier` that
`QueryController` already applies elsewhere. `QueryControllerTest.thereIsNoJobSubmissionEndpoint`
and `theWholeJobSurfaceIsGone` invert into cases that assert the contract.

Buys: an INSERT becomes runnable by `curl`, and the three `getHeldJobs()` consumers start seeing
real jobs. Costs: a second unauthenticated path into the query engine, on an application that ships
without authentication — `SECURITY.md` already states the deployment constraint, and this widens
what is behind it from *reads* to *writes on the user's cluster*. **That is the real decision in
this document**, and it is a product decision, not a technical one.

Not enough on its own: a job you can start and cannot see is worse than one you cannot start.

### I2 — The editor submits, and says what became of the job — **2 days**

Restore the mode selector and a status panel. `git show e50cd27^:src/main/webapp/src/components/query/SubmittedJobPanel.tsx`
is 155 lines and already correct about the thing such panels get wrong: it distinguishes *running*,
*ended*, and *its status could not be read*, and refuses to let the green of the submission stand
for a verdict. Its 107-line test comes with it.

What does **not** come back, because it was removed for its own reasons and would have to be
re-argued: `pickSinkTable`, `insertableColumns`, `sinkNameRange`, `starterJobQueries` — the
affordances that posed an `INSERT INTO` skeleton with a target chosen out of the catalogue.

Depends on I1. Depends on I3 for the panel to have anything to poll.

### I3 — A read for one job's state — **1 day**

The panel polls something. `GET /api/query/jobs/{queryId}` served `statusDetail`, `errorMessage`
and a dated history; all three came out of `FlinkJobStore`, which is gone. Two ways:

- **From the registry alone** (0.5 day): `buildJobSummary` on the held `JobInfo`. Gives the status
  and the timestamps, no history, and **nothing at all once the job ends and is swept** — which is
  precisely the moment the operator wants an answer.
- **With a bounded in-memory ring** (1 day): keep the last N ended jobs with how they ended. No
  file, so no restart durability — which is honest, since the *jobs* have none either. This is the
  version that answers "what happened to my INSERT".

Take the second. The first has a hole exactly where the question is asked.

### I4 — Somewhere to see and stop what is running — **1.5 days**

A list, and a Stop button per row. `POST /api/query/cancel/{queryId}` already backs it. Where it
goes is a real choice:

- **On the SQL editor**, beside the panel — the operator is already there, and the list is short.
- **Back on the Dashboard** — which is where it was, and where it was wrong: a panel whose count is
  almost always zero on a page polled every five seconds. If it returns, it returns *because a
  submission exists*, and its empty state has to be honest about that.

Recommend the editor. The Dashboard version is what this repository just removed, and re-adding it
without a submission behind it would be the same defect.

### I5 — The `INSERT` lesson on the Help page — **0.5 day**

`85a4bcf` removed it along with the `runnable` flag it was the only user of, so that every Help step
is a statement the editor actually runs. Restoring it means restoring that flag and the test that
keeps every example executable against what `setup-demo.sh` seeds.

### I6 — A demo target topic and an end-to-end CI assertion — **1 day**

`setup-demo.sh` seeds no sink. Without one there is nothing to demonstrate and nothing for
`ci.yml`'s `docker` job to assert against — and that job is where the two defects of `735b900`
would have been caught, since a MiniCluster test cannot refuse a connector option the way a real
broker does. **This is the item that keeps the feature from silently rotting again.**

### Totals

| Scope | Items | Days |
|---|---|---|
| **Minimum honest feature** | I1 + I3 + I4 + I6 | **4** |
| **Plus the editor** | + I2 | **6** |
| **Complete** | + I5 | **6.5** |
| API only, deliberately headless | I1 + I6 | **1.5** |

Add roughly a day if `POST /api/query/jobs` is judged to need authentication of its own; that is a
larger question than this feature and `SECURITY.md` currently answers it with "deploy it behind
something".

## What would change the recommendation

- **Nobody wants to write to their cluster from this tool.** Then none of it, and the current
  refusal is already the right answer — it names the shape of the screen rather than a security
  rule, and points at running the pipeline as a Flink job of your own. Delete `submitJob` and
  `FlinkSqlServiceInsertVariantsTest` with it, and the `explorer.max-concurrent-jobs` cap.
- **A job has to survive a restart.** Then this is the wrong design altogether and no number of days
  fixes it: submit to a real Flink cluster over its REST API instead of an embedded MiniCluster.
  That is a different feature with a different name, and the estimate above says nothing about it.
- **Only `curl` needs it.** I1 + I6, 1.5 days, and the editor keeps its single execution path.

## Non-goals

- Resurrecting `FlinkJobStore`. Its measured cost is in `FLINK-JOBS-AUDIT.md` — a whole-list JSON
  rewrite per record, 17.3 ms at 10 000 records — and it recorded every planner statement, not
  every job. If job history is wanted, I3's bounded ring is the shape, sized for jobs.
- Re-adding the Dashboard's job panel as it was. See I4.
- Lifting the `INSERT` refusal in `executeSql`'s whitelist or the `CREATE TABLE … AS SELECT`
  refusal. Both exist because those paths start a job that enters no registry, counts against no
  cap and has no id anything can cancel. A submission path does not change that.
