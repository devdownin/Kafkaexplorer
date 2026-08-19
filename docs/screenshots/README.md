# Regenerating the screenshots

`docs/img/*.png` is what the [Docker Hub overview page](../DOCKERHUB.md), the READMEs and the
GitHub Pages site show. They are produced here, not taken by hand, so that a UI change can be
reflected by re-running one command instead of someone remembering to re-photograph eight
screens at the same window size.

```bash
cd src/main/webapp && npm ci
./node_modules/.bin/tsc && ./node_modules/.bin/vite build --outDir /tmp/kse-site --emptyOutDir

cd ../../../docs/screenshots
node server.mjs /tmp/kse-site 4173 &
node capture.mjs http://127.0.0.1:4173 ../img
```

Requires Node 22+ and Playwright with a Chromium build (`npm i -g playwright && playwright
install chromium`). The capture exits non-zero if any screen fails — seven screenshots out of
eight means the eighth silently disappears from the documentation, so a partial run is a
failed run.

On an image that ships its own Chromium (`PLAYWRIGHT_BROWSERS_PATH`) while Playwright was
installed separately, the two version numbers diverge and the launch fails asking for a
download the image forbids. Point **`CHROMIUM_PATH`** at the binary that is already there
(`CHROMIUM_PATH=/opt/pw-browsers/chromium-*/chrome-linux/chrome`); unset, Playwright's own
lookup is unchanged, so CI needs nothing. Both scripts honour it — `layout-probe.mjs` launched
bare until the Data Model page was added to it, so on such an image the one script whose whole
point is that its numbers get re-measured was the one that could not run.

**Install `pngquant` too** (`apt install pngquant`, `brew install pngquant`, or point
`PNGQUANT` at a binary). Each shot is quantised to an 8-bit palette, which on flat UI colour
and text is visually indistinguishable from the original and takes the eight from ~2.9 MB to
~970 kB. That is not repository housekeeping: the Docker Hub overview loads all eight of them from
GitHub Pages on every view. Without the binary the run still succeeds and says so at the end
— compression is not what this script is for, but nobody should discover the omission from a
commit diff either.

## Measuring the layout, not photographing it

`layout-probe.mjs` runs against the same server and reports numbers instead of images: whether a
page scrolls sideways, which containers clip content with no way to reach the rest, how many
interactive targets fall below the 24 x 24 CSS px of WCAG 2.5.8, and — on the SQL editor, where
the question is sharpest — the width actually left to the Monaco editor.

```bash
node layout-probe.mjs http://127.0.0.1:4173            # eight pages x three viewports
node layout-probe.mjs http://127.0.0.1:4173 --sweep    # editor width against viewport width
node layout-probe.mjs http://127.0.0.1:4173 --check    # what CI gates on; non-zero on a regression
```

`--check` is W6: it asserts that no page scrolls sideways at any width, and that no page carries
more sub-24px targets than the budget recorded beside `TARGET_BUDGET`. A page added to `PAGES`
without a budget entry fails the check rather than being measured and never gated. Clipping is
**reported and not gated**, deliberately — it turns on text metrics, so the same string wraps
differently under another font stack and a ceiling set here would fail on a runner for a reason
unrelated to the change under test. `ci.yml` runs it in the screenshot job, which already has the
server, the SPA and Chromium.

It exists because "the page has no mobile story" was an assertion nobody had measured, and it is
where the tables in [`MOBILE-LAYOUT-SCOPE.md`](../../MOBILE-LAYOUT-SCOPE.md) come from. A
screenshot shows that something is wrong; a measurement says how wrong, and at which width it
stops being wrong. Re-run it before trusting that document — a single CSS change can move every
number in it.

## What is real and what is not

**The UI is real.** Every pixel is the compiled `src/main/webapp` — the same components,
tokens and layout the application ships. Nothing is mocked up, redrawn or retouched.

**The data is canned.** `fixtures.mjs` answers the REST calls, because standing up Kafka,
Flink and a seeded cluster to take a picture is neither reproducible nor something a
documentation build can do. The fixtures mirror what `setup-demo.sh` actually seeds — the
six-step order pipeline, header-only payment correlation, the duplicates and poison records
the audit is calibrated to find — so the screens show what someone gets after
`docker compose up -d`, not an invented cluster. Timestamps derive from one fixed instant **and
the browser's clock is pinned to that same instant** (`page.clock.setFixedTime`), so re-running
produces the same image rather than a diff nobody can review. The fixed instant alone was not
enough and this file used to claim it was: every relative reading — "3 min ago", the staleness
warning above the suggested KPIs — compared it to the real clock, so the screens aged a little
every day and the Metrics shot eventually grew an amber banner about a two-month-old audit that
says nothing about the product.

The fixtures are shaped against the frontend's own TypeScript contracts
(`TopicSearchResponse`, `SchemaInfo`, `AuditReport`, `ParsedFlow`…). When one of those
contracts changes, the screenshot is where it shows: a missing field renders as `NaN` or an
empty panel rather than failing loudly. If a capture looks wrong, compare the fixture with the
interface before assuming the UI broke.

## How the screens are driven

By URL, not by clicking. The pages round-trip their whole state through the query string — a
search, a trace and a SQL tab are all shareable links, and a shared link re-runs itself on
open — so `/topic/…?mode=CONTAINS&q=ORD-1042` *is* the search. The capture therefore exercises
the same code path a colleague's pasted link does, and it does not break the day a button
moves. Only two gestures are actually clicked: the schema browser's refresh (it has no
mount-time fetch) and `Run query`.

`storage` seeds persisted layout preferences before the app boots — Stream Flow's criteria
panel is collapsed there, because the graph fits its chain to the width left over and every
node is illegible with the panel open.

## Adding a screen

Append to `SCREENS` in `capture.mjs`: a `name` (the file name), a `url`, a `settle` that waits
for something only the finished state shows, and optionally `scroll` (pixels, via a wheel
event over the content area — `Layout` scrolls an inner container, so `window.scrollTo` moves
nothing) and `storage`.

Wait on a value from the fixture rather than on a timeout, and beware `getByText` with
`exact: true`: the small caps labels are uppercased by CSS, so the DOM says "Total Topics"
where the screen says "TOTAL TOPICS".

If the new screen calls an endpoint that has no stub, `server.mjs` answers 404 and logs the
path — deliberately, since an unstubbed call would otherwise render an empty state and the
screenshot would quietly lie about the product.
