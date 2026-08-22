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
  // clusterName / bootstrapServers feed the header's connection pill. They were absent here, so
  // every published screenshot showed the frontend's fallback rather than what the app renders —
  // the exact failure mode this harness's README warns about: a missing field shows up on screen,
  // it does not fail loudly.
  clusterName: 'Kafka cluster',
  bootstrapServers: 'kafka:29092',
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

/**
 * `GET /api/dashboard/activity` — la colonne de sparklines du tableau des topics.
 *
 * Dérivée de la requête, comme le vrai endpoint : la page ne demande que les lignes affichées, et
 * une fixture qui répondrait pour tous les topics rendrait la capture muette sur ce point. La
 * série est pseudo-aléatoire mais **déterministe** (graine tirée du nom du topic), sur la règle de
 * ce harnais : une image qui change à chaque build est un diff que personne ne relit.
 *
 * Deux topics sortent du lot volontairement, parce que ce sont les deux cas que la colonne existe
 * pour distinguer : `demo.iot.sensors` produit en continu, et `internal.metrics.config` ne produit
 * presque rien — une ligne plate qui est une mesure, pas une absence de mesure.
 */
export function topicActivity(url) {
  const requested = (url.searchParams.get('topics') ?? '').split(',').filter(Boolean);
  const buckets = Number(url.searchParams.get('buckets') ?? 24);
  const windowMs = Number(url.searchParams.get('windowMs') ?? 24 * 3_600_000);
  const bucketMs = Math.round(windowMs / buckets);
  const end = Math.floor(NOW / bucketMs) * bucketMs;
  const start = end - bucketMs * buckets;

  const seedOf = (name) => [...name].reduce((h, c) => (h * 31 + c.charCodeAt(0)) % 100_000, 7);
  const topics = {};
  for (const topic of requested) {
    let seed = seedOf(topic);
    const next = () => (seed = (seed * 1103515245 + 12345) % 2147483648) / 2147483648;
    /*
     * `demo.errors.poison` a reçu sa salve puis s'est tu : c'est le cas que la pastille « silent »
     * existe pour montrer, et le seul qu'une courbe de cette taille ne dit pas d'elle-même. Ses
     * dix-huit enregistrements tiennent donc dans le premier quart de la fenêtre.
     */
    const burstUntil = topic === 'demo.errors.poison' ? Math.round(buckets / 4) : null;
    // Ailleurs, le débit est dérivé de la taille du topic dans la même fixture : un lecteur
    // compare la courbe à la colonne « Messages » de la même ligne, et 600/jour en face de 18
    // messages se verrait. Le facteur 1,4 compense le creux nocturne de la forme ci-dessous.
    const base = burstUntil
      ? (topicSizes[topic] ?? 0) / burstUntil
      : (topicSizes[topic] ?? 200) * 1.4 / buckets;
    const counts = Array.from({ length: buckets }, (_, i) => {
      // Une forme de journée : creux la nuit, pic en milieu d'après-midi.
      const hour = ((start + i * bucketMs) / 3_600_000) % 24;
      const shape = 0.35 + 0.65 * Math.max(0, Math.sin((hour / 24) * Math.PI * 2 - 1.2));
      const stopped = burstUntil !== null && i >= burstUntil;
      return stopped ? 0 : Math.round(base * shape * (0.7 + 0.6 * next()));
    });
    topics[topic] = {
      topic, windowStartMs: start, windowEndMs: end, bucketMs, counts,
      total: counts.reduce((a, b) => a + b, 0),
      coveredFromMs: null, partitionsMeasured: 3, partitionsTotal: 3,
      available: true, note: null,
    };
  }
  return { topics, windowStartMs: start, windowEndMs: end, bucketMs, buckets, available: true, warnings: [] };
}

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
    // Un groupe de l'explorer lui-même : la page le marque au lieu de le confondre avec les vôtres.
    { groupId: 'kafka-explorer-live-a41f9c', type: 'CONSUMER', state: 'STABLE', explorer: true },
  ],
  // `min` / `max` : c'est ce que le service met sur le fil (`range.put("min", …)`). La fixture
  // portait les noms de l'API Kafka (`minVersionLevel`), donc la capture publiée affichait
  // « vundefined » — un champ absent se voit à l'écran, il n'échoue pas bruyamment.
  finalizedFeatures: {
    'metadata.version': { min: 25, max: 25 },
    'kraft.version': { min: 1, max: 1 },
  },
  supportedFeatures: {
    'metadata.version': { min: 1, max: 25 },
    'kraft.version': { min: 0, max: 1 },
  },
};

