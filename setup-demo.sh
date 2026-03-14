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

# --- Scenario 1: Nominal Flow (Order #101) ---
produce "demo.orders.1.received" "{\"id\":\"ORD-101\",\"state\":\"RECEIVED\",\"description\":\"Smartphone purchase\",\"type\":\"ELECTRONICS\",\"date\":\"$DATE_NOW\",\"amount\":599.99,\"customer_id\":\"C-001\"}"
produce "demo.orders.2.validated" "{\"id\":\"ORD-101\",\"state\":\"VALIDATED\",\"description\":\"Smartphone purchase\",\"type\":\"ELECTRONICS\",\"date\":\"$DATE_NOW\",\"amount\":599.99,\"customer_id\":\"C-001\",\"validation_code\":\"VAL-A1\"}"
produce "demo.orders.3.enriched" "{\"id\":\"ORD-101\",\"state\":\"ENRICHED\",\"description\":\"Smartphone purchase\",\"type\":\"ELECTRONICS\",\"date\":\"$DATE_NOW\",\"amount\":599.99,\"customer_id\":\"C-001\",\"metadata\":{\"warehouse\":\"WH-NORTH\",\"priority\":\"HIGH\"}}"
produce "demo.orders.4.transformed" "{\"id\":\"ORD-101\",\"state\":\"TRANSFORMED\",\"description\":\"Smartphone purchase\",\"type\":\"ELECTRONICS\",\"date\":\"$DATE_NOW\",\"total_with_tax\":719.99,\"currency\":\"EUR\"}"
produce "demo.orders.5.shipped" "{\"id\":\"ORD-101\",\"state\":\"SHIPPED\",\"description\":\"Smartphone purchase\",\"type\":\"ELECTRONICS\",\"date\":\"$DATE_NOW\",\"tracking_number\":\"TRK-123456\"}"
produce "demo.orders.6.delivered" "{\"id\":\"ORD-101\",\"state\":\"DELIVERED\",\"description\":\"Smartphone purchase\",\"type\":\"ELECTRONICS\",\"date\":\"$DATE_NOW\",\"delivery_status\":\"SUCCESS\"}"

# --- Scenario 2: Rejected Order (Order #102) ---
produce "demo.orders.1.received" "{\"id\":\"ORD-102\",\"state\":\"RECEIVED\",\"description\":\"Broken laptop return\",\"type\":\"ELECTRONICS\",\"date\":\"$DATE_NOW\",\"amount\":0.00,\"customer_id\":\"C-002\"}"
produce "demo.orders.2.validated" "{\"id\":\"ORD-102\",\"state\":\"REJECTED\",\"description\":\"Broken laptop return\",\"type\":\"ELECTRONICS\",\"date\":\"$DATE_NOW\",\"reason\":\"Warranty expired\"}"

# --- Scenario 3: Aggregation Data ---
produce "demo.orders.1.received" "{\"id\":\"ORD-103\",\"state\":\"RECEIVED\",\"description\":\"Sofa\",\"type\":\"FURNITURE\",\"date\":\"$DATE_NOW\",\"amount\":1200.00,\"customer_id\":\"C-003\"}"
produce "demo.orders.1.received" "{\"id\":\"ORD-104\",\"state\":\"RECEIVED\",\"description\":\"Table\",\"type\":\"FURNITURE\",\"date\":\"$DATE_NOW\",\"amount\":450.00,\"customer_id\":\"C-003\"}"
produce "demo.orders.1.received" "{\"id\":\"ORD-105\",\"state\":\"RECEIVED\",\"description\":\"Apple\",\"type\":\"FOOD\",\"date\":\"$DATE_NOW\",\"amount\":2.50,\"customer_id\":\"C-001\"}"
produce "demo.orders.1.received" "{\"id\":\"ORD-106\",\"state\":\"RECEIVED\",\"description\":\"Banana\",\"type\":\"FOOD\",\"date\":\"$DATE_NOW\",\"amount\":1.80,\"customer_id\":\"C-001\"}"

# --- Scenario 4: XML Support (demo.orders.xml) ---
XML_MSG="<Order id=\"ORD-XML-01\"><Customer>Alice</Customer><Amount>150.00</Amount><Items><Item><Name>SSD</Name><Price>100.00</Price></Item><Item><Name>Cable</Name><Price>50.00</Price></Item></Items></Order>"
produce "demo.orders.xml" "$XML_MSG"

# --- Scenario 5: Reference Data (demo.customers) ---
produce "demo.customers" "{\"customer_id\":\"C-001\",\"name\":\"Alice\",\"segment\":\"VIP\",\"country\":\"FR\"}"
produce "demo.customers" "{\"customer_id\":\"C-002\",\"name\":\"Bob\",\"segment\":\"REGULAR\",\"country\":\"UK\"}"
produce "demo.customers" "{\"customer_id\":\"C-003\",\"name\":\"Charlie\",\"segment\":\"REGULAR\",\"country\":\"DE\"}"

