# Kafka Explorer — Process Mining & Anomaly Detection
## Prompt Claude Code

---

## CONTEXTE PROJET

Tu travailles sur **Kafka Explorer**, une application existante composée de :
- **Backend** : Spring Boot 3 / Java 21
- **Frontend** : React
- **Infrastructure** : Apache Kafka (consumer existant)

Tu dois ajouter un module complet de **Process Mining** permettant de reconstruire
les flux métier et détecter les anomalies à partir des messages Kafka.

---

## MISSION

Implémente le pipeline suivant en 4 étapes séquentielles :

```
ÉTAPE 0 → Échantillonnage Kafka (50 msg/topic)
ÉTAPE 1 → Profilage des champs via Claude API  ← NOUVEAU
ÉTAPE 2 → Validation schéma unifié (UI React)  ← NOUVEAU
ÉTAPE 3 → Analyse flux + anomalies via Claude  ← NOUVEAU
           ├── Mode SNAPSHOT (lecture historique)
           └── Mode LIVE (fil de l'eau, SSE)
```

---

## ÉTAPE 0 — KafkaSnapshotReader

Crée `KafkaSnapshotReader.java` dans le package approprié.

**Comportement :**
- Supporte 3 modes de profondeur : `EARLIEST`, `LATEST_N`, `TIMESTAMP`
- Paramétrable : `maxMessagesPerTopic` (défaut 500), `durationMinutes`, `fromTimestamp`
- Retourne une liste de `KafkaMessage` : `{ topic, partition, offset, timestamp, key, value }`
- Pour le profilage (étape 1) : limiter à 50 messages par topic
- Utilise un consumer group temporaire (`snapshot-reader-{uuid}`) sans persistence

---

## ÉTAPE 1 — FieldProfilingService

Crée `FieldProfilingService.java`.

**Appel Claude API** avec ce prompt système :

```
Tu es un expert Apache Kafka et data mining.
Analyse des échantillons de messages Kafka.
Réponds UNIQUEMENT avec un JSON valide, sans markdown.
```

**Prompt utilisateur :** injecte les échantillons groupés par topic.

**Détecte pour chaque champ :**
| Rôle sémantique  | Indices de détection |
|------------------|----------------------|
| `CORRELATION_ID` | nom : id/key/ref/saga, valeurs UUID ou code stable, présent dans plusieurs topics |
| `TIMESTAMP`      | nom : date/time/at/on/created, valeur ISO8601 ou epoch |
| `STATUS`         | nom : status/state/step/type, valeurs répétées d'un ensemble fermé |
| `AMOUNT`         | nom : amount/price/total/qty, valeur numérique |

**JSON de sortie attendu de Claude :**
```json
{
  "topics": [
    {
      "name": "string",
      "format": "JSON|XML|AVRO",
      "fields": [
        {
          "path": "$.field.path",
          "sample_values": ["v1","v2","v3"],
          "inferred_type": "STRING|NUMBER|BOOLEAN|DATE|UUID|ENUM",
          "semantic_role": "CORRELATION_ID|TIMESTAMP|STATUS|AMOUNT|ACTOR|UNKNOWN",
          "confidence": 0.95,
          "reasoning": "explication courte"
        }
      ],
      "candidate_correlation_keys": ["$.orderId"],
      "candidate_timestamps": ["$.createdAt"],
      "candidate_statuses": ["$.status"]
    }
  ],
  "unification_proposal": {
    "correlation_id": {
      "canonical_name": "correlationId",
      "mappings": { "topic-A": "$.orderId", "topic-B": "$.order_id" },
      "confidence": 0.92,
      "conflicts": []
    },
    "timestamp": { "canonical_name": "eventTimestamp", "mappings": {}, "confidence": 0.95, "conflicts": [] },
    "status": {
      "canonical_name": "businessStatus",
      "mappings": {},
      "unified_values": { "CREATED": ["created","NEW"], "PAID": ["paid","PAYMENT_OK"] },
      "confidence": 0.88,
      "conflicts": []
    },
    "amount": { "canonical_name": "businessAmount", "mappings": {}, "currency_field": null, "confidence": 0.80 }
  },
  "warnings": ["champ id ambigu sur topic-C"]
}
```