export const config = {
  bootstrapServers: 'kafka:29092',
  mode: 'PLAIN',
  schemaRegistryUrl: 'http://schema-registry:8081',
  isConnected: true,
  clusterName: 'Kafka cluster',
  llmProvider: 'OLLAMA',
  llmBaseUrl: 'http://ollama:11434/v1',
  llmModel: 'qwen3:4b',
  llmApiKeyConfigured: false,
};

/* ──────────────────────────────────────────────────────────────────────────
 * Metrics
 *
 * Two metrics that have run, and the KPIs the panel derives from the audit above. The
 * suggestions are shaped exactly as `MetricSuggestionService` produces them — same evidence
 * sentences, same threshold arithmetic (2×/4× the measured hop, 2×/4× the observed gap), and
 * the same refusal to carry a threshold where nothing was measured. A fixture that invented
 * rounder numbers would photograph a panel this application does not have.
 * ────────────────────────────────────────────────────────────────────────── */

const history = (values) => values;

export const metrics = [
  {
    id: 'm-orders-volume', name: 'gauge_volume_demo_orders_1_received', type: 'GAUGE',
    sql: 'SELECT COUNT(*) AS metric_value\nFROM demo_orders_1_received',
    description: 'Records currently readable in demo.orders.1.received.',
    warningThreshold: 1800, criticalThreshold: 2400,
    lastValue: 1200, lastUpdateTime: min(1), errorMessage: null,
    history: history([1104, 1121, 1139, 1152, 1168, 1177, 1186, 1194, 1200]),
    lastSummary: { rowCount: 1 }, createTableSql: null,
    templateType: 'RAW_SQL', templateParams: {}, executionMode: 'SQL',
    labelTopic: 'demo.orders.1.received', labelFields: [],
  },
  {
    id: 'm-hop-latency', name: 'gauge_latency_demo_orders_3_enriched_to_demo_orders_4_transformed',
    type: 'GAUGE', sql: null,
    description: 'Average latency between demo.orders.3.enriched and demo.orders.4.transformed.',
    warningThreshold: 3800, criticalThreshold: 7600,
    lastValue: 2140, lastUpdateTime: min(1), errorMessage: null,
    history: history([1980, 2010, 2260, 2090, 1940, 2310, 2180, 2140]),
    lastSummary: { matchedCount: 812, avgLatencyMs: 2140, p95LatencyMs: 3410 },
    createTableSql: null,
    templateType: 'TOPIC_TRANSIT_LATENCY',
    templateParams: {
      sourceTopic: 'demo.orders.3.enriched', targetTopic: 'demo.orders.4.transformed',
      sourceSql: 'SELECT `id` AS match_key, `event_time` AS event_time\nFROM demo_orders_3_enriched',
      targetSql: 'SELECT `id` AS match_key, `event_time` AS event_time\nFROM demo_orders_4_transformed',
    },
    executionMode: 'TEMPLATE_BOUNDED_SCAN',
    labelTopic: 'demo.orders.3.enriched', labelFields: [],
  },
];

export const metricTemplates = [
  {
    type: 'TOPIC_COUNT_DELTA', label: 'Topic Count Delta',
    description: 'Compare two bounded topic counts and compute a gap, ratio or percentage difference.',
    supportedMetricTypes: ['GAUGE'], requiredParams: ['leftSql', 'rightSql', 'operation'],
  },
  {
    type: 'TOPIC_TRANSIT_LATENCY', label: 'Topic Transit Latency',
    description: 'Measure processing latency between two topics by matching events on a key.',
    supportedMetricTypes: ['GAUGE', 'HISTOGRAM', 'SUMMARY'], requiredParams: ['sourceSql', 'targetSql'],
  },
  {
    type: 'CONSUMER_TIME_LAG', label: 'Consumer Lag in Time',
    description: 'How far behind a consumer group is in time, not in records: the age of the oldest '
      + 'message still waiting. No SQL — committed offsets and record timestamps.',
    supportedMetricTypes: ['GAUGE'], requiredParams: ['topic', 'group'],
  },
];

const suggestion = (over) => ({
  alreadyConfigured: false, existingMetricName: null, thresholdBasis: null, caveats: [], ...over,
});