# --- Scenario 6: Complex/Nested JSON (demo.orders.complex) ---
produce "demo.orders.complex" "{\"id\":\"ORD-NEST-01\",\"header\":{\"timestamp\":$(date +%s),\"source\":\"WEB\"},\"payload\":{\"items\":[{\"sku\":\"SKU-1\",\"qty\":2},{\"sku\":\"SKU-2\",\"qty\":1}],\"shipping\":{\"address\":{\"city\":\"Paris\",\"zip\":\"75001\"}}}}"

# --- Scenario 7: Malformed/Poison Messages (demo.errors.poison) ---
produce "demo.errors.poison" "{\"id\":\"ERR-01\", \"status\": \"CORRUPT\" ... missing quote"
produce "demo.errors.poison" "I am not a JSON at all"

# --- Scenario 8: Supply Chain 2.0 (10 orders, 20 steps, 200 messages) ---
echo "Generating Supply Chain 2.0 messages..."
for i in $(seq 0 9); do
  ORDER_ID="SC-10$i"
  CUSTOMER_ID="C-00$(( (i % 3) + 1 ))"

  for j in "${!SC_STEPS[@]}"; do
    STEP_NAME="${SC_STEPS[$j]}"
    STEP_NUM=$((j + 1))

    # Base JSON
    MSG="{\"order_id\":\"$ORDER_ID\",\"step\":\"$STEP_NAME\",\"timestamp\":\"$(date -u +"%Y-%m-%dT%H:%M:%SZ")\",\"status\":\"COMPLETED\""

    # Step 1+: Initial details
    if [ $STEP_NUM -ge 1 ]; then
      MSG="$MSG,\"initial_details\":{\"customer_id\":\"$CUSTOMER_ID\",\"origin\":\"E-COMMERCE-WEB\"}"
    fi

    # Step 3+: Payment info
    if [ $STEP_NUM -ge 3 ]; then
      MSG="$MSG,\"payment\":{\"provider\":\"STRIPE\",\"transaction_id\":\"TXN-$(date +%s)-$i\",\"amount\":$(( (i + 1) * 42 )).99}"
    fi

    # Step 6+: Fulfillment info
    if [ $STEP_NUM -ge 6 ]; then
      MSG="$MSG,\"fulfillment\":{\"warehouse\":\"WH-MAIN\",\"aisle\":\"A-$i\",\"bin\":\"B-$(printf "%03d" $j)\"}"
    fi

    # Step 11+: Quality control
    if [ $STEP_NUM -ge 11 ]; then
      MSG="$MSG,\"quality_control\":{\"inspector\":\"QA-$(($i + 1))\",\"score\":$((90 + (i % 10))),\"checks\":[\"VISUAL\",\"WEIGHT\",\"DIMENSIONS\"]}"
    fi

    # Step 13+: Logistics
    if [ $STEP_NUM -ge 13 ]; then
      MSG="$MSG,\"logistics\":{\"carrier\":\"FAST-SHIP\",\"tracking\":\"FS-$ORDER_ID-$(date +%s)\",\"priority\":\"NORMAL\"}"
    fi

    # Step 16+: International
    if [ $STEP_NUM -ge 16 ]; then
      MSG="$MSG,\"international\":{\"customs_code\":\"HS-84713000\",\"destination\":\"REGION-$((i % 5))\"}"
    fi

    # Step 19+: Last mile
    if [ $STEP_NUM -ge 19 ]; then
      MSG="$MSG,\"last_mile\":{\"driver\":\"DRV-$(($i * 7 % 20))\",\"vehicle\":\"VAN-$i\",\"geo\":{\"lat\":48.$((85+i)),\"lon\":2.$((35+i))}}"
    fi

    MSG="$MSG}"

    produce "demo.sc.$STEP_NAME.out" "$MSG"
  done
done

echo "--- Demo Setup Complete ---"
echo "Suggestions for exploration:"
echo "1. Query XML: SELECT XmlExtract(raw_value, '/Order/Customer') FROM \"demo.orders.xml\""
echo "2. Join: SELECT c.name, o.amount FROM \"demo.orders.1.received\" o JOIN \"demo.customers\" c ON o.customer_id = c.customer_id"
echo "3. Nested JSON: Use the 'Register Table' feature on \"demo.orders.complex\" to test automatic JSON_VALUE generation"
echo "4. Traceability: Trace 'ORD-101' across all demo topics"
echo "5. Supply Chain 2.0: Trace 'SC-100' through 'demo.sc.*' topics to see evolving schema"
