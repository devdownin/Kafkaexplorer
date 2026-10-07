<!--
SPDX-License-Identifier: AGPL-3.0-or-later
Copyright (C) 2026 Kafka Explorer Contributors
-->
# Prévisions TimesFM et exposition MCP — audit (2026-10)

Revue de la chaîne complète « métrique → historique → TimesFM → prévision persistée → outils
MCP », au commit `ee6a70b` :

- `src/main/java/com/compagnonsdudev/kafkasqlexplorer/forecast/**` (pilote, store PostgreSQL,
  client HTTP, préparation de série, évaluation, baselines, seuils, assistant de configuration) ;
- `src/main/java/com/compagnonsdudev/kafkasqlexplorer/mcp/tools/ForecastMcpTools.java` et son
  enregistrement dans `McpServerConfiguration` ;
- `services/timesfm/` (service FastAPI, worker, contrat) ;
- `timesfm.md`, `docs/notes/timesfm-pilot.md`, `docs/notes/timesfm-history.md`.

Chaque constat est une preuve (fichier:ligne, au commit audité), un effet observable et un
correctif proposé. **Tous sont corrigés sauf F6** (statut en tête de chacun). Les chiffres marqués *calculé* sont des probabilités
binomiales sous l'hypothèse la plus favorable au modèle (parfaitement calibré, erreurs
indépendantes) ; rien n'a été mesuré sur une série réelle, faute de cluster et de poids TimesFM
dans l'environnement de l'audit.

## Verdict

L'architecture est saine et prudente : inférence hors des chemins de lecture, MCP strictement en
lecture seule, périmètre vérifié avant toute I/O, verrou consultatif + bail pour la publication,
fallback étiqueté sans bande de confiance, contrat Python/Java validé des deux côtés. Les
honnêtetés de surface (`Measured`, `SHADOW`/`ACTIVE`, quantiles « nominaux ») sont là.

Mais **en l'état, une série n'atteindra pas durablement `ACTIVE`, et l'outil MCP de
franchissements peut répondre « rien à signaler » précisément quand le modèle est en panne.**
Quatre constats y concourent et se renforcent : la planification partagée prive la collecte de
son thread (F1), un point imputé suspend l'évaluation pendant 512 pas (F3), le détecteur de
dérive juge sur une seule cohorte et verrouille `DEGRADED` (F2), et l'absence d'évaluation est
rendue comme une absence de franchissement (F4).

| # | Sévérité | Constat |
|---|---|---|
| F1 | Critique, *corrigé* | Le pilote partage l'unique thread `@Scheduled` avec la collecte des métriques |
| F2 | Haute, *corrigé* | La dérive est jugée sur une cohorte unique puis verrouillée : `DEGRADED` est quasi certain |
| F3 | Haute, *corrigé* | Un seul point imputé dans le contexte suspend l'évaluation pendant 512 pas |
| F4 | Haute, *corrigé* | `kex_list_predicted_threshold_breaches` confond « non évalué » et « aucun franchissement » |
| F5 | Moyenne, *corrigé* | DLP appliqué au catalogue, pas aux quatre autres outils |
| F6 | Moyenne | Le retour à `SHADOW` est refusé (409) pendant un rafraîchissement |
| F7 | Moyenne, *corrigé* | Clé d'idempotence construite sur `Record.toString()` |
| F8 | Moyenne, *corrigé* | Un TimesFM qui ne répond plus réduit le cycle à deux séries |
| F9 | Moyenne, *corrigé* | `fix_quantile_crossing=False` transforme toute inversion de quantiles en fallback |
| F10 | Basse | Une connexion PostgreSQL neuve par lecture MCP |
| F11 | Basse | `checkNotQuarantined(seriesId)` ne contrôle rien |
| F12 | Basse | Cinq outils annoncés même sans aucune série approuvée |
| F13 | Basse | Purge doublée par série, budget occupé par des séries retirées |

## F1 — Critique : le pilote bloque la collecte qu'il consomme

