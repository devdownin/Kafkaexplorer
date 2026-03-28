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

## Automation Plan

### 1. Instrumentation
- Add `POST /api/test/produce/{topic}` to allow sending JSON payloads to Kafka for testing.
- This bypasses the need for external producer scripts during automated tests.

### 2. Integration Tests
- Use `MetricIntegrationTest.java` with Testcontainers.
- Create Flink tables using `flinkSqlService.executeSql`.
- Define metrics via `metricService.save`.
- Use `RestAssured` or `MockMvc` to trigger production and verify state.
- Assert Micrometer registry state directly.

## Execution Steps
1. Create `MessageProducerService` and `TestController`.
2. Implement `MetricIntegrationTest`.
3. Run tests using `mvn test`.
