# Un petit modèle embarqué (Gemma) pour les requêtes et les métriques — étude et conception (2026-11)

L'application sait déjà parler à un modèle, mais pour une seule chose : Process Mining. L'éditeur
SQL et la page Métriques — les deux écrans où l'on *écrit* du Flink SQL, et où un débutant bute —
n'en tirent rien. Ce document étudie ce qu'il faudrait pour qu'un petit modèle local de la famille
Gemma y aide : où le faire tourner, à quoi l'employer, et comment l'encadrer pour qu'une réponse
fausse ne passe jamais pour une réponse juste.

> **Statut.** **Les lots 0 à 3 sont faits** (section 8). Le lot 0 a été **mesuré** le 2026-11-11 sur
> quatre modèles locaux ; ses résultats et la décision qu'ils emportent sont en section 7.1. Les
> chiffres de taille et de mémoire de la section 3 viennent de sources publiques ; ceux de la
> section 7.1 sont mesurés. Ce qui est affirmé du code a été lu dans le code et nomme son fichier.
>
> **Écarts entre la conception ci-dessous et ce qui a été livré**, chacun pour une raison :
>
> - `GET /api/assistant/status` rend `localDeployment` et non `dataLeavesHost` : c'est
>   `ClaudeConfig.isLocalDeployment()` lu sur l'adresse *effective* de l'assistant, la règle que
>   les Réglages appliquent déjà — une seconde définition de « local » aurait fini par diverger.
> - **La clé d'API ne suit pas une surcharge d'adresse.** Quand `explorer.assistant.provider` ou
>   `base-url` envoie l'assistant ailleurs que `claude.*`, la clé de `claude.*` n'est pas transmise
>   (le précédent est `ClaudeConfig.probeCopy`) ; une surcharge hébergée le dit alors par
>   `configurationProblem()`.
> - `include-sample-values` n'existe pas : aucune valeur de message n'est jamais envoyée, et un
>   réglage sans effet serait le bouton inerte que `check-config-yaml.py` traque.
> - Le mode `EXPLAIN` (U4) n'est pas livré : sans critère de réussite, il n'avait rien à valider.
> - Une proposition qui n'est pas une lecture (`INSERT`, `DROP`…) n'est **jamais** rendue, même
>   non validée : `sql` vaut `null` et le statut `OUT_OF_SCOPE`.
> - Un appel concurrent est refusé en `BUSY` (statut 200) plutôt que mis en file.
> - Les compteurs `explorer_assistant_requests_total{mode,outcome}` (§6) sont livrés avec le lot 1.
>
> Le code : `config/AssistantProperties`, `service/Assistant{Llm,ContextService,Prompts,SqlGate,
> Service}`, `FlinkSqlService.planCheck` / `sourceSchema`, `web/AssistantController` ; côté SPA
> `pages/sqlAssistant.ts` et `components/assistant/`.

**La recommandation en quatre lignes.**

1. Faire tourner le modèle **à côté** de la JVM (conteneur llama.cpp / Ollama livré par un overlay),
   pas **dans** la JVM — la section 2 dit pourquoi « directement dans l'appli » ne doit pas vouloir
   dire « dans le même processus ».
2. Commencer par **corriger une requête refusée** et **générer un SELECT sur des tables nommées**,
   pas par un chat ni par de l'autocomplétion.
3. **Le modèle propose, le planificateur dispose** : toute sortie passe par `SqlAst`, la liste
   blanche et un `EXPLAIN` avant d'être montrée, et rien ne s'exécute ni ne s'enregistre tout seul.
4. Choisir le modèle **par une évaluation** sur les topics de la démo, pas par réputation — le
   candidat est Gemma 4 E2B/E4B, le tenant du titre est le `qwen2.5-coder:7b` que
   `compose/ollama.yml` livre déjà.

---

## 1. Ce qui existe, et ce qui manque

### 1.1 La plomberie LLM est déjà là

| Élément | Fichier | Ce qu'il apporte à ce projet |
|---|---|---|
| Abstraction `LlmClient` (`generateWithMeta(system, user, schema)`) | `service/LlmClient.java` | Un nouvel usage n'a pas à connaître le fournisseur |
| Cinq fournisseurs, dont `OLLAMA` et `OPENAI_COMPATIBLE` | `config/ClaudeConfig.java`, `service/LlmClientFactory.java` | Un llama.cpp ou un Ollama local est déjà joignable sans une ligne de Java |
| Sortie contrainte par schéma JSON, avec repli mémorisé par modèle | `service/LlmOutputSchema.java`, `OpenAiCompatibleLlmClient`, `SchemaRefusalMemory` | Le point où un petit modèle échoue le plus — broder autour du JSON — est déjà fermé |
| Client reconstruit quand la configuration bouge | `service/LlmClientProvider.java` | Repointer le modèle depuis Settings fonctionne |
| Coût, modèle servi, schéma envoyé ou non | `domain/LlmUsage.java`, `LlmResponse` | La provenance d'une réponse est déjà un type |
| Pile locale livrée | `compose/ollama.yml` | Fenêtre de contexte, budget de prompt et délai y sont déjà réglés **ensemble** |
| Diagnostic de configuration | `ClaudeConfig.configurationProblem()` | « Pas de modèle » a déjà une phrase |

### 1.2 Mais un seul écran s'en sert

