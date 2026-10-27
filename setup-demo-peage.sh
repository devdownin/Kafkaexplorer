#!/bin/bash
# SPDX-License-Identifier: AGPL-3.0-or-later
# Copyright (C) 2026 Kafka Explorer Contributors
#
# Seeds the toll-road (péage) topics behind the ksqlDB → Flink SQL migration examples.
#
#   ./setup-demo-peage.sh [bootstrap-server]
#
# The examples in docs/QUERY-EXAMPLES.md ("ksqlDB → Flink migration") are the queries of the
# ksql2flink laboratory, rewritten for the embedded engine: no UDF, no SET, no CREATE VIEW, no
# INSERT. They read the topics below, which carry the production names and the payload shapes
# the ksqlDB stream declarations give them — upper-case keys, nested structures — so a query
# copied from the migration reads here as it reads there.
#
# Why this is a separate script: setup-demo.sh seeds a fixed set the documentation, the audit
# scenarios and the MCP specification all count. These topics are opt-in and never change it.
#
# Same constraints as setup-demo.sh: one producer call per topic, no `bc`, no `date -d`. Money is
# integer cents formatted with printf; DATE_OPERATION is epoch milliseconds, the type the ksqlDB
# stream declares (DOUBLE) holding the same instant.

BOOTSTRAP_SERVER=${1:-"localhost:9092"}

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

echo "--- Péage demo topics (ksqlDB → Flink migration examples) ---"
echo "Bootstrap Server: $BOOTSTRAP_SERVER"

BUFDIR=$(mktemp -d 2>/dev/null || echo "/tmp/kse-peage-$$")
mkdir -p "$BUFDIR"
trap 'rm -rf "$BUFDIR"' EXIT

TS=$(date +%s)

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

buffer() {
  # buffer <topic> <key> <value>
  printf '%s\t%s\n' "$2" "$3" >> "$BUFDIR/$1"
}

flush() {
  local topic=$1
  [ -s "$BUFDIR/$topic" ] || return 0
  $KAFKA_PRODUCER_CMD --bootstrap-server "$BOOTSTRAP_SERVER" --topic "$topic" \
    --property parse.key=true < "$BUFDIR/$topic" >/dev/null 2>&1 || echo "  ! could not produce to $topic"
}

money() {
  printf '%d.%02d' $(( $1 / 100 )) $(( $1 % 100 ))
}

PASSAGES_ASF="asf.peage.voie.passagesortieva"
PASSAGES_ESC="esc.peage.voie.passagesortieva"
REF_COMPTEUR="va.peage.backoffice.socleET.ref_compteur"
ETANCHEITE="va.peage.backoffice.socleET.etancheite"
FLOWS="VA_PEAGE_CLASSIFICATION_FLOWS_EXTEND"

echo "Creating topics..."
create_topic "$PASSAGES_ASF" 3
create_topic "$PASSAGES_ESC" 3
create_topic "$REF_COMPTEUR" 3
create_topic "$ETANCHEITE" 3
create_topic "$FLOWS" 3

# ---------------------------------------------------------------------------
# Lot 1 — exit passages. Payment types in several cases (the original lower-cases them),
# cash ("especes") excluded by the filter, and one passage whose SECOND payment matches:
# only the first one counts, RETENUS[1] in ksqlDB being RETENUS[0] in a JSON path.
# ---------------------------------------------------------------------------
echo "Producing exit passages (lot 1)..."
TYPES=("TelePeageLibert" "telepeagecardme" "CARTEACCREDITIVE" "cartemagnetique" "especes")
GARES=("25004123" "25006123" "25021212" "25005601")
passage() {
  # passage <topic> <company> <index> <payments-json>
  local topic=$1 company=$2 i=$3 payments=$4
  local id gare ttc ht
  id="$company-$(printf '%05d' "$i")"
  gare=${GARES[$(( i % 4 ))]}
  ttc=$(( 250 + (i * 173) % 2400 ))
  ht=$(( ttc * 100 / 120 ))
  buffer "$topic" "$id" "{\"IDPASSAGE\":\"$id\",\"MESSAGE\":{\"NUMERO\":$i,\"TYPE\":\"PASSAGESORTIE\",\"DHM\":\"2026-10-25T08:$(printf '%02d' $(( i % 60 ))):00Z\",\"TYPEEMISSION\":\"TR\",\"PRODUCTEUR\":{\"TYPE\":\"VOIE\",\"ID\":\"$gare\"}},\"PASSAGESORTIE\":{\"SYSTEME\":\"$company\",\"DHM\":\"2026-10-25T08:$(printf '%02d' $(( i % 60 ))):00Z\",\"STATUT\":\"VALIDE\",\"LOCALISATION\":{\"GARE\":\"$gare\",\"EQUIPEMENT\":{\"NUMERO\":\"$(( i % 6 + 1 ))\",\"TYPE\":\"VS\"}},\"TARIFICATION\":{\"CLASSETARIFAIRE\":{\"CLASSE\":\"$(( i % 4 + 1 ))\",\"ACQUISITION\":\"AUTO\"},\"PRIX\":{\"MONTANTHT\":$(money $ht),\"MONTANTTVA\":$(money $(( ttc - ht ))),\"MONTANTTTC\":$(money $ttc),\"TAUXTVA\":20.00}},\"PAIEMENT\":{\"RETENUS\":$payments}}}"
}
for i in $(seq 1 15); do
  TYPE=${TYPES[$(( i % 5 ))]}
  passage "$PASSAGES_ASF" "ASF" "$i" "[{\"TYPE\":\"$TYPE\",\"MONTANT\":$(money $(( 250 + (i * 173) % 2400 ))),\"DEVISE\":\"EUR\",\"PAN\":\"PAN-ASF-$i\"}]"