export const metricSuggestions = {
  suggestions: [
    suggestion({
      id: 'audit:hop-latency:demo.orders.4.transformed>demo.orders.5.shipped',
      source: 'AUDIT',
      title: 'Processing latency demo.orders.4.transformed → demo.orders.5.shipped',
      rationale: 'This hop is on a flow the audit reconstructed, and it is where a stalled consumer '
        + 'or a slow enrichment shows up first — as time, before it shows up as a backlog.',
      evidence: ['The cluster audit of 2026-06-15 14:32 measured an average hop of 4.2 s between '
        + 'demo.orders.4.transformed and demo.orders.5.shipped (flow "demo.orders", correlated on '
        + 'the id field of recent messages).'],
      thresholdBasis: 'Warning at 2× and critical at 4× the 4.2 s that was measured — a multiple of '
        + 'an observation, not a round number.',
      caveats: [
        'Correlation matches `id` (a column of the registered table for demo.orders.4.transformed) '
          + 'against `id` (a column of the registered table for demo.orders.5.shipped) — check both '
          + 'in the preview before saving.',
        'event_time is the Kafka record timestamp of the table the explorer registers.',
      ],
      metric: {
        id: null, name: 'gauge_latency_demo_orders_4_transformed_to_demo_orders_5_shipped',
        type: 'GAUGE', sql: null,
        description: 'Average latency between demo.orders.4.transformed and demo.orders.5.shipped.',
        warningThreshold: 8400, criticalThreshold: 16800,
        lastValue: null, lastUpdateTime: null, errorMessage: null, history: [], lastSummary: null,
        createTableSql: null, templateType: 'TOPIC_TRANSIT_LATENCY',
        templateParams: {
          sourceTopic: 'demo.orders.4.transformed', targetTopic: 'demo.orders.5.shipped',
        },
        executionMode: 'TEMPLATE_BOUNDED_SCAN',
        labelTopic: 'demo.orders.4.transformed', labelFields: [],
      },
    }),
    suggestion({
      id: 'audit:flow-gap:demo.orders.5.shipped>demo.orders.6.delivered',
      source: 'AUDIT',
      title: 'Throughput gap demo.orders.5.shipped → demo.orders.6.delivered',
      rationale: 'Both topics are counted anyway; the difference between them is the KPI, and it is '
        + 'the one number that says whether the step is losing events rather than merely slow.',
      evidence: ['The cluster audit of 2026-06-15 14:32 counted 1142 record(s) in '
        + 'demo.orders.5.shipped and 1098 in demo.orders.6.delivered — 96.1 % carried through '
        + '(flow "demo.orders").'],
      thresholdBasis: 'Warning at 2× and critical at 4× the 3.9 % gap observed, floored at 1 % / 5 % '
        + 'so a lossless flow still has a threshold.',
      caveats: ['A legitimate filter between the two steps shows up here as a permanent gap — set '
        + 'the thresholds around the level it normally sits at.'],
      metric: {
        id: null, name: 'gauge_gap_demo_orders_5_shipped_to_demo_orders_6_delivered',
        type: 'GAUGE', sql: null,
        description: 'Percentage gap between the record counts of demo.orders.5.shipped and '
          + 'demo.orders.6.delivered.',
        warningThreshold: 7.8, criticalThreshold: 15.6,
        lastValue: null, lastUpdateTime: null, errorMessage: null, history: [], lastSummary: null,
        createTableSql: null, templateType: 'TOPIC_COUNT_DELTA',
        templateParams: {
          leftTopic: 'demo.orders.5.shipped', rightTopic: 'demo.orders.6.delivered',
          operation: 'PERCENT_GAP',
        },
        executionMode: 'TEMPLATE_BOUNDED_SCAN',
        labelTopic: 'demo.orders.5.shipped', labelFields: [],
      },
    }),
    suggestion({
      id: 'audit:duplicates:demo.orders.1.received',
      source: 'AUDIT',
      title: 'Duplicate keys in demo.orders.1.received',
      rationale: 'The audit found duplicates here once. Whether that was a one-off redelivery or a '
        + 'producer that retries without idempotence is a question only a continuous measurement '
        + 'answers.',
      evidence: ['The cluster audit of 2026-06-15 14:32 found 2 duplicate key(s) in '
        + 'demo.orders.1.received over the messages it scanned.'],
      thresholdBasis: 'Warning at the 2 already observed and critical at twice that — the level '
        + 'that was reached once is the level worth hearing about again.',
      caveats: ['COUNT(DISTINCT …) needs the Flink planner; if the query falls back to the direct '
        + 'engine the metric reports the engine’s own error rather than a number.'],
      metric: {
        id: null, name: 'gauge_duplicates_demo_orders_1_received', type: 'GAUGE',
        sql: 'SELECT COUNT(*) - COUNT(DISTINCT `id`) AS metric_value\nFROM demo_orders_1_received',
        description: 'Records in demo.orders.1.received sharing an id with another record.',
        warningThreshold: 2, criticalThreshold: 4,
        lastValue: null, lastUpdateTime: null, errorMessage: null, history: [], lastSummary: null,
        createTableSql: null, templateType: 'RAW_SQL', templateParams: {}, executionMode: 'SQL',
        labelTopic: 'demo.orders.1.received', labelFields: [],
      },
    }),
    suggestion({
      id: 'audit:time-lag:demo.orders.3.enriched>orders-enricher',
      source: 'AUDIT',
      title: 'Delay in time of orders-enricher on demo.orders.3.enriched',
      rationale: 'A record count says how much is waiting, never for how long — the same 4 000 '
        + 'messages are seconds on one topic and days on another. This is the backlog in the unit '
        + 'an operator can act on.',
      evidence: [
        'The cluster audit of 2026-06-15 14:32 reported on demo.orders.3.enriched: Consumer group '
          + '’orders-enricher’ is 4200 message(s) behind with no member assigned to this '
          + 'topic — nothing is draining it.',
        'Read just now: group ’orders-enricher’ is 4200 record(s) behind on '
          + 'demo.orders.3.enriched. How long that represents is exactly what this metric measures, '
          + 'and nothing here knows it yet.',
      ],
      caveats: ['No threshold is proposed: nothing here has ever measured this topic in time. Run '
        + 'it, look at what it reports, then set one.'],
      metric: {
        id: null, name: 'gauge_time_lag_demo_orders_3_enriched_orders_enricher', type: 'GAUGE',
        sql: null,
        description: 'Age in milliseconds of the oldest message orders-enricher has not read on '
          + 'demo.orders.3.enriched.',
        warningThreshold: null, criticalThreshold: null,
        lastValue: null, lastUpdateTime: null, errorMessage: null, history: [], lastSummary: null,
        createTableSql: null, templateType: 'CONSUMER_TIME_LAG',
        templateParams: {
          topic: 'demo.orders.3.enriched', group: 'orders-enricher', aggregation: 'MAX',
        },
        executionMode: 'TEMPLATE_BOUNDED_SCAN',
        labelTopic: 'demo.orders.3.enriched', labelFields: [],
      },
    }),
    suggestion({
      id: 'flow:hop-latency:demo.payments.authorized>demo.payments.captured',
      source: 'STREAM_FLOW',
      title: 'Processing latency demo.payments.authorized → demo.payments.captured',
      rationale: 'A key was traced across this hop, so the pair is real rather than inferred from a '
        + 'naming convention — measuring it continuously turns one observation into a trend.',
      evidence: ['The trace of key ORD-1042 on 2026-06-15 14:28 travelled '
        + 'demo.payments.authorized → demo.payments.captured in 1.8 s (one key, one trace — a '
        + 'single observation, not a distribution).'],
      thresholdBasis: 'Warning at 2× and critical at 4× the 1.8 s that was measured — a multiple of '
        + 'an observation, not a round number.',
      caveats: ['Correlation matches `id` (assumed on demo.payments.authorized — nothing registered '
        + 'or profiled says otherwise).'],
      metric: {
        id: null, name: 'gauge_traced_latency_demo_payments_authorized_to_demo_payments_captured',
        type: 'GAUGE', sql: null,
        description: 'Average latency between demo.payments.authorized and demo.payments.captured.',
        warningThreshold: 3600, criticalThreshold: 7200,
        lastValue: null, lastUpdateTime: null, errorMessage: null, history: [], lastSummary: null,
        createTableSql: null, templateType: 'TOPIC_TRANSIT_LATENCY',
        templateParams: {
          sourceTopic: 'demo.payments.authorized', targetTopic: 'demo.payments.captured',
        },
        executionMode: 'TEMPLATE_BOUNDED_SCAN',
        labelTopic: 'demo.payments.authorized', labelFields: [],
      },
    }),
    suggestion({
      id: 'audit:volume:demo.iot.sensors',
      source: 'AUDIT',
      title: 'Volume of demo.iot.sensors',
      rationale: 'This is where the cluster’s traffic actually is. A count that stops moving on '
        + 'the busiest topic is the earliest sign of a producer that has gone quiet.',
      evidence: ['The cluster audit of 2026-06-15 14:32 counted 7200 record(s) in demo.iot.sensors.'],
      thresholdBasis: 'Warning at 1.5× and critical at 2× the 7200 counted by that run — growth '
        + 'relative to a measured baseline, not an absolute.',
      caveats: ['A bounded scan of the whole topic: on a topic with retention this counts what is '
        + 'still readable, not what was ever produced.'],
      alreadyConfigured: true,
      existingMetricName: 'gauge_volume_demo_orders_1_received',
      metric: {
        id: null, name: 'gauge_volume_demo_iot_sensors', type: 'GAUGE',
        sql: 'SELECT COUNT(*) AS metric_value\nFROM demo_iot_sensors',
        description: 'Records currently readable in demo.iot.sensors.',
        warningThreshold: 10800, criticalThreshold: 14400,
        lastValue: null, lastUpdateTime: null, errorMessage: null, history: [], lastSummary: null,
        createTableSql: null, templateType: 'RAW_SQL', templateParams: {}, executionMode: 'SQL',
        labelTopic: 'demo.iot.sensors', labelFields: [],
      },
    }),
  ],
  auditAvailable: true,
  auditId: 'audit-20260615-1432',
  auditTimestamp: NOW,
  auditSource: 'CURRENT_RUN',
  auditTopics: 28,
  flowChainsSubmitted: 1,
  notes: [
    'The audit reported consumer-lag findings on demo.orders.3.enriched. The backlog in *records* '
      + 'is not proposed as a SQL metric — it is exported directly from committed offsets: name '
      + 'those topics in explorer.lag-metrics-topics and Prometheus gets kafka_consumer_group_lag '
      + 'for them. The delay in *time* is what the proposed KPI above adds, since no count can be '
      + 'read as a duration.',
    '2 topic(s) carry unparseable payloads. No KPI is proposed for that: a parse failure is not '
      + 'something SQL can count — the query engine skips or fails on the record rather than '
      + 'reporting it. The audit is the measurement here.',
  ],
};