`LlmClientProvider` n'a que deux consommateurs : `FieldProfilingService` et `LlmAnalysisService`,
tous deux Process Mining (plus `test-llm` dans `ConfigController`). `QueryController` et
`MetricController` n'appellent aucun modèle.

### 1.3 Et ces deux écrans ont déjà des aides déterministes

C'est la contrainte de conception la plus importante : le modèle ne doit **rien refaire** de ce
que le code sait déjà faire exactement.

- `SqlAst` lit la forme d'une requête avec la grammaire de Flink, sans catalogue.
- `POST /api/query/validate` (`SqlQueryValidator`) rejette une faute de syntaxe avec ligne et
  colonne ; `SqlErrorClassifier` sépare l'erreur de l'utilisateur de la panne du moteur.
- `SchemaInferenceService` + `DdlGeneratorService` donnent les colonnes et les types d'un topic.
- `WindowAssistant` construit les fenêtres TVF ; `DataModelSqlService` construit les jointures.
- `MetricSuggestionService` propose des KPI **adossés à une observation**, et
  `POST /api/metrics/preview-template` tranche avant l'enregistrement.

Ce qui manque est donc étroit et précis : **passer d'une intention en langue naturelle à du Flink
SQL**, et **d'un message du planificateur à une correction**. Le reste — vérifier, borner,
exécuter — existe.

---

## 2. « Directement dans l'appli » : quatre façons, une recommandée

Un fait cadre toute la discussion : **les poids ne tiendront jamais dans le JAR ni dans l'image.**
Un Gemma 4 E2B quantifié en 4 bits pèse de l'ordre de 3 Go (section 3). Quelle que soit l'option,
il y a donc un second artefact à télécharger et un volume pour le garder. « Un seul artefact » n'est
pas sur la table ; la seule question est *quel processus fait l'inférence*.

| | A. JNI dans la JVM | B. Java pur dans la JVM | C. Conteneur voisin | D. Processus fils géré |
|---|---|---|---|---|
| Moteur | llama.cpp via `java-llama.cpp` (`de.kherud:llama` 4.2.0, ou le fork `net.ladenthin:llama` 5.0.x, plus récent) | Jlama (API Vector) | `llama-server` ou Ollama, API OpenAI | L'appli télécharge et lance `llama-server` |
| Nouveau code Java | Un `LlmClient` + cycle de vie du modèle | Idem | **Aucun** côté client | Gestion de binaire, de port, de redémarrage |
| Dépendance Maven | Oui, avec natifs par OS/arch | Oui | Non | Non |
| Isolation des pannes | **Aucune** : un crash natif tue la JVM | Bonne (pas de natif) | Totale | Bonne |
| Mémoire | Hors tas, invisible de `MaxRAMPercentage` | Dans/à côté du tas | Bornée par son propre `mem_limit` | Hors JVM, même conteneur |
| Vitesse CPU | Référence | Inférieure à llama.cpp (à mesurer) | Référence | Référence |
| GPU | Recompilation des natifs | Non | Image `-cuda` / overlay, comme `spectra-hub.gpu.yml` | Binaire par plateforme |

### Pourquoi pas A (JNI en processus)

Quatre raisons, toutes propres à ce dépôt :

- **Le processus héberge déjà Flink et l'UI.** Un `SIGSEGV` dans du code natif n'est pas une
  exception : il emporte le MiniCluster, les métriques planifiées et la page qui aurait affiché
  l'erreur. L'application prend partout le parti inverse — un moteur en panne *dit* qu'il est en
  panne.
- **La mémoire échappe au seul réglage qui la borne.** Les deux images posent
  `-XX:MaxRAMPercentage=75.0` : le tas prend 75 % de la limite du conteneur. 3 à 5 Go de poids
  mappés hors tas dans les 25 % restants, c'est le code de sortie 137 que `compose/limits.yml`
  décrit, sur une limite par défaut de 2 Go.
- **L'image d'exécution est `eclipse-temurin:25-jre-alpine`, donc musl.** Les binaires natifs
  publiés par les bindings Java visent d'ordinaire la glibc ; il faudrait soit changer d'image de
  base, soit compiler llama.cpp pour musl sur `linux/amd64` **et** `linux/arm64`, les deux
  plateformes que `release.yml` publie et vérifie. *À vérifier avant de retenir cette option.*
- **Chaque boucle de build la paie.** `verify-offline.sh` compile avec `javac` contre un classpath
  résolu à la main ; un natif chargé au démarrage y ajoute un cas, et un avertissement JVM de plus
  (`System::load` restreint) à la liste de ceux qu'on a décidé de ne pas masquer par un drapeau.

### Pourquoi pas B (Java pur)

Jlama demande le module incubateur `jdk.incubator.vector`, donc un `--add-modules` au lancement —
exactement le genre de drapeau que `CLAUDE.md` refuse d'ajouter « par précaution ». Son README
annonce Gemma et Gemma 2 ; le support de Gemma 4 n'est pas établi. Et l'inférence partagerait le
tas avec Flink.

### Pourquoi pas D (processus fils)

