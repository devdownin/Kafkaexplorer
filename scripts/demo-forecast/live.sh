#!/bin/sh
# SPDX-License-Identifier: AGPL-3.0-or-later
# Copyright (C) 2026 Kafka Explorer Contributors
#
# Live traffic for the forecast demo: the topic pairs behind its forecastable metrics, fed once the
# demo is up and for as long as it runs — the rest of the demo's topics are seeded once and then
# stand still, so a metric over them is one value for ever and has no history to forecast. See
# signals.awk for the curves and docs/metrics-demo-scenarios.md for the story.
#
#   demo.live.orders   -> demo.live.shipped     orders not yet shipped: a three-hour ramp, then drained.
#   demo.live.requests -> demo.live.processed   requests waiting: a strict hourly cycle.
#   demo.live.imports  -> demo.live.indexed     imports not yet indexed: a slow noisy climb, four hours.
#   demo.live.events   -> demo.live.archived    events not yet archived: noisy and stationary, no news.
#   demo.live.tickets  -> demo.live.resolved    tickets open: a level that shifts every ninety minutes.
#
# Each pair is held on its curve by reading the end offsets and producing the difference to
# whichever side is short. The metric over a pair is the difference of the two end offsets, so
# what is produced here is exactly what it reads: no timestamp to back-date, no window to blur it.
#
# **The two topics of a pair share their keys.** The k-th record of the second topic carries the key
# of the k-th record of the first (`orders-1234`), and its event_time is when it was *written*: a key
# traced in Stream Flow is found in both, and the time between them is how long that order waited —
# so the Stream Flow screen, and the metrics it creates from a hop, have something real to show here.
#
# Stateless by construction. The curves are functions of the wall clock and the gap is read from
# the log, so a restart — or a second copy — resumes where the curve is instead of replaying
# one: nothing here remembers anything.
set -eu

BOOTSTRAP="${1:-kafka:29092}"
# Overridable so the decisions below can be exercised against stub CLIs; the Kafka image puts them here.
KAFKA_BIN="${KAFKA_BIN:-/opt/kafka/bin}"
HERE="$(cd "$(dirname "$0")" && pwd)"
SIGNALS="${SIGNALS:-$HERE/signals.awk}"
TICK="${DEMO_LIVE_TICK:-15}"
SCENARIO_FILE="${DEMO_SCENARIO_FILE:-/demo-control/demo-scenario.conf}"
APPLIED_FILE="${DEMO_SCENARIO_APPLIED_FILE:-/demo-control/demo-scenario-applied.conf}"

# <first topic> <second topic> <curve in signals.awk>: one line per pair, the one place to add one.
PAIRS="demo.live.orders demo.live.shipped unshipped
demo.live.requests demo.live.processed backlog
demo.live.imports demo.live.indexed creep
demo.live.events demo.live.archived jitter
demo.live.tickets demo.live.resolved shift"

