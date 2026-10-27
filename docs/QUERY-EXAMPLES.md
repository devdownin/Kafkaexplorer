# Query Examples

All examples below run out of the box against the bundled demo topics (`docker compose up -d` seeds 79 of them — see the [feature tour](FEATURES.md#12-demo--sandbox-environment)).

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

## ksqlDB → Flink migration (péage)
The queries of the [ksql2flink](https://github.com/SteelDevAndroid/ksql2flink) laboratory, which
migrates 37 persistent ksqlDB queries of a toll-road back office to Flink SQL, rewritten for the
embedded engine. They need their own topics, seeded on demand and never by the default stack:
```bash
./setup-demo-peage.sh localhost:9092
```
The laboratory submits `INSERT INTO` jobs through `SET`, `CREATE VIEW` and Java UDFs; the editor
reads, so each job becomes the `SELECT` that feeds it; the paths quoted below are the laboratory's.
Three rewrites recur:
- **`STRUCT` fields are JSON strings here.** Schema inference types a nested object as `STRING`,
  so `PASSAGESORTIE->PAIEMENT->RETENUS[1]->TYPE` becomes
  `JSON_VALUE(PASSAGESORTIE, '$.PAIEMENT.RETENUS[0].TYPE')` — and ksqlDB's arrays start at 1, a
  JSON path's at 0.
- **`LATEST_BY_OFFSET` is `LAST_VALUE`**, grouped by the key, which is how a stream becomes the
  ksqlDB `TABLE` a join reads.
- **`REDUCE` over an array has no Flink equivalent without a UDF** (the laboratory ships
  `JsonContains`); `JSON_QUERY` over the array's field, then `LIKE`, answers the same "does any
  element carry this value".

What the seeder writes, and what each query below returns on it:

| Topic | Records | Key | Content |
|---|---|---|---|
| `asf.peage.voie.passagesortieva` | 16 | `IDPASSAGE` | Exit passages, five payment types in mixed case, 3 in cash, 1 with two payments |
| `esc.peage.voie.passagesortieva` | 8 | `IDPASSAGE` | The same shape for a second company, 2 in cash |
| `va.peage.backoffice.socleET.ref_compteur` | 6 | `ID` | Five counters; ID 3 published twice, ID 5 without a frequency |
| `va.peage.backoffice.socleET.etancheite` | 48 | `IDT_TRAN_PEA` | One transaction every five minutes over the four hours before seeding |
| `VA_PEAGE_CLASSIFICATION_FLOWS_EXTEND` | 70 | `STATIONLANE` | Classification flows on three lanes, plus 4 `TD` and 4 `PostDTO` flows to exclude |

An exit passage, as `asf.peage.voie.passagesortieva` carries it:
```json
{
  "IDPASSAGE": "ASF-00001",
  "MESSAGE": {"NUMERO": 1, "TYPE": "PASSAGESORTIE", "DHM": "2026-10-25T08:01:00Z", "TYPEEMISSION": "TR",
              "PRODUCTEUR": {"TYPE": "VOIE", "ID": "25006123"}},
  "PASSAGESORTIE": {
    "SYSTEME": "ASF", "DHM": "2026-10-25T08:01:00Z", "STATUT": "VALIDE",
    "LOCALISATION": {"GARE": "25006123", "EQUIPEMENT": {"NUMERO": "2", "TYPE": "VS"}},
    "TARIFICATION": {
      "CLASSETARIFAIRE": {"CLASSE": "2", "ACQUISITION": "AUTO"},
      "PRIX": {"MONTANTHT": 3.52, "MONTANTTVA": 0.71, "MONTANTTTC": 4.23, "TAUXTVA": 20.00}
    },
    "PAIEMENT": {"RETENUS": [{"TYPE": "telepeagecardme", "MONTANT": 4.23, "DEVISE": "EUR", "PAN": "PAN-ASF-1"}]}
  }
}
```

Lot 1, Lexhistoric (`sql/flink/02-lexhistoric.sql`): one company's exit passages projected to the
settlement mode. Twelve rows: cash is excluded, and so is the passage whose *second* payment
matches, only the first one counting.
```sql
SELECT IDPASSAGE AS IDTRAN,
       JSON_VALUE(PASSAGESORTIE, '$.DHM') AS DATETRANSACTION,
       JSON_VALUE(PASSAGESORTIE, '$.PAIEMENT.RETENUS[0].PAN') AS CONTENUISO,
       CASE LOWER(JSON_VALUE(PASSAGESORTIE, '$.PAIEMENT.RETENUS[0].TYPE'))
         WHEN 'telepeagelibert' THEN 256
         WHEN 'telepeagecardme' THEN 512
         WHEN 'carteaccreditive' THEN 1024
         WHEN 'cartemagnetique' THEN 2048
       END AS MODEREGLEMENT,
       SUBSTRING(JSON_VALUE(PASSAGESORTIE, '$.LOCALISATION.GARE'), 1, 5) AS SCA
FROM "asf.peage.voie.passagesortieva"
WHERE LOWER(JSON_VALUE(PASSAGESORTIE, '$.PAIEMENT.RETENUS[0].TYPE'))
      IN ('telepeagelibert', 'telepeagecardme', 'carteaccreditive', 'cartemagnetique');
```
The three passages the filter drops, by their first `RETENUS` entry:

| `IDPASSAGE` | `PAIEMENT.RETENUS` | Why it is dropped |
|---|---|---|
| `ASF-00004`, `ASF-00009`, `ASF-00014` | `[{"TYPE":"especes", …}]` | Cash is not a settlement mode |
| `ASF-00016` | `[{"TYPE":"especes", …}, {"TYPE":"telepeagelibert", …}]` | Only the first payment is read |

The first rows of the twelve returned:

| IDTRAN | DATETRANSACTION | CONTENUISO | MODEREGLEMENT | SCA |
|---|---|---|---|---|
| ASF-00001 | 2026-10-25T08:01:00Z | PAN-ASF-1 | 512 | 25006 |
| ASF-00002 | 2026-10-25T08:02:00Z | PAN-ASF-2 | 1024 | 25021 |
| ASF-00003 | 2026-10-25T08:03:00Z | PAN-ASF-3 | 2048 | 25005 |
| ASF-00005 | 2026-10-25T08:05:00Z | PAN-ASF-5 | 256 | 25006 |
| … | | | | |

The same lot consolidated: ksqlDB writes one `CREATE STREAM … AS SELECT` per company into a
shared topic, Flink reads them as one `UNION ALL`. Eighteen passages from two companies, counted
by settlement mode.
```sql
WITH passages AS (
  SELECT IDPASSAGE, PASSAGESORTIE FROM "asf.peage.voie.passagesortieva"
  UNION ALL
  SELECT IDPASSAGE, PASSAGESORTIE FROM "esc.peage.voie.passagesortieva"
)
SELECT JSON_VALUE(PASSAGESORTIE, '$.SYSTEME') AS SOCIETE,
       LOWER(JSON_VALUE(PASSAGESORTIE, '$.PAIEMENT.RETENUS[0].TYPE')) AS MODE_REGLEMENT,
       COUNT(*) AS NB_PASSAGES
FROM passages
WHERE LOWER(JSON_VALUE(PASSAGESORTIE, '$.PAIEMENT.RETENUS[0].TYPE'))
      IN ('telepeagelibert', 'telepeagecardme', 'carteaccreditive', 'cartemagnetique')
GROUP BY JSON_VALUE(PASSAGESORTIE, '$.SYSTEME'),
         LOWER(JSON_VALUE(PASSAGESORTIE, '$.PAIEMENT.RETENUS[0].TYPE'));
```
| SOCIETE | MODE_REGLEMENT | NB_PASSAGES |
|---|---|---|
| ASF | telepeagelibert | 3 |
| ASF | telepeagecardme | 3 |
| ASF | carteaccreditive | 3 |
| ASF | cartemagnetique | 3 |
| ESC | telepeagelibert | 2 |
| ESC | telepeagecardme | 1 |
| ESC | carteaccreditive | 1 |
| ESC | cartemagnetique | 2 |

Lot 6, socle ET (`sql/ksqldb/06-socleet.sql`): the counter referential as the ksqlDB table
`TABLE_VA_PEAGE_BACKOFFICE_SOCLEET_REF_COMPTEURS` holds it. Four counters: ID 3 has two versions
and keeps the last, ID 5 is incomplete and filtered out. The six records, in offset order:
```json
{"ID":1,"R_COMPTEUR_TYPEID":1,"R_FREQUENCEID":1,"R_OPERATIONID":10,"R_MODE_REGLEMENTID":1,"R_TRANSACTION_ETATID":1}
{"ID":2,"R_COMPTEUR_TYPEID":2,"R_FREQUENCEID":1,"R_OPERATIONID":10,"R_MODE_REGLEMENTID":256,"R_TRANSACTION_ETATID":1}
{"ID":3,"R_COMPTEUR_TYPEID":1,"R_FREQUENCEID":2,"R_OPERATIONID":20,"R_MODE_REGLEMENTID":1,"R_TRANSACTION_ETATID":2}
{"ID":4,"R_COMPTEUR_TYPEID":2,"R_FREQUENCEID":2,"R_OPERATIONID":20,"R_MODE_REGLEMENTID":512,"R_TRANSACTION_ETATID":1}
{"ID":5,"R_COMPTEUR_TYPEID":2,"R_FREQUENCEID":null,"R_OPERATIONID":20,"R_MODE_REGLEMENTID":1,"R_TRANSACTION_ETATID":1}
{"ID":3,"R_COMPTEUR_TYPEID":1,"R_FREQUENCEID":2,"R_OPERATIONID":20,"R_MODE_REGLEMENTID":1,"R_TRANSACTION_ETATID":1}
```
```sql
SELECT ID,
       COUNT(*) AS NB_VERSIONS,
       LAST_VALUE(R_COMPTEUR_TYPEID) AS R_COMPTEUR_TYPEID,
       LAST_VALUE(R_OPERATIONID) AS R_OPERATIONID,
       LAST_VALUE(R_MODE_REGLEMENTID) AS R_MODE_REGLEMENTID,
       LAST_VALUE(R_TRANSACTION_ETATID) AS R_TRANSACTION_ETATID
FROM "va.peage.backoffice.socleET.ref_compteur"
WHERE R_COMPTEUR_TYPEID IS NOT NULL AND R_FREQUENCEID IS NOT NULL AND R_OPERATIONID IS NOT NULL
  AND R_MODE_REGLEMENTID IS NOT NULL AND R_TRANSACTION_ETATID IS NOT NULL
GROUP BY ID;
```
| ID | NB_VERSIONS | R_COMPTEUR_TYPEID | R_OPERATIONID | R_MODE_REGLEMENTID | R_TRANSACTION_ETATID |
|---|---|---|---|---|---|
| 1 | 1 | 1 | 10 | 1 | 1 |
| 2 | 1 | 2 | 10 | 256 | 1 |
| 3 | 2 | 1 | 20 | 1 | 1 |
| 4 | 1 | 2 | 20 | 512 | 1 |

The transactions joined to that referential (`VA_PEAGE_BACKOFFICE_SOCLEET_FILTER_REF_COMPTEUR`).
A counting counter is worth 1, a summing one the amount; mode 1 accepts every payment mode.
Joining the raw topic instead of the grouped referential would match each fact against *every*
version of counter 3. One difference remains, and it is why the laboratory runs this lot as a
DataStream job: a Flink regular join re-emits past transactions when the referential changes,
where ksqlDB joins each transaction to the referential as it stood on arrival. Three of the 48
transactions (`DATE_OPERATION` depends on when the seeder ran):
```json
{"R_COMPTEURID":1,"R_MODE_REGLEMENTID":256,"R_OPERATIONID":30,"R_VOIEID":1,"R_GAREID":25004,"R_SOCIETEID":25004,"R_PARTENAIREID":1,"R_TRANSACTION_ETATID":2,"R_TRANSACTION_MODIFICATION_TYPEID":0,"IDT_TRAN_PEA":"TRN-00000","MONTANT_TRANSACTION":2.50,"DATE_OPERATION":1793088407000,"DATE_COMPTABLE":1793088407000}
{"R_COMPTEURID":2,"R_MODE_REGLEMENTID":512,"R_OPERATIONID":10,"R_VOIEID":2,"R_GAREID":25006,"R_SOCIETEID":25006,"R_PARTENAIREID":1,"R_TRANSACTION_ETATID":1,"R_TRANSACTION_MODIFICATION_TYPEID":0,"IDT_TRAN_PEA":"TRN-00001","MONTANT_TRANSACTION":4.61,"DATE_OPERATION":1793088707000,"DATE_COMPTABLE":1793088707000}
{"R_COMPTEURID":3,"R_MODE_REGLEMENTID":1024,"R_OPERATIONID":20,"R_VOIEID":3,"R_GAREID":25021,"R_SOCIETEID":25021,"R_PARTENAIREID":1,"R_TRANSACTION_ETATID":1,"R_TRANSACTION_MODIFICATION_TYPEID":0,"IDT_TRAN_PEA":"TRN-00002","MONTANT_TRANSACTION":6.72,"DATE_OPERATION":1793089007000,"DATE_COMPTABLE":1793089007000}
```
`TRN-00000` has an operation no counter declares, `TRN-00001` pays in mode 512 where counter 2
accepts only 256, `TRN-00002` matches counter 3 as it stands after its update.

WITH compteurs AS (
  SELECT ID,
         LAST_VALUE(R_COMPTEUR_TYPEID) AS R_COMPTEUR_TYPEID,
         LAST_VALUE(R_OPERATIONID) AS R_OPERATIONID,
         LAST_VALUE(R_MODE_REGLEMENTID) AS R_MODE_REGLEMENTID,
         LAST_VALUE(R_TRANSACTION_ETATID) AS R_TRANSACTION_ETATID
  FROM "va.peage.backoffice.socleET.ref_compteur"
  WHERE R_COMPTEUR_TYPEID IS NOT NULL AND R_FREQUENCEID IS NOT NULL AND R_OPERATIONID IS NOT NULL
    AND R_MODE_REGLEMENTID IS NOT NULL AND R_TRANSACTION_ETATID IS NOT NULL
  GROUP BY ID
)
SELECT t.IDT_TRAN_PEA, t.R_GAREID, t.R_MODE_REGLEMENTID, c.ID AS R_COMPTEURID,
       CASE WHEN c.R_COMPTEUR_TYPEID = 1 THEN CAST(1 AS DOUBLE) ELSE t.MONTANT_TRANSACTION END AS VALEUR
FROM "va.peage.backoffice.socleET.etancheite" t
JOIN compteurs c ON t.R_COMPTEURID = c.ID
WHERE t.R_OPERATIONID = c.R_OPERATIONID
  AND t.R_TRANSACTION_ETATID = c.R_TRANSACTION_ETATID
  AND (c.R_MODE_REGLEMENTID = 1 OR t.R_MODE_REGLEMENTID = c.R_MODE_REGLEMENTID);
```
Sixteen rows, among them:

| IDT_TRAN_PEA | R_GAREID | R_MODE_REGLEMENTID | R_COMPTEURID | VALEUR |
|---|---|---|---|---|
| TRN-00002 | 25021 | 1024 | 3 | 1.0 |
| TRN-00005 | 25006 | 1024 | 1 | 1.0 |
| TRN-00010 | 25021 | 512 | 1 | 1.0 |
| TRN-00013 | 25006 | 512 | 4 | 29.93 |
| TRN-00043 | 25005 | 512 | 4 | 3.23 |
| … | | | | |

Lot 7, hourly indicator (`sql/ksqldb/07-08-hourly.sql`): ksqlDB's `WINDOW TUMBLING (SIZE 1 HOURS)`
is a `TUMBLE` table function. The direct reader answers it and buckets on `DATE_OPERATION`, the
epoch milliseconds of the transaction: four or five buckets, the seeded four hours rarely starting
on the hour. It does not group by counter or toll station as the original does — that takes a
table declared with a watermark, see the help page's window lesson.
```sql
SELECT window_start, window_end,
       COUNT(*) AS NB_TRANSACTIONS,
       SUM(MONTANT_TRANSACTION) AS VALEUR
FROM TABLE(TUMBLE(TABLE "va.peage.backoffice.socleET.etancheite", DESCRIPTOR(DATE_OPERATION), INTERVAL '1' HOUR))
GROUP BY window_start, window_end;
```
Seeded at 12:10 UTC, the buckets read:

| window_start | window_end | NB_TRANSACTIONS | VALEUR |
|---|---|---|---|
| 2026-10-27 08:00:00 | 2026-10-27 09:00:00 | 11 | 143.55 |
| 2026-10-27 09:00:00 | 2026-10-27 10:00:00 | 12 | 207.78 |
| 2026-10-27 10:00:00 | 2026-10-27 11:00:00 | 12 | 211.62 |
| 2026-10-27 11:00:00 | 2026-10-27 12:00:00 | 12 | 215.46 |
| 2026-10-27 12:00:00 | 2026-10-27 13:00:00 | 1 | 11.67 |

Lot 8, classification quality (`sql/ksqldb/07-08-quality.sql`): the lanes where more than 30 % of
at least 20 tariffed passages were classified manually or by FC. One row, lane `25004-12`; lane
`25006-03` stays under the threshold, lane `25021-07` has too few passages. The original's
`WINDOW SESSION (60 MINUTES)` is left out — the direct reader would approximate it as a tumbling
window, and the seeded flows all fall inside one session anyway. A flow, reduced to the fields the
query reads (the record also carries `BUSINESSFILTER`, `BUSINESSRULE`, `BUSINESSCYCLOPE`,
`RESPONSE` and `TECHNICALFILTER`):
```json
{
  "FLOW": {
    "STATION": "25004", "LANE": "12", "CREATIONDATE": "2026-10-25T09:00:00.000000Z", "CALLERMODE": "VA",
    "CLASS": [{"CLASSTYPE": "TarifiedClass", "CLASSORIGIN": "Manual"},
              {"CLASSTYPE": "DetectedClass", "CLASSORIGIN": "Auto"}]
  },
  "STATIONLANE": "25004-12"
}
```
| Lane | Flows kept | Manual or FC | Ratio | Outcome |
|---|---|---|---|---|
| `25004-12` | 25 | 15 | 0.60 | Alarm |
| `25006-03` | 25 (+ 4 `TD`, 4 `PostDTO` excluded) | 4 | 0.16 | Under the threshold |
| `25021-07` | 12 | 12 | 1.00 | Fewer than 20 passages |
```sql
WITH flows AS (
  SELECT STATIONLANE,
         CASE WHEN JSON_QUERY(FLOW, '$.CLASS[*].CLASSORIGIN') LIKE '%"Manual"%'
                OR JSON_QUERY(FLOW, '$.CLASS[*].CLASSORIGIN') LIKE '%"FC"%'
              THEN 1 ELSE 0 END AS WRONG_ORIGIN
  FROM "VA_PEAGE_CLASSIFICATION_FLOWS_EXTEND"
  WHERE JSON_QUERY(FLOW, '$.CLASS[*].CLASSTYPE') LIKE '%"TarifiedClass"%'
    AND JSON_QUERY(FLOW, '$.CLASS[*].CLASSTYPE') NOT LIKE '%"PostDTO"%'
    AND JSON_VALUE(FLOW, '$.CALLERMODE') <> 'TD'
)
SELECT STATIONLANE,
       COUNT(*) AS NBPASSAGE,
       SUM(WRONG_ORIGIN) AS NBCLASSIFWRONGORIGIN,
       SUM(WRONG_ORIGIN) / CAST(COUNT(*) AS DOUBLE) AS RATIO
FROM flows
GROUP BY STATIONLANE
HAVING SUM(WRONG_ORIGIN) / CAST(COUNT(*) AS DOUBLE) > 0.3 AND COUNT(*) >= 20;
```
| STATIONLANE | NBPASSAGE | NBCLASSIFWRONGORIGIN | RATIO |
|---|---|---|---|
| 25004-12 | 25 | 15 | 0.6 |

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
