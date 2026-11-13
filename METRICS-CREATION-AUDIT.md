# Création d'une métrique — audit (2026-11)

Périmètre : le parcours qui va de « New metric » à une série Prometheus — l'éditeur
(`pages/Metrics.tsx`, `pages/metricsEditor.ts`, `components/assistant/MetricDraftAssist.tsx`),
`POST /api/metrics/preview-template`, `POST /api/metrics` et `MetricService.save`. Les modèles à
deux requêtes ont leur propre rapport (`METRICS-TWO-QUERY-AUDIT.md`) et ne sont pas repris ici.

> **Statut.** **B1, B2, B3, B7 et U1 sont corrigés**, chacun avec le test qui l'aurait montré :
> le bloc de l'assistant est un groupe et non plus un `<form>` (`MetricDraftAssist.test.tsx`) ;
> `save` écrit dans le topic *avant* de publier en mémoire et une écriture refusée répond **503**
> avec la raison (`MetricServiceTest`, `MetricControllerTest`) ; le nom compte dans
> `metricShapeChanged`, donc un renommage ré-enregistre la série ; un mode inconnu est refusé en
> nommant les modes admis ; et Échap, le voile, la croix et « Cancel » demandent confirmation dès
> que l'éditeur contient un geste de l'utilisateur ou un brouillon restauré (`Metrics.test.tsx`).
> **B4, B5 et B6 aussi** : `save` refuse ce que l'éditeur bloque (nom au format Prometheus, type
> parmi quatre, seuils finis et distincts, SQL qui commence par `SELECT` après un CTE et les
> commentaires) — à l'enregistrement seulement, pour qu'une métrique déjà stockée continue de
> tourner ; l'éditeur lit au-delà d'un `WITH` de tête, accepte `` `metric_value` `` et cherche
> l'alias hors des littéraux ; un `CREATE TABLE` qui échoue est la réponse de l'aperçu, et précède
> l'erreur de la requête au rafraîchissement ; l'aperçu restaure les secrets masqués d'une métrique
> stockée ; et l'aperçu ne laisse plus de table derrière lui : celle qu'il a créée est supprimée
> (de Flink et du magasin rejoué au démarrage), et quand la table existait déjà il dit que son DDL,
> modifié, n'a pas été appliqué (puce « DDL not applied »). **U2 et U3 aussi** :
> `POST /api/metrics` rend la métrique enregistrée, que la page rafraîchit aussitôt, et le pied du
> modal dit « Not previewed » tant que la définition affichée n'a pas été exécutée ; une métrique
> ne publie pas plus de `explorer.metric-max-series` séries (500, 0 sans plafond) — au-delà elle
> ne publie rien et sa carte dit pourquoi, et l'aperçu compte les séries et refuse de même.
> **Les optimisations aussi** : les colonnes des tables sont demandées à la première ouverture de
> l'éditeur et non à chaque visite (O1) ; le brouillon est écrit après 400 ms de pause, sans
> l'historique des valeurs, et vidé au départ de la page (O2) ; l'aperçu attend les deux côtés d'un
> gabarit (`2 × timeoutMs + 15 s`) au lieu des 60 s globales. **Les points plus légers aussi** :
> l'assistant est sur l'onglet de la requête, à la largeur de l'éditeur ; un refus du serveur ouvre
> l'onglet qu'il concerne (`saveErrorTab`) ; et Entrée avec une erreur bloquante ouvre l'onglet qui
> échoue au lieu d'un toast. **Tout l'audit est traité.** Chaque constat dit comment il a été établi —
> **reproduit** (un test l'a montré, jeté ensuite), ou **lu** (déduit du code, fichier nommé).
> Douze constats, classés ; le premier est une régression introduite par l'assistant (PR #547).

| # | Constat | Gravité | Établi |
|---|---|---|---|
| B1 | « Draft » de l'assistant soumet aussi le formulaire de l'éditeur | **Critique** | reproduit |
| B2 | Un enregistrement non persisté répond « Metric saved » | **Élevée** | reproduit |
| B3 | Renommer une métrique ne renomme pas sa série Prometheus | Élevée | reproduit |
| B4 | Le serveur accepte n'importe quel nom, type, seuil ou SQL | Moyenne | reproduit |
| B5 | L'éditeur refuse du SQL valide et accepte un alias écrit dans une chaîne | Moyenne | reproduit |
| B6 | L'échec du `CREATE TABLE` est avalé ; l'aperçu enregistre une table | Moyenne | lu |
| B7 | Un mode d'exécution inconnu répond le nom d'une classe Java | Faible | reproduit |
| U1 | Échap, Cancel ou un clic dehors jettent le brouillon sans confirmation | Élevée | lu |
| U2 | On peut enregistrer sans aperçu, et la carte reste « pending » jusqu'au cycle suivant | Moyenne | lu |
| U3 | Aucune borne sur le nombre de séries qu'une requête crée | Moyenne | lu |
| O1 | `/api/metrics/metadata` lit le schéma de chaque table, une par une, à l'ouverture de la page | Faible | lu |
| O2 | Le brouillon est réécrit dans `localStorage` à chaque frappe | Faible | lu |

---

## Bugs

### B1 — « Draft » soumet aussi le formulaire de l'éditeur *(critique, reproduit)*

`MetricDraftAssist` rend un `<form onSubmit>` ; `Metrics.tsx` le place dans la colonne de gauche,
**à l'intérieur** du `<form>` de l'éditeur (`onSubmit={… if (!hasBlockingErrors) void handleSave()}`).
Deux formulaires imbriqués sont invalides en HTML, et React fait remonter l'événement : le
`preventDefault()` du formulaire intérieur n'arrête pas la propagation.

Reproduit : un `<form>` extérieur muni d'un espion, `MetricDraftAssist` dedans, un clic sur
**Draft** → l'espion extérieur est appelé une fois, et React journalise « `<form>` cannot be a
descendant of `<form>` ».

Conséquence dans la page : demander un brouillon **enregistre la métrique en cours** dès que le
formulaire est valide — donc à chaque fois qu'on édite une métrique existante — et ferme le modal
avant que le brouillon n'arrive. Sur une métrique neuve sans nom, c'est le toast « Name is
required ». Le test de `MetricDraftAssist` ne le voit pas : il rend le composant seul.

**Correction** : ne pas imbriquer — un `<div role="group">` avec un bouton `type="button"` et
Entrée gérée sur le champ, ou sortir le bloc du `<form>` de l'éditeur. Et un test qui rend le bloc
*dans* un formulaire.

### B2 — Un enregistrement non persisté répond « Metric saved » *(élevée, reproduit)*

`MetricService.persistToKafka` attrape toute exception, écrit un `log.warn` et rend la main ;
`save()` ne sait donc rien de l'échec et le contrôleur répond 200. Reproduit avec le producteur
fermé : `save` revient normalement et la métrique est en mémoire.

L'opérateur voit « Metric saved », la carte, puis des valeurs — et la métrique disparaît au
redémarrage suivant, sans qu'aucun écran ne l'ait dit. C'est le défaut que ce dépôt retire
partout ailleurs : un échec rendu comme un succès.

**Correction** : faire remonter l'échec (`save` répond une erreur que le modal affiche déjà via
`saveError`), ou garder la métrique mais la marquer « non persistée » sur sa carte.

