#!/bin/sh
# SPDX-License-Identifier: AGPL-3.0-or-later
# Copyright (C) 2026 Kafka Explorer Contributors
#
# Entrypoint of the `demo-setup` service in every bundled stack: wait for the broker,
# seed the demo topics through setup-demo.sh — but only once.
#
# Why the marker. Kafka data lives in the `kafka_data` named volume, so it survives
# `docker compose down`. `demo-setup` is a one-shot service, and compose re-runs a
# one-shot on every `up`, which meant setup-demo.sh replayed its ~400 records into
# topics that already held them: a minute of seeding paid again at each start, and a
# demo dataset that grew a duplicate generation every time — quietly changing what the
# audit's duplicate detection and the Stream Flow traces report. The marker topic is
# created only after a successful seed, so an interrupted run seeds again next time,
# and `docker compose down -v` (which wipes the volume) resets both.
#
# Why the marker is not enough on its own. It is a *topic*, and a topic never expires;
# the records it vouches for do. On a stack left up — or brought back up — past the
# demo topics' retention, the broker comes back with ~80 topic names, no records in any
# of them, and a marker that makes this script skip the seed for ever. That is not a
# hypothetical: it is the state this file was found in, and the whole Process Mining
# pipeline profiles an empty cluster while everything looks healthy. The documented way
# out, `docker compose down -v`, also wipes the explorer's own volume, so it takes the
# operator's saved settings with it.
#
# So the marker answers "has this been seeded before", and the canary answers "is the
# data still there" — and only both together mean there is nothing to do.
#
# It lives in a script rather than in four copies of a compose `entrypoint:` one-liner,
# which is where this logic used to be duplicated.
set -e

BOOTSTRAP="${1:-kafka:29092}"
MARKER="internal.demo.seeded"
# One topic of the seeded set, checked for records. The order pipeline's first step: it
# is what every stack's demo revolves around, and setup-demo.sh always creates it.
CANARY="demo.orders.1.received"
KAFKA_BIN=/opt/kafka/bin

echo "Waiting for Kafka at $BOOTSTRAP..."
until "$KAFKA_BIN/kafka-broker-api-versions.sh" --bootstrap-server "$BOOTSTRAP" >/dev/null 2>&1; do
  sleep 2
done

# Records held by $CANARY, summed over its partitions: end offsets minus beginning
# offsets, which is what survives retention rather than what was ever produced. Prints 0
# when the topic is absent, empty or unreadable — all three mean "do not trust the
# marker", which is the safe direction: seeding again costs a minute, and skipping
# wrongly costs a demo that silently has no data in it.
records_in_canary() {
  ends=$("$KAFKA_BIN/kafka-get-offsets.sh" --bootstrap-server "$BOOTSTRAP" \
    --topic "$CANARY" --time -1 2>/dev/null | awk -F: '{ total += $3 } END { print total + 0 }')
  begins=$("$KAFKA_BIN/kafka-get-offsets.sh" --bootstrap-server "$BOOTSTRAP" \
    --topic "$CANARY" --time -2 2>/dev/null | awk -F: '{ total += $3 } END { print total + 0 }')
  echo $((ends - begins))
}

if "$KAFKA_BIN/kafka-topics.sh" --bootstrap-server "$BOOTSTRAP" --list 2>/dev/null | grep -qx "$MARKER"; then
  if [ "$(records_in_canary)" -gt 0 ]; then
    echo "Demo data already present (marker topic $MARKER) — skipping the seed."
    echo "Re-seed from scratch with: docker compose down -v"
    exit 0
  fi
  echo "Marker topic $MARKER exists but $CANARY holds no records — the demo data has been"
  echo "deleted by retention, or removed. Seeding again."
  "$KAFKA_BIN/kafka-topics.sh" --bootstrap-server "$BOOTSTRAP" \
    --delete --topic "$MARKER" >/dev/null 2>&1 || true
fi

# The script is bind-mounted from the host checkout, which on Windows may hold CRLF
# line endings that the shell refuses. Strip them into a copy rather than editing the
# mount (which would write back into the user's working tree).
sed 's/\r$//' /data/setup-demo.sh > /tmp/setup-demo.sh
# setup-demo.sh uses bash arrays, so it needs bash, not the busybox sh running this.
bash /tmp/setup-demo.sh "$BOOTSTRAP"

"$KAFKA_BIN/kafka-topics.sh" --bootstrap-server "$BOOTSTRAP" \
  --create --topic "$MARKER" --partitions 1 --replication-factor 1 >/dev/null 2>&1 || true
echo "Demo data seeded ($MARKER created — later starts will skip the seed)."
