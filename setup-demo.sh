#!/bin/bash
# SPDX-License-Identifier: AGPL-3.0-or-later
# Copyright (C) 2026 Kafka Explorer Contributors
#
# Seeds a Kafka cluster with the Kafka Explorer demo dataset.
#
#   ./setup-demo.sh [bootstrap-server]
#
# Environment knobs:
#   DEMO_HOP_DELAY   seconds slept between two hops of the traced order pipeline
#                    (default 2, set to 0 for the fastest possible seeding).
#                    Record timestamps are set by the broker at produce time, so a
#                    real pause is the only way to give Stream Flow a measurable
#                    hop latency to chart.
#   DEMO_PARALLEL    how many kafka CLI processes run at once (default 8). Each
#                    call is a JVM start, and there are ~80 topics to create.
#
# Design notes for whoever extends this:
#   * Messages are produced in ONE kafka-console-producer call per topic, not one
#     per message. A JVM start costs ~1.5 s, and the previous per-message version
#     spent ~8 minutes seeding 250 records while docker-compose blocked the app on
#     it (`depends_on: demo-setup: service_completed_successfully`).
#   * Every business record carries a RECORD KEY and KAFKA HEADERS. Without them,
#     exact-key tracing, key-partition narrowing, header search, compaction and the
#     audit's key-based duplicate detection have nothing to run against.
#   * No `bc`, no `date -d`: the seeding runs inside the apache/kafka image
#     (busybox), on macOS and on GNU hosts alike. Money is integer cents formatted
#     with printf, and event times are epoch seconds (the query engine reads any
#     value below 10^10 as epoch seconds).

BOOTSTRAP_SERVER=${1:-"localhost:9092"}
DEMO_HOP_DELAY=${DEMO_HOP_DELAY:-2}
DEMO_PARALLEL=${DEMO_PARALLEL:-8}

# Robust command detection (Apache Kafka uses .sh suffix and /opt/kafka/bin path)
if command -v kafka-topics >/dev/null 2>&1; then
  KAFKA_TOPICS_CMD="kafka-topics"
elif [ -f "/opt/kafka/bin/kafka-topics.sh" ]; then
  KAFKA_TOPICS_CMD="/opt/kafka/bin/kafka-topics.sh"
else
  KAFKA_TOPICS_CMD="kafka-topics.sh"
fi

if command -v kafka-console-producer >/dev/null 2>&1; then
  KAFKA_PRODUCER_CMD="kafka-console-producer"
elif [ -f "/opt/kafka/bin/kafka-console-producer.sh" ]; then
  KAFKA_PRODUCER_CMD="/opt/kafka/bin/kafka-console-producer.sh"
else
  KAFKA_PRODUCER_CMD="kafka-console-producer.sh"
fi

if command -v kafka-console-consumer >/dev/null 2>&1; then
  KAFKA_CONSUMER_CMD="kafka-console-consumer"
elif [ -f "/opt/kafka/bin/kafka-console-consumer.sh" ]; then
  KAFKA_CONSUMER_CMD="/opt/kafka/bin/kafka-console-consumer.sh"
else
  KAFKA_CONSUMER_CMD="kafka-console-consumer.sh"
fi

echo "--- Starting Kafka Demo Setup ---"
echo "Bootstrap Server: $BOOTSTRAP_SERVER"

# Record headers need `--property parse.headers=true` (KIP-798, Kafka 3.1+). Against an
# older CLI the whole batch would be rejected, so probe once and degrade to keys only
# rather than seeding nothing.
if $KAFKA_PRODUCER_CMD --help 2>&1 | grep -q "parse.headers"; then
  HEADERS_SUPPORTED=1
else
  HEADERS_SUPPORTED=0
  echo "! This kafka-console-producer does not support parse.headers (Kafka < 3.1):"
  echo "  records will be seeded with keys but without headers, and the header-search"
  echo "  scenarios (demo.payments.* / demo.shipments.*) will have nothing to match."
fi

BUFDIR=$(mktemp -d 2>/dev/null || echo "/tmp/kse-demo-$$")
mkdir -p "$BUFDIR/keyed" "$BUFDIR/raw"
trap 'rm -rf "$BUFDIR"' EXIT

TS=$(date +%s)
DATE_NOW=$(date -u +"%Y-%m-%dT%H:%M:%SZ")

# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------

# Runs a command in the background, keeping at most $DEMO_PARALLEL in flight.
JOBS_STARTED=0
throttle() {
  "$@" &
  JOBS_STARTED=$((JOBS_STARTED + 1))
  if [ $((JOBS_STARTED % DEMO_PARALLEL)) -eq 0 ]; then
    wait
  fi
}

create_topic() {
  # create_topic <name> <partitions> [config...]
  local topic=$1 partitions=$2
  shift 2
  local args=()
  for cfg in "$@"; do args+=(--config "$cfg"); done
  if ! $KAFKA_TOPICS_CMD --bootstrap-server "$BOOTSTRAP_SERVER" --create --topic "$topic" \
       --partitions "$partitions" --replication-factor 1 --if-not-exists "${args[@]}" >/dev/null 2>&1; then
    echo "  ! could not create topic $topic"
  fi
}

# Buffers a keyed record. The console producer reads `headers<TAB>key<TAB>value`
# when parse.headers and parse.key are both on; headers are `k1:v1,k2:v2`, so a
# header VALUE must never contain a comma, a colon or a tab (epoch millis, not
# ISO-8601, is why the produced-at header is a number).
buffer() {
  # buffer <topic> <headers> <key> <value>
  printf '%s\t%s\t%s\n' "$2" "$3" "$4" >> "$BUFDIR/keyed/$1"
}

# Buffers a record with no key and no headers — used for payloads that are
# deliberately malformed, where any framing would be a lie about the producer.
buffer_raw() {
  # buffer_raw <topic> <value>
  printf '%s\n' "$2" >> "$BUFDIR/raw/$1"
}

