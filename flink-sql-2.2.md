# Flink SQL — Document de Référence Complet
## Apache Flink 2.2 (décembre 2025)

---

## Table des matières

1. [Vue d'ensemble](#1-vue-densemble)
2. [Architecture et moteur SQL](#2-architecture-et-moteur-sql)
3. [Types de données](#3-types-de-données)
4. [DDL — Data Definition Language](#4-ddl--data-definition-language)
5. [DML — Data Manipulation Language](#5-dml--data-manipulation-language)
6. [Requêtes SELECT et opérations de lecture](#6-requêtes-select-et-opérations-de-lecture)
7. [Fenêtrage (Windowing)](#7-fenêtrage-windowing)
8. [Jointures](#8-jointures)
9. [Agrégations](#9-agrégations)
10. [Pattern Matching (MATCH_RECOGNIZE)](#10-pattern-matching-match_recognize)
11. [Fonctions intégrées](#11-fonctions-intégrées)
12. [Fonctions définies par l'utilisateur (UDF)](#12-fonctions-définies-par-lutilisateur-udf)
13. [Catalogues et métadonnées](#13-catalogues-et-métadonnées)
14. [Materialized Tables](#14-materialized-tables)
15. [IA et inférence de modèles](#15-ia-et-inférence-de-modèles)
16. [Process Table Functions (PTF)](#16-process-table-functions-ptf)
17. [Connecteurs (Connectors)](#17-connecteurs-connectors)
18. [Gestion de l'état (State Management)](#18-gestion-de-létat-state-management)
19. [Modes d'exécution et configuration](#19-modes-dexécution-et-configuration)
20. [SQL Client et SQL Gateway](#20-sql-client-et-sql-gateway)
21. [Optimisations et performances](#21-optimisations-et-performances)
22. [Limites et restrictions notables](#22-limites-et-restrictions-notables)
23. [Flink Embarqué (Embedded / In-Process)](#23-flink-embarqué-embedded--in-process)

---

## 1. Vue d'ensemble

Apache Flink 2.2 est la dernière version majeure (décembre 2025) du moteur de traitement de flux de données en temps réel. Flink SQL est le niveau d'API le plus haut proposé par Flink. Il permet d'exprimer des pipelines de traitement de données — sur flux infinis ou ensembles bornés — via le langage SQL standard, sans écrire de code Java ou Python.

### Principes fondamentaux

- **Conformité ANSI SQL 2011** : Flink SQL est basé sur Apache Calcite et implémente le standard SQL ANSI 2011, avec des extensions spécifiques au traitement de flux.
- **Unification batch et streaming** : un même SQL peut s'exécuter en mode batch (données bornées) ou en mode streaming (flux infinis) selon la configuration.
- **Tables dynamiques** : le concept central de Flink SQL. Une table dynamique représente un flux de données changeant dans le temps. Les requêtes SQL sur des tables dynamiques produisent de nouvelles tables dynamiques de manière continue.
- **Changelog streams** : en interne, Flink représente les mutations (`+I`, `-U`, `+U`, `-D`) comme un flux de changements (changelog), permettant les mises à jour et suppressions dans un contexte streaming.
- **Java 17/21 requis** : depuis Flink 2.0, Java 8 n'est plus supporté. Java 17 est la version recommandée par défaut, Java 21 est officiellement supporté.

### Nouveautés clés de la branche 2.x (2.0 → 2.2)

| Version | Apports majeurs |
|---------|----------------|
| **2.0** | Modèle d'exécution asynchrone, state backend disaggrégé (ForSt), QUALIFY clause, C-style escape strings, suppression du DataSet API |
| **2.1** | Process Table Functions (PTF), VARIANT type, DeltaJoin, MultiJoin, ML_PREDICT TVF, AI Model DDL, Keyed State connector |
| **2.2** | VECTOR_SEARCH TVF, Table API model inference, FRESHNESS optionnel pour Materialized Tables, MaterializedTableEnricher, rate limiting connectors, DISTRIBUTED BY pour Materialized Tables |

---

## 2. Architecture et moteur SQL

### Pipeline de traitement SQL

```
SQL Text
   │
   ▼
Parser (Apache Calcite)
   │
   ▼
Validator (type checking, catalog lookup)
   │
   ▼
Logical Plan (RelNode tree)
   │
   ▼
Optimizer (rules-based + cost-based)
   │   ├── Predicate pushdown
   │   ├── Projection pruning
   │   ├── Join reordering
   │   └── Adaptive optimizations (runtime)
   ▼
Physical Plan
   │
   ▼
Code Generation (Janino)
   │
   ▼
Execution (DataStream operators)
```

### Modes d'exécution

```sql
-- Mode streaming (flux infinis, défaut pour Kafka, etc.)
SET 'execution.runtime-mode' = 'streaming';

-- Mode batch (données bornées, optimisé pour le throughput)
SET 'execution.runtime-mode' = 'batch';
```

### API disponibles (hiérarchie)

```
SQL (niveau le plus haut, déclaratif)
   └── Table API (niveau intermédiaire, programmatique Java/Python)
         └── DataStream API (niveau bas, contrôle total)
```

---

## 3. Types de données

### Types primitifs

| Catégorie | Types |
|-----------|-------|
| **Entiers** | `TINYINT`, `SMALLINT`, `INT` / `INTEGER`, `BIGINT` |
| **Flottants** | `FLOAT`, `DOUBLE`, `REAL` |
| **Décimaux** | `DECIMAL(p, s)` / `NUMERIC(p, s)` |
| **Chaînes** | `CHAR(n)`, `VARCHAR(n)`, `STRING` |
| **Binaires** | `BINARY(n)`, `VARBINARY(n)`, `BYTES` |
| **Booléens** | `BOOLEAN` |
| **Date/heure** | `DATE`, `TIME(p)`, `TIMESTAMP(p)`, `TIMESTAMP_LTZ(p)` |
| **Intervalles** | `INTERVAL YEAR TO MONTH`, `INTERVAL DAY TO SECOND` |
| **Complexes** | `ARRAY<T>`, `MAP<K, V>`, `ROW<...>`, `MULTISET<T>` |
| **Semi-structuré** | `VARIANT` *(nouveau depuis 2.1)* |
| **Spéciaux** | `NULL`, `RAW<T>` (type opaque) |

### Le type VARIANT (depuis 2.1)

Le type `VARIANT` permet de stocker des données semi-structurées, notamment du JSON, sans schéma fixe.

```sql
-- Déclaration
CREATE TABLE events (
  id BIGINT,
  payload VARIANT
) WITH ('connector' = 'kafka', ...);

-- Conversion depuis JSON
SELECT PARSE_JSON('{"name": "Alice", "age": 30}') AS v;

-- Accès aux champs (syntaxe chemin)
SELECT payload['name'], payload['address']['city'] FROM events;

-- Conversion sûre
SELECT TRY_PARSE_JSON(raw_string) AS v FROM raw_events;
```

### Types de temps et attributs temporels

Les attributs temporels sont essentiels au traitement de flux. Ils permettent à Flink de suivre la progression du temps.

```sql
-- Event time : basé sur un timestamp dans les données
CREATE TABLE orders (
  order_id  BIGINT,
  order_time TIMESTAMP(3),
  -- Déclaration de l'event time et du watermark
  WATERMARK FOR order_time AS order_time - INTERVAL '5' SECOND
) WITH (...);

-- Processing time : basé sur l'horloge système
CREATE TABLE clicks (
  user_id  STRING,
  proc_time AS PROCTIME()  -- colonne calculée
) WITH (...);
```

### Types structurés (STRUCTURED TYPE) — depuis 2.1

```sql
-- Déclaration inline dans CREATE TABLE
CREATE TABLE t (
  id BIGINT,
  address <
    street STRING,
    city   STRING,
    zip    STRING
  >
) WITH (...);
```

---

## 4. DDL — Data Definition Language

### 4.1 CREATE TABLE

```sql
CREATE TABLE [IF NOT EXISTS] [catalog.][db.]table_name (
  col_name  col_type  [COMMENT 'description']  [column_constraint],
  ...
  [computed_col_definition],
  [metadata_col_definition],
  [watermark_definition],
  [table_constraint]
)
[COMMENT 'table description']
[PARTITIONED BY (col1, col2, ...)]
WITH ('key' = 'value', ...);
```

**Exemples concrets :**

```sql
-- Table Kafka avec event time et watermark
CREATE TABLE user_clicks (
  user_id   STRING,
  page_url  STRING,
  click_ts  TIMESTAMP(3),
  WATERMARK FOR click_ts AS click_ts - INTERVAL '2' SECOND
) WITH (
  'connector'                     = 'kafka',
  'topic'                         = 'user-clicks',
  'properties.bootstrap.servers'  = 'localhost:9092',
  'properties.group.id'           = 'flink-group',
  'format'                        = 'json',
  'scan.startup.mode'             = 'earliest-offset'
);

-- Table avec clé primaire (pour upsert)
CREATE TABLE product_catalog (
  product_id  BIGINT,
  name        STRING,
  price       DECIMAL(10, 2),
  updated_at  TIMESTAMP(3),
  PRIMARY KEY (product_id) NOT ENFORCED
) WITH (
  'connector' = 'jdbc',
  'url'       = 'jdbc:mysql://localhost:3306/shop',
  'table-name'= 'products'
);

-- Table avec colonne métadonnée Kafka
CREATE TABLE kafka_source (
  id       BIGINT,
  message  STRING,
  `partition` BIGINT METADATA,
  `offset`    BIGINT METADATA VIRTUAL,
  ts          TIMESTAMP_LTZ(3) METADATA FROM 'timestamp'
) WITH (
  'connector' = 'kafka',
  ...
);

-- Table avec colonne calculée
CREATE TABLE enriched (
  price     DECIMAL(10, 2),
  quantity  INT,
  total     AS price * quantity  -- colonne calculée, non stockée
) WITH (...);

-- Table LIKE (héritage de schéma)
CREATE TABLE archive_orders
  LIKE orders (EXCLUDING OPTIONS);  -- copie le schéma, pas les WITH options
```

### 4.2 ALTER TABLE

```sql
-- Renommer une table
ALTER TABLE old_name RENAME TO new_name;

-- Modifier les options du connecteur
ALTER TABLE my_table SET ('key1' = 'value1', 'key2' = 'value2');

-- Supprimer des options
ALTER TABLE my_table RESET ('key1');

-- Ajouter une colonne
ALTER TABLE my_table ADD COLUMN new_col STRING COMMENT 'ajout';

-- Modifier une colonne
ALTER TABLE my_table MODIFY COLUMN col_name STRING NOT NULL;

-- Supprimer une colonne
ALTER TABLE my_table DROP COLUMN col_name;

-- Ajouter un watermark
ALTER TABLE my_table ADD WATERMARK FOR event_time AS event_time - INTERVAL '1' SECOND;

-- Modifier un watermark
ALTER TABLE my_table MODIFY WATERMARK FOR event_time AS event_time - INTERVAL '5' SECOND;

-- Supprimer un watermark
ALTER TABLE my_table DROP WATERMARK;

-- Ajouter une contrainte
ALTER TABLE my_table ADD PRIMARY KEY (id) NOT ENFORCED;

-- Supprimer une contrainte
ALTER TABLE my_table DROP CONSTRAINT pk_name;

-- Ajouter une partition
ALTER TABLE my_table ADD PARTITION (dt='2024-01-01') WITH ('path' = '...');
```

### 4.3 DROP TABLE

```sql
DROP TABLE [IF EXISTS] [catalog.][db.]table_name;
```

### 4.4 CREATE VIEW

```sql
CREATE [OR REPLACE] [TEMPORARY] VIEW [IF NOT EXISTS] view_name
  [(col1, col2, ...)]
AS
SELECT ...;
```

```sql
-- Exemple
CREATE VIEW enriched_orders AS
SELECT o.*, p.name AS product_name, p.category
FROM orders o
JOIN product_catalog FOR SYSTEM_TIME AS OF o.proc_time AS p
  ON o.product_id = p.product_id;
```

### 4.5 CREATE DATABASE

```sql
CREATE DATABASE [IF NOT EXISTS] [catalog.]db_name
  [COMMENT 'description']
  WITH ('key' = 'value');
```

### 4.6 CREATE CATALOG

```sql
CREATE CATALOG catalog_name
  WITH ('type' = 'hive', 'hive-conf-dir' = '/path', ...);

-- Exemples de catalogues courants
CREATE CATALOG my_hive WITH (
  'type'           = 'hive',
  'hive-conf-dir'  = '/etc/hive/conf'
);

CREATE CATALOG my_jdbc WITH (
  'type'              = 'jdbc',
  'default-database'  = 'mydb',
  'username'          = 'root',
  'password'          = 'secret',
  'base-url'          = 'jdbc:mysql://localhost:3306/'
);

CREATE CATALOG paimon_catalog WITH (
  'type'        = 'paimon',
  'warehouse'   = 'hdfs://nn:8020/warehouse'
);
```

### 4.7 CREATE FUNCTION

```sql
-- Fonction temporaire (durée de la session)
CREATE TEMPORARY FUNCTION IF NOT EXISTS my_func
  AS 'com.example.MyFunction'
  LANGUAGE JAVA;

-- Fonction permanente (stockée dans le catalogue)
CREATE FUNCTION [IF NOT EXISTS] [catalog.][db.]func_name
  AS 'fully.qualified.ClassName'
  [LANGUAGE JAVA | PYTHON | SCALA];

-- Avec ressource JAR
CREATE FUNCTION my_udf
  AS 'com.example.MyUDF'
  USING JAR 'hdfs:///user/flink/my-udf.jar';
```

### 4.8 CREATE MATERIALIZED TABLE

```sql
-- Syntaxe complète (depuis 2.0, FRESHNESS optionnel depuis 2.2)
CREATE MATERIALIZED TABLE [IF NOT EXISTS] [catalog.][db.]table_name
  [COMMENT 'description']
  [PARTITIONED BY (col1, col2, ...)]
  [DISTRIBUTED BY (col1, col2, ...) | DISTRIBUTED INTO n BUCKETS]
  [WITH ('key' = 'value', ...)]
  [FRESHNESS = INTERVAL 'n' time_unit]
  AS <select_statement>;

-- Exemple : table matérialisée avec rafraîchissement toutes les heures
CREATE MATERIALIZED TABLE daily_revenue
FRESHNESS = INTERVAL '1' HOUR
AS
SELECT
  DATE_FORMAT(order_time, 'yyyy-MM-dd') AS day,
  product_id,
  SUM(amount) AS total_revenue
FROM orders
GROUP BY DATE_FORMAT(order_time, 'yyyy-MM-dd'), product_id;

-- Depuis 2.2 : FRESHNESS n'est plus obligatoire
CREATE MATERIALIZED TABLE snapshot_table
DISTRIBUTED BY (user_id)
AS SELECT * FROM user_events;
```

### 4.9 CREATE MODEL (AI — depuis 2.0)

```sql
CREATE MODEL [IF NOT EXISTS] model_name
INPUT (input_col_name input_col_type, ...)
OUTPUT (output_col_name output_col_type, ...)
WITH (
  'provider'  = 'openai',
  'task'      = 'text_generation',
  'openai.model' = 'gpt-4o',
  'openai.api_key' = '...'
);

-- Modèle d'embedding
CREATE MODEL text_embedder
INPUT  (text STRING)
OUTPUT (embedding ARRAY<FLOAT>)
WITH (
  'provider'          = 'openai',
  'task'              = 'embedding',
  'openai.model'      = 'text-embedding-3-small',
  'openai.endpoint'   = 'https://api.openai.com/v1/embeddings'
);
```

### 4.10 DESCRIBE / SHOW

```sql
-- Décrire la structure d'une table
DESCRIBE [EXTENDED] table_name;
DESC table_name;  -- alias

-- Lister les tables
SHOW TABLES [FROM [catalog.] db_name] [LIKE 'pattern'];

-- Lister les bases de données
SHOW DATABASES [FROM catalog_name];

-- Lister les catalogues
SHOW CATALOGS;

-- Lister les fonctions
SHOW [USER | SYSTEM] FUNCTIONS;

-- Lister les vues
SHOW VIEWS;

-- Lister les jobs
SHOW JOBS;

-- Lister les modèles IA
SHOW MODELS;

-- Afficher le plan d'exécution
EXPLAIN [ESTIMATED_COST] [CHANGELOG_MODE] [JSON_EXECUTION_PLAN]
SELECT ...;
```

---

## 5. DML — Data Manipulation Language

### 5.1 INSERT INTO

```sql
-- Insertion simple vers un sink
INSERT INTO sink_table
SELECT col1, col2, ...
FROM source_table
WHERE condition;

-- Insertion avec colonnes explicites
INSERT INTO my_table (col_a, col_b)
SELECT expr_a, expr_b FROM source;

-- Multi-insert dans un seul job (StatementSet)
STATEMENT SET
BEGIN
  INSERT INTO sink_a SELECT * FROM source WHERE type = 'A';
  INSERT INTO sink_b SELECT * FROM source WHERE type = 'B';
END;

-- INSERT OVERWRITE (mode batch uniquement, pour partitions)
INSERT OVERWRITE my_table
SELECT * FROM source;

INSERT OVERWRITE my_table PARTITION (dt='2024-01-01')
SELECT * FROM source WHERE dt = '2024-01-01';
```

### 5.2 UPDATE et DELETE (mode batch)

```sql
-- Mise à jour (batch uniquement)
UPDATE my_table
SET col1 = expr1, col2 = expr2
WHERE condition;

-- Suppression (batch uniquement)
DELETE FROM my_table
WHERE condition;
```

### 5.3 ANALYZE TABLE

```sql
-- Calcul de statistiques pour l'optimiseur
ANALYZE TABLE my_table COMPUTE STATISTICS;
ANALYZE TABLE my_table COMPUTE STATISTICS FOR COLUMNS col1, col2;
ANALYZE TABLE my_table PARTITION (dt='2024-01-01') COMPUTE STATISTICS;
```

---

## 6. Requêtes SELECT et opérations de lecture

### 6.1 Structure générale d'un SELECT

```sql
SELECT [ALL | DISTINCT]
  { * | expression [[AS] alias], ... }
FROM table_reference [, ...]
[WHERE boolean_expression]
[GROUP BY { grouping_element [, ...] }]
[HAVING boolean_expression]
[QUALIFY boolean_expression]    -- filtre sur résultats de window functions
[WINDOW window_name AS window_spec [, ...]]
[ORDER BY { col [ASC | DESC] } [, ...]]
[LIMIT n]
[OFFSET n];
```

### 6.2 QUALIFY (depuis Flink 2.0)

`QUALIFY` est un sucre syntaxique pour filtrer les résultats des fonctions de fenêtre sans sous-requête.

```sql
-- Trouver le top-1 par groupe (équivalent à un sous-SELECT + WHERE)
SELECT *
FROM orders
QUALIFY ROW_NUMBER() OVER (PARTITION BY product_id ORDER BY amount DESC) = 1;

-- Dédupliquation
SELECT *
FROM events
QUALIFY ROW_NUMBER() OVER (PARTITION BY user_id, event_type ORDER BY ts DESC) = 1;
```

### 6.3 Sous-requêtes

```sql
-- Sous-requête scalaire
SELECT name, (SELECT MAX(price) FROM products) AS max_price FROM orders;

-- Sous-requête IN
SELECT * FROM orders WHERE product_id IN (SELECT id FROM active_products);

-- Sous-requête EXISTS
SELECT * FROM users u
WHERE EXISTS (SELECT 1 FROM orders o WHERE o.user_id = u.id);

-- Sous-requête corrélée
SELECT o.id, (SELECT p.name FROM products p WHERE p.id = o.product_id) AS pname
FROM orders o;
```

### 6.4 LATERAL TABLE (table functions)

```sql
-- Appel de table function dans FROM (syntaxe TABLE() — legacy)
SELECT u.name, tag
FROM users u, LATERAL TABLE(split_tags(u.tags)) AS t(tag);

-- Syntaxe directe sans TABLE() (depuis Flink 2.0)
SELECT u.name, tag
FROM users u, LATERAL split_tags(u.tags) AS t(tag);

-- LEFT JOIN LATERAL (préserve les lignes sans résultat)
SELECT u.name, tag
FROM users u
LEFT JOIN LATERAL TABLE(split_tags(u.tags)) AS t(tag) ON TRUE;
```

### 6.5 ORDER BY et LIMIT (batch)

```sql
-- Tri (batch seulement pour ORDER BY global)
SELECT * FROM my_table ORDER BY ts DESC LIMIT 100;

-- Streaming : ORDER BY est autorisé uniquement sur l'attribut de temps (pour le tri temporel)
SELECT * FROM stream_table ORDER BY event_time;
```

### 6.6 SET et opérations ensemblistes

```sql
SELECT a, b FROM t1
UNION ALL              -- union sans déduplication
SELECT a, b FROM t2;

SELECT a, b FROM t1
UNION                  -- union avec déduplication (batch)
SELECT a, b FROM t2;

SELECT a FROM t1
INTERSECT ALL
SELECT a FROM t2;

SELECT a FROM t1
EXCEPT ALL
SELECT a FROM t2;
```

### 6.7 OVER (fenêtres glissantes)

```sql
-- Syntaxe OVER inline
SELECT
  user_id,
  amount,
  SUM(amount) OVER (
    PARTITION BY user_id
    ORDER BY order_time
    ROWS BETWEEN 3 PRECEDING AND CURRENT ROW
  ) AS rolling_sum,
  AVG(amount) OVER (
    PARTITION BY user_id
    ORDER BY order_time
    RANGE BETWEEN INTERVAL '1' HOUR PRECEDING AND CURRENT ROW
  ) AS hourly_avg
FROM orders;

-- Syntaxe WINDOW nommé (réutilisable)
SELECT
  user_id,
  ROW_NUMBER() OVER w AS rn,
  RANK()       OVER w AS rnk,
  DENSE_RANK() OVER w AS drnk,
  LAG(amount, 1, 0.0)  OVER w AS prev_amount,
  LEAD(amount, 1, 0.0) OVER w AS next_amount
FROM orders
WINDOW w AS (PARTITION BY user_id ORDER BY order_time);
```

---

## 7. Fenêtrage (Windowing)

Flink SQL propose deux approches de fenêtrage : les **Windowing TVFs** (recommandées, conformes SQL 2016) et les **Grouped Window Functions** (legacy, dépréciées).

### 7.1 Windowing TVFs (Table-Valued Functions)

Les Windowing TVFs retournent une relation enrichie avec trois colonnes supplémentaires : `window_start`, `window_end`, `window_time`.

#### TUMBLE — Fenêtres fixes non chevauchantes

```sql
-- Agrégation par fenêtre de 5 minutes
SELECT
  window_start,
  window_end,
  user_id,
  COUNT(*)    AS nb_clicks,
  SUM(amount) AS total
FROM TABLE(
  TUMBLE(TABLE orders, DESCRIPTOR(order_time), INTERVAL '5' MINUTE)
)
GROUP BY window_start, window_end, user_id;

-- Avec offset (décalage de l'alignement)
SELECT window_start, window_end, SUM(amount)
FROM TABLE(
  TUMBLE(TABLE orders, DESCRIPTOR(order_time), INTERVAL '1' DAY, INTERVAL '8' HOUR)
)
GROUP BY window_start, window_end;
```

#### HOP — Fenêtres glissantes chevauchantes

```sql
-- Fenêtres de 10 minutes, glissant toutes les 2 minutes
SELECT
  window_start,
  window_end,
  SUM(amount) AS total
FROM TABLE(
  HOP(TABLE orders, DESCRIPTOR(order_time),
      INTERVAL '2' MINUTE,    -- slide
      INTERVAL '10' MINUTE)   -- size
)
GROUP BY window_start, window_end;
```

#### CUMULATE — Fenêtres cumulatives

```sql
-- Cumul depuis le début de la journée, émis toutes les heures
SELECT
  window_start,
  window_end,
  SUM(amount) AS cumulative_total
FROM TABLE(
  CUMULATE(TABLE orders, DESCRIPTOR(order_time),
           INTERVAL '1' HOUR,   -- step (fréquence d'émission)
           INTERVAL '1' DAY)    -- size (fenêtre maximale)
)
GROUP BY window_start, window_end;
```

#### SESSION — Fenêtres de session

```sql
-- Fenêtres de session avec gap de 30 minutes
SELECT
  window_start,
  window_end,
  user_id,
  COUNT(*) AS nb_events
FROM TABLE(
  SESSION(TABLE user_events, DESCRIPTOR(event_time),
          INTERVAL '30' MINUTE)  -- gap d'inactivité
)
GROUP BY window_start, window_end, user_id;
-- Note : SESSION est en mode beta pour les Join/TopN/Deduplication
```

### 7.2 Window TopN (top N par fenêtre)

```sql
-- Top 3 des produits les plus vendus par heure
SELECT *
FROM (
  SELECT
    window_start, window_end, product_id, total_amount,
    ROW_NUMBER() OVER (
      PARTITION BY window_start, window_end
      ORDER BY total_amount DESC
    ) AS rn
  FROM (
    SELECT window_start, window_end, product_id, SUM(amount) AS total_amount
    FROM TABLE(TUMBLE(TABLE orders, DESCRIPTOR(order_time), INTERVAL '1' HOUR))
    GROUP BY window_start, window_end, product_id
  )
)
WHERE rn <= 3;

-- Syntaxe QUALIFY (plus concise, depuis 2.0)
SELECT window_start, window_end, product_id, total_amount
FROM (
  SELECT window_start, window_end, product_id, SUM(amount) AS total_amount
  FROM TABLE(TUMBLE(TABLE orders, DESCRIPTOR(order_time), INTERVAL '1' HOUR))
  GROUP BY window_start, window_end, product_id
)
QUALIFY ROW_NUMBER() OVER (
  PARTITION BY window_start, window_end ORDER BY total_amount DESC
) <= 3;
```

### 7.3 Déduplication par fenêtre (Window Deduplication)

```sql
SELECT window_start, window_end, user_id, event_type, ts
FROM (
  SELECT *,
    ROW_NUMBER() OVER (
      PARTITION BY window_start, window_end, user_id
      ORDER BY ts DESC
    ) AS rn
  FROM TABLE(TUMBLE(TABLE events, DESCRIPTOR(ts), INTERVAL '10' MINUTE))
)
WHERE rn = 1;
```

### 7.4 Agrégations GROUPING SETS, ROLLUP, CUBE

```sql
-- Avec window TVF
SELECT window_start, window_end, region, product, SUM(amount)
FROM TABLE(TUMBLE(TABLE sales, DESCRIPTOR(sale_time), INTERVAL '1' DAY))
GROUP BY window_start, window_end,
  GROUPING SETS ((region, product), (region), (product), ());

-- ROLLUP
GROUP BY window_start, window_end, ROLLUP(region, product);

-- CUBE
GROUP BY window_start, window_end, CUBE(region, product);
```

---

## 8. Jointures

### 8.1 Regular Join (jointure régulière)

Maintient tout l'historique en état. À utiliser avec précaution en streaming.

```sql
-- INNER JOIN
SELECT o.id, p.name, o.amount
FROM orders o
INNER JOIN products p ON o.product_id = p.id;

-- LEFT OUTER JOIN
SELECT u.id, u.name, o.amount
FROM users u
LEFT JOIN orders o ON u.id = o.user_id;

-- CROSS JOIN
SELECT u.name, p.name FROM users u CROSS JOIN products p;
```

### 8.2 Interval Join

Limite l'état en joignant uniquement les événements dans une fenêtre temporelle relative.

```sql
-- Jointure entre shipments et orders dans un intervalle de 4h avant / 0h après
SELECT o.id, s.tracking_id
FROM orders o, shipments s
WHERE o.id = s.order_id
  AND s.ship_time BETWEEN o.order_time AND o.order_time + INTERVAL '4' HOUR;

-- Syntaxe alternative
SELECT o.id, s.tracking_id
FROM orders o
JOIN shipments s ON o.id = s.order_id
  AND s.ship_time >= o.order_time
  AND s.ship_time <= o.order_time + INTERVAL '4' HOUR;
```

### 8.3 Window Join

Jointure dans la même fenêtre de temps. Aucun état résiduel après la fermeture de la fenêtre.

```sql
SELECT L.num, L.id, R.num, R.id
FROM (
  SELECT * FROM TABLE(TUMBLE(TABLE left_table, DESCRIPTOR(row_time), INTERVAL '5' MINUTE))
) L
JOIN (
  SELECT * FROM TABLE(TUMBLE(TABLE right_table, DESCRIPTOR(row_time), INTERVAL '5' MINUTE))
) R
ON L.num = R.num
   AND L.window_start = R.window_start
   AND L.window_end = R.window_end;

-- FULL OUTER Window Join
... L FULL OUTER JOIN R ON ... AND L.window_start = R.window_start AND L.window_end = R.window_end;
```

### 8.4 Temporal Join (jointure temporelle)

Jointure avec la version d'une table versionnée correspondant à un instant précis. Conforme SQL 2011 (`FOR SYSTEM_TIME AS OF`).

```sql
-- Enrichissement des commandes avec les taux de change historiques
SELECT o.amount * r.rate AS amount_eur
FROM orders o
JOIN currency_rates FOR SYSTEM_TIME AS OF o.order_time AS r
  ON o.currency = r.currency;

-- La table versionnée doit avoir une PRIMARY KEY et un attribut de temps
CREATE TABLE currency_rates (
  currency   STRING,
  rate       DECIMAL(10, 4),
  valid_from TIMESTAMP(3),
  PRIMARY KEY (currency) NOT ENFORCED,
  WATERMARK FOR valid_from AS valid_from - INTERVAL '5' SECOND
) WITH (...);
```

### 8.5 Lookup Join

Enrichissement en temps réel depuis un système externe (JDBC, HBase, Redis...).

```sql
-- Enrichissement avec lookup dans une table externe
SELECT o.id, o.amount, p.category
FROM orders o
JOIN product_catalog FOR SYSTEM_TIME AS OF o.proc_time AS p
  ON o.product_id = p.id;

-- Configuration du cache lookup
CREATE TABLE product_catalog (...) WITH (
  'connector'                          = 'jdbc',
  'lookup.cache'                       = 'PARTIAL',
  'lookup.partial-cache.max-rows'      = '1000',
  'lookup.partial-cache.expire-after-write' = '10min',
  'lookup.async'                       = 'true'    -- lookup asynchrone
);
```

### 8.6 Delta Join (depuis 2.1, activé par défaut)

Optimisation automatique des jointures régulières pour les tables avec index externe (ex. Apache Fluss). Réduit drastiquement la taille d'état.

```sql
-- Jointure régulière optimisée automatiquement en delta join
-- si les tables sources ont un index externe
SELECT o.*, u.profile
FROM orders o
JOIN users u ON o.user_id = u.id;

-- Désactivation si nécessaire
SET 'table.optimizer.delta-join.strategy' = 'NONE';
```

### 8.7 Multi-Join (depuis 2.1)

Opérateur unique pour les jointures en cascade, élimine l'état intermédiaire.

```sql
-- Jointure multi-flux, état réduit à zéro intermédiaire
SELECT o.id, u.name, p.name, s.status
FROM orders o
JOIN users u    ON o.user_id = u.id
JOIN products p ON o.product_id = p.id
JOIN shipments s ON o.id = s.order_id;
-- Le planner optimise automatiquement en MultiJoin si applicable
```

---

## 9. Agrégations

### 9.1 Agrégations de groupe (streaming)

```sql
-- Agrégation continue sur un flux (résultats mis à jour en continu)
SELECT
  user_id,
  COUNT(*)                   AS total_events,
  SUM(amount)                AS total_amount,
  AVG(amount)                AS avg_amount,
  MIN(amount)                AS min_amount,
  MAX(amount)                AS max_amount,
  COUNT(DISTINCT product_id) AS distinct_products
FROM orders
GROUP BY user_id;
```

### 9.2 Fonctions d'agrégation disponibles

| Fonction | Description |
|----------|-------------|
| `COUNT(*)` | Nombre de lignes |
| `COUNT(expr)` | Nombre de valeurs non nulles |
| `COUNT(DISTINCT expr)` | Nombre de valeurs distinctes |
| `SUM(expr)` | Somme |
| `AVG(expr)` | Moyenne |
| `MIN(expr)` | Minimum |
| `MAX(expr)` | Maximum |
| `STDDEV_POP(expr)` | Écart-type population |
| `STDDEV_SAMP(expr)` | Écart-type échantillon |
| `VAR_POP(expr)` | Variance population |
| `VAR_SAMP(expr)` | Variance échantillon |
| `COLLECT(expr)` | Collecte en MULTISET |
| `ARRAY_AGG(expr)` | Collecte en ARRAY |
| `LISTAGG(expr, sep)` | Concaténation en STRING |
| `FIRST_VALUE(expr)` | Première valeur |
| `LAST_VALUE(expr)` | Dernière valeur |

### 9.3 Retraction et mise à jour (streaming)

En mode streaming, les agrégations avec `GROUP BY` produisent un **changelog stream**. Flink gère automatiquement l'émission de messages de rétraction (`-U`) et de mise à jour (`+U`).

---

## 10. Pattern Matching (MATCH_RECOGNIZE)

`MATCH_RECOGNIZE` permet de détecter des séquences d'événements (Complex Event Processing).

```sql
SELECT *
FROM orders
MATCH_RECOGNIZE (
  PARTITION BY user_id
  ORDER BY order_time
  MEASURES
    FIRST(A.order_time) AS first_order,
    LAST(C.order_time)  AS last_order,
    B.amount            AS big_amount
  ONE ROW PER MATCH
  AFTER MATCH SKIP TO NEXT ROW
  PATTERN (A B+ C)
  WITHIN INTERVAL '1' HOUR
  DEFINE
    A AS amount < 10,
    B AS amount > 100,
    C AS amount < 10
) AS m;
```

**Clauses MATCH_RECOGNIZE :**

| Clause | Description |
|--------|-------------|
| `PARTITION BY` | Partitionnement du flux |
| `ORDER BY` | Attribut de temps (obligatoire) |
| `MEASURES` | Colonnes extraites du match |
| `ONE ROW PER MATCH` | Une ligne par correspondance |
| `ALL ROWS PER MATCH` | Une ligne par événement du match |
| `AFTER MATCH SKIP TO NEXT ROW` | Reprise après match |
| `AFTER MATCH SKIP PAST LAST ROW` | Reprise après le dernier événement |
| `PATTERN (...)` | Expression régulière d'événements |
| `WITHIN` | Délai maximum du pattern |
| `DEFINE` | Conditions booléennes par symbole |

**Quantificateurs de pattern :**

| Symbole | Description |
|---------|-------------|
| `A` | Exactement une occurrence |
| `A*` | Zéro ou plus |
| `A+` | Une ou plus |
| `A?` | Zéro ou une |
| `A{n}` | Exactement n occurrences |
| `A{n,m}` | Entre n et m occurrences |

---

## 11. Fonctions intégrées

### 11.1 Fonctions scalaires — Chaînes de caractères

```sql
CHAR_LENGTH(str)               -- longueur en caractères
LENGTH(str)                    -- alias
UPPER(str) / LOWER(str)
INITCAP(str)                   -- première lettre en majuscule
CONCAT(s1, s2, ...)            -- concaténation
CONCAT_WS(sep, s1, s2, ...)   -- concaténation avec séparateur
SUBSTRING(str, start [, len])
SUBSTR(str, start [, len])     -- alias
LEFT(str, n)
RIGHT(str, n)
TRIM([LEADING|TRAILING|BOTH] char FROM str)
LTRIM(str) / RTRIM(str)
LPAD(str, n, pad)              -- rembourrage gauche
RPAD(str, n, pad)
REPLACE(str, old, new)
REGEXP_REPLACE(str, regex, repl)
REGEXP_EXTRACT(str, regex [, group])
SPLIT_INDEX(str, sep, index)
LIKE(str, pattern)
SIMILAR TO pattern
POSITION(sub IN str)
LOCATE(sub, str [, pos])
OVERLAY(str PLACING repl FROM start [FOR len])
PARSE_URL(url, part [, key])
UUID()                          -- génère un UUID v4
FROM_BASE64(str)
TO_BASE64(bytes)
```

### 11.2 Fonctions scalaires — Numériques

```sql
ABS(x)
CEIL(x) / CEILING(x)
FLOOR(x)
ROUND(x [, d])
TRUNCATE(x [, d])
POWER(x, y)
SQRT(x)
LN(x) / LOG(x) / LOG2(x) / LOG10(x) / LOG(base, x)
EXP(x)
SIN(x) / COS(x) / TAN(x)
ASIN(x) / ACOS(x) / ATAN(x) / ATAN2(y, x)
SINH(x) / COSH(x) / TANH(x)
DEGREES(x) / RADIANS(x)
SIGN(x)
MOD(x, y)
PI()
E()
RAND([seed])
RAND_INTEGER([seed,] n)   -- entier aléatoire entre 0 et n
BIN(x)                    -- représentation binaire
HEX(x)                    -- représentation hexadécimale
```

### 11.3 Fonctions scalaires — Date et heure

```sql
CURRENT_DATE
CURRENT_TIME
CURRENT_TIMESTAMP
NOW()
LOCALTIME
LOCALTIMESTAMP
PROCTIME()                                      -- processing time

DATE_FORMAT(ts, fmt)                            -- formatage
TO_DATE(str [, fmt])                            -- parsing
TO_TIMESTAMP(str [, fmt])
TO_TIMESTAMP_LTZ(epoch_millis, precision)
FROM_UNIXTIME(epoch [, fmt])
UNIX_TIMESTAMP([ts [, fmt]])

DATE_ADD(date, days) / DATE_SUB(date, days)
TIMESTAMPADD(unit, n, ts)
DATEDIFF(ts1, ts2)
TIMESTAMPDIFF(unit, ts1, ts2)

YEAR(date) / QUARTER(date) / MONTH(date)
WEEK(date) / DAYOFYEAR(date) / DAYOFMONTH(date)
DAYOFWEEK(date) / HOUR(ts) / MINUTE(ts) / SECOND(ts)

EXTRACT(unit FROM temporal)                     -- extraction d'unité
FLOOR(ts TO unit)                               -- arrondi inférieur
CEIL(ts TO unit)                                -- arrondi supérieur
CONVERT_TZ(ts, from_tz, to_tz)                 -- changement de fuseau

-- Intervalles
INTERVAL '5' SECOND
INTERVAL '10' MINUTE
INTERVAL '2' HOUR
INTERVAL '1' DAY
INTERVAL '1' MONTH
INTERVAL '1' YEAR
```

### 11.4 Fonctions conditionnelles

```sql
CASE WHEN cond1 THEN val1
     WHEN cond2 THEN val2
     ELSE default
END

IF(condition, true_val, false_val)     -- alias simplifié
IFNULL(expr, default)
NULLIF(val1, val2)                     -- NULL si val1 = val2
COALESCE(v1, v2, ...)                  -- premier non-null
IS NULL / IS NOT NULL
IS TRUE / IS FALSE / IS UNKNOWN
```

### 11.5 Fonctions de collecte et tableaux

```sql
ARRAY[v1, v2, v3]               -- création de tableau
ARRAY_CONTAINS(arr, val)        -- appartenance
ARRAY_DISTINCT(arr)             -- déduplication
ARRAY_UNION(arr1, arr2)
ARRAY_CONCAT(arr1, arr2)
ARRAY_SLICE(arr, start, stop)
ARRAY_FLATTEN(arr)              -- aplatissement
ELEMENT(arr)                    -- unique élément d'un tableau à 1 élément
CARDINALITY(arr)                -- taille du tableau
arr[index]                      -- accès par index (1-based)

-- MAP
MAP['k1', v1, 'k2', v2]        -- création de map
MAP_KEYS(m) / MAP_VALUES(m)
MAP_UNION(m1, m2)
MAP_ENTRIES(m)
m['key']                        -- accès par clé

-- ROW
ROW(v1, v2, v3)                 -- création
row_expr.field_name             -- accès au champ
```

### 11.6 Fonctions JSON

```sql
JSON_EXISTS(json, path)
JSON_VALUE(json, path)
JSON_QUERY(json, path)
JSON_OBJECT('k1' VALUE v1, 'k2' VALUE v2)
JSON_ARRAY(v1, v2, v3)
JSON_ARRAYAGG(expr)
JSON_OBJECTAGG(key VALUE val)
IS JSON [VALUE | ARRAY | OBJECT | SCALAR]

-- Pour le type VARIANT (depuis 2.1)
PARSE_JSON(str)          -- STRING → VARIANT
TRY_PARSE_JSON(str)      -- STRING → VARIANT (sans erreur)
```

### 11.7 Fonctions de hachage et crypto

```sql
MD5(str)
SHA1(str)
SHA224(str)
SHA256(str)
SHA384(str)
SHA512(str)
HASH_CODE(val)
```

### 11.8 Fonctions de conversion de type

```sql
CAST(expr AS target_type)
TRY_CAST(expr AS target_type)   -- retourne NULL si conversion impossible
```

---

## 12. Fonctions définies par l'utilisateur (UDF)

### 12.1 Types de UDF

| Type | Description | Classe de base |
|------|-------------|----------------|
| **ScalarFunction** | 1 ligne → 1 valeur | `ScalarFunction` |
| **TableFunction** | 1 ligne → n lignes | `TableFunction<T>` |
| **AggregateFunction** | n lignes → 1 valeur | `AggregateFunction<T, ACC>` |
| **TableAggregateFunction** | n lignes → n lignes | `TableAggregateFunction<T, ACC>` |
| **AsyncScalarFunction** | scalaire asynchrone | `AsyncScalarFunction` |
| **ProcessTableFunction** | accès à l'état, timers | `ProcessTableFunction` *(PTF, 2.1+)* |

### 12.2 ScalarFunction (Java)

```java
import org.apache.flink.table.functions.ScalarFunction;

public class HashCode extends ScalarFunction {
  private int factor;

  public HashCode(int factor) { this.factor = factor; }

  public int eval(String s) {
    return s.hashCode() * factor;
  }
}
```

```sql
CREATE TEMPORARY FUNCTION hash_code AS 'com.example.HashCode';
SELECT hash_code(user_id) FROM users;
```

### 12.3 TableFunction (Java)

```java
import org.apache.flink.table.functions.TableFunction;
import org.apache.flink.table.annotation.DataTypeHint;
import org.apache.flink.table.annotation.FunctionHint;

@FunctionHint(output = @DataTypeHint("ROW<word STRING, length INT>"))
public class SplitFunction extends TableFunction<Row> {
  public void eval(String str) {
    for (String s : str.split(" ")) {
      collect(Row.of(s, s.length()));
    }
  }
}
```

```sql
SELECT word, length
FROM users, LATERAL TABLE(SplitFunction(name));
```

### 12.4 AggregateFunction

```java
public class WeightedAvg extends AggregateFunction<Double, WeightedAvgAccum> {
  @Override
  public WeightedAvgAccum createAccumulator() {
    return new WeightedAvgAccum();
  }

  public void accumulate(WeightedAvgAccum acc, Double value, Integer weight) {
    acc.sum += value * weight;
    acc.count += weight;
  }

  public void retract(WeightedAvgAccum acc, Double value, Integer weight) {
    acc.sum -= value * weight;
    acc.count -= weight;
  }

  @Override
  public Double getValue(WeightedAvgAccum acc) {
    return acc.count == 0 ? null : acc.sum / acc.count;
  }
}
```

### 12.5 UDF Python

```python
from pyflink.table.udf import udf
from pyflink.table import DataTypes

@udf(result_type=DataTypes.STRING())
def upper_case(s):
    return s.upper() if s else None
```

```sql
CREATE TEMPORARY FUNCTION upper_case AS 'my_module.upper_case' LANGUAGE PYTHON;
```

---

## 13. Catalogues et métadonnées

### 13.1 Hiérarchie des identifiants

```
catalog_name . database_name . object_name
    │                │               └── table / view / function / model
    │                └── base de données (namespace)
    └── catalogue (registre de métadonnées)
```

### 13.2 Gestion du catalogue courant

```sql
USE CATALOG my_catalog;
USE my_database;
SHOW CURRENT CATALOG;
SHOW CURRENT DATABASE;
```

### 13.3 Catalogues supportés

| Catalogue | Description |
|-----------|-------------|
| **GenericInMemory** (défaut) | Catalogue en mémoire, temporaire |
| **Hive Metastore** | Catalogue partagé Hadoop |
| **JDBC Catalog** | Basé sur une base de données JDBC |
| **Paimon Catalog** | Pour Apache Paimon (lakehouse) |
| **Fluss Catalog** | Pour Apache Fluss (streaming lakehouse) |
| **Glue Catalog** (AWS) | AWS Glue Data Catalog |
| **Custom** | Implémentation personnalisée |

### 13.4 Keyed State Connector (depuis 2.1)

Permet de requêter l'état interne d'un job Flink depuis un checkpoint/savepoint.

```sql
CREATE TABLE my_operator_state (
  k        INTEGER,
  user_id  STRING,
  balance  DOUBLE
) WITH (
  'connector' = 'savepoint',
  'path'      = 'file:///path/to/savepoint',
  'uid'       = 'my-operator-uid'
);

-- Interrogation de l'état
SELECT * FROM my_operator_state WHERE user_id = 'alice';
```

---

## 14. Materialized Tables

Les Materialized Tables unifient les pipelines batch et streaming dans un modèle déclaratif unique.

### 14.1 Création

```sql
-- Avec FRESHNESS (rafraîchissement automatique)
CREATE MATERIALIZED TABLE hourly_sales
FRESHNESS = INTERVAL '1' HOUR
AS
SELECT
  FLOOR(sale_time TO HOUR) AS hour,
  product_id,
  SUM(amount) AS total
FROM sales
GROUP BY FLOOR(sale_time TO HOUR), product_id;

-- Sans FRESHNESS (depuis 2.2, déclenché manuellement ou en continu)
CREATE MATERIALIZED TABLE current_inventory
DISTRIBUTED BY (warehouse_id) INTO 8 BUCKETS
AS
SELECT warehouse_id, product_id, SUM(qty) AS stock
FROM inventory_events
GROUP BY warehouse_id, product_id;
```

### 14.2 Modification et cycle de vie

```sql
-- Modifier une materialized table
CREATE OR ALTER MATERIALIZED TABLE hourly_sales
FRESHNESS = INTERVAL '30' MINUTE
AS
SELECT ...;

-- Suspendre le rafraîchissement
ALTER MATERIALIZED TABLE hourly_sales SUSPEND;

-- Reprendre le rafraîchissement
ALTER MATERIALIZED TABLE hourly_sales RESUME;

-- Supprimer
DROP MATERIALIZED TABLE IF EXISTS hourly_sales;
```

---

## 15. IA et inférence de modèles

### 15.1 ML_PREDICT — Inférence de modèles (depuis 2.1)

```sql
-- Utilisation d'un modèle OpenAI pour classification de logs
SELECT
  log_id,
  log_message,
  ML_PREDICT(
    'log_classifier',        -- nom du modèle CREATE MODEL
    log_message              -- colonnes d'entrée
  ) AS classification
FROM application_logs;

-- Avec table function pour récupérer plusieurs colonnes de sortie
SELECT log_id, category, confidence
FROM application_logs,
  LATERAL TABLE(ML_PREDICT('log_classifier', log_message))
  AS T(category, confidence);
```

### 15.2 VECTOR_SEARCH — Recherche de similarité vectorielle (depuis 2.2)

```sql
-- Recherche des K voisins les plus proches dans un flux de requêtes
SELECT
  q.query_id,
  q.query_text,
  v.document_id,
  v.similarity_score
FROM query_stream q,
  LATERAL TABLE(
    VECTOR_SEARCH(
      'document_embeddings',     -- table de vecteurs cible
      'embedding',               -- colonne vecteur dans la cible
      q.query_embedding,         -- vecteur requête
      3                          -- top-K résultats
    )
  ) AS v(document_id, similarity_score);
```

### 15.3 Cycle complet IA en Flink SQL

```sql
-- 1. Créer le modèle d'embedding
CREATE MODEL text_embedder
INPUT  (text STRING)
OUTPUT (embedding ARRAY<FLOAT>)
WITH (
  'provider'     = 'openai',
  'task'         = 'embedding',
  'openai.model' = 'text-embedding-3-small'
);

-- 2. Créer la table de vecteurs de référence
CREATE TABLE document_embeddings (
  doc_id    BIGINT,
  text      STRING,
  embedding ARRAY<FLOAT>,
  PRIMARY KEY (doc_id) NOT ENFORCED
) WITH ('connector' = 'paimon', ...);

-- 3. Peupler avec les embeddings
INSERT INTO document_embeddings
SELECT doc_id, content, ML_PREDICT('text_embedder', content) AS embedding
FROM documents;

-- 4. Recherche en temps réel
SELECT q.id, d.doc_id, d.text, vs.score
FROM incoming_queries q,
  LATERAL TABLE(VECTOR_SEARCH('document_embeddings', 'embedding', q.q_embedding, 5))
  AS vs(doc_id, score)
JOIN document_embeddings d ON d.doc_id = vs.doc_id;
```

---

## 16. Process Table Functions (PTF)

Introduites en Flink 2.1, les PTF sont les fonctions les plus puissantes de Flink SQL. Elles ont accès à l'état Flink, aux timers, à l'event-time, et aux changelogs.

### 16.1 Capacités PTF

- Accès à l'état géré de Flink (`ValueState`, `ListState`, `MapState`)
- Enregistrement de timers (event-time et processing-time)
- Accès au watermark courant
- Lecture du changelog d'entrée (`+I`, `-U`, `+U`, `-D`)
- Émission de lignes à la demande (pas seulement sur chaque entrée)

### 16.2 Exemple : machine à états avec timers

```java
@FunctionHint(
  input = @DataTypeHint("ROW<user_id STRING, event STRING, ts TIMESTAMP_LTZ(3)>"),
  output = @DataTypeHint("ROW<user_id STRING, session_duration BIGINT>")
)
public class SessionDetector extends ProcessTableFunction<Row> {

  @StateHint
  public ValueState<Long> sessionStart;

  @TimerHint(timeDomain = TimeDomain.EVENT_TIME)
  public TimerState sessionTimeout;

  public void eval(Context ctx, String userId, String event, Instant ts) {
    Long start = sessionStart.value();
    if (start == null) {
      sessionStart.update(ts.toEpochMilli());
    }
    // Repousser le timer à +30min
    sessionTimeout.register(ts.toEpochMilli() + 30_000L);
  }

  public void onTimer(long timestamp, OnTimerContext ctx) throws Exception {
    Long start = sessionStart.value();
    if (start != null) {
      collect(Row.of(ctx.getCurrentKey(), timestamp - start));
      sessionStart.clear();
    }
  }
}
```

```sql
SELECT user_id, session_duration
FROM TABLE(
  SessionDetector(TABLE user_events PARTITION BY user_id)
);
```

---

## 17. Connecteurs (Connectors)

### 17.1 Connecteurs disponibles (officiels)

| Connecteur | Source | Sink | Mode |
|------------|--------|------|------|
| `kafka` | ✓ | ✓ | Streaming |
| `kafka` (upsert) | ✓ | ✓ | Streaming |
| `filesystem` | ✓ | ✓ | Batch + Streaming |
| `jdbc` | ✓ | ✓ | Batch + Lookup |
| `hbase` | ✓ | ✓ | Batch + Lookup |
| `elasticsearch` / `opensearch` | – | ✓ | Streaming |
| `hive` | ✓ | ✓ | Batch + Streaming |
| `paimon` | ✓ | ✓ | Batch + Streaming |
| `fluss` | ✓ | ✓ | Streaming |
| `kinesis` | ✓ | ✓ | Streaming |
| `pulsar` | ✓ | ✓ | Streaming |
| `rabbitmq` | ✓ | ✓ | Streaming |
| `blackhole` | – | ✓ | Dev/Test |
| `datagen` | ✓ | – | Dev/Test |
| `print` | – | ✓ | Dev/Test |
| `savepoint` | ✓ | – | Debug (2.1+) |

### 17.2 Format de données

| Format | Description |
|--------|-------------|
| `json` | JSON standard |
| `avro` | Apache Avro |
| `avro-confluent` | Avro avec Schema Registry Confluent |
| `csv` | CSV |
| `parquet` | Apache Parquet |
| `orc` | Apache ORC |
| `debezium-json` | Debezium CDC |
| `canal-json` | Canal CDC |
| `maxwell-json` | Maxwell CDC |
| `raw` | Bytes bruts |
| `protobuf` | Protocol Buffers (upgrade vers proto-java 4.x en 2.2) |

### 17.3 Configuration Kafka détaillée

```sql
CREATE TABLE kafka_table (...) WITH (
  'connector'                              = 'kafka',
  'topic'                                  = 'my-topic',
  'properties.bootstrap.servers'           = 'broker:9092',
  'properties.group.id'                    = 'my-group',

  -- Startup mode
  'scan.startup.mode'                      = 'earliest-offset',
  -- ou: latest-offset | group-offsets | timestamp | specific-offsets

  -- Format
  'format'                                 = 'json',
  'json.fail-on-missing-field'             = 'false',
  'json.ignore-parse-errors'               = 'true',

  -- Sink
  'sink.partitioner'                       = 'default',   -- ou: fixed | round-robin | custom
  'sink.delivery-guarantee'                = 'exactly-once',
  'sink.transactional-id-prefix'           = 'flink-tx-',

  -- Performances
  'properties.max.block.ms'               = '5000',
  'properties.enable.auto.commit'          = 'false'
);
```

---

## 18. Gestion de l'état (State Management)

### 18.1 State TTL (Time-To-Live)

```sql
-- Configurer l'expiration de l'état pour les jointures et agrégations
SET 'table.exec.state.ttl' = '7 d';     -- expiration après 7 jours d'inactivité
```

### 18.2 State Backend (Flink 2.x)

Flink 2.0 introduit **ForSt DB**, un state backend disaggrégé, conçu pour le cloud.

```yaml
# conf/config.yaml
state.backend: forst             # nouveau backend cloud-native
state.backend.forst.localdir: /tmp/flink-state

# Backend legacy
state.backend: rocksdb
state.checkpoints.dir: hdfs:///flink/checkpoints
```

### 18.3 Checkpointing

```sql
-- Configuration dans Flink SQL
SET 'execution.checkpointing.interval'      = '60 s';
SET 'execution.checkpointing.mode'          = 'EXACTLY_ONCE';
SET 'execution.checkpointing.timeout'       = '10 min';
SET 'execution.checkpointing.min-pause'     = '5 s';
SET 'execution.checkpointing.max-concurrent-checkpoints' = '1';
```

---

## 19. Modes d'exécution et configuration

### 19.1 Paramètres de configuration principaux

```sql
-- Mode d'exécution
SET 'execution.runtime-mode' = 'streaming';   -- ou 'batch'

-- Parallélisme
SET 'parallelism.default' = '4';
SET 'table.exec.sink.not-null-enforcer' = 'ERROR';   -- ou 'DROP'

-- Optimisations batch adaptatives
SET 'table.exec.adaptive-local-hash-agg.enabled' = 'true';
SET 'table.exec.skewed-join.enabled' = 'true';

-- Lookup join asynchrone
SET 'table.exec.async-lookup.buffer-capacity' = '100';
SET 'table.exec.async-lookup.timeout' = '3 min';

-- Delta join
SET 'table.optimizer.delta-join.strategy' = 'AUTO';   -- ou 'NONE'

-- Mini-batch (optimisation streaming)
SET 'table.exec.mini-batch.enabled' = 'true';
SET 'table.exec.mini-batch.allow-latency' = '5 s';
SET 'table.exec.mini-batch.size' = '5000';

-- Agrégation locale-globale (two-phase)
SET 'table.optimizer.agg-phase-strategy' = 'TWO_PHASE';

-- Inférence de schéma
SET 'table.dynamic-table-options.enabled' = 'true';
```

### 19.2 Options de hint SQL

Les hints permettent de surcharger localement les options de table ou de plan.

```sql
-- Hint sur une table (override des WITH options pour cette requête)
SELECT * FROM my_table /*+ OPTIONS('scan.startup.mode'='latest-offset') */;

-- Hint de jointure
SELECT /*+ BROADCAST(small_table) */ * FROM big_table JOIN small_table ON ...;
SELECT /*+ SHUFFLE_MERGE(t1, t2) */ * FROM t1 JOIN t2 ON ...;
SELECT /*+ NEST_LOOP(t1, t2) */ * FROM t1 JOIN t2 ON ...;

-- Hint d'agrégation
SELECT /*+ STATE_TTL('orders'='1 d', 'products'='7 d') */
  o.user_id, COUNT(*)
FROM orders o JOIN products p ON o.product_id = p.id
GROUP BY o.user_id;
```

---

## 20. SQL Client et SQL Gateway

### 20.1 SQL Client (interactif)

```bash
# Démarrer le SQL Client
./bin/sql-client.sh

# Avec configuration personnalisée
./bin/sql-client.sh --defaults ./conf/sql-client-defaults.yaml

# Exécuter un fichier SQL
./bin/sql-client.sh -f my_script.sql
```

### 20.2 SQL Gateway (API REST)

Le SQL Gateway expose une API REST pour soumettre des requêtes SQL programmatiquement.

```bash
# Démarrer le gateway
./bin/sql-gateway.sh start -Dsql-gateway.endpoint.rest.address=localhost

# Créer une session
curl -X POST http://localhost:8083/v1/sessions

# Soumettre une requête
curl -X POST http://localhost:8083/v1/sessions/{session_id}/statements \
  -H 'Content-Type: application/json' \
  -d '{"statement": "SELECT * FROM my_table LIMIT 10"}'

# Récupérer les résultats
curl http://localhost:8083/v1/sessions/{session_id}/operations/{op_id}/result/0
```

### 20.3 Commandes utiles dans le SQL Client

```sql
-- Configurer l'affichage des résultats
SET 'sql-client.execution.result-mode' = 'table';    -- ou 'changelog' | 'tableau'
SET 'sql-client.display.max-column-width' = '60';

-- Compiler un plan d'exécution (portabilité)
COMPILE PLAN '/path/to/plan.json' FOR
INSERT INTO sink SELECT * FROM source;

-- Exécuter depuis un plan compilé
EXECUTE PLAN '/path/to/plan.json';

-- Gérer les jobs
SHOW JOBS;
STOP JOB 'job-id' [WITH SAVEPOINT];
```

---

## 21. Optimisations et performances

### 21.1 Optimisations automatiques en 2.x

**Adaptive Broadcast Join** (2.0) : Flink détecte à l'exécution si un côté du join est suffisamment petit pour être diffusé, et bascule automatiquement sur Broadcast Join.

**Automatic Join Skew Optimization** (2.0) : Redistribution automatique des données pour les clés surreprésentées dans un join.

**Delta Join** (2.1, activé par défaut) : Remplace les jointures régulières avec état massif par des lookups directs sur un système de stockage externe indexé (ex. Fluss).

**Multi-Join Operator** (2.1) : Fusionne plusieurs jointures en cascade en un seul opérateur, éliminant l'état intermédiaire.

**Sink Merging** (2.1) : Fusionne automatiquement plusieurs `INSERT INTO` vers la même table en un seul opérateur sink.

### 21.2 Optimisations manuelles recommandées

```sql
-- 1. Mini-batch pour réduire les accès état
SET 'table.exec.mini-batch.enabled' = 'true';
SET 'table.exec.mini-batch.allow-latency' = '2 s';
SET 'table.exec.mini-batch.size' = '1000';

-- 2. Agrégation en deux phases (local + global)
SET 'table.optimizer.agg-phase-strategy' = 'TWO_PHASE';

-- 3. Dédupliquation des COUNT DISTINCT (état partagé)
-- Flink détecte automatiquement si plusieurs COUNT DISTINCT partagent la même clé

-- 4. State TTL pour limiter la croissance de l'état
SET 'table.exec.state.ttl' = '1 d';

-- 5. Lookup asynchrone pour les enrichissements
-- Configurer la table avec 'lookup.async' = 'true'
```

### 21.3 Analyse et débogage des plans

```sql
-- Afficher le plan logique et physique
EXPLAIN SELECT ...;

-- Avec estimation des coûts
EXPLAIN ESTIMATED_COST SELECT ...;

-- Avec mode changelog (streaming)
EXPLAIN CHANGELOG_MODE SELECT ...;

-- Format JSON pour intégration
EXPLAIN JSON_EXECUTION_PLAN SELECT ...;
```

---

## 22. Limites et restrictions notables

### 22.1 Restrictions en mode Streaming

| Fonctionnalité | Restriction |
|----------------|-------------|
| `ORDER BY` global | Interdit (sauf sur attribut de temps) |
| `LIMIT` global | Non garanti (via TopN ou Window) |
| `UPDATE` / `DELETE` | Mode batch uniquement |
| Sous-requêtes corrélées complexes | Support limité |
| `FULL OUTER JOIN` sur deux flux infinis | État non borné |
| `CROSS JOIN` | Risque d'état non borné |
| Agrégations sans `GROUP BY` | Produit un changelog continu |
| Session Window Join/TopN | Bêta (peut évoluer) |

### 22.2 Restrictions sur les types

Certains types ne sont pas encore supportés dans toutes les expressions SQL (notamment dans `CAST` ou les littéraux) : `STRING` (remplacer par `VARCHAR`), `BYTES`, `RAW`, `TIME(p) WITH LOCAL TIME ZONE`.

### 22.3 Clés primaires

Les clés primaires dans Flink SQL sont déclarées `NOT ENFORCED` : Flink ne valide pas l'unicité à l'exécution. C'est la responsabilité du système source ou du connecteur.

```sql
PRIMARY KEY (id) NOT ENFORCED  -- obligatoire
```

### 22.4 Identifiants réservés

Les mots-clés Flink SQL sont insensibles à la casse. Pour utiliser un mot réservé comme identifiant, utiliser les backticks :

```sql
SELECT `table`, `value`, `key` FROM my_data;
```

### 22.5 Exécution des requêtes SELECT en SQL Client

En mode streaming, un `SELECT` dans le SQL Client est une requête foreground : il produit un flux de résultats jusqu'à interruption. Pour stocker les résultats, utiliser `INSERT INTO`.

---

## Annexe — Références rapides

### Syntaxe de création de table minimale

```sql
CREATE TABLE ma_table (
  id      BIGINT,
  message STRING,
  ts      TIMESTAMP(3),
  WATERMARK FOR ts AS ts - INTERVAL '5' SECOND
) WITH (
  'connector' = 'kafka',
  'topic'     = 'mon-topic',
  'properties.bootstrap.servers' = 'localhost:9092',
  'format'    = 'json'
);
```

### Requête fenêtrée typique

```sql
SELECT
  window_start,
  window_end,
  user_id,
  COUNT(*)    AS nb,
  SUM(amount) AS total
FROM TABLE(
  TUMBLE(TABLE orders, DESCRIPTOR(order_time), INTERVAL '1' HOUR)
)
GROUP BY window_start, window_end, user_id;
```

### Pipeline complet source → transformation → sink

```sql
-- Source
CREATE TABLE kafka_source (...) WITH ('connector'='kafka', ...);

-- Sink
CREATE TABLE jdbc_sink (...) WITH ('connector'='jdbc', ...);

-- Pipeline
INSERT INTO jdbc_sink
SELECT user_id, SUM(amount) AS total
FROM TABLE(TUMBLE(TABLE kafka_source, DESCRIPTOR(ts), INTERVAL '1' HOUR))
GROUP BY window_start, window_end, user_id;
```

---

## 23. Flink Embarqué (Embedded / In-Process)

Le mode **embarqué** consiste à exécuter Flink entièrement dans la JVM de l'application hôte, sans cluster distant. C'est le mode utilisé dans le cadre du projet **Kafka SQL Explorer** : un moteur Flink tourne dans le même processus Spring Boot que l'API REST, sans infrastructure externe.

### 23.1 Modes d'exécution locale

Flink propose trois façons d'exécuter des jobs en mode local :

| Mode | Classe centrale | Usage typique |
|------|----------------|---------------|
| **MiniCluster embarqué** | `MiniCluster` + `MiniClusterConfiguration` | Application autonome, tests d'intégration |
| **LocalEnvironment** | `StreamExecutionEnvironment.createLocalEnvironment()` | Dev/IDE, DataStream API |
| **TableEnvironment in-process** | `TableEnvironment.create(EnvironmentSettings)` | SQL pur embarqué, sans cluster |

En mode `TableEnvironment`, Flink détecte automatiquement l'absence de cluster et démarre un MiniCluster interne. Ce comportement est transparent pour l'appelant.

### 23.2 Dépendances Maven minimales

```xml
<!-- BOM Flink 2.2 -->
<dependencyManagement>
  <dependencies>
    <dependency>
      <groupId>org.apache.flink</groupId>
      <artifactId>flink-bom</artifactId>
      <version>2.2.0</version>
      <type>pom</type>
      <scope>import</scope>
    </dependency>
  </dependencies>
</dependencyManagement>

<dependencies>
  <!-- Moteur de planification SQL (obligatoire) -->
  <dependency>
    <groupId>org.apache.flink</groupId>
    <artifactId>flink-table-planner-loader</artifactId>
  </dependency>

  <!-- API Table/SQL Java -->
  <dependency>
    <groupId>org.apache.flink</groupId>
    <artifactId>flink-table-api-java-bridge</artifactId>
  </dependency>

  <!-- Runtime streaming (MiniCluster embarqué) -->
  <dependency>
    <groupId>org.apache.flink</groupId>
    <artifactId>flink-streaming-java</artifactId>
  </dependency>

  <!-- Client Flink (nécessaire pour soumettre au MiniCluster) -->
  <dependency>
    <groupId>org.apache.flink</groupId>
    <artifactId>flink-clients</artifactId>
  </dependency>

  <!-- Connecteurs selon le besoin -->
  <dependency>
    <groupId>org.apache.flink</groupId>
    <artifactId>flink-connector-kafka</artifactId>
    <version>3.4.0-2.0</version>
  </dependency>

  <!-- Format JSON -->
  <dependency>
    <groupId>org.apache.flink</groupId>
    <artifactId>flink-json</artifactId>
  </dependency>
</dependencies>
```

> **Attention — `flink-table-planner-loader` vs `flink-table-planner`** : depuis Flink 1.15, le planner est isolé dans son propre classloader via `flink-table-planner-loader`. Ne jamais inclure les deux, sous peine de conflits de classloader au démarrage.

### 23.3 Instanciation du TableEnvironment embarqué

```java
import org.apache.flink.table.api.EnvironmentSettings;
import org.apache.flink.table.api.TableEnvironment;
import org.apache.flink.api.common.RuntimeExecutionMode;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.configuration.TaskManagerOptions;
import org.apache.flink.configuration.RestOptions;

// ── Mode streaming (défaut pour Kafka) ──────────────────────────────
EnvironmentSettings settings = EnvironmentSettings.newInstance()
    .inStreamingMode()
    .build();
TableEnvironment tEnv = TableEnvironment.create(settings);

// ── Mode batch ───────────────────────────────────────────────────────
EnvironmentSettings batchSettings = EnvironmentSettings.newInstance()
    .inBatchMode()
    .build();
TableEnvironment batchEnv = TableEnvironment.create(batchSettings);

// ── Avec configuration fine du MiniCluster interne ───────────────────
Configuration conf = new Configuration();
conf.set(TaskManagerOptions.NUM_TASK_SLOTS, 4);         // slots disponibles
conf.set(TaskManagerOptions.TASK_HEAP_MEMORY,           // mémoire heap TM
         MemorySize.ofMebiBytes(512));
conf.setString("rest.port", "0");                       // port REST aléatoire (évite conflit 8081)
conf.setString("parallelism.default", "2");

EnvironmentSettings settings = EnvironmentSettings.newInstance()
    .inStreamingMode()
    .withConfiguration(conf)
    .build();
TableEnvironment tEnv = TableEnvironment.create(settings);
```

### 23.4 MiniCluster piloté manuellement

Pour un contrôle total du cycle de vie (utile dans Spring Boot pour le `@PreDestroy`).

```java
import org.apache.flink.runtime.minicluster.MiniCluster;
import org.apache.flink.runtime.minicluster.MiniClusterConfiguration;
import org.apache.flink.configuration.Configuration;

@Configuration
public class FlinkEmbeddedConfig {

    @Bean(destroyMethod = "close")
    public MiniCluster miniCluster() throws Exception {
        Configuration conf = new Configuration();
        conf.setString("rest.port", "0");          // port dynamique : évite le conflit avec 8081

        MiniClusterConfiguration clusterConf = new MiniClusterConfiguration.Builder()
            .setConfiguration(conf)
            .setNumTaskManagers(1)
            .setNumSlotsPerTaskManager(4)
            .build();

        MiniCluster cluster = new MiniCluster(clusterConf);
        cluster.start();
        return cluster;
    }

    @Bean
    public TableEnvironment tableEnvironment(MiniCluster miniCluster) {
        // Le TableEnvironment se connecte au MiniCluster déjà démarré
        // via la configuration REST injectée dans EnvironmentSettings
        Configuration conf = new Configuration();
        conf.setString("rest.address", "localhost");
        conf.setString("rest.port", String.valueOf(
            miniCluster.getRestAddress().toCompletableFuture().join().getPort()
        ));

        EnvironmentSettings settings = EnvironmentSettings.newInstance()
            .inStreamingMode()
            .withConfiguration(conf)
            .build();

        return TableEnvironment.create(settings);
    }
}
```

### 23.5 Exécution de SQL embarqué

```java
// DDL — création de tables (idempotent dans la session)
tEnv.executeSql("""
    CREATE TEMPORARY TABLE kafka_input (
      user_id  STRING,
      amount   DECIMAL(10, 2),
      ts       TIMESTAMP(3),
      WATERMARK FOR ts AS ts - INTERVAL '5' SECOND
    ) WITH (
      'connector'                     = 'kafka',
      'topic'                         = 'transactions',
      'properties.bootstrap.servers'  = 'localhost:9092',
      'format'                        = 'json'
    )
""");

// DQL — requête retournant un TableResult itérable
TableResult result = tEnv.executeSql("""
    SELECT user_id, SUM(amount) AS total
    FROM TABLE(TUMBLE(TABLE kafka_input, DESCRIPTOR(ts), INTERVAL '1' MINUTE))
    GROUP BY window_start, window_end, user_id
""");

// Itération sur les résultats (bloquant jusqu'à la fin du job)
try (CloseableIterator<Row> it = result.collect()) {
    while (it.hasNext()) {
        Row row = it.next();
        System.out.printf("user=%s total=%s%n", row.getField(0), row.getField(1));
    }
}

// DML — pipeline INSERT INTO (non bloquant, retourne un JobID)
TableResult insertResult = tEnv.executeSql("""
    INSERT INTO output_table
    SELECT user_id, SUM(amount)
    FROM kafka_input
    GROUP BY user_id
""");

// Attendre la fin (batch) ou laisser tourner (streaming)
insertResult.await();                        // bloquant
JobID jobId = insertResult.getJobClient()
    .map(JobClient::getJobID)
    .orElseThrow();                          // non bloquant
```

### 23.6 Exécution via l'objet Table (Table API)

```java
// Création d'une table depuis du SQL
Table table = tEnv.sqlQuery("""
    SELECT user_id, COUNT(*) AS nb
    FROM kafka_input
    GROUP BY user_id
""");

// Ajout de transformations programmatiques
Table filtered = table.filter($("nb").isGreater(10));

// Affichage (utile en dev/debug)
filtered.execute().print();

// Conversion vers DataStream si besoin
StreamExecutionEnvironment senv = StreamExecutionEnvironment.getExecutionEnvironment();
StreamTableEnvironment stEnv = StreamTableEnvironment.create(senv);

DataStream<Row> stream = stEnv.toDataStream(filtered);
stream.addSink(new MyCustomSink());
senv.execute("embedded-job");
```

### 23.7 Gestion du cycle de vie dans Spring Boot

```java
@Component
public class FlinkSqlEngine implements DisposableBean {

    private final TableEnvironment tEnv;
    private final Map<String, JobClient> runningJobs = new ConcurrentHashMap<>();

    public FlinkSqlEngine() {
        Configuration conf = new Configuration();
        conf.setString("rest.port", "0");
        conf.setString("parallelism.default", "2");
        conf.setString("table.exec.state.ttl", "1 h");

        this.tEnv = TableEnvironment.create(
            EnvironmentSettings.newInstance()
                .inStreamingMode()
                .withConfiguration(conf)
                .build()
        );
    }

    public String submitJob(String sql) throws Exception {
        TableResult result = tEnv.executeSql(sql);
        JobClient client = result.getJobClient().orElseThrow();
        String jobId = client.getJobID().toString();
        runningJobs.put(jobId, client);
        return jobId;
    }

    public void cancelJob(String jobId) throws Exception {
        JobClient client = runningJobs.remove(jobId);
        if (client != null) {
            client.cancel().get();
        }
    }

    @Override
    public void destroy() {
        // Annulation propre de tous les jobs au shutdown
        runningJobs.forEach((id, client) -> {
            try { client.cancel().get(); } catch (Exception ignored) {}
        });
    }
}
```

### 23.8 Spécificités et restrictions du mode embarqué

#### Restrictions fonctionnelles

| Fonctionnalité | Comportement en embarqué |
|----------------|--------------------------|
| **Interface web** | Absente par défaut. Activer via `web.submit.enable: true` et `rest.port: 8081` si port libre |
| **Haute disponibilité (HA)** | Non supportée en MiniCluster. Un seul JobManager, pas de failover |
| **Savepoints externes** | Supportés, mais le chemin doit être accessible localement (`file://`) |
| **Plusieurs jobs parallèles** | Limité par le nombre de slots configurés. Augmenter `taskmanager.numberOfTaskSlots` |
| **Checkpoints** | Fonctionnels. Le répertoire doit être un chemin local ou accessible (HDFS, S3) |
| **RocksDB / ForSt State Backend** | Fonctionnel en embarqué. Nécessite les natives libs dans le classpath |
| **Metrics** | Disponibles via le registre interne. Pas de Prometheus sans plugin additionnel |
| **REST API Flink** | Démarrée sur le port configuré (par défaut 8081). En conflit si déjà occupé → utiliser `rest.port: 0` |
| **Classloading** | Tous les connecteurs et formateurs doivent être dans le classpath de l'application hôte |

#### Problèmes courants et solutions

**Conflit de port REST (8081)**

```java
// Toujours utiliser le port 0 (port dynamique assigné par l'OS) en embarqué
conf.setString("rest.port", "0");
// Pour récupérer le port alloué dynamiquement :
// miniCluster.getRestAddress().get().getPort()
```

**Conflit de classloader avec Spring Boot**

Spring Boot repackage les JARs en fat-jar avec son propre classloader (`LaunchedURLClassLoader`). Flink utilise également son propre classloader pour isoler le planner. Ces deux mécanismes peuvent entrer en conflit.

```java
// Solution 1 : exclure le plugin Spring Boot repackage
// Ne pas utiliser spring-boot:repackage, lancer avec -cp à la place

// Solution 2 : configurer le classloader Flink
conf.setString("classloader.resolve-order", "parent-first");
// parent-first : classpath standard (compatible Spring Boot)
// child-first  : classloader Flink en priorité (défaut cluster)

// Solution 3 : utiliser le layout NONE pour le JAR Spring Boot
// (dans le pom.xml)
// <layout>NONE</layout> dans le plugin spring-boot-maven-plugin
```

**Mémoire insuffisante (OutOfMemoryError)**

```java
// Configurer explicitement les zones mémoire Flink
conf.set(TaskManagerOptions.TASK_HEAP_MEMORY, MemorySize.ofMebiBytes(256));
conf.set(TaskManagerOptions.MANAGED_MEMORY_SIZE, MemorySize.ofMebiBytes(128));
conf.set(TaskManagerOptions.NETWORK_MEMORY_MIN, MemorySize.ofMebiBytes(64));
conf.set(TaskManagerOptions.NETWORK_MEMORY_MAX, MemorySize.ofMebiBytes(64));

// Ou désactiver la gestion mémoire fine (dev uniquement)
conf.setBoolean("taskmanager.memory.task.off-heap.size", false);
```

**Nombre de slots insuffisant pour des requêtes complexes**

```java
// Une requête avec shuffle (ex. GROUP BY avec clé différente de la partition source)
// nécessite au moins 2 slots par pipeline
conf.setInteger(TaskManagerOptions.NUM_TASK_SLOTS, 8);
// Règle empirique : nb_slots >= 2 × nb_opérateurs_parallélisables
```

**Jobs streaming qui ne se terminent pas**

```java
// En mode streaming, executeSql() est non bloquant et retourne immédiatement.
// collect() bloque jusqu'à la fin du job (ou une exception).
// Pour un job streaming infini, collect() bloque indéfiniment.
// → Exécuter collect() dans un thread dédié et gérer l'interruption.

CompletableFuture.runAsync(() -> {
    try (CloseableIterator<Row> it = result.collect()) {
        while (!Thread.currentThread().isInterrupted() && it.hasNext()) {
            process(it.next());
        }
    } catch (Exception e) {
        log.error("Job terminated", e);
    }
}, executorService);
```

### 23.9 Configuration recommandée pour un usage production embarqué

```java
Configuration conf = new Configuration();

// ── Réseau / REST ─────────────────────────────────────────────────────
conf.setString("rest.port", "0");                     // port dynamique
conf.setString("rest.address", "127.0.0.1");          // écoute locale uniquement

// ── Ressources ────────────────────────────────────────────────────────
conf.setInteger(TaskManagerOptions.NUM_TASK_SLOTS, 4);
conf.set(TaskManagerOptions.TASK_HEAP_MEMORY, MemorySize.ofMebiBytes(512));
conf.set(TaskManagerOptions.MANAGED_MEMORY_SIZE, MemorySize.ofMebiBytes(256));

// ── Parallélisme ──────────────────────────────────────────────────────
conf.setString("parallelism.default", "2");

// ── Checkpointing ─────────────────────────────────────────────────────
conf.setString("execution.checkpointing.interval", "60 s");
conf.setString("execution.checkpointing.mode", "EXACTLY_ONCE");
conf.setString("state.checkpoints.dir", "file:///tmp/flink-checkpoints");
conf.setInteger("state.checkpoints.num-retained", 3);

// ── État ──────────────────────────────────────────────────────────────
conf.setString("table.exec.state.ttl", "1 h");
conf.setString("state.backend", "rocksdb");           // persistant entre redémarrages
conf.setString("state.backend.rocksdb.localdir", "/tmp/flink-rocksdb");

// ── Optimisations SQL ────────────────────────────────────────────────
conf.setString("table.exec.mini-batch.enabled", "true");
conf.setString("table.exec.mini-batch.allow-latency", "2 s");
conf.setString("table.exec.mini-batch.size", "1000");

// ── Classloader ───────────────────────────────────────────────────────
conf.setString("classloader.resolve-order", "parent-first");  // pour Spring Boot

// ── Logging ───────────────────────────────────────────────────────────
conf.setString("akka.ask.timeout", "30 s");
conf.setBoolean("web.submit.enable", false);          // pas d'UI web en production

EnvironmentSettings settings = EnvironmentSettings.newInstance()
    .inStreamingMode()
    .withConfiguration(conf)
    .build();

TableEnvironment tEnv = TableEnvironment.create(settings);
```

### 23.10 Tests avec Flink embarqué (JUnit 5)

```java
import org.apache.flink.table.api.TableEnvironment;
import org.apache.flink.table.api.EnvironmentSettings;
import org.apache.flink.types.Row;
import org.junit.jupiter.api.*;

@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class FlinkSqlEmbeddedTest {

    private static TableEnvironment tEnv;

    @BeforeAll
    static void setup() {
        tEnv = TableEnvironment.create(
            EnvironmentSettings.newInstance().inBatchMode().build()
        );
    }

    @Test
    @Order(1)
    void testSimpleAggregation() throws Exception {
        tEnv.executeSql("""
            CREATE TEMPORARY TABLE sales (
              product STRING,
              amount  DECIMAL(10,2)
            ) WITH ('connector'='datagen', 'number-of-rows'='100')
        """);

        TableResult result = tEnv.executeSql("""
            SELECT product, SUM(amount) AS total
            FROM sales
            GROUP BY product
        """);

        List<Row> rows = new ArrayList<>();
        try (CloseableIterator<Row> it = result.collect()) {
            it.forEachRemaining(rows::add);
        }
        Assertions.assertFalse(rows.isEmpty());
    }

    @Test
    @Order(2)
    void testWindowAggregation() throws Exception {
        // En batch, les window TVFs fonctionnent sur des tables bornées
        TableResult result = tEnv.executeSql("""
            SELECT window_start, window_end, COUNT(*) AS nb
            FROM TABLE(
              TUMBLE(TABLE sales, DESCRIPTOR(rowtime), INTERVAL '10' SECOND)
            )
            GROUP BY window_start, window_end
        """);
        // assertion...
    }
}
```

### 23.11 Matrice de compatibilité SQL en mode embarqué vs cluster

| Fonctionnalité SQL | Embarqué (MiniCluster) | Cluster Standalone/K8s |
|--------------------|------------------------|------------------------|
| DDL (CREATE TABLE, VIEW…) | ✅ Complet | ✅ Complet |
| DML INSERT INTO | ✅ Complet | ✅ Complet |
| SELECT avec collect() | ✅ Résultats locaux | ✅ Via collect sink |
| Windowing TVF | ✅ Complet | ✅ Complet |
| Temporal Join / Lookup Join | ✅ (cache en mémoire) | ✅ (cache distribué) |
| MATCH_RECOGNIZE | ✅ Complet | ✅ Complet |
| Process Table Functions | ✅ Complet | ✅ Complet |
| ML_PREDICT / VECTOR_SEARCH | ✅ (si dépendances présentes) | ✅ Complet |
| Checkpoints / Savepoints | ✅ Local filesystem | ✅ HDFS, S3, GCS |
| Haute disponibilité | ❌ Non supporté | ✅ ZooKeeper, K8s |
| Scalabilité horizontale | ❌ Limité à un seul TM | ✅ N TaskManagers |
| REST API Flink | ✅ Port local (0 recommandé) | ✅ Port 8081 standard |
| Interface web Flink | ⚠️ Disponible mais optionnelle | ✅ Active par défaut |
| Materialized Tables | ⚠️ En mémoire, non persistées | ✅ Catalogue persistant |
| Catalogues Hive / Paimon | ⚠️ Nécessite les dépendances | ✅ Natif |

---

*Document généré pour Apache Flink 2.2 (décembre 2025)*
*Sources : documentation officielle Apache Flink, release notes Flink 2.0 / 2.1 / 2.2*
