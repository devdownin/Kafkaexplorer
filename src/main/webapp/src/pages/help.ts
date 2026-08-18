// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors

/**
 * Contenu pédagogique de la page d'aide — parcours SQL, matrice des moteurs,
 * anatomie des fenêtres, guide d'erreurs.
 *
 * Tout est ici plutôt que dans le JSX pour deux raisons. La première est
 * ordinaire : une fonction pure se teste sans monter la page. La seconde l'est
 * moins — une page d'aide ment vite. Les exemples doivent rester exécutables
 * (statements réellement autorisés par `FlinkSqlService.executeSql`, tables
 * réellement semées par `setup-demo.sh`, noms réellement produits par
 * `DdlGeneratorService.toTableName`), et un test peut vérifier ça ; une suite
 * de littéraux JSX, non.
 *
 * Les affirmations sur les moteurs viennent du code, pas de la documentation
 * Flink : ce que le lecteur direct sait faire est décrit par `kafkaDirectSelect`
 * / `kafkaAggregateSelect` / `kafkaWindowSelect`, et le partage entre « rejeté »
 * et « replié sur le lecteur direct » par `SqlErrorClassifier` +
 * `rejectIfUserError`.
 */

/** Étape du parcours guidé — l'ordre des groupes est l'ordre d'apprentissage. */
export type LessonGroup =
  | 'Read a topic'
  | 'Filter and count'
  | 'Group by time'
  | 'Structured payloads'
  | 'What only Flink can do'
  | 'Beyond SELECT';

export const LESSON_GROUPS: LessonGroup[] = [
  'Read a topic',
  'Filter and count',
  'Group by time',
  'Structured payloads',
  'What only Flink can do',
  'Beyond SELECT',
];

export interface Lesson {
  id: string;
  group: LessonGroup;
  title: string;
  /** Ce que la requête cherche à obtenir, en une phrase, sans jargon SQL. */
  goal: string;
  sql: string;
  /** Ce qu'il faut avoir compris en lisant la requête. Deux à quatre points. */
  reading: string[];
  /** L'erreur que tout le monde fait à cette étape. */
  pitfall?: string;
  /**
   * `FLINK` : le lecteur direct ne sait pas l'exécuter, donc la requête échoue
   * si le planner est indisponible. `ANY` : les deux moteurs répondent.
   */
  engine: 'ANY' | 'FLINK';
  /** `false` pour ce que le bouton Run refuse (INSERT INTO → mode Job). */
  runnable: boolean;
  keywords: string[];
}

/**
 * Les exemples portent sur les topics de `setup-demo.sh` : une requête qu'on
 * peut ouvrir et lancer enseigne plus qu'une requête sur `orders_raw`, table
 * qui n'existe nulle part. L'ancienne page en donnait cinq, dont trois
 * inexécutables telles quelles (`orders_raw`, `users`, `xml_topic`).
 */
