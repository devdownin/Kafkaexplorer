# Regenerating the screenshots

`docs/img/*.png` is what the [Docker Hub overview page](../DOCKERHUB.md), the READMEs and the
GitHub Pages site show. They are produced here, not taken by hand, so that a UI change can be
reflected by re-running one command instead of someone remembering to re-photograph six
screens at the same window size.

```bash
cd src/main/webapp && npm ci
./node_modules/.bin/tsc && ./node_modules/.bin/vite build --outDir /tmp/kse-site --emptyOutDir

cd ../../../docs/screenshots
node server.mjs /tmp/kse-site 4173 &
node capture.mjs http://127.0.0.1:4173 ../img
```

Requires Node 22+ and Playwright with a Chromium build (`npm i -g playwright && playwright
install chromium`). The capture exits non-zero if any screen fails — five screenshots out of
six means the sixth silently disappears from the documentation, so a partial run is a failed
run.

## What is real and what is not

**The UI is real.** Every pixel is the compiled `src/main/webapp` — the same components,
tokens and layout the application ships. Nothing is mocked up, redrawn or retouched.

**The data is canned.** `fixtures.mjs` answers the REST calls, because standing up Kafka,
Flink and a seeded cluster to take a picture is neither reproducible nor something a
documentation build can do. The fixtures mirror what `setup-demo.sh` actually seeds — the
six-step order pipeline, header-only payment correlation, the duplicates and poison records
the audit is calibrated to find — so the screens show what someone gets after
`docker compose up -d`, not an invented cluster. Timestamps derive from one fixed instant, so
re-running produces the same image rather than a diff nobody can review.

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
