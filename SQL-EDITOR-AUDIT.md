# SQL Editor — audit (2026-08)

Full review of the SQL editor: `pages/QueryWorkbench.tsx` and the pure modules it drives
(`queryError.ts`, `sqlScope.ts`, `windowSql.ts`, `resultExport.ts`), plus the endpoints behind it
(`QueryController`, `SqlQueryValidator`, `FlinkSqlService.executeSync`). Four axes were asked for —
reliability, ergonomics, optimisation, UI quality — and they are the four sections below.

Everything listed as **fixed** is fixed on this branch. What was found and deliberately left is in
*Constaté, non traité* at the end, with the reason. The pure logic extracted along the way lives in
`pages/queryWorkbench.ts` and is covered by `pages/queryWorkbench.test.ts` (41 cases).

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

### R8 — the history dropdown never closed on its own *(fixed)*

No outside-click handler, no `Escape`. It stayed open over the results panel until the button was
clicked again. Both are wired, plus `aria-expanded` / `role="menu"`.

---

## 2. Ergonomics

### E1 — four different controls silently destroyed the tab you were writing in *(fixed)*

`updateSql()` replaces the **whole active tab**. It was called by the sidebar's "SELECT from this
table" button, by every Kafka topic in the sidebar, by every history entry, and by the DDL preview's
"Insert in editor". Clicking a topic to see its shape erased the query in progress, with no
confirmation and no undo. This is precisely the defect that was fixed on the Window Assistant
("*Elle écrasait auparavant tout l'onglet*") and left everywhere else.

One rule now, in `openSql`: an empty tab is filled, a tab with content is left alone and the SQL
opens in a new tab, which is what loading a *saved* query already did. The Window Assistant keeps
its own behaviour (insert at the cursor) because it produces a fragment, not a statement.

### E2 — the Format button never formatted anything *(fixed)*

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

### E3 — reading `Rows` and `Offset` was impossible on a narrow window *(fixed)*

`Offset` was hidden below `md`, `Rows` below `lg` — and both kept applying. On a laptop with a
narrow window the query ran EARLIEST/50 with no way to see it, let alone change it. The toolbar's
left group scrolls horizontally instead of dropping controls: a setting nobody can see is the worst
of both worlds. Both also gained a tooltip explaining what they actually select.

### E4 — the results grid did not distinguish NULL from the empty string *(fixed)*

`String(row[col] ?? '')` rendered a SQL NULL and a zero-length string identically. On a result grid
that is the distinction that says whether a LEFT JOIN found its row. `cellText` returns the flag,
NULL renders as a dimmed italic `NULL`, and copying a NULL cell still copies the empty string —
the value, not its rendering.

### E5 — nothing said the displayed rows no longer answered the query on screen *(fixed)*

The grid kept the previous run's rows while the next query was being typed, silently. A `Stale —
rerun` control now appears when the editor's SQL has drifted from what produced the rows
(`isResultStale`, whitespace-insensitive so re-indenting does not invalidate a result). Stream Flow
marks its graph the same way, for the same reason.

### E6 — saving twice under one name made two indistinguishable entries *(fixed)*

Saving now offers to replace, and refuses to save an empty tab.

### E7 — the workbench layout reset on every visit *(fixed)*

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
- **The Window Assistant is a permanently mounted 288 px panel.** It costs that width to the editor
  at all times for a tool used occasionally, and there is no way to fold it. Folding it is a real
  improvement, but it changes the page's layout contract (the editor pane is currently sized against
  it), so it is left as a separate change rather than smuggled into an audit.
- **The page has no mobile story.** Fixed-width sidebar, split panes, Monaco: it targets a desktop,
  and the fix for E3 makes the toolbar usable on a narrow window without pretending otherwise.
- **`detectStatementType` duplicates the backend's own detection** to gate the execution modes. The
  two agree today. Sharing one definition would mean an endpoint that classifies a statement, which
  is a round trip to answer a question the client can answer instantly.
- **A closed tab is gone for good.** Closing now asks when there is something to lose, which is the
  cheap half of the fix; an undo stack is the other half and belongs with a wider "recently closed"
  notion.