### B3 — Renommer ne renomme pas la série *(élevée, reproduit)*

`metricShapeChanged` compare le type, le SQL, les labels, le modèle, ses paramètres, le DDL et le
mode — **pas le nom**. Or le nom est un tag (`metric_name`) fixé à l'enregistrement du compteur
Micrometer (`computeIfAbsent`). Reproduit : enregistrer `before_rename`, rafraîchir, renommer en
`after_rename`, rafraîchir → la série porte toujours `metric_name="before_rename"`.

La carte affiche le nouveau nom, Prometheus l'ancien, jusqu'au redémarrage ; une alerte écrite sur
le nouveau nom ne se déclenche jamais.

**Correction** : compter le nom dans la forme (purge des séries, l'historique est gardé), sans
effacer la mesure affichée puisque la définition n'a pas changé.

### B4 — Le serveur ne valide presque rien *(moyenne, reproduit)*

`validateMetric` exige un nom non vide et, en SQL brut, un SQL non vide. Reproduit : un
`POST` avec le nom `bad name!{}`, le type `FOO`, le SQL `DROP TABLE x`, un seuil `NaN` et un seuil
négatif est **accepté**.

Tout ce que l'éditeur vérifie (nom au format Prometheus, type parmi quatre, alias `metric_value`,
seuils distincts) n'existe que dans le navigateur. Or la création passe aussi par l'API, par les
suggestions, et par un brouillon restauré. Le SQL destructeur est arrêté plus loin par la liste
blanche à l'exécution ; la métrique, elle, reste enregistrée et en erreur pour toujours.