export const LESSONS: Lesson[] = [
  {
    id: 'read',
    group: 'Read a topic',
    title: 'Read the first messages',
    goal: 'See what a topic actually contains, without knowing its schema.',
    sql: 'SELECT * FROM demo_orders_1_received LIMIT 20',
    reading: [
      'You never create the table: the app samples the topic, infers a schema, and runs the CREATE TABLE for you the first time you name it.',
      'The table name is the topic name with dots and dashes turned into underscores — demo.orders.1.received becomes demo_orders_1_received.',
      'LIMIT is not decoration. A SELECT runs against a stream, so it stops on the row limit or on the timeout, never on "end of table".',
    ],
    pitfall:
      'Asking for more rows than the topic holds: the query waits for messages that never come and ends on "Query timed out after 10000ms". Lower the limit, or read a busier topic.',
    engine: 'ANY',
    runnable: true,
    keywords: ['select', 'star', 'limit', 'first query', 'auto-registration'],
  },
  {
    id: 'columns',
    group: 'Read a topic',
    title: 'Pick columns, and use the two you did not declare',
    goal: 'Read only the fields you care about, plus the record timestamp.',
    sql: `SELECT
  id,
  state,
  amount,
  event_time
FROM demo_orders_1_received
LIMIT 20`,
    reading: [
      'Every generated table carries two extra columns: event_time (the Kafka record timestamp, read as metadata) and proc_time (the moment the query reads the row).',
      'event_time is what windows group by — it is the one column you get for free on every topic, whatever the payload looks like.',
      'A field named event_time inside the payload wins: the generated DDL only adds the metadata column when the schema does not already define one.',
    ],
    engine: 'ANY',
    runnable: true,
    keywords: ['projection', 'columns', 'event_time', 'proc_time', 'metadata'],
  },
  {
    id: 'filter',
    group: 'Filter and count',
    title: 'Filter on a value',
    goal: 'Keep only the records in one state.',
    sql: `SELECT id, state, amount
FROM demo_orders_1_received
WHERE state = 'RECEIVED'
LIMIT 50`,
    reading: [
      'On the Flink engine the whole WHERE clause applies — comparisons, LIKE, IN, OR, NOT.',
      'The fallback reader only understands column = \'value\' joined by AND. Anything else is reported in the result warnings rather than applied silently.',
      'That warning is the point: a scan that dropped half your predicate must not look like a filtered result.',
    ],
    pitfall:
      'Reading rows that do not match your WHERE and assuming the data is wrong. Check the engine badge and the warnings above the grid first.',
    engine: 'ANY',
    runnable: true,
    keywords: ['where', 'filter', 'predicate', 'warnings', 'fallback'],
  },
  {
    id: 'aggregate',
    group: 'Filter and count',
    title: 'Count per category',
    goal: 'How many orders of each type, in one row per type.',
    sql: `SELECT
  type,
  COUNT(*) AS order_count,
  AVG(amount) AS avg_amount
FROM demo_orders_1_received
GROUP BY type`,
    reading: [
      'Alias every aggregate. The fallback reader matches aggregates by their alias, so COUNT(*) without AS is an expression it cannot name — and the Metrics module reads the column named metric_value for the same reason.',
      'COUNT / SUM / AVG / MIN / MAX with an optional GROUP BY are the aggregates both engines compute.',
      'The fallback reader scans up to 100 000 messages for an aggregate, so the number is exact for that window and not for the topic if it is larger.',
    ],
    pitfall:
      'An aggregate with no window over a live stream is a changelog: on the Flink engine every new message updates the result, and you see each update as a row. Group by a time window when you want one row per bucket.',
    engine: 'ANY',
    runnable: true,
    keywords: ['count', 'sum', 'avg', 'group by', 'aggregate', 'alias', 'metric_value'],
  },
  {
    id: 'tumble',
    group: 'Group by time',
    title: 'One bucket per five minutes',
    goal: 'Turn a stream of readings into a time series you can chart.',
    sql: `SELECT
  window_start,
  window_end,
  AVG(temperature) AS avg_temp,
  MAX(temperature) AS max_temp
FROM TABLE(
  TUMBLE(TABLE demo_iot_sensors, DESCRIPTOR(event_time), INTERVAL '5' MINUTES)
)
GROUP BY window_start, window_end`,
    reading: [
      'The windowing function wraps the table — FROM TABLE(TUMBLE(TABLE t, …)) — it is not a clause you append.',
      'DESCRIPTOR names the time column. window_start and window_end are produced by the window, so you both select and group by them.',
      'The demo sensors spread their readings over two hours, which is what makes several buckets appear. On a topic where everything landed in the same minute, one bucket is the correct answer.',
    ],
    pitfall:
      'Pointing DESCRIPTOR at a column that is not a time. The fallback reader resolves the column as an ISO-8601 or epoch field and drops back to the Kafka record timestamp; the Flink planner just rejects it.',
    engine: 'ANY',
    runnable: true,
    keywords: ['tumble', 'window', 'descriptor', 'interval', 'time series', 'window_start'],
  },
  {
    id: 'hop',
    group: 'Group by time',
    title: 'A sliding window',
    goal: 'A 15-minute average recomputed every 5 minutes.',
    sql: `SELECT
  window_start,
  window_end,
  AVG(temperature) AS avg_temp
FROM TABLE(
  HOP(TABLE demo_iot_sensors, DESCRIPTOR(event_time), INTERVAL '5' MINUTES, INTERVAL '15' MINUTES)
)
GROUP BY window_start, window_end`,
    reading: [
      'HOP takes the slide first and the size second — the opposite of how you say it out loud.',
      'Overlapping windows mean a record is counted in several buckets. That is the intent, not a bug.',
      'CUMULATE and SESSION follow the same shape; SESSION additionally requires a PARTITION BY key.',
    ],
    pitfall:
      'HOP, CUMULATE and SESSION are exact on the Flink engine only. If a query falls back, they are approximated as a tumbling window of the same width, and the result says so in its warnings.',
    engine: 'ANY',
    runnable: true,
    keywords: ['hop', 'sliding', 'cumulate', 'session', 'window', 'approximation'],
  },
  {
    id: 'nested',
    group: 'Structured payloads',
    title: 'Reach into a nested document',
    goal: 'Read a field buried three levels down in the JSON.',
    sql: `SELECT
  id,
  status,
  JSON_VALUE(customer, '$.profile.name') AS customer_name,
  JSON_VALUE(customer, '$.profile.segment') AS segment
FROM demo_orders_nested
LIMIT 20`,
    reading: [
      'Schema inference is one level deep: a nested object or an array becomes a STRING column holding its JSON. So customer is text, not a struct.',
      'JSON_VALUE walks that text with a JSONPath expression — which is why the path starts at $ and not at the column.',
      'Clicking a cell in the result grid opens the row beside the grid, where a sub-document is indented rather than shown as one long line.',
    ],
    pitfall:
      'Writing customer.profile.name. The column is a STRING, so the planner reports an unknown identifier — and a rejected query is not retried on the other engine.',
    engine: 'FLINK',
    runnable: true,
    keywords: ['json', 'nested', 'json_value', 'jsonpath', 'struct', 'string column'],
  },
  {
    id: 'xml',
    group: 'Structured payloads',
    title: 'Query an XML topic',
    goal: 'Pull two values out of an XML document with XPath.',
    sql: `SELECT
  XmlExtract(raw_value, '/Order/@id') AS order_id,
  XmlExtract(raw_value, '/Order/Customer') AS customer,
  XmlExtract(raw_value, '/Order/Amount') AS amount
FROM demo_orders_xml
LIMIT 20`,
    reading: [
      'Flink has no XML format, so an XML topic is registered with a single raw_value STRING column holding the document.',
      'XmlExtract(document, xpath) is the UDF this app registers. It compiles and caches each expression, and returns null when the path matches nothing.',
      'External entities are disabled in that parser — an XPath over a hostile document cannot reach the filesystem.',
    ],
    pitfall:
      'Expecting columns named after the XML elements. There is exactly one column, raw_value; every field you want is an XmlExtract call.',
    engine: 'FLINK',
    runnable: true,
    keywords: ['xml', 'xpath', 'xmlextract', 'udf', 'raw_value'],
  },
  {
    id: 'dedup',
    group: 'What only Flink can do',
    title: 'Keep the latest record per key',
    goal: 'One row per order — the most recent state it reached.',
    sql: `SELECT id, state, event_time
FROM (
  SELECT *, ROW_NUMBER() OVER (
    PARTITION BY id ORDER BY event_time DESC
  ) AS row_num
  FROM demo_orders_1_received
)
WHERE row_num = 1`,
    reading: [
      'This is the standard Flink deduplication pattern: number the rows per key, keep number one.',
      'A sub-query is Flink-only. The fallback reader matches a table name out of FROM and would answer with rows that ignore the nesting entirely.',
      'ORDER BY event_time ASC keeps the first record per key instead of the last.',
    ],
    pitfall:
      'If the Flink planner is unavailable, this query fails rather than returning approximate rows. That is deliberate — wrong rows are worse than a refusal.',
    engine: 'FLINK',
    runnable: true,
    keywords: ['deduplicate', 'row_number', 'over', 'partition by', 'subquery', 'latest'],
  },
  {
    id: 'join',
    group: 'What only Flink can do',
    title: 'Join two topics',
    goal: 'Attach customer reference data to each order.',
    sql: `SELECT
  o.id,
  o.amount,
  c.name,
  c.segment
FROM demo_orders_1_received AS o
JOIN demo_customers AS c
  ON o.customer_id = c.customer_id
LIMIT 20`,
    reading: [
      'Auto-registration registers one table — the first one named after FROM. Read each side once on its own before joining them, or the second table comes back as "Object not found".',
      'A streaming join keeps both sides in state, so it is unbounded work: keep a LIMIT on it while exploring.',
      'A cross join is refused by the validator unless the deployment enables it, because a cartesian product over two streams has no end.',
    ],
    pitfall:
      'Joining a topic you have never opened. Registration happens on a plain SELECT, so run SELECT * FROM demo_customers LIMIT 1 first.',
    engine: 'FLINK',
    runnable: true,
    keywords: ['join', 'two topics', 'enrich', 'reference data', 'registration'],
  },
  {
    id: 'cte',
    group: 'What only Flink can do',
    title: 'Name an intermediate result',
    goal: 'Read a long query top to bottom instead of inside out.',
    sql: `WITH high_value AS (
  SELECT id, amount, customer_id
  FROM demo_orders_1_received
  WHERE amount > 500
)
SELECT customer_id, COUNT(*) AS orders, SUM(amount) AS total
FROM high_value
GROUP BY customer_id`,
    reading: [
      'A common table expression is accepted: the guard classifies the statement past the leading WITH chain, so this counts as a SELECT.',
      'It never falls back to the direct reader, for the same reason as a sub-query: that reader would read the source topic and ignore the WITH clause.',
      'amount > 500 is a comparison, which is another reason this one belongs to the Flink engine.',
    ],
    engine: 'FLINK',
    runnable: true,
    keywords: ['with', 'cte', 'common table expression', 'readable'],
  },
  {
    id: 'ddl',
    group: 'Beyond SELECT',
    title: 'Declare a table yourself',
    goal: 'Take control of the schema instead of accepting the inferred one.',
    sql: `CREATE TABLE IF NOT EXISTS orders_typed (
  \`id\` STRING,
  \`amount\` DOUBLE,
  \`state\` STRING,
  \`event_time\` TIMESTAMP(3) METADATA FROM 'timestamp',
  \`proc_time\` AS PROCTIME()
) WITH (
  'connector' = 'kafka',
  'topic' = 'demo.orders.1.received',
  'properties.bootstrap.servers' = 'localhost:9092',
  'properties.group.id' = 'flink_orders_typed',
  'value.format' = 'json',
  'json.ignore-parse-errors' = 'true',
  'scan.startup.mode' = 'earliest-offset'
)`,
    reading: [
      'CREATE TABLE always goes through the Flink planner, never the fallback reader.',
      'The topic keeps its real name in the connector properties; only the SQL identifier is sanitized.',
      'Any DDL the app shows you is masked: passwords and the Confluent SASL secret are replaced before the string leaves the server, so paste your own credentials back in when you copy one.',
    ],
    pitfall:
      'json.ignore-parse-errors is what keeps one malformed message from failing the whole read. Dropping it turns a poison record into a failed query.',
    engine: 'FLINK',
    runnable: true,
    keywords: ['create table', 'ddl', 'connector', 'schema', 'startup mode'],
  },
  {
    id: 'explain',
    group: 'Beyond SELECT',
    title: 'Ask what the planner will do',
    goal: 'Check a query plan without reading a single message.',
    sql: `EXPLAIN SELECT
  type,
  COUNT(*) AS order_count
FROM demo_orders_1_received
GROUP BY type`,
    reading: [
      'EXPLAIN runs on the Flink planner and touches no Kafka data, so it is the cheapest way to check that a query resolves.',
      'It is also how the validator enforces its rules: cross joins and system tables are detected in the plan, not in the text.',
      'The Validate button in the editor uses the same path — a syntax error comes back with its line and column before anything is read.',
    ],
    engine: 'FLINK',
    runnable: true,
    keywords: ['explain', 'plan', 'validate', 'dry run'],
  },
  {
    id: 'insert',
    group: 'Beyond SELECT',
    title: 'Write a continuous stream',
    goal: 'Keep a derived topic up to date, forever.',
    sql: `INSERT INTO orders_enriched
SELECT
  o.id,
  o.amount,
  c.segment
FROM demo_orders_1_received AS o
JOIN demo_customers AS c
  ON o.customer_id = c.customer_id`,
    reading: [
      'INSERT INTO is not run by the editor: it is submitted as a Flink job through Job mode, and it keeps running after the page is closed.',
      'The target table must exist first — declare it with CREATE TABLE, pointing at the topic you want written.',
      'Running jobs, their Flink job id and their history are on the Dashboard; the Lineage page draws what each job reads and writes.',
    ],
    pitfall:
      'Pressing Run on an INSERT. The answer is "INSERT INTO statements must be submitted via Flink Job mode" — a routing rule, not a syntax complaint.',
    engine: 'FLINK',
    runnable: false,
    keywords: ['insert into', 'job', 'continuous', 'sink', 'pipeline'],
  },
];

