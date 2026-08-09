// Canned API responses for the screenshot run.
//
// The UI in the captures is the real one, compiled from src/main/webapp — only the data is
// canned. It deliberately mirrors what `setup-demo.sh` seeds (the six-step order pipeline,
// header-only payment/shipment correlation, the IoT time series, the duplicates and poison
// records the audit is calibrated to find), so a screenshot shows what someone actually
// gets after `docker compose up -d` rather than an invented cluster.
//
// Timestamps are relative to a fixed instant so a re-run produces the same image: a
// screenshot that changes on every build is a diff nobody can review.

/** Fixed reference instant — 2026-06-15 14:32 UTC. Everything is derived from it. */
export const NOW = Date.UTC(2026, 5, 15, 14, 32, 0);
const min = (n) => NOW - n * 60_000;

const ORDER_PIPELINE = [
  'demo.orders.1.received',
  'demo.orders.2.validated',
  'demo.orders.3.enriched',
  'demo.orders.4.transformed',
  'demo.orders.5.shipped',
  'demo.orders.6.delivered',
];

const SUPPLY_CHAIN = [
  'demo.sc.suppliers', 'demo.sc.purchase.orders', 'demo.sc.inbound.shipments',
  'demo.sc.warehouse.receipts', 'demo.sc.inventory.levels', 'demo.sc.stock.movements',
  'demo.sc.picking.tasks', 'demo.sc.packing.units', 'demo.sc.outbound.shipments',
  'demo.sc.carrier.events', 'demo.sc.customs.declarations', 'demo.sc.returns',
];

export const TOPICS = [
  ...ORDER_PIPELINE,
  'demo.orders.nested', 'demo.orders.complex', 'demo.orders.xml',
  'demo.payments.authorized', 'demo.payments.captured',
  'demo.shipments.dispatched', 'demo.shipments.delivered',
  'demo.customers', 'demo.iot.sensors', 'demo.errors.poison',
  ...SUPPLY_CHAIN,
  'internal.audit.history', 'internal.metrics.config',
];

const SIZES = {
  'demo.orders.1.received': 1200, 'demo.orders.2.validated': 1187,
  'demo.orders.3.enriched': 1174, 'demo.orders.4.transformed': 1174,
  'demo.orders.5.shipped': 1142, 'demo.orders.6.delivered': 1098,
  'demo.orders.nested': 640, 'demo.orders.complex': 120, 'demo.orders.xml': 96,
  'demo.payments.authorized': 1183, 'demo.payments.captured': 1150,
  'demo.shipments.dispatched': 1140, 'demo.shipments.delivered': 1096,
  'demo.customers': 240, 'demo.iot.sensors': 7200, 'demo.errors.poison': 18,
  'internal.audit.history': 7, 'internal.metrics.config': 3,
};

export const topicSizes = Object.fromEntries(
  TOPICS.map((t, i) => [t, SIZES[t] ?? 180 + ((i * 37) % 420)]));

export const topicLastMessages = Object.fromEntries(
  TOPICS.map((t, i) => [t, t.startsWith('internal.') ? min(180 + i) : min(1 + (i % 47))]));

export const dashboard = {
  topics: TOPICS,
  topicSizes,
  totalMessages: Object.values(topicSizes).reduce((a, b) => a + b, 0),
  tables: ['demo_orders_1_received', 'demo_orders_nested', 'demo_iot_sensors', 'demo_payments_authorized'],
  jobs: [
    {
      queryId: 'q-7f3a91c4', flinkJobId: '8b1e0a2c94d7f6135ae2', statementType: 'SELECT',
      status: 'FINISHED', sql: 'SELECT order_id, status, amount_cents FROM demo_orders_5_shipped LIMIT 50',
      startedAt: min(4), endedAt: min(4) + 2_310, cancelRequested: false,
    },
    {
      queryId: 'q-2c05be18', flinkJobId: 'd42f7c1908ba6e35f0c1', statementType: 'CREATE TABLE',
      status: 'FINISHED', sql: 'CREATE TABLE demo_iot_sensors (...) WITH (...)',
      startedAt: min(11), endedAt: min(11) + 640, cancelRequested: false,
    },
  ],
  health: true,
  topicLastMessages,
};

