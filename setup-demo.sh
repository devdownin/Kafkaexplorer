#!/bin/bash

# Configuration
BOOTSTRAP_SERVER=${1:-"localhost:9092"}
KAFKA_TOPICS_CMD=${KAFKA_TOPICS_CMD:-"kafka-topics"}
KAFKA_PRODUCER_CMD=${KAFKA_PRODUCER_CMD:-"kafka-console-producer"}

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

# Create topics
echo "Creating topics..."
for TOPIC in "${TOPICS[@]}"; do
  $KAFKA_TOPICS_CMD --bootstrap-server "$BOOTSTRAP_SERVER" --create --topic "$TOPIC" --partitions 1 --replication-factor 1 --if-not-exists
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

echo "--- Demo Setup Complete ---"
echo "Suggestions for exploration:"
echo "1. Query XML: SELECT XmlExtract(raw_value, '/Order/Customer') FROM \"demo.orders.xml\""
echo "2. Join: SELECT c.name, o.amount FROM \"demo.orders.1.received\" o JOIN \"demo.customers\" c ON o.customer_id = c.customer_id"
echo "3. Nested JSON: Use the 'Register Table' feature on \"demo.orders.complex\" to test automatic JSON_VALUE generation"
echo "4. Traceability: Trace 'ORD-101' across all demo topics"