/** Ce que chaque moteur sait faire. `note` dit ce qui arrive en cas de repli. */
export interface EngineCapability {
  feature: string;
  flink: boolean;
  /** `true` pris en charge, `'partial'` approximé ou restreint, `false` hors de portée. */
  direct: boolean | 'partial';
  note: string;
}

export const ENGINE_MATRIX: EngineCapability[] = [
  {
    feature: 'SELECT columns, LIMIT',
    flink: true,
    direct: true,
    note: 'Both engines project and bound the read.',
  },
  {
    feature: "WHERE col = 'value'",
    flink: true,
    direct: true,
    note: 'The direct reader compares case-insensitively and understands dot paths into the payload.',
  },
  {
    feature: 'WHERE with >, LIKE, IN, OR, NOT',
    flink: true,
    direct: false,
    note: 'The direct reader lists the fragment it could not apply in the result warnings.',
  },
  {
    feature: 'COUNT / SUM / AVG / MIN / MAX, GROUP BY',
    flink: true,
    direct: true,
    note: 'The direct reader needs an alias on each aggregate and scans at most 100 000 messages.',
  },
  {
    feature: 'TUMBLE window',
    flink: true,
    direct: true,
    note: 'The direct reader buckets by the time column, falling back to the Kafka record timestamp.',
  },
  {
    feature: 'HOP / CUMULATE / SESSION windows',
    flink: true,
    direct: 'partial',
    note: 'Approximated as a tumbling window of the same width, and the warning says so.',
  },
  {
    feature: 'ORDER BY',
    flink: true,
    direct: false,
    note: 'The direct reader returns rows in scan order. Sort the grid by clicking a column header.',
  },
  {
    feature: 'JOIN, sub-queries, WITH',
    flink: true,
    direct: false,
    note: 'Never approximated: if the planner cannot run it, the query is refused rather than answered wrongly.',
  },
  {
    feature: 'CREATE TABLE, EXPLAIN',
    flink: true,
    direct: false,
    note: 'Always the planner — these never touch the fallback path.',
  },
  {
    feature: 'INSERT INTO',
    flink: true,
    direct: false,
    note: 'Submitted as a continuous Flink job through Job mode, not run inline.',
  },
];

