#!/bin/bash
# SPDX-License-Identifier: AGPL-3.0-or-later
# Copyright (C) 2026 Kafka Explorer Contributors
#
# Seeds two Avro topics against a Confluent Schema Registry.
#
#   ./setup-demo-avro.sh [bootstrap-server] [schema-registry-url]
#
# Why this is a separate script: `kafka-avro-console-producer` ships with the
# Confluent distribution, not with apache/kafka, and Schema Registry only runs in
# the `compose/schema-registry.yml` stack. The main `setup-demo.sh` must stay
# runnable against a bare Apache broker, so the Avro part lives here and is wired
# only into that stack.
#
# What it demonstrates: `AvroSchemaInferrer` reads the `<topic>-value` subject
# from the registry, and `SchemaInferenceService.detectFormat` classifies a topic
# as AVRO purely on that subject existing. Until now nothing in the demo ever
# registered one, so the whole Avro path — format badge, inferred columns,
# generated DDL — had no dataset to run against.
#
# It never fails the stack: a missing CLI or an unreachable registry is reported
# and the script exits 0.

BOOTSTRAP_SERVER=${1:-"localhost:9092"}
SCHEMA_REGISTRY_URL=${2:-"http://localhost:8081"}

echo "--- Avro demo topics ---"
echo "Bootstrap Server: $BOOTSTRAP_SERVER"
echo "Schema Registry:  $SCHEMA_REGISTRY_URL"

if command -v kafka-avro-console-producer >/dev/null 2>&1; then
  AVRO_PRODUCER="kafka-avro-console-producer"
elif [ -x "/usr/bin/kafka-avro-console-producer" ]; then
  AVRO_PRODUCER="/usr/bin/kafka-avro-console-producer"
else
  echo "! kafka-avro-console-producer not found — skipping the Avro topics."
  echo "  (they only seed from the Confluent image, in compose/schema-registry.yml)"
  exit 0
fi

if command -v kafka-topics >/dev/null 2>&1; then
  KAFKA_TOPICS_CMD="kafka-topics"
else
  KAFKA_TOPICS_CMD="kafka-topics.sh"
fi

# Wait for the registry — the broker can be healthy well before it is.
echo "Waiting for Schema Registry..."
for _ in $(seq 1 60); do
  if curl -sf "$SCHEMA_REGISTRY_URL/subjects" >/dev/null 2>&1; then
    READY=1
    break
  fi
  sleep 2
done
if [ -z "${READY:-}" ]; then
  echo "! Schema Registry unreachable at $SCHEMA_REGISTRY_URL — skipping the Avro topics."
  exit 0
fi

$KAFKA_TOPICS_CMD --bootstrap-server "$BOOTSTRAP_SERVER" --create --topic demo.avro.orders \
  --partitions 3 --replication-factor 1 --if-not-exists >/dev/null 2>&1
$KAFKA_TOPICS_CMD --bootstrap-server "$BOOTSTRAP_SERVER" --create --topic demo.avro.customers \
  --partitions 3 --replication-factor 1 --if-not-exists >/dev/null 2>&1

TS=$(date +%s)

# A nullable union and a nested record on purpose: the inferrer unwraps
# ["null", X] to X, and a RECORD field lands as STRING — both worth seeing in
# the generated DDL.
ORDER_SCHEMA='{
  "type":"record","name":"AvroOrder","namespace":"com.demo",
  "fields":[
    {"name":"order_id","type":"string"},
    {"name":"customer_id","type":"string"},
    {"name":"status","type":{"type":"enum","name":"OrderStatus","symbols":["RECEIVED","CONFIRMED","SHIPPED","CANCELLED"]}},
    {"name":"amount","type":"double"},
    {"name":"currency","type":"string","default":"EUR"},
    {"name":"quantity","type":"int"},
    {"name":"event_time","type":"long"},
    {"name":"promo_code","type":["null","string"],"default":null},
    {"name":"shipping","type":{"type":"record","name":"Shipping","fields":[
      {"name":"carrier","type":"string"},
      {"name":"city","type":"string"},
      {"name":"express","type":"boolean"}
    ]}}
  ]
}'

