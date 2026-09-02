# SQL Editor — audit (2026-08)

Full review of the SQL editor: `pages/QueryWorkbench.tsx` and the pure modules it drives
(`queryError.ts`, `sqlScope.ts`, `windowSql.ts`, `resultExport.ts`), plus the endpoints behind it
(`QueryController`, `SqlQueryValidator`, `FlinkSqlService.executeSync`). Four axes were asked for —
reliability, ergonomics, optimisation, UI quality — and they are the four sections below.

Everything listed as **fixed** is fixed on this branch. What was found and deliberately left is in
*Constaté, non traité* at the end, with the reason.

The pure logic extracted along the way lives in `pages/queryWorkbench.ts`, covered by
`pages/queryWorkbench.test.ts` (102 cases); the backend fixes are pinned by `QueryControllerTest`
and `SqlStatementsTest`.
**The wiring has its own tests** — `pages/QueryWorkbench.test.tsx`, the first component test of any
page in this repository. It exists because most of what was fixed here *is* wiring: whether Run
sends what `splitStatements` designated, whether clicking a topic still destroys the tab you are
writing in, whether the detail panel closes when a sort invalidates the index it holds. Monaco is
mocked away — 4 MB of editor jsdom cannot lay out, and none of the behaviour under test needs a real
one. With that net in place the page was split into `components/query/` (`SchemaBrowser`,
`ResultsGrid`, `RowDetail`, `WindowAssistant`, `DdlPreviewModal`), taking it from 2 168 lines down
— in that order, since refactoring what you have just corrected, with no test, is the surest
way to un-correct it.

---

## 1. Reliability

### R1 — `?sql=` reopened the same tab on every reload *(fixed)*

`restoreTabs` appends a tab for the `?sql=` carried by a link from the Topic Explorer, tabs are
persisted to `kse:tabs`, and the parameter stayed in the URL. So a reload restored the tabs — which
already contained the one from the URL — and then appended it again. Every F5 added another copy,
without bound.

The parameter is now consumed once and stripped (`navigate(pathname, { replace: true })`), and an
incoming SQL identical to an open tab focuses that tab instead of duplicating it.

### R2 — `⌘↵` could start a second query over the first *(fixed)*

The Run button disables itself while a query runs, but the Monaco keybinding calls `runQuery`
directly and bypassed it. Two quick `⌘↵` and the second run overwrote `abortRef` and
`runningQueryIdRef`: **Stop could no longer cancel the first query**, and the first query's
`finally` flipped the screen back to "Complete" while the second was still in flight. A
synchronous `executingRef` now refuses the second start and says so — a React state is not yet
updated when both keystrokes land in one tick, which is exactly the case to reject.

### R3 — the `⌘↵` shortcut sometimes never existed *(fixed)*

It was registered in an effect keyed on `[monaco]` that bailed out when `editorRef.current` was
still null. Since Monaco is bundled locally (`monaco-setup`), `useMonaco()` very often resolves
*before* `<Editor>` mounts — and the effect never re-ran, so the shortcut advertised in the toolbar
was simply absent. It is registered from `onMount`, whose second argument *is* the Monaco API, the
one moment both are guaranteed to exist.

### R4 — `closeTab` mutated state from inside a state updater *(fixed)*

`setActiveTabId` was called inside the function passed to `setTabs`. Updaters must be pure; React 19
invokes them twice under StrictMode. The choice of the next tab is now a pure, tested function
(`nextActiveTabId`) applied outside the updater — and closing a tab that holds SQL asks first, since
that SQL exists nowhere else afterwards.

### R5 — three of the four `localStorage` writes were unguarded *(fixed)*

Tabs were wrapped in `try/catch`; saved queries, history and the history *Clear* were not.
`setItem` throws on quota and in some private-browsing modes, so saving a query threw inside a
click handler — which, since uncaught errors now surface to the screen, means a generic error
banner instead of the message that says what could not be kept. All four go through
`writeStored`/`readStored`/`removeStored`, and a save that was *not* written no longer reports
itself as "Saved".

### R6 — the sidebar resize handle jumped on grab *(fixed)*

`setSidebarWidth(clamp(e.clientX, …))` reads a viewport coordinate as if the panel began at the
left edge of the window. It never did: `Layout`'s global navigation occupies 68 px collapsed and
256 px expanded. Grabbing the handle therefore snapped the width by that amount. The width is now
measured from the panel's own bounding rect.

### R7 — export produced a different order than the screen *(fixed)*

The grid renders `sortedRows`; `exportResults` serialised `results.rows`. Sort a column, export,
and the file came out in the engine's order with nothing saying so. Both now come from the same
array, the row count is stated in the confirmation, and the file is named after the tab and stamped
— three exports in a row no longer pile up as `query-results (2).csv`.

### R8 — `/api/query/init` swallowed both of its failures *(fixed)*

The endpoint that fills the schema browser ended each of its two probes in an empty catch
(`// Ignore and show empty list`, `// Flink might be starting up`). An unreachable broker, a
bootstrap address pointing nowhere, an authorisation failure and a Flink runtime still starting up
therefore produced exactly the same screen — "Engine offline · 0 tables · 0 topics" — with nothing
to tell them apart and nothing to act on. `KafkaAdminService.ping()` was the third empty catch on
that path: a bare boolean, so no caller *could* say why.