**Correction** : reprendre côté serveur les règles bloquantes de `metricsEditor.ts` (nom, type,
seuils finis et distincts, SQL qui commence par `SELECT`/`WITH`).

### B5 — L'éditeur refuse du SQL valide, et se laisse tromper par une chaîne *(moyenne, reproduit)*

`validateMetricSql` raisonne par expressions régulières sur le texte. Reproduit sur quatre formes :

| SQL | Verdict de l'éditeur | Verdict du moteur |
|---|---|---|
| `WITH r AS (…) SELECT COUNT(*) AS metric_value FROM r` | **bloqué** (« Only SELECT ») | accepté |
| ``SELECT COUNT(*) AS `metric_value` FROM t`` | **bloqué** (alias manquant) | accepté |
| `SELECT COUNT(*) AS n FROM t WHERE note = 'x AS metric_value'` | accepté | métrique « pending » à vie |

Les deux premières bloquent **Save** et **Preview**. L'assistant, lui, produit ces formes : son
prompt autorise un `WITH` de tête et sa porte accepte l'alias entre accents graves — un brouillon
« accepté par le planificateur » peut donc être refusé par le formulaire qu'il vient de remplir.

**Correction** : lire au-delà d'un CTE de tête, accepter l'alias entre accents graves, et chercher
l'alias hors des littéraux — ou, mieux, demander au serveur (`/api/query/validate` existe).

### B6 — Le `CREATE TABLE` échoue en silence, et l'aperçu a un effet de bord *(moyenne, lu)*

`executeCreateTableIfPresent` attrape toute exception et la journalise en `debug`. Une faute dans
le DDL ne remonte donc jamais : l'aperçu affiche l'erreur *suivante* — « Object not found » sur
une table dont le nom est correct — et l'opérateur corrige le mauvais onglet.

Deux effets voisins, dans la même méthode :

- l'aperçu **exécute** le DDL : un simple « Preview » enregistre une table dans le catalogue, qui
  reste après l'abandon du modal ;
- l'aperçu envoie le DDL tel que le navigateur l'a reçu, c'est-à-dire **masqué** (`'******'`).
  `save` restaure les secrets (`restoreMaskedProperties`), `previewMetric` non : sur une table pas
  encore enregistrée, l'aperçu la crée avec des identifiants faux.

**Correction** : rendre l'erreur du DDL dans `MetricPreviewResult.error`, et restaurer les secrets
dans l'aperçu quand la métrique a un `id`.

### B7 — Un mode inconnu répond le nom d'une classe *(faible, reproduit)*

`MetricExecutionMode.valueOf` et `MetricTemplateType.fromValue` lèvent l'exception brute de
l'énumération : le modal affiche « No enum constant
com.compagnonsdudev.kafkasqlexplorer.domain.MetricExecutionMode.TURBO ». Atteignable par l'API ou
par un brouillon ancien. **Correction** : une phrase qui nomme les valeurs admises.

---

## Interface et expérience

### U1 — Le brouillon se perd sur un geste *(élevée, lu)*