// ── Topic Explorer ───────────────────────────────────────────────────────────────────
const orderPayload = (id, status, cents, customer) => JSON.stringify({
  order_id: id,
  customer_id: customer,
  status,
  amount_cents: cents,
  currency: 'EUR',
  items: [
    { sku: 'SKU-4417', qty: 2, unit_price_cents: Math.round(cents / 3) },
    { sku: 'SKU-9082', qty: 1, unit_price_cents: Math.round(cents / 3) },
  ],
  shipping: { country: 'FR', city: 'Lyon', postal_code: '69003' },
  event_time: Math.floor(min(12) / 1000),
}, null, 2);

const headersFor = (id, event) => ({
  'correlation-id': `corr-${id.toLowerCase()}`,
  traceparent: '00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01',
  'source-system': 'order-service',
  'event-type': event,
  'produced-at': String(min(12)),
});

const message = (partition, offset, key, value, event, ageMin) => ({
  partition, offset, key, value,
  timestamp: min(ageMin),
  headers: headersFor(key, event),
  valueBytes: value.length,
  truncated: false,
});

export const topicDetail = {
  topic: {
    name: 'demo.orders.5.shipped',
    partitions: 3,
    estimatedSize: 1142,
    minOffsets: { 0: 0, 1: 0, 2: 0 },
    maxOffsets: { 0: 381, 1: 380, 2: 381 },
  },
  format: 'JSON',
  schema: {
    order_id: 'STRING', customer_id: 'STRING', status: 'STRING',
    amount_cents: 'BIGINT', currency: 'STRING',
    'items[].sku': 'STRING', 'items[].qty': 'INT', 'items[].unit_price_cents': 'BIGINT',
    'shipping.country': 'STRING', 'shipping.city': 'STRING', 'shipping.postal_code': 'STRING',
    event_time: 'BIGINT',
  },
  ddl: `CREATE TABLE demo_orders_5_shipped (
  order_id STRING,
  customer_id STRING,
  status STRING,
  amount_cents BIGINT,
  currency STRING,
  items ARRAY<ROW<sku STRING, qty INT, unit_price_cents BIGINT>>,
  shipping ROW<country STRING, city STRING, postal_code STRING>,
  event_time BIGINT,
  ts AS TO_TIMESTAMP_LTZ(event_time, 0),
  WATERMARK FOR ts AS ts - INTERVAL '5' SECOND
) WITH (
  'connector' = 'kafka',
  'topic' = 'demo.orders.5.shipped',
  'properties.bootstrap.servers' = 'kafka:29092',
  'scan.startup.mode' = 'earliest-offset',
  'format' = 'json',
  'json.ignore-parse-errors' = 'true'
)`,
  samples: [
    message(1, 380, 'ORD-1042', orderPayload('ORD-1042', 'SHIPPED', 12990, 'CUST-318'), 'order.shipped', 3),
    message(0, 381, 'ORD-1041', orderPayload('ORD-1041', 'SHIPPED', 4550, 'CUST-207'), 'order.shipped', 5),
    message(2, 379, 'ORD-1040', orderPayload('ORD-1040', 'SHIPPED', 28900, 'CUST-091'), 'order.shipped', 8),
    message(1, 379, 'ORD-1039', orderPayload('ORD-1039', 'SHIPPED', 7120, 'CUST-455'), 'order.shipped', 12),
    message(0, 380, 'ORD-1038', orderPayload('ORD-1038', 'SHIPPED', 15400, 'CUST-318'), 'order.shipped', 16),
  ],
};