**Règle de confiance :**
- `>= 0.9` → certitude forte
- `0.7–0.9` → hypothèse modérée
- `< 0.7` → signaler en warning, exclure de l'unification automatique

---

## ÉTAPE 2 — API REST + UI Validation

### Endpoints Spring Boot

```
POST /api/profiling/start
     Body  : { "topics": ["t1","t2"], "depth": { "mode": "LATEST_N", "value": 500 } }
     Return: SchemaUnificationProposal

POST /api/profiling/validate
     Body  : { "proposal": {...}, "userCorrections": { "correlationId": {...} } }
     Return: { "fieldMappingId": "uuid" }  ← stocker en cache (Caffeine ou Map)

POST /api/process-mining/snapshot
     Body  : { "topics": [...], "depth": {...}, "fieldMappingId": "uuid" }
     Return: ProcessMiningResult

GET  /api/process-mining/live
     Params: topics, fieldMappingId
     Return: SSE stream (text/event-stream)
     Events: FLOWCHART_UPDATE | ANOMALY_DETECTED | HEARTBEAT
```

### Composant React SchemaValidationPanel

Affiche pour chaque type de champ détecté :
- Le mapping topic → JSONPath avec indicateur de confiance (✓ vert / ⚠ orange / ? rouge)
- Un champ éditable pour corriger le JSONPath si besoin
- Les valeurs de statut unifiées avec possibilité de corriger les équivalences
- Les warnings LLM mis en évidence
- Bouton **"Valider et lancer l'analyse"** → POST /api/profiling/validate

---

## ÉTAPE 3 — LlmAnalysisService

Crée `LlmAnalysisService.java` avec deux méthodes publiques :
`analyzeSnapshot(topics, depth, fieldMapping)` et `analyzeLive(windowMessages, fieldMapping, referenceFlowchart)`.

### Prompt système commun (injecté en `system`) :

```
Tu es un expert Apache Kafka, process mining et conception logicielle.
Tu analyses des messages Kafka pour produire un flowchart Mermaid et un rapport d'anomalies.
Réponds UNIQUEMENT avec un objet JSON valide, sans markdown, sans texte libre.

Structure de sortie obligatoire :
{
  "flowchart": "flowchart TD\n...",
  "comments": "description prose du flux reconstruit",
  "hypotheses": ["hypothèse 1", "hypothèse 2"],
  "blind_spots": ["donnée manquante 1"],
  "anomalies": [
    {
      "id": "ANO-001",
      "topic": "nom-topic",
      "type": "SEQUENCE|TEMPORAL|STRUCTURAL|CARDINALITY|BUSINESS",
      "severity": "CRITICAL|MAJOR|MINOR",
      "fields": ["$.field"],
      "description": "description de l'anomalie",
      "probable_cause": "cause racine probable",
      "ksql_suggestion": "CREATE STREAM ... AS SELECT ..."
    }
  ]
}

Règles Mermaid :
- Topics Kafka      → [NomTopic]
- Services          → (NomService)
- Décisions         → {condition}
- Flux nominal      --> avec label
- Flux anormal      -.-> avec label anomalie
- Nœuds nominaux    → style fill:#90EE90
- Nœuds anomalie    → style fill:#FF6B6B
```

### Prompt utilisateur — Mode SNAPSHOT

```
## MODE : ANALYSE SNAPSHOT

## MAPPING DES CHAMPS VALIDÉ
{fieldMapping.toPromptBlock()}

## TOPICS : {topics}
## PROFONDEUR : {depth}

## MESSAGES (triés par timestamp croissant, groupés par topic)
{messagesJson}

## INSTRUCTIONS
1. Corrèle les messages via les chemins définis dans le MAPPING DES CHAMPS VALIDÉ
2. Reconstitue la séquence chronologique inter-topics
3. Identifie les 5 types d'anomalies :
   - SEQUENCE   : événements dans le mauvais ordre, étapes sautées
   - TEMPORAL   : délais anormaux entre événements corrélés
   - STRUCTURAL : champs manquants, types inattendus, valeurs aberrantes
   - CARDINALITY: doublons, multiplicité anormale pour un même id
   - BUSINESS   : transitions interdites (ex: CANCELLED → PAID)
4. Génère le flowchart Mermaid + rapport JSON complet
```