/** Anatomie d'un appel de fenêtre — chaque fragment avec ce qu'il décide. */
export interface WindowPart {
  fragment: string;
  meaning: string;
}

export const WINDOW_ANATOMY: WindowPart[] = [
  { fragment: 'FROM TABLE( … )', meaning: 'A windowing function returns a table, so the whole call sits where the table name would.' },
  { fragment: 'TUMBLE(TABLE demo_iot_sensors', meaning: 'The source table, introduced by the keyword TABLE a second time.' },
  { fragment: 'DESCRIPTOR(event_time)', meaning: 'Which column carries the time. Almost always event_time.' },
  { fragment: "INTERVAL '5' MINUTES", meaning: 'The bucket width. Singular and plural units are both accepted.' },
  { fragment: 'GROUP BY window_start, window_end', meaning: 'The columns the window adds. Select them to see the bucket each row belongs to.' },
];

export interface WindowKindDoc {
  name: string;
  signature: string;
  use: string;
  caveat?: string;
}

export const WINDOW_KINDS: WindowKindDoc[] = [
  {
    name: 'TUMBLE',
    signature: "TUMBLE(TABLE t, DESCRIPTOR(ts), INTERVAL '5' MINUTES)",
    use: 'Fixed, non-overlapping buckets. One row per bucket — the shape you want for a chart or a metric.',
  },
  {
    name: 'HOP',
    signature: "HOP(TABLE t, DESCRIPTOR(ts), INTERVAL '1' MINUTE, INTERVAL '5' MINUTES)",
    use: 'A five-minute view recomputed every minute. Slide first, size second.',
    caveat: 'Approximated as a 5-minute TUMBLE if the query falls back to the direct reader.',
  },
  {
    name: 'CUMULATE',
    signature: "CUMULATE(TABLE t, DESCRIPTOR(ts), INTERVAL '1' MINUTE, INTERVAL '1' HOUR)",
    use: 'A running total that grows through the hour and resets on the hour.',
    caveat: 'Approximated as a 1-hour TUMBLE on the direct reader — the intermediate steps disappear.',
  },
  {
    name: 'SESSION',
    signature: "SESSION(TABLE t PARTITION BY user_id, DESCRIPTOR(ts), INTERVAL '10' MINUTES)",
    use: 'Activity separated by gaps: a window closes after ten idle minutes for that key.',
    caveat: 'Flink requires the PARTITION BY key. Approximated as a fixed window on the direct reader.',
  },
];