export const topicSearch = {
  hits: [
    message(2, 214, 'ORD-1042', orderPayload('ORD-1042', 'SHIPPED', 12990, 'CUST-318'), 'order.shipped', 3),
    message(2, 118, 'ORD-1042', orderPayload('ORD-1042', 'VALIDATED', 12990, 'CUST-318'), 'order.validated', 21),
  ],
  scanned: 4318,
  matched: 2,
  // Part of the contract, not decoration: the coverage strip renders "scanned in <elapsed>",
  // so omitting it printed "4,318 scanned in NaNs".
  elapsedMs: 1840,
  stopReason: 'EXHAUSTED',
  exhausted: true,
  nextCursor: {},
  warnings: [],
};

// ── SQL Editor ───────────────────────────────────────────────────────────────────────
/** `GET /api/query/init` — drives the schema browser and the "Engine connected" footer. */
export const queryInit = {
  topics: TOPICS.filter(t => !t.startsWith('internal.')),
  tables: ['demo_orders_1_received', 'demo_orders_5_shipped', 'demo_orders_nested', 'demo_iot_sensors', 'demo_payments_authorized'],
  health: true,
};

export const querySchema = {
  demo_orders_5_shipped: ['order_id', 'customer_id', 'status', 'amount_cents', 'currency', 'event_time'],
  demo_iot_sensors: ['sensor_id', 'site', 'reading', 'unit', 'event_time'],
};

export const queryResult = {
  columns: ['order_id', 'customer_id', 'status', 'amount_cents', 'currency'],
  rows: [
    { order_id: 'ORD-1042', customer_id: 'CUST-318', status: 'SHIPPED', amount_cents: 12990, currency: 'EUR' },
    { order_id: 'ORD-1041', customer_id: 'CUST-207', status: 'SHIPPED', amount_cents: 4550, currency: 'EUR' },
    { order_id: 'ORD-1040', customer_id: 'CUST-091', status: 'SHIPPED', amount_cents: 28900, currency: 'EUR' },
    { order_id: 'ORD-1039', customer_id: 'CUST-455', status: 'SHIPPED', amount_cents: 7120, currency: 'EUR' },
    { order_id: 'ORD-1038', customer_id: 'CUST-318', status: 'SHIPPED', amount_cents: 15400, currency: 'EUR' },
    { order_id: 'ORD-1037', customer_id: 'CUST-772', status: 'SHIPPED', amount_cents: 9990, currency: 'EUR' },
    { order_id: 'ORD-1036', customer_id: 'CUST-104', status: 'SHIPPED', amount_cents: 33500, currency: 'EUR' },
    { order_id: 'ORD-1035', customer_id: 'CUST-318', status: 'SHIPPED', amount_cents: 2450, currency: 'EUR' },
    { order_id: 'ORD-1034', customer_id: 'CUST-560', status: 'SHIPPED', amount_cents: 18700, currency: 'EUR' },
    { order_id: 'ORD-1033', customer_id: 'CUST-091', status: 'SHIPPED', amount_cents: 6300, currency: 'EUR' },
  ],
  error: null,
  engine: 'FLINK',
  warnings: [],
};

export const QUERY_SQL = `-- Orders shipped above 50 EUR, newest first.
SELECT order_id, customer_id, status, amount_cents, currency
FROM demo_orders_5_shipped
WHERE amount_cents > 5000
LIMIT 50`;

// ── Stream Flow ──────────────────────────────────────────────────────────────────────
// Six hops, not the full eight the demo pipeline can produce: the graph fits its chain to
// the viewport, so every extra node shrinks all of them. Past six the labels stop being
// legible in a documentation screenshot, which is the whole point of the picture.
const HOPS = [
  ['demo.orders.1.received', 0, 2, 640],
  ['demo.orders.2.validated', 180, 0, 118],
  ['demo.orders.3.enriched', 410, 1, 205],
  ['demo.payments.authorized', 890, 2, 77],
  ['demo.orders.5.shipped', 4_610, 1, 214],
  ['demo.orders.6.delivered', 5_270, 2, 288],
];

