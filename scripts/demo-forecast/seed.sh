#!/bin/sh
# SPDX-License-Identifier: AGPL-3.0-or-later
# Copyright (C) 2026 Kafka Explorer Contributors
#
# Makes the forecast demo forecastable on the first visit instead of two hours later.
#
# A forecast needs 128 recorded points and its context is 512, and a metric is recorded only as
# fast as it is collected: left to itself, the demo's Metrics page shows curves for hours before
# the first forecast can exist. This creates the two metrics that live.sh feeds (orders not yet
# shipped, degrading towards its threshold; a request backlog on an hourly cycle), waits for the
# Explorer to record each one for real once, and writes the nine hours before that first real
# point — the same curves signals.awk gives live.sh, so the seeded past and the live future are
# one signal.
#
# What it writes is honest about itself. Every seeded observation is a clone of the real one the
# Explorer recorded (same series, unit, definition version and scope) with its own
# collectorRunId `demo-seed-<n>`, so a reader of the history can tell them apart; a series that
# already holds any is left alone, so restarting the stack does not seed twice; and nothing is
# seeded for a metric that has not been recorded for real. It exists for the demo and nowhere else.
#
# Then it starts the forecast of the unshipped-orders metric — the one with something to say — and
# leaves the backlog's for the operator to start with the Forecast button on its card, which is the
# feature the demo is for, with the history already there.
set -eu

EXPLORER="${EXPLORER_URL:-http://explorer:8080}"
HERE="$(cd "$(dirname "$0")" && pwd)"
SIGNALS="${SIGNALS:-$HERE/signals.awk}"
# Nine hours: three degradation cycles and nine backlog cycles, inside the 512-step context.
HISTORY_MINUTES="${DEMO_FORECAST_HISTORY_MINUTES:-540}"
WAIT_SECONDS="${DEMO_FORECAST_WAIT:-600}"

UNSHIPPED_ID=demo-live-unshipped
BACKLOG_ID=demo-live-backlog

psql_() { psql -v ON_ERROR_STOP=1 -qAtX "$@"; }
log() { printf 'demo-forecast: %s\n' "$*"; }

post_json() { # <url> <body>
  wget -q -O - --header 'Content-Type: application/json' --post-data "$2" "$1"
}

until wget -q -O - "$EXPLORER/actuator/health" 2>/dev/null | grep -q '"status" *: *"UP"'; do
  log "waiting for the Explorer at $EXPLORER"
  sleep 5
done
until psql_ -c 'SELECT 1' >/dev/null 2>&1; do
  log "waiting for PostgreSQL"
  sleep 3
done

# ── the two metrics ─────────────────────────────────────────────────────────
# An upsert by id: running this again changes nothing. Both are templates — a forecast is made
# only of a metric whose sources the Explorer can name, which raw SQL never is.
post_json "$EXPLORER/api/metrics" "$(cat <<'JSON'
{"id":"demo-live-unshipped","name":"gauge_unshipped_demo_live_orders_minus_shipped","type":"GAUGE",
 "description":"Orders taken and not yet shipped (demo.live.orders minus demo.live.shipped). Live demo traffic: the warehouse falls behind for three hours, then a bulk shipment drains it.",
 "warningThreshold":300,"criticalThreshold":400,
 "templateType":"TOPIC_COUNT_DELTA",
 "templateParams":{
  "leftSql":"SELECT COUNT(*) AS metric_value\nFROM demo_live_orders",
  "rightSql":"SELECT COUNT(*) AS metric_value\nFROM demo_live_shipped",
  "leftTopic":"demo.live.orders","rightTopic":"demo.live.shipped",
  "operation":"LEFT_MINUS_RIGHT","countBy":"OFFSETS"},
 "executionMode":"TEMPLATE_BOUNDED_SCAN","labelTopic":"demo.live.orders","labelFields":[]}
JSON
)" >/dev/null || { log "could not create the unshipped-orders metric (see: docker compose logs explorer)"; exit 1; }
post_json "$EXPLORER/api/metrics" "$(cat <<'JSON'
{"id":"demo-live-backlog","name":"gauge_backlog_demo_live_requests_minus_processed","type":"GAUGE",
 "description":"Requests waiting between two services (demo.live.requests minus demo.live.processed). Live demo traffic on an hourly cycle.",
 "warningThreshold":110,"criticalThreshold":135,
 "templateType":"TOPIC_COUNT_DELTA",
 "templateParams":{
  "leftSql":"SELECT COUNT(*) AS metric_value\nFROM demo_live_requests",
  "rightSql":"SELECT COUNT(*) AS metric_value\nFROM demo_live_processed",
  "leftTopic":"demo.live.requests","rightTopic":"demo.live.processed",
  "operation":"LEFT_MINUS_RIGHT","countBy":"OFFSETS"},
 "executionMode":"TEMPLATE_BOUNDED_SCAN","labelTopic":"demo.live.requests","labelFields":[]}
JSON
)" >/dev/null || { log "could not create the backlog metric (see: docker compose logs explorer)"; exit 1; }
log "metrics created"