/** Erreur telle qu'elle s'affiche → ce qu'elle veut dire → quoi faire. */
export interface ErrorGuideEntry {
  message: string;
  meaning: string;
  fix: string;
}

export const ERROR_GUIDE: ErrorGuideEntry[] = [
  {
    message: 'Object \'orders\' not found',
    meaning: 'No registered table and no topic whose sanitized name matches what you typed.',
    fix: 'Take the exact name from the Schema Browser. Dots and dashes in a topic name become underscores.',
  },
  {
    message: 'Column \'orderId\' not found',
    meaning: 'The inferred schema has no such field — often a case difference, or a field that only some messages carry.',
    fix: 'Expand the table in the Schema Browser. Inference samples a few messages, so a rare field may be missing from it.',
  },
  {
    message: 'Query timed out after 10000ms',
    meaning: 'A streaming SELECT waits for rows that have not arrived: usually you asked for more rows than the topic holds.',
    fix: 'Lower the row limit, tighten the WHERE clause, or switch the read mode to Latest to stop replaying history.',
  },
  {
    message: 'Only SELECT, EXPLAIN and CREATE TABLE statements are allowed.',
    meaning: 'The statement whitelist. UPDATE, DELETE, DROP and ALTER have no path through this app.',
    fix: 'For INSERT INTO, use Job mode. For anything else, there is no way round it — by design.',
  },
  {
    message: 'INSERT INTO statements must be submitted via Flink Job mode.',
    meaning: 'Routing, not syntax: an INSERT is a continuous job, not a query with an answer.',
    fix: 'Submit it as a job; follow it from the Dashboard.',
  },
  {
    message: 'Cross joins are not allowed in this environment.',
    meaning: 'The plan contains a cartesian product — often a missing ON clause rather than a deliberate CROSS JOIN.',
    fix: 'Add the join condition. A deployment can allow cross joins in its configuration, but it is off by default.',
  },
  {
    message: 'The direct engine applied only the "column = \'value\'" conditions…',
    meaning: 'Not an error: the query ran on the fallback reader, which dropped part of your WHERE clause.',
    fix: 'Rows that do not match may be in the result. Re-run when the planner is available, or narrow the query to equality.',
  },
  {
    message: 'This query uses a common table expression, which only the Flink engine can run.',
    meaning: 'The planner did not answer and the WITH clause cannot be approximated.',
    fix: 'Nothing to change in the SQL — the engine is the problem. Check the broker and the Flink runtime on the Dashboard.',
  },
  {
    message: 'Cannot reach Kafka broker',
    meaning: 'Registration could not list the topics, so the query never started.',
    fix: 'Check the bootstrap servers in Settings, and the connection badge in the header.',
  },
];