const base = min(26);
export const streamFlow = {
  nodes: HOPS.map(([topic, offsetMs, , ]) => ({
    id: topic, label: topic, type: 'topic', timestamp: base + offsetMs, hits: 1,
  })),
  edges: HOPS.slice(1).map(([topic], i) => ({
    source: HOPS[i][0], target: topic,
  })),
  hits: HOPS.map(([topic, offsetMs, partition, offset], i) => ({
    topic,
    occurrences: 1,
    firstTimestamp: base + offsetMs,
    lastTimestamp: base + offsetMs,
    firstPartition: partition,
    firstOffset: offset,
    firstKey: 'ORD-1042',
    preview: `{"order_id":"ORD-1042","status":"${topic.split('.').pop().toUpperCase()}","amount_cents":12990,…`,
    latencyFromPreviousMs: i === 0 ? null : offsetMs - HOPS[i - 1][1],
  })),
  stats: {
    topicsInScope: 28, topicsScanned: 28, topicsSkipped: 0, topicsFailed: 0,
    messagesScanned: 19_480, matches: 6, durationMs: 4_120,
    truncated: false, stopReason: 'COMPLETED',
    maxMessagesPerTopic: 1000, timeLimitMinutes: null,
  },
  warnings: [],
};

// ── Audit ────────────────────────────────────────────────────────────────────────────
const healthyTopic = (name, count, dups = 0) => ({
  name, messageCount: count, format: 'JSON',
  poisonMessageCount: 0, duplicateCount: dups,
  healthStatus: dups ? 'WARNING' : 'HEALTHY',
  issues: dups
    ? [{ message: `${dups} duplicate keys among the 10 000 most recent messages (ORD-103, ORD-105)`, severity: 'WARNING' }]
    : [],
});

export const auditReport = {
  auditId: 'audit-20260615-1432',
  status: 'COMPLETED',
  totalTopics: 28,
  totalMessages: 24_186,
  criticalTopicsCount: 2,
  warningTopicsCount: 3,
  topicAudits: [
    {
      name: 'demo.errors.poison', messageCount: 18, format: 'JSON',
      poisonMessageCount: 6, duplicateCount: 0, healthStatus: 'CRITICAL',
      issues: [{ message: '6 of the 10 sampled messages could not be parsed as JSON', severity: 'CRITICAL' }],
    },
    {
      name: 'demo.orders.3.enriched', messageCount: 1174, format: 'JSON',
      poisonMessageCount: 2, duplicateCount: 2, healthStatus: 'CRITICAL',
      issues: [
        { message: '2 unparseable payloads inside an otherwise healthy topic', severity: 'CRITICAL' },
        { message: '2 duplicate keys among the 10 000 most recent messages (ORD-103, ORD-105)', severity: 'WARNING' },
      ],
    },
    healthyTopic('demo.orders.1.received', 1200, 2),
    healthyTopic('demo.orders.2.validated', 1187),
    healthyTopic('demo.orders.4.transformed', 1174),
    healthyTopic('demo.orders.5.shipped', 1142, 1),
    healthyTopic('demo.orders.6.delivered', 1098),
    healthyTopic('demo.payments.authorized', 1183),
    healthyTopic('demo.payments.captured', 1150),
    healthyTopic('demo.shipments.dispatched', 1140),
    healthyTopic('demo.shipments.delivered', 1096),
    { ...healthyTopic('demo.orders.xml', 96), format: 'XML' },
    { ...healthyTopic('demo.orders.nested', 640), format: 'JSON' },
    healthyTopic('demo.iot.sensors', 7200),
    healthyTopic('demo.customers', 240),
  ],
  flowAudits: [
    {
      flowName: 'Order pipeline',
      overallHealthScore: 0.915,
      steps: [
        { topicName: 'demo.orders.1.received', count: 1200, throughputPercentage: 100, averageLatencyMs: null },
        { topicName: 'demo.orders.2.validated', count: 1187, throughputPercentage: 98.9, averageLatencyMs: 180 },
        { topicName: 'demo.orders.3.enriched', count: 1174, throughputPercentage: 97.8, averageLatencyMs: 230 },
        { topicName: 'demo.orders.4.transformed', count: 1174, throughputPercentage: 97.8, averageLatencyMs: 350 },
        { topicName: 'demo.orders.5.shipped', count: 1142, throughputPercentage: 95.2, averageLatencyMs: 3670 },
        { topicName: 'demo.orders.6.delivered', count: 1098, throughputPercentage: 91.5, averageLatencyMs: 1110 },
      ],
    },
  ],
  globalStats: {
    timestamp: NOW - 90_000,
    startedAt: NOW - 214_000,
    durationMs: 124_000,
    healthScore: 0.891,
    scopeNotes: [
      'Duplicate detection read the 10 000 most recent messages of each topic.',
      'Poison detection parsed 10 sampled messages per topic.',
      'Flow latency was computed over the 1 000 most recent messages per step.',
    ],
    options: { exactCounts: true, inferSchema: true, detectDuplicates: true },
  },
};