# The curve value at epoch second $1, as an awk expression $2 over the functions in signals.awk.
signal() { awk -v t="$1" -v mode="$3" "$(cat "$SIGNALS")
BEGIN { printf \"%d\", scenario_value(\"$2\", t, mode) }"; }

echo "Waiting for Kafka at $BOOTSTRAP..."
until "$KAFKA_BIN/kafka-broker-api-versions.sh" --bootstrap-server "$BOOTSTRAP" >/dev/null 2>&1; do
  sleep 2
done

# One partition: the offsets of a topic are then its count, which is what the metric reads.
printf '%s\n' "$PAIRS" | while read -r left right _; do
  for topic in "$left" "$right"; do
    "$KAFKA_BIN/kafka-topics.sh" --bootstrap-server "$BOOTSTRAP" --create --if-not-exists \
      --topic "$topic" --partitions 1 --replication-factor 1 >/dev/null
  done
done

# One long-lived producer per topic, fed through a named pipe: a producer started per record would
# spend more time in the JVM's start-up than in the interval it is meant to fill. A pipe closes its
# reader when its last writer goes, so a `sleep` holds the write end open for as long as the script
# lives — in place of a numbered descriptor, which `sh` limits to one digit and ten topics exceed.
FIFOS="$(mktemp -d)"
trap 'rm -rf "$FIFOS"; kill $(jobs -p) 2>/dev/null || true' EXIT INT TERM
for topic in $(printf '%s\n' "$PAIRS" | awk '{ print $1; print $2 }'); do
  mkfifo "$FIFOS/$topic"
  "$KAFKA_BIN/kafka-console-producer.sh" --bootstrap-server "$BOOTSTRAP" --topic "$topic" \
    --property parse.key=true --property key.separator='|' <"$FIFOS/$topic" >/dev/null &
  sleep 31536000 >"$FIFOS/$topic" &
done

# The log end of every topic in one call (a JVM per question would be most of the cost of a tick).
end_offsets() {
  "$KAFKA_BIN/kafka-get-offsets.sh" --bootstrap-server "$BOOTSTRAP" --time -1
}
count_of() { # <topic> <offsets>
  printf '%s\n' "$2" | awk -F: -v topic="$1" '$1 == topic { sum += $3 } END { print sum + 0 }'
}

# Brings (left - right) to the target by producing the difference to whichever side is short, under
# the keys the other side already holds (the k-th of each is `<name>-k`, name = the left topic's last segment).
hold() { # <offsets> <left topic> <right topic> <target> <now>
  have_left=$(count_of "$2" "$1"); have_right=$(count_of "$3" "$1")
  gap=$(( $4 - (have_left - have_right) ))
  topic=$2; from=$have_left
  [ "$gap" -ge 0 ] || { gap=$((-gap)); topic=$3; from=$have_right; }
  [ "$gap" -gt 0 ] || return 0
  name=${2##*.}
  awk -v n="$gap" -v from="$from" -v name="$name" -v ms="$(($5 * 1000))" \
    'BEGIN { for (i = 1; i <= n; i++) printf "%s-%d|{\"event_time\":%s,\"id\":\"%s-%d\"}\n", name, from + i, ms, name, from + i }' \
    >"$FIFOS/$topic"
}

# Verify *observed Kafka offsets*, not FIFO writes. Async Kafka producers can
# persist a tick over multiple offset polls; requiring an exact integer match at a
# particular second left overload stuck in APPLYING even after its demo metrics had
# crossed their thresholds. Use the same scenario thresholds as the public smoke,
# and a bounded tolerance for time-varying signals. Unrelated pairs keep evolving
# and must not block overload, incident or seasonal acknowledgement.
targets_reached() { # <offset snapshot> <epoch> <scenario>
  printf '%s\n' "$PAIRS" | while read -r left right curve; do
    case "$3:$curve" in
      overload:unshipped|overload:backlog|incident:shift|incident:jitter|seasonal:backlog|recover:*|normal:*)
        actual=$(( $(count_of "$left" "$1") - $(count_of "$right" "$1") ))
        wanted=$(signal "$2" "$curve" "$3")
        delta=$((actual - wanted))
        [ "$delta" -ge 0 ] || delta=$((-delta))
        case "$3:$curve" in
          # Bounded threshold windows around the fixed scenario targets
          # (480/185 and 195/180) exclude both the old baseline and a
          # still-unsettled overshoot. Never confirm arbitrary excess traffic.
          overload:unshipped) [ "$actual" -ge 460 ] && [ "$actual" -le 510 ] || return 1 ;;
          overload:backlog)   [ "$actual" -ge 165 ] && [ "$actual" -le 215 ] || return 1 ;;
          incident:shift)    [ "$actual" -ge 175 ] && [ "$actual" -le 225 ] || return 1 ;;
          incident:jitter)   [ "$actual" -ge 155 ] && [ "$actual" -le 210 ] || return 1 ;;
          # Return close to every fixed recovery baseline; the public demo
          # specifically requires requests <= 40 and tickets <= 70.
          recover:*) [ "$delta" -le 20 ] || return 1 ;;
          # A minute boundary may shift baseline noise before a subsequent
          # offset poll. Never require an exact match for continuous signals.
          *) [ "$delta" -le 30 ] || return 1 ;;
        esac
        ;;
    esac
  done
}
# A container restart invalidates a previous generator's heartbeat immediately.
[ ! -d "${APPLIED_FILE%/*}" ] || rm -f "$APPLIED_FILE"
previous_request=''
previous_at=''
previous_mode=''
pending_ticks=0
echo "Producing live traffic every ${TICK}s (five topic pairs: orders, requests, imports, events, tickets)."
while :; do
  now=$(date +%s)
  offsets=$(end_offsets) || offsets=""
  request=normal
  if [ -r "$SCENARIO_FILE" ]; then
    IFS= read -r request < "$SCENARIO_FILE" || true
    request=$(printf '%s' "$request" | tr -d "\r")
  fi
  mode=${request%%|*}
  case "$mode" in normal|overload|incident|recover|seasonal) ;; *) mode=normal; request=normal ;; esac
  if [ "$request" != "$previous_request" ]; then
    pending_ticks=0
    echo "Demo traffic request: $mode (Kafka offset verification pending)"
  fi
  if [ -n "$offsets" ]; then
    # A new request is only ACTIVE after a complete tick has been observed in Kafka.
    # Recheck every tick; a stalled producer never gets a success receipt.
    if [ "$request" = "$previous_request" ] && [ -n "$previous_at" ] &&
       [ -d "${APPLIED_FILE%/*}" ] &&
       targets_reached "$offsets" "$previous_at" "$previous_mode"; then
      receipt_tmp="${APPLIED_FILE}.pending"
      started=$now
      if [ -r "$APPLIED_FILE" ]; then
        old_receipt=$(cat "$APPLIED_FILE" 2>/dev/null || true)
        case "$old_receipt" in
          v2\|*)
            old_rest=${old_receipt#v2|}
            old_started=${old_rest##*|}
            old_rest=${old_rest%|*}
            old_request=${old_rest%|*}
            if [ "$old_request" = "$request" ]; then
              case "$old_started" in *[!0-9]*|'') ;; *) started=$old_started ;; esac
            fi
            ;;
        esac
      fi
      printf 'v2|%s|%s|%s\n' "$request" "$now" "$started" > "$receipt_tmp"
      mv -f "$receipt_tmp" "$APPLIED_FILE"
      if [ "$started" = "$now" ]; then
        echo "Demo traffic verified: $mode at Kafka offsets ($now)"
      fi
      pending_ticks=0
    else
      pending_ticks=$((pending_ticks + 1))
      # Lightweight operational breadcrumb when an overloaded runner cannot
      # drain the Kafka producer buffers. Do not log individual records.
      if [ "$((pending_ticks % 4))" -eq 0 ]; then
        echo "Scenario $mode pending ($pending_ticks offset polls): orders=$(( $(count_of demo.live.orders "$offsets") - $(count_of demo.live.shipped "$offsets") )), requests=$(( $(count_of demo.live.requests "$offsets") - $(count_of demo.live.processed "$offsets") )), tickets=$(( $(count_of demo.live.tickets "$offsets") - $(count_of demo.live.resolved "$offsets") )), events=$(( $(count_of demo.live.events "$offsets") - $(count_of demo.live.archived "$offsets") ))"
      fi
    fi
    printf '%s\n' "$PAIRS" | while read -r left right curve; do
      hold "$offsets" "$left" "$right" "$(signal "$now" "$curve" "$mode")" "$now"
    done
    previous_request=$request
    previous_at=$now
    previous_mode=$mode
  fi
  sleep "$TICK"
done