# ── the history before the first real observation ───────────────────────────
real_first() { # <metric id> -> the first observation the Explorer recorded itself, in ms (0 if none)
  psql_ -c "SELECT COALESCE(MIN(observed_at), 0) FROM kex_metric_observation_v1
            WHERE payload::jsonb->>'metricId' = '$1' AND payload::jsonb->>'component' = 'value'
              AND payload::jsonb->>'collectorRunId' NOT LIKE 'demo-seed-%'" 2>/dev/null || echo 0
}
seeded() { # <metric id> -> how many observations this script has written for it
  psql_ -c "SELECT COUNT(*) FROM kex_metric_observation_v1
            WHERE payload::jsonb->>'metricId' = '$1' AND payload::jsonb->>'component' = 'value'
              AND payload::jsonb->>'collectorRunId' LIKE 'demo-seed-%'" 2>/dev/null || echo 0
}

backfill() { # <metric id> <awk expression of t, the epoch second>
  id=$1; expression=$2
  if [ "$(seeded "$id")" -gt 0 ]; then log "$id already has its demo history"; return 0; fi
  deadline=$(( $(date +%s) + WAIT_SECONDS ))
  first=$(real_first "$id")
  while [ "${first:-0}" -eq 0 ]; do
    [ "$(date +%s)" -lt "$deadline" ] || { log "$id was not recorded within ${WAIT_SECONDS}s; no history seeded for it"; return 1; }
    sleep 10
    first=$(real_first "$id")
  done
  {
    printf '%s\n' 'BEGIN;'
    printf '%s\n' 'CREATE TEMP TABLE seed (idx int, at bigint, v double precision) ON COMMIT DROP;'
    printf '%s\n' 'COPY seed FROM STDIN WITH (FORMAT csv);'
    awk -v first="$first" -v n="$HISTORY_MINUTES" "$(cat "$SIGNALS")
BEGIN { for (i = 1; i <= n; i++) { at = first - (n - i + 1) * 60000; t = at / 1000; printf \"%d,%.0f,%.3f\\n\", i, at, $expression } }"
    printf '%s\n' '\.'
    cat <<SQL
INSERT INTO kex_metric_observation_v1 (observation_id, series_id, observed_at, metric_value, payload)
SELECT o.oid, b.series_id, s.at, s.v,
       (b.p || jsonb_build_object('observationId', o.oid, 'collectorRunId', 'demo-seed-' || s.idx,
                                  'observedAt', s.at, 'value', s.v))::text
FROM seed s
CROSS JOIN (SELECT series_id, payload::jsonb AS p FROM kex_metric_observation_v1
            WHERE payload::jsonb->>'metricId' = '$id' AND payload::jsonb->>'component' = 'value'
              AND observed_at = $first LIMIT 1) b
CROSS JOIN LATERAL (SELECT encode(sha256(convert_to('["' || b.series_id || '","demo-seed-' || s.idx || '"]', 'UTF8')), 'hex') AS oid) o
ON CONFLICT (observation_id) DO NOTHING;
COMMIT;
SQL
  } | psql_ >/dev/null
  log "$id: $HISTORY_MINUTES minutes of demo history written before $(date -u -d "@$((first / 1000))" '+%H:%M:%S' 2>/dev/null || echo "$first")"
}

backfill "$UNSHIPPED_ID" "unshipped(t)"
backfill "$BACKLOG_ID" "backlog(t)"

# ── the forecast of the unshipped-orders metric ──────────────────────────────────────
# Built from the candidate catalogue, as the Forecast button on a card does it: the Explorer says
# which topics, which definition version and which interval; nothing here restates them.
candidate=""
deadline=$(( $(date +%s) + 120 ))
while :; do
  catalogue=$(wget -q -O - "$EXPLORER/api/forecasts/candidates" 2>/dev/null || true)
  candidate=$(printf '%s' "$catalogue" | sed -n 's/.*\({"metricId":"'"$UNSHIPPED_ID"'"[^}]*}\).*/\1/p')
  if printf '%s' "$candidate" | grep -q '"eligible":true' && printf '%s' "$catalogue" | grep -q '"applyUnavailable":null'; then break; fi
  [ "$(date +%s)" -lt "$deadline" ] || { log "the unshipped-orders metric is not a forecast candidate yet; press Forecast on its card"; exit 0; }
  sleep 5
done
field() { printf '%s' "$candidate" | sed -n "s/.*\"$1\":\"\([^\"]*\)\".*/\1/p"; }
list() { printf '%s' "$candidate" | sed -n "s/.*\"$1\":\(\[[^]]*\]\).*/\1/p"; }
top() { printf '%s' "$catalogue" | sed -n "s/.*\"$1\":\"\([^\"]*\)\".*/\1/p"; }
step=$(printf '%s' "$candidate" | sed -n 's/.*"suggestedStepMillis":\([0-9]*\).*/\1/p')
body=$(printf '{"metricId":"%s","definitionVersion":"%s","environment":"local","unit":"%s","clusterId":"%s","collectorId":"%s","topics":%s,"groups":%s,"stepMillis":%s,"horizon":%s,"threshold":null,"direction":"ABOVE","confirmed":true,"seasonality":"NONE"}' \
  "$UNSHIPPED_ID" "$(field definitionVersion)" "$(field unit)" "$(top clusterId)" "$(top collectorId)" \
  "$(list topics)" "$(list groups)" "${step:-60000}" "$((1800000 / ${step:-60000}))")
if post_json "$EXPLORER/api/forecasts/series" "$body" >/dev/null 2>&1; then
  log "forecast of $UNSHIPPED_ID started; press Forecast on the backlog card to start the other"
else
  log "the forecast of $UNSHIPPED_ID was not started (already running, or runtime approval is off); press Forecast on its card"
fi