CUSTOMER_SCHEMA='{
  "type":"record","name":"AvroCustomer","namespace":"com.demo",
  "fields":[
    {"name":"customer_id","type":"string"},
    {"name":"name","type":"string"},
    {"name":"segment","type":"string"},
    {"name":"country","type":"string"},
    {"name":"lifetime_value","type":"double"},
    {"name":"since","type":"long"}
  ]
}'

echo "Producing demo.avro.orders..."
STATUSES=("RECEIVED" "CONFIRMED" "SHIPPED" "CANCELLED")
CARRIERS=("DHL" "FEDEX" "UPS" "COLISSIMO")
CITIES=("Paris" "Lyon" "Berlin" "Madrid")
{
  for i in $(seq 1 24); do
    OID="ORD-AVRO-$(printf '%03d' "$i")"
    CID="C-$(printf '%03d' $((i % 5 + 1)))"
    STATUS="${STATUSES[$((i % 4))]}"
    CARRIER="${CARRIERS[$((i % 4))]}"
    CITY="${CITIES[$((i % 4))]}"
    AMOUNT="$(( i * 37 + 19 )).50"
    # Spread over the last 2 hours, so a window over event_time has buckets.
    EVENT_TIME=$(( (TS - (25 - i) * 300) * 1000 ))
    if [ $((i % 3)) -eq 0 ]; then
      PROMO='{"string":"SAVE10"}'
    else
      PROMO='null'
    fi
    EXPRESS=$( [ $((i % 4)) -eq 0 ] && echo true || echo false )
    printf '"%s"\t{"order_id":"%s","customer_id":"%s","status":"%s","amount":%s,"currency":"EUR","quantity":%d,"event_time":%d,"promo_code":%s,"shipping":{"carrier":"%s","city":"%s","express":%s}}\n' \
      "$OID" "$OID" "$CID" "$STATUS" "$AMOUNT" "$((i % 5 + 1))" "$EVENT_TIME" "$PROMO" "$CARRIER" "$CITY" "$EXPRESS"
  done
} | $AVRO_PRODUCER --broker-list "$BOOTSTRAP_SERVER" --topic demo.avro.orders \
      --property schema.registry.url="$SCHEMA_REGISTRY_URL" \
      --property parse.key=true \
      --property key.schema='"string"' \
      --property value.schema="$ORDER_SCHEMA" \
  || { echo "! failed to produce demo.avro.orders"; exit 0; }

echo "Producing demo.avro.customers..."
{
  printf '"C-001"\t{"customer_id":"C-001","name":"Alice","segment":"VIP","country":"FR","lifetime_value":12400.00,"since":%d}\n' $(( (TS - 86400 * 900) * 1000 ))
  printf '"C-002"\t{"customer_id":"C-002","name":"Bob","segment":"PREMIUM","country":"UK","lifetime_value":3100.50,"since":%d}\n' $(( (TS - 86400 * 400) * 1000 ))
  printf '"C-003"\t{"customer_id":"C-003","name":"Charlie","segment":"REGULAR","country":"DE","lifetime_value":780.00,"since":%d}\n' $(( (TS - 86400 * 120) * 1000 ))
  printf '"C-004"\t{"customer_id":"C-004","name":"Diana","segment":"PREMIUM","country":"ES","lifetime_value":5400.25,"since":%d}\n' $(( (TS - 86400 * 300) * 1000 ))
  printf '"C-005"\t{"customer_id":"C-005","name":"Ethan","segment":"NEW","country":"NL","lifetime_value":95.00,"since":%d}\n' $(( (TS - 86400 * 10) * 1000 ))
} | $AVRO_PRODUCER --broker-list "$BOOTSTRAP_SERVER" --topic demo.avro.customers \
      --property schema.registry.url="$SCHEMA_REGISTRY_URL" \
      --property parse.key=true \
      --property key.schema='"string"' \
      --property value.schema="$CUSTOMER_SCHEMA" \
  || { echo "! failed to produce demo.avro.customers"; exit 0; }

echo "--- Avro demo topics ready ---"
echo "  demo.avro.orders     24 records, subject demo.avro.orders-value"
echo "  demo.avro.customers   5 records, subject demo.avro.customers-value"
echo ""
echo "Open either topic in the Topic Explorer: the format badge reads AVRO and the"
echo "columns come from the registry, not from sampling the payloads."