**Corrigé** : `ForecastPilotScheduler` (`SmartLifecycle`, thread `forecast-pilot`), tests dans
`ForecastPilotSchedulerTest`. Le correctif proposé ci-dessous était **faux** et ne doit pas être
repris : publier un bean `ScheduledExecutorService` satisfait le `@ConditionalOnMissingBean` de
l'auto-configuration du planificateur de Spring Boot, qui ne crée alors plus le sien — et
`MetricService.refreshMetrics` serait passé sur le thread du pilote. L'exécuteur est donc privé, et
un test vérifie qu'aucun bean `ScheduledExecutorService` ni `TaskScheduler` n'est publié.

**Preuve.** `ForecastPilotService.refresh()` (`ForecastPilotService.java:85`) et
`MetricService.refreshMetrics()` (`MetricService.java:1901`) sont deux `@Scheduled`. Aucun
`spring.task.scheduling.pool.size`, aucun `TaskScheduler`, aucun thread virtuel n'est configuré :
Spring Boot fournit alors un ordonnanceur à **un seul thread**. Un cycle de pilote dure jusqu'à
60 s de budget (`ForecastPilotService.java:92`) plus l'appel en vol, borné à 35 s par défaut
(`TimesFmInferenceProperties.java:12`), plus les lectures PostgreSQL.

**Effet.** Pendant un cycle, aucune collecte (`explorer.metrics-refresh-rate`, 30 s) ne
s'exécute. Avec un pas de préparation de 60 s, un cycle de plus d'une minute laisse un seau vide
→ point imputé (F3) ou `INSUFFICIENT_HISTORY` dès deux pas manquants consécutifs
(`SeriesPreparationProfile.MAX_GAP_STEPS = 2`). L'objectif écrit dans `timesfm.md` — « les
collecteurs de métriques restent indépendants de l'inférence » — est faux à l'exécution, et la
dégradation s'auto-entretient : plus TimesFM est lent, plus l'historique a de trous.

**Correctif.** Un ordonnanceur dédié, propre au pilote, plutôt qu'un réglage global du pool qui
changerait l'ordonnancement de toute l'application :

```java
// ForecastPilotConfiguration.java — retirer @EnableScheduling ici et @Scheduled sur refresh()
@Bean(destroyMethod = "shutdownNow")
ScheduledExecutorService forecastPilotScheduler(ForecastPilotService pilot, ForecastPilotProperties p) {
  var executor = Executors.newSingleThreadScheduledExecutor(
      Thread.ofPlatform().name("forecast-pilot").daemon().factory());
  long period = p.getInterval().toMillis();
  executor.scheduleWithFixedDelay(() -> {
    try {
      pilot.refresh();
    } catch (RuntimeException e) { // une exception non rattrapée annulerait toutes les exécutions suivantes
      LoggerFactory.getLogger(ForecastPilotService.class).warn("Forecast pilot cycle failed", e);
    }
  }, period, period, TimeUnit.MILLISECONDS);
  return executor;
}
```

```java
// ForecastPilotSchedulingTest.java
@Test
void pilotCycleRunsOffTheSharedSchedulingThread() throws Exception {
  var pilot = mock(ForecastPilotService.class);
  var thread = new CompletableFuture<String>();
  doAnswer(i -> thread.complete(Thread.currentThread().getName())).when(pilot).refresh();
  var props = new ForecastPilotProperties();
  props.setInterval(Duration.ofMillis(10)); // validate() n'est pas appelé par le bean d'ordonnancement
  var executor = new ForecastPilotConfiguration().forecastPilotScheduler(pilot, props);
  try {
    assertThat(thread.get(2, TimeUnit.SECONDS)).isEqualTo("forecast-pilot");
  } finally {
    executor.shutdownNow();
  }
}
```

`nextScheduledAt()` reste juste : il est mis à jour par `refresh()` lui-même.

## F2 — Haute : un détecteur de dérive qui se déclenche sur un modèle parfait