/**
 * `POST /api/data-model` — le modèle déduit pour la sélection que la capture ouvre.
 *
 * Calqué sur ce que `setup-demo.sh` sème, et surtout **sur ce que le service produirait
 * réellement** : les clés primaires sont celles que `detectPrimaryKey` élirait (un champ nommé
 * d'après le topic l'emporte sur un `id` nu), et chaque relation est celle que
 * `deduceRelations` tirerait, avec le grade et la phrase de justification que le serveur écrit.
 * Une fixture qui décrirait un modèle que le code ne sait pas produire photographierait un
 * produit qui n'existe pas.
 */
const dmColumn = (name, type, over = {}) => ({
  name, type, primaryKey: false, references: null, ...over,
});

export const dataModel = {
  entities: [
    {
      id: 'demo_customers',
      topic: 'demo.customers',
      format: 'JSON',
      primaryKey: 'customer_id',
      messageCount: SIZES['demo.customers'],
      columns: [
        dmColumn('customer_id', 'STRING', { primaryKey: true }),
        dmColumn('name', 'STRING'),
        dmColumn('email', 'STRING'),
        dmColumn('country', 'STRING'),
        dmColumn('tier', 'STRING'),
        dmColumn('created_at', 'BIGINT'),
      ],
    },
    {
      id: 'demo_orders_1_received',
      topic: 'demo.orders.1.received',
      format: 'JSON',
      primaryKey: 'order_id',
      messageCount: SIZES['demo.orders.1.received'],
      columns: [
        dmColumn('order_id', 'STRING', { primaryKey: true }),
        dmColumn('customer_id', 'STRING', { references: 'demo_customers' }),
        dmColumn('status', 'STRING'),
        dmColumn('amount_cents', 'BIGINT'),
        dmColumn('currency', 'STRING'),
        dmColumn('event_time', 'BIGINT'),
      ],
    },
    {
      id: 'demo_payments_authorized',
      topic: 'demo.payments.authorized',
      format: 'JSON',
      primaryKey: 'payment_id',
      messageCount: SIZES['demo.payments.authorized'],
      columns: [
        dmColumn('payment_id', 'STRING', { primaryKey: true }),
        dmColumn('order_id', 'STRING', { references: 'demo_orders_1_received' }),
        dmColumn('method', 'STRING'),
        dmColumn('amount_cents', 'BIGINT'),
        dmColumn('authorized_at', 'BIGINT'),
      ],
    },
    {
      id: 'demo_shipments_dispatched',
      topic: 'demo.shipments.dispatched',
      format: 'JSON',
      primaryKey: 'shipment_id',
      messageCount: SIZES['demo.shipments.dispatched'],
      columns: [
        dmColumn('shipment_id', 'STRING', { primaryKey: true }),
        dmColumn('order_id', 'STRING', { references: 'demo_orders_1_received' }),
        dmColumn('carrier', 'STRING'),
        dmColumn('tracking_code', 'STRING'),
        dmColumn('dispatched_at', 'BIGINT'),
      ],
    },
  ],
  relations: [
    {
      from: 'demo_orders_1_received', to: 'demo_customers',
      fromColumn: 'customer_id', toColumn: 'customer_id',
      confidence: 'HIGH',
      reason: "'customer_id' names topic 'demo.customers' and matches its key column 'customer_id'.",
    },
    {
      from: 'demo_payments_authorized', to: 'demo_orders_1_received',
      fromColumn: 'order_id', toColumn: 'order_id',
      confidence: 'HIGH',
      reason: "'order_id' names topic 'demo.orders.1.received' and matches its key column 'order_id'.",
    },
    {
      from: 'demo_shipments_dispatched', to: 'demo_orders_1_received',
      fromColumn: 'order_id', toColumn: 'order_id',
      confidence: 'HIGH',
      reason: "'order_id' names topic 'demo.orders.1.received' and matches its key column 'order_id'.",
    },
  ],
  warnings: [],
  topicsRequested: 4,
  topicsAnalyzed: 4,
  truncated: false,
};

