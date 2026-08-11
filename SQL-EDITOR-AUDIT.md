# SQL Editor — audit (2026-08)

Full review of the SQL editor: `pages/QueryWorkbench.tsx` and the pure modules it drives
(`queryError.ts`, `sqlScope.ts`, `windowSql.ts`, `resultExport.ts`), plus the endpoints behind it
(`QueryController`, `SqlQueryValidator`, `FlinkSqlService.executeSync`). Four axes were asked for —
reliability, ergonomics, optimisation, UI quality — and they are the four sections below.

Everything listed as **fixed** is fixed on this branch. What was found and deliberately left is in
*Constaté, non traité* at the end, with the reason. The pure logic extracted along the way lives in
`pages/queryWorkbench.ts` and is covered by `pages/queryWorkbench.test.ts` (77 cases); the two
backend fixes are pinned by `QueryControllerTest`.

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

### R9 — the history dropdown never closed on its own *(fixed)*

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

---

## Constaté, non traité

- **`SqlQueryValidator` is not the whitelist.** `CLAUDE.md` describes it as "whitelist-based guard:
  only SELECT, EXPLAIN and CREATE TABLE are allowed". It is not: `validate()` checks cross-joins and
  system tables, rethrows a parse error, and **returns silently for anything else** (including
  `INSERT`, which is what lets Flink Job mode work at all). The whitelist is enforced in
  `FlinkSqlService.executeSql` (`"Only SELECT, EXPLAIN and CREATE TABLE statements are allowed."`).
  `CLAUDE.md` is corrected; the code is left alone, since the behaviour is right and only the
  description was wrong.
- **The autocomplete provider rebuilds its whole suggestion list on every keystroke**, including a
  `resolveScope` pass with two regexes over the full document and one `push` per column of every
  loaded table. It is bounded by the size of the catalogue actually loaded, so it has not been a
  problem in practice; memoising it on the model's version id is the fix if it becomes one.
- **The page has no mobile story.** Fixed-width sidebar, split panes, Monaco: it targets a desktop,
  and the fix for E5 makes the toolbar usable on a narrow window without pretending otherwise.
- **`detectStatementType` duplicates the backend's own detection** to gate the execution modes. The
  two agree today. Sharing one definition would mean an endpoint that classifies a statement, which
  is a round trip to answer a question the client can answer instantly.
- **A closed tab is gone for good.** Closing now asks when there is something to lose, which is the
  cheap half of the fix; an undo stack is the other half and belongs with a wider "recently closed"
  notion.