/** Ce que l'éditeur fait et qu'on ne devine pas en le regardant. */
export interface EditorTip {
  title: string;
  body: string;
}

export const EDITOR_TIPS: EditorTip[] = [
  {
    title: 'Run sends one statement',
    body: 'With no selection, Run (⌘↵) executes the statement the cursor is in — the toolbar says which one, as "Statement 2/3". Run all (⌘⇧↵) executes them in order and stops at the first failure, keeping what each one returned: the list under the results brings any of them back into the grid. Select text to run exactly that — a selection spanning several statements runs them all.',
  },
  {
    title: 'Nothing overwrites the tab you are writing in',
    body: 'Clicking a topic, a saved query, a history entry or the DDL preview fills an empty tab and opens a new one otherwise. Tabs survive a reload.',
  },
  {
    title: 'The error panel keeps the raw text',
    body: 'A failure is shown as a title, a hint and — when the engine cites one — a line and column, with a marker in the editor and a jump button. The engine\'s own message is one click away under "Show raw error".',
  },
  {
    title: 'Autocomplete covers topics too',
    body: 'Registered Flink tables and raw Kafka topics are both suggested; topics appear under the table name you would use. Column suggestions are scoped to the tables the query actually cites.',
  },
  {
    title: 'The window assistant writes real SQL',
    body: 'It emits genuinely different SQL per window kind, pre-fills the time column from the loaded schema, states its caveats and inserts at the cursor rather than replacing the tab.',
  },
  {
    title: 'Stop aborts the request, always',
    body: 'A Flink run is cancelled at the job. A fallback scan has no job to cancel, so the request is aborted and the server finishes its in-flight read — the button says which of the two happened.',
  },
  {
    title: 'A result is a link and a file',
    body: 'Link copies a replayable URL carrying the SQL. CSV exports what is displayed, in the order it is displayed, quoted per RFC 4180.',
  },
];