/**
 * `GET /api/lineage` — le graphe de dépendances.
 *
 * Cette page n'avait aucune fixture : ni la capture ni la sonde de layout ne l'ouvrent, donc
 * elle n'était exercée par rien ici. C'est pourtant l'un des trois consommateurs de
 * `useGraphViewport`, et celui dont la politique diffère (`0` revient à une origine fixe là où
 * les deux autres recadrent), donc le laisser dehors aurait laissé le tiers du hook partagé
 * hors couverture — exactement le trou que la sonde de gestes existe pour fermer.
 *
 * La forme suit ce que `LineageService` produit : les topics de la démo en sources, les tables
 * Flink qu'une auto-registration crée à partir d'eux, et un `INSERT INTO` qui relie les deux.
 * Assez de nœuds pour que le graphe s'étende au-delà du viewport, ce dont le pan a besoin pour
 * être observable.
 */
export const lineage = {
  nodes: [
    { id: 'demo.orders.1.received', label: 'demo.orders.1.received', type: 'topic', messageCount: 1284 },
    { id: 'demo.payments.authorized', label: 'demo.payments.authorized', type: 'topic', messageCount: 863 },
    { id: 'demo.customers', label: 'demo.customers', type: 'topic', messageCount: 214 },
    { id: 'demo_orders_1_received', label: 'demo_orders_1_received', type: 'table' },
    { id: 'demo_payments_authorized', label: 'demo_payments_authorized', type: 'table' },
    { id: 'demo_customers', label: 'demo_customers', type: 'table' },
    { id: 'orders_enriched', label: 'orders_enriched', type: 'view' },
    { id: 'demo_orders_3_enriched', label: 'demo_orders_3_enriched', type: 'table' },
    { id: 'demo.orders.3.enriched', label: 'demo.orders.3.enriched', type: 'topic', messageCount: 1190 },
    { id: 'job-7f2a', label: "INSERT INTO demo_orders_3_enriched", type: 'query' },
  ],
  edges: [
    { from: 'demo.orders.1.received', to: 'demo_orders_1_received', label: 'connector' },
    { from: 'demo.payments.authorized', to: 'demo_payments_authorized', label: 'connector' },
    { from: 'demo.customers', to: 'demo_customers', label: 'connector' },
    { from: 'demo_orders_1_received', to: 'orders_enriched' },
    { from: 'demo_payments_authorized', to: 'orders_enriched' },
    { from: 'demo_customers', to: 'orders_enriched' },
    { from: 'orders_enriched', to: 'job-7f2a' },
    { from: 'job-7f2a', to: 'demo_orders_3_enriched' },
    { from: 'demo_orders_3_enriched', to: 'demo.orders.3.enriched', label: 'connector' },
  ],
  warnings: [],
};
