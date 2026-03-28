# Metric Testing Strategy

This document outlines the strategy for extensively testing the metric generation and validation within Kafka SQL Explorer.

## Objectives
- Validate the accuracy of each metric type (GAUGE, COUNTER, HISTOGRAM, SUMMARY).
- Ensure correctness of metric templates (TOPIC_COUNT_DELTA, TOPIC_TRANSIT_LATENCY).
- Verify label extraction and tagging in Micrometer/Prometheus.
- Automate end-to-end validation: Data Ingestion -> Flink SQL Processing -> Micrometer Update.

## Metric Types Verification

### 1. GAUGE
- **Scenario**: Point-in-time value (e.g., `SELECT COUNT(*) FROM topic`).
- **Test**:
  1. Produce 5 messages to `topic_a`.
  2. Refresh metric.
  3. Assert Gauge value is 5.0.
  4. Produce 3 more messages.
  5. Refresh metric.
  6. Assert Gauge value is 8.0.

### 2. COUNTER
- **Scenario**: Monotonically increasing value.
- **Test**:
  1. Produce 10 messages.
  2. Refresh metric.
  3. Assert Counter incremented by 10.
  4. Produce 5 more messages.
  5. Refresh metric.
  6. Assert Counter incremented by 5 (Total 15).

### 3. HISTOGRAM
- **Scenario**: Distribution of values (e.g., `SELECT amount AS metric_value FROM orders`).
- **Test**:
  1. Produce messages with values: 10, 20, 100, 200.
  2. Refresh metric.
  3. Assert `DistributionSummary` contains 4 observations.
  4. Assert sum is 330.0.

### 4. SUMMARY
- **Scenario**: Percentiles (P50, P95, etc.).
- **Test**:
  1. Produce a range of messages with varying numeric values.
  2. Refresh metric.
  3. Assert Micrometer summary quantiles are within expected ranges.

## Template Verification

### 1. TOPIC_COUNT_DELTA
- **Scenario**: `LEFT_MINUS_RIGHT` between two topics.
- **Test**:
  1. Produce 10 messages to `topic_a`, 4 to `topic_b`.
  2. Refresh metric.
  3. Assert metric value is 6.0.

### 2. TOPIC_TRANSIT_LATENCY
- **Scenario**: Time difference between matching keys.
- **Test**:
  1. Produce message with `id=123` at `T0` to `source_topic`.
  2. Produce message with `id=123` at `T0 + 500ms` to `target_topic`.
  3. Refresh metric.
  4. Assert average latency is ~500ms.

## Label Extraction Verification
- **Scenario**: Dynamic labels from message payload.
- **Test**:
  1. Define metric with `labelFields = ['region', 'status']`.
  2. Produce message `{"region": "EU", "status": "ACTIVE", ...}`.
  3. Assert Micrometer metric has tags `region=EU` and `status=ACTIVE`.

## Advanced Testing Scenarios

### 1. Fault Tolerance
- **Simulate Kafka Downtime**: Verify that `MetricService` handles connection losses gracefully and resumes once Kafka is back.
- **Malformed SQL**: Ensure that invalid SQL doesn't crash the background scheduler and provides clear error messages in the metric metadata.
- **Schema Changes**: Verify behavior when message structure changes (missing fields for labels or numeric values).

### 2. Performance & Scalability
- **High Volume Topics**: Test metrics against topics with 1M+ messages to validate bounded-scan performance.
- **Metric Proliferation**: Create 100+ metrics and verify CPU/Memory impact of the background refresh process.
- **Complex Aggregations**: Use heavy JOINs or window functions in metric SQL to find performance bottlenecks.

## Automation Plan

### 1. Instrumentation
- Added `POST /api/test/produce/{topic}` to allow sending JSON payloads to Kafka for testing.
- This bypasses the need for external producer scripts during automated tests.

### 2. Integration Tests
- Uses `MetricIntegrationTest.java` with Testcontainers.
- Creates Flink tables using `flinkSqlService.executeSql`.
- Defines metrics via `metricService.save`.
- Asserts Micrometer registry state directly.

## Execution Steps
1. Create `MessageProducerService` and `TestController`.
2. Implement `MetricIntegrationTest`.
3. Run tests using `mvn test`.