**Corrigé** autrement que proposé ci-dessous : `ForecastQualityWindow`, tests dans
`ForecastQualityWindowTest` et `ForecastPilotServiceTest`. La fenêtre glissante proposée ici ne
suffisait pas — simulée, jugée à chaque cohorte et verrouillée, elle se déclenche encore dans
100 % des cas en 30 jours sur un modèle calibré, parce que des fenêtres qui se chevauchent
répètent le même test des milliers de fois. Ce qui est livré : blocs **disjoints** d'au moins
120 points jugés une fois, borne de couverture à 2,326 erreurs-types (la plus grande de l'erreur
binomiale et de celle mesurée entre cohortes), tolérance de 10 % contre les baselines pour la
dérive, aucune pour l'activation, marge de 0,1 % du niveau de la série, et `DEGRADED` après deux
blocs en échec consécutifs. Simulé sur 30 jours : environ 1 % de verrouillages à tort, quelle que
soit la corrélation intra-cohorte ; une couverture réelle de 0,6 est détectée en deux blocs quand
les cohortes sont indépendantes, plus lentement sinon. Le refus d'enrôler une série constante n'a
pas été fait : la marge de niveau suffit à ce qu'elle ne passe plus en `DEGRADED`.

**Preuve.** `ForecastPilotService.java:176-185` : à chaque cohorte mûre, `quality` est recalculée
sur **cette seule cohorte** (`horizon` points, 10 dans l'exemple du runbook), puis
`degraded |= coverage < minimumCoverage || cohortMae > mae(baseline)` pour **n'importe laquelle**
des quatre baselines, comparaison stricte. `DEGRADED` est verrouillé : seule une révision de
configuration le lève. L'activation (`ForecastPilotService.java:236`) applique le même test sur la
même cohorte unique.

**Effet, calculé.** Intervalle Q10–Q90 nominal à 80 %, seuil de couverture 0,8 (valeur de
l'exemple du runbook **et** valeur codée en dur par l'assistant, `ForecastSetupService.java:202`) :

| Situation | Probabilité de déclencher `DEGRADED` |
|---|---:|
| Une cohorte de 10 points, modèle parfaitement calibré | 32,2 % |
| Couverture cumulée sur 120 points, même modèle | 44,6 % |
| `ACTIVE` encore là après 5 / 10 cohortes (couverture seule) | ≤ 14,3 % / ≤ 2,0 % |

Et cela avant la comparaison aux baselines, qui ne fait qu'aggraver : battre le minimum de quatre
estimateurs sur dix points est une loterie, et une série constante — nombre de partitions,
nombre de brokers, précisément les jauges ajoutées par `365e8a6` — donne
`LAST_VALUE` à MAE 0 : la moindre erreur flottante du modèle la passe en `DEGRADED`. L'assistant
fixe aussi `seasonLength = 1`, ce qui rend `SEASONAL_NAIVE` identique à `LAST_VALUE` : la
« comparaison à quatre baselines » en compare trois.

**Correctif.** Juger sur une fenêtre glissante de cohortes, avec une borne statistique pour la
couverture et une tolérance relative contre les baselines ; refuser à l'enrôlement une série dont
l'historique est constant.

```java
// RollingQuality.java — persisté dans ForecastRecord à la place de la cohorte isolée
public record RollingQuality(List<Cohort> cohorts) {
  public static final int WINDOW = 12;
  private static final double Z_99 = 2.326;

  public record Cohort(double absErrorSum, int covered, int points, Map<String, Double> baselineAbsErrorSum) {}

  public RollingQuality {
    cohorts = List.copyOf(cohorts.subList(Math.max(0, cohorts.size() - WINDOW), cohorts.size()));
  }

  public RollingQuality add(Cohort c) {
    return new RollingQuality(Stream.concat(cohorts.stream(), Stream.of(c)).toList());
  }

  int points() { return cohorts.stream().mapToInt(Cohort::points).sum(); }

  public double mae() { return cohorts.stream().mapToDouble(Cohort::absErrorSum).sum() / points(); }

  /** Échoue seulement sous la borne unilatérale à 99 % : un modèle calibré passe presque toujours. */
  public boolean coverageBelow(double nominal) {
    double observed = (double) cohorts.stream().mapToInt(Cohort::covered).sum() / points();
    // Erreurs autocorrélées : n effectif < points(), la borne reste optimiste — à mesurer.
    return observed < nominal - Z_99 * Math.sqrt(nominal * (1 - nominal) / points());
  }

  /** Perd seulement s'il fait nettement pire qu'une baseline sur toute la fenêtre. */
  public boolean losesTo(double relativeTolerance) {
    double model = mae();
    return cohorts.getFirst().baselineAbsErrorSum().keySet().stream()
        .mapToDouble(name -> cohorts.stream().mapToDouble(c -> c.baselineAbsErrorSum().get(name)).sum() / points())
        .anyMatch(baseline -> model > baseline * (1 + relativeTolerance) + 1e-9);
  }
}
```

Le verdict `DEGRADED` ne s'applique qu'une fois `WINDOW` cohortes réunies ; `activate()` lit le
même objet. Le `1e-9` absolu évite le cas MAE 0, mais une série constante n'a de toute façon rien
à prévoir : `ForecastSetupService` doit la refuser avec la raison.

## F3 — Haute : un trou isolé suspend l'évaluation pendant huit heures

**Corrigé** comme proposé : seules les valeurs observées de l'horizon réalisé comptent ; tests
`imputedPointOutsideTheRealisedHorizonStillEvaluates` et
`imputedPointInsideTheRealisedHorizonIsNeverScored`.

**Preuve.** `ForecastPilotService.java:160` exige `context.imputedPoints() == 0` sur les **512**
points du contexte courant. Le préparateur admet jusqu'à 5 % de points imputés (25) et des trous
de deux pas (`MetricSeriesPreparer.java:116-128`) : un contexte `READY` avec des points imputés
est l'état ordinaire d'une collecte réelle, a fortiori avec F1.

**Effet.** Un seul seau manquant interdit toute évaluation tant qu'il reste dans la fenêtre :
512 pas, soit 8 h 32 au pas d'une minute. `evaluatedPoints` n'atteint pas
`minimumEvaluatedPoints`, l'activation reste refusée, et rien ne dit pourquoi — la raison
publiée est celle de la préparation (« Short gauge gaps filled… »), pas « évaluation suspendue ».

Le test vise aussi le mauvais ensemble dans l'autre sens : `values` est construit à partir de
**tous** les points (`ForecastPilotService.java:163`), imputés compris ; seul le garde global
empêche de noter le modèle contre une valeur inventée.

**Correctif.** Exiger des valeurs observées sur l'horizon évalué seulement :

```java
// ForecastPilotService.refresh — remplace la condition imputedPoints() == 0 et la construction de values
var observed = context.points().stream()
    .filter(p -> !p.imputed() && p.value() != null)
    .collect(Collectors.toMap(PreparedMetricSeries.Point::endAt, PreparedMetricSeries.Point::value));
var points = matured.forecast().points();
if (points.stream().allMatch(p -> observed.containsKey(p.at()))) { ... }
```

Le contexte d'entraînement de la cohorte mûre peut, lui, contenir des imputations : c'est ce que
le modèle a réellement vu, il est juste de l'évaluer dessus.

## F4 — Haute : l'outil de franchissements répond « zéro » quand il n'a rien évalué

**Corrigé** : `ForecastPilotService.evaluateBreach` renvoie une issue typée (`BREACH`,
`NO_BREACH`, `NO_POLICY`, `NOT_EVALUATED`) et sa raison ; l'outil rend une ligne par série
autorisée et une couverture `PARTIAL_FAILURE` qui nomme les séries non évaluées. Une série sans
politique de seuil est listée mais ne compte pas comme demandée. Tests dans
`ForecastPilotServiceTest`.

**Preuve.** `ForecastMcpTools.breaches()` (`ForecastMcpTools.java:186-195`) n'ajoute une ligne
que si `pilot.breach()` renvoie un franchissement. `breach()` renvoie `null` sans distinction
(`ForecastPilotService.java:275-285`) : pas de seuil configuré, pas de prévision, prévision
`STALE`, stratégie de fallback, `DEGRADED`, horizon écoulé. Et chaque outil déclare
`Coverage.exhausted(0, 0, 0)` (`ForecastMcpTools.java:214`) : « lecture complète ».

**Effet.** TimesFM tombe → toutes les séries passent en `SEASONAL_NAIVE`/`LAST_VALUE`, qui ne
produisent jamais de franchissement par conception → l'outil renvoie `[]` avec
`stopReason: EXHAUSTED`. Un agent conclut « aucun franchissement prévu » au moment exact où plus
rien n'est prévu. C'est la règle que `CLAUDE.md` pose pour tout le module — « a measurement that
failed is never zero » — et celle que `Coverage` existe pour faire respecter.

**Correctif.** Une ligne par série autorisée, avec son issue, et une couverture partielle qui
nomme les séries non évaluées :

```java
// ForecastMcpTools.java
public enum Outcome { BREACH, NO_BREACH, NOT_EVALUATED }

public record SeriesBreach(String seriesId, Outcome outcome, String reason,
                           ForecastPilotService.PredictedBreach breach) {}

public ToolResult<List<SeriesBreach>> breaches() {
  var rows = allowed().stream().map(this::evaluate).toList();
  var notReached = rows.stream().filter(r -> r.outcome() == Outcome.NOT_EVALUATED)
      .map(SeriesBreach::seriesId).toList();
  var coverage = notReached.isEmpty()
      ? Coverage.exhausted(rows.size(), 0, 0)
      : Coverage.partial(rows.size(), rows.size() - notReached.size(), notReached, 0, 0,
          StopReason.PARTIAL_FAILURE, null);
  return ToolResult.of(rows, coverage, List.of(LIMITS));
}
```

`ForecastPilotService.breach()` retourne alors un résultat typé (« pas de seuil », « prévision
STALE », « fallback : TimesFM TIMEOUT »…) au lieu de `null`. C'est un changement de forme du
retour MCP : `McpServerBootTest` le verra, et la note `docs/notes/mcp-server.md` doit le dire.

## F5 — Moyenne : DLP sur le catalogue seulement

**Corrigé** : les quatre autres outils passent les mêmes chaînes d'identité (version de
définition, unités, raisons) dans `scrub`, enregistrement imbriqué compris ; test
`everyForecastExitScrubsWhatTheCatalogueScrubs`.