`pingDetail()` keeps the reason (`ping()` stays as the shorthand for callers that only want the
flag), and `QueryInitResponse` carries `kafkaError` / `flinkError`. The two probes stay independent
— Flink being down must not empty the topic list, and the reverse. The sidebar states each reason
where the empty list is, "No topics with messages" is only claimed when the cluster could actually
be read, and the health dot has **three** states: a broker that answers the probe but refuses a
metadata call is *degraded*, not offline — sending an operator to check a connection that works is
the worst possible lead. `QueryControllerTest` covers the four combinations.

### R9 — `POST /api/query/cancel/{queryId}` never said what it had achieved *(fixed)*

`FlinkSqlService.cancelQuery` returned `void` and the endpoint answered 200 whether it had found a
live `JobClient` or nothing at all, so the caller could not tell a cancelled Flink job from an id
with no job behind it. That is the common case rather than the edge one: a `KAFKA_DIRECT` scan has
**no Flink job by construction**, and its in-flight fetch finishes server-side whatever the client
does. The editor's Stop button had already had to learn this on its own side — "only confirm what
actually happened" — while the endpoint it called still could not tell it.

`cancelQuery` returns a `CancelOutcome` (`CANCELLED` / `NO_ACTIVE_JOB`), both endpoints report it,
and Stop now says either "Flink job cancelled" or "Request aborted — no Flink job to cancel, the
server finishes its in-flight read". Still 200 in both cases: nothing to cancel is a legitimate
outcome of a well-formed request, and the client has aborted its own HTTP request regardless.

### R10 — the history dropdown never closed on its own *(fixed)*

No outside-click handler, no `Escape`. It stayed open over the results panel until the button was
clicked again. Both are wired, plus `aria-expanded` / `role="menu"`.

---

## 2. Ergonomics

### E1 — the seeded query could not run, and it is the first screen *(fixed)*

`DEFAULT_SQL` shipped the first tab with:

```sql
SELECT window_start, window_end, product_id, SUM(quantity) AS total_sales
FROM orders_stream
WINDOW TUMBLING (SIZE 5 MINUTES)
GROUP BY window_start, window_end, product_id
EMIT CHANGES;
```

Two independent problems. `WINDOW TUMBLING (SIZE …) … EMIT CHANGES` is **ksqlDB syntax**, not
Flink SQL — Flink writes `TABLE(TUMBLE(TABLE t, DESCRIPTOR(ts), INTERVAL '5' MINUTE))`. And
`orders_stream` exists nowhere: `setup-demo.sh` seeds `demo.orders.1.received` … `demo.orders.6.delivered`,
`demo.orders.nested`, `demo.iot.sensors`; a grep finds the name only in that literal and in one
`queryError` test fixture. So the most natural first gesture — open the editor, press Run — failed,
and since user errors stopped falling back to the direct reader it failed on a planner error.

The first tab now opens empty, and the results panel offers **starter queries built from the
catalogue that actually loaded** (`starterQueries`, pure and tested): they cannot cite a table that
is not there, `internal.*` topics are skipped since the app writes those to itself, and when the
catalogue is empty there are no suggestions at all — an empty screen beats an example that lies.

### E2 — Run sent the whole tab, whatever the cursor was in *(fixed)*

A tab can hold several statements separated by `;` — the formatter lays them out, the editor accepts
them — but Run sent the entire text and the backend classified on the first word. Selecting by hand
was the workaround. `⌘↵` now runs **the statement the cursor is in**, which is the standard
affordance of every SQL editor; a selection still forces any fragment, and a single-statement
document behaves exactly as before.

`splitStatements` walks strings, quoted identifiers and both comment forms, so a `;` inside any of
them separates nothing. `statementIndexAt` picks the statement *just finished* when the cursor
follows its semicolon — typing the terminator then running means running what you just wrote, and
it is what DataGrip and DBeaver do. `runOrigin` carries the statement's offset so the engine's
line/column still land on the right line of the document. The toolbar states `Statement 2/3` before
you press: "Run statement" without saying which one would be the worrying half of the feature.

### E2b — a common table expression could not be run at all *(fixed)*

Every keyword check in the query path is a `startsWith`, so `WITH recent AS (SELECT …) SELECT * FROM
recent` — ordinary SQL the moment a query outgrows three lines, and fully supported by Flink — was
refused by the statement whitelist with *"Only SELECT, EXPLAIN and CREATE TABLE statements are
allowed"*. That message reads as a security restriction, so the user believes they wrote something
forbidden rather than something the guard never learned.

`SqlStatements.withoutLeadingCte` removes a leading `WITH … AS ( … )` chain so the existing checks
keep classifying the statement they were meant to classify — whitelist, SELECT branch,
auto-registration, and the job-mode split, which now sees that `WITH … INSERT INTO` is an INSERT.
It **fails closed**: any shape it does not recognise comes back unchanged and stays refused, because
a guard that guesses when confused is worse than one that is narrow. `withoutLeadingCte` exists on
the front too, so the execution-mode gate agrees with the engine.

