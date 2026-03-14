# Brief de Design : Kafka SQL Explorer (V2 Enrichie)

Ce document fournit les spécifications détaillées pour la refonte de l'interface du **Kafka SQL Explorer**. Il est conçu pour orienter un designer UX/UI ou un outil de génération de design (type Stitch/Google) vers une interface moderne, performante et intuitive.

---

## 🎨 Principes Directeurs
- **Esthétique :** "Industrial Modern". Palette sombre (Dark Mode), contrastes élevés pour la lecture de code (syntaxe SQL).
- **Réactivité :** États de chargement dynamiques (skeletons, spinners teal), transitions fluides entre les vues.
- **Ergonomie "Dev-First" :** Raccourcis clavier (Ctrl+Space), zones de texte extensibles, interactions contextuelles au clic.

---

## 1. Structure Globale & Navigation (`layout.html`)
- **Concept :** Interface "Dark Mode" professionnelle avec des accents colorés (Teal/Cyan) pour la lisibilité technique.
- **Navigation :** Barre latérale ou supérieure persistante (Sticky).
- **Indicateurs d'état :** Un témoin visuel (LED/Badge pulsant) indiquant si la connexion au cluster Kafka est active en temps réel.
- **Notifications :** Système de "Toasts" en haut à droite pour les erreurs SQL ou les alertes système.

## 2. Dashboard : Le Centre de Commande
- **Cartes KPI Dynamiques :** Quatre indicateurs (Topics, Messages, Tables, Jobs) avec icônes. Au survol, effet de "glow". Chiffres animés à l'affichage.
- **Explorateur de Topics (Live Search) :**
    *   **Filtrage Instantané :** Recherche par préfixe ou nom complet filtrant les lignes sans rechargement.
    *   **Badges de Status :** Badge `EMPTY` (gris discret) vs `DLT` (orange "warning" pour les Dead Letter Topics).
- **Surveillance des Jobs :** Liste des requêtes SQL actives avec barre de progression pulsante et bouton "Cancel" (rouge) avec confirmation.

## 3. SQL Editor : Productivité & Intelligence
- **Zone d'Édition :** Éditeur CodeMirror avec coloration syntaxique Flink SQL et auto-complétion intelligente (topics, tables, colonnes).
- **Contrôles de lecture :** Switch entre mode *Earliest* (début du flux) et *Latest* (nouveaux messages).
- **Assistant de Fenêtrage (Window Assistant) :** Modale guidée par étapes. L'utilisateur définit la durée et le type de fenêtre, le SQL est généré et prévisualisé en temps réel.
- **Schema Browser (Sidebar) :** Accordéons interactifs montrant l'arborescence des objets. Un clic sur une colonne l'insère directement dans le code SQL.
- **Résultats :** Grille de données dynamique avec "Sticky Header", export CSV/JSON et statistiques de performance.

## 4. Topic Detail & Query Assistant (L'Innovation)
- **Visualisation de Payload :** Messages Kafka affichés sous forme de blocs formatés (JSON/XML).
- **Query Assistant (Mode Interactif) :**
    *   **Hover Effect :** En mode assistant, chaque clé/valeur JSON ou tag XML s'éclaire au survol.
    *   **Interaction au clic :** Clic sur une clé = ajout automatique au `SELECT`. Clic sur une valeur = ajout à la clause `WHERE`.
    *   **Live Preview :** Une barre flottante affiche la requête SQL se construisant dynamiquement au fil des clics.
- **Inférence de Schéma :** Tableau décrivant la structure détectée (noms de colonnes, types de données).

## 5. Visualisation : Lineage & Stream Flow
- **Moteur de Graphe (Cytoscape.js) :**
    *   **Lineage :** Visualisation des flux entre Topics, Tables, Vues et Jobs. Codage couleur strict par type d'objet.
    *   **Stream Flow :** Traçage d'un message par ID. Le graphe affiche le cheminement chronologique entre les topics avec des nœuds numérotés.
- **Inspecteur (Side Panel) :** Panneau latéral s'ouvrant au clic sur un nœud pour afficher sa DDL, son schéma et ses métriques.

## 6. Audit Fonctionnel & Santé
- **Workflow Audit :** Visualisation horizontale des processus métier.
    *   **Latence & Débit :** Badges "horloge" montrant le temps de traitement entre étapes et taux de succès/perte.
- **Diagnostic Technique :** Tableau listant les anomalies (Poison messages, doublons) avec icônes de statut explicites (Check vert / Warning rouge).

## 7. Comparaison Side-by-Side
- **Layout Split Screen :** Deux colonnes strictement miroirs pour deux topics différents.
- **Requête Partagée :** Un seul éditeur SQL pilotant les deux vues simultanément.
- **Analyse de Diff :** Surlignage (Highlight) automatique des cellules divergentes entre les deux topics sur la base d'un identifiant pivot.

## 8. Metrics & Monitoring
- **Créateur de Métriques :** Formulaire pour convertir une requête SQL en métrique Prometheus.
- **Testeur :** Affichage d'un graphique "sparkline" pour valider la métrique avant son déploiement.

---
**Directives UX additionnelles :**
- **Empty States :** Illustrations sobres avec messages d'aide quand aucune donnée n'est présente.
- **Transitions :** Utiliser des transitions de type "fade-in" ou "slide" pour éviter les changements brusques d'interface.
- **Typographie :** Utiliser des polices "monospaced" (type JetBrains Mono ou Roboto Mono) pour toutes les zones de données et de code.