**Preuve.** `catalog()` passe `metricId`, `environment`, `unit` et `definitionVersion` dans
`guard.dlp().scrub` et marque `complete = false` quand un identifiant est masqué
(`ForecastMcpTools.java:36-45`). `history()`, `get()`, `quality()` et `breaches()` renvoient
`PreparedMetricSeries`, `ForecastRecord` et `PredictedBreach` tels quels : `definitionVersion`,
`outputUnit`, `reason`. `McpToolInterceptor` ne nettoie que les paramètres
(`McpToolInterceptor.java:124`), jamais les résultats.

**Effet.** Une valeur que l'opérateur a demandé de masquer l'est dans `kex_list_forecastable_metrics`
et apparaît en clair dans `kex_forecast_metric` sur la même série. Les valeurs numériques ne sont
pas en cause ; ce sont les quelques chaînes d'identité.

**Correctif.** Une seule projection MCP par type exposé, construite à un seul endroit et
appliquant `scrub` aux mêmes champs que le catalogue ; un test qui configure un motif DLP
correspondant à `definitionVersion` et l'assert absent des cinq réponses.

## F6 — Moyenne : le retour à SHADOW attend la fin du rafraîchissement

**Preuve.** `activate(id, false)` prend le même verrou consultatif que le rafraîchissement et
lève `IllegalArgumentException` → 409 s'il est tenu (`ForecastPilotService.java:239-240`).
`timesfm.md` promet : « le retour à SHADOW est immédiat via le contrôle opérateur ».