C'est la seule option qui serve l'utilisateur du JAR nu (`./mvnw spring-boot:run`, sans Docker).
Mais elle fait de l'application un gestionnaire de binaires : un `llama-server` par OS et par
architecture à télécharger, à vérifier (ce dépôt signe son JAR et épingle ses images par digest —
un binaire tiré à l'exécution sans somme de contrôle serait le maillon faible), à relancer. À
garder pour plus tard, si le besoin est nommé.

### C, donc : un overlay, zéro client nouveau

« Directement dans l'appli » se lit alors comme l'utilisateur le vit : **une commande, aucune clé,
rien qui sorte de la machine**, et l'assistant est dans l'éditeur.

```bash
docker compose -f docker-compose.yml -f compose/ollama.yml up -d   # existe déjà
```

Le chemin le plus court réutilise `compose/ollama.yml` tel quel, en rendant son modèle
paramétrable (il est écrit en dur deux fois : dans `ollama-pull-model` et dans `CLAUDE_MODEL`).
Tout ce qui a coûté cher à régler y est déjà — le fournisseur `OLLAMA` qui active la sortie
contrainte, la fenêtre et le budget posés ensemble, le délai de 300 s, l'UI qui n'attend pas le
téléchargement. Un overlay `llama-server` dédié (l'image `ghcr.io/ggml-org/llama.cpp:server-b9828`
est déjà épinglée par `compose/spectra-hub.yml`) ne se justifie que si le lot 0 mesure un écart.

L'utilisateur du JAR nu garde ce qu'il a aujourd'hui : un Ollama installé sur sa machine et
`provider: OLLAMA`, ou un modèle hébergé.

---

## 3. Quel modèle

| Candidat | Licence | Poids Q4 (publié) | RAM indicative | Rôle dans l'étude |
|---|---|---|---|---|
| **Gemma 4 E2B** | Apache 2.0 | ~3,1 Go | 4–5 Go | Candidat « tourne partout » |
| **Gemma 4 E4B** | Apache 2.0 | ~5,0 Go | 5–8 Go | Candidat « qualité » |
| `qwen2.5-coder:7b` | Apache 2.0 | ~5 Go | ~6–8 Go | **Tenant du titre** : déjà livré, spécialisé code |
| Gemma 3 1B / 270M | Gemma Terms of Use | < 1 Go | 1–2 Go | Écarté *a priori* (voir ci-dessous) |
| Modèle hébergé (`openai/gpt-4o-mini`, défaut actuel) | — | — | — | Plafond de référence |

Trois remarques.

- **La licence compte.** Gemma 3 est distribué sous des conditions d'utilisation propres à Google,
  qui ne sont pas une licence libre ; Gemma 4 est passé sous Apache 2.0. Pour un projet AGPL dont
  une pile *télécharge* le modèle à la place de l'utilisateur, c'est ce qui rend « Gemma par
  défaut » défendable, et c'est une raison de viser la génération 4.
- **Les très petits (≤ 1B) sont écartés par la tâche, pas par principe.** Flink SQL est un dialecte
  peu représenté (TVF de fenêtrage, attributs de temps, backticks) ; un modèle de 270 M de
  paramètres sert à classer ou à extraire, pas à écrire une jointure. Ils restent mesurables au
  lot 0 pour le seul cas « corriger une faute », si quelqu'un veut le chiffre.
- **Rien ne dit que Gemma batte le modèle déjà livré.** `qwen2.5-coder` est entraîné pour le code ;
  Gemma est généraliste. L'évaluation de la section 7 décide, et « on garde qwen » est une issue
  acceptable de cette étude.

### Un modèle ou deux ?

Process Mining et l'assistant n'ont pas le même profil : le premier envoie jusqu'à ~30 000 tokens
de digests, le second quelques milliers de tokens de schéma. Un petit modèle rapide convient au
second et pas forcément au premier. La configuration proposée (5.8) permet donc à l'assistant
d'avoir **son propre modèle**, et par défaut d'hériter de `claude.*` — une seule chose à régler
pour qui n'en veut qu'un. Deux modèles chargés, c'est deux fois la mémoire : c'est un choix de
déploiement, pas un défaut.

---

## 4. Les usages proposés, par ordre de valeur

| # | Usage | Entrée | Sortie | Vérifiable par la machine ? |
|---|---|---|---|---|
| **U1** | **Corriger une requête refusée** | Le SQL, le message du planificateur (ligne/colonne), les schémas des tables citées | Un SQL corrigé + une phrase | **Oui** : le planificateur l'accepte ou non |
| **U2** | **Question → SELECT** | Une phrase, des tables choisies ou nommées | Un SELECT + une phrase | En partie : valide, mais pas forcément *la bonne question* |
| **U3** | **Description → brouillon de métrique** | Une phrase, un topic | Nom, type Prometheus, SQL avec `AS metric_value`, description | En partie, puis `preview-template` |
| U4 | Expliquer une requête | Un SQL | Un paragraphe | Non |

**U1 d'abord**, et ce n'est pas l'ordre intuitif. C'est le plus petit prompt, la tâche la plus
bornée (modifier, pas inventer), celle où le dépôt fournit déjà l'entrée parfaite — un message
classé `USER_ERROR` avec sa position — et la seule dont le succès est **décidable** sans humain.
C'est aussi là qu'un petit modèle a le plus de chances d'être réellement bon.

**Ce qui n'est pas proposé**, et pourquoi :

- *Autocomplétion en ligne par le modèle* — sur CPU la latence se compte en secondes, et
  l'éditeur a déjà une complétion déterministe sur le périmètre (`sqlScope.ts`).
- *Un chat libre* — pas de critère de réussite, et une surface d'injection sans contrepartie.
- *Un agent qui exécute* — le serveur MCP est la surface pour cela, avec ses gardes ; ne pas en
  ouvrir une seconde, sans gardes, derrière l'éditeur.
- *Des seuils d'alerte inventés* (U3) — le modèle n'a vu aucune valeur ; un seuil plausible sorti
  de nulle part est exactement le « faux précis » que ce dépôt refuse. Les seuils restent vides.
- *Une proposition de KPI sans observation* — `MetricSuggestionService` tient la règle « une
  proposition n'apparaît qu'adossée à une observation » ; U3 traduit une demande **de
  l'utilisateur**, il ne suggère rien de lui-même.

---

## 5. Conception

### 5.1 Le principe : le modèle propose, le planificateur dispose

```
question ──▶ contexte (déterministe) ──▶ prompt ──▶ modèle (JSON contraint)
                                                        │
        réponse + provenance ◀── porte déterministe ◀───┘
                                   │  échec : UN tour de réparation, avec le message du planificateur
                                   └─ encore en échec : le SQL est rendu AVEC son erreur, jamais sans
```

Trois invariants, repris des règles que le reste de l'application tient déjà :

1. **Rien ne s'exécute ni ne s'enregistre tout seul.** Le SQL généré ouvre un onglet ; la métrique
   pré-remplit l'éditeur. Run et Save restent des gestes (règle 2 de `metricSuggestions.ts`).
2. **Une sortie non validée le dit.** `validated: false` et l'erreur voyagent avec le SQL ; l'UI
   n'affiche jamais un SQL généré sans son statut.
3. **La réponse dit ce qu'elle a vu.** Tables incluses dans le prompt, tables omises *par leur
   nom*, modèle servi — la même idée que `Coverage` côté MCP.

### 5.2 Construire le contexte (`AssistantContextService`)

Le problème d'un petit modèle est sa fenêtre : la démo a 79 topics, on ne peut pas tout décrire.
La sélection est **déterministe**, dans cet ordre, jusqu'au plafond :

1. les tables cochées dans le navigateur de schéma (périmètre explicite) ;
2. les sources de la requête en cours d'édition (`SqlAst`), pour U1 ;
3. les tables ou topics **nommés dans la question**, résolus par la règle unique de
   `withTopicTableNames` (`demo.orders` → `demo_orders`) ;
4. à défaut, une correspondance lexicale entre les mots de la question et les noms de tables.

Plafonds proposés : 5 tables, 40 colonnes par table, noms et types seulement
(`FlinkSqlService.getSchema` / inférence). Pas d'embeddings au premier lot : ce serait un second
modèle à servir pour un classement que les noms de topics font déjà correctement.

**Aucune valeur d'échantillon par défaut.** Les valeurs sont (a) des données métier, (b) du texte
contrôlé par quiconque produit dans le topic — donc un vecteur d'injection. Les noms de colonnes
suffisent à écrire la requête ; un réglage explicite peut ajouter quelques valeurs distinctes par
colonne, pour les littéraux d'un `WHERE`.

**La fenêtre et le budget se règlent ensemble** — la leçon écrite en tête de `compose/ollama.yml` :
Ollama tronque en silence ce qui dépasse. Cible : fenêtre de 8 192 tokens, prompt plafonné à
~12 000 caractères, et la réponse nomme ce qui a été coupé pour tenir.

### 5.3 Le prompt

- **Préfixe stable** : rôle, règles du dialecte, quelques exemples. Les règles sont celles que le
  dépôt a payées : backticks pour les identifiants, `proc_time` calculée (jamais dans un
  `SELECT *` vers un sink), `event_time` et son watermark, syntaxe exacte des TVF
  (`TABLE(TUMBLE(TABLE t, DESCRIPTOR(event_time), INTERVAL '1' MINUTE))`), pas d'`ORDER BY` non
  borné, pas d'`EXISTS` corrélé, un seul statement, `SELECT` uniquement.
- **Exemples** tirés de `docs/QUERY-EXAMPLES.md` — ils sont déjà tenus vrais par
  `DemoQueryExamplesTest`, donc un exemple du prompt ne peut pas dériver du produit.
- **Suffixe variable** : schémas retenus, puis la demande. Dans cet ordre, pour que le cache de
  préfixe du serveur d'inférence serve d'un appel à l'autre (gain à mesurer au lot 0).
- Tout ce qui vient du cluster (noms de topics, de colonnes, messages d'erreur) est encadré comme
  **donnée** et jamais interpolé dans les consignes.

### 5.4 Le schéma de sortie (dans `LlmSchemas`)

```json
{
  "status": "OK | NEEDS_CLARIFICATION | OUT_OF_SCOPE",
  "sql": "…",
  "explanation": "une ou deux phrases",
  "missing": "ce qui manque pour répondre, si status ≠ OK"
}
```

Pas de champ `confidence` : l'auto-évaluation d'un petit modèle est du bruit présenté comme une
mesure. La seule « confiance » affichée est celle de la porte. `NEEDS_CLARIFICATION` donne au
modèle une sortie honnête quand la table n'est pas dans le contexte — sans elle, il invente une
colonne.

### 5.5 La porte déterministe (`AssistantSqlGate`)

Dans l'ordre, chaque étape réutilise une classe existante :

| Étape | Avec | Refuse |
|---|---|---|
| 1. Un seul statement, `SELECT` (ou `WITH … SELECT`) | la classification de `FlinkSqlService.executeSql` | Tout DDL, tout `INSERT`, un CTAS |
| 2. Sources ⊆ tables du contexte | `SqlAst` | Une table hallucinée — *par son nom*, au lieu de laisser l'auto-enregistrement chercher un topic |
| 3. Garde cross-join / tables système | `SqlQueryValidator` | Le produit cartésien, sous ses deux écritures |
| 4. Planification à blanc | `EXPLAIN` après enregistrement des sources | Colonne inconnue, type incompatible, forme non planifiable en streaming |
| 5. (U3) Forme de métrique | alias `metric_value`, `MetricService.isSingleTableRead` | Une métrique qui resterait `pending` pour toujours |

Sur un refus classé `USER_ERROR`, **un** tour de réparation : le SQL, le message rendu lisible
(`SqlErrorClassifier.readable`), et la consigne de ne changer que ce qui est fautif. Un seul, parce
que chaque tour coûte des dizaines de secondes sur CPU et qu'un modèle qui échoue deux fois sur la
même requête échoue en général la troisième. Un refus `ENGINE_BUSY` ou `ENGINE_ERROR` n'est **pas**
renvoyé au modèle : ce n'est pas sa faute, et la réponse dit « non validé : moteur occupé ».

**Ce que la porte ne peut pas voir** doit être dit dans le document comme dans l'UI : un SQL valide
qui répond à *une autre question* (`COUNT(*)` au lieu de `COUNT(DISTINCT …)`, la mauvaise colonne
de temps). C'est la « réponse fausse plausible », le pire des deux sens. Trois parades, aucune
complète : l'explication est affichée à côté du SQL, les tables lues sont nommées, et rien ne
s'exécute sans un geste. La section 7 *mesure* ce taux au lieu de le supposer faible.

### 5.6 API

```
GET  /api/assistant/status   → { enabled, problem, model, dataLeavesHost }
POST /api/assistant/sql      → mode GENERATE | FIX | EXPLAIN
POST /api/assistant/metric   → brouillon de MetricConfig
```

```json
// POST /api/assistant/sql
{ "mode": "FIX", "question": null, "sql": "SELECT id, FROM orders",
  "error": "Encountered \"FROM\" at line 1, column 12", "tables": ["demo_orders"] }

// réponse
{ "status": "OK", "sql": "SELECT id FROM demo_orders", "explanation": "…",
  "validated": true, "validationError": null, "repairRounds": 0,
  "tablesConsidered": ["demo_orders"], "tablesOmitted": [],
  "model": "gemma4:e2b", "usage": { … }, "durationMs": 8400 }
```

- Un contrôleur propre sous `/api/assistant` — règle de routage SPA : `/api/**` a son mapping, et
  aucun contrôleur ne se pose sur une route cliente.
- Synchrone au premier lot, avec un délai **terminal** (pas de réessai : rejouer le prompt sur un
  modèle qui a montré qu'il lui faut plus de temps triple l'attente). Le streaming demanderait
  d'étendre `LlmClient`, qui n'en a pas ; à faire si les latences mesurées l'exigent.
- **Un appel à la fois** (sémaphore d'un permis, cohérent avec un serveur d'inférence à un slot).
  Occupé ⇒ une réponse « l'assistant traite une autre demande », pas une file invisible — la même
  lecture que `ENGINE_BUSY` : une attente n'est pas une panne.
- Les erreurs du modèle remontent telles quelles (règle : jamais de `catch` silencieux qui rend
  `null`).

### 5.7 Interface

- **Éditeur** (`components/query/AskBar.tsx`, logique pure dans `sqlAssistant.ts`) : une ligne
  au-dessus de l'éditeur, dans un vrai `<form onSubmit>` avec les contrôles partagés. Le résultat
  ouvre **un nouvel onglet** — ce que fait déjà une requête sauvegardée — avec l'explication, les
  tables lues, et une pastille « généré par *modèle* · accepté par le planificateur » ou
  « non validé : *raison* ».
- **Bandeau d'erreur** (`queryError.ts`) : un bouton « Proposer une correction » sur les seules
  erreurs `USER_ERROR`. La correction s'affiche en diff contre la requête d'origine.
- **Métriques** : « Décrire la métrique » dans l'éditeur de métrique ; le brouillon pré-remplit
  le formulaire, la prévisualisation existante tranche.
- **Assistant éteint** : les entrées n'apparaissent pas, et la page d'aide dit comment l'allumer.
  Un bouton grisé sans raison est le cas « pourquoi je ne vois pas cet outil ? » que la console MCP
  a dû résoudre après coup.
- Si le fournisseur est hébergé, la barre porte le même avertissement que Settings
  (`llmPolicy.ts`) : *les noms de vos topics et colonnes quittent la machine.*

### 5.8 Configuration

```yaml
explorer:
  assistant:
    enabled: false              # posture, comme explorer.mcp.enabled
    provider: ""                # vide = hériter de claude.*
    base-url: ""
    model: ""
    request-timeout-seconds: 120
    max-tables: 5
    max-columns-per-table: 40
    prompt-char-budget: 12000   # à régler AVEC la fenêtre du serveur d'inférence
    repair-rounds: 1
```

- **Éteint par défaut.** Le défaut livré de `claude.provider` est `OPENROUTER` : allumer
  l'assistant d'office enverrait des noms de topics chez un tiers sans qu'on l'ait demandé.
  `compose/ollama.yml` l'allume, parce que là rien ne sort (`EXPLORER_ASSISTANT_ENABLED`
  ailleurs). `demo.sh` / `demo.ps1` restent au lot 4, **et s'éditent ensemble**.
- **Surcharges optionnelles** : `LlmClientFactory.create` prend un `ClaudeConfig` et
  `LlmClientProvider` est un singleton câblé sur celui-ci. Le second modèle demande donc une
  seconde instance du fournisseur de client, construite sur une copie dérivée de la
  configuration — un point de conception à ne pas découvrir à l'implémentation.
- Chaque clé doit entrer dans la table de configuration : `check-config-table.py` le vérifie.

### 5.9 Sécurité et confidentialité

| Risque | Parade |
|---|---|
| Le SQL généré fait autre chose qu'une lecture | Liste blanche `SELECT` à l'étape 1 de la porte, puis la même liste blanche à l'exécution — **aucun privilège nouveau** |
| Injection par un nom de topic/colonne ou un message d'erreur | Encadré comme donnée ; le pire résultat est un `SELECT` sur les tables du contexte, lu par l'utilisateur avant Run |
| Injection par le contenu des messages | Aucune valeur dans le prompt par défaut |
| Fuite vers un fournisseur hébergé | Éteint par défaut ; `dataLeavesHost` dans `/status` ; avertissement dans la barre |
| Identifiants dans le prompt | Les schémas viennent de l'inférence, pas du DDL ; si un DDL y entre un jour, `maskSensitiveProperties()` d'abord, et les prompts journalisés passent par `LogSafe` |
| Épuisement CPU (l'appli n'a pas d'authentification) | Un appel à la fois, délai terminal, éteint par défaut |
| Un agent MCP qui appelle l'assistant | **Pas d'outil MCP** pour l'assistant : l'agent a déjà un modèle, et `kex_sql_query` a déjà ses gardes |

---

## 6. Empreinte et exploitation

- **Mémoire** : le modèle vit dans son conteneur, avec sa propre limite. `compose/limits.yml` ne
  peut nommer que les services de sa base ; la limite du serveur d'inférence est donc une variable
  de l'overlay lui-même. Ordre de grandeur à prévoir : 4–5 Go (E2B) à 8 Go (E4B, 7B), fenêtre
  comprise — *à mesurer*.
- **Premier démarrage** : plusieurs gigaoctets à télécharger. L'UI ne doit pas l'attendre
  (`depends_on: ollama`, jamais le one-shot de téléchargement — déjà le cas), et `/status` doit
  distinguer « modèle en cours de téléchargement » de « assistant en panne ».
- **Latence** : sur CPU, un petit modèle 4 bits produit de l'ordre de quelques tokens à quelques
  dizaines de tokens par seconde ; une réponse de 150 tokens se compte donc en secondes ou en
  dizaines de secondes. C'est acceptable pour « corrige ma requête », pas pour de la complétion —
  d'où le périmètre de la section 4.
- **Observabilité** : compteurs Micrometer `explorer_assistant_requests_total{mode,outcome}` où
  `outcome` ∈ `validated`, `repaired`, `unvalidated`, `clarification`, `error` ; un histogramme de
  durée. Le taux `unvalidated` en production est la mesure continue de ce que la section 7 mesure
  une fois.

---

## 7. Évaluer avant de choisir

Le dépôt a déjà la forme : `LlmAnalysisEvalTest` (`@Tag("llm-eval")`), exclu du build par défaut,
qui **saute au lieu d'échouer** sans modèle configuré. L'assistant suit la même règle avec un
`@Tag("assistant-eval")`, à exclure aux deux mêmes endroits (surefire et `verify-offline.sh`).

- **Jeu de questions** : ~40, écrites **depuis le seeder** (`setup-demo.sh`), jamais capturées sur
  un cluster — la règle du fixture d'éval existant. Cinq familles : filtre, agrégat, fenêtre,
  jointure, métrique ; plus ~10 requêtes cassées pour U1.
- **Critère** : l'**exactitude à l'exécution** — le résultat du SQL généré comparé au résultat
  d'une requête de référence sur les données seedées — et non la ressemblance textuelle. Deux SQL
  différents peuvent être également justes.
- **Mesures par modèle** : taux accepté par la porte au premier jet ; après réparation ; exactitude
  à l'exécution ; **taux de « valide mais faux »** (le chiffre qui compte) ; latence p50/p95 sur
  CPU seul, 4 vCPU ; mémoire résidente.
- **Candidats** : Gemma 4 E2B, Gemma 4 E4B, `qwen2.5-coder:7b`, et le modèle hébergé par défaut
  comme plafond.

**Seuils de décision proposés** (à discuter, mais à fixer *avant* de regarder les résultats) :

| Constat | Décision |
|---|---|
| U1 : ≥ 80 % de corrections acceptées par le planificateur, p95 < 30 s | Livrer U1 avec ce modèle |
| U2 mono-table : ≥ 70 % d'exactitude à l'exécution, « valide mais faux » < 10 % | Livrer U2 mono-table |
| U2 jointures sous ces seuils | Ne pas générer de jointure : renvoyer vers le Data Model, qui les construit exactement |
| Aucun modèle local ne passe U1 | L'assistant n'est proposé qu'avec un modèle hébergé, et le document le dit |

Ce qui ne dépend pas d'un modèle — sélection du contexte, assemblage du prompt, la porte, la
boucle de réparation avec un `LlmClient` scripté — se teste dans `mvn verify`, comme le harnais de
l'agent MCP.

---

### 7.1 Résultats (2026-11-11)

Mesurés par `AssistantEvalTest` sur les 50 cas de `src/test/resources/eval/assistant/cases.json`.
Conditions : 4 vCPU x86_64 sans GPU, 15 Go de RAM ; llama.cpp (`llama-cpp-python` 0.3.36),
fenêtre de 8 192 tokens, poids Q4_K_M vérifiés par SHA-256 ; température 0 et sortie contrainte
par le schéma JSON, comme sur la pile Ollama ; réglages `explorer.assistant.*` par défaut.
Rapports cas par cas : `docs/benchmarks/assistant-eval/` (prompt actuel) et son sous-dossier
`prompt-v1/` (premier passage).

**Prompt actuel (v2)** — après les trois corrections décrites plus bas :

| Modèle (Q4_K_M) | Correctes | Acceptées mais fausses | Non validées | Sans réponse | p50 | p95 | Mémoire de pointe |
|---|---|---|---|---|---|---|---|
| **Gemma 4 E4B** | **48 (96 %)** | 2 (4 %) | 0 | 0 | 25,2 s | 40,5 s | 11,5 Go |
| Qwen3-4B-Instruct-2507 | 47 (94 %) | 1 (2 %) | 1 (2 %) | 1 (2 %) | 20,2 s | 37,0 s | 7,9 Go |
| Gemma 4 E2B | 44 (88 %) | 2 (4 %) | 4 (8 %) | 0 | 16,1 s | 35,8 s | 7,7 Go |

**Premier passage (prompt v1)**, pour mémoire :

| Modèle | Correctes | Acceptées mais fausses | Non validées | Sans réponse | p50 | p95 | Mémoire |
|---|---|---|---|---|---|---|---|
| Gemma 4 E4B | 45 (90 %) | 2 (4 %) | 3 (6 %) | 0 | 27,9 s | 60,3 s | 11,3 Go |
| Qwen3-4B-Instruct-2507 | 42 (84 %) | 3 (6 %) | 5 (10 %) | 0 | 20,0 s | 51,0 s | 7,8 Go |
| Gemma 4 E2B | 36 (72 %) | 1 (2 %) | 13 (26 %) | 0 | 18,9 s | 45,4 s | 7,6 Go |
| `qwen2.5-coder:7b` | 34 (68 %) | 1 (2 %) | 5 (10 %) | 10 (20 %) | 24,8 s | 64,1 s | 11,1 Go |

`qwen2.5-coder:7b` n'a pas été remesuré : il était dernier et ne sert plus l'assistant. La mémoire
est le pic du processus serveur : poids, cache KV de 8 192 tokens et serveur Python.

**Confrontés aux seuils fixés avant la mesure (prompt v2) :**

| Seuil | Gemma 4 E4B | Qwen3-4B | Gemma 4 E2B |
|---|---|---|---|
| U1 : ≥ 80 % de corrections acceptées | 100 % ✓ | 90 % ✓ | 100 % ✓ |
| U1 : p95 < 30 s (sur ce CPU) | 45,0 s ✗ | 37,0 s ✗ | **27,7 s ✓** |
| U2 mono-table : ≥ 70 % justes / < 10 % faux | 100 % / 0 % ✓ | 95 % / 5 % ✓ | 95 % / 5 % ✓ |
| Jointures (mêmes seuils) | 100 % / 0 % ✓ | 100 % / 0 % ✓ | 88 % / 0 % ✓ |
| Tranches de temps (4 cas) | 2 / 4 | 3 / 4 | 0 / 4 |

**Décision.**

- **Gemma 4 E4B sert l'assistant** dans `compose/ollama.yml` (`EXPLORER_ASSISTANT_MODEL`) : le plus
  juste sur les deux passages, aucun refus du planificateur sur le second, et un tag Ollama vérifié
  (`gemma4:e4b`, Ollama ≥ 0.20, la pile en épingle 0.32.6).
- **Qwen3-4B-Instruct-2507 est à égalité dans la marge** (94 % contre 96 %, un cas d'écart sur 50)
  pour 3,6 Go de moins et 20 % plus rapide. C'est le meilleur choix là où la mémoire compte ; son
  tag Ollama exact n'a pas été vérifié ici, d'où le défaut sur Gemma.
- **Gemma 4 E2B pour une petite machine** : le seul à tenir le p95 de 30 s sur 4 cœurs pour la
  correction, mais il ne réussit aucune tranche de temps.
- **`qwen2.5-coder:7b` ne sert plus l'assistant** ; il reste le modèle de Process Mining.
- **Aucun modèle ne tient 30 s de p95 en génération sur ce CPU** ; la latence se mesure sur la
  machine qui servira, avec `bin/assistant-eval.sh`.

**Ce que la mesure a appris sur le produit :**

1. **Deux défauts trouvés par le banc et corrigés avant le premier passage chiffré.** En mode `FIX`,
   une requête qui ne se parse pas ou qui nomme une table absente ne donnait aucune table au
   modèle (un oracle rendant la référence obtenait 47/50). Et toute réparation plantait sur un
   fournisseur local, la somme des coûts déballant un `null` (8 cas sur 50).
2. **Trois faiblesses du prompt, corrigées en v2** :
   - il affirmait que `event_time` porte toujours un watermark, ce qui est faux sur chaque topic
     de démo (un `BIGINT` d'epoch du payload), et chaque modèle tentait une fenêtre dessus ; le
     catalogue annote désormais chaque colonne temporelle avec ce qu'elle est ;
   - son exemple de fenêtre déteignait : Gemma 4 E2B écrivait `FROM TABLE x` dans 10 de ses 13
     refus ; l'exemple est un agrégat par tranche horaire et `FROM TABLE t` est interdit par nom ;
   - `AS count` était refusé et jamais réparé ; les mots réservés sont bannis comme alias par nom.
   Effet : + 6 à + 16 points de requêtes justes selon le modèle, et les non-validées de E2B
   passent de 13 à 4.
3. **Reste ouvert** : les tranches de temps (Gemma 4 E2B groupe par l'alias, ce que Flink refuse ;
   E4B oublie de remultiplier la tranche ou utilise l'ancienne syntaxe `GROUP BY TUMBLE(...)`) ;
   F04 (`'rejected'` en minuscules, ou le mauvais champ) est faux pour les trois.
4. **Une référence est ambiguë** : J06 compte les redélivrances et un `DISTINCT` est défendable.

**Limites.** Le prompt v2 a été corrigé **après** avoir lu les échecs de ces 50 cas : son gain est
donc mesuré sur les cas mêmes qui l'ont inspiré, et c'est une borne haute. Les corrections sont
générales — elles décrivent le catalogue plutôt qu'un cas — mais un jeu de cas tenu à l'écart
serait la mesure honnête. Un seul passage par modèle, 50 cas (4 à 12 par famille, soit ± une
dizaine de points par famille), un seul jeu de topics, et un serveur llama.cpp plutôt qu'Ollama,
la contrainte de schéma y passant par un relais local qui la traduit.

## 8. Plan par lots

| Lot | Contenu | Taille | Dépend de |
|---|---|---|---|
| **0 — Mesurer** *(fait, §7.1)* | Fixture de questions, test `assistant-eval`, prototype de prompt, banc des quatre candidats ; `compose/ollama.yml` paramétré par modèle | M | — |
| **1 — Cœur serveur** *(livré)* | `explorer.assistant.*`, `AssistantContextService`, `AssistantSqlGate`, schéma, `/api/assistant/status` et `/sql` (FIX, GENERATE), tests déterministes | M | 0 concluant |
| **2 — Éditeur** *(livré)* | Bouton « Proposer une correction », `AskBar`, onglet de résultat avec statut | M | 1 |
| **3 — Métriques** *(livré)* | `/api/assistant/metric`, entrée dans l'éditeur de métrique | S | 1 |
| **4 — Livraison** | Overlay/démo (`demo.sh` **et** `demo.ps1`), `.env.example`, `docs/LLM-PROVIDERS.md`, page d'aide, compteurs | S | 2 |
| 5 — *Optionnel* | Option D (processus fils) pour le JAR nu, ou streaming | L | Un besoin nommé |

Le lot 0 peut conclure « ne pas faire », et c'est pour cela qu'il est seul en tête : il coûte un
fixture et un banc, pas une fonctionnalité.

---

## 9. Ce qui reste à vérifier

Ces points sont des hypothèses de ce document, pas des faits établis ici :

1. Les **tags exacts** sous lesquels Ollama publie Gemma 4 E2B/E4B, et la disponibilité de GGUF
   officiels à épingler (par somme de contrôle, comme tout le reste de l'arbre).
2. La **mémoire et la latence réelles** sur une machine CPU ordinaire — les sources publiques
   divergent du simple au double sur l'E2B 4 bits.
3. Que la sortie contrainte par `response_format: json_schema` se comporte avec Gemma comme avec
   `qwen2.5-coder` sur la version d'Ollama épinglée (`0.32.6`) — et que cette version sait charger
   Gemma 4.
4. La compatibilité **musl** des bindings JNI, si l'option A devait un jour être rouverte.
5. La **qualité** elle-même : qu'un modèle de cette taille écrive du Flink SQL juste. C'est
   l'hypothèse la plus fragile de toute l'étude, et la seule que le lot 0 a pour mission de
   trancher.

---

## Sources

Modèles et licences :

- [Gemma — documentation Google (tailles, GGUF)](https://ai.google.dev/gemma/docs/core)
- [Gemma 4 — How to Run Locally (Unsloth)](https://unsloth.ai/docs/models/gemma-4)
- [Gemma 4 dans LM Studio](https://www.lmstudio.ai/models/gemma-4)
- [Gemma 4 E2B — fiche modèle](https://huggingface.co/unsloth/gemma-4-E2b-it)

Moteurs d'inférence côté Java :

- [`de.kherud:llama` sur Maven Central](https://central.sonatype.com/artifact/de.kherud/llama)
- [`net.ladenthin:llama` sur Maven Central](https://central.sonatype.com/artifact/net.ladenthin/llama)
- [Jlama (GitHub)](https://www.github.com/tjake/Jlama)
- [Jlama — présentation InfoQ](https://www.infoq.com/news/2024/05/jlama-llm-inference-java)
