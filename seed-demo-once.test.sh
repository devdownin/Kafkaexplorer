#!/bin/sh
# SPDX-License-Identifier: AGPL-3.0-or-later
# Copyright (C) 2026 Kafka Explorer Contributors
#
# What seed-demo-once.sh decides, and nothing else.
#
# That script answers one question — should the demo be seeded again? — from two
# readings of the cluster, and the interesting part is what it does when a reading
# fails. Nothing exercised it: CI's `docker` job asserts that a second run skips, which
# is the one path that has always worked, and every other branch was reasoned about
# rather than run. The two defects this file was written after were both in that gap: a
# marker that made the seeder skip for ever once retention had emptied what it vouched
# for, and then an unreadable offset query counted as "the topic is empty", which
# re-seeds at every `up` and adds a generation of duplicate records each time.
#
# The broker is stubbed rather than started. What is under test is a decision taken from
# two command outputs, so the cost of a real broker buys nothing here — and this runs in
# a second, on any machine, with no daemon. `KafkaClusterIntegrationTest` is where a
# claim about how Kafka itself behaves belongs.
#
# Run it directly: ./seed-demo-once.test.sh
set -eu

HERE=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
# Overridable so a case can be run against another revision of the script — which is how
# each case below was checked to fail against the code it describes.
SEEDER="${SEEDER:-$HERE/seed-demo-once.sh}"
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT

MARKER="internal.demo.seeded"
CANARY="demo.orders.1.received"
FAILURES=0

# ---------------------------------------------------------------------------
# The stubs. Each reads the cluster state out of the environment, so a case below
# describes a cluster in the variables it sets and nothing else.
# ---------------------------------------------------------------------------
mkdir -p "$WORK/bin"

cat > "$WORK/bin/kafka-broker-api-versions.sh" <<'STUB'
#!/bin/sh
exit 0
STUB

cat > "$WORK/bin/kafka-topics.sh" <<'STUB'
#!/bin/sh
case "$*" in
  *--list*)
    # An unreadable list is a non-zero exit, which is what the real tool does when it
    # cannot reach the broker — never an empty list, the distinction under test.
    [ "${LIST_FAILS:-no}" = yes ] && exit 1
    for t in ${STUB_TOPICS:-}; do echo "$t"; done
    exit 0 ;;
  *--delete*) echo "$*" >> "$STUB_LOG"; exit 0 ;;
  *--create*) echo "$*" >> "$STUB_LOG"; exit 0 ;;
esac
exit 0
STUB

cat > "$WORK/bin/kafka-get-offsets.sh" <<'STUB'
#!/bin/sh
# Three ways to answer: the offsets, a failure, or a success carrying nothing — the last
# being the one that looks like a measurement and is not.
[ "${OFFSETS_FAIL:-no}" = yes ] && exit 1
[ "${OFFSETS_SILENT:-no}" = yes ] && exit 0
case "$*" in
  *"--time -1"*) for l in ${STUB_ENDS:-}; do echo "$l"; done ;;
  *"--time -2"*) for l in ${STUB_BEGINS:-}; do echo "$l"; done ;;
esac
exit 0
STUB

# Stands in for setup-demo.sh: the seeder is asserted to have *run* it, rather than to
# have crashed on its absence.
cat > "$WORK/setup-demo.sh" <<'STUB'
#!/bin/sh
echo "seeded $1" >> "$STUB_LOG"
STUB

chmod +x "$WORK/bin/"*.sh "$WORK/setup-demo.sh"

# ---------------------------------------------------------------------------
# Harness
# ---------------------------------------------------------------------------

# run <case name> <expect: seeds|skips> [VAR=value ...]
run() {
  name=$1
  expectation=$2
  shift 2

  : > "$WORK/log"
  set +e
  out=$(env KAFKA_BIN="$WORK/bin" SETUP_SCRIPT="$WORK/setup-demo.sh" \
    STUB_LOG="$WORK/log" "$@" sh "$SEEDER" localhost:9092 2>&1)
  code=$?
  set -e
  # Set before the checks below, not after them: a case that fails still has output, and
  # leaving `says` to read the previous case's is how a harness reports the wrong thing —
  # and under `set -u` an early return would leave it unset altogether.
  LAST_OUT=$out

  seeded=no
  grep -q '^seeded ' "$WORK/log" 2>/dev/null && seeded=yes

  if [ "$code" -ne 0 ]; then
    printf '✗ %s\n  exited %s\n%s\n' "$name" "$code" "$out" | sed 's/^/    /'
    FAILURES=$((FAILURES + 1))
    return
  fi
  if [ "$expectation" = seeds ] && [ "$seeded" = no ]; then
    printf '✗ %s\n  expected a seed, and setup-demo.sh was never run\n%s\n' "$name" "$out" | sed 's/^/    /'
    FAILURES=$((FAILURES + 1))
    return
  fi
  if [ "$expectation" = skips ] && [ "$seeded" = yes ]; then
    printf '✗ %s\n  expected the seed to be skipped, and it ran\n%s\n' "$name" "$out" | sed 's/^/    /'
    FAILURES=$((FAILURES + 1))
    return
  fi
  printf '✓ %s (%s)\n' "$name" "$expectation"
}

# says <substring> — about the run that just happened. A skip and a skip that explains
# itself are not the same thing: the whole point of the `unknown` branch is the sentence
# it prints, which is the only thing telling an operator why nothing happened.
says() {
  case "$LAST_OUT" in
    *"$1"*) ;;
    *)
      printf '✗   ...but it never said "%s"\n' "$1"
      FAILURES=$((FAILURES + 1)) ;;
  esac
}

# logged <substring> — a command the seeder issued to the broker.
logged() {
  if ! grep -q -- "$1" "$WORK/log"; then
    printf '✗   ...but it never ran a command matching "%s"\n' "$1"
    FAILURES=$((FAILURES + 1))
  fi
}

# ---------------------------------------------------------------------------
# The cases. Two of them are measurements the seeder must act on, and three are
# non-answers it must not mistake for one.
# ---------------------------------------------------------------------------

run "a cluster nobody has seeded yet is seeded" seeds \
  STUB_TOPICS="some.other.topic"
logged "--create --topic $MARKER"

run "the marker plus records means there is nothing to do" skips \
  STUB_TOPICS="$MARKER $CANARY" STUB_ENDS="$CANARY:0:8" STUB_BEGINS="$CANARY:0:0"
says "already present"

run "records the retention has deleted are seeded again" seeds \
  STUB_TOPICS="$MARKER $CANARY" STUB_ENDS="$CANARY:0:8" STUB_BEGINS="$CANARY:0:8"
says "holds no records"
logged "--delete --topic $MARKER"

run "a canary topic that is gone is seeded again" seeds \
  STUB_TOPICS="$MARKER"
says "does not"

# The three below are the reason this file exists. Each is a reading that failed, and
# each used to be indistinguishable from "the topic is empty" — which re-seeds, at every
# single start, quietly changing what the audit's duplicate detection reports.
run "an offset query that failed is not an empty topic" skips \
  STUB_TOPICS="$MARKER $CANARY" OFFSETS_FAIL=yes
says "could not be read"

run "an offset query that answered nothing is not an empty topic" skips \
  STUB_TOPICS="$MARKER $CANARY" OFFSETS_SILENT=yes
says "could not be read"

run "a topic list that could not be read seeds nothing" skips \
  LIST_FAILS=yes
says "Could not read the topic list"

if [ "$FAILURES" -ne 0 ]; then
  printf '\n%s case(s) failed\n' "$FAILURES"
  exit 1
fi
printf '\nEvery decision of the seeder holds.\n'
