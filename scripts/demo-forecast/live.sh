#!/bin/sh
# SPDX-License-Identifier: AGPL-3.0-or-later
# Copyright (C) 2026 Kafka Explorer Contributors
#
# Live traffic for the forecast demo: the two topic pairs behind its two forecastable metrics,
# fed once the demo is up and for as long as it runs — the rest of the demo's topics are seeded
# once and then stand still, so a metric over them is one value for ever and has no history to
# forecast. See signals.awk for the two curves and docs/metrics-demo-scenarios.md for the story.
#
#   demo.live.orders   -> demo.live.shipped     orders taken and not yet shipped, on the
#                                               "unshipped" curve: a three-hour ramp, then drained.
#   demo.live.requests -> demo.live.processed   requests waiting, on the "backlog" curve: hourly.
#
# Each pair is held on its curve by reading the end offsets and producing the difference to
# whichever side is short. The metric over a pair is the difference of the two end offsets, so
# what is produced here is exactly what it reads: no timestamp to back-date, no window to blur it.
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

ORDERS=demo.live.orders
SHIPPED=demo.live.shipped
REQUESTS=demo.live.requests
PROCESSED=demo.live.processed

# The curve value at epoch second $1, as an awk expression $2 over the functions in signals.awk.
signal() { awk -v t="$1" "$(cat "$SIGNALS")
BEGIN { printf \"%d\", $2 }"; }

echo "Waiting for Kafka at $BOOTSTRAP..."
until "$KAFKA_BIN/kafka-broker-api-versions.sh" --bootstrap-server "$BOOTSTRAP" >/dev/null 2>&1; do
  sleep 2
done

# One partition: the offsets of a topic are then its count, which is what the metric reads.
for topic in "$ORDERS" "$SHIPPED" "$REQUESTS" "$PROCESSED"; do
  "$KAFKA_BIN/kafka-topics.sh" --bootstrap-server "$BOOTSTRAP" --create --if-not-exists \
    --topic "$topic" --partitions 1 --replication-factor 1 >/dev/null
done

# One long-lived producer per topic, fed through a pipe: a producer started per record would
# spend more time in the JVM's start-up than in the interval it is meant to fill.
FIFOS="$(mktemp -d)"
trap 'rm -rf "$FIFOS"; kill $(jobs -p) 2>/dev/null || true' EXIT INT TERM
n=3
for topic in "$ORDERS" "$SHIPPED" "$REQUESTS" "$PROCESSED"; do
  mkfifo "$FIFOS/$topic"
  "$KAFKA_BIN/kafka-console-producer.sh" --bootstrap-server "$BOOTSTRAP" --topic "$topic" \
    --property parse.key=true --property key.separator='|' <"$FIFOS/$topic" >/dev/null &
  # Opening the write end is what lets the producer's own open of the read end complete.
  eval "exec $n>\"$FIFOS/$topic\""
  n=$((n + 1))
done
# fd 3 orders, 4 shipped, 5 requests, 6 processed.

# The log end of every topic in one call (a JVM per question would be most of the cost of a tick).
end_offsets() {
  "$KAFKA_BIN/kafka-get-offsets.sh" --bootstrap-server "$BOOTSTRAP" --time -1
}
count_of() { # <topic> <offsets>
  printf '%s\n' "$2" | awk -F: -v topic="$1" '$1 == topic { sum += $3 } END { print sum + 0 }'
}

# Brings (left - right) to the target by producing the difference to whichever side is short.
hold() { # <offsets> <left topic> <left fd> <right topic> <right fd> <target> <now>
  gap=$(( $6 - ( $(count_of "$2" "$1") - $(count_of "$4" "$1") ) ))
  fd=$3
  [ "$gap" -ge 0 ] || { gap=$((-gap)); fd=$5; }
  i=0
  while [ "$i" -lt "$gap" ]; do
    printf 'r-%s-%s|{"event_time":%s}\n' "$7" "$i" "$(($7 * 1000))" >&$fd
    i=$((i + 1))
  done
}

echo "Producing live traffic every ${TICK}s (orders -> shipped, requests -> processed)."
while :; do
  now=$(date +%s)
  offsets=$(end_offsets) || offsets=""
  if [ -n "$offsets" ]; then
    hold "$offsets" "$ORDERS" 3 "$SHIPPED" 4 "$(signal "$now" "unshipped(t)")" "$now"
    hold "$offsets" "$REQUESTS" 5 "$PROCESSED" 6 "$(signal "$now" "backlog(t)")" "$now"
  fi
  sleep "$TICK"
done