**Effet.** Pendant un cycle (jusqu'à ~95 s avec F8), l'opérateur qui veut couper une série
`ACTIVE` reçoit un refus et doit réessayer.

**Correctif.** La désactivation n'a pas besoin du verrou : la visibilité exposée est recalculée à
la lecture depuis `kex_forecast_pilot_control_v2` (`ForecastPilotService.java:63-68`), donc un
enregistrement publié avec `ACTIVE` juste après la coupure est relu `SHADOW`. Seule l'activation
garde le verrou, puisqu'elle juge la qualité courante.

```java
public void activate(String id, boolean active) throws Exception {
  var spec = resolve(id);
  if (!active) {
    store.activate(id, false); // idempotent, autocommit : jamais refusé
    return;
  }
  // ... chemin actuel inchangé pour active == true
}
```

## F7 — Moyenne : une clé d'idempotence qui dépend de `toString()`

**Corrigé** : `ForecastPilotService.canonical` encode la spécification champ par champ, longueurs
préfixées et doubles par leur motif binaire. L'ancienne clé reste acceptée en lecture
(`legacyKey`), sans quoi la mise à jour aurait elle-même produit l'effet décrit ici : séries
`ACTIVE` coupées et cohortes en cours perdues ; elle peut disparaître après une période de
rétention. `absenceReason` distingue « jamais écrit » de « retenu, spécification antérieure ».

**Preuve.** `ForecastPilotService.key()` concatène `s.toString()` du record `Series`
(`ForecastPilotService.java:333`), records imbriqués et `Double` compris. La Javadoc de
`Record.toString()` dit du format : « unspecified and subject to change ».

**Effet.** Une montée de JDK qui change ce format (le build Docker est en JDK 26, l'exécution en
JRE 25) invalide toutes les clés d'un coup : `previous` ne correspond plus, `store.disable` coupe
toutes les séries `ACTIVE` (`ForecastPilotService.java:137-143`), l'évaluation repart de zéro, et
`get()` renvoie `null` sur l'enregistrement existant (`ForecastPilotService.java:57`) — que
l'outil MCP traduit en « No forecast has been persisted », phrase fausse : il existe, il est
d'une spécification antérieure.

**Correctif.** Une sérialisation canonique explicite (JSON trié des champs, ou concaténation
nommée champ par champ) ; et une raison distincte quand l'enregistrement est retenu pour
incompatibilité de spécification.

## F8 — Moyenne : un modèle muet affame le cycle

**Corrigé** comme proposé : après un `TIMEOUT` ou un `UNAVAILABLE`, les séries restantes du
cycle passent directement en fallback, raison et compteur à l'appui ; `BUSY` et
`INVALID_OUTPUT` ne coupent pas le modèle. La transaction reste ouverte pendant le seul appel
qui attend.

**Preuve.** Le délai TimesFM par défaut est 35 s, le budget de cycle 60 s, les séries sont
traitées une par une et chaque `TIMEOUT` déclenche un fallback série par série
(`ForecastPilotService.java:198-206`). Rien ne retient d'un appel à l'autre que le service ne
répond pas.

**Effet.** Service qui accepte la connexion mais ne répond pas : deux séries par cycle. Avec 20
séries et un intervalle de 5 min, chaque série est rafraîchie toutes les ~50 min ; pour un
horizon de 10 min, ses prévisions sont `STALE` les quatre cinquièmes du temps, et la transaction
PostgreSQL reste ouverte pendant chaque attente.

**Correctif.** Après un `TIMEOUT` ou `UNAVAILABLE`, passer le reste du cycle en fallback direct
sans appeler le client, et le dire dans la raison (« TimesFM TIMEOUT plus tôt dans ce cycle »).

## F9 — Moyenne, à mesurer : quantiles croisés = fallback

**Corrigé** sans mesure préalable, parce que le code amont rend la mesure inutile :
`fix_quantile_crossing` de TimesFM 2.0.2 corrige les quantiles de proche en proche *depuis la
médiane*, qu'il ne touche pas, puis la dénormalisation et l'écrêtage à zéro sont monotones — les
quantiles reviennent ordonnés et `central == q50` tient toujours. Le contrat continue de vérifier
les deux. `infer_is_positive` (le défaut amont) n'écrête qu'une série dont le contexte est
entièrement positif. `ADAPTER_VERSION` passe à `kex-timesfm-2.5-v2` des deux côtés. Les benchmarks
de `timesfm.md` ont été mesurés avant ce changement ; les deux opérations ajoutées sont des
`torch.where` sur un tenseur de 60 × 10 par série.

**Preuve.** Le service compile le modèle avec `fix_quantile_crossing=False`
(`services/timesfm/kex_timesfm/model.py:35`) et refuse ensuite toute inversion sur les neuf
quantiles et tout l'horizon (`contract.py:64-65`) ; le client Java refait le contrôle.

**Effet.** Une seule inversion, même sur un quantile intermédiaire non exposé, fait de tout
l'appel un `INVALID_OUTPUT` → fallback ponctuel, sans bande ni franchissement. La fréquence n'est
pas mesurée ; le compteur existe (`explorer_forecasting_invalid_output_total`).
`infer_is_positive=False` laisse par ailleurs des Q10 négatifs sur des grandeurs qui ne le sont
jamais (lag, comptages), ce qui fausse les seuils `BELOW`.

**Correctif.** Mesurer d'abord le taux d'`INVALID_OUTPUT` sur des séries réelles. S'il n'est pas
négligeable, activer `fix_quantile_crossing` et changer `ADAPTER_VERSION` (la clé
d'idempotence et la provenance le portent déjà) plutôt que d'assouplir le contrôle.

## F10 à F13 — Basses

**Corrigés** : F10 par `ForecastConnectionPool` (quatre connexions inactives au plus, rollback
avant réutilisation pour libérer le verrou consultatif), F11 en retirant l'appel, F12 par
`ApprovedForecastSeriesCondition` et une ligne masquée avec sa raison dans le catalogue de la
console (le schéma des outils reste vérifié par `McpForecastToolsBootTest`, qui approuve une
série), F13 en retirant la seconde purge et en ne comptant que les séries approuvées.

- **F10.** `ForecastPilotStore.open()` crée une connexion par `DriverManager` à chaque appel
  (`ForecastPilotStore.java:30`) : chaque lecture MCP ouvre et ferme une connexion PostgreSQL,
  TLS compris, et `kex_list_predicted_threshold_breaches` en ouvre une par série. Un petit pool
  borné (2–4) suffit.
- **F11.** `authorize()` appelle `guard.checkNotQuarantined(id)` avec l'identifiant de **série**
  (`ForecastMcpTools.java:55`), alors que l'ensemble mis en quarantaine contient des identités
  d'appelant. Le contrôle ne fait rien ; la quarantaine de l'appelant est déjà faite par
  `McpToolInterceptor`. À retirer, ou à remplacer par un vrai mécanisme de mise à l'écart d'une
  série si c'est l'intention.
- **F12.** Le pilote étant activé par défaut, les cinq outils figurent dans `tools/list` même
  sans aucune série approuvée : le modèle les voit, les choisit, reçoit une liste vide ou
  `OUT_OF_SCOPE`. C'est le cas que la règle « a denied tool that appears… » de `CLAUDE.md` décrit.
  Conditionner l'enregistrement à une liste de séries non vide et déclarer le retrait à
  `McpCatalogService` avec la raison.
- **F13.** `store.purge` est appelé deux fois par série et par cycle
  (`ForecastPilotService.java:122` et `228`) ; une série retirée de la configuration continue de
  compter dans `max-series` jusqu'à l'expiration de la rétention (`checkSeriesBudget` compte les
  `series_id` présents en table).

## Ce qui tient

- Lecture seule réelle : aucun outil ne déclenche SQL, collecte ni inférence ; les cinq
  annotations sont `readOnlyHint`, et l'enregistrement dépend de `explorer.forecasting.pilot.enabled`.
- Périmètre vérifié avant toute lecture de stockage (environnement exact, tous les topics et
  groupes déclarés), identifiant inconnu → `OUT_OF_SCOPE`, catalogue filtré.
- Publication clôturée : verrou transactionnel + bail en temps base de données, `INSERT … WHERE
  EXISTS` sur le propriétaire non expiré.
- Fallback étiqueté, sans intervalle et jamais `ACTIVE` ; historique invalide → pas de prévision ;
  zéro n'est jamais substitué à un manque dans le journal.
- Client HTTP borné (taille requête et réponse, délai couvrant le corps, pas de redirection,
  sémaphore d'un appel) ; contrat vérifié des deux côtés (révision du modèle, version
  d'adaptateur, forme et ordre des quantiles) ; poids vérifiés par SHA-256.

## Ordre de correction proposé

1. **F1**, seul et d'abord : il fausse toutes les mesures des autres constats.
2. **F3** puis **F2** ensemble : sans eux, aucune donnée de qualité exploitable ni activation
   durable — à valider sur une série réelle en `SHADOW` avant toute décision `ACTIVE`.
3. **F4** et **F5** : contrat MCP, changement de forme à annoncer aux clients.
4. **F6**, **F7**, **F8**.
5. **F9** après mesure ; **F10–F13** au fil de l'eau.
