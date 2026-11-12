# Prévisions de métriques — audit des bugs, de la performance et de l'UX (2026-11)

Périmètre : la page **Metrics** côté prévisions — `ForecastPanel`, `ForecastTimeline`,
`ForecastProgress`, `ForecastShadowComparison`, `ForecastPreparation`, `ForecastSetupWizard`, la
ligne « Forecast » des cartes — et ce qu'ils appellent : `ForecastController`,
`ForecastSetupController`, `ForecastSetupService`, `ForecastPilotService` (chemins de lecture).
Le moteur lui-même (pilote, dérive, TimesFM, outils MCP) a son rapport : `TIMESFM-FORECAST-AUDIT.md`.

> **Statut.** **B1, B2, P1, P2 et P3 sont corrigés**, chacun avec un test qui échoue sans lui :
> les deux `<Area>` de l'intervalle sont deux enfants séparés, et la bande s'affiche avec sa légende ;
> une série illisible devient une ligne en erreur (`error`, pastille « Unreadable ») au lieu d'une
> 503 sans série ; la liste ne porte plus les points d'historique — **4 KB par série au lieu de
> 44 KB** sur la même série d'essai — et `GET /api/forecasts/{seriesId}` rend l'enregistrement
> complet de la série affichée ; la préparation de `/progress` est mémoïsée par pas clos ;
> choisir une série ne lit plus qu'elle. Le reste est ouvert. Chaque constat dit comment il a été établi —
> **observé** (l'interface compilée, servie sur les données figées de `docs/screenshots/` avec trois
> séries inventées — en observation, en collecte, dérivée — et pilotée par Playwright à 1440×900),
> **reproduit** (un test jeté ensuite) ou **lu** (déduit du code, fichier nommé). Les chiffres
> marqués *calculé* sont une extrapolation de ce qui a été mesuré. Aucun cluster, aucun
> PostgreSQL ni TimesFM n'a tourné : rien ici ne dit comment le modèle se comporte.

| # | Constat | Gravité | Établi |
|---|---|---|---|
| B1 | L'intervalle à 80 % n'est jamais dessiné | **Haute** | observé |
| B2 | Une série illisible vide toute la liste | **Haute** | reproduit |
| B3 | Une série en collecte porte « Baseline fallback » | Moyenne | observé + lu |
| B4 | Les points imputés sont tracés comme « Observed » | Moyenne | lu |
| B5 | Le graphique ne montre pas le seuil configuré | Moyenne | lu |
| B6 | L'assistant laisse choisir une métrique déjà prévue, et refuse avec une mauvaise piste | Basse | lu |
| B7 | Les pastilles de sources passent au-dessus de leur libellé | Basse | observé |
| P1 | La liste renvoie le contexte complet de **toutes** les séries | **Haute** | mesuré + calculé |
| P2 | Un cycle d'interrogation relit trois fois la même série | Moyenne | observé + lu |
| P3 | Changer de série relance la liste entière | Moyenne | observé |
| P4 | La préparation est lue au chargement, repliée | Basse | observé |
| U1 | Le graphique et la décision sont en bas d'un panneau de 914 px | **Haute** | observé |
| U2 | Des contrôles natifs hors du design system | Moyenne | observé + lu |
| U3 | Quatre tuiles qui disent « Unmeasured » | Moyenne | observé |
| U4 | « Prepare a forecast » ne montre rien là où l'on a cliqué | Moyenne | observé + lu |
| U5 | Arrêter et approuver sont aux deux bouts du panneau | Basse | observé |
| U6 | Le texte ne dit jamais ce que l'approbation change | Basse | lu |

---

## Bugs

### B1 — L'intervalle à 80 % n'est jamais dessiné *(haute, observé)*

Le panneau écrit « The 80 % interval is nominal, not a guarantee », le tooltip sait formater
`q10 – q90`, la qualité réalisée mesure la couverture de cet intervalle — et **aucune bande n'est
tracée**, ni aucune entrée « 80 % interval » dans la légende. Observé sur une série TimesFM : deux
courbes (Observed, Median forecast), rien d'autre.

Cause lue dans `ForecastPanel.tsx` : les deux `<Area>` (le plancher transparent et la largeur de
bande) sont enfermées dans un fragment `<>…</>` sous `result.strategy === 'TIMESFM' && …`.
recharts 2.15 ne retrouve pas des éléments graphiques dans ce fragment. **Vérifié** : avec les deux
`<Area>` écrites chacune sous sa propre condition, la bande apparaît et la légende gagne « 80 %
interval ». Sans elle, l'opérateur juge une prévision sans voir son incertitude, qui est ce que
cette fonctionnalité promet de montrer.

**Correction** : deux conditions séparées (deux lignes), et un test qui compte les entrées de la
légende.

### B2 — Une série illisible vide toute la liste *(haute, reproduit)*

`ForecastController.status()` appelle `p.get(seriesId)` pour chaque série dans un seul `try`. Un
enregistrement qui ne se décode pas, une requête qui expire sur une seule : l'exception sort de la
boucle et la réponse est **503, `series: []`**. Reproduit avec deux séries dont la seconde lève :
`status=503 state=UNAVAILABLE series=0`.