done
passage "$PASSAGES_ASF" "ASF" 16 "[{\"TYPE\":\"especes\",\"MONTANT\":4.20,\"DEVISE\":\"EUR\",\"PAN\":\"\"},{\"TYPE\":\"telepeagelibert\",\"MONTANT\":0.00,\"DEVISE\":\"EUR\",\"PAN\":\"PAN-ASF-16\"}]"
for i in $(seq 1 8); do
  TYPE=${TYPES[$(( (i + 2) % 5 ))]}
  passage "$PASSAGES_ESC" "ESC" "$i" "[{\"TYPE\":\"$TYPE\",\"MONTANT\":$(money $(( 250 + (i * 173) % 2400 ))),\"DEVISE\":\"EUR\",\"PAN\":\"PAN-ESC-$i\"}]"
done

# ---------------------------------------------------------------------------
# Lot 6 — socle ET. A counter referential keyed by ID (ID 3 is updated, ID 5 is incomplete
# and filtered out) and the transactions joined to it. R_COMPTEUR_TYPEID 1 counts, 2 sums;
# R_MODE_REGLEMENTID 1 means "every payment mode".
# ---------------------------------------------------------------------------
echo "Producing the counter referential and transactions (lot 6)..."
buffer "$REF_COMPTEUR" "1" '{"ID":1,"R_COMPTEUR_TYPEID":1,"R_FREQUENCEID":1,"R_OPERATIONID":10,"R_MODE_REGLEMENTID":1,"R_TRANSACTION_ETATID":1}'
buffer "$REF_COMPTEUR" "2" '{"ID":2,"R_COMPTEUR_TYPEID":2,"R_FREQUENCEID":1,"R_OPERATIONID":10,"R_MODE_REGLEMENTID":256,"R_TRANSACTION_ETATID":1}'
buffer "$REF_COMPTEUR" "3" '{"ID":3,"R_COMPTEUR_TYPEID":1,"R_FREQUENCEID":2,"R_OPERATIONID":20,"R_MODE_REGLEMENTID":1,"R_TRANSACTION_ETATID":2}'
buffer "$REF_COMPTEUR" "4" '{"ID":4,"R_COMPTEUR_TYPEID":2,"R_FREQUENCEID":2,"R_OPERATIONID":20,"R_MODE_REGLEMENTID":512,"R_TRANSACTION_ETATID":1}'
buffer "$REF_COMPTEUR" "5" '{"ID":5,"R_COMPTEUR_TYPEID":2,"R_FREQUENCEID":null,"R_OPERATIONID":20,"R_MODE_REGLEMENTID":1,"R_TRANSACTION_ETATID":1}'
buffer "$REF_COMPTEUR" "3" '{"ID":3,"R_COMPTEUR_TYPEID":1,"R_FREQUENCEID":2,"R_OPERATIONID":20,"R_MODE_REGLEMENTID":1,"R_TRANSACTION_ETATID":1}'

