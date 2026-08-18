# Mobile layout — scope

`SQL-EDITOR-AUDIT.md` closes its *constaté, non traité* list with "**The page has no mobile
story.** Fixed-width sidebar, split panes, Monaco: it targets a desktop." That is a fair statement
and an unmeasured one, and a scoping decision taken on an unmeasured statement is a guess.

This document is the measurement, the product question it raises, and the work that follows from
each possible answer. The one decision it needs is not a technical one, and it is stated in
[The product question](#the-product-question) — everything downstream of it changes size depending
on the answer.

> **Status.** It implemented nothing when it was written. **W0, W2 and W7 have since shipped**
> (Option C plus the two items that are right under every answer); the tables below have been
> re-measured against that state, and re-measuring is what turned up the correction in
> [What the first measurement got wrong](#what-the-first-measurement-got-wrong). W1, W3–W6 remain
> open, and the product question remains unanswered.

## How this was measured

`docs/screenshots/layout-probe.mjs`, over the same stub-backed server the documentation
screenshots use, so the UI is the compiled application and only the data is canned (see
[`docs/screenshots/README.md`](docs/screenshots/README.md)):

```bash
cd src/main/webapp && npm ci
./node_modules/.bin/tsc && ./node_modules/.bin/vite build --outDir /tmp/kse-site --emptyOutDir

cd ../../../docs/screenshots
node server.mjs /tmp/kse-site 4173 &
node layout-probe.mjs http://127.0.0.1:4173            # the table below
node layout-probe.mjs http://127.0.0.1:4173 --sweep    # the editor against viewport width
```

Every figure below comes from that script. What it establishes is layout: widths, overflow,
clipping, target sizes. What it does **not** establish is how any of this feels under a thumb —
it drives desktop Chromium with `hasTouch`, which is not a device. That gap is why W4 exists
rather than a recommendation about Monaco and touch.

## What is measured

### Nothing scrolls sideways, anywhere

Clipped containers, and of those the ones whose remaining content cannot be reached by any means —
no `title`, no scrollable ancestor, not `sr-only`:

| page | phone (390) | tablet (768) | desktop (1440) |
|---|---|---|---|
| dashboard | 0 clipped, 0 unreachable | 0 / 0 | 0 / 0 |
| sql-editor | 15 clipped, 4 unreachable | 8 / 1 | 9 / 3 |
| topic-explorer | 23 clipped, 0 unreachable | 21 / 19 | 18 / 18 |
| stream-flow | 15 clipped, 2 unreachable | 12 / 2 | 22 / 2 |
| audit | 0 clipped, 0 unreachable | 0 / 0 | 0 / 0 |
| metrics | 18 clipped, 13 unreachable | 13 / 8 | 11 / 6 |
| cluster | 12 clipped, 2 unreachable | 2 / 0 | 5 / 0 |

The document never exceeds the window on any page at any of the three widths — that part held and
still holds. The discipline `CLAUDE.md` states for artifacts — wide content scrolls inside its own
`overflow-x: auto` container, the body never — holds in the application too. **So this is not the
usual reflow bug hunt**, and the work below is not "make it responsive" in the sense of fixing
spills.

Two cautions about the numbers themselves. The counts move between runs on the pages that fetch
asynchronously (`topic-explorer` is the worst: 0 unreachable at 390 in the run above, 19 at 768),
because the probe measures at a fixed settle time and a card that has not arrived clips nothing.
Treat a *non-zero* unreachable count as a finding worth reading and a zero as unconfirmed rather
than proven — which is exactly what W6 has to fix before any of this can gate a build. And the
`unreachable` column is a heuristic: it credits a `title` on the element, on a descendant or on an
ancestor, and any ancestor that actually scrolls. It over-reports a flex row that overflows by ten
pixels with its buttons still visible, and it cannot see a value that is also printed somewhere
else on the page.

### What the first measurement got wrong

The table in the first version of this document read `8 clipped` for five of the seven pages at
every width, and concluded from that constancy: "not a narrow-width regression … consistent with
truncation by design". **Eight was the probe's own `.slice(0, 8)`.** The list was cut before it was
counted, so a ceiling was printed where a measurement was expected, and the reading drawn from it
was drawn from an artefact. The probe now counts the whole set and samples eight for the detail
view, which is what the corrected table above reports.

The conclusion that followed was wrong too, which is the part that mattered. W7 was scoped as
confirming that nothing hides unreachable content; it found the opposite. A metric card's name
(`gauge_volume_demo_orders_1_received`, 280 px of text in 147 px), its description and its SQL
line all carried `truncate` with **no `title` anywhere** — the codebase's own convention is that a
compacted value keeps its exact form in a `title`, and these three had simply not followed it. The
Cluster page's property names overflowed a grid cell with no ellipsis and no title at all. Those
are fixed; `layout-probe.mjs --detail` names what remains.

### The SQL editor is 5 CSS pixels wide on a phone — the tablet is fixed

Monaco's rendered width against the width it is given, as the page opens (`default`) and tuned as
far as a user can tune it today — window assistant folded, schema browser dragged to its 200 px
minimum, both already possible and both persisted in `kse:query-layout`:

| viewport | 390 | 480 | 640 | **768** | 900 | 1024 | 1180 | 1280 | 1440 |
|---|---|---|---|---|---|---|---|---|---|
| editor width, default, **before W2** | 5 | 5 | 64 | **5** | 68 | 192 | 348 | 448 | 608 |
| editor width, default, **after W2** | 5 | 5 | 64 | **192** | 324 | 192 | 348 | 448 | 608 |
| editor width, tuned, after W2 | 150 | 240 | 400 | 528 | 660 | 528 | 684 | 784 | 944 |

Three readings, in the order that matters:

1. **Below 640 the editor still does not exist.** The schema browser holds a fixed 288 px at those
   widths — it never yields — and the window assistant opens by default beside it. On a 390 px
   phone that is 288 px of chrome plus an assistant, leaving five pixels for the thing the page is
   named after. That is W1, and it is untouched.
2. **Crossing the `md` breakpoint used to make it worse, not better** — 640 → 64 px, then
   768 → 5 px — because at exactly 768 the shell's navigation stopped being an off-canvas drawer
   and became an in-flow 256 px rail, while the editor's own 288 px browser was unchanged. A tablet
   in landscape was worse off than a phone. Nothing had chosen that; it was two independent width
   decisions meeting. **W2 moved the shell's threshold to `lg`** (`DESKTOP_QUERY =
   '(min-width: 1024px)'`, with the matching `lg:` classes in `Layout`, `Sidebar` and `Header`), and
   the row above is the effect: 768 goes from 5 px to 192 px, 900 from 68 px to 324 px.
3. **The dip at 1024 is now the deliberate one.** 900 renders more editor than 1024 does, because
   the rail returns there and takes its 256 px. That is the cost of showing navigation, paid at the
   width where it is affordable — not the accident item 2 described. It is also the sharpest
   argument for W1: the 288 px browser is what makes that cost visible.
4. **Roughly half of what remains is default state, not structure.** The tuned row is what today's
   preferences already recover: +145 px at 390, +336 px at 768. That is reachable by changing
   defaults under a breakpoint, with no new layout — which is why W1 is a day and W3 is two.

For scale: the editor is configured at `fontSize: 14` with `fontFamily: 'JetBrains Mono'`.

### Small tap targets are an app-wide item that mobile merely exposes

Interactive elements below the 24 × 24 CSS px of WCAG 2.5.8 (Target Size, Minimum):

| page | ratio | page | ratio |
|---|---|---|---|
| sql-editor | 69 / 103 | audit | 30 / 54 |
| dashboard | 32 / 60 | stream-flow | 19 / 43 |
| topic-explorer | 14 / 64 | metrics | 8 / 53 |
| cluster | 1 / 20 | | |

**These counts are the same at 390 and at 1440** — the elements do not change size with the
viewport. So this is existing accessibility debt that a mouse forgives and a thumb does not. It is
listed separately (W5) on purpose: folded into "mobile", it would be deprioritised whenever mobile
is, and it is worth doing on a desktop-only product.

The count includes inline links and icon-only controls; some are legitimately small by the
standard's own exceptions (inline text links). Triage is part of W5, not a reason to discount the
number.

## The product question

**Nothing in this repository says who opens this application on a phone.** No issue, no README
line, no analytics. The measurements make that question decisive rather than academic, because
the honest reading of the table above is that no amount of layout work turns a 390 px viewport
into a SQL authoring surface — the fully tuned best case is 150 px of editor.

Meanwhile the screens an operator would plausibly want during an incident — Dashboard, Audit,
Topic Explorer, Cluster — already show no overflow and no clipping at phone width. Their only
measured problem is tap-target size, which is W5 and independent of everything here.

Three answers, with what each costs:

**Option A — Results-first editor (recommended).** Under `lg`, `/query` stops presenting itself as
an authoring surface: saved queries, history and results, with the editor reachable but not the
default pane. Authoring stays a wide-screen activity because it cannot be anything else.
*≈ 6 ideal days* (W0–W3, W6).

**Option B — Full authoring on mobile.** Swap Monaco for a plain textarea below a breakpoint and
rebuild what the editor provides. The features that would silently vanish are not decoration:
error markers with jump-to-line, scoped autocomplete, the formatter, cursor-statement targeting,
`⌘↵`. Each is either reimplemented against a textarea or lost on that breakpoint, and the two
surfaces then drift. *≈ 15+ ideal days, and a permanent second surface to maintain.* Not
recommended unless someone actually writes SQL on a phone.

**Option C — Say so and stop.** A notice under `lg`: this screen needs a wider window, here is
what does work on this one. *≈ half a day.*

**Recommendation: ship C first, then decide between A and B.** C is half a day and it converts a
silently broken screen into an honest one; A remains open afterwards, and B is only worth its
price against a user nobody has yet identified.

## Work items

Sized in ideal days, each independently shippable and useful on its own.

| id | item | size | depends on | state |
|---|---|---|---|---|
| W0 | "Needs a wider window" notice on `/query` under `lg`, naming the screens that do work | 0.5 | — | **done** |
| W1 | Chrome that yields: schema browser as an overlay drawer under `lg`; window assistant folded by default under `lg` | 1 | — | open |
| W2 | The `md` regression: keep the nav a drawer until `lg`, or let `/query` opt out of the in-flow rail | 0.5 | shell change — check the other six pages | **done** |
| W3 | Stack instead of split under `lg`: Editor / Results as two panes you switch between | 2 | W1 | open |
| W4 | Monaco under a real thumb: measure on a device, write down the answer | 1 | — | open |
| W5 | Tap targets to 24 × 24, app-wide, with triage | 2 | — | open |
| W6 | Thresholds in `layout-probe.mjs` + a CI job, so this cannot silently regress | 0.5 | W1–W3 | open |
| W7 | Identify the clipped containers; confirm none hides unreachable content | 0.5 | — | **done — and the answer was no** |
| W8 | The unreachable containers W7 left: the SQL editor's toolbar strip under `lg`, and whatever `--detail` still names once the pages are given time to settle | 1 | W1, W6 | open |

**What shipped, and what it cost.** W2 is four files of `md:` → `lg:` in the shell plus the
`matchMedia` query, and the sweep above is its whole justification. W0 is a dismissible notice on
`/query` under `lg` (`components/query/NarrowWindowNotice.tsx`) naming Dashboard, Audit and
Cluster — measured clean at 390 — with what each answers; deliberately not naming the Topic
Explorer, which is measured clean too but has no route without a topic name, so a link there would
land on the 404. W7 is the probe change (a true count, a reachability verdict, a `--detail` mode)
plus the four `title` attributes and one `flex-wrap` it found missing. Neither W0 nor W2 depends on
the product question, which is why they were the right first cut under any answer.

Notes that will cost time if discovered late:

- **W1 has a pattern to copy.** The schema browser is already a resizable `<aside>` with a
  persisted width, and `Layout` / `Sidebar` already implement the overlay-drawer pattern with a
  backdrop and a `matchMedia` listener. This is re-use, not invention.
- **W3 has to place two things that have no obvious home when stacked**: the batch strip added in
  #208 (one chip per statement, above the grid) and the `RowDetail` panel, which is deliberately
  beside the grid rather than over it so a row keeps its context. Stacked, "beside" is gone.
- **W2 was a shell change**, so it was the one item that could regress pages this document
  otherwise leaves alone. The probe covers all seven, which is what made that checkable — no page
  gained an overflow or an unreachable container from it, and the counts that moved on `cluster`
  and `metrics` moved because of the `title` fixes, not because of the breakpoint.

## Non-goals

- Touch-first Stream Flow and Lineage graphs beyond the pointer-event work already done (both
  already pan and zoom under a finger).
- A phone layout for the Process Mining four-step pipeline.
- Landscape-specific layouts.
- Anything for the Metrics charts: Recharts resizes, and nothing measured says otherwise.
- Native apps, PWA packaging, offline.

## What would change the recommendation

- **Someone does author SQL on a phone** → Option B, and W4 becomes the first item rather than the
  fourth.
- **Nobody opens this application on a phone at all** → W0 and W5 only; close the rest and record
  the decision here, since the next person to look at a 5 px editor will otherwise re-derive all
  of this.
- **The primary mobile screen turns out to be Audit or Topic Explorer** → the editor work drops
  below W5 in priority, and this document's centre of gravity is wrong.

## Keeping it honest

`layout-probe.mjs` is the check. Re-running it is how the tables above are kept true, and W6 is
what turns them into thresholds a build can fail on — the same reasoning as `check-links.py` and
`check-api-types.py`: a claim nothing verifies rots, and this document is a claim about numbers
that a single CSS change can move.