Two consequences worth stating. Auto-registration had to follow — it also bailed on anything not
starting with `SELECT`, so a CTE would have cleared the whitelist only to hit "Object not found";
the first `FROM` sits inside the CTE body, which is exactly the source topic to register. And a CTE
**never falls back to the direct Kafka reader**: that reader regex-matches a table name out of
`FROM`, so it would return rows read from whichever name it matched, silently ignoring the `WITH`
clause. Wrong rows are worse than a refusal — the same reason user errors stopped falling back.

### E2c — a shared `?sql=` link crashed the page whenever the SQL held a `%` *(fixed)*

`restoreTabs` ran `decodeURIComponent` on a value **`URLSearchParams.get()` had already decoded**.
That second pass throws on any lone `%`: `LIKE '%foo%'` comes back from `get()` as `%foo%`, which
`decodeURIComponent` rejects with `URIError: URI malformed`. The read happens inside a `useState`
initializer, so the exception was thrown during render and **the whole editor failed to mount** — an
error screen instead of a page, on the most ordinary predicate an exploration tool has.

It was latent only because nothing produced such a link: the Topic Explorer's "open in editor" emits
`SELECT … LIMIT 50`, which has no `%`. Adding a Link button is exactly what would have made it live.
`readSqlParam` now does the single decode `URLSearchParams` already performs, and `buildQueryLink`
is its inverse; the round trip is pinned on `%`, `&`, `+` and newlines.

### E2d — no way to share a query *(fixed)*

The editor *accepted* a `?sql=` and never produced one, against the repository's own convention —
Stream Flow and the Topic Explorer round-trip their whole state through the query string and each
carry a `Link` button. Saved queries live in one browser's `localStorage`, so showing a colleague a
query meant copy-pasting it into a chat. A `Link` button now copies a replayable URL; stripping the
parameter after it is consumed, added earlier in this branch, is what keeps the link from stacking a
duplicate tab on every open.

### E2e — no way to run a script *(fixed)*

Since Run targets the statement under the cursor, a tab holding `CREATE TABLE …;` then `SELECT …;`
had to be run piece by piece. Before, Run sent the whole text — but the backend classified on the
first word, so the case was never served either. Creating a table and then reading it is exactly what
the auto-registration flow encourages.

`Run all` executes them in order and **stops at the first failure**, naming which one stopped it.
Stopping is deliberate: statements in one tab usually follow because each depends on the last, so
carrying on into a table that was never created would produce a second error that buries the first.
The execution of a single statement was extracted (`executeStatement`) rather than duplicated — a
sequential runner that re-implemented the mode guards and the pre-flight would drift from them, and
those guards are exactly what decides what reaches the engine.

### E3 — four different controls silently destroyed the tab you were writing in *(fixed)*

`updateSql()` replaces the **whole active tab**. It was called by the sidebar's "SELECT from this
table" button, by every Kafka topic in the sidebar, by every history entry, and by the DDL preview's
"Insert in editor". Clicking a topic to see its shape erased the query in progress, with no
confirmation and no undo. This is precisely the defect that was fixed on the Window Assistant
("*Elle écrasait auparavant tout l'onglet*") and left everywhere else.

One rule now, in `openSql`: an empty tab is filled, a tab with content is left alone and the SQL
opens in a new tab, which is what loading a *saved* query already did. The Window Assistant keeps
its own behaviour (insert at the cursor) because it produces a fragment, not a statement.

### E4 — the Format button never formatted anything *(fixed)*

It ran `editor.action.formatDocument`. **Monaco ships no SQL formatter** — for this language it
provides Monarch tokenisation and nothing else (`monaco-editor/esm/vs/languages/definitions/sql/`
holds `sql.js` and `register.js`, no formatting provider). With no provider registered, the action
does nothing beyond a discreet "There is no formatter for 'sql' files installed" inside the editor.
The button had been decorative since it was added.

`formatSql` (pure, 13 test cases) is now registered as a `DocumentFormattingEditProvider`, so the
button and `Shift+Alt+F` both go through it: one clause per line, one select item per line, keywords
upper-cased. String literals, quoted identifiers and comments are moved but never rewritten — that
is what makes a formatter safe, and `WHERE label = 'group by'` is the test that pins it. Function
names are upper-cased from a **closed** list, because an unknown one is most likely a UDF and Flink
does not promise case-insensitive resolution for those: recasing `XmlExtract` would break the query
we claimed to be tidying.

The provider is registered in **`monaco-setup.ts`**, not in the page. The first pass put it in a
`QueryWorkbench` effect, disposed on unmount — and the provider is global to the Monaco instance, so
the app's three other SQL editors (two in `Metrics`, one in `TopicExplorer`) still had none: the
same symptom, in the same places, under a fix that thought it was finished. That module is imported
by each of those pages and evaluated once, which is exactly the scope wanted.

### E4b — autocomplete and hover ignored half the catalogue *(fixed)*

Both providers read `schema.tables` only. But the sidebar lists Kafka **topics**, clicking one
writes `SELECT * FROM demo_orders_1_received`, and the backend auto-registers a topic on a plain
SELECT — so typing that very name offered no completion, and hovering it showed nothing. The half of
the catalogue the editor is built around was invisible to the assistance.

Topics are now offered under their table name (dots and dashes to underscores — the name the query
must carry), labelled *"Kafka topic — registered on first use"*, sorted behind the tables already
registered, and skipped when a table of that name exists. Hover recognises them the same way and
says which topic it is.