export interface FormatDoc {
  name: string;
  tone: 'success' | 'primary' | 'secondary' | 'neutral';
  detection: string;
  columns: string;
}

export const FORMATS: FormatDoc[] = [
  {
    name: 'JSON',
    tone: 'success',
    detection: 'A sampled message starting with { or [.',
    columns: 'One column per top-level field. Nested objects and arrays become STRING columns holding their JSON.',
  },
  {
    name: 'XML',
    tone: 'primary',
    detection: 'A sampled message starting with <.',
    columns: 'A single raw_value STRING. Use XmlExtract with an XPath to pull fields out.',
  },
  {
    name: 'AVRO',
    tone: 'secondary',
    detection: 'A subject registered for the topic in the Schema Registry.',
    columns: 'Columns from the registered schema, decoded through avro-confluent.',
  },
  {
    name: 'Anything else',
    tone: 'neutral',
    detection: 'No schema could be inferred — plain text, an empty topic, or binary.',
    columns: 'No Flink table is registered. The direct reader answers with a single raw_value column.',
  },
];

export interface Shortcut {
  key: string;
  desc: string;
  where: string;
}

/**
 * Uniquement des raccourcis qui existent : la ligne « Ctrl + S — Save query
 * (coming soon) » annonçait une touche qui ne faisait rien.
 */
