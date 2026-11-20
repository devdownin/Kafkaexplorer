# Métriques et prévisions — second audit : bugs et optimisations (2026-11)

Périmètre lu : `MetricSeriesPreparer`, `ForecastQualityWindow`, `ForecastBaselines`,
`ForecastBacktestEvaluator`, `MetricObservationJournal`, `ForecastSetupService` (candidats,
approbation, `historyPreview`, `detectCycle`), les lectures de `ForecastPilotService`/`ForecastPilotStore`
derrière `GET /api/forecasts`, `TimesFmClient` et le contrat Python (en-têtes et bornes), l'assistant et
le panneau côté SPA, les overlays offline, les workflows de publication.
Premier audit du moteur : `TIMESFM-FORECAST-AUDIT.md` ; de l'UX : `METRICS-FORECAST-UX-AUDIT.md`. Rien
n'y est re-signalé.

> **Statut.** **B1–B6, U1, O1, P2 et S1 sont corrigés**, et les deux premiers points « à vérifier » aussi
> (le tag vide des workflows est gardé ; le chemin de `compose/llama-cpp.yml` est corrigé, avec un contrôle
> qui échoue sur l'ancien). Chacun a un test ; sans le correctif, 192 marches aléatoires sur 200
> recevaient un cycle (B1) et l'aperçu lisait une autre série (B2). **Restent ouverts P1 et P3** : P1
> demande une requête SQL propre à PostgreSQL ou un résumé stocké à part, à mesurer sur une vraie base ;
> P3 était à mesurer avant d'y toucher, et ne l'a pas été. **Les défauts B1, B2, B4, B5, B6 et U1
> viennent de l'assistant et de l'aperçu d'historique ajoutés par la PR #570** — du code récent, écrit
> sans avoir été éprouvé sur des séries qui dérivent. Chaque constat dit comment il a été établi —
> **reproduit** (un test jeté ensuite, sortie citée), **lu** (déduit du code, fichier:ligne) ou
> **calculé** (extrapolation d'une mesure). Aucun cluster, PostgreSQL ni TimesFM n'a tourné : rien ici
> ne dit comment le modèle se comporte.

| # | Constat | Gravité | Établi |
|---|---|---|---|
| B1 | `detectCycle` annonce un cycle sur toute série qui dérive, et l'assistant l'applique | **Haute** | reproduit, corrigé |
| B2 | `historyPreview` ne normalise pas la métrique : il peut lire une autre série que l'approbation | Moyenne | reproduit, corrigé |
| B3 | Les places d'enrôlement de l'historique ne sont jamais rendues | Moyenne | reproduit, corrigé |
| B4 | Le seuil suggéré ignore la direction : 1,5× la dernière valeur est déjà franchi en « Below » | Basse | reproduit, corrigé |
| B5 | L'aperçu garde les données de l'ancien intervalle sous la légende du nouveau | Basse | lu, corrigé |
| B6 | Un trou ou un point imputé fausse la détection de cycle | Moyenne | reproduit + lu, corrigé |
| P1 | `GET /api/forecasts` lit et décode le contexte entier de chaque série pour le jeter | Moyenne | lu + calculé |
| P2 | `historyPreview` n'est ni mémoïsé ni borné en fréquence | Basse | lu, corrigé |
| P3 | La préparation recalcule un SHA-256 par observation, pour une identité déjà filtrée par la requête | Basse | lu |
| O1 | Un seul reset de compteur invalide tout le contexte de 512 points | Moyenne | lu + calculé, corrigé |
| S1 | `kex-llm` s'exécute en root | Basse | lu, corrigé |
| U1 | Le sélecteur « History shown » offre plus de points que le contexte n'en a | Basse | lu, corrigé |

---

## Bugs

### B1 — `detectCycle` annonce un cycle sur toute série qui dérive *(haute, reproduit — corrigé)*

`ForecastSetupService.detectCycle` (l. 109) calcule l'autocorrélation brute, centrée sur la moyenne
globale, à l'écart d'une heure puis d'un jour, et retient le cycle dès que `r ≥ 0,5`
(`CYCLE_CORRELATION`, l. 126 pour le calcul). Une série **non stationnaire** — une file qui se remplit, un
volume qui monte, une marche aléatoire — est corrélée à elle-même à *tous* les écarts, donc à celui-là.

Reproduit sur 400 marches aléatoires (σ = 1, 300 points, intervalle 5 min) : **94,5 % reçoivent un
cycle « HOURLY »**. Sur du bruit blanc, au contraire, 0 % (0,3 % au pire, à 1 min, n = 100) : le seuil est
sain, c'est le centrage qui ne l'est pas.

Conséquence : `ForecastSetupWizard.tsx:164` applique le cycle proposé tant que l'opérateur n'a pas touché
au sélecteur (`cycleTouched`). Presque toute jauge réelle qui dérive reçoit donc `HOURLY` ou `DAILY` par
défaut, sans que rien ne l'indique hors du pli « Advanced » et d'une tuile. Ce que cela change :
`seasonLength` de la série (`ForecastSetupService.seasonLength`), donc la base `SEASONAL_NAIVE` contre
laquelle l'approbation compare ; et, pour un cycle quotidien à 5 min, `minimumContextPoints =
max(128, 288)` — le premier forecast attend **288 points au lieu de 128** (24 h au lieu de ~10,7 h,
*calculé*).

**Correction** : exiger que le pic soit un vrai pic — `r(lag) ≥ 0,5` **et** `r(lag) − r(lag/2) ≥ 0,5`
(un cycle a `r(lag/2)` négatif, une dérive l'a élevé). Simulé sur 300 tirages chacun : marche aléatoire
0 %, bruit 0 %, marche aléatoire à 15 min 1 %, et **100 %** de détection d'une sinusoïde quotidienne
bruitée, d'une tendance + cycle horaire et d'une dent de scie horaire. (Différencier la série n'est pas
une alternative : il élimine aussi la sinusoïde quotidienne, simulé à 0 %.)
**Test** : une marche aléatoire de 300 points à 5 min → `NONE` ; une sinusoïde de 288 points par jour
sur 512 → `DAILY`.

### B2 — `historyPreview` ne normalise pas la métrique *(moyenne, reproduit — corrigé)*

`approval()` normalise d'abord (`ForecastSetupService.java:294`,
`MetricService.normalizeObservationDefinition`) puis calcule `collectedVersion(m, …)` (l. 311) et
`seriesId` ; `historyPreview` calcule les mêmes (l. 92) à partir de la métrique **telle que stockée**. Or
`definitionVersion` hache `type`, `template` et `execution` (`MetricObservation.java:89`), que la
normalisation remplit (`null` → `GAUGE`, mode d'exécution par défaut).

Reproduit : pour une métrique `type = null`, `executionMode = null`, la version de la forme brute
≠ celle de la forme normalisée (`equals` → `false`). L'aperçu lit alors **une autre série** que celle que
l'approbation créerait : « no history » pour une métrique qui en a une (ou l'inverse), et une suggestion
de cycle ou de seuil qui ne vient pas de la série réellement approuvée. `MetricService` normalise à l'enregistrement et à l'édition (l. 539, 638, 2313), donc le cas est celui
d'une définition qui n'a pas transité par ces chemins (anciennes ou importées) — non cherché dans les données.
**Correction** : `m = normalizeObservationDefinition(m)` en tête de `historyPreview`, comme en l. 294.
**Test** : `historyPreview` d'une métrique non canonique renvoie les points de la série approuvée.

### B3 — Les places d'enrôlement ne sont jamais rendues *(moyenne, reproduit — corrigé)*

`MetricObservationJournal.selects` (l. 61–66) inscrit un id dans `autoEnrolled` tant que
`autoEnrolled + metricIds < MAX_METRICS (100)`. Rien ne le retire : ni la suppression d'une métrique, ni
un refus d'éligibilité. Reproduit : après 100 ids distincts éligibles, **le 101ᵉ n'est pas sélectionné**
(`false`). Ensuite, plus aucune observation d'une métrique nouvelle, sans autre trace qu'un seul
`log.warn` (`boundReported` n'est jamais remis à faux).

Scénario : un opérateur qui crée et supprime des métriques sous des ids nouveaux, sur un processus qui
tourne longtemps ; après la centième, les nouvelles métriques ne s'enregistrent plus. Et l'interface ne le sait pas : `candidate().enrolled` (l. 256) est calculé de la
configuration (`isEnrollEligible() && blockers.isEmpty()`), jamais de l'état du journal — la carte dit
« History recorded » pour une métrique que le journal refuse, et la série reste « Collecting » pour
toujours.
**Correction** : un `forget(metricId)` appelé par `MetricService.delete`, et `enrolled` lu du journal ;
à défaut, au moins un compteur `…_enrollment_refused_total` et un `warn` par refus nouveau.

### B4 — Le seuil suggéré ignore la direction *(basse, reproduit)*

`ForecastSetupWizard.tsx:241` : `round(lastValue * 1.5)` quelle que soit `direction`. Reproduit : avec
« Below » choisi, le bouton « Use 150 (1.5× latest) » est encore proposé — un seuil que la dernière valeur
(100) **a déjà franchi**, c'est-à-dire une prévision « franchie » dès le premier cycle.
**Correction** : 1,5× pour « Above », 0,5× pour « Below », ou masquer la suggestion en « Below ».

### B5 — L'aperçu garde l'ancien intervalle sous la nouvelle légende *(basse, lu)*

`ForecastSetupWizard.tsx:288` écrit « Recorded history at {cadenceLabel} » avec le libellé du **nouvel**
intervalle pendant que `preview` est encore celui de l'ancien (`setPreview(null)` n'a lieu qu'au
changement de métrique, l. 87). Pendant la lecture, un graphique à 1 min est légendé « 15 minutes ».
**Correction** : `setPreview(null)` au changement d'intervalle aussi, ou mémoriser le pas de l'aperçu.

### B6 — Un trou ou un point imputé fausse la détection *(moyenne, reproduit + lu)*

`historyPreview` (l. 96) retire les points `null` avant `detectCycle` : un trou intérieur **raccourcit la
chronologie**, et les écarts d'indice ne valent plus des durées. Reproduit : une sinusoïde quotidienne à
5 min dont 40 points intérieurs manquent passe de `r = 0,93` à **`0,64`** — encore détectée ici, mais un
trou plus long peut faire passer `r` sous le seuil 0,5. Par ailleurs les
points *imputés* (report de la dernière valeur, `MetricSeriesPreparer`) sont comptés comme des mesures :
des plateaux, donc de l'autocorrélation en trop (*lu*).
**Correction** : calculer l'autocorrélation sur la grille régulière en ignorant les paires qui contiennent
un `null` ou un point `imputed`, plutôt que sur la liste compactée.

---

## Performance

### P1 — `GET /api/forecasts` décode le contexte entier de chaque série pour le jeter *(moyenne, lu + calculé)*

`ForecastController.view` appelle `ForecastPilotService.get` — une connexion et un
`SELECT payload … ORDER BY history_end DESC LIMIT 1` par série (`ForecastPilotStore.latest`, l. 178) — qui
décode le JSON complet, contexte de 512 points compris, puis `withoutHistory` l'écarte. Les index existent
(`kex_forecast_result_series_v2`) : c'est le travail, pas la recherche, qui est inutile. Sur la série
d'essai du premier audit (44 KB de JSON, *mesuré* alors), 20 séries font ~0,9 MB décodés et jetés *par
sondage de 30 s et par onglet ouvert* (*calculé*).
**Optimisation** : une requête pour toutes les séries (`DISTINCT ON (series_id)`) en retirant les points
côté SQL (`payload::jsonb #- '{context,points}'`), ou stocker un résumé à part.

### P2 — `historyPreview` n'est ni mémoïsé ni borné *(basse, lu)*

Chaque ouverture de l'assistant et chaque changement d'intervalle lisent jusqu'à
`MAX_READ_OBSERVATIONS = 4097` lignes et reconstruisent le contexte. `progress()` mémoïse par pas clos
(`preparedByStep`) ; l'aperçu non. Le coût est borné par appel, mais un client qui boucle n'est limité par
rien (comme tout `/api`, cf. `SECURITY.md`). **Optimisation** : la même mémoïsation par `(métrique,
intervalle, pas clos)`.

### P3 — Un SHA-256 par observation dans la préparation *(basse, lu, non mesuré)*

`MetricSeriesPreparer.prepare` recalcule `MetricObservation.seriesId(...)` (JSON + SHA-256) pour chaque
ligne, jusqu'à 4096, afin de comparer à `seriesId` — alors que la requête filtre déjà
`WHERE series_id = ?`. Le contrôle vaut pour une source non fiable ; pour la voie `JdbcMetricObservationStore`
il est redondant. À mesurer avant d'y toucher.

---

## Optimisations

### O1 — Un reset de compteur invalide tout le contexte *(moyenne, lu + calculé)*

`MetricSeriesPreparer.java:73` renvoie `COUNTER_RESET` dès qu'une valeur décroît **n'importe où** dans les
512 points. Un redémarrage de la source bloque donc la prévision pour toute la fenêtre : 8,5 h à 1 min,
~5,3 jours à 15 min (*calculé*), alors que le segment *après* le reset suffit dès 128 points
(`MIN_CONTEXT_POINTS`). **Optimisation** : couper le contexte au dernier reset et le traiter comme un
contexte partiel (le chemin existe déjà pour un contexte qui se remplit), au lieu de refuser.

---

## Sécurité

### S1 — `kex-llm` s'exécute en root *(basse, lu)*

`services/llama-cpp/Dockerfile` n'a pas de `USER` : le processus hérite de l'utilisateur de l'image de
base. `kex-forecast` tourne en 10001. Le serveur n'écoute qu'en loopback dans le compose et n'a besoin que
de lire `/models`. **Correction** : `USER 10001` après avoir rendu `/models` lisible — à vérifier que
`llama-server` n'écrit rien d'autre.

## UX

### U1 — « Last 512 points » promet plus que le contexte *(basse, lu)*

`ForecastPanel.tsx:302` propose 60, 180 et 512 points quel que soit le contexte ; pour une série
« Collecting » de 130 points, « Last 512 points · ~9 h » en montre 130. **Correction** : n'offrir que les
tailles ≤ `context.points.length`.

---

## Ce qui tient

- **Tests** : `./verify-offline.sh` sur `.*(Metric|Forecast).*Test` : 334 trouvés, **331 réussis, 0 échec,
  2 sautés, 1 abandonné** (l'abandonné n'a pas été examiné) ; Vitest 1972/1972 ; ESLint propre.
- **Mathématiques** : `ForecastBaselines` (indexation `SEASONAL_NAIVE`, régression linéaire, moyenne
  mobile sur 12), perte pinball, échelle MASE, mise en commun pondérée de `ForecastQualityWindow` (borne
  de couverture, erreur standard entre cohortes). Les quatre bases ont toujours les mêmes clés, donc
  `poolBaselines` ne peut pas lever de `NullPointerException`.
- **Préparation** : imputation causale (valeurs passées seulement), un trou de plus de 2 pas refuse,
  un compteur n'est jamais imputé, le contexte partiel est tronqué proprement.
- **Détection de cycle sur bruit blanc** : 0 à 0,3 % de faux positifs, 16 combinaisons intervalle ×
  longueur de 400 tirages.
- **Rétention** : purge de 10 000 lignes par minute contre ~600 lignes par minute d'entrée dans un cas
  réaliste (100 métriques, 3 séries, 2 rafraîchissements par minute, *calculé*) ; index présents sur
  résultats, ombres et observations.
- **Journal** : jamais de SQL, d'étiquette ou de chaîne de connexion dans ses logs ; file bornée et pertes
  comptées (`dropped`).
- **Client TimesFM** : requêtes (128 Ko) et réponses (256 Ko) bornées, délai de bout en bout ; le contrat Python (128–512 points, horizon ≤ 60, lot ≤ 4) est celui du Java.
- **Aperçu** : n'est servi que pour une métrique éligible (non étiquetée), donc sans fuite de libellés.

## À vérifier (non prouvé)

- **Publication sur un tag `v*`** : `forecast-image.yml:74` et `llm-image.yml:72` écrivent
  `type=raw,value=${{ inputs.tag }},enable=false` ; sur un push de tag `inputs.tag` est vide.
  Si `docker/metadata-action` refuse une valeur vide même désactivée, la publication d'un tag échoue.
  Seuls des lancements manuels ont tourné. Garde simple : `value=${{ inputs.tag || github.ref_name }}`.
- **Chemins relatifs de `compose/llama-cpp.yml`** : avec Compose v5.6.1, `-f docker-compose.yml -f
  compose/llama-cpp.yml` résout `../scripts/llama-cpp/serve.sh` en `/home/user/scripts/…`, hors du
  dépôt (Compose résout contre le répertoire du premier fichier). Constaté dans `config`, pas dans un
  démarrage.
- Le test **abandonné** de la ligne de base (quel fichier, pourquoi).
- Le comportement réel de TimesFM et de Timestral (hors de portée ici).

## Non audité

`ForecastPilotService` (ordonnancement, bail, activation), `ForecastThresholdEvaluator`,
`ForecastOrchestrator`, les outils MCP de prévision, l'intérieur de `MetricService` hors du delta de
compteur et de la purge des instruments, le service Python au-delà du contrat, et tout le comportement
sous charge ou avec PostgreSQL arrêté.

## Ordre proposé

1. **B1** — c'est le défaut par défaut du nouvel assistant ; petit et testé par simulation.
2. **B3, B2** — deux défauts d'identité/enrôlement qui coupent silencieusement l'historique.
3. **B6, B4, B5, U1** — le reste de l'assistant.
4. **O1, P1** — les deux optimisations qui changent ce qu'un opérateur voit.
5. **P2, P3, S1**, puis les points « à vérifier » (le premier avant la prochaine release).
