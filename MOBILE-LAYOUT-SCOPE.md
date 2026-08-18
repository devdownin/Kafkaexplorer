# Mobile layout — scope

`SQL-EDITOR-AUDIT.md` closes its *constaté, non traité* list with "**The page has no mobile
story.** Fixed-width sidebar, split panes, Monaco: it targets a desktop." That is a fair statement
and an unmeasured one, and a scoping decision taken on an unmeasured statement is a guess.

This document is the measurement, the product question it raises, and the work that follows from
each possible answer. **It implements nothing.** The one decision it needs is not a technical one,
and it is stated in [The product question](#the-product-question) — everything downstream of it
changes size depending on the answer.

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

| page | phone (390) | tablet (768) | desktop (1440) |
|---|---|---|---|
| dashboard | no overflow, 0 clipped | no overflow, 7 clipped | no overflow, 0 clipped |
| sql-editor | no overflow, 8 clipped | no overflow, 8 clipped | no overflow, 8 clipped |
| topic-explorer | no overflow, 8 clipped | no overflow, 8 clipped | no overflow, 8 clipped |
| stream-flow | no overflow, 8 clipped | no overflow, 8 clipped | no overflow, 8 clipped |
| audit | no overflow, 0 clipped | no overflow, 1 clipped | no overflow, 0 clipped |
| metrics | no overflow, 8 clipped | no overflow, 8 clipped | no overflow, 8 clipped |
| cluster | no overflow, 8 clipped | no overflow, 8 clipped | no overflow, 1 clipped |

The document never exceeds the window on any page at any of the three widths. The discipline
`CLAUDE.md` states for artifacts — wide content scrolls inside its own `overflow-x: auto`
container, the body never — holds in the application too. **So this is not the usual reflow bug
hunt**, and the work below is not "make it responsive" in the sense of fixing spills.

The clipped counts are near-identical at 390 and at 1440, so they are not a narrow-width
regression; they are consistent with truncation by design (`text-overflow`, a `title` carrying the
full value). Confirming that none of the eight hides something unreachable is a half-day of
looking, listed as W7 and deliberately not folded into "mobile".

### The SQL editor is 5 CSS pixels wide on a phone — and on a tablet

Monaco's rendered width against the width it is given, as the page opens (`default`) and tuned as
far as a user can tune it today — window assistant folded, schema browser dragged to its 200 px
minimum, both already possible and both persisted in `kse:query-layout`:

| viewport | 390 | 480 | 640 | **768** | 900 | 1024 | 1180 | 1280 | 1440 |
|---|---|---|---|---|---|---|---|---|---|
| editor width, default | **5** | **5** | 64 | **5** | 68 | 192 | 348 | 448 | 608 |
| editor width, tuned | 150 | 240 | 400 | 272 | 404 | 528 | 684 | 784 | 944 |

Three readings, in the order that matters:

1. **Below 1024 the editor does not exist.** The schema browser holds a fixed 288 px at every one
   of those widths — it never yields — and the window assistant opens by default beside it. On a
   390 px phone that is 288 px of chrome plus an assistant, leaving five pixels for the thing the
   page is named after.
2. **Crossing the `md` breakpoint makes it worse, not better.** 640 → 64 px, then 768 → 5 px. At
   exactly 768 the shell's navigation stops being an off-canvas drawer and becomes an in-flow
   256 px rail (`DESKTOP_QUERY = '(min-width: 768px)'` in `components/Layout.tsx`, `md:ml-64` on
   the content, `md:w-64` on `components/Sidebar.tsx`), while the editor's own 288 px browser is
   unchanged. A tablet in landscape is worse off than a phone. Nothing chose this; it is two
   independent width decisions meeting.
3. **Roughly half the loss is default state, not structure.** The tuned column is what today's
   preferences already recover: +145 px at 390, +267 px at 768. That is reachable by changing
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

| id | item | size | depends on |
|---|---|---|---|
| W0 | "Needs a wider window" notice on `/query` under `lg`, naming the screens that do work | 0.5 | — |
| W1 | Chrome that yields: schema browser as an overlay drawer under `lg`; window assistant folded by default under `lg` | 1 | — |
| W2 | The `md` regression: keep the nav a drawer until `lg`, or let `/query` opt out of the in-flow rail | 0.5 | shell change — check the other six pages |
| W3 | Stack instead of split under `lg`: Editor / Results as two panes you switch between | 2 | W1 |
| W4 | Monaco under a real thumb: measure on a device, write down the answer | 1 | — |
| W5 | Tap targets to 24 × 24, app-wide, with triage | 2 | — |
| W6 | Thresholds in `layout-probe.mjs` + a CI job, so this cannot silently regress | 0.5 | W1–W3 |
| W7 | Identify the eight clipped containers; confirm none hides unreachable content | 0.5 | — |

Notes that will cost time if discovered late:

- **W1 has a pattern to copy.** The schema browser is already a resizable `<aside>` with a
  persisted width, and `Layout` / `Sidebar` already implement the overlay-drawer pattern with a
  backdrop and a `matchMedia` listener. This is re-use, not invention.
- **W3 has to place two things that have no obvious home when stacked**: the batch strip added in
  #208 (one chip per statement, above the grid) and the `RowDetail` panel, which is deliberately
  beside the grid rather than over it so a row keeps its context. Stacked, "beside" is gone.
- **W2 is a shell change**, so it is the one item that can regress pages this document otherwise
  leaves alone. The probe covers all seven, which is what makes that checkable.

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
