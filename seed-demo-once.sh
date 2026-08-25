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
# Why "we could not ask" is a third answer. The canary check reads offsets, and a read can
# fail — a broker still settling, a CLI missing from a different image. Folding that into
# "the topic is empty" looks like the safe direction and is not: it is not paid once but at
# *every* `up`, and each replay adds one more generation of duplicates to a dataset the
# cluster audit's duplicate detection and the Stream Flow traces are calibrated against —
# the exact damage the marker exists to prevent. The marker's presence is itself evidence
# that a seed once succeeded, so when the data cannot be examined that evidence is what
# stands, loudly, with the one command that forces a re-seed. An *absent* canary topic is a
# different matter and does mean the data is gone: topics do not expire, records do.
#
# It lives in a script rather than in four copies of a compose `entrypoint:` one-liner,
# which is where this logic used to be duplicated.
set -e

BOOTSTRAP="${1:-kafka:29092}"
MARKER="internal.demo.seeded"
# One topic of the seeded set, checked for records. The order pipeline's first step: it
# is what every stack's demo revolves around, and setup-demo.sh always creates it.
CANARY="demo.orders.1.received"
# Overridable so the decisions below can be exercised against stub CLIs; the images this
# runs in all put the tools here.
KAFKA_BIN="${KAFKA_BIN:-/opt/kafka/bin}"

echo "Waiting for Kafka at $BOOTSTRAP..."
until "$KAFKA_BIN/kafka-broker-api-versions.sh" --bootstrap-server "$BOOTSTRAP" >/dev/null 2>&1; do
  sleep 2
done

# The cluster's topic list, read once and retried: everything below is asked of it, and the
# difference between "the answer is no" and "we could not ask" is the whole point of what
# follows — so it is worth asking more than once before settling for the second.
TOPICS=""
LIST_READ=no
attempt=1
while [ "$attempt" -le 3 ]; do
  if TOPICS=$("$KAFKA_BIN/kafka-topics.sh" --bootstrap-server "$BOOTSTRAP" --list 2>/dev/null); then
    LIST_READ=yes
    break
  fi
  attempt=$((attempt + 1))
  sleep 2
done

has_topic() {
  printf '%s
' "$TOPICS" | grep -qx "$1"
}

# One of: populated | empty | absent | unknown. Records held by $CANARY are its end offsets
# minus its beginning offsets, summed over its partitions — what survives retention rather
# than what was ever produced. The four answers are kept apart because two of them mean
# "seed again" and two of them do not: a topic that is gone or provably empty has lost its
# data, while a query that failed says nothing about the data at all.
canary_state() {
  if [ "$LIST_READ" = no ]; then
    echo unknown
    return
  fi
  if ! has_topic "$CANARY"; then
    echo absent
    return
  fi
  ends=$("$KAFKA_BIN/kafka-get-offsets.sh" --bootstrap-server "$BOOTSTRAP"     --topic "$CANARY" --time -1 2>/dev/null) || { echo unknown; return; }
  begins=$("$KAFKA_BIN/kafka-get-offsets.sh" --bootstrap-server "$BOOTSTRAP"     --topic "$CANARY" --time -2 2>/dev/null) || { echo unknown; return; }
  # The topic exists, so a reply with no partition line in it did not answer the question
  # either — an exit status of 0 is not on its own a measurement.
  if [ -z "$ends" ] || [ -z "$begins" ]; then
    echo unknown
    return
  fi
  ends=$(printf '%s
' "$ends" | awk -F: '{ total += $3 } END { print total + 0 }')
  begins=$(printf '%s
' "$begins" | awk -F: '{ total += $3 } END { print total + 0 }')
  if [ "$((ends - begins))" -gt 0 ]; then
    echo populated
  else
    echo empty
  fi
}

if [ "$LIST_READ" = no ]; then
  echo "Could not read the topic list from $BOOTSTRAP — so whether this cluster has already"
  echo "been seeded is unknown. Doing nothing rather than seeding blind: a second seed would"
  echo "add one more generation of duplicates to a dataset the cluster audit and the Stream"
  echo "Flow traces are calibrated against. The next 'docker compose up' runs this again."
  exit 0
fi

if has_topic "$MARKER"; then
  STATE=$(canary_state)
  case "$STATE" in
    populated)
      echo "Demo data already present (marker topic $MARKER) — skipping the seed."
      echo "Re-seed from scratch with: docker compose down -v"
      exit 0
      ;;
    unknown)
      echo "Marker topic $MARKER exists, but $CANARY's offsets could not be read — so whether the"
      echo "demo data is still there is unknown. Trusting the marker and skipping: 'we could not"
      echo "ask' is not 'the topic is empty', and treating it as one would re-seed at every start,"
      echo "each run adding a generation of duplicates. If the demo really is empty, force one:"
      echo "  kafka-topics.sh --bootstrap-server $BOOTSTRAP --delete --topic $MARKER"
      exit 0
      ;;
    absent)
      echo "Marker topic $MARKER exists but $CANARY does not — the demo topics have been removed."
      echo "Seeding again."
      ;;
    *)
      echo "Marker topic $MARKER exists but $CANARY holds no records — the demo data has been"
      echo "deleted by retention, or removed. Seeding again."
      ;;
  esac
  "$KAFKA_BIN/kafka-topics.sh" --bootstrap-server "$BOOTSTRAP"     --delete --topic "$MARKER" >/dev/null 2>&1 || true
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