Trois gestes ferment le modal : **Échap** (écouteur sur `window`), **Cancel**, et un clic sur le
fond. Aucun ne demande confirmation, et l'effet qui suit `isModalOpen` appelle
`clearDraft(EDITOR_DRAFT)` : le SQL, le DDL et les paramètres tapés sont perdus. Le dépôt a
pourtant l'outil (`useUnsavedGuard`, `useConfirm`) et l'applique à la fermeture d'un onglet SQL ;
`Metrics.tsx` ne l'utilise pas.

À vérifier à la main : Échap sert aussi à fermer la liste de complétion de Monaco, et l'écouteur
est sur `window` — si Monaco ne retient pas la touche, fermer une suggestion ferme le modal.

**Correction** : confirmer quand le formulaire diffère de son état d'ouverture, ou garder le
brouillon à la fermeture et proposer « Reprendre le brouillon ».

### U2 — Enregistrer sans aperçu, puis attendre *(moyenne, lu)*

Le bouton s'appelle **Save & Activate** et n'exige aucun aperçu : une requête jamais exécutée
devient une métrique. `save` ne calcule rien ; la carte reste « pending » jusqu'au prochain cycle
planifié, sans dire quand. `POST /api/metrics` ne rend ni l'identifiant ni la métrique, donc la
page ne peut pas déclencher `/{id}/refresh` sur ce qu'elle vient de créer.

**Correction** : rendre la métrique enregistrée, la rafraîchir aussitôt, et signaler dans le modal
qu'aucun aperçu n'a été fait sur ce SQL (`sameStatement` existe déjà pour le savoir).

### U3 — Rien ne borne le nombre de séries *(moyenne, lu)*

Toute colonne autre que `metric_value` devient un label (`buildTags`). `SELECT id, amount AS
metric_value FROM orders` crée une série par commande, et l'éditeur ne prévient que de l'absence
d'agrégat. Aucune limite n'existe dans `MetricService` (recherche de `cardinal`, `maxSeries` :
rien). L'aperçu, lui, connaît le nombre de lignes.

**Correction** : afficher dans l'aperçu « cette requête créerait N séries », et refuser ou
plafonner au-delà d'un seuil configurable.

### Points plus légers

- **L'assistant occupe le haut d'une colonne de 288 px** (`w-72`) : sa carte de proposition, avec
  le SQL, y est à l'étroit. Sa place naturelle est l'onglet de la requête.
- **Le refus du serveur n'est pas rattaché à un champ** : `saveError` s'affiche en tête du modal,
  même quand il concerne le DDL d'un onglet fermé.
- **Un seul message à la fois** : `handleSave` affiche en toast la première erreur bloquante ;
  le bouton est déjà désactivé dans ce cas, donc ce chemin n'est atteint que par Entrée.

---

## Optimisations

- **O1 — `GET /api/metrics/metadata`** appelle `getTableSchema` pour chaque table, chacune sous le
  verrou de lecture du runtime Flink, à chaque ouverture de la page — pour alimenter une complétion
  qui ne sert que dans le modal. À charger à l'ouverture du modal, ou à mettre en cache.
- **O2 — Brouillon** : `writeDraft` s'exécute à chaque changement de `editingMetric`, donc à
  chaque frappe dans Monaco, et sérialise toute la métrique (historique compris pour une
  métrique existante). Un délai de quelques centaines de millisecondes suffit, comme les onglets
  de l'éditeur SQL le font déjà.
- **Aperçu** : `preview-template` n'a pas de délai propre ; il hérite du filet global de 60 s
  alors qu'un modèle peut balayer jusqu'à `scanTimeoutMs`. À aligner sur le budget du modèle.

---

## Ordre proposé

1. **B1** — une régression récente, qui enregistre à la place de l'utilisateur.
2. **B2** et **U1** — les deux façons de perdre du travail sans le savoir.
3. **B3**, **B5** — ce que l'opérateur voit diverge de ce qui tourne.
4. **B4**, **B6**, **B7** — la validation et les messages côté serveur.
5. **U2**, **U3**, puis les optimisations.
