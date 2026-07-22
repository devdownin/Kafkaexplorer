# Query Examples

All examples below run out of the box against the bundled demo topics (`docker compose up -d` seeds 70+ of them — see the [feature tour](FEATURES.md#11-demo--sandbox-environment)).

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
```sql
SELECT COUNT(*) AS metric_value
FROM TABLE(TUMBLE(TABLE "demo.orders.1.received", DESCRIPTOR(ts), INTERVAL '5' MINUTE));
```

## Tips
- Aggregate queries must alias the result column (e.g. `COUNT(*) AS metric_value`).
- Use the **Read Mode** switch (Earliest / Latest) in the SQL editor to control where the scan starts, without touching DDL.
- Click any field in a message preview (Topic Explorer) to inject it into `SELECT` / `WHERE` — no need to type `JSON_VALUE` paths by hand.
