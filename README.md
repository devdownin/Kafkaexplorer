KAFKA SQL EXPLORER
Prompt de Developpement Complet
Spring Boot 3.x  |  Apache Flink Embedd  |  Thymeleaf  |  Java 21
Objectif
Application web permettant d'interroger des topics Kafka en temps reel via SQL Flink,
avec support natif des formats JSON et XML, editeur SQL integre et visualisation des resultats.



1. Contexte & Perimetre Fonctionnel
Kafka SQL Explorer est une application web embarquant un moteur Apache Flink en mode minicluster local, permettant d'interroger des topics Kafka via des requetes SQL standard. L'interface web, developpee en Thymeleaf, cible des profils data engineers et architects backend.

1.1 Utilisateurs Cibles
Data Engineers : creation de requetes SQL, exploration de topics, debug de messages
Architects : validation de schemas, audit de flux, documentation
Ops : surveillance des consumers, lag monitoring, alertes

1.2 Formats de Messages Supportes
Formats pris en charge
  JSON  :  messages structures, inferences de schema automatique, requete sur champs imbriques
  XML   :  parsing XPATH-like, projection de noeuds, conversion vers table relationnelle Flink
  Mixed :  detection automatique du format par topic (JSON ou XML), configuration par topic


2. Stack Technique
Couche
Technologie
Rôle
Backend
Java 21 + Spring Boot 3.x
API REST, logique metier, orchestration Flink
Moteur SQL
Apache Flink (embedded minicluster)
Execution SQL streaming sur topics Kafka
Connecteur
flink-connector-kafka 3.x
Lecture Kafka avec offsets, partitions, watermarks
Parsing
Jackson (JSON), JAXB / StAX (XML)
Deserialisation et inference de schema
Frontend
Thymeleaf + Alpine.js (optionnel)
Rendu SSR, interactions legeres cote client
Build
Maven 3.9+ / Java 21 records
Compilation, packaging, profils Spring
Infra dev
Docker Compose (Kafka + ZooKeeper)
Environnement local de developpement
Tests
JUnit 5 + Testcontainers
Tests unitaires et integration avec Kafka reel


3. Architecture Logicielle
3.1 Structure des Packages
com.yourcompany.kafkasqlexplorer
├── config/
│   ├── FlinkConfig.java          # StreamExecutionEnvironment + TableEnvironment beans
│   ├── KafkaConfig.java          # Proprietes connexion cluster Kafka (YAML)
│   └── WebConfig.java            # Intercepteurs, encodages, CORS
├── domain/
│   ├── TopicDescriptor.java      # Record Java 21 : nom, partitions, offsets, format
│   ├── QueryRequest.java         # Record : sql, topic, maxRows, timeout, readMode
│   ├── QueryResult.java          # Record : colonnes, lignes, duree, erreur
│   └── MessageFormat.java        # Enum : JSON, XML, AUTO
├── service/
│   ├── KafkaAdminService.java    # AdminClient : liste topics, metadata, consumer groups
│   ├── FlinkSqlService.java      # Execution SQL, TableResult -> QueryResult
│   ├── DdlGeneratorService.java  # Generation DDL CREATE TABLE Flink dynamique
│   └── SchemaInferenceService.java # Inference schema depuis samples JSON/XML
├── parser/
│   ├── JsonSchemaInferrer.java   # Inference schema depuis messages JSON
│   └── XmlSchemaInferrer.java    # Inference schema depuis messages XML (XPath)
├── web/
│   ├── DashboardController.java  # GET /  → liste topics
│   ├── QueryController.java      # POST /query → execution SQL, retour resultats
│   ├── TopicController.java      # GET /topic/{name} → detail + apercu messages
│   └── ConfigController.java     # GET/POST /config → gestion clusters Kafka
└── resources/
    ├── templates/
    │   ├── layout.html           # Layout Thymeleaf avec fragments nav + footer
    │   ├── dashboard.html        # Vue liste topics avec filtres et badges
    │   ├── query.html            # Editeur SQL + resultats + export CSV
    │   ├── topic-detail.html     # Metadonnees + apercu messages + DDL genere
    │   └── config.html           # Configuration multi-cluster
    └── static/
        ├── css/style.css         # Design system cyberpunk/terminal
        └── js/editor.js          # CodeMirror + interactions AJAX

3.2 Configuration Flink Embedd (Java)
@Configuration
public class FlinkConfig {
 
    @Bean
    public StreamExecutionEnvironment streamEnv() {
        Configuration cfg = new Configuration();
        cfg.setInteger(TaskManagerOptions.NUM_TASK_SLOTS, 4);
        cfg.setString(RestOptions.BIND_PORT, "0");   // port UI Flink aleatoire
        return StreamExecutionEnvironment.createLocalEnvironmentWithWebUI(cfg);
    }
 
