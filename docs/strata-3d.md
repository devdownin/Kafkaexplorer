# Kafka Strata 3D — original spatial activity explorer (archived P1)

> **Current experience:** The spatial cockpit supersedes the initial one-hour
> prototype described below. See the [current Strata 3D operator guide](STRATA-3D.md)
> for the 30-minute synchronized timeline, topic navigator, consumer diagnostics,
> forecast layers and provenance rules. This older guide is retained for historical context.

Strata 3D is available from **Observe → Strata 3D** (`/strata`).

## What the scene means

Each column is **one Kafka topic**. From bottom to top, its 12 rings represent the
12 consecutive 5-minute buckets in the past hour. Ring radius uses a square-root
scale relative to the largest bucket on the **currently visible page**. The
same reference is used for all 24 topics in view; radius is **not** message count.

The data comes from `GET /api/dashboard/activity` and uses offset boundaries,
not message samples. An interval counts changes in offsets, including offsets
later removed by retention or compaction (and certain control records). The
dashboard's topic-size estimate is a **different** measure.

- **Cyan:** all partitions measured and the full interval is covered.
- **Amber:** partial coverage. The displayed counts are lower bounds.
- **Grey:** no valid measurement was returned. Grey is **not** zero.
- **Thin, faint rings:** measured intervals with zero offset activity.
- **Moving highlight:** an abstract indicator of aggregate production activity,
  not an individual record or a physically tracked message.

The scope is bounded to 24 topics per page, with a name filter and pagination.
All requests use the configured activity lookup budgets and report coverage
limitations from the API instead of making up missing values. Activity refreshes
every 45 seconds; a scene frame is not a claim of broker-level live telemetry.

## Controls

- Left drag: orbit; right drag: pan; scroll or pinch: zoom.
- Click a 3D column or its name in the accessible list: select a topic.
- **Top view:** inspect spatial placement; **Reset view:** restore isometric camera.
- **Pause motion:** freeze representative highlight movement.
- **Open Topic Explorer:** inspect the real records, partitions and schema.

The WebGL scene is optional. If the GPU/browser cannot initialize WebGL, the
accessible topic list and measurement panel still work. A system reduced-motion
preference disables representative animation.

The light and dark scene follow the application's theme. The 3D engine and
OrbitControls load only when the Strata route is opened.

## Data boundaries and future scope

P1 deliberately does **not** connect topics with invented arrows or simulate
Kafka messages. Real lineage edges, consumer groups, lag and trace playback
belong to later phases (P2/P3) and must be grounded in measured or explicitly
inferred relationships. The columns are a spatial representation of temporal
activity, not a topology of broker networking or replica placement.

Implementation:
- `src/main/webapp/src/pages/Strata3D.tsx`: page, requests and accessible UI.
- The scene, OrbitControls and GPU cleanup now live in `StrataCanvas` inside `Strata3D.tsx`; the pure helpers are the `pages/strata*.ts` modules.