export const SHORTCUTS: Shortcut[] = [
  { key: '⌘ / Ctrl + K', desc: 'Open the command palette — pages, topics, tables, or trace a key', where: 'Global' },
  { key: '⌘ / Ctrl + Enter', desc: 'Run the current statement (or just the selection)', where: 'SQL Editor' },
  { key: 'Esc', desc: 'Close a modal, or deselect the focused node', where: 'Global · Graphs' },
  { key: '/', desc: 'Focus the search field', where: 'Topic Explorer' },
  { key: 'j / k', desc: 'Walk up and down the hits', where: 'Topic Explorer' },
  { key: 'Scroll / drag', desc: 'Zoom and pan — pointer or touch', where: 'Lineage · Stream Flow' },
  { key: '↑ ↓ ← →', desc: 'Pan the graph (hold Shift for larger steps)', where: 'Lineage · Stream Flow' },
  { key: '+ / −', desc: 'Zoom the graph in and out', where: 'Lineage · Stream Flow' },
  { key: '0', desc: 'Fit the graph back into view', where: 'Lineage · Stream Flow' },
  { key: 'Tab, then Enter', desc: 'Move between graph nodes and open the focused one', where: 'Lineage · Stream Flow' },
];

/**
 * Nom de table Flink pour un topic Kafka.
 *
 * Reproduit `DdlGeneratorService.toTableName` : points et tirets deviennent des
 * soulignés. C'est la règle qui explique le plus d'échecs « Object not found »,
 * donc la page la rend manipulable plutôt que de l'énoncer.
 */
export function topicToTable(topic: string): string {
  return topic.trim().replace(/[.-]/g, '_');
}

/** Lien « ouvrir dans l'éditeur » — un seul encodage, celui que `readSqlParam` défait. */
export function editorLink(sql: string): string {
  return `/query?sql=${encodeURIComponent(sql)}`;
}

/**
 * Filtre du parcours : tous les mots doivent matcher, sur le titre, l'objectif,
 * le SQL ou les mots-clés. Chercher « window » doit ramener la leçon TUMBLE même
 * si le mot n'est que dans le SQL.
 */
export function filterLessons(lessons: Lesson[], query: string): Lesson[] {
  const terms = query.toLowerCase().split(/\s+/).filter(Boolean);
  if (terms.length === 0) return lessons;
  return lessons.filter((lesson) => {
    const haystack = [
      lesson.title,
      lesson.goal,
      lesson.sql,
      lesson.group,
      ...lesson.keywords,
    ].join(' ').toLowerCase();
    return terms.every((term) => haystack.includes(term));
  });
}

/** Regroupe pour l'affichage, dans l'ordre pédagogique, sans groupe vide. */
export function groupLessons(lessons: Lesson[]): { group: LessonGroup; items: Lesson[] }[] {
  return LESSON_GROUPS
    .map((group) => ({ group, items: lessons.filter((l) => l.group === group) }))
    .filter((section) => section.items.length > 0);
}

export interface NumberedSection {
  group: LessonGroup;
  items: { lesson: Lesson; step: number }[];
}

/**
 * Numérotation continue à travers les groupes : c'est un parcours, pas six
 * listes qui repartent de 1. Calculée ici plutôt que dans le rendu — un
 * compteur incrémenté pendant le `map` d'un composant est précisément ce que
 * la règle d'immutabilité de React Compiler refuse.
 */
export function numberSections(lessons: Lesson[]): NumberedSection[] {
  let step = 0;
  return groupLessons(lessons).map(({ group, items }) => ({
    group,
    items: items.map((lesson) => {
      step += 1;
      return { lesson, step };
    }),
  }));
}