    @Bean
    public StreamTableEnvironment tableEnv(StreamExecutionEnvironment env) {
        EnvironmentSettings settings = EnvironmentSettings
            .newInstance().inStreamingMode().build();
        return StreamTableEnvironment.create(env, settings);
    }
}

3.3 Generation DDL Kafka Dynamique
Le service DdlGeneratorService produit le CREATE TABLE Flink adapte au format du topic :
-- DDL genere pour un topic JSON
CREATE TABLE orders_json (
    order_id     STRING,
    customer_id  STRING,
    amount       DOUBLE,
    status       STRING,
    event_time   TIMESTAMP(3) METADATA FROM 'timestamp',
    kafka_offset BIGINT METADATA FROM 'offset' VIRTUAL,
    WATERMARK FOR event_time AS event_time - INTERVAL '5' SECOND
) WITH (
    'connector'            = 'kafka',
    'topic'                = 'orders',
    'properties.bootstrap.servers' = 'localhost:9092',
    'scan.startup.mode'    = 'earliest-offset',
    'format'               = 'json',
    'json.ignore-parse-errors' = 'true'
);
 
-- DDL genere pour un topic XML
CREATE TABLE invoices_xml (
    invoice_id   STRING,
    vendor_name  STRING,
    total_amount DOUBLE,
    currency     STRING,
    issue_date   STRING
) WITH (
    'connector'            = 'kafka',
    'topic'                = 'invoices',
    'properties.bootstrap.servers' = 'localhost:9092',
    'scan.startup.mode'    = 'latest-offset',
    'format'               = 'raw',
    'value.format'         = 'raw'
);
-- Note : pour XML, un UDF Flink parse le champ RAW via StAX/JAXB

4. Exemples de Requetes SQL Flink
4.1 Requetes JSON
Topics au format JSON avec inference de schema automatique :

Cas d'usage
Format
Requête SQL Flink
Lecture brute
JSON
SELECT * FROM orders_json LIMIT 50;
Filtre metier
JSON
SELECT order_id, amount, status FROM orders_json WHERE amount > 500.0 AND status = 'PENDING' LIMIT 20;
Agregation gliss.
JSON
SELECT TUMBLE_START(event_time, INTERVAL '1' MINUTE) AS win, COUNT(*) AS nb_orders, SUM(amount) AS total FROM orders_json GROUP BY TUMBLE(event_time, INTERVAL '1' MINUTE);
Top N clients
JSON
SELECT customer_id, COUNT(*) AS nb FROM orders_json GROUP BY customer_id ORDER BY nb DESC LIMIT 10;
Champ imbrique
JSON
SELECT order_id, JSON_VALUE(payload, '$.shipping.city') AS city FROM orders_json LIMIT 30;
Offset specifique
JSON
SELECT kafka_offset, order_id, amount FROM orders_json WHERE kafka_offset BETWEEN 1000 AND 1100;


4.2 Requetes XML
Topics XML avec UDF de parsing integre (XmlExtractUDF) :

Cas d'usage
Format
Requête SQL Flink
Lecture brute XML
XML
SELECT * FROM invoices_xml LIMIT 30;
Filtre montant
XML
SELECT invoice_id, vendor_name, total_amount FROM invoices_xml WHERE total_amount > 10000.0 AND currency = 'EUR' LIMIT 20;
Groupement devise
XML
SELECT currency, COUNT(*) AS nb_factures, SUM(total_amount) AS montant_total FROM invoices_xml GROUP BY currency;
Extraction XPath
XML
SELECT invoice_id, XmlExtract(raw_payload, '/Invoice/Items/Item/@sku') AS sku FROM invoices_raw LIMIT 50;
Jointure JSON+XML
Mix
SELECT o.order_id, i.invoice_id, o.amount, i.total_amount FROM orders_json o JOIN invoices_xml i ON o.order_id = i.invoice_id WHERE o.status = 'CLOSED';
Stats mensuelles
XML
SELECT DATE_FORMAT(CAST(issue_date AS TIMESTAMP), 'yyyy-MM') AS mois, SUM(total_amount) AS ca FROM invoices_xml GROUP BY DATE_FORMAT(CAST(issue_date AS TIMESTAMP), 'yyyy-MM');


4.3 Requetes de Monitoring
Requetes orientees observabilite et surveillance de cluster :