Effets : le panneau affiche « Forecast persistence is unavailable. Existing results may be stale. »
pour dix-neuf séries saines, et `onStatus(null)` retire de **toutes les cartes** la ligne
« Forecast » (la page l'a prévu pour « nobody can read the status »). Le sélecteur de série
disparaît avec elles : impossible de retirer la série fautive depuis l'interface.

**Correction** : un `try` par série ; une ligne en erreur (`result: null` + raison) à la place de
la 503, qui reste pour une base injoignable.

### B3 — Une série en collecte porte « Baseline fallback » *(moyenne, observé + lu)*

Une série qui n'a pas encore assez d'historique est écrite avec `strategy = "UNAVAILABLE"`
(`ForecastPilotService`, ligne 399). Le panneau traduit par
`STRATEGY_LABEL[strategy] ?? 'Baseline fallback'` : une pastille **jaune** « Baseline fallback » à
côté de « Collecting history », alors qu'aucun repli n'existe. Autour : le bouton désactivé
« Approve after quality checks » et la phrase « A baseline forecast has no interval » sont montrés
sans prévision.

**Correction** : nommer `UNAVAILABLE` (« No forecast yet », neutre) et ne montrer le bloc
prévision, l'avertissement et le bouton d'approbation que s'il y en a une.

### B4 — Les points imputés sont tracés comme « Observed » *(moyenne, lu)*

`chart` prend `actual: p.value` pour tous les points du contexte. Un point imputé (`imputed: true`)
a une valeur, donc il est dessiné dans la courbe verte « Observed ». `ForecastProgress` les sait
distincts (« Imputed: n », et la tendance les exclut) ; le graphique non. Une série pleine de
trous comblés paraît mesurée.

**Correction** : un marqueur ou un trait en pointillé sur les segments imputés, et la légende le dit.

### B5 — Le graphique ne montre pas le seuil configuré *(moyenne, lu)*

La tuile « Predicted threshold breach » écrit « ABOVE 800 ms » ; le graphique n'importe pas
`ReferenceLine` (`ForecastPanel.tsx`, import recharts) et ne trace donc rien. Dire qu'une borne
prudente franchit un seuil que personne ne voit sur la courbe oblige à le comparer de tête.

**Correction** : une `ReferenceLine` au seuil quand `progress.breach` en porte un.

### B6 — L'assistant laisse choisir une métrique déjà prévue *(basse, lu)*

Le sélecteur de candidats liste toute métrique éligible, y compris celles qui ont déjà une série ;
le refus vient après la case à cocher et « Start forecasting » :
« This series is already approved; edit its deployment configuration explicitly »
(`ForecastSetupService.approval`). Pour une série approuvée **depuis l'assistant** (cas ordinaire,
`withdrawable`), la piste est fausse : il n'y a rien à éditer dans un fichier, il faut d'abord
« Stop forecasting this series ». Les cartes ont déjà l'information (`forecastStages`).

**Correction** : marquer « déjà prévue » dans la liste (et la désactiver), et réécrire le refus
selon l'origine de la série.

### B7 — Les pastilles de sources passent au-dessus de leur libellé *(basse, observé)*

À l'étape 2, `NameListField` rend la pastille `demo.orders.1.received ×` **avant** le libellé « All
source topics », qui se retrouve entre elle et le champ d'ajout : on lit une pastille sans titre,
puis un titre au-dessus d'un champ vide.

---

## Performance

### P1 — La liste renvoie le contexte complet de toutes les séries *(haute, mesuré + calculé)*

`GET /api/forecasts` embarque, pour **chaque** série, l'enregistrement entier : 512 points de
contexte, la prévision, la fenêtre de qualité. Mesuré sur la série d'essai : **44 KB** de JSON.
`maxSeries` vaut 20 par défaut : jusqu'à **~0,9 MB** (*calculé*) toutes les 30 s et par onglet
ouvert, pour une page qui n'affiche qu'une série et dont les cartes n'ont besoin que de l'étape
(`forecastStage`). Côté serveur, c'est un `store.latest` + un décodage JSON par série, en série.

**Correction** : la liste rend un résumé (identité, étape, `activation`, pas de points) ; le détail
d'une série (`GET /api/forecasts/{id}`) rend l'enregistrement, et le panneau ne demande que celle
qu'il montre.

### P2 — Un cycle d'interrogation relit trois fois la même série *(moyenne, observé + lu)*

Observé : **trois requêtes par cycle** (liste, `/progress`, `/shadow`). *Corrigé en partie* : la
préparation est mémoïsée tant que le pas n'a pas changé, donc l'historique est relu une fois par pas
et par série, quel que soit le nombre de pages ouvertes ; les trois requêtes demeurent. Lu : la liste fait `get()` ;
`/progress` refait `prep.prepare(...)` — relecture de l'historique PostgreSQL et reconstruction du
contexte de 512 points — puis `breach(id)`, qui refait `get()` ; `/shadow` ouvre sa propre lecture.
Les nombres de points observés / manquants / imputés que `/progress` recalcule sont déjà dans
`result.context` de la liste.