export const auditHistory = {
  runs: [
    {
      auditId: 'audit-20260615-1432', status: 'COMPLETED', timestamp: NOW - 90_000,
      durationMs: 124_000, totalTopics: 28, totalMessages: 24_186,
      criticalTopicsCount: 2, warningTopicsCount: 3, healthScore: 0.891,
      topicPrefix: null, legacy: false,
    },
    {
      auditId: 'audit-20260614-0900', status: 'COMPLETED', timestamp: NOW - 105_600_000,
      durationMs: 131_500, totalTopics: 28, totalMessages: 21_004,
      criticalTopicsCount: 1, warningTopicsCount: 3, healthScore: 0.922,
      topicPrefix: null, legacy: false,
    },
    {
      auditId: 'audit-20260613-0900', status: 'COMPLETED', timestamp: NOW - 192_000_000,
      durationMs: 128_900, totalTopics: 27, totalMessages: 18_770,
      criticalTopicsCount: 1, warningTopicsCount: 2, healthScore: 0.935,
      topicPrefix: null, legacy: false,
    },
  ],
  recordsScanned: 7,
  exhausted: true,
  warnings: [],
};

// ── Cluster ──────────────────────────────────────────────────────────────────────────
export const cluster = {
  clusterId: 'MkU2OhlMTT69sPFvS1n16g',
  controllerId: 1,
  brokerCount: 1,
  kraftQuorum: {
    leaderId: 1, leaderEpoch: 4, highWatermark: 48_213,
    voters: [{ replicaId: 1, logEndOffset: 48_213, lag: 0 }],
    observers: [],
  },
  groups: [
    { groupId: 'order-enrichment', type: 'CONSUMER', state: 'STABLE' },
    { groupId: 'payment-matcher', type: 'CONSUMER', state: 'STABLE' },
    { groupId: 'legacy-etl', type: 'CLASSIC', state: 'STABLE' },
    { groupId: 'analytics-share', type: 'SHARE', state: 'STABLE' },
    { groupId: 'sc-reconciler', type: 'STREAMS', state: 'STABLE' },
  ],
  finalizedFeatures: {
    'metadata.version': { minVersionLevel: 25, maxVersionLevel: 25 },
    'kraft.version': { minVersionLevel: 1, maxVersionLevel: 1 },
  },
  supportedFeatures: {
    'metadata.version': { minVersionLevel: 1, maxVersionLevel: 25 },
    'kraft.version': { minVersionLevel: 0, maxVersionLevel: 1 },
  },
};

export const config = {
  bootstrapServers: 'kafka:29092',
  mode: 'PLAIN',
  schemaRegistryUrl: 'http://schema-registry:8081',
  isConnected: true,
  clusterName: 'KRAFT 4.2',
  llmProvider: 'OLLAMA',
  llmBaseUrl: 'http://ollama:11434/v1',
  llmModel: 'qwen3:4b',
  llmApiKeyConfigured: false,
};