### E5 — reading `Rows` and `Offset` was impossible on a narrow window *(fixed)*

`Offset` was hidden below `md`, `Rows` below `lg` — and both kept applying. On a laptop with a
narrow window the query ran EARLIEST/50 with no way to see it, let alone change it. The toolbar's
left group scrolls horizontally instead of dropping controls: a setting nobody can see is the worst
of both worlds. Both also gained a tooltip explaining what they actually select.

### E6 — the results grid did not distinguish NULL from the empty string *(fixed)*

`String(row[col] ?? '')` rendered a SQL NULL and a zero-length string identically. On a result grid
that is the distinction that says whether a LEFT JOIN found its row. `cellText` returns the flag,
NULL renders as a dimmed italic `NULL`, and copying a NULL cell still copies the empty string —
the value, not its rendering.

### E7 — nothing said the displayed rows no longer answered the query on screen *(fixed)*

The grid kept the previous run's rows while the next query was being typed, silently. A `Stale —
rerun` control now appears when the editor's SQL has drifted from what produced the rows
(`isResultStale`, whitespace-insensitive so re-indenting does not invalidate a result). Stream Flow
marks its graph the same way, for the same reason.

### E8 — saving twice under one name made two indistinguishable entries *(fixed)*

Saving now offers to replace, and refuses to save an empty tab.

### E9 — the history said nothing about what a query had done *(fixed)*

Each entry carried `{sql, ts}` and nothing else, so finding "the one that worked" meant re-reading
twenty queries and guessing — and **failures never entered it at all**, although a query you want to
reopen is very often precisely the one that failed. An entry now carries duration, row count,
engine and outcome (`HistoryEntry`, `pushHistory`, `describeHistoryEntry`), rendered as a status
icon plus a compact `1.2s · 50 rows · Kafka Direct` line. Every result field is optional, so an
entry written by an earlier version reads back and simply shows less. A cancelled run is *not*
recorded: stopping a query teaches nothing about it.

### E10 — a failed DDL preview lost its reason *(fixed, front and back)*

`QueryController.ddlPreview` returned `Map.of("error", e.getMessage())`. **`Map.of` rejects a null
value and `getMessage()` is null for a `NullPointerException`** — so the one failure mode where a
caller most needs a reason turned the handler's own error path into a 500, which the UI could only
report as a generic toast. It goes through `SqlErrorClassifier.explain()`, documented never to
return null or blank and already used for exactly this on the query paths;
`QueryControllerTest` pins all three cases including the message-less throwable. The
`@RequestParam` is named explicitly while we are there, so the handler resolves without relying on
`-parameters` being on the compiler command line.

On the front, the reason now renders in an `ErrorPanel` **inside the modal**, with a retry, rather
than in a toast that faded behind the dialog in three seconds carrying the only useful part — the
same fix the Metrics editor already received.

### E11 — a long or nested value was only reachable through a hover tooltip *(fixed)*

The grid renders one line per row, and once virtualised each cell is forced onto a single line: a
long value was truncated with a `title` for its only recourse, and a nested JSON document stayed a
run of text. Clicking a cell now opens a **row detail panel** beside the grid — every column of the
selected row, structured values indented, and copy per value. `detailValue` also indents JSON that
arrived **as a string**, which is what the direct engine commonly returns for a sub-document.

Clicking a cell used to copy it, blind; copy moves into the panel, where you can see what you are
copying. The panel closes on `Escape`, steps between rows with its own controls, and closes itself
when a sort reorders the grid — the index it holds would otherwise point at a different row.

### E12 — the Window Assistant took 288 px permanently *(fixed)*

For a tool used occasionally, with no way to fold it. It folds to a rail that reopens it, and the
state travels in `kse:query-layout` with the rest of the layout. Open by default: that is how it has
always been, and folding should be a decision rather than a surprise on upgrade. This was listed as
deliberately left open in the first pass — the layout contract it needed now exists.

### E13 — the workbench layout reset on every visit *(fixed)*

Pages are unmounted on navigation, so an editor pane widened to write a long query came back at 55 %
after a trip to the Dashboard, and the sidebar back to 288 px. Both are persisted under
`kse:query-layout`, clamped on read so an out-of-range stored value cannot leave a pane unusable.

---

## 3. Optimisation

### O1 — every keystroke serialised every tab to `localStorage` *(fixed)*

The persist effect ran on `[tabs, activeTabId]`, i.e. on every character typed: a `JSON.stringify`
of all tabs plus a synchronous `localStorage` write on the main thread. Debounced to 400 ms; nothing
about what is kept changes.

### O2 — every keystroke re-rendered the whole results grid *(fixed)*

The grid was inline in the page, so a `setTabs` rebuilt up to 200 rows of cells — re-serialising
each value — although nothing about the result had moved. It is now a `React.memo`-ed component at
module scope, and the props that would have defeated the memo (`useVirtualRows`' fresh object, the
`slice` of visible rows) are memoised on their primitives.

### O3 — sorting went through the Unicode collator, per comparison *(fixed)*

`String(a[col] ?? '').localeCompare(…, { numeric: true })` allocated two strings and called the
collator on **every** comparison — of the order of 60 000 calls for 5 000 rows. It was also
subtly wrong: `numeric: true` compares digit runs inside text, not numbers, so `1e3` sorted after
`2`. `sortRows` extracts one key per row, compares numbers as numbers (including numeric strings,
which is how engines return 64-bit integers), is stable, and sends absent values **last in both
directions** — sorting to find the largest value and reading a column of blanks answers nothing.

### O4 — every run paid for two full planner passes *(fixed)*

`POST /api/query/validate` runs `tableEnv.explainSql` under the Flink runtime's read lock, and then
`run-sync` does its own planning. The pre-flight is worth keeping — it rejects a syntax error before
a single Kafka consumer opens — but not worth repaying for an unchanged statement. Re-running the
same SQL after changing the row limit or the offset, the most common gesture on this screen, now
skips it.

---

## 4. UI quality / accessibility

Everything below was unreachable without a mouse. All fixed.

- **Tabs** were `<div onClick>`: no tab stop, no state announced. They are a real `role="tablist"`
  with roving `tabIndex`, arrow navigation, `F2` to rename, and the editor pane wired as their
  `tabpanel`.
- **Sidebar tables and topics** were `<div onClick>` — the entire schema browser was keyboard-dead.
  They are buttons, with `aria-expanded` on the ones that expand and labels that name the target.
- **Result column headers** were clickable `<th>` with no `aria-sort`: the sort was neither
  reachable nor announced. Header buttons plus `aria-sort` on the cell.
- **Both resize handles** were mouse-only (`mousedown`/`mousemove`), so neither worked on a tablet
  at all. They are `role="separator"` with pointer events, `touch-action: none`, arrow-key
  adjustment and `Home` to reset — the same treatment Stream Flow's and Lineage's already had.
- **The DDL modal** had no `Escape` and did not restore focus to what opened it. Both added; the
  rest of the app (`ConfirmDialog`, the Metrics modal, the command palette) already closed on
  `Escape`.
- **Drag no longer selects text** across the panes it crosses (`user-select` suspended for the
  gesture).
- **Result column headers are no longer CSS-uppercased.** A column name is *data*, not a label: it
  comes from the engine, and `customerId` rendered as `CUSTOMERID` is no longer the identifier to
  retype into the next query — on an engine that distinguishes case, it is a false lead. The wide
  letter-spacing went with it, since it only existed to make capitals legible, and the header takes
  the monospace of the values it sits above.

---

## 5. Running several statements — second pass

`Run all` (E2e) made a tab of several statements *runnable*. This pass is about what running them
actually reported, and what could still go wrong while they ran.

### M1 — a batch threw away every result but the last *(fixed)*

`runAllStatements` called `executeStatement` in a loop, and each call begins with `setResults(null)`.
Five statements therefore left **one** grid: no rows, no duration, no engine and no proof that the
other four had ever run. On the screen whose whole job is to show what a query returned, four of
five answers were discarded between one render and the next.

The run now keeps a `StatementRun` per statement — kind, status, duration, row count, engine and the
result itself — and a strip above the grid lists them; selecting one brings its rows back, with the
row cap *it* ran under, so the "limit reached" badge does not judge an old result by how the
selector is set now. Retention is bounded (`forgetOldestResults`, 10 000 rows across the batch,
oldest released first, never the one on screen) and what is released **says so** rather than
rendering as an empty grid: "4 000 rows, no longer held" is an answer, "0 rows" is a false one.

### M2 — Flink Job mode ran one statement of several, silently *(fixed)*

Run has targeted the statement under the cursor since E2, in both modes. But the toolbar's
`Statement 2/3` counter and the `Run all` button were gated on `executionMode === 'SYNC_READ'`. A tab
holding three `INSERT INTO` — the shape that mode exists for — submitted **one** job, with nothing on
screen saying so and no way to submit the rest in one gesture. Silent partial execution is precisely
what that counter was added to prevent; the gate is gone.

### M3 — `⌘↵` during a batch started a competing query *(fixed)*

`executingRef` is the synchronous guard against two runs at once, and it falls back to `false`
between two statements of a batch. A keystroke landing in that gap — the gap is not small: it holds
the state updates, the history write and possibly a schema refetch — started a query *concurrent*
with the loop, both writing `results`, `abortRef` and `runningQueryIdRef`. `batchRef` closes it, and
covers `Run all` against itself for the same reason.

### M4 — Stop did not stop a batch *(fixed)*

`cancelRunningQuery` aborts the in-flight HTTP request. Pressed between two statements there is no
request to abort, so the loop simply carried on to the next one — and the Stop button was not even
rendered there, `executing` being false. Stop is now shown for the whole batch, raises a flag the
loop re-reads after each statement, and says what it did (`Stopping the batch after this statement`)
instead of `No query was running`.

### M5 — a cancelled batch was reported as a failure *(fixed)*

`executeStatement` returned a bare `true`/`false`, so a user-requested cancellation and an engine
error were the same value: pressing Stop raised a red `Stopped at statement 2 of 5`. It returns an
outcome now (`ok` / `failed` / `cancelled`), the toast distinguishes the two, and the statements the
batch never reached are marked `skipped` — not left looking like runs that returned nothing.

### M6 — a selection spanning two statements was sent as one query *(fixed)*

Selecting two statements and pressing Run is an ordinary gesture in any SQL editor. The selected text
went to `/api/query/run-sync` verbatim, `;` included, where the planner rejected it. `planRun` splits
a selection exactly as it splits the document — and still sends a *fragment* verbatim, since
selecting a sub-expression to try it must keep working.

`planRun` is also what makes an empty run impossible: a tab holding only blanks or comments answers
`Nothing to run` before anything is sent, instead of asking the engine to say what we already knew.

### M7 — a multi-statement tab was permanently "stale" *(fixed)*

`isResultStale` compared the SQL that ran against the whole tab. On a tab with several statements
those are never equal, so the amber `Stale — rerun` chip was on from the first run and never went
off. A warning that is always on stops being a warning. It now consults the tab's statements: a
result is current while what ran is still, word for word, one of them — and editing *another*
statement does not stale the one on screen.

### M8 — the page kept its own copy of an API response type *(fixed)*

`QueryWorkbench.tsx` declared a local `FlinkJobSubmission` interface — eight fields, written by
hand at the point of call — for what `POST /api/query/jobs` returns. `api/types.ts` already carried
`FlinkJobSummary` under its `@java` marker, resolved against the Java record by
`docs/check-api-types.py`. The two agreed, and nothing required them to: this is exactly the drift
that killed the Compare page, and the reason that checker exists. The local copy is gone; the page
imports the checked type and keeps only an alias for the name the gesture goes by.

### M9 — the error position was frozen at run time, so editing moved it off target *(fixed)*

Engine positions are relative to the fragment that was sent, so an origin is needed to map them back
into the document. That origin was captured when the query ran and kept in state — which makes it
wrong the moment the text moves: adding a line above shifted "Jump to line" by one, and `updateSql`
reset it to `null` on every edit, which mapped a failed statement's position to the *top of the
document*, since `results.error` survives the edit. Reopening a batch entry after an edit had the
same problem.

`resolveOrigin` derives it from the current text instead: the literal text that ran, located in the
document (which also covers a selected fragment, not being a statement), then a whitespace-
insensitive match against the tab's statements, so a reformat keeps the position. When neither
finds it, the answer is `null` — **the position is removed rather than guessed**, since pointing at
a line would designate something other than what the engine is talking about; the raw message keeps
its own line and column. The stored origin on a batch entry went with it: derived state that was
also kept is state that can disagree with itself.

### M10 — the completion list was rebuilt on every keystroke *(fixed)*

Monaco calls the provider on **each character** (`quickSuggestions`), and it rebuilt everything each
time: an object per Flink table, a `toTableName()` and an interpolated `sortText` per Kafka topic, a
`Set` of registered tables, an `Object.entries` per loaded schema. On a three-hundred-topic cluster
that is several hundred objects and as many fabricated strings per keypress, for a list that is
identical until the catalogue or the scope changes. `buildCompletionEntries` is now a pure function
(tested), memoised on the **identities** of the catalogue, the loaded schemas and the resolved scope
— so between two keystrokes the check is three reference comparisons — and only the range, which
follows the word under the cursor, is attached per call.

### M11 — a closed tab was gone for good *(fixed)*

Listed under "constaté, non traité" in the first pass: closing asks for confirmation when there is
something to lose, but nothing could bring it back, and a tab's text exists nowhere else. The
blocker named there was where to *offer* the undo — the shared `Toast` carries a message and a
tone, no action, and extending it is a design-system change that should not be smuggled into one
page; `⌘⇧T` is not an answer either, since the browser keeps that shortcut for itself and a
shortcut nobody can discover helps nobody.

The tab bar is where one looks for a tab that has disappeared, so the affordance lives there: an
undo button appears beside `+` while something can be reopened, naming the tab in its tooltip and
its accessible label. The last five closed tabs are kept **in memory only** — a net for the gesture
just made, not a history — which is stated in the tooltip rather than left to be discovered.

---

## Constaté, non traité

- **`SqlQueryValidator` is not the whitelist.** `CLAUDE.md` describes it as "whitelist-based guard:
  only SELECT, EXPLAIN and CREATE TABLE are allowed". It is not: `validate()` checks cross-joins and
  system tables, rethrows a parse error, and **returns silently for anything else** (including
  `INSERT`, which is what lets Flink Job mode work at all). The whitelist is enforced in
  `FlinkSqlService.executeSql` (`"Only SELECT, EXPLAIN and CREATE TABLE statements are allowed."`).
  `CLAUDE.md` is corrected; the code is left alone, since the behaviour is right and only the
  description was wrong.
- **The page has no mobile story.** Fixed-width sidebar, split panes, Monaco: it targets a desktop,
  and the fix for E5 makes the toolbar usable on a narrow window without pretending otherwise.
  *Since resolved as a decision rather than as code:* `MOBILE-LAYOUT-SCOPE.md` measured the case,
  put the product question, and the answer is that **the application is not intended for phones**.
  The page therefore keeps no mobile story on purpose — it says so under `lg`
  (`components/query/NarrowWindowNotice.tsx`) and names the screens that do work at that width.
  What remains open there is W5, tap targets, which was never a mobile item.
- **`detectStatementType` duplicates the backend's own detection** to gate the execution modes, and
  now also to label the statements of a batch. The two agree today. Sharing one definition would
  mean an endpoint that classifies a statement, which is a round trip to answer a question the
  client can answer instantly; it moved into `queryWorkbench.ts` instead, where it is tested.
- **Two identical statements in one tab are indistinguishable to `resolveOrigin`.** It returns the
  first, so reopening the second one's error after an edit can map its position onto the first.
  Distinguishing them means tracking each statement's identity across edits — a document model the
  page does not have — for a case where both statements are the same text anyway.

---

# SQL Editor — audit, second pass (2026-09)

Same scope as the first pass — `pages/QueryWorkbench.tsx`, its pure modules, `components/query/`,
and the endpoints behind them (`QueryController`, `SqlQueryValidator`, `FlinkSqlService.executeSql`)
— re-read a year of changes later, for bugs and for what the editor costs the engine.

Six items, all fixed here. Two of them are the first pass's own corrections re-opened by work that
landed after it, which is the pattern worth naming: the pre-flight `POST /api/query/validate` was
added *above* a guard that assumed nothing awaited before it, and the accessibility pass converted
two of the sidebar's three lists. Each fix carries a test that was verified to fail against the
revision it describes.

One item is recorded and not fixed, at the end.

---

## S1 — two queries could run at once again, because the pre-flight is an `await` *(fixed)*

R2 of the first pass introduced `executingRef`, a synchronous mirror of `executing`, because ⌘↵
calls the run path directly and a React state is not yet updated when two keystrokes land in one
tick. What re-opened it is that the flag was raised **after** the pre-flight:

```ts
if (validatedSqlRef.current !== sqlToRun) { await axios.post('/api/query/validate', …); }
…
executingRef.current = true;
setExecuting(true);
```

That `await` is not a tick. `POST /api/query/validate` runs `tableEnv.explainSql` — a complete Flink
planner pass, taken under the runtime's read lock, so it also queues behind whatever else holds the
runtime. For the whole of it the screen was in its resting state: the Run button enabled and reading
“Run query”, no spinner, no Stop, `executingRef` false. So the window the guard exists to close was
a round trip wide, and reachable with the mouse alone — two clicks, two queries, the second
overwriting `abortRef` and `runningQueryIdRef`, so Stop no longer cancelled the first and the first
query's `finally` flipped the screen to “Complete” with the other still in flight. Exactly R2, by a
different road.

The state is raised before the pre-flight now, and the `AbortController` is created before it and
its signal passed to that request too — so Stop pressed during the wait aborts the request that is
actually in flight, instead of finding an executing screen with nothing to stop. A cancel raised
there returns `cancelled` rather than falling into the `catch` that said *let execution handle it*,
which would have sent to the engine the query that had just been stopped.

## S2 — a syntax error caught before execution lost its position *(fixed)*

M9 replaced the frozen error origin with one derived from the current text: `resolveOrigin(sql,
ranSql)` locates, in the document as it is now, the SQL that ran, and `queryError` **removes the
position** when it cannot — pointing at a line would otherwise designate something other than what
the engine is talking about.

`refuse()` — the pre-flight and mode refusals — never set `ranSql`. Two consequences, and the
common one is the worse:

- On the first run of a tab `ranSql` is `null`, so `runOrigin` is `null`, so the position was
  dropped. On the one error that always carries a line and a column — the parser's — there was
  neither a Monaco marker nor a “Jump to line”.
- After any earlier run, `ranSql` still held the **previous** statement, so the refusal's position
  was mapped through that statement's origin: a marker in the right file, on the wrong statement.

`refuse` now records what it refused. `executionMs` goes with it, for the same reason: a refusal
that leaves the previous query's duration on screen reports an execution time for an execution that
did not happen.

## S3 — `POST /api/query/validate` examined a different statement than the one that would run *(fixed)*

Every other caller hands `SqlQueryValidator` the prepared statement — `FlinkSqlService.prepareSql`:
double-quoted identifiers normalised to backticks, comments stripped, edges trimmed. The controller
handed it the request body verbatim. The editor calls that endpoint before every Run, so the gap was
on the busiest path of the page, and it showed in three directions:

- **A double-quoted identifier was refused.** `SELECT * FROM "orders"` is the form
  `normalizeIdentifierQuotes` exists to accept; passed raw to `explainSql` it is a parse error,
  which `SqlErrorClassifier.isSyntaxError` correctly recognises and the endpoint returns as a
  rejection. The editor therefore refused to run a query the engine runs fine — and refused it as
  the user's typo.
- **A keyword in a comment was read as SQL.** `SqlStatements.outsideLiterals` neutralises string
  literals, which is the rule this repository applies everywhere, but not comments — stripping those
  is the caller's job. So `-- surtout pas de CROSS JOIN ici` above a query tripped the cross-join
  guard, and the query was refused for a line that is not SQL.
- **And a statement whose first line is a comment was validated by nothing at all.** The
  classification is a `startsWith` on the body; a leading comment fails it, so `validate` returned
  early — a pre-flight that accepts in silence, on the shape the DDL preview and every commented
  query have.

The preparation moved **into `validate`** rather than into the controller. It is idempotent, so the
callers that prepared already are unchanged, and a caller can no longer forget: one definition, not
a convention. `SqlQueryValidatorTest` is the first test this class has had.

## S4 — the engine badge named an engine before one had answered *(fixed)*

`{(results?.engine || results || executing) && <Badge>{results?.engine ?? 'Kafka Direct'}</Badge>}`.
Which of the two engines answers is decided per query, inside `executeSql`, so before the result
there is no answer to give — and on an error result there is none either, `engine` being null on
those paths. The badge asserted “Kafka Direct” in both cases. That is the one indicator whose whole
job is to say which engine ran the query, and reading it wrong changes what the rows mean (no
multi-topic JOIN, a scan ceiling, a different notion of the read mode). It now says `Engine…` while
a query is in flight and renders nothing when the result names none.

## S5 — the third list in the sidebar was still a `<div onClick>` *(fixed)*

The first pass's accessibility section converted the Flink tables and the Kafka topics — "the entire
schema browser was keyboard-dead" — and left the saved queries below them. Reopening a saved query
was still mouse-only: no tab stop, no role, no announced name. It is a `<button>` with an
`aria-label` naming the query, like its two neighbours.

## S6 — the editor made the engine parse the same statement three to six times *(fixed)*

`SqlAst.read` builds a fresh Calcite `SqlParser` and reads the whole statement. One editor SELECT
goes through it repeatedly, on the identical string: the cross-join guard, `extractSourceTables`
during auto-registration, `extractPrimaryTable` (up to three separate conditions on the SELECT
path), `MetricService.isSingleTableRead` when a read mode is named, and finally the direct reader.
The pre-flight adds its own. Nothing shared the result, because nothing owned it.

It is a pure function of the text — the parser config is a constant — and `Read` is immutable, every
list built through `List.copyOf`, so an analysis can be handed out twice. `SqlAst.read` memoises on
the statement, bounded at 128 entries and 4 000 characters and cleared wholesale at the ceiling: it
is not a business cache, only a way of not redoing the same work inside one request, so losing it
costs one parse. The metric refresh loop benefits too, coming back every thirty seconds with SQL
that has not changed.

## S7 — the schema browser was re-rendered on every keystroke *(fixed)*

The same defect `ResultsGrid` was memoised for, in the larger of the two lists. Typing calls
`updateSql`, which calls `setTabs`, which re-renders the page; `SchemaBrowser` was neither memoised
nor given stable props, and it renders **every** Flink table and **every** Kafka topic —
`ScrollList` bounds the height, it does not virtualise. On a three-hundred-topic cluster that is a
few thousand React elements reconciled per character typed, for a subtree that cannot have changed.

`React.memo` alone would have done nothing: half the handlers read the active tab, which is what
changes at every character, so `useCallback` cannot stabilise them either. `useStableCallback`
(`src/useStableCallback.ts`, the `useEvent` pattern — the current function in a ref rewritten in
`useInsertionEffect`, a fixed wrapper handed out) gives the page stable handlers with no dependency
list to keep and no stale closure to capture.

---

## Constaté, non traité

- **Each Run still costs two full Flink planner passes.** `POST /api/query/validate` runs
  `explainSql`, and `executeSql` then calls the same validator again on the same statement. The
  stated reason for the pre-flight — rejecting a typo "before the query opens the least Kafka
  consumer" — is already met by the second call: `executeSql` validates *before* auto-registration
  and before any broker access, and returns the same parser message, with its position, which
  `describeQueryError` unwraps from its `SQL Validation Error:` prefix and classifies identically.
  So the round trip buys no earlier rejection; what it costs is a second pass under the runtime's
  read lock, on the single embedded runtime that also serves the metric refresh loop — and it is
  what opened S1. Removing it means deleting the endpoint's only caller, and therefore deciding the
  fate of `POST /api/query/validate`, `SqlValidationResponse` and their checked API type; that is a
  deletion to argue on its own rather than a line to flip inside an audit, and S1 has removed the
  harm in the meantime. The measurement that would settle it is the wall time of the two passes on
  the demo cluster, which nothing takes today.
- **`stripSqlNoise` (`pages/sqlScope.ts`) strips comments before literals**, so a `--` inside a
  string value truncates the rest of the line before the FROM/JOIN scan runs. The backend has the
  single-pass answer for exactly this (`SqlStatements.outsideLiterals`) and the frontend has no
  equivalent. What it costs is bounded to the autocompletion scope and the window assistant's table
  guess — never to what is sent to the engine, `splitStatements` and `planRun` doing their own
  proper scan — so it is recorded rather than fixed here.
- **A selected statement is sent with its trailing `;`.** `planRun` returns the selection verbatim
  when it splits to one statement, rather than the statement `splitStatements` found inside it.
  Flink accepts the semicolon (`FlinkSqlServiceInsertVariantsTest` pins that), so nothing fails; the
  only visible effect is that `isResultStale` compares a string that no statement of the tab equals,
  which on a multi-statement tab marks the result stale one edit early.
- **“Stale — rerun” runs the statement under the cursor**, not the one that produced the rows on
  screen. They are the same in the ordinary case — the result goes stale because that statement was
  edited, and the cursor is in it — and telling them apart means tracking a statement's identity
  across edits, which is the document model `resolveOrigin` was already left without in the first
  pass.