# 48 transactions, one every five minutes over the last four hours: four hourly buckets.
MODES=(256 512 1024)
SOCIETES=(25004 25006 25021 25005)
for i in $(seq 0 47); do
  COMPTEUR=$(( i % 5 + 1 ))
  OPERATION=10
  [ "$COMPTEUR" -ge 3 ] && OPERATION=20
  [ $(( i % 7 )) -eq 0 ] && OPERATION=30
  ETAT=1
  [ $(( i % 6 )) -eq 0 ] && ETAT=2
  AT=$(( (TS - (48 - i) * 300) * 1000 ))
  ID="TRN-$(printf '%05d' "$i")"
  buffer "$ETANCHEITE" "$ID" "{\"R_COMPTEURID\":$COMPTEUR,\"R_MODE_REGLEMENTID\":${MODES[$(( i % 3 ))]},\"R_OPERATIONID\":$OPERATION,\"R_VOIEID\":$(( i % 3 + 1 )),\"R_GAREID\":${SOCIETES[$(( i % 4 ))]},\"R_SOCIETEID\":${SOCIETES[$(( i % 4 ))]},\"R_PARTENAIREID\":1,\"R_TRANSACTION_ETATID\":$ETAT,\"R_TRANSACTION_MODIFICATION_TYPEID\":0,\"IDT_TRAN_PEA\":\"$ID\",\"MONTANT_TRANSACTION\":$(money $(( 250 + (i * 211) % 3000 ))),\"DATE_OPERATION\":$AT,\"DATE_COMPTABLE\":$AT}"
done

# ---------------------------------------------------------------------------
# Lots 7/8 — classification flows. Lane 25004-12 crosses the alarm threshold (more than 30 %
# manual or FC classes over at least 20 passages), lane 25006-03 stays under it, and lane
# 25021-07 has too few passages to judge. PostDTO and TD flows are excluded by the filter.
# ---------------------------------------------------------------------------
echo "Producing classification flows (lots 7/8)..."
flow() {
  # flow <station> <lane> <index> <origin> <callermode> <extra-class-json>
  local station=$1 lane=$2 i=$3 origin=$4 mode=$5 extra=$6
  local created
  created="2026-10-25T09:$(printf '%02d' $(( i % 60 ))):$(printf '%02d' $(( (i * 7) % 60 ))).000000Z"
  buffer "$FLOWS" "$station-$lane" "{\"FLOW\":{\"STATION\":\"$station\",\"LANE\":\"$lane\",\"CREATIONDATE\":\"$created\",\"CALLERMODE\":\"$mode\",\"CLASS\":[{\"CLASSTYPE\":\"TarifiedClass\",\"CLASSORIGIN\":\"$origin\"},{\"CLASSTYPE\":\"DetectedClass\",\"CLASSORIGIN\":\"Auto\"}$extra]},\"BUSINESSFILTER\":{\"ID\":1},\"BUSINESSRULE\":{\"ID\":$(( i % 3 + 1 ))},\"BUSINESSCYCLOPE\":{\"CLASS\":$(( i % 4 + 1 )),\"CONFIDENCE\":0.$(( 50 + i % 49 )),\"PMR\":\"N\",\"PRESENCE\":\"O\"},\"RESPONSE\":{\"CALCULATEDCLASS\":$(( i % 4 + 1 )),\"RESULTCLASS\":$(( i % 4 + 1 )),\"CLASSORIGIN\":\"$origin\",\"DECISION\":\"ACCEPT\",\"CLASSUSING\":\"TARIFIED\"},\"TECHNICALFILTER\":{\"ID\":1},\"STATIONLANE\":\"$station-$lane\"}"
}
ORIGINS_HIGH=("Manual" "Auto" "FC" "Auto" "Manual")
for i in $(seq 0 24); do flow "25004" "12" "$i" "${ORIGINS_HIGH[$(( i % 5 ))]}" "VA" ""; done
for i in $(seq 0 24); do
  ORIGIN="Auto"
  [ $(( i % 8 )) -eq 0 ] && ORIGIN="Manual"
  flow "25006" "03" "$i" "$ORIGIN" "VA" ""
done
for i in $(seq 0 11); do flow "25021" "07" "$i" "Manual" "VA" ""; done
for i in $(seq 0 3); do flow "25006" "03" "$(( 30 + i ))" "Manual" "TD" ""; done
for i in $(seq 0 3); do flow "25006" "03" "$(( 40 + i ))" "Manual" "VA" ",{\"CLASSTYPE\":\"PostDTO\",\"CLASSORIGIN\":\"Manual\"}"; done

echo "Publishing..."
for topic in "$PASSAGES_ASF" "$PASSAGES_ESC" "$REF_COMPTEUR" "$ETANCHEITE" "$FLOWS"; do
  flush "$topic" &
done
wait

echo "--- Péage demo topics ready ---"
echo "Queries to run: docs/QUERY-EXAMPLES.md, section \"ksqlDB → Flink migration\"."
