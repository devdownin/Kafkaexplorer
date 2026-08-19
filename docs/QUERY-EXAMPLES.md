# Query Examples

All examples below run out of the box against the bundled demo topics (`docker compose up -d` seeds 76 of them — see the [feature tour](FEATURES.md#12-demo--sandbox-environment)).

## XML with XPath
```sql
SELECT XmlExtract(raw_value, '/Order/Customer') as customer,
       XmlExtract(raw_value, '/Order/Amount') as amount
FROM "demo.orders.xml"
WHERE amount > 100;
```

## JOIN across topics
```sql
SELECT c.name, c.segment, o.amount, o.state
FROM "demo.orders.1.received" o
JOIN "demo.customers" c ON o.customer_id = c.customer_id;
```

## Deeply nested JSON (Supply Chain 2.0)
```sql
SELECT order_id,
       step,
       JSON_VALUE(raw_value, '$.quality_control.score') as qc_score,
       JSON_VALUE(raw_value, '$.logistics.tracking') as tracking
FROM "demo.sc.13.carrier.assigned.out"
WHERE JSON_VALUE(raw_value, '$.quality_control.score') > 95;
```

## Windowed aggregation
`demo.iot.sensors` is the topic to window: its `event_time` is spread over the last ~2h24, so this
returns one row per five-minute bucket. Windowing a topic whose records were all written at once
collapses into a single row — which is not the query being wrong, just the data having no span.
```sql
SELECT COUNT(*) AS metric_value, AVG(temperature) AS avg_temp, MAX(temperature) AS peak_temp
FROM TABLE(TUMBLE(TABLE "demo.iot.sensors", DESCRIPTOR(event_time), INTERVAL '5' MINUTE));
```

## Nested paths and aggregation
```sql
SELECT id, status, channel
FROM "demo.orders.nested"
WHERE customer.profile.segment = 'VIP';

SELECT channel, COUNT(*) AS metric_value
FROM "demo.orders.nested"
GROUP BY channel;
```

## Avro (Schema Registry stack only)
Columns come from the `demo.avro.orders-value` subject, not from sampling the payloads.
```sql
SELECT order_id, status, amount, currency
FROM "demo.avro.orders"
WHERE status = 'SHIPPED';
```

## Not SQL: tracing and searching
Some questions are not queries. Two the demo data is built for:
- **Stream Flow** → key `ORD-101`, *exact record key* on: the six pipeline topics chain up with a
  visible bottleneck on the 3 → 4 hop. Turn *search headers too* on and `demo.payments.*` /
  `demo.shipments.*` join the chain — their payloads never mention the order, only their headers do.
- **Topic Explorer** → `demo.orders.nested`, mode **Key**, value `ORD-DEEP-007`, *only this key's
  partition* on: the status strip reports the narrowed scan across the topic's 6 partitions.

## Tips
- Aggregate queries must alias the result column (e.g. `COUNT(*) AS metric_value`).
- Use the **Read Mode** switch (Earliest / Latest) in the SQL editor to control where the scan starts, without touching DDL.
- Click any field in a message preview (Topic Explorer) to inject it into `SELECT` / `WHERE` — no need to type `JSON_VALUE` paths by hand.
- The direct engine only applies simple `col = 'value'` predicates; anything else is reported in the
  result's warnings rather than silently dropped, so check that strip before trusting a filtered count.