Cas d'usage
Format
Requête SQL Flink
Volume par topic
JSON
SELECT COUNT(*) AS messages, MIN(event_time) AS debut, MAX(event_time) AS fin FROM orders_json;
Lag de traitement
JSON
SELECT CURRENT_TIMESTAMP - MAX(event_time) AS lag_secondes FROM orders_json;
Anomalies JSON
JSON
SELECT kafka_offset, raw_value FROM orders_raw WHERE NOT is_valid_json(raw_value) LIMIT 20;
Taux d'erreurs
JSON
SELECT status, COUNT(*) * 100.0 / SUM(COUNT(*)) OVER () AS pct FROM orders_json GROUP BY status;
Deduplication
JSON
SELECT order_id, COUNT(*) AS duplicates FROM orders_json GROUP BY order_id HAVING COUNT(*) > 1 LIMIT 50;


5. Fonctionnalites de l'Interface
5.1 Dashboard (Vue Principale)
Liste paginee de tous les topics Kafka du cluster
Pour chaque topic : partitions, offset min/max, format detecte (JSON/XML), taille estimee
Badges colores : Actif (vert), Vide (gris), Erreur (rouge)
Barre de recherche et filtre temps reel (JavaScript)
Bouton "Ouvrir dans l'editeur SQL" avec DDL pre-genere
Rafraichissement auto des metadonnees toutes les 30 secondes (Caffeine cache)

5.2 Editeur SQL Flink
Zone de saisie SQL avec coloration syntaxique via CodeMirror
Auto-completion des noms de topics et colonnes inferees
Parametres d'execution : LIMIT, timeout (ms), mode offset (EARLIEST / LATEST / TIMESTAMP)
Bouton Executer avec indicateur de progression anime
Affichage des resultats en tableau dynamique paginable
Export CSV et JSON des resultats
Affichage du plan d'execution Flink (EXPLAIN SQL)
Historique des 20 dernieres requetes (sessionStorage navigateur)

5.3 Detail d'un Topic
Metadonnees completes : partitions, replicas, leaders, ISR
Apercu des 20 derniers messages (JSON formatte / XML indente)
Schema infere automatiquement depuis les 100 derniers messages
DDL Flink genere et pret a copier (JSON et XML)
Offset browser : navigation message par message

5.4 Configuration Multi-Cluster
Ajout / suppression de clusters Kafka nommes
Validation de connectivite en temps reel (AdminClient ping)
Support SASL/SSL avec upload de keystore/truststore
Registre de schemas Avro optionnel (Confluent Schema Registry)

6. Design System & Charte UI
Direction artistique : Terminal Professionnel
  Palette    :  Fond #0D1117 (noir profond), accents #00D4AA (teal), texte #E6EDF3
  Typographie:  JetBrains Mono (code/donnees) + DM Sans (labels UI)
  Composants :  Cartes avec bordures 1px solid #30363D, coins 8px
  Etats      :  Skeleton loaders, animations de progression, indicateur de connexion
  Feedback   :  Toasts succes/erreur, dot anime pour l'etat du cluster Kafka
  Responsive :  Sidebar collapsible + contenu principal, adaptatif mobile


7. Exigences Non-Fonctionnelles
7.1 Securite
Protection CSRF activee (Thymeleaf CSRF token automatique)
Validation des requetes SQL : whitelist de clauses (SELECT, WHERE, GROUP BY, HAVING, LIMIT)
Rejet de requetes DML : INSERT, UPDATE, DELETE, DROP interdits
Injection SQL Flink bloquee par validation stricte des entrees

7.2 Performance & Robustesse
Cache Caffeine pour les metadonnees Kafka (TTL 30 secondes)
Timeout configurable sur les requetes Flink (defaut : 10 secondes)
Pool de TableEnvironment pour eviter les creations redondantes
Gestion des topics inexistants ou indisponibles avec messages clairs

7.3 Observabilite
Spring Actuator expose : /actuator/health, /actuator/metrics, /actuator/info
Logs structures au format JSON (Logback + logstash-logback-encoder)
Metriques Micrometer : duree des requetes Flink, taux d'erreurs, topics actifs

8. Livrables Attendus
Projet Maven complet compilable avec ./mvnw spring-boot:run
docker-compose.yml avec Kafka, ZooKeeper et optionnellement Schema Registry
application.yml avec profils dev / prod et secrets externalisables
README.md : prerequis, demarrage, exemples de requetes JSON et XML
Tests unitaires : FlinkSqlService, DdlGeneratorService, SchemaInferenceService (JUnit 5 + Mockito)
Tests d'integration : KafkaAdminService avec Testcontainers (Kafka reel)
Documentation Swagger/OpenAPI de l'API REST interne

Note sur le parsing XML dans Flink
Flink ne dispose pas de connecteur XML natif. L'approche recommandee est :
  1. Lire le topic Kafka en format RAW (champ STRING brut)
  2. Appliquer un UDF Java (XmlExtractUDF) base sur StAX ou JAXB
  3. Le UDF transforme le payload XML en colonnes relationnelles exploitables en SQL
  4. La generation DDL propose automatiquement le DDL avec UDF pre-cablees


