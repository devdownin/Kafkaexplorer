#!/bin/bash

# Configuration
BOOTSTRAP_SERVER=${1:-"localhost:9092"}

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

echo "--- Starting Kafka Demo Setup ---"
echo "Bootstrap Server: $BOOTSTRAP_SERVER"

# Topics definition
TOPICS=(
  "demo.orders.1.received"
  "demo.orders.2.validated"
  "demo.orders.3.enriched"
  "demo.orders.4.transformed"
  "demo.orders.5.shipped"
  "demo.orders.6.delivered"
  "demo.orders.xml"
  "demo.orders.complex"
  "demo.orders.nested"
  "demo.customers"
  "demo.errors.poison"
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

# Create standard topics
echo "Creating standard topics..."
for TOPIC in "${TOPICS[@]}"; do
  $KAFKA_TOPICS_CMD --bootstrap-server "$BOOTSTRAP_SERVER" --create --topic "$TOPIC" --partitions 1 --replication-factor 1 --if-not-exists
done

# Create Supply Chain 2.0 topics (60 topics)
echo "Creating Supply Chain 2.0 topics..."
for STEP in "${SC_STEPS[@]}"; do
  for SUFFIX in "in" "out" "audit"; do
    TOPIC="demo.sc.$STEP.$SUFFIX"
    $KAFKA_TOPICS_CMD --bootstrap-server "$BOOTSTRAP_SERVER" --create --topic "$TOPIC" --partitions 1 --replication-factor 1 --if-not-exists
  done
done

echo "Populating topics with demo data..."

# Function to produce a message
produce() {
  local topic=$1
  local message=$2
  echo "$message" | $KAFKA_PRODUCER_CMD --bootstrap-server "$BOOTSTRAP_SERVER" --topic "$topic"
}

DATE_NOW=$(date -u +"%Y-%m-%dT%H:%M:%SZ")
TS=$(date +%s)

# --- Scenario 1: Nominal Flow (Order #101) ---
produce "demo.orders.1.received" "{\"id\":\"ORD-101\",\"state\":\"RECEIVED\",\"description\":\"Smartphone purchase\",\"type\":\"ELECTRONICS\",\"odate\":\"$DATE_NOW\",\"amount\":599.99,\"customer_id\":\"C-001\"}"
produce "demo.orders.2.validated" "{\"id\":\"ORD-101\",\"state\":\"VALIDATED\",\"description\":\"Smartphone purchase\",\"type\":\"ELECTRONICS\",\"odate\":\"$DATE_NOW\",\"amount\":599.99,\"customer_id\":\"C-001\",\"validation_code\":\"VAL-A1\"}"
produce "demo.orders.3.enriched" "{\"id\":\"ORD-101\",\"state\":\"ENRICHED\",\"description\":\"Smartphone purchase\",\"type\":\"ELECTRONICS\",\"odate\":\"$DATE_NOW\",\"amount\":599.99,\"customer_id\":\"C-001\",\"metadata\":{\"warehouse\":\"WH-NORTH\",\"priority\":\"HIGH\"}}"
produce "demo.orders.4.transformed" "{\"id\":\"ORD-101\",\"state\":\"TRANSFORMED\",\"description\":\"Smartphone purchase\",\"type\":\"ELECTRONICS\",\"odate\":\"$DATE_NOW\",\"total_with_tax\":719.99,\"currency\":\"EUR\"}"
produce "demo.orders.5.shipped" "{\"id\":\"ORD-101\",\"state\":\"SHIPPED\",\"description\":\"Smartphone purchase\",\"type\":\"ELECTRONICS\",\"odate\":\"$DATE_NOW\",\"tracking_number\":\"TRK-123456\"}"
produce "demo.orders.6.delivered" "{\"id\":\"ORD-101\",\"state\":\"DELIVERED\",\"description\":\"Smartphone purchase\",\"type\":\"ELECTRONICS\",\"odate\":\"$DATE_NOW\",\"delivery_status\":\"SUCCESS\"}"

# --- Scenario 2: Rejected Order (Order #102) ---
produce "demo.orders.1.received" "{\"id\":\"ORD-102\",\"state\":\"RECEIVED\",\"description\":\"Broken laptop return\",\"type\":\"ELECTRONICS\",\"odate\":\"$DATE_NOW\",\"amount\":0.00,\"customer_id\":\"C-002\"}"
produce "demo.orders.2.validated" "{\"id\":\"ORD-102\",\"state\":\"REJECTED\",\"description\":\"Broken laptop return\",\"type\":\"ELECTRONICS\",\"odate\":\"$DATE_NOW\",\"reason\":\"Warranty expired\"}"

# --- Scenario 3: Aggregation Data ---
produce "demo.orders.1.received" "{\"id\":\"ORD-103\",\"state\":\"RECEIVED\",\"description\":\"Sofa\",\"type\":\"FURNITURE\",\"odate\":\"$DATE_NOW\",\"amount\":1200.00,\"customer_id\":\"C-003\"}"
produce "demo.orders.1.received" "{\"id\":\"ORD-104\",\"state\":\"RECEIVED\",\"description\":\"Table\",\"type\":\"FURNITURE\",\"odate\":\"$DATE_NOW\",\"amount\":450.00,\"customer_id\":\"C-003\"}"
produce "demo.orders.1.received" "{\"id\":\"ORD-105\",\"state\":\"RECEIVED\",\"description\":\"Apple\",\"type\":\"FOOD\",\"odate\":\"$DATE_NOW\",\"amount\":2.50,\"customer_id\":\"C-001\"}"
produce "demo.orders.1.received" "{\"id\":\"ORD-106\",\"state\":\"RECEIVED\",\"description\":\"Banana\",\"type\":\"FOOD\",\"odate\":\"$DATE_NOW\",\"amount\":1.80,\"customer_id\":\"C-001\"}"

# --- Scenario 4: XML Support (demo.orders.xml) ---
XML_MSG="<Order id=\"ORD-XML-01\"><Customer>Alice</Customer><Amount>150.00</Amount><Items><Item><Name>SSD</Name><Price>100.00</Price></Item><Item><Name>Cable</Name><Price>50.00</Price></Item></Items></Order>"
produce "demo.orders.xml" "$XML_MSG"

# --- Scenario 5: Reference Data (demo.customers) ---
produce "demo.customers" "{\"customer_id\":\"C-001\",\"name\":\"Alice\",\"segment\":\"VIP\",\"country\":\"FR\"}"
produce "demo.customers" "{\"customer_id\":\"C-002\",\"name\":\"Bob\",\"segment\":\"REGULAR\",\"country\":\"UK\"}"
produce "demo.customers" "{\"customer_id\":\"C-003\",\"name\":\"Charlie\",\"segment\":\"REGULAR\",\"country\":\"DE\"}"

# --- Scenario 6: Complex/Nested JSON (demo.orders.complex) — 2-level nesting ---
produce "demo.orders.complex" "{\"id\":\"ORD-NEST-01\",\"header\":{\"timestamp\":$TS,\"source\":\"WEB\"},\"payload\":{\"items\":[{\"sku\":\"SKU-1\",\"qty\":2},{\"sku\":\"SKU-2\",\"qty\":1}],\"shipping\":{\"address\":{\"city\":\"Paris\",\"zip\":\"75001\"}}}}"

# --- Scenario 7: Malformed/Poison Messages (demo.errors.poison) ---
produce "demo.errors.poison" "{\"id\":\"ERR-01\", \"status\": \"CORRUPT\" ... missing quote"
produce "demo.errors.poison" "I am not a JSON at all"

# ---------------------------------------------------------------------------
# --- Scenario 8: Deep Nested JSON — 3 levels (demo.orders.nested) ---------
# ---------------------------------------------------------------------------
# Structure:
#   Level 1 — id, timestamp, status, channel
#   Level 2 — customer{}, order{}, logistics{}, audit{}
#   Level 3 — customer.profile.segment, customer.address.billing.city,
#              order.payment.details.provider, order.items[].pricing.discount.rate,
#              logistics.carrier.tracking.last_event.location,
#              audit.source.system.version
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
  AMOUNT=$(echo "scale=2; $i * 47 + 12.50" | bc)
  UNIT_PRICE=$(echo "scale=2; $i * 23 + 9.99" | bc)
  DISCOUNT_RATE=$(echo "scale=2; ($i % 4) * 0.05" | bc)
  SCORE=$((88 + (i % 12)))
  TRACKING="TRK-$CARRIER-$(printf "%08d" $((i * 13579)))"
  TXN_ID="TXN-$(printf "%010d" $((TS + i)))"
  PHONE="+336$(printf "%08d" $((i * 12345678 % 100000000)))"
  EMAIL="${CUSTOMER_NAME,,}$(printf "%02d" $i)@example.com"
  STREET="$((i * 3)) Rue de la République"
  SKU_A="SKU-$(printf "%04d" $((i * 7 % 9999)))"
  SKU_B="SKU-$(printf "%04d" $((i * 13 % 9999)))"

  MSG=$(cat <<EOF
{
  "id": "$ORDER_ID",
  "timestamp": "$DATE_NOW",
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
          "unit_price": $(echo "scale=2; $UNIT_PRICE / 2" | bc),
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
  produce "demo.orders.nested" "$COMPACT_MSG"
done

echo "Generated 20 messages with 3-level nested JSON in demo.orders.nested"

# --- Scenario 9: Supply Chain 2.0 (10 orders, 20 steps, 200 messages) ---
echo "Generating Supply Chain 2.0 messages..."
for i in $(seq 0 9); do
  ORDER_ID="SC-10$i"
  CUSTOMER_ID="C-00$(( (i % 3) + 1 ))"

  for j in "${!SC_STEPS[@]}"; do
    STEP_NAME="${SC_STEPS[$j]}"
    STEP_NUM=$((j + 1))

    # Base JSON
    MSG="{\"order_id\":\"$ORDER_ID\",\"step\":\"$STEP_NAME\",\"timestamp\":\"$(date -u +"%Y-%m-%dT%H:%M:%SZ")\",\"status\":\"COMPLETED\""

    # Step 1+: Initial details (level 2: initial_details.customer, level 3: initial_details.customer.contact)
    if [ $STEP_NUM -ge 1 ]; then
      MSG="$MSG,\"initial_details\":{\"customer_id\":\"$CUSTOMER_ID\",\"origin\":\"E-COMMERCE-WEB\",\"customer\":{\"segment\":\"VIP\",\"contact\":{\"channel\":\"EMAIL\",\"preference\":\"MORNING\"}}}"
    fi

    # Step 3+: Payment info (level 2: payment.details, level 3: payment.details.card)
    if [ $STEP_NUM -ge 3 ]; then
      MSG="$MSG,\"payment\":{\"provider\":\"STRIPE\",\"transaction_id\":\"TXN-$(date +%s)-$i\",\"amount\":$(( (i + 1) * 42 )).99,\"details\":{\"method\":\"CARD\",\"card\":{\"brand\":\"VISA\",\"last4\":\"$(printf "%04d" $((i * 1111 % 10000)))\",\"exp\":\"12/27\"}}}"
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
      MSG="$MSG,\"logistics\":{\"priority\":\"NORMAL\",\"carrier\":{\"name\":\"FAST-SHIP\",\"tracking\":{\"number\":\"FS-$ORDER_ID-$(date +%s)\",\"last_scan\":{\"location\":\"CDG Hub\",\"timestamp\":\"$(date -u +"%Y-%m-%dT%H:%M:%SZ")\"}}},\"sla\":{\"promised\":\"$DATE_NOW\",\"met\":true}}"
    fi

    # Step 16+: International (level 2: international.customs, level 3: international.customs.declaration)
    if [ $STEP_NUM -ge 16 ]; then
      MSG="$MSG,\"international\":{\"customs_code\":\"HS-84713000\",\"destination\":\"REGION-$((i % 5))\",\"customs\":{\"status\":\"CLEARED\",\"declaration\":{\"ref\":\"DECL-$(date +%s)-$i\",\"officer\":\"OFF-$((i % 10 + 1))\",\"cleared_at\":\"$DATE_NOW\"}}}"
    fi

    # Step 19+: Last mile (level 2: last_mile.delivery, level 3: last_mile.delivery.geo)
    if [ $STEP_NUM -ge 19 ]; then
      MSG="$MSG,\"last_mile\":{\"driver\":\"DRV-$(($i * 7 % 20))\",\"vehicle\":\"VAN-$i\",\"delivery\":{\"attempt\":1,\"confirmed_by\":\"SIGNATURE\",\"geo\":{\"lat\":48.$((85+i)),\"lon\":2.$((35+i)),\"accuracy_m\":5}}}"
    fi

    MSG="$MSG}"

    produce "demo.sc.$STEP_NAME.out" "$MSG"
  done
done

echo "--- Demo Setup Complete ---"
echo ""
echo "Suggestions for exploration:"
echo "1. 3-level nested JSON: SELECT id, status, channel FROM \"demo.orders.nested\""
echo "   → Try accessing deep fields: customer.profile.contact.email, order.payment.details.transaction.id"
echo "2. Nested aggregation: SELECT channel, COUNT(*) AS metric_value FROM \"demo.orders.nested\" GROUP BY channel"
echo "3. Filter on nested path: SELECT id FROM \"demo.orders.nested\" WHERE customer.profile.segment = 'VIP'"
echo "4. XML: SELECT XmlExtract(raw_value, '/Order/Customer') FROM \"demo.orders.xml\""
echo "5. Join: SELECT c.name, o.amount FROM \"demo.orders.1.received\" o JOIN \"demo.customers\" c ON o.customer_id = c.customer_id"
echo "6. Traceability: Trace 'ORD-101' across all demo topics"
echo "7. Supply Chain deep nesting: Inspect 'SC-100' in demo.sc.*.out — 3-level nesting from step 6 onwards"
echo "8. Metrics SQL: SELECT COUNT(*) AS metric_value FROM \"demo.orders.nested\""
echo "   → SELECT COUNT(*) AS metric_value, channel FROM \"demo.orders.nested\" GROUP BY channel"
