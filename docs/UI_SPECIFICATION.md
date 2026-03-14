# Spécifications Fonctionnelles pour Refonte UI - Kafka SQL Explorer

Ce document détaille l'ensemble des modules du **Kafka SQL Explorer**. L'objectif est de transformer une interface technique basique en une plateforme moderne, ergonomique et hautement productive pour les ingénieurs de données.

## 1. Structure Globale & Navigation (`layout.html`)
*   **Concept :** Interface "Dark Mode" professionnelle avec des accents colorés (Teal/Cyan) pour la lisibilité technique.
*   **Navigation :** Une barre latérale ou supérieure persistante permettant de basculer entre :
    *   **Dashboard** (Vue d'ensemble)
    *   **SQL Editor** (Cœur du produit)
    *   **Compare** (Outil de diff)
    *   **Audit** (Santé du cluster)
    *   **Lineage** (Graphique de dépendances)
    *   **Stream Flow** (Traçage de messages)
*   **Indicateurs d'état :** Un témoin visuel proéminent (LED/Badge) indiquant si la connexion au cluster Kafka est active.

## 2. Tableau de Bord (`dashboard.html`)
*   **Statistiques (KPI Cards) :** Quatre compteurs majeurs : Topics totaux, Nombre de messages, Tables Flink, Jobs actifs.
*   **Explorateur de Topics :**
    *   Tableau listant les topics avec filtres intelligents (recherche par préfixe, recherche textuelle).
    *   Badges d'état : "Empty" pour les topics sans données, "DLT" pour les Dead Letter Topics (en orange/alerte).
    *   Actions : Bouton d'exploration rapide.
*   **Surveillance des Jobs :** Liste des requêtes SQL de streaming tournant en tâche de fond avec possibilité de les arrêter (Kill process).

## 3. Éditeur SQL & Assistant (`query.html`)
*   **Zone d'Édition :** Éditeur de code (type CodeMirror) avec coloration syntaxique SQL, numérotation des lignes et auto-complétion.
*   **Contrôles de lecture :** Toggle pour choisir entre lire depuis le début du topic (*Earliest*) ou uniquement les nouveaux messages (*Latest*).
*   **Assistant de Fenêtrage (Window Assistant) :** Interface par étapes pour générer des requêtes de fenêtres de temps (Tumble/Hop) sans avoir à connaître la syntaxe complexe de Flink SQL.
*   **Schema Browser (Sidebar) :** Panneau latéral affichant l'arborescence des tables et topics. Un clic sur une colonne doit l'insérer dans l'éditeur.
*   **Résultats :** Grille de données dynamique avec export CSV/JSON et statistiques de performance (temps d'exécution, débit).

## 4. Comparaison Side-by-Side (`compare.html`)
*   **Double Vue :** Deux colonnes indépendantes permettant de sélectionner deux topics différents.
*   **Requête Partagée :** Un éditeur SQL unique qui applique la même logique aux deux colonnes (ex: `SELECT * FROM {topic}`).
*   **Synchronisation :** Option pour lier les curseurs temporels des deux topics.
*   **Moteur de Diff :** Mise en évidence (Highlight) des différences de valeurs entre deux messages ayant le même identifiant technique.

## 5. Audit Fonctionnel & Santé (`audit.html`)
*   **Rapport de Santé :** Score global de fiabilité du cluster.
*   **Flow Audit :** Visualisation des étapes d'un processus métier (ex: Commande -> Paiement -> Expédition). Affiche la latence entre les étapes et les taux de perte de données.
*   **Détection d'anomalies :** Identification automatique des "Poison Messages" (JSON/XML invalides) et des doublons.

## 6. Lignage de Données (`lineage.html`)
*   **Graphe de Dépendances :** Utilisation de Cytoscape.js pour dessiner les liens entre les Topics Kafka, les Tables Flink, les Vues SQL et les requêtes actives.
*   **Inspecteur :** Panneau de détails s'ouvrant au clic sur un nœud du graphe pour voir sa définition technique (DDL) et son schéma.

## 7. Traçabilité - Stream Flow (`stream-flow.html`)
*   **Recherche de Message :** Formulaire permettant de chercher une valeur précise (ou Regex) à l'intérieur des messages.
*   **Support des chemins :** Utilisation de JSONPath ou XPath pour scanner des structures de données complexes.
*   **Propagation :** Graphique montrant le trajet chronologique d'un message spécifique à travers tous les topics du système.

## 8. Détail de Topic & Assistant Intelligent (`topic-detail.html`)
*   **Fiche Technique :** Partitions, taille estimée, format détecté automatiquement.
*   **Inférence de Schéma :** Tableau généré automatiquement décrivant la structure des données (colonnes, types).
*   **Query Assistant (L'innovation majeure) :**
    *   Aperçu des messages réels.
    *   **Interaction au clic :** L'utilisateur clique sur une clé dans le JSON (ex: `customer_id`) et l'interface ajoute automatiquement le champ dans la requête SQL ou crée un filtre `WHERE`.

## 9. Métriques & Monitoring (`metrics.html`)
*   **Indicateurs Personnalisés :** Création de compteurs ou jauges basés sur des requêtes SQL.
*   **Export :** Configuration pour envoyer ces métriques vers un dashboard Prometheus/Grafana.

## 10. Configuration (`config.html`)
*   **Multi-Cluster :** Gestion des connexions à différents environnements (Local, Dev, Prod) avec indicateurs de statut de connexion.

---
**Directives de Design pour Stitch / Google Designer :**
- **Densité d'information :** Interface dense mais claire, typique des outils d'ingénierie (type VS Code ou Datadog).
- **Interactivité :** Priorité aux actions contextuelles (clic sur une donnée = action SQL).
- **Code-Centric :** Les zones de texte SQL et les prévisualisations de données doivent être au centre de l'expérience utilisateur.
