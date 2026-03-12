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

# Scenario 1: Nominal Flow (Order #101) - Complete success
DATE_NOW=$(date -u +"%Y-%m-%dT%H:%M:%SZ")
produce "demo.orders.1.received" "{\"id\":\"ORD-101\",\"state\":\"RECEIVED\",\"description\":\"Smartphone purchase\",\"type\":\"ELECTRONICS\",\"date\":\"$DATE_NOW\",\"amount\":599.99,\"customer\":\"Alice\"}"
produce "demo.orders.2.validated" "{\"id\":\"ORD-101\",\"state\":\"VALIDATED\",\"description\":\"Smartphone purchase\",\"type\":\"ELECTRONICS\",\"date\":\"$DATE_NOW\",\"amount\":599.99,\"customer\":\"Alice\",\"validation_code\":\"VAL-A1\"}"
produce "demo.orders.3.enriched" "{\"id\":\"ORD-101\",\"state\":\"ENRICHED\",\"description\":\"Smartphone purchase\",\"type\":\"ELECTRONICS\",\"date\":\"$DATE_NOW\",\"amount\":599.99,\"customer\":\"Alice\",\"metadata\":{\"warehouse\":\"WH-NORTH\",\"priority\":\"HIGH\"}}"
produce "demo.orders.4.transformed" "{\"id\":\"ORD-101\",\"state\":\"TRANSFORMED\",\"description\":\"Smartphone purchase\",\"type\":\"ELECTRONICS\",\"date\":\"$DATE_NOW\",\"total_with_tax\":719.99,\"currency\":\"EUR\"}"
produce "demo.orders.5.shipped" "{\"id\":\"ORD-101\",\"state\":\"SHIPPED\",\"description\":\"Smartphone purchase\",\"type\":\"ELECTRONICS\",\"date\":\"$DATE_NOW\",\"tracking_number\":\"TRK-123456\"}"
produce "demo.orders.6.delivered" "{\"id\":\"ORD-101\",\"state\":\"DELIVERED\",\"description\":\"Smartphone purchase\",\"type\":\"ELECTRONICS\",\"date\":\"$DATE_NOW\",\"delivery_status\":\"SUCCESS\"}"

# Scenario 2: Rejected Order (Order #102) - Stops at validation
produce "demo.orders.1.received" "{\"id\":\"ORD-102\",\"state\":\"RECEIVED\",\"description\":\"Broken laptop return\",\"type\":\"ELECTRONICS\",\"date\":\"$DATE_NOW\",\"amount\":0.00,\"customer\":\"Bob\"}"
produce "demo.orders.2.validated" "{\"id\":\"ORD-102\",\"state\":\"REJECTED\",\"description\":\"Broken laptop return\",\"type\":\"ELECTRONICS\",\"date\":\"$DATE_NOW\",\"reason\":\"Warranty expired\"}"

# Scenario 3: Aggregation Data (Various types)
produce "demo.orders.1.received" "{\"id\":\"ORD-103\",\"state\":\"RECEIVED\",\"description\":\"Sofa\",\"type\":\"FURNITURE\",\"date\":\"$DATE_NOW\",\"amount\":1200.00,\"customer\":\"Charlie\"}"
produce "demo.orders.1.received" "{\"id\":\"ORD-104\",\"state\":\"RECEIVED\",\"description\":\"Table\",\"type\":\"FURNITURE\",\"date\":\"$DATE_NOW\",\"amount\":450.00,\"customer\":\"Charlie\"}"
produce "demo.orders.1.received" "{\"id\":\"ORD-105\",\"state\":\"RECEIVED\",\"description\":\"Apple\",\"type\":\"FOOD\",\"date\":\"$DATE_NOW\",\"amount\":2.50,\"customer\":\"Alice\"}"
produce "demo.orders.1.received" "{\"id\":\"ORD-106\",\"state\":\"RECEIVED\",\"description\":\"Banana\",\"type\":\"FOOD\",\"date\":\"$DATE_NOW\",\"amount\":1.80,\"customer\":\"Alice\"}"

# Advance some for ORD-103
produce "demo.orders.2.validated" "{\"id\":\"ORD-103\",\"state\":\"VALIDATED\",\"description\":\"Sofa\",\"type\":\"FURNITURE\",\"date\":\"$DATE_NOW\",\"amount\":1200.00,\"customer\":\"Charlie\",\"validation_code\":\"VAL-C3\"}"

echo "--- Demo Setup Complete ---"
echo "You can now use Kafka SQL Explorer to query these topics."
echo "Prefix search suggestion: 'demo.'"
