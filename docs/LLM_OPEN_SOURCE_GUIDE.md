# Configuration & Usage — Open Source LLM for Kafka Explorer

Ce guide explique comment configurer et exploiter des modèles d'IA **Open Source** et **légers** pour l'analyse de flux Kafka (Process Mining).

---

## 🏗️ Architecture d'Inférence Locale

Kafka Explorer communique avec vos modèles locaux via une API compatible OpenAI. Vous pouvez utiliser plusieurs moteurs d'inférence :

1.  **Ollama** (Recommandé) : Simple, performant, tourne sur Windows/Mac/Linux.
2.  **vLLM** : Idéal pour les serveurs Linux avec GPU.
3.  **LM Studio** : Interface graphique simple pour explorer les modèles GGUF.

### Exemple avec Ollama

1.  **Installez Ollama** : [ollama.com](https://ollama.com)
2.  **Téléchargez un modèle adapté** :
    ```bash
    ollama run qwen2.5-coder:7b
    ```
3.  **Configurez l'application** (`src/main/resources/application.yml`) :
    ```yaml
    claude:
      provider: OPENAI_COMPATIBLE
      base-url: http://localhost:11434/v1
      model: qwen2.5-coder:7b
    ```

---

## 🧠 Modèles Recommandés (SOTA 2025)

Pour le **Process Mining**, l'IA doit être excellente en extraction JSON et en logique temporelle.

| Modèle | Taille | Pourquoi ? |
| :--- | :--- | :--- |
| **Qwen 2.5-Coder 7B** | ~5 Go | **Le champion.** Suivi de schéma JSON parfait, excellente logique de code. |
| **Llama 3.2 3B** | ~2 Go | **Ultra-rapide.** Parfait pour le mode LIVE (SSE) sur de petites configurations. |
| **DeepSeek-R1-Distill-Qwen-7B** | ~5 Go | **Raisonnement (CoT).** Le meilleur pour expliquer les anomalies complexes. |

---

## 🛠️ Optimisations pour les Petits Modèles

Les petits modèles (1B à 7B) sont plus sensibles à la structure des prompts. Kafka Explorer a été optimisé pour :

1.  **Prompts Concis** : Réduction du bruit pour focaliser l'attention du modèle.
2.  **Sortie JSON contrainte, pas seulement demandée** : un schéma JSON voyage avec la requête
    (`output_config.format` chez Anthropic, `response_format: {type: json_schema}` sur le chemin
    compatible OpenAI), de sorte que le décodeur ne *peut* pas produire autre chose. C'est sur un
    petit modèle que cela compte le plus, lui qui est bien plus enclin à broder autour du JSON
    demandé. `claude.structured-output` vaut `AUTO` par défaut : actif là où le support est connu
    (Anthropic, Ollama), laissé de côté sur une passerelle `OPENAI_COMPATIBLE` quelconque, dont
    certaines répondent 400 à un `response_format` qu'elles ne connaissent pas — et le client se
    dégrade tout seul, avec un réessai sans schéma, plutôt que de vous annoncer une panne. La
    consigne "Return ONLY JSON" reste dans le prompt, et le nettoyage des balises markdown reste
    en filet : c'est ce qui rattrape les chemins où aucun schéma ne s'applique.
3.  **Mapping Prévue** : L'ÉTAPE 2 (Validation Schéma) est cruciale. En validant manuellement le mapping, vous facilitez énormément le travail du LLM lors de la reconstruction du flowchart (ÉTAPE 3).

---

## 🕵️ Dépannage (Troubleshooting)

### Le modèle répond avec du texte avant/après le JSON
Si vous utilisez un modèle très petit (ex: Llama 3.2 1B), il peut arriver qu'il "discute".
- **Solution** : Kafka Explorer nettoie automatiquement les balises markdown (```json ... ```), mais privilégiez les versions "Instruct" ou "Coder" des modèles.

### Latence élevée en mode LIVE
- **Solution** : Utilisez un modèle quantizé (Q4_K_M ou Q8_0) ou réduisez la `snapshot-window-size` dans la configuration.

### L'analyse ignore des messages qu'elle a pourtant reçus
C'est le défaut le plus silencieux de cette page, et il n'a rien à voir avec le modèle : **le
prompt ne tient pas dans sa fenêtre de contexte**. Ollama donne 4 096 jetons à un modèle sauf si
la machine a la VRAM pour davantage, la requête compatible OpenAI envoie `model`, `messages`,
`max_tokens`, `temperature` et `stream` — jamais `num_ctx`, que cet endpoint ne lirait pas depuis
le corps de toute façon — et `process-mining.prompt-char-budget` vaut 120 000 caractères, soit
~30 000 jetons. Ollama ne refuse pas l'excédent : il enlève les messages les plus anciens jusqu'à
ce que le prompt entre, et le journalise en DEBUG. L'analyse répond donc depuis une fraction de
ce qu'on lui a donné, sans que rien ne dise laquelle.

- **Solution** : posez les deux moitiés ensemble, comme le fait `docker-compose-llm.yml` —
  `OLLAMA_CONTEXT_LENGTH=16384` côté serveur (llama.cpp : `-c 16384`, vLLM : `--max-model-len`)
  et `PROCESS_MINING_PROMPT_CHAR_BUDGET=16000` côté application. En élargir une seule n'apporte
  rien, ou tronque de nouveau. Le coût d'une fenêtre plus large est le cache KV : ~2 Go pour un
  7B à 16k.
- **Vérification** : sur l'API native d'Ollama, `prompt_eval_count` dans la réponse dit combien
  de jetons ont *réellement* été lus.

---

## 🚀 Usages Spécialisés du LLM Local

L'utilisation d'un LLM spécialisé en local permet de transformer l'exploration technique (SQL) en une véritable intelligence métier. Voici les cas d'usage implémentés :

### 1. Profilage Sémantique Automatisé (`FieldProfilingService`)
Le LLM analyse des échantillons de messages pour "comprendre" la donnée sans intervention humaine :
*   **Détection d'Identifiants de Corrélation** : Repérage des champs pivots (`order_id`, `saga_id`, etc.) présents sur plusieurs topics pour reconstruire le cycle de vie d'un objet métier.
*   **Classification Temporelle** : Distinction entre les timestamps techniques et les dates métiers critiques (`expected_delivery`, `payment_date`).
*   **Unification des Statuts** : Cartographie sémantique des états (ex: mapper `STATE='OK'` et `STATUS='SUCCESS'` vers un état canonique).

### 2. Reconstruction de Processus (Process Mining)
Le LLM transforme des séquences de messages techniques en flux métier lisibles :
*   **Génération de Flowcharts Mermaid** : Production automatique de diagrammes `flowchart TD` visualisant les services, topics et décisions.
*   **Mode LIVE (Streaming)** : Analyse de fenêtres glissantes de messages via SSE pour détecter des changements de structure de flux en temps réel.
*   **Identification des "Angles Morts"** : Signalement des topics ou services manquants pour avoir une vision complète du processus.

### 3. Détection d'Anomalies Complexes
Le LLM est capable d'identifier des problèmes que le SQL traditionnel détecte difficilement :
*   **SEQUENCE** : Événements arrivant dans le mauvais ordre ou étapes sautées.
*   **TEMPORAL** : Délais anormaux entre deux étapes corrélées (latence métier).
*   **STRUCTURAL** : Valeurs aberrantes ou champs manquants dans des payloads JSON/XML imbriqués.
*   **BUSINESS** : Violations de règles métier (ex: passage direct de "Commande créée" à "Livrée" sans "Paiement").

### 4. Aide à la Remédiation (`ksqlSuggestion`)
Pour chaque anomalie détectée, le LLM propose une **requête qui la ferait apparaître** — le champ
s'appelle `ksqlSuggestion` et l'interface l'affiche sous « KSQL / Flink SQL Suggestion ». C'est une
piste à lire, pas une commande à exécuter : le moteur embarqué ici est **Flink SQL**, sa liste
blanche n'accepte que `SELECT`, `EXPLAIN` et `CREATE TABLE`, et un `CREATE STREAM` — syntaxe
ksqlDB — y serait refusé. Transposez-la avant de la lancer dans l'éditeur.

---
*Astuce : Pour une analyse de production sans GPU, le modèle **Qwen 2.5-Coder 7B** en quantification 4-bit (via Ollama) offre le meilleur rapport précision/performance.*