### Prompt utilisateur — Mode LIVE

```
## MODE : ANALYSE LIVE — FENÊTRE GLISSANTE

## MAPPING DES CHAMPS VALIDÉ
{fieldMapping.toPromptBlock()}

## FENÊTRE : {windowStart} → {windowEnd}  ({windowSize} messages)
## FLUX DE RÉFÉRENCE : {referenceFlowchart|"INCONNU"}

## NOUVEAUX MESSAGES
{messagesJson}

## INSTRUCTIONS SPÉCIFIQUES LIVE
1. Compare uniquement les nouveaux messages au flux de référence
2. Si flux de référence INCONNU : construis-en un partiel
3. Pour chaque anomalie, indique :
   - NOUVELLE    : jamais vue dans le flux de référence
   - RECURRENTE  : déjà signalée, fréquence en hausse
   - RESOLUE     : anomalie précédente qui disparaît
4. Si aucun nouveau chemin découvert : retourne "flowchart": "NO_CHANGE"
5. Priorité : détection d'anomalies > description du flux
```

---

## CLASSES ET RECORDS JAVA À CRÉER

```
SnapshotConfig.java          record (depthMode, maxMessages, durationMinutes, fromTimestamp)
KafkaMessage.java            record (topic, partition, offset, timestamp, key, value)
FieldProfileResult.java      record (topics, unificationProposal, warnings)
SchemaUnificationProposal.java record (correlationId, timestamp, status, amount, warnings)
FieldMapping.java            record + méthode toPromptBlock()
ProcessMiningResult.java     record (flowchart, comments, hypotheses, blindSpots, anomalies)
AnomalyReport.java           record (id, topic, type, severity, fields, description, cause, ksql)

FieldProfilingService.java   @Service
LlmAnalysisService.java      @Service
KafkaSnapshotReader.java      @Component
KafkaLiveConsumer.java        @Component  (WindowBuffer + trigger taille/timeout)
SseEmitterManager.java        @Component
ProcessMiningController.java  @RestController
```

---

## COMPOSANTS REACT À CRÉER

```
ProcessMiningPage.jsx        page principale, orchestration des étapes
TopicSelectorPanel.jsx       multiselect topics + config profondeur
SchemaValidationPanel.jsx    affichage + édition du schéma unifié proposé
MermaidRenderer.jsx          rendu flowchart Mermaid (lib: mermaid.js)
AnomalyTable.jsx             tableau des anomalies snapshot (sévérité, type, topic)
LiveStatusBar.jsx            indicateur connexion SSE + stats fenêtre
AnomalyFeed.jsx              flux temps réel anomalies (badges NEW/RECURRENT/RESOLVED)
```

---

## CONTRAINTES TECHNIQUES

- Java 21 : utiliser les records, switch expressions, text blocks (`"""`)
- Spring Boot : `@ConfigurationProperties` pour les paramètres Kafka et Claude API
- Claude API : modèle `claude-sonnet-4-20250514`, `max_tokens: 4096`
- Consumer Kafka profilage : group id temporaire avec `enable.auto.commit=false`
- Live consumer : WindowBuffer déclenché par taille (défaut 100 msg) OU timeout (défaut 30s)
- SSE : `SseEmitter` avec timeout 5 minutes, heartbeat toutes les 15s
- Cache FieldMapping : Caffeine, TTL 30 minutes
- Gestion XML : convertir en Map avant sérialisation JSON pour l'envoi au LLM
- Gestion AVRO : déserialiser via Schema Registry si disponible, sinon inférer le schéma

---

## ORDRE D'IMPLÉMENTATION SUGGÉRÉ

1. `KafkaMessage` + `SnapshotConfig` + `KafkaSnapshotReader`
2. `FieldMapping` + `FieldProfileResult` + `SchemaUnificationProposal`
3. `FieldProfilingService` (appel Claude étape 1)
4. `ProcessMiningResult` + `AnomalyReport`
5. `LlmAnalysisService` (snapshot puis live)
6. `KafkaLiveConsumer` + `SseEmitterManager`
7. `ProcessMiningController` (endpoints REST + SSE)
8. React : `SchemaValidationPanel` → `MermaidRenderer` → `AnomalyTable` → `AnomalyFeed`
