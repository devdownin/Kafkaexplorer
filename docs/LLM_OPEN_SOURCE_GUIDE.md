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
2.  **Contraintes JSON Strictes** : Utilisation d'instructions "Return ONLY JSON" pour éviter les textes de bavardage.
3.  **Mapping Prévue** : L'ÉTAPE 2 (Validation Schéma) est cruciale. En validant manuellement le mapping, vous facilitez énormément le travail du LLM lors de la reconstruction du flowchart (ÉTAPE 3).

---

## 🕵️ Dépannage (Troubleshooting)

### Le modèle répond avec du texte avant/après le JSON
Si vous utilisez un modèle très petit (ex: Llama 3.2 1B), il peut arriver qu'il "discute".
- **Solution** : Kafka Explorer nettoie automatiquement les balises markdown (```json ... ```), mais privilégiez les versions "Instruct" ou "Coder" des modèles.

### Latence élevée en mode LIVE
- **Solution** : Utilisez un modèle quantizé (Q4_K_M ou Q8_0) ou réduisez la `snapshot-window-size` dans la configuration.

---
*Astuce : Pour une analyse de production sans GPU, le modèle **Qwen 2.5-Coder 7B** en quantification 4-bit (via Ollama) offre le meilleur rapport précision/performance.*
