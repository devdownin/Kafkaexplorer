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
# It lives in a script rather than in four copies of a compose `entrypoint:` one-liner,
# which is where this logic used to be duplicated.
set -e

BOOTSTRAP="${1:-kafka:29092}"
MARKER="internal.demo.seeded"
KAFKA_BIN=/opt/kafka/bin

echo "Waiting for Kafka at $BOOTSTRAP..."
until "$KAFKA_BIN/kafka-broker-api-versions.sh" --bootstrap-server "$BOOTSTRAP" >/dev/null 2>&1; do
  sleep 2
done

if "$KAFKA_BIN/kafka-topics.sh" --bootstrap-server "$BOOTSTRAP" --list 2>/dev/null | grep -qx "$MARKER"; then
  echo "Demo data already present (marker topic $MARKER) — skipping the seed."
  echo "Re-seed from scratch with: docker compose down -v"
  exit 0
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