**Correction** : dériver ces nombres de l'enregistrement déjà lu, et ne garder pour `/progress`
que ce qui n'y est pas (l'échéance suivante, l'évaluation du seuil).

### P3 — Changer de série relance la liste entière *(moyenne, observé)*

`selected` est une dépendance de l'effet d'interrogation : le sélecteur interrompt la boucle et
refait les **trois** requêtes, la liste complète (P1) comprise, pour obtenir le progrès et
l'ombre d'une série dont la liste vient d'arriver. Observé : trois requêtes à chaque changement.

**Correction** : le changement ne demande que `/progress` et `/shadow` de la série choisie ; la
boucle de la liste ne dépend plus de la sélection.

### P4 — La préparation est lue au chargement, repliée *(basse, observé)*

`GET /api/forecasts/preparation` part au montage même quand des séries existent et que le
`<details>` est fermé. À lire à l'ouverture.

---

## Interface et expérience

### U1 — Le graphique et la décision sont en bas d'un panneau de 914 px *(haute, observé)*

Pour **une** série en observation, le panneau mesure **914 px** à 1440×900 et empile : un
`<details>` de préparation, le sélecteur, la frise (4 étapes), une phrase d'horodatage, quatre
tuiles, un `<details>` d'historique, « Stop forecasting », la phrase de raison, le **graphique (à
500 px)**, une mise en garde, un `<details>` de qualité, puis le bouton d'approbation. Ce qu'un
opérateur vient chercher — *à quoi ressemble la prévision, et que dois-je décider* — est en bas.

**Proposition** : en-tête (série + une pastille d'état) → graphique → une ligne de verdict
(« Observing · quality 12/30 · approvable in ~18 min ») → la décision → tout le reste dans des
dépliants.

### U2 — Des contrôles natifs hors du design system *(moyenne, observé + lu)*

Le sélecteur « Series » est un `<select>` nu (fond gris natif sur le thème sombre, visible sur les
captures), les baselines sont un `<table>` sans classes, la progression un `<progress>` natif. La
règle du dépôt est de construire formulaires et tableaux avec les contrôles partagés.

### U3 — Quatre tuiles qui disent « Unmeasured » *(moyenne, observé)*

Une série en collecte affiche « Trend — Unmeasured », « Horizon — No forecast yet », « Predicted
threshold breach — No threshold configured » (suivi de « Your threshold against the cautious edge… »
pour un seuil qui n'existe pas) et « Realised quality — Unmeasured » : quatre cases vides. La
tendance compare en plus la fin de l'horizon à la dernière valeur sans nommer l'horizon
(« Falling · −51.93 ms » — sur 30 minutes).

**Correction** : ne montrer une tuile que lorsqu'elle a une valeur ; nommer l'horizon.

### U4 — « Prepare a forecast » ne montre rien là où l'on a cliqué *(moyenne, observé + lu)*

L'assistant s'ouvre **sous la grille de cartes**, puis `scrollIntoView({ block: 'nearest' })` est
appelé sur son **titre** seul. Observé 800 ms après le clic : la page n'a pas bougé, le titre
affleure au bord bas (y = 871 sur 900) et le formulaire est entièrement sous le pli. De plus, lancé
depuis une carte, l'assistant reste à l'étape 1 avec la métrique déjà choisie : il faut encore
« Continue to sources ».

**Correction** : l'ouvrir à l'étape 2 quand la métrique est donnée, et faire défiler jusqu'au
formulaire (ou l'ouvrir en panneau).

### U5 — Arrêter et approuver sont aux deux bouts du panneau *(basse, observé)*

« Stop forecasting this series » (destructif, discret) est entre les tuiles et le graphique ;
l'approbation est tout en bas. Les deux décisions d'une série devraient se trouver ensemble.

### U6 — Le texte ne dit jamais ce que l'approbation change *(basse, lu)*

Le dialogue dit « Records your approval of its realised quality. It sends no alert and can be
returned to observing at any time. » ; `timesfm.md` dit que ACTIVE est ce que les outils MCP
servent. La page ne le dit nulle part : un opérateur approuve sans savoir à quoi.

---

## Ce qui tient

L'interrogation s'arrête derrière un onglet caché et reprend au retour ; une série sans ombre
configurée n'est plus interrogée (404 retenu) ; chaque refus d'approbation affiche sa raison à côté
du bouton ; la frise dit pour chaque étape ce qu'elle attend et à peu près pour quand ; les
valeurs absentes sont dites « unmeasured » et non zéro ; les cartes portent l'étape sans qu'il
faille ouvrir le panneau.

## Ordre proposé

1. **B1, B2** — la bande et la liste qui ne se vide plus : deux défauts nets, petits.
2. **P1, P2, P3** — un seul chantier : liste résumée, détail par série, interrogation découplée.
3. **U1, U3, B3** — le panneau réordonné et sobre pour une série en collecte.
4. **B4, B5** — un graphique honnête (imputés, seuil).
5. **U4, B6, B7, U2, P4, U5, U6**.