flush_keyed() {
  local topic=$1
  local file="$BUFDIR/keyed/$topic"
  [ -s "$file" ] || return 0
  if [ "$HEADERS_SUPPORTED" -eq 1 ]; then
    $KAFKA_PRODUCER_CMD --bootstrap-server "$BOOTSTRAP_SERVER" --topic "$topic" \
      --property parse.key=true --property parse.headers=true \
      < "$file" >/dev/null 2>&1 || echo "  ! could not produce to $topic"
  else
    # Drop the leading headers column; the rest is still `key<TAB>value`.
    cut -f2- < "$file" \
      | $KAFKA_PRODUCER_CMD --bootstrap-server "$BOOTSTRAP_SERVER" --topic "$topic" \
        --property parse.key=true >/dev/null 2>&1 || echo "  ! could not produce to $topic"
  fi
  rm -f "$file"
}

flush_raw() {
  local topic=$1
  local file="$BUFDIR/raw/$topic"
  [ -s "$file" ] || return 0
  $KAFKA_PRODUCER_CMD --bootstrap-server "$BOOTSTRAP_SERVER" --topic "$topic" \
    < "$file" >/dev/null 2>&1 || echo "  ! could not produce to $topic"
  rm -f "$file"
}

# Flushes every buffered topic, DEMO_PARALLEL producers at a time.
flush_all_keyed() {
  for f in "$BUFDIR"/keyed/*; do
    [ -e "$f" ] || continue
    throttle flush_keyed "$(basename "$f")"
  done
  wait
}

# W3C traceparent: 00-<32 hex trace id>-<16 hex span id>-01. Derived from a
# number so the same order always gets the same trace id across runs.
traceparent() {
  printf '00-4b%030x-%016x-01' "$1" "$2"
}

# Integer cents -> "123.45". Avoids `bc`, absent from the busybox-based image.
money() {
  printf '%d.%02d' $(( $1 / 100 )) $(( $1 % 100 ))
}

# ---------------------------------------------------------------------------
# Topics
# ---------------------------------------------------------------------------

# name:partitions[:config[:config]]
# The order pipeline and the nested topic are multi-partition on purpose: with a
# single partition, "search only this key's partition" narrows nothing and the
# murmur2 routing it relies on can't be observed.
TOPICS=(
  "demo.orders.1.received:3"
  "demo.orders.2.validated:3"
  "demo.orders.3.enriched:3"
  "demo.orders.4.transformed:3"
  "demo.orders.5.shipped:3"
  "demo.orders.6.delivered:3"
  "demo.orders.xml:1"
  "demo.orders.complex:1"
  "demo.orders.nested:6"
  "demo.customers:3:cleanup.policy=compact"
  "demo.errors.poison:1"
  # Header-correlated side flow: the payloads share no id with the orders,
  # only the correlation-id header ties them together.
  "demo.payments.authorized:3"
  "demo.payments.captured:3"
  "demo.shipments.dispatched:3"
  "demo.shipments.delivered:3"
  # Time series with spread event times — the only dataset here on which a
  # TUMBLE/HOP window produces more than one bucket.
  "demo.iot.sensors:3"
)

# Supply Chain 2.0 Steps
SC_STEPS=(
  "01.order.placed"
  "02.payment.pending"
  "03.payment.confirmed"
  "04.inventory.check"
  "05.stock.reserved"
  "06.warehouse.allocated"
  "07.picking.started"
  "08.picking.completed"
  "09.packing.started"
  "10.packing.completed"
  "11.quality.inspected"
  "12.label.printed"
  "13.carrier.assigned"
  "14.package.handed.over"
  "15.in.transit.hub"
  "16.at.customs"
  "17.customs.cleared"
  "18.local.depot"
  "19.out.for.delivery"
  "20.delivered"
)

echo "Creating topics (${DEMO_PARALLEL} in parallel)..."
for ENTRY in "${TOPICS[@]}"; do
  IFS=':' read -r NAME PARTS CFG1 CFG2 <<< "$ENTRY"
  throttle create_topic "$NAME" "$PARTS" ${CFG1:+"$CFG1"} ${CFG2:+"$CFG2"}
done
for STEP in "${SC_STEPS[@]}"; do
  for SUFFIX in "in" "out" "audit"; do
    throttle create_topic "demo.sc.$STEP.$SUFFIX" 1
  done
done
wait
echo "Topics ready (existing topics keep their current partition count)."

echo "Populating topics with demo data..."

# ---------------------------------------------------------------------------
# --- Scenario 1: Nominal Flow (Order #101), traced hop by hop --------------
# ---------------------------------------------------------------------------
# One order walking the 6 steps. Each step is flushed separately with a pause,
# so the hops have real, differentiated latencies: step 3 -> 4 is deliberately
# the slow one, which is what Stream Flow highlights as the bottleneck edge.
TP_101=$(traceparent 101 1)
H101="correlation-id:ORD-101,traceparent:$TP_101,source-system:OMS-v3.2,produced-at:${TS}000"

STEP_TOPICS=(
  "demo.orders.1.received"
  "demo.orders.2.validated"
  "demo.orders.3.enriched"
  "demo.orders.4.transformed"
  "demo.orders.5.shipped"
  "demo.orders.6.delivered"
)
STEP_BODIES=(
  "{\"id\":\"ORD-101\",\"state\":\"RECEIVED\",\"description\":\"Smartphone purchase\",\"type\":\"ELECTRONICS\",\"odate\":\"$DATE_NOW\",\"event_time\":$TS,\"amount\":599.99,\"customer_id\":\"C-001\"}"
  "{\"id\":\"ORD-101\",\"state\":\"VALIDATED\",\"description\":\"Smartphone purchase\",\"type\":\"ELECTRONICS\",\"odate\":\"$DATE_NOW\",\"event_time\":$TS,\"amount\":599.99,\"customer_id\":\"C-001\",\"validation_code\":\"VAL-A1\"}"
  "{\"id\":\"ORD-101\",\"state\":\"ENRICHED\",\"description\":\"Smartphone purchase\",\"type\":\"ELECTRONICS\",\"odate\":\"$DATE_NOW\",\"event_time\":$TS,\"amount\":599.99,\"customer_id\":\"C-001\",\"metadata\":{\"warehouse\":\"WH-NORTH\",\"priority\":\"HIGH\"}}"
  "{\"id\":\"ORD-101\",\"state\":\"TRANSFORMED\",\"description\":\"Smartphone purchase\",\"type\":\"ELECTRONICS\",\"odate\":\"$DATE_NOW\",\"event_time\":$TS,\"total_with_tax\":719.99,\"currency\":\"EUR\"}"
  "{\"id\":\"ORD-101\",\"state\":\"SHIPPED\",\"description\":\"Smartphone purchase\",\"type\":\"ELECTRONICS\",\"odate\":\"$DATE_NOW\",\"event_time\":$TS,\"tracking_number\":\"TRK-123456\"}"
  "{\"id\":\"ORD-101\",\"state\":\"DELIVERED\",\"description\":\"Smartphone purchase\",\"type\":\"ELECTRONICS\",\"odate\":\"$DATE_NOW\",\"event_time\":$TS,\"delivery_status\":\"SUCCESS\"}"
)
STEP_EVENTS=("order.received" "order.validated" "order.enriched" "order.transformed" "order.shipped" "order.delivered")
# Multiplier applied to DEMO_HOP_DELAY after each step: the 3 -> 4 hop is 3x.
STEP_PAUSE=(1 1 3 1 1 0)

echo "Producing the traced order pipeline (ORD-101)..."
for idx in "${!STEP_TOPICS[@]}"; do
  buffer "${STEP_TOPICS[$idx]}" "$H101,event-type:${STEP_EVENTS[$idx]}" "ORD-101" "${STEP_BODIES[$idx]}"
  flush_keyed "${STEP_TOPICS[$idx]}"
  PAUSE=$(( DEMO_HOP_DELAY * ${STEP_PAUSE[$idx]} ))
  [ "$PAUSE" -gt 0 ] && sleep "$PAUSE"
done

# ---------------------------------------------------------------------------
# --- Scenario 2: Rejected Order (Order #102) — a flow that drops off -------
# ---------------------------------------------------------------------------
TP_102=$(traceparent 102 1)
H102="correlation-id:ORD-102,traceparent:$TP_102,source-system:OMS-v3.2,produced-at:${TS}000"
buffer "demo.orders.1.received" "$H102,event-type:order.received" "ORD-102" \
  "{\"id\":\"ORD-102\",\"state\":\"RECEIVED\",\"description\":\"Broken laptop return\",\"type\":\"ELECTRONICS\",\"odate\":\"$DATE_NOW\",\"event_time\":$TS,\"amount\":0.00,\"customer_id\":\"C-002\"}"
buffer "demo.orders.2.validated" "$H102,event-type:order.rejected" "ORD-102" \
  "{\"id\":\"ORD-102\",\"state\":\"REJECTED\",\"description\":\"Broken laptop return\",\"type\":\"ELECTRONICS\",\"odate\":\"$DATE_NOW\",\"event_time\":$TS,\"reason\":\"Warranty expired\"}"

# ---------------------------------------------------------------------------
# --- Scenario 3: Aggregation data, with two at-least-once redeliveries -----
# ---------------------------------------------------------------------------
# ORD-103 and ORD-105 are produced twice with an identical payload. That is what
# an at-least-once producer does after a retry, and it gives the cluster audit's
# duplicate detection a real finding instead of a clean-room zero.
AGG_IDS=(103 104 105 106 103 105)
AGG_BODIES=(
  "{\"id\":\"ORD-103\",\"state\":\"RECEIVED\",\"description\":\"Sofa\",\"type\":\"FURNITURE\",\"odate\":\"$DATE_NOW\",\"event_time\":$TS,\"amount\":1200.00,\"customer_id\":\"C-003\"}"
  "{\"id\":\"ORD-104\",\"state\":\"RECEIVED\",\"description\":\"Table\",\"type\":\"FURNITURE\",\"odate\":\"$DATE_NOW\",\"event_time\":$TS,\"amount\":450.00,\"customer_id\":\"C-003\"}"
  "{\"id\":\"ORD-105\",\"state\":\"RECEIVED\",\"description\":\"Apple\",\"type\":\"FOOD\",\"odate\":\"$DATE_NOW\",\"event_time\":$TS,\"amount\":2.50,\"customer_id\":\"C-001\"}"
  "{\"id\":\"ORD-106\",\"state\":\"RECEIVED\",\"description\":\"Banana\",\"type\":\"FOOD\",\"odate\":\"$DATE_NOW\",\"event_time\":$TS,\"amount\":1.80,\"customer_id\":\"C-001\"}"
  "{\"id\":\"ORD-103\",\"state\":\"RECEIVED\",\"description\":\"Sofa\",\"type\":\"FURNITURE\",\"odate\":\"$DATE_NOW\",\"event_time\":$TS,\"amount\":1200.00,\"customer_id\":\"C-003\"}"
  "{\"id\":\"ORD-105\",\"state\":\"RECEIVED\",\"description\":\"Apple\",\"type\":\"FOOD\",\"odate\":\"$DATE_NOW\",\"event_time\":$TS,\"amount\":2.50,\"customer_id\":\"C-001\"}"
)
for idx in "${!AGG_IDS[@]}"; do
  OID="ORD-${AGG_IDS[$idx]}"
  buffer "demo.orders.1.received" \
    "correlation-id:$OID,traceparent:$(traceparent "${AGG_IDS[$idx]}" $((idx + 1))),source-system:OMS-v3.2,event-type:order.received,produced-at:${TS}000" \
    "$OID" "${AGG_BODIES[$idx]}"
done

# ---------------------------------------------------------------------------
# --- Scenario 4: XML Support (demo.orders.xml) -----------------------------
# ---------------------------------------------------------------------------
# Several documents, of two shapes, so XPath searches (`/Order/Customer/Name`,
# predicates, `//Item`) have something to discriminate between.
buffer "demo.orders.xml" "correlation-id:ORD-XML-01,source-system:B2B-v1.5,event-type:order.received,produced-at:${TS}000" "ORD-XML-01" \
  "<Order id=\"ORD-XML-01\"><Customer>Alice</Customer><Amount>150.00</Amount><Items><Item><Name>SSD</Name><Price>100.00</Price></Item><Item><Name>Cable</Name><Price>50.00</Price></Item></Items></Order>"
buffer "demo.orders.xml" "correlation-id:ORD-XML-02,source-system:B2B-v1.5,event-type:order.received,produced-at:${TS}000" "ORD-XML-02" \
  "<Order id=\"ORD-XML-02\"><Customer>Bob</Customer><Amount>89.90</Amount><Items><Item><Name>Keyboard</Name><Price>89.90</Price></Item></Items></Order>"
buffer "demo.orders.xml" "correlation-id:ORD-XML-03,source-system:B2B-v1.5,event-type:order.received,produced-at:${TS}000" "ORD-XML-03" \
  "<Order id=\"ORD-XML-03\"><Customer>Charlie</Customer><Amount>1250.00</Amount><Items><Item><Name>Workstation</Name><Price>1200.00</Price></Item><Item><Name>Mouse</Name><Price>50.00</Price></Item></Items></Order>"
# Nested shape with attributes and a shipping subtree — exercises the deeper
# XPath expressions (axes, predicates) the simple dot walker cannot express.
buffer "demo.orders.xml" "correlation-id:ORD-XML-04,source-system:B2B-v1.5,event-type:order.enriched,produced-at:${TS}000" "ORD-XML-04" \
  "<Order id=\"ORD-XML-04\" channel=\"PARTNER\"><Customer segment=\"VIP\"><Name>Diana</Name><Contact><Email>diana@example.com</Email></Contact></Customer><Amount currency=\"EUR\">430.00</Amount><Items><Item sku=\"SKU-0042\"><Name>Monitor</Name><Price>380.00</Price><Quantity>1</Quantity></Item><Item sku=\"SKU-0043\"><Name>HDMI Cable</Name><Price>50.00</Price><Quantity>2</Quantity></Item></Items><Shipping><Carrier>DHL</Carrier><Address><City>Lyon</City><Zip>69001</Zip></Address></Shipping></Order>"

# ---------------------------------------------------------------------------
# --- Scenario 5: Reference Data (demo.customers, log-compacted) ------------
# ---------------------------------------------------------------------------
# Keyed by customer_id on a compacted topic: C-002 is produced twice, so the
# topic shows what compaction keeps (latest value per key) once it runs.
buffer "demo.customers" "source-system:CRM-v4.0,event-type:customer.upserted,produced-at:${TS}000" "C-001" \
  "{\"customer_id\":\"C-001\",\"name\":\"Alice\",\"segment\":\"VIP\",\"country\":\"FR\"}"
buffer "demo.customers" "source-system:CRM-v4.0,event-type:customer.upserted,produced-at:${TS}000" "C-002" \
  "{\"customer_id\":\"C-002\",\"name\":\"Bob\",\"segment\":\"REGULAR\",\"country\":\"UK\"}"
buffer "demo.customers" "source-system:CRM-v4.0,event-type:customer.upserted,produced-at:${TS}000" "C-003" \
  "{\"customer_id\":\"C-003\",\"name\":\"Charlie\",\"segment\":\"REGULAR\",\"country\":\"DE\"}"
buffer "demo.customers" "source-system:CRM-v4.0,event-type:customer.upserted,produced-at:${TS}000" "C-004" \
  "{\"customer_id\":\"C-004\",\"name\":\"Diana\",\"segment\":\"PREMIUM\",\"country\":\"ES\"}"
buffer "demo.customers" "source-system:CRM-v4.0,event-type:customer.upserted,produced-at:${TS}000" "C-005" \
  "{\"customer_id\":\"C-005\",\"name\":\"Ethan\",\"segment\":\"NEW\",\"country\":\"NL\"}"
# Upgrade: same key, newer value.
buffer "demo.customers" "source-system:CRM-v4.0,event-type:customer.upgraded,produced-at:${TS}000" "C-002" \
  "{\"customer_id\":\"C-002\",\"name\":\"Bob\",\"segment\":\"PREMIUM\",\"country\":\"UK\"}"

# ---------------------------------------------------------------------------
# --- Scenario 6: Complex/Nested JSON (demo.orders.complex) -----------------
# ---------------------------------------------------------------------------
buffer "demo.orders.complex" "correlation-id:ORD-NEST-01,source-system:OMS-v3.2,event-type:order.received,produced-at:${TS}000" "ORD-NEST-01" \
  "{\"id\":\"ORD-NEST-01\",\"header\":{\"timestamp\":$TS,\"source\":\"WEB\"},\"payload\":{\"items\":[{\"sku\":\"SKU-1\",\"qty\":2},{\"sku\":\"SKU-2\",\"qty\":1}],\"shipping\":{\"address\":{\"city\":\"Paris\",\"zip\":\"75001\"}}}}"

# ---------------------------------------------------------------------------
# --- Scenario 7: Malformed / non-text payloads (demo.errors.poison) --------
# ---------------------------------------------------------------------------
# Four kinds of "unreadable", because the app reports them differently: a
# truncated JSON is a parse error, plain prose is a format mismatch, an empty
# payload is neither, and invalid UTF-8 is flagged as binary — no text search
# can ever match it, and the search panel says so rather than returning 0 hits.
buffer_raw "demo.errors.poison" "{\"id\":\"ERR-01\", \"status\": \"CORRUPT\" ... missing quote"
buffer_raw "demo.errors.poison" "I am not a JSON at all"
buffer_raw "demo.errors.poison" "{\"id\":\"ERR-03\",\"nested\":{\"unclosed\":true"
buffer_raw "demo.errors.poison" "<Order><Customer>Unclosed XML</Customer>"
buffer_raw "demo.errors.poison" "$(printf '\xff\xfe\xc3\x28 binary-ish payload \xc0\xaf')"
buffer_raw "demo.errors.poison" ""

# Two truncated records inside an otherwise healthy pipeline topic. A poison
# check that only ever runs against a topic named "poison" proves nothing; this
# is what makes the cluster audit report one CRITICAL topic in a green flow.
buffer_raw "demo.orders.3.enriched" "{\"id\":\"ORD-666\",\"state\":\"ENRICHED\",\"metadata\":{\"warehouse\":"
buffer_raw "demo.orders.3.enriched" "{\"id\":\"ORD-667\",\"state\":"

# ---------------------------------------------------------------------------
# --- Scenario 8: Deep Nested JSON — 3 levels (demo.orders.nested) ----------
# ---------------------------------------------------------------------------
# Structure:
#   Level 1 — id, timestamp, event_time, status, channel
#   Level 2 — customer{}, order{}, logistics{}, audit{}
#   Level 3 — customer.profile.segment, customer.address.billing.city,
#              order.payment.details.provider, order.items[].pricing.discount.rate,
#              logistics.carrier.tracking.last_event.location,
#              audit.source.system.version
# event_time is spread over the last 2 hours (6 minutes apart) so a TUMBLE /
# HOP window over it yields several buckets. With every record stamped "now",
# the whole window feature collapsed into a single row.
# ---------------------------------------------------------------------------
echo "Generating 3-level nested JSON messages (demo.orders.nested)..."

CHANNELS=("WEB" "MOBILE" "POS" "API" "PARTNER")
SEGMENTS=("VIP" "PREMIUM" "REGULAR" "NEW")
CARRIERS=("DHL" "FEDEX" "UPS" "COLISSIMO" "TNT")
CITIES=("Paris" "Lyon" "Marseille" "Berlin" "Madrid" "London" "Amsterdam" "Rome")
ZIPS=("75001" "69001" "13001" "10115" "28001" "EC1A" "1012" "00184")
SYSTEMS=("OMS-v3.2" "ERP-v2.1" "CRM-v4.0" "B2B-v1.5" "POS-v6.0")
STATUSES=("RECEIVED" "PROCESSING" "CONFIRMED" "PENDING_PAYMENT" "SHIPPED")
CATEGORIES=("ELECTRONICS" "FURNITURE" "CLOTHING" "FOOD" "SPORTS" "HOME")
PROMO_CODES=("SAVE10" "FLASH20" "VIP15" "WELCOME5" "NONE")

for i in $(seq 1 20); do
  ORDER_ID="ORD-DEEP-$(printf "%03d" $i)"
  CUSTOMER_ID="C-$(printf "%03d" $((i % 5 + 1)))"
  CUSTOMER_NAME_ARR=("Alice" "Bob" "Charlie" "Diana" "Ethan")
  CUSTOMER_NAME="${CUSTOMER_NAME_ARR[$((i % 5))]}"
  CHANNEL="${CHANNELS[$((i % 5))]}"
  SEGMENT="${SEGMENTS[$((i % 4))]}"
  CARRIER="${CARRIERS[$((i % 5))]}"
  CITY="${CITIES[$((i % 8))]}"
  ZIP="${ZIPS[$((i % 8))]}"
  SYSTEM="${SYSTEMS[$((i % 5))]}"
  STATUS="${STATUSES[$((i % 5))]}"
  CATEGORY="${CATEGORIES[$((i % 6))]}"
  PROMO="${PROMO_CODES[$((i % 5))]}"
  AMOUNT=$(money $(( i * 4700 + 1250 )))
  UNIT_PRICE_CENTS=$(( i * 2300 + 999 ))
  UNIT_PRICE=$(money $UNIT_PRICE_CENTS)
  HALF_PRICE=$(money $(( UNIT_PRICE_CENTS / 2 )))
  DISCOUNT_RATE=$(printf '0.%02d' $(( (i % 4) * 5 )))
  SCORE=$((88 + (i % 12)))
  TRACKING="TRK-$CARRIER-$(printf "%08d" $((i * 13579)))"
  TXN_ID="TXN-$(printf "%010d" $((TS + i)))"
  PHONE="+336$(printf "%08d" $((i * 12345678 % 100000000)))"
  EMAIL="$(printf '%s' "$CUSTOMER_NAME" | tr '[:upper:]' '[:lower:]')$(printf "%02d" $i)@example.com"
  STREET="$((i * 3)) Rue de la République"
  SKU_A="SKU-$(printf "%04d" $((i * 7 % 9999)))"
  SKU_B="SKU-$(printf "%04d" $((i * 13 % 9999)))"
  # Oldest first: message 1 is ~2h old, message 20 is ~6 min old.
  EVENT_TIME=$(( TS - (21 - i) * 360 ))

  MSG=$(cat <<EOF
{
  "id": "$ORDER_ID",
  "timestamp": "$DATE_NOW",
  "event_time": $EVENT_TIME,
  "status": "$STATUS",
  "channel": "$CHANNEL",
  "customer": {
    "id": "$CUSTOMER_ID",
    "profile": {
      "name": "$CUSTOMER_NAME",
      "segment": "$SEGMENT",
      "contact": {
        "email": "$EMAIL",
        "phone": "$PHONE"
      }
    },
    "address": {
      "billing": {
        "street": "$STREET",
        "city": "$CITY",
        "zip": "$ZIP",
        "country": "FR"
      },
      "shipping": {
        "street": "$((i+1)) Avenue des Champs",
        "city": "$CITY",
        "zip": "$ZIP",
        "country": "FR"
      }
    }
  },
  "order": {
    "category": "$CATEGORY",
    "items": [
      {
        "sku": "$SKU_A",
        "description": "Product A - $CATEGORY",
        "quantity": $((i % 5 + 1)),
        "pricing": {
          "unit_price": $UNIT_PRICE,
          "discount": {
            "type": "PROMO",
            "code": "$PROMO",
            "rate": $DISCOUNT_RATE
          }
        }
      },
      {
        "sku": "$SKU_B",
        "description": "Product B - $CATEGORY",
        "quantity": 1,
        "pricing": {
          "unit_price": $HALF_PRICE,
          "discount": {
            "type": "NONE",
            "code": null,
            "rate": 0.0
          }
        }
      }
    ],
    "payment": {
      "method": "CARD",
      "amount": $AMOUNT,
      "details": {
        "provider": "STRIPE",
        "transaction": {
          "id": "$TXN_ID",
          "status": "CONFIRMED",
          "captured_at": "$DATE_NOW"
        }
      }
    }
  },
  "logistics": {
    "priority": "$( [ $((i % 4)) -eq 0 ] && echo "EXPRESS" || echo "STANDARD" )",
    "carrier": {
      "name": "$CARRIER",
      "service": "STANDARD",
      "tracking": {
        "number": "$TRACKING",
        "last_event": {
          "code": "IN_TRANSIT",
          "location": "$CITY Hub",
          "timestamp": "$DATE_NOW"
        }
      }
    }
  },
  "audit": {
    "created_by": "system",
    "quality_score": $SCORE,
    "source": {
      "system": "$SYSTEM",
      "version": {
        "major": $((i % 3 + 1)),
        "minor": $((i % 10)),
        "patch": $((i % 5))
      }
    }
  }
}
EOF
)
  # Compact to single line for kafka-console-producer
  COMPACT_MSG=$(echo "$MSG" | tr -d '\n' | sed 's/  */ /g')
  buffer "demo.orders.nested" \
    "correlation-id:$ORDER_ID,traceparent:$(traceparent $((900 + i)) $i),source-system:$SYSTEM,event-type:order.deep,produced-at:${EVENT_TIME}000" \
    "$ORDER_ID" "$COMPACT_MSG"
done

echo "Generated 20 messages with 3-level nested JSON in demo.orders.nested"

# ---------------------------------------------------------------------------
# --- Scenario 9: Header-only correlation (payments & shipments) ------------
# ---------------------------------------------------------------------------
# These payloads share NO field with the order: they carry their own reference
# (PAY-… / SHP-…). The order id lives only in the correlation-id header, so
# tracing ORD-101 across the cluster finds them if — and only if — header search
# is on. That is the whole point of the "search headers too" switch.
echo "Producing the header-correlated payment & shipment flow..."
PAY_ORDERS=(101 103 104 106)
PAY_AMOUNTS=(59999 120000 45000 180)
for idx in "${!PAY_ORDERS[@]}"; do
  N=${PAY_ORDERS[$idx]}
  OID="ORD-$N"
  AMT=$(money "${PAY_AMOUNTS[$idx]}")
  HDR="correlation-id:$OID,traceparent:$(traceparent "$N" $((10 + idx))),source-system:PSP-v1.2,produced-at:${TS}000"
  buffer "demo.payments.authorized" "$HDR,event-type:payment.authorized" "PAY-$N" \
    "{\"paymentRef\":\"PAY-$N\",\"provider\":\"STRIPE\",\"amount\":$AMT,\"currency\":\"EUR\",\"state\":\"AUTHORIZED\",\"event_time\":$TS}"
  buffer "demo.payments.captured" "$HDR,event-type:payment.captured" "PAY-$N" \
    "{\"paymentRef\":\"PAY-$N\",\"provider\":\"STRIPE\",\"amount\":$AMT,\"currency\":\"EUR\",\"state\":\"CAPTURED\",\"event_time\":$((TS + 2))}"
done
flush_keyed "demo.payments.authorized"
flush_keyed "demo.payments.captured"
[ "$DEMO_HOP_DELAY" -gt 0 ] && sleep "$DEMO_HOP_DELAY"

SHIP_ORDERS=(101 103)
for idx in "${!SHIP_ORDERS[@]}"; do
  N=${SHIP_ORDERS[$idx]}
  OID="ORD-$N"
  HDR="correlation-id:$OID,traceparent:$(traceparent "$N" $((20 + idx))),source-system:WMS-v5.0,produced-at:${TS}000"
  buffer "demo.shipments.dispatched" "$HDR,event-type:shipment.dispatched" "SHP-$N" \
    "{\"shipmentRef\":\"SHP-$N\",\"carrier\":\"DHL\",\"state\":\"DISPATCHED\",\"hub\":\"CDG\",\"event_time\":$((TS + 4))}"
  buffer "demo.shipments.delivered" "$HDR,event-type:shipment.delivered" "SHP-$N" \
    "{\"shipmentRef\":\"SHP-$N\",\"carrier\":\"DHL\",\"state\":\"DELIVERED\",\"signed_by\":\"SIGNATURE\",\"event_time\":$((TS + 6))}"
done

# ---------------------------------------------------------------------------
# --- Scenario 10: Time series (demo.iot.sensors) ---------------------------
# ---------------------------------------------------------------------------
# 144 readings, one per minute over the last 2h24, round-robin across 8 sensors
# (so each sensor reports every 8 minutes). A windowed AVG/MAX therefore has
# several buckets to fill and a Prometheus GAUGE / SUMMARY metric has a moving
# value. Four readings are out of range and flagged ALERT — something for an
# anomaly hunt to actually find.
echo "Generating IoT time series (demo.iot.sensors)..."
SITES=("PARIS-DC1" "LYON-DC2" "BERLIN-DC3" "MADRID-DC4")
for i in $(seq 0 143); do
  S=$(( i % 8 + 1 ))
  SENSOR="sensor-$(printf '%02d' $S)"
  SITE="${SITES[$(( S % 4 ))]}"
  EVENT_TIME=$(( TS - (144 - i) * 60 ))
  # 18.0 -> 26.9 °C, with a spike every 37th reading.
  TEMP_C=$(( 1800 + (i * 137) % 900 ))
  STATUS="OK"
  if [ $(( i % 37 )) -eq 0 ]; then
    TEMP_C=$(( 4100 + (i % 5) * 30 ))
    STATUS="ALERT"
  fi
  TEMP=$(money $TEMP_C)
  HUMIDITY=$(( 35 + (i * 7) % 45 ))
  buffer "demo.iot.sensors" \
    "correlation-id:$SENSOR,source-system:IOT-GW-v2.0,event-type:sensor.reading,produced-at:${EVENT_TIME}000" \
    "$SENSOR" \
    "{\"sensor_id\":\"$SENSOR\",\"site\":\"$SITE\",\"event_time\":$EVENT_TIME,\"temperature\":$TEMP,\"humidity\":$HUMIDITY,\"status\":\"$STATUS\"}"
done

# ---------------------------------------------------------------------------
# --- Scenario 11: Supply Chain 2.0 (10 orders, 20 steps, 200 messages) -----
# ---------------------------------------------------------------------------
echo "Generating Supply Chain 2.0 messages..."
for i in $(seq 0 9); do
  ORDER_ID="SC-10$i"
  CUSTOMER_ID="C-00$(( (i % 3) + 1 ))"

  for j in "${!SC_STEPS[@]}"; do
    STEP_NAME="${SC_STEPS[$j]}"
    STEP_NUM=$((j + 1))
    # Each step is stamped 90 s after the previous one, so the payload timeline
    # of an order is a real progression rather than 20 identical instants.
    STEP_TIME=$(( TS - (20 - STEP_NUM) * 90 ))
    STEP_ISO=$DATE_NOW

    # Base JSON
    MSG="{\"order_id\":\"$ORDER_ID\",\"step\":\"$STEP_NAME\",\"step_number\":$STEP_NUM,\"timestamp\":\"$STEP_ISO\",\"event_time\":$STEP_TIME,\"status\":\"COMPLETED\""

    # Step 1+: Initial details (level 2: initial_details.customer, level 3: initial_details.customer.contact)
    if [ $STEP_NUM -ge 1 ]; then
      MSG="$MSG,\"initial_details\":{\"customer_id\":\"$CUSTOMER_ID\",\"origin\":\"E-COMMERCE-WEB\",\"customer\":{\"segment\":\"VIP\",\"contact\":{\"channel\":\"EMAIL\",\"preference\":\"MORNING\"}}}"
    fi

    # Step 3+: Payment info (level 2: payment.details, level 3: payment.details.card)
    if [ $STEP_NUM -ge 3 ]; then
      MSG="$MSG,\"payment\":{\"provider\":\"STRIPE\",\"transaction_id\":\"TXN-$TS-$i\",\"amount\":$(( (i + 1) * 42 )).99,\"details\":{\"method\":\"CARD\",\"card\":{\"brand\":\"VISA\",\"last4\":\"$(printf "%04d" $((i * 1111 % 10000)))\",\"exp\":\"12/27\"}}}"
    fi

    # Step 6+: Fulfillment info (level 2: fulfillment.location, level 3: fulfillment.location.coordinates)
    if [ $STEP_NUM -ge 6 ]; then
      MSG="$MSG,\"fulfillment\":{\"warehouse\":\"WH-MAIN\",\"aisle\":\"A-$i\",\"bin\":\"B-$(printf "%03d" $j)\",\"location\":{\"zone\":\"ZONE-$((i % 4 + 1))\",\"coordinates\":{\"x\":$((i * 12 + 5)),\"y\":$((j * 8 + 3)),\"level\":$((i % 3 + 1))}}}"
    fi

    # Step 11+: Quality control (level 2: quality_control.result, level 3: quality_control.result.breakdown)
    if [ $STEP_NUM -ge 11 ]; then
      MSG="$MSG,\"quality_control\":{\"inspector\":\"QA-$(($i + 1))\",\"score\":$((90 + (i % 10))),\"checks\":[\"VISUAL\",\"WEIGHT\",\"DIMENSIONS\"],\"result\":{\"passed\":true,\"breakdown\":{\"visual\":$((95 + i % 5)),\"weight\":$((92 + i % 7)),\"dimensions\":$((98 + i % 2))}}}"
    fi

    # Step 13+: Logistics (level 2: logistics.carrier, level 3: logistics.carrier.tracking)
    if [ $STEP_NUM -ge 13 ]; then
      MSG="$MSG,\"logistics\":{\"priority\":\"NORMAL\",\"carrier\":{\"name\":\"FAST-SHIP\",\"tracking\":{\"number\":\"FS-$ORDER_ID-$TS\",\"last_scan\":{\"location\":\"CDG Hub\",\"timestamp\":\"$STEP_ISO\"}}},\"sla\":{\"promised\":\"$DATE_NOW\",\"met\":true}}"
    fi

    # Step 16+: International (level 2: international.customs, level 3: international.customs.declaration)
    if [ $STEP_NUM -ge 16 ]; then
      MSG="$MSG,\"international\":{\"customs_code\":\"HS-84713000\",\"destination\":\"REGION-$((i % 5))\",\"customs\":{\"status\":\"CLEARED\",\"declaration\":{\"ref\":\"DECL-$TS-$i\",\"officer\":\"OFF-$((i % 10 + 1))\",\"cleared_at\":\"$DATE_NOW\"}}}"
    fi

    # Step 19+: Last mile (level 2: last_mile.delivery, level 3: last_mile.delivery.geo)
    if [ $STEP_NUM -ge 19 ]; then
      MSG="$MSG,\"last_mile\":{\"driver\":\"DRV-$(($i * 7 % 20))\",\"vehicle\":\"VAN-$i\",\"delivery\":{\"attempt\":1,\"confirmed_by\":\"SIGNATURE\",\"geo\":{\"lat\":48.$((85+i)),\"lon\":2.$((35+i)),\"accuracy_m\":5}}}"
    fi

    MSG="$MSG}"

    buffer "demo.sc.$STEP_NAME.out" \
      "correlation-id:$ORDER_ID,traceparent:$(traceparent $((1000 + i)) "$STEP_NUM"),source-system:SCM-v2.4,event-type:sc.$STEP_NAME,produced-at:${STEP_TIME}000" \
      "$ORDER_ID" "$MSG"
  done
done

# ---------------------------------------------------------------------------
# Flush everything still buffered — one producer per topic, DEMO_PARALLEL at a time.
# ---------------------------------------------------------------------------
echo "Publishing buffered records..."
flush_all_keyed
for f in "$BUFDIR"/raw/*; do
  [ -e "$f" ] || continue
  throttle flush_raw "$(basename "$f")"
done
wait

# ---------------------------------------------------------------------------
# --- Scenario 12: Consumer groups — one draining, one abandoned ------------
# ---------------------------------------------------------------------------
#
# The seeder produced records and nothing ever read them, so a fresh demo cluster
# had **no consumer group at all**. Four features then had nothing to show: the
# Consumers panel of the Topic Explorer, the audit's consumer checks, the
# CONSUMER_TIME_LAG KPI the Metrics page proposes, and the Prometheus lag gauges.
# The same reason duplicates and poison records are seeded here: a feature with no
# data is a feature nobody discovers.
#
# Two groups, because one number means nothing without a contrast:
#
#   demo.orders.processor  reads everything → CAUGHT_UP, health green.
#   demo.orders.reporting  reads the first records, then stops for good → records
#                          waiting, no member left. That is exactly the STALLED
#                          shape (`ConsumerGroupLag.health()`), which the audit
#                          reports CRITICAL and which the delay-in-time KPI is
#                          proposed for.
#
# A console consumer commits on exit, so consuming *N* messages and leaving is all
# it takes — no offset reset, which `kafka-consumer-groups.sh` refuses on a group
# that never existed (state DEAD).
#
# The delay in *time* starts at a few seconds and grows on its own: record
# timestamps are set at produce time and nothing reads that group again, which is
# precisely the "a stopped producer leaves a backlog that keeps ageing" case the
# metric exists to show. Leave the stack running an hour and the KPI reads an hour.
seed_consumer_groups() {
  local topic="demo.orders.1.received"

  # Drains the topic: exits on the timeout once there is nothing left to read.
  $KAFKA_CONSUMER_CMD --bootstrap-server "$BOOTSTRAP_SERVER" \
    --topic "$topic" --group "demo.orders.processor" \
    --from-beginning --timeout-ms 8000 >/dev/null 2>&1 || true

  # Reads a handful and never comes back. --max-messages makes it exit cleanly;
  # the timeout is the safety net for a topic that holds fewer records than asked.
  $KAFKA_CONSUMER_CMD --bootstrap-server "$BOOTSTRAP_SERVER" \
    --topic "$topic" --group "demo.orders.reporting" \
    --from-beginning --max-messages 4 --timeout-ms 8000 >/dev/null 2>&1 || true
}

echo "Seeding consumer groups (one caught up, one abandoned)..."
seed_consumer_groups

echo "--- Demo Setup Complete ---"
echo ""
echo "What was seeded (76 topics):"
echo "  • demo.orders.1..6.*   6-step order pipeline, 3 partitions, keyed by order id,"
echo "                         with correlation-id / traceparent / source-system headers."
echo "                         ORD-101 walks all 6 steps with a real pause between hops"
echo "                         (3 -> 4 is the slow one); ORD-102 is rejected at step 2."
echo "  • demo.orders.nested   20 deep JSON docs, 6 partitions, event_time spread over 2h."
echo "  • demo.iot.sensors     144 readings from 8 sensors, one per minute, a few ALERTs."
echo "  • demo.payments.* /    correlated to the orders by HEADER only — their payloads"
echo "    demo.shipments.*     carry PAY-/SHP- references, never the order id."
echo "  • demo.customers       log-compacted, keyed by customer_id (C-002 has 2 versions)."
echo "  • demo.errors.poison   truncated JSON, prose, unclosed XML, invalid UTF-8, empty."
echo "  • demo.orders.3.enriched also carries 2 truncated records — the cluster audit"
echo "                         should report it CRITICAL inside an otherwise green flow."
echo "  • demo.sc.*            20-step supply chain, 10 orders, 60 topics."
echo "  • 2 consumer groups on demo.orders.1.received: 'demo.orders.processor' is"
echo "                         caught up, 'demo.orders.reporting' stopped after 4 records"
echo "                         and left — records waiting, no member: the STALLED case."
echo ""
echo "Suggestions for exploration:"
echo "1. Trace by key: Stream Flow → key 'ORD-101', exact record key ON."
echo "   → then turn 'search headers too' ON: demo.payments.* and demo.shipments.*"
echo "     join the chain, because only their headers carry the order id."
echo "2. Header search: Topic Explorer → demo.orders.nested → mode Field, path"
echo "   'header:correlation-id', value 'ORD-DEEP-007'."
echo "3. Key partitioning: search KEY = 'ORD-DEEP-007' on demo.orders.nested (6 partitions)"
echo "   with 'only this key's partition' ON — the status strip shows the scan narrowed."
echo "4. Real time windows (event_time is spread, so this returns several buckets):"
echo "   SELECT COUNT(*) AS metric_value, AVG(temperature) AS avg_temp"
echo "   FROM TABLE(TUMBLE(TABLE \"demo.iot.sensors\", DESCRIPTOR(event_time), INTERVAL '5' MINUTE))"
echo "5. Nested paths: SELECT id FROM \"demo.orders.nested\" WHERE customer.profile.segment = 'VIP'"
echo "6. XML / XPath: SELECT XmlExtract(raw_value, '/Order/Customer') FROM \"demo.orders.xml\""
echo "   → demo.orders.xml also holds a nested document (ORD-XML-04) for XPath predicates."
echo "7. Join: SELECT c.name, o.amount FROM \"demo.orders.1.received\" o JOIN \"demo.customers\" c ON o.customer_id = c.customer_id"
echo "8. Cluster Audit: run it — duplicates on demo.orders.1.received (ORD-103 / ORD-105"
echo "   redelivered), poison on demo.orders.3.enriched, drop-off on the orders flow."
echo "9. Metrics: SELECT AVG(temperature) AS metric_value FROM \"demo.iot.sensors\" (GAUGE)"
echo "9b. Consumer lag: Topic Explorer → demo.orders.1.received → Consumers tab."
echo "    'demo.orders.reporting' is behind with no member — run the Cluster Audit and"
echo "    the Metrics page then proposes a CONSUMER_TIME_LAG KPI for it, which reads"
echo "    the backlog in time. It grows on its own while the stack stays up."
echo "10. Supply Chain deep nesting: inspect 'SC-100' in demo.sc.*.out — 3-level nesting"
echo "    from step 6 onwards, each step stamped 90s after the previous one."
