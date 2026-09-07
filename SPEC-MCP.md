# SPEC-MCP — Serveur MCP `kafka-explorer-mcp`

**Version** 0.2 — spécification complète (analyse comparative, surface MCP, écran de supervision)
**Cible** Kafka SQL Explorer (`devdownin/Kafkaexplorer`) — Java 25, Spring Boot 4.1, Flink 2.3 embarqué, React 19
**Statut** proposition d'architecture, à valider avant implémentation

---

## Sommaire

3. [Lecture stratégique](#3-lecture-stratégique)
4. [Apports de KafkaExplorer transposables](#4-apports-de-kafkaexplorer-transposables-en-outils-mcp)
5. [Spécification du serveur](#5-spécification-du-serveur)
6. [Écran « MCP » — catalogue et supervision](#6-écran--mcp--catalogue-et-supervision)
7. [Trajectoire, risques, sources](#7-trajectoire)

---

## 3. Lecture stratégique

**Quatre constats structurent la spécification.**

**(a) Le marché est saturé sur les verbes Kafka, vide sur la sémantique.**
Neuf implémentations exposent en substance la même chose : `list_topics`, `describe_topic`, `produce`, `consume`, `describe_consumer_group`. C'est la traduction 1:1 de l'`AdminClient` en JSON-RPC. Aucune ne répond à la question qu'un ingénieur pose réellement — *« où est passée la commande ORD-1042 ? »*, *« pourquoi la DLQ se remplit ? »*, *« quels topics parlent de la même entité ? »*. Reproduire cette surface serait produire le dixième clone.

**(b) `consume_messages` est un mauvais outil pour un agent.**
Il rend des enregistrements bruts. Pour corréler une clé sur six topics, l'agent doit émettre N appels, ramener des milliers de messages dans son contexte, et faire la corrélation lui-même — coûteux, lent, et surtout **non vérifiable** : rien dans la réponse ne dit ce qui a été lu ni ce qui ne l'a pas été. Un résultat vide se lit « ça n'existe pas » alors qu'il signifie « je n'ai pas regardé ». C'est le vecteur d'hallucination principal de tous les serveurs MCP Kafka actuels.

**(c) KIP-1318 fixe la barre sécurité, pas la barre fonctionnelle.**
Son pipeline (deny-list → allow-list/readonly → scope → policy → taint → approval → rate limit → circuit breaker) et ses codes d'erreur sont ce qui se fait de mieux. Il faut s'y aligner plutôt que d'inventer. Mais son périmètre fonctionnel reste l'`AdminClient` : ni SQL, ni inférence, ni corrélation.

**(d) Personne ne montre ce que l'agent fait.**
Tous les serveurs recensés sont des processus opaques : la surface se découvre par un `--list-tools` en ligne de commande, et l'usage ne se lit qu'en fouillant des logs. Or brancher un LLM sur un cluster de production est exactement la situation où l'opérateur doit voir, en continu, quels outils sont exposés, qui les appelle, ce qui a été refusé et pourquoi. KafkaExplorer possède déjà une UI ; l'écran MCP (§6) est donc un différenciant **gratuit en architecture et majeur en adoption**.

**Positionnement retenu :** `kafka-explorer-mcp` n'est pas un serveur MCP Kafka de plus. C'est l'exposition de la couche d'analyse de KafkaExplorer, avec le plan de contrôle Kafka délégué ou désactivé par défaut, et une console de gouvernance intégrée. Il est complémentaire, pas concurrent : un agent peut charger KIP-1318 pour l'administration et `kafka-explorer-mcp` pour comprendre.

---

## 4. Apports de KafkaExplorer transposables en outils MCP

| Capacité applicative | Outil MCP dérivé | Pourquoi c'est un différenciant |
|---|---|---|
| Moteur Flink 2.3 embarqué, SQL whitelisté (`SELECT`/`EXPLAIN`/`CREATE TABLE`) | `kex_sql_query` | SQL sur **Kafka vanilla**, sans Confluent Cloud, sans DDL à écrire |
| Inférence de schéma JSON/XML/Avro par échantillonnage | `kex_infer_schema` | Rend un topic non typé interrogeable ; aucun concurrent ne le fait |
| Stream Flow : traçage clé/header/JSONPath/XPath cross-topic, latence par hop, reprise sur budget | `kex_trace_key`, `kex_resume_trace`, `kex_compare_traces` | Une question métier = **un** appel, pas N `consume` |
| Data Model : relations déduites, graduées HIGH/MEDIUM/LOW, avec preuve | `kex_deduce_data_model` | Fournit à l'agent les prédicats de jointure qu'il inventerait sinon |
| Audit cluster : poison, doublons, drop-off, latence, score | `kex_run_audit`, `kex_get_audit` | Diagnostic scoré, pas un dump de métadonnées |
| Dead Letter : arrivées + part du topic source, appariement vérifié | `kex_analyze_dead_letters` | Le **taux** d'échec, pas le compte absolu |
| `CONSUMER_TIME_LAG` : âge du plus vieux message non lu | `kex_consumer_lag` | Unité dans laquelle un opérateur agit ; absent partout ailleurs |
| KPI suggérés depuis des mesures réellement prises | `kex_suggest_kpis` | Propose, ne crée jamais |
| Observabilité Kafka 4 : quorum KRaft, KIP-848, share groups, features | ressources `kafka://cluster/*` | Parité KIP-1318 en lecture |
| UI React existante | **écran MCP (§6)** | Catalogue auto-décrit + supervision temps réel : personne ne l'a |
| **« Une mesure qui a échoué n'est jamais zéro »** | invariant transverse | Voir §5.1 — c'est le cœur du contrat |

---

## 5. Spécification du serveur

### 5.1 Principes de conception

**P1 — Contrat d'honnêteté.** Toute réponse d'outil porte une enveloppe `coverage` : ce qui a été lu, ce qui ne l'a pas été, pourquoi ça s'est arrêté, et un jeton de reprise. Toute valeur numérique non mesurable est `null` accompagnée d'une raison — jamais `0`. `0` est une affirmation (« rattrapé », « aucun échec ») ; publiée à un LLM, elle produit une conclusion fausse et confiante. C'est l'invariant déjà tenu côté application (`PartitionLag`, `PartitionTimeLag`, `TopicTimeLag`) ; le serveur MCP l'étend à toute sa surface, **écran de supervision compris** (§6.4).

**P2 — Outils sémantiques.** Un outil répond à une question, il ne rend pas de la matière première. `kex_trace_key` remplace six `consume_messages` + corrélation côté modèle.

**P3 — Lecture seule par défaut.** `explorer.mcp.readonly=true` en sortie d'usine. Aucun outil mutant n'est *enregistré* — donc ni découvrable, ni invocable — sans opt-in explicite.

**P4 — In-process.** Le serveur MCP est un module du même JAR Spring Boot, pas un second processus. Il réutilise `KafkaAdminService`, `FlinkSqlService`, `AuditService`, `StreamFlowService`, `DataModelService`, le cache Caffeine, les budgets et les timeouts existants. Aucun second client Kafka, aucune configuration dupliquée, aucune dérive entre l'UI et l'agent. C'est aussi ce qui rend l'écran de supervision possible sans infrastructure supplémentaire.

**P5 — Compatibilité KIP-1318.** Ressources `kafka://` et codes d'erreur `-32040`…`-32047` repris à l'identique. Un profil `kip1318-aliases` expose en plus les noms d'outils standards (`consume_messages`, `describe_topic`…) pour les agents entraînés sur cette surface.

**P6 — Sortie bornée.** Plafonds non contournables sur le nombre d'enregistrements, de lignes SQL et d'octets. Toute troncature est signalée dans l'enveloppe et comptée en métrique.

**P7 — Pas de LLM dans la boucle.** Le Process Mining actuel appelle un LLM externe (OpenRouter par défaut). Via MCP, **le client EST le LLM** : le serveur rend des faits structurés, le modèle du client raisonne. La frontière de confidentialité disparaît, le coût par analyse aussi. Les outils MCP ne consomment aucun jeton fournisseur.

**P8 — Le serveur se décrit lui-même.** La liste des outils, leurs plafonds et leur état d'activation sont **lus dans le registre à l'exécution**, jamais recopiés dans le frontend ni dans la documentation. Une constante écrite des deux côtés finit par diverger ; l'écran MCP interroge `/api/mcp/catalog`, et ce catalogue est la même source que celle qui sert la capability negotiation JSON-RPC.

### 5.2 Architecture

```
kafkaexplorer/
├── src/main/java/.../mcp/
│   ├── McpServerConfiguration.java      # enregistrement conditionnel des tools
│   ├── contract/                        # Coverage, ToolResult, Measured, StopReason
│   ├── tools/                           # *McpTools : adaptateurs vers les services existants
│   ├── resources/                       # kafka:// et kex://
│   ├── prompts/                         # workflows de diagnostic
│   ├── guard/                           # ToolGuard : readonly, scope, DLP, caps, taint
│   └── observability/                   # McpCallRecorder, McpSessionRegistry, McpCatalogService
├── src/main/java/.../api/
│   └── McpConsoleController.java        # /api/mcp/** consommé par l'écran
└── src/main/frontend/src/pages/Mcp/     # écran React (Catalogue | Supervision)
```

Transports :
- **stdio** — `--mcp.stdio` sur le JAR, pour Claude Desktop / Claude Code / Cursor en local.
- **HTTP streamable** — endpoint `/mcp` du serveur Spring Boot existant, sans état, OAuth 2.1 (audience + issuer validés), TLS obligatoire. Aucune session serveur : scalable horizontalement derrière un LB.

> Conséquence sur la supervision : en HTTP stateless, une « session » est une abstraction de l'écran, reconstruite depuis les appels partageant la même identité et le même `client_info` de l'`initialize`. L'écran le dit (§6.4) plutôt que de laisser croire à un suivi de session serveur.

### 5.3 Contrats communs

```java
// src/main/java/.../mcp/contract/Coverage.java
public record Coverage(
        int topicsRequested,
        int topicsScanned,
        List<String> topicsNotReached,   // nommés, jamais omis silencieusement
        long recordsScanned,
        long elapsedMs,
        StopReason stopReason,
        Instant windowStart,
        Instant windowEnd,
        String resumeToken               // null si la passe est complète
) {
    public boolean complete() { return stopReason == StopReason.EXHAUSTED; }
}

public enum StopReason { EXHAUSTED, TIME_BUDGET, TOPIC_LIMIT, RECORD_LIMIT, CANCELLED, PARTIAL_FAILURE }

// Valeur mesurable qui peut légitimement ne pas l'être. Interdit le 0 par défaut.
public record Measured<T>(T value, String unmeasuredReason) {
    public static <T> Measured<T> of(T v) { return new Measured<>(v, null); }
    public static <T> Measured<T> unmeasured(String why) { return new Measured<>(null, why); }
    public boolean measured() { return value != null; }
}

public record ToolResult<T>(T data, Coverage coverage, List<Warning> warnings, boolean truncated) {}

public record Warning(Severity severity, String code, String message) {
    public enum Severity { INFO, WARN, CRITICAL }
}
```

> Sérialisation : `Measured` est aplati par un `JsonSerializer` dédié en `{"value": 12, "measured": true}` ou `{"value": null, "measured": false, "reason": "partition 3 illisible : timeout 8 s"}`. Le modèle voit la raison, pas un `null` nu qu'il interpréterait comme zéro. Le frontend applique la même règle : un composant `<MeasuredValue>` unique rend « non mesuré + raison », jamais un tiret ambigu.

### 5.4 Surface — Outils

#### Lecture / exploration

| Outil | Entrée | Sortie | Notes |
|---|---|---|---|
| `kex_list_topics` | `prefix?`, `includeDlt=false`, `activeSince?`, `limit≤200` | topics + partitions + count + dernière activité + type détecté | point d'entrée de tout agent |
| `kex_describe_topic` | `topic` | partitions, offsets min/max, taille estimée, format détecté, groupes, badge DLT | |
| `kex_preview_messages` | `topic`, `partition?`, `n≤50`, `readMode` | échantillon formaté (pretty JSON/XML), DLP appliquée | remplace `consume_messages`, borné |
| `kex_infer_schema` | `topics[]≤30`, `sampleSize≤500` | colonnes + type + confiance + format + DDL Flink prêt | Avro via Schema Registry si dispo |
| `kex_sql_query` | `sql`, `readMode=earliest\|latest`, `maxRows≤1000`, `timeoutMs≤30000` | lignes + schéma + moteur ayant répondu | whitelist `SELECT`/`EXPLAIN`/`CREATE TABLE` ; annulation du job Flink garantie sur timeout |
| `kex_list_tables` | — | tables Flink enregistrées + DDL **avec secrets rédigés** | |

#### Corrélation / diagnostic — le cœur différenciant

| Outil | Entrée | Sortie |
|---|---|---|
| `kex_trace_key` | `criterion{value, mode: EXACT_KEY\|DOT_PATH\|JSONPATH\|XPATH\|HEADER\|ANY, headerName?}`, `topics[]?`, `window?`, `budgetMs≤60000` | `hops[]` (topic, partition, offset, timestamp, latence depuis le hop précédent, extrait), hop le plus lent marqué, dérive d'horloge signalée, **`coverage` complète** |
| `kex_resume_trace` | `resumeToken` | poursuit sur les topics non lus, fusionne dans la même chaîne |
| `kex_compare_traces` | `traceA`, `traceB` | topics communs, topics atteints par un seul, **écarts de latence par hop** (jamais de timestamps absolus) |
| `kex_deduce_data_model` | `topics[]≤100`, `perTopicBudgetMs≤20000` | entités + colonnes, relations `HIGH\|MEDIUM\|LOW` avec preuve textuelle, colonnes FK-like sans cible signalées `?`, export Mermaid `erDiagram` |
| `kex_build_join` | `entities[]` | requête SQL joignant l'ensemble — **refuse et nomme l'entité inatteignable** plutôt que d'inventer un prédicat |
| `kex_run_audit` | `scope?`, `async=true` | `runId` |
| `kex_get_audit` | `runId` | score, findings gradués (poison, doublons, drop-off, latence, upgrade KRaft non finalisé), scope du run |
| `kex_analyze_dead_letters` | `window`, `queues[]?` | par file : arrivées bucketisées, **part du topic source**, appariement (`exact`\|`inferred`\|`ambiguous` + candidats nommés), verdict `quiet\|retrying\|receiving\|surging\|not_measured`, top champs d'échec |
| `kex_consumer_lag` | `groups[]?`, `topics[]?`, `includeTimeLag=true` | par partition : `Measured<Long>` en offsets **et** `Measured<Duration>` en temps |
| `kex_suggest_kpis` | — | KPI proposés, chacun citant la mesure et le run dont il dérive ; aucun seuil inventé |

#### Écriture — désactivés par défaut

| Outil | Garde |
|---|---|
| `kex_register_table` | `readonly=false` |
| `kex_create_metric` | `readonly=false` + approval token |
| `kex_produce_message` | `readonly=false` + scope préfixe + contrôle d'exfiltration DLP (`-32045`) |
| `kex_set_cluster_target` | `readonly=false` + refus 409 si audit/job/session en cours, override explicite |

Les mutations d'administration (`create_topic`, `delete_topic`, ACLs, reassignments) sont **hors périmètre**. Délégation à KIP-1318 ou `mcp-confluent`. Justification : aucun apport KafkaExplorer, et les exposer double la surface d'attaque pour zéro différenciation.

### 5.5 Surface — Ressources

```
kafka://cluster                      id, contrôleur, brokers
kafka://cluster/metadata-quorum      leader, epoch, high watermark, lag par réplique
kafka://cluster/features             versions finalisées vs supportées + badge de retard
kafka://topics                       liste
kafka://topics/{name}                description
kafka://topics/{name}/offsets        earliest/latest par partition
kafka://groups                       tous types : CLASSIC, CONSUMER, SHARE, STREAMS
kafka://groups/{id}                  membres, assignations, état
kafka://groups/{id}/lag              lag offsets + temps, Measured
kex://audit/latest                   dernier rapport d'audit avec son scope
kex://tables                         tables Flink enregistrées (DDL rédigé)
kex://metrics                        métriques configurées + état + seuils
kex://dead-letters                   inventaire des files avec appariement source
kex://health                         santé par module (data-plane, control-plane, flink, llm)
kex://usage/recent                   N derniers appels MCP (§6.4) — lecture seule, DLP appliquée
```

`kex://usage/recent` permet à un agent de relire ses propres actions — utile pour la reprise de contexte, et symétrique de `kafka://audit/recent` de KIP-1318.

### 5.6 Surface — Prompts

| Prompt | Arguments | Enchaînement |
|---|---|---|
| `kex_incident_triage` | `key`, `window` | `trace_key` → `resume_trace` si budget épuisé → `consumer_lag` sur les groupes touchés → `analyze_dead_letters` → synthèse citant la couverture |
| `kex_dlq_postmortem` | `queue`, `window` | `analyze_dead_letters` → `preview_messages` → `trace_key` sur 3 clés échantillonnées → hypothèse de cause |
| `kex_onboard_domain` | `prefix` | `list_topics` → `infer_schema` → `deduce_data_model` → `build_join` → note d'architecture |
| `kex_schema_drift_check` | `topic`, `baselineSchema` | `infer_schema` → diff structurel → champs apparus/disparus/retypés |
| `kex_flow_health` | `prefix` | `run_audit` scopé → `get_audit` → `suggest_kpis` |

### 5.7 Sécurité

Pipeline d'évaluation **repris de KIP-1318, même ordre, même fail-closed** :

```
1. authentification (OAuth 2.1 bearer sur HTTP, audience + issuer)
2. deny-list d'outils
3. allow-list / readonly
4. scope ressources (allowed-topic-prefixes, allowed-group-prefixes)   → -32041
5. policy engine externe si configuré (fail-closed)                    → -32044
6. taint guard (valeur lue → argument d'outil mutant)                  → -32040
7. approval token                                                      → -32042
8. rate limit                                                          → -32029
9. exécution, circuit breaker par dépendance (Kafka/Flink/SR)          → -32043
```

Chaque étape de refus **écrit une entrée d'usage** (§6.4) : l'écran de supervision montre les refus au même titre que les succès. Un contrôle qui bloque silencieusement ne se règle jamais.

Contrôles spécifiques à cette application :

- **Rédaction DDL.** Le masquage existant (mots de passe SSL, secrets SASL/Confluent) s'applique à `kex_list_tables`, aux `EXPLAIN` de `kex_sql_query`, à toute ressource exposant du DDL, **et au journal d'usage** : les paramètres d'appel sont rédigés avant persistance.
- **Whitelist SQL.** `SELECT` / `EXPLAIN` / `CREATE TABLE` uniquement. Un agent ne peut pas émettre de DML.
- **XXE.** Le durcissement XML existant couvre `kex_infer_schema` et les modes XPath de `kex_trace_key`.
- **Budget d'exécution.** Chaque outil analytique porte un budget borné par un plafond serveur que l'outil **lit** : `explorer.stream-flow-max-topics`, `explorer.data-model-max-topics` (100), `explorer.activity-max-topics` (100).
- **Repointage de cluster.** `kex_set_cluster_target` conserve le refus 409 si un audit, un job Flink ou une session Process Mining tourne. Un rapport ne doit pas décrire deux clusters.
- **Piste d'audit.** Chaque appel (identité, outil, paramètres rédigés, décision, résultat, id de corrélation) est appendé sur `internal.mcp.audit`, configuré append-only pour l'identité de l'application. Le topic `internal.audit.history` reste dédié aux rapports d'audit fonctionnels.

Codes : `-32001` non authentifié · `-32029` rate limit · `-32040` taint · `-32041` hors scope · `-32042` approbation requise · `-32043` dépendance indisponible · `-32044` policy · `-32045` exfiltration bloquée · `-32046` validation · `-32047` quarantaine.

### 5.8 Configuration

```yaml
explorer:
  mcp:
    enabled: true
    transport: http                 # http | stdio | both
    readonly: true                  # défaut verrouillé
    tools:
      allowed: "*"
      denied: ""
      kip1318-aliases: false
    allowed-topic-prefixes: "*"     # ex: "demo.,sandbox."
    allowed-group-prefixes: "*"
    approval-required-tools: "kex_produce_message,kex_set_cluster_target,kex_create_metric"
    hard-max-rows: 1000
    hard-max-records: 50
    hard-max-output-bytes: 1048576
    default-budget-ms: 20000
    hard-max-budget-ms: 60000
    audit-topic: internal.mcp.audit
    console:
      enabled: true                 # écran MCP dans l'UI
      ring-buffer-size: 2000        # appels conservés en mémoire pour le flux live
      allow-runtime-toggle: true    # kill-switch sans redémarrage
      allow-try-it: true            # exécution d'un outil depuis l'UI
    dlp:
      mode: redact                  # redact | block | off
      scrub-all-outputs: true
    oauth:
      expected-issuer: https://idp.example.com/
      expected-audience: kafka-explorer-mcp
```

### 5.9 Implémentation du serveur

**Dépendance** :

```xml
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-starter-mcp-server-webmvc</artifactId>
</dependency>
```
> Version pilotée par le BOM Spring AI aligné sur Spring Boot 4.1.

**Enregistrement conditionnel** — la garde read-only s'applique *à l'enregistrement*, pas à l'invocation : un outil non enregistré n'est ni découvrable ni invocable, quel que soit le prompt.

```java
// src/main/java/.../mcp/McpServerConfiguration.java
@Configuration
@ConditionalOnProperty(prefix = "explorer.mcp", name = "enabled", havingValue = "true")
class McpServerConfiguration {

    private final McpProperties properties;

    McpServerConfiguration(McpProperties properties) {
        this.properties = properties;
    }

    @Bean
    ToolCallbackProvider explorerTools(List<ReadOnlyMcpTools> readTools,
                                       List<MutatingMcpTools> writeTools,
                                       McpCatalogService catalog) {
        var registered = new ArrayList<Object>(readTools);
        if (!properties.readonly()) {
            registered.addAll(writeTools);
        }
        var enabled = registered.stream()
                .filter(t -> properties.toolFilter().accepts(t.getClass()))
                .toList();

        catalog.publish(registered, enabled);   // le catalogue connaît aussi les outils MASQUÉS et pourquoi
        return MethodToolCallbackProvider.builder().toolObjects(enabled.toArray()).build();
    }
}
```

**Outil de référence** — `kex_trace_key`, adaptateur mince sur le service existant. Toute la logique métier reste dans `StreamFlowService` : le module MCP ne réimplémente rien, il traduit.

```java
// src/main/java/.../mcp/tools/StreamFlowMcpTools.java
@Component
class StreamFlowMcpTools implements ReadOnlyMcpTools {

    private final StreamFlowService streamFlow;
    private final McpProperties properties;
    private final ToolGuard guard;

    StreamFlowMcpTools(StreamFlowService streamFlow, McpProperties properties, ToolGuard guard) {
        this.streamFlow = streamFlow;
        this.properties = properties;
        this.guard = guard;
    }

    @Tool(name = "kex_trace_key", description = """
            Trace one business key across the whole Kafka cluster and return the ordered hops
            with per-hop latency. Always inspect `coverage`: an empty `hops` array with
            stopReason != EXHAUSTED means "not found in what was scanned", NOT "does not exist".
            Use kex_resume_trace with coverage.resumeToken to continue.""")
    ToolResult<TraceView> traceKey(
            @ToolParam(description = "Value to look for, e.g. ORD-1042") String value,
            @ToolParam(description = "EXACT_KEY | DOT_PATH | JSONPATH | XPATH | HEADER | ANY. ANY searches key, payload and headers.")
            MatchMode mode,
            @ToolParam(required = false, description = "Header name, required when mode=HEADER") String headerName,
            @ToolParam(required = false, description = "Restrict to these topics; empty = most recently active first")
            List<String> topics,
            @ToolParam(required = false, description = "ISO-8601 start of the search window") Instant from,
            @ToolParam(required = false, description = "ISO-8601 end of the search window") Instant to,
            @ToolParam(required = false, description = "Time budget in ms, clamped by the server ceiling") Integer budgetMs) {

        guard.checkTopicScope(topics);                     // -32041 avant tout appel Kafka
        var criterion = TraceCriterion.of(value, mode, headerName);  // path/regex invalide -> -32046, jamais dégradé en substring
        var budget = Duration.ofMillis(guard.clampBudget(budgetMs, properties.defaultBudgetMs()));

        var trace = streamFlow.trace(criterion, topics, TimeWindow.of(from, to), budget);

        return new ToolResult<>(
                TraceView.from(trace, guard.dlp()),        // masquage PII/secrets sur les extraits de payload
                trace.coverage(),
                trace.warnings(),
                guard.truncate(trace));
    }
}
```

**Garde d'invocation** :

```java
// src/main/java/.../mcp/guard/ToolGuard.java
@Component
public class ToolGuard {

    private final McpProperties properties;
    private final DlpScrubber dlp;

    public ToolGuard(McpProperties properties, DlpScrubber dlp) {
        this.properties = properties;
        this.dlp = dlp;
    }

    public void checkTopicScope(List<String> topics) {
        if (topics == null || properties.allowedTopicPrefixes().isEmpty()) {
            return;
        }
        var offending = topics.stream()
                .filter(t -> properties.allowedTopicPrefixes().stream().noneMatch(t::startsWith))
                .toList();
        if (!offending.isEmpty()) {
            throw new McpScopeViolationException(offending); // -> JSON-RPC -32041
        }
    }

    public long clampBudget(Integer requested, long defaultMs) {
        return requested == null ? defaultMs : Math.min(requested, properties.hardMaxBudgetMs());
    }

    public DlpScrubber dlp() {
        return dlp;
    }
}
```

### 5.10 Tests du serveur

```java
// src/test/java/.../mcp/tools/StreamFlowMcpToolsTest.java
@ExtendWith(MockitoExtension.class)
class StreamFlowMcpToolsTest {

    @Mock StreamFlowService streamFlow;
    @Mock DlpScrubber dlp;

    McpProperties properties = McpProperties.defaults().withAllowedTopicPrefixes(List.of("demo."));
    StreamFlowMcpTools tools;

    @BeforeEach
    void setUp() {
        tools = new StreamFlowMcpTools(streamFlow, properties, new ToolGuard(properties, dlp));
    }

    @Test
    void rejects_topics_outside_the_configured_scope_before_touching_kafka() {
        assertThatThrownBy(() -> tools.traceKey("ORD-1", MatchMode.ANY, null,
                List.of("demo.orders.1.received", "prod.payments"), null, null, null))
                .isInstanceOf(McpScopeViolationException.class);

        verifyNoInteractions(streamFlow); // le scope doit couper avant tout I/O
    }

    @Test
    void surfaces_an_incomplete_scan_as_incomplete_not_as_a_negative_result() {
        given(streamFlow.trace(any(), any(), any(), any())).willReturn(
                Trace.empty(new Coverage(40, 12, List.of("demo.sc.13.out"), 8_400L, 20_000L,
                        StopReason.TIME_BUDGET, null, null, "rt-9f3c")));

        var result = tools.traceKey("ORD-404", MatchMode.ANY, null, null, null, null, null);

        assertThat(result.data().hops()).isEmpty();
        assertThat(result.coverage().complete()).isFalse();
        assertThat(result.coverage().resumeToken()).isNotBlank();
        assertThat(result.coverage().topicsNotReached()).contains("demo.sc.13.out");
    }

    @Test
    void clamps_a_client_supplied_budget_to_the_server_ceiling() {
        given(streamFlow.trace(any(), any(), any(), any())).willReturn(Trace.empty(Coverage.exhausted()));

        tools.traceKey("ORD-1", MatchMode.ANY, null, null, null, null, 999_999);

        var budget = ArgumentCaptor.forClass(Duration.class);
        verify(streamFlow).trace(any(), any(), any(), budget.capture());
        assertThat(budget.getValue()).isLessThanOrEqualTo(Duration.ofMillis(properties.hardMaxBudgetMs()));
    }
}
```

Tests d'intégration sur le stack de démonstration (79 topics, `ORD-101` sur six étapes, doublons `ORD-103`/`ORD-105`, poison dans `demo.errors.poison` **et** `demo.orders.3.enriched`, groupe `demo.orders.reporting` calé) :

1. `kex_trace_key("ORD-101", ANY)` retrouve les six hops **et** les topics `demo.payments.*` corrélés par header seul.
2. `EXACT_KEY` sur `demo.orders.nested` (6 partitions) ne scanne qu'une partition.
3. `kex_run_audit` remonte `demo.orders.3.enriched` en CRITICAL dans un flux par ailleurs vert.
4. `kex_consumer_lag` rend un lag temps mesuré pour `demo.orders.reporting`, jamais `0`.
5. `kex_analyze_dead_letters` rend `inferred` sur `demo.orders.2.dlt`, `ambiguous` là où plusieurs sources existent.
6. `kex_sql_query("DELETE FROM …")` est refusé par la whitelist.
7. Une réponse dépassant `hard-max-output-bytes` est tronquée **et** signalée.

---

## 6. Écran « MCP » — catalogue et supervision

### 6.1 Intention

Deux questions, un écran.

- *« Qu'est-ce que ce serveur offre à un agent, exactement, en ce moment ? »* → onglet **Catalogue**.
- *« Qu'est-ce que les agents en ont fait, et qu'est-ce qui a été refusé ? »* → onglet **Supervision**.

C'est la contrepartie visible de la décision de brancher un LLM sur un cluster. Aucun serveur MCP Kafka recensé n'offre cela : la surface s'y découvre par un `--list-tools` en ligne de commande et l'usage par des logs. Ici, le catalogue est lu dans le registre à l'exécution (P8) et l'usage est instrumenté de bout en bout.

Navigation : entrée **MCP** dans la barre latérale, entre *Metrics* et *Cluster*. Enregistrée dans la palette de commandes (⌘K) avec les actions rapides « Copier la config client », « Passer en lecture seule », « Voir les refus ». Écran masqué si `explorer.mcp.enabled=false` ; affiché avec un état vide explicatif si `enabled=true` mais qu'aucun client ne s'est jamais connecté — l'état vide dit *« le serveur écoute sur `…/mcp`, aucun client ne s'est connecté »*, pas *« aucune donnée »*.

### 6.2 Onglet Catalogue

**Bandeau d'état** — six faits, lus du runtime :

| Champ | Exemple | Source |
|---|---|---|
| État | `À l'écoute` / `Désactivé` | registre |
| Transports | `HTTP (streamable)` + `stdio` | `explorer.mcp.transport` résolu |
| Endpoint | `https://explorer.corp/mcp` | **adresse effectivement liée**, pas la propriété brute |
| Mode | badge `LECTURE SEULE` (vert) ou `ÉCRITURE ACTIVE` (ambre) | `readonly` |
| Authentification | `OAuth 2.1 — issuer …, audience …` ou `Aucune (stdio local)` | config résolue |
| Portée | `demo.*, sandbox.*` ou `Tous les topics` | préfixes |

Le badge `ÉCRITURE ACTIVE` liste au survol les outils mutants effectivement enregistrés. Un opérateur doit pouvoir répondre en une seconde à « est-ce que cet agent peut écrire ? ».

**Table des outils** — une ligne par outil, y compris **ceux qui ne sont pas exposés** :

| Colonne | Contenu |
|---|---|
| Nom | `kex_trace_key` |
| Catégorie | Exploration · Corrélation · Diagnostic · Écriture |
| État | `Exposé` · `Masqué (lecture seule)` · `Masqué (deny-list)` · `Masqué (allow-list)` · `Approbation requise` |
| Description | celle vue par l'agent, à l'identique |
| Budget / plafonds | `20 s par défaut, 60 s max · 50 enregistrements · 1 Mo` |
| Appels (24 h) | compteur, cliquable → Supervision filtrée sur cet outil |
| p95 | latence |
| Actions | `Essayer` · `Désactiver` |

Montrer les outils masqués **avec le motif** est le point important : sans cela, un opérateur qui cherche pourquoi son agent ne voit pas `kex_produce_message` n'a aucune piste. La colonne État répond avant qu'il ouvre le YAML.

**Ressources** et **Prompts** : deux tables plus courtes (URI/nom, description, dernier accès, nombre d'accès). Un prompt affiche l'enchaînement d'outils qu'il déclenche — c'est ce qui le rend auditable.

**Config client prête à copier.** Trois onglets (Claude Desktop / Claude Code / générique JSON), générés depuis l'endpoint résolu et le mode d'authentification réel. Bouton *Copier*. Aucun jeton n'est jamais inséré dans le snippet ; un encart indique où le placer.

**Essayer un outil.** Un panneau latéral compose le formulaire depuis le JSON Schema de l'outil, exécute l'appel **avec la même garde et le même chemin d'exécution qu'un agent**, et affiche la réponse JSON brute — enveloppe `coverage` comprise. Deux règles :

- l'appel est marqué `origin: CONSOLE` dans le journal d'usage : il compte dans la charge, il ne se confond pas avec un agent ;
- pour un outil mutant, le panneau exige la même confirmation que l'approval token et affiche le blast radius. « Essayer » n'est pas un contournement.

C'est le pendant exact du *Test* du sélecteur de modèle : on éprouve sans s'engager.

### 6.3 Onglet Supervision

**Quatre cartes de tête** (fenêtre glissante réglable : 1 h / 24 h / 7 j) :

1. **Appels** — total, dont refusés, avec sparkline. Le refus est sur la même carte que le succès, pas relégué ailleurs.
2. **Latence** — p50 / p95 / max, et l'outil le plus lent nommé.
3. **Charge induite sur le cluster** — enregistrements lus, octets consommés, jobs Flink lancés **par MCP**, en regard du total application. Répond à « est-ce que l'agent fait mal au cluster ? ».
4. **Clients** — identités distinctes actives, avec le client MCP déclaré (`claude-code/1.4.2`).

**Flux d'appels** — table temps réel, la vue principale :

| Horodatage | Origine | Identité | Outil | Verdict | Durée | Lu | Couverture | Corrélation |
|---|---|---|---|---|---|---|---|---|
| 14:02:31 | agent | `svc-sre@corp` | `kex_trace_key` | `OK` | 18,4 s | 8 400 rec | ⚠ `TIME_BUDGET` | `a3f9…` |
| 14:02:12 | agent | `svc-sre@corp` | `kex_produce_message` | `-32041 hors scope` | 2 ms | — | — | `a3f8…` |
| 14:01:58 | console | `marie@corp` | `kex_sql_query` | `OK (tronqué)` | 1,2 s | 1 000 li. | ✓ | `a3f7…` |

- Colonne **Couverture** : ✓ passe complète, ⚠ + motif d'arrêt sinon. Un opérateur voit immédiatement quelles réponses son agent a reçues incomplètes — c'est là que naissent les conclusions fausses.
- Ligne dépliable : paramètres **rédigés** (secrets et PII masqués par la même DLP que la sortie), enveloppe complète, avertissements, et pour un refus la garde exacte qui a bloqué et sa configuration courante.
- Filtres : outil, verdict, identité, origine, fenêtre. L'état de l'écran voyage dans l'URL, comme sur Stream Flow et Dead Letter — « regarde ce que l'agent a fait entre 14 h et 15 h » doit être un lien.
- Export CSV / JSON des lignes filtrées.

**Panneau « Refusé, et pourquoi »** — agrégation par code d'erreur, avec le nombre d'occurrences, les identités concernées et un lien vers le réglage responsable. Un rate limit qui frappe 200 fois par heure est une configuration à revoir, pas un incident de sécurité ; l'écran doit permettre de faire la différence.

**Kill switch** (si `allow-runtime-toggle=true`) :

- basculer le serveur en lecture seule ;
- désactiver un outil précis ;
- mettre une identité en quarantaine (`-32047`).

Effet immédiat, sans redémarrage, et **journalisé comme un appel** avec l'opérateur qui l'a déclenché. Une bannière persistante rappelle toute dérogation active au YAML, avec qui l'a posée et quand : une désactivation temporaire oubliée est un piège classique.

### 6.4 Honnêteté du panneau lui-même

Le principe P1 s'applique à l'écran de supervision, qui est lui aussi une mesure.

- **Le tampon mémoire est borné.** Le flux live sert `ring-buffer-size` appels. Au-delà, la table affiche *« historique antérieur : rejouer depuis `internal.mcp.audit` »* avec un bouton, jamais une liste tronquée silencieusement.
- **Un appel non enregistré n'est pas un appel qui n'a pas eu lieu.** Si l'écriture sur le topic d'audit échoue, la carte Appels affiche `non mesuré` avec la cause, et non un compteur qui aurait l'air juste. Le compteur d'échecs d'écriture (`explorer_mcp_audit_write_errors_total`) est visible sur l'écran.
- **Le transport HTTP est sans état.** Le regroupement en « sessions » est une reconstruction côté écran depuis l'identité et le `client_info` de l'`initialize`. L'infobulle de la carte Clients le dit. Ne pas laisser croire à un suivi de session serveur qui n'existe pas.
- **Une fenêtre sans trafic n'est pas un serveur inactif.** L'état vide distingue « aucun appel dans cette fenêtre » de « console désactivée » et de « journal illisible ».

### 6.5 API de la console

Tous les endpoints sont soumis à l'authentification de l'application, pas à celle du serveur MCP.

| Méthode | Chemin | Rôle |
|---|---|---|
| `GET` | `/api/mcp/status` | bandeau d'état (transport, endpoint lié, readonly, auth, portée) |
| `GET` | `/api/mcp/catalog` | outils (exposés **et** masqués + motif), ressources, prompts, plafonds |
| `GET` | `/api/mcp/catalog/client-config?client=claude-code` | snippet de configuration généré |
| `GET` | `/api/mcp/calls?since&tool&outcome&identity&origin&limit` | flux d'appels |
| `GET` | `/api/mcp/stats?window=24h` | cartes de tête, agrégats, top outils, refus par code |
| `GET` | `/api/mcp/clients?window=24h` | identités et clients déclarés |
| `POST` | `/api/mcp/try/{tool}` | exécution depuis la console (`origin=CONSOLE`) |
| `POST` | `/api/mcp/toggle/readonly` | bascule runtime |
| `POST` | `/api/mcp/toggle/tool/{name}` | activer/désactiver un outil |
| `POST` | `/api/mcp/quarantine/{identity}` | mise en quarantaine |
| `GET` | `/api/mcp/calls/replay?from&to` | rejeu depuis `internal.mcp.audit` |

### 6.6 Implémentation de la console

**Enregistrement d'usage** — un intercepteur unique sur le dispatch JSON-RPC, en amont et en aval de la garde, pour que les refus soient enregistrés au même titre que les succès.

```java
// src/main/java/.../mcp/observability/McpCallRecord.java
public record McpCallRecord(
        String correlationId,
        Instant startedAt,
        long durationMs,
        Origin origin,                  // AGENT | CONSOLE | PROMPT
        String identity,                // sub OAuth, ou "local (stdio)"
        String clientInfo,              // claude-code/1.4.2, tiré de l'initialize
        String tool,
        Map<String, Object> redactedParams,
        Outcome outcome,                // OK | DENIED | ERROR
        Integer jsonRpcErrorCode,       // null si OK
        String deniedByGuard,           // SCOPE | APPROVAL | TAINT | RATE_LIMIT | POLICY | READONLY
        Measured<Long> recordsScanned,  // non mesuré != 0 pour les outils qui ne comptent pas
        StopReason stopReason,          // null si l'outil n'a pas d'enveloppe
        boolean truncated,
        long outputBytes
) {
    public enum Origin { AGENT, CONSOLE, PROMPT }
    public enum Outcome { OK, DENIED, ERROR }
}
```

```java
// src/main/java/.../mcp/observability/McpCallRecorder.java
@Component
public class McpCallRecorder {

    private static final Logger log = LoggerFactory.getLogger(McpCallRecorder.class);

    // Anneau borné : la console live n'est pas un magasin d'historique, le topic d'audit l'est.
    private final Queue<McpCallRecord> ring;
    private final int capacity;
    private final McpAuditSink auditSink;
    private final MeterRegistry meters;
    private final AtomicLong auditWriteErrors = new AtomicLong();

    public McpCallRecorder(McpProperties properties, McpAuditSink auditSink, MeterRegistry meters) {
        this.capacity = properties.console().ringBufferSize();
        this.ring = new ConcurrentLinkedQueue<>();   // ConcurrentLinkedQueue.size() est en O(n) : ne jamais l'appeler dans record()
        this.auditSink = auditSink;
        this.meters = meters;
        Gauge.builder("explorer_mcp_audit_write_errors_total", auditWriteErrors, AtomicLong::get)
             .register(meters);
    }

    public void record(McpCallRecord call) {
        ring.add(call);
        while (ring.size() > capacity) {             // borné : cf. remarque ci-dessus, à remplacer par un compteur si le volume monte
            ring.poll();
        }

        meters.counter("explorer_mcp_calls_total",
                "tool", call.tool(),
                "outcome", call.outcome().name(),
                "origin", call.origin().name()).increment();
        meters.timer("explorer_mcp_call_duration", "tool", call.tool())
              .record(call.durationMs(), TimeUnit.MILLISECONDS);
        if (call.outcome() == Outcome.DENIED) {
            meters.counter("explorer_mcp_denied_total",
                    "guard", call.deniedByGuard(),
                    "code", String.valueOf(call.jsonRpcErrorCode())).increment();
        }
        if (call.truncated()) {
            meters.counter("explorer_mcp_output_truncated_total", "tool", call.tool()).increment();
        }

        try {
            auditSink.append(call);                  // topic append-only, hors du chemin critique
        } catch (RuntimeException e) {
            // Un appel non journalisé n'est pas un appel qui n'a pas eu lieu :
            // on compte l'échec et la console l'affiche, on ne fait jamais échouer l'appel MCP.
            auditWriteErrors.incrementAndGet();
            log.warn("MCP audit append failed for correlationId={}", call.correlationId(), e);
        }
    }

    public List<McpCallRecord> recent(McpCallFilter filter, int limit) {
        return ring.stream().filter(filter).sorted(comparing(McpCallRecord::startedAt).reversed())
                   .limit(limit).toList();
    }

    public long auditWriteErrors() {
        return auditWriteErrors.get();
    }
}
```

**Catalogue introspecté** — pas de liste écrite en dur, ni côté Java ni côté React.

```java
// src/main/java/.../mcp/observability/McpCatalogService.java
@Service
public class McpCatalogService {

    private volatile List<ToolDescriptor> descriptors = List.of();
    private final McpProperties properties;

    public McpCatalogService(McpProperties properties) {
        this.properties = properties;
    }

    /** Appelé une fois à la construction du ToolCallbackProvider. */
    void publish(List<Object> allToolBeans, List<Object> exposedToolBeans) {
        var exposed = Set.copyOf(exposedToolBeans);
        this.descriptors = allToolBeans.stream()
                .flatMap(bean -> ToolIntrospector.describe(bean).stream()
                        .map(d -> d.withVisibility(visibilityOf(bean, d, exposed))))
                .sorted(comparing(ToolDescriptor::category).thenComparing(ToolDescriptor::name))
                .toList();
    }

    private Visibility visibilityOf(Object bean, ToolDescriptor d, Set<Object> exposed) {
        if (!exposed.contains(bean)) {
            return bean instanceof MutatingMcpTools && properties.readonly()
                    ? Visibility.hiddenBy("lecture seule")
                    : Visibility.hiddenBy("allow/deny-list");
        }
        return properties.approvalRequiredTools().contains(d.name())
                ? Visibility.exposedWithApproval()
                : Visibility.exposed();
    }

    public List<ToolDescriptor> catalog() {
        return descriptors;   // l'écran lit ceci, jamais une constante frontend
    }
}
```

**Contrat frontend** (TypeScript, `src/pages/Mcp/types.ts`) :

```typescript
export type Visibility =
  | { state: 'EXPOSED' }
  | { state: 'EXPOSED_WITH_APPROVAL' }
  | { state: 'HIDDEN'; reason: string };

export interface ToolDescriptor {
  name: string;
  category: 'EXPLORATION' | 'CORRELATION' | 'DIAGNOSTIC' | 'WRITE';
  description: string;          // exactement ce que voit l'agent
  inputSchema: JSONSchema;      // pilote le formulaire « Essayer »
  visibility: Visibility;
  defaultBudgetMs: number | null;
  hardCaps: { records?: number; rows?: number; bytes: number };
  calls24h: number;
  p95Ms: number | null;         // null = pas assez d'appels, pas 0
}

export interface CallRow {
  correlationId: string;
  startedAt: string;
  durationMs: number;
  origin: 'AGENT' | 'CONSOLE' | 'PROMPT';
  identity: string;
  tool: string;
  outcome: 'OK' | 'DENIED' | 'ERROR';
  jsonRpcErrorCode: number | null;
  deniedByGuard: string | null;
  recordsScanned: Measured<number>;
  stopReason: StopReason | null;
  truncated: boolean;
}
```

Composants React : `McpPage` (onglets), `CatalogTable`, `ToolTryPanel` (formulaire généré depuis `inputSchema`), `UsageFeed` (polling 5 s, pause au survol), `DenialsPanel`, `KillSwitchBar`, et le `MeasuredValue` partagé avec les écrans Metrics et Dead Letter.

### 6.7 Métriques Prometheus

Exportées sur `/actuator/prometheus`, aux côtés des séries existantes :

```
explorer_mcp_calls_total{tool,outcome,origin}
explorer_mcp_call_duration_seconds{tool}          # histogram
explorer_mcp_denied_total{guard,code}
explorer_mcp_records_scanned_total{tool}
explorer_mcp_output_bytes_total{tool}
explorer_mcp_output_truncated_total{tool}
explorer_mcp_flink_jobs_total{origin="mcp"}
explorer_mcp_active_identities
explorer_mcp_audit_write_errors_total
explorer_mcp_readonly                             # gauge 0/1, alerte sur bascule non planifiée
```

Deux alertes recommandées : `explorer_mcp_readonly == 0` hors fenêtre de changement, et `rate(explorer_mcp_denied_total{guard="TAINT"}[5m]) > 0` — une violation de taint est le signal d'une tentative d'escalade donnée → action.

### 6.8 Tests de la console

```java
// src/test/java/.../mcp/observability/McpCallRecorderTest.java
@ExtendWith(MockitoExtension.class)
class McpCallRecorderTest {

    @Mock McpAuditSink auditSink;
    MeterRegistry meters = new SimpleMeterRegistry();
    McpCallRecorder recorder;

    @BeforeEach
    void setUp() {
        recorder = new McpCallRecorder(McpProperties.defaults().withRingBufferSize(3), auditSink, meters);
    }

    @Test
    void a_failed_audit_append_never_fails_the_call_but_is_counted() {
        willThrow(new KafkaException("topic unavailable")).given(auditSink).append(any());

        assertThatNoException().isThrownBy(() -> recorder.record(okCall("kex_list_topics")));

        assertThat(recorder.auditWriteErrors()).isEqualTo(1);
        assertThat(recorder.recent(McpCallFilter.all(), 10)).hasSize(1);
    }

    @Test
    void the_ring_buffer_stays_bounded() {
        for (int i = 0; i < 10; i++) {
            recorder.record(okCall("kex_list_topics"));
        }
        assertThat(recorder.recent(McpCallFilter.all(), 100)).hasSize(3);
    }

    @Test
    void denials_are_recorded_with_the_guard_that_blocked_them() {
        recorder.record(deniedCall("kex_produce_message", -32041, "SCOPE"));

        assertThat(meters.counter("explorer_mcp_denied_total", "guard", "SCOPE", "code", "-32041").count())
                .isEqualTo(1.0);
    }
}
```

```java
// src/test/java/.../api/McpConsoleControllerTest.java
@WebMvcTest(McpConsoleController.class)
class McpConsoleControllerTest {

    @Autowired MockMvc mvc;
    @MockitoBean McpCatalogService catalog;
    @MockitoBean McpCallRecorder recorder;

    @Test
    void the_catalog_exposes_hidden_tools_with_their_reason() throws Exception {
        given(catalog.catalog()).willReturn(List.of(
                ToolDescriptor.of("kex_produce_message", Category.WRITE).hiddenBy("lecture seule")));

        mvc.perform(get("/api/mcp/catalog"))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$[0].visibility.state").value("HIDDEN"))
           .andExpect(jsonPath("$[0].visibility.reason").value("lecture seule"));
    }

    @Test
    void the_client_config_snippet_never_contains_a_token() throws Exception {
        mvc.perform(get("/api/mcp/catalog/client-config").param("client", "claude-code"))
           .andExpect(status().isOk())
           .andExpect(content().string(not(containsStringIgnoringCase("secret"))))
           .andExpect(content().string(not(containsStringIgnoringCase("bearer "))));
    }
}
```

Tests frontend (Vitest) :

1. un outil `HIDDEN` s'affiche avec son motif, jamais absent de la table ;
2. une ligne d'appel dont `stopReason != EXHAUSTED` porte le pictogramme d'avertissement de couverture ;
3. `recordsScanned.measured === false` rend « non mesuré » + raison, jamais `0` ;
4. le bandeau `ÉCRITURE ACTIVE` s'affiche dès qu'un outil mutant est exposé ;
5. l'état des filtres est restauré depuis l'URL.

---

## 7. Trajectoire

| Phase | Contenu | Sortie |
|---|---|---|
| **1 — Socle honnête** | contrats `Coverage`/`Measured`, `ToolGuard`, readonly, `list_topics`, `describe_topic`, `preview_messages`, `sql_query`, `infer_schema`, ressources `kafka://`, **`McpCallRecorder` + métriques** | serveur utile, sûr et déjà instrumenté |
| **2 — Écran MCP** | `/api/mcp/status`, `/catalog`, `/calls`, `/stats` ; onglets Catalogue et Supervision ; config client copiable ; « Essayer » | l'opérateur voit ce qu'il a ouvert |
| **3 — Différenciation** | `trace_key`, `resume_trace`, `compare_traces`, `analyze_dead_letters`, `consumer_lag` (offsets + temps), prompt `incident_triage` | ce qu'aucun concurrent ne fait |
| **4 — Modélisation** | `deduce_data_model`, `build_join`, `run_audit`/`get_audit`, `suggest_kpis`, prompts `onboard_domain` et `dlq_postmortem` | valeur architecte |
| **5 — Entreprise** | OAuth 2.1, taint guard, approval token, hook OPA, topic d'audit + rejeu, DLP complète, kill switch runtime, alias KIP-1318, multi-cluster | déploiement contraint |

La phase 2 est placée **avant** les outils différenciants délibérément : la console rend chaque outil suivant observable dès son premier appel, et la dette d'instrumentation ne se rattrape pas après coup.

### 7.1 Risques et points ouverts

| Risque | Traitement |
|---|---|
| Un agent boucle sur `run_audit` / `trace_key` et sature le cluster | rate limit par identité + quotas broker + circuit breaker Flink ; carte « charge induite » de l'écran ; `run_audit` idempotent par `runId` |
| `sql_query` monopolise le minicluster Flink | pool borné, timeout dur, annulation garantie (comportement déjà en place) ; jobs MCP comptés séparément |
| Le modèle ignore l'enveloppe `coverage` et conclut « pas trouvé » | description d'outil explicite (§5.9), `stopReason` en tête de payload, colonne Couverture sur l'écran pour que l'opérateur voie les réponses partielles |
| Le flux d'usage fuit des données sensibles dans l'UI | même DLP que la sortie MCP appliquée aux paramètres **avant** persistance, pas à l'affichage |
| Le kill switch runtime devient une dérogation permanente oubliée | bannière persistante nommant l'auteur et la date de toute dérogation active |
| `/api/mcp/**` accessible sans contrôle | endpoints soumis à l'authentification applicative ; `console.enabled=false` les retire du contexte |
| Divergence entre la surface MCP et l'écran | catalogue introspecté (P8), aucune liste en dur ; test d'architecture ArchUnit interdisant la logique métier dans `mcp/` |
| Licence AGPL-3.0 vs adoption entreprise | à trancher : un serveur MCP exposé sur le réseau est un usage « service » ; clarifier ou envisager un double licensing sur le module MCP |
| Spring AI MCP est un SDK de tier 2 | acceptable : KIP-1318 fait le même arbitrage en faveur de la complétude du client Kafka |

### 7.2 Sources

- KIP-1318 — MCP Server for Apache Kafka : https://cwiki.apache.org/confluence/display/KAFKA/KIP-1318
- confluentinc/mcp-confluent : https://github.com/confluentinc/mcp-confluent
- Confluent — MCP managé : https://docs.confluent.io/cloud/current/ai/ai-tools/managed-mcp-server.html
- Confluent — MCP open source : https://docs.confluent.io/cloud/current/ai/ai-tools/open-source-mcp-server.html
- tuannvm/kafka-mcp-server : https://github.com/tuannvm/kafka-mcp-server
- Redpanda — Kafka managed MCP : https://docs.redpanda.com/agentic-data-plane/connect/managed/kafka/
- Redpanda — `rpk cloud mcp` : https://docs.redpanda.com/redpanda-cloud/ai-agents/mcp/configuration/
- GCP Managed Service for Apache Kafka MCP : https://docs.cloud.google.com/managed-service-for-apache-kafka/docs/use-managed-service-for-apache-kafka-mcp
- kanapuli/mcp-kafka · Joel-hanson/kafka-mcp-server · aswinayyolath/kafka-mcp-server · jonyx225/MCP_Kafka
- Kafka SQL Explorer — README, `docs/FEATURES.md`, `docs/architecture.md` : https://github.com/devdownin/Kafkaexplorer
