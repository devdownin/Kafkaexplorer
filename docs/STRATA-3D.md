# Kafka Strata 3D — spatial observability

Kafka Strata 3D is an optional, read-only Three.js view at **/strata** (Analyze → Kafka Strata 3D).
It provides a time-oriented view of topics and deliberate connections, complementary to
[Stream Flow](../src/main/webapp/src/pages/StreamFlow.tsx), [Lineage](../src/main/webapp/src/pages/Lineage.tsx)
and Topic Explorer.

## Visual grammar

| Element | Meaning | Evidence |
|---|---|---|
| Column | Kafka topic | `GET /api/dashboard` |
| Twelve rings, bottom to top | Consecutive 2.5-minute offsets-produced buckets in the last 30 minutes | `GET /api/dashboard/activity` |
| Ring radius (logarithmic) | Relative **offset** production, not current record count | Offset differences by timestamp |
| Horizontal translucent plane | Currently selected time bucket, **same bucket** on every topic | Activity response shared time window |
| Amber highlighted ring | **Candidate** deviation from previous four complete buckets | Client-side, conservative heuristic |
| Dashed line | Flink INSERT dependency **declared** by Lineage | `GET /api/lineage` |
| Teal arc | Chronological matches for a searched Kafka key, **not proven causal transfer** | `POST /api/stream-flow/stream` |
| Coloured satellites | Lag per partition of the **selected consumer group** | `GET /api/topic/{name}/consumers` |
| Neutral rings, unknown readings | Missing/incomplete data, **not zero traffic** | Availability and coverage metadata |

Flink edges are only drawn when explicit source-topic table → read edge → INSERT
job → write edge → sink table → target-topic relationships exist. Arbitrary
topic proximity and matching names do not create a link. Parser warnings are
shown. No link is not evidence of absent activity.

## Using the view

1. Open **Analyze → Kafka Strata 3D**. Use the search box to limit to at most 48
   visible topics. Camera movement uses OrbitControls: drag to orbit, wheel to
   zoom, and pointer dragging to pan; keyboard-only selection is available in
   the topic list below the canvas.
2. Use **Temporal lens** to select an interval. The plane and highlighted ring
   correspond to the same server-provided interval across all topics.
   **Most recent** returns to the latest bucket. A new activity snapshot is
   retrieved every 30 seconds while the tab is visible.
3. Select a topic to show retained-offset estimates, time-bucket production
   and consumer groups. Choose a group to inspect per-partition lag. No commit
   is shown as unknown (not as lag 0); negative lag is preserved.
4. Enter a Kafka record key in **Trace a message key** and select **Trace in 3D**.
   The trace is **explicitly initiated**, scans at most 100 recent messages per
   visible topic within the previous 30 minutes, and is cancellable. Intermediate
   evidence may be shown before completion. Use **Full Stream Flow** for
   expanded search scope, path, header matching and continuation of partial
   scans. The 3D arcs connect chronological matches; they are **not** proof of
   causality. A record is identified by topic, partition, offset and timestamp.
5. Inspect **Unusual activity in this time slice**: the selected bucket is only
   flagged if complete and deviates strongly from a measured four-bucket local
   baseline. These are **visual leads**, not health incidents or automatic
   alerts. Comparisons use **offset production**, not Kafka record payloads.

## Behaviour under constraints

- Cluster metadata refreshes every 60 seconds and Lineage every 120 seconds.
  Polls stop when the tab is hidden and abort on unmount; overlapping requests
  are avoided. The Three.js context and OrbitControls are preserved when metric
  values update. Changing the set of visible topics deliberately refits the camera.
- Activity retrieval is limited by server budgets as well as the 48-topic view
  limit; partial/missing readings are not interpreted as zeros.
- Consumer group inspection occurs only for the selected topic, refreshed
  every 30 seconds; up to 64 partition satellites are shown in 3D, while the
  inspector displays up to 100 partitions with exact lag numbers.
- The temporal baseline detector requires complete partition and retention
  coverage; only four preceding complete buckets form a baseline.
- A tracing request always requires a user action. Cancelling aborts the
  request and prevents further scanning where supported by the server.
- With WebGL unavailable, the topic list remains accessible. Users who prefer
  reduced motion may leave animation disabled.

## Data boundaries

Kafka does not expose proof that any two topic messages were directly transferred
between topics without trace evidence or an operational lineage source.
The visualization intentionally distinguishes declared Flink paths, observed key
matches, statistical activity and unknown states. It does not create a Kafka
consumer for visual particle animation. Forecast overlays are not part of this
initial P3: they require explicit mapping between forecast metrics, topic names
and sample intervals.

## Validation

Frontend: `cd src/main/webapp && npm ci && npm run build && npm run lint && npm test`.

The focused `strataTime.test.ts` and `strataLineage.test.ts` tests verify
bucket coverage, conservative anomaly detection and declared-only topic paths.
Runtime checks should additionally verify orbit, selection, light/dark modes,
WebGL fallback, trace cancel, and missing/partial Kafka metadata.
