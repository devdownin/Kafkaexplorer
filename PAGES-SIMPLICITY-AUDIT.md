# Topic Explorer, Dead Letter, Stream Flow — audit de simplicité et de fluidité (2026-11)

Périmètre : trois pages de lecture, regardées avec la même grille que `SQL-EDITOR-AUDIT.md` —
ce qu'il faut de gestes pour la question ordinaire de chaque page, ce que la page affirme et qui
n'est pas vrai, ce qui sort du cadre. `pages/TopicExplorer.tsx`, `pages/DeadLetter.tsx`,
`pages/StreamFlow.tsx` et ce qu'ils importent (`dashboardRefresh.ts`, `streamFlowLogic.ts`).

> **Statut.** **Tout est corrigé** (PR suivante), chaque correction avec un test qui échoue sans
> elle quand elle se teste hors navigateur : Dead Letter prend la marge des pages *Observe* et
> n'offre plus que 30 s, 1 min et 5 min, la ligne d'état disant « every 30 s » (D1, D2) ; Topic
> Explorer s'ouvre sur Latest, le mode de lecture vit au-dessus de l'échantillon qu'il gouverne et
> disparaît pendant une recherche (T1, T2), les deux cases « … too » sont rangées sous « Scan
> options » et annoncées repliées quand elles quittent le défaut (T3), l'invitation sous la table
> est une ligne (T4), les outils des résultats forment un groupe insécable (T5) ; Stream Flow
> propose de restreindre la trace suivante aux topics atteints (S1), ne descend pas sous l'échelle
> 0,75 à l'arrivée et part de la source (S2), et son exemple tient dans le champ (S3) ;
> `GET /api/forecasts` dit `shadowEnabled`, et le panneau ne demande plus une comparaison qui
> n'existe pas (X1). Les captures `topic-explorer`, `stream-flow` et `dead-letter` sont refaites.
>
> Le constat initial, ci-dessous, est laissé tel qu'établi. Chaque constat dit comment il a été
> établi — **observé** (le SPA compilé servi par `docs/screenshots/server.mjs` sur les données
> figées de `docs/screenshots/fixtures.mjs`, piloté par Playwright à 1440×900), ou **lu** (déduit
> du code, fichier nommé). Aucun broker n'a tourné : les durées de scan sont celles des fixtures.

| # | Constat | Page | Gravité | Établi |
|---|---|---|---|---|
| D1 | La page n'a pas de marge et déborde à droite | Dead Letter | **Haute** | observé |
| D2 | « Every 5 s » est affiché, la page interroge toutes les 30 s | Dead Letter | **Haute** | observé + lu |
| T1 | Le mode de lecture part sur Earliest, l'éditeur SQL sur Latest | Topic Explorer | Moyenne | observé + lu |
| T2 | Le mode de lecture ne gouverne pas la recherche | Topic Explorer | Moyenne | observé |
| T3 | Une barre de recherche chargée, dont deux cases qui recouvrent des modes | Topic Explorer | Moyenne | observé |
| T4 | Un panneau vide sous les résultats | Topic Explorer | Basse | observé + lu |
| T5 | La ligne d'actions des résultats passe à la ligne | Topic Explorer | Basse | observé |
| S1 | Par défaut, la trace scanne tout le cluster | Stream Flow | Moyenne | observé |
| S2 | Le graphe recadré rend des nœuds illisibles | Stream Flow | Moyenne | observé |
| S3 | Le placeholder de « Search Path » est tronqué | Stream Flow | Basse | observé |
| X1 | Chaque chargement de la page Metrics écrit une erreur console | Metrics | Basse | observé |

## Dead Letter

### D1 — La page n'a pas de marge et déborde à droite *(haute, observé)*

Le conteneur racine est `<div className="space-y-6">` (`DeadLetter.tsx:407`) là où les autres
pages portent `p-4 md:p-6 max-w-7xl mx-auto`. Mesuré : le titre commence à x=256 (280 ailleurs),
un élément s'étend jusqu'à 1556 px dans une fenêtre de 1440 px, et la carte KPI comme le libellé
« 3 queues » sont coupés au bord droit. C'est la seule page de l'application dont le contenu touche
la barre latérale.

**Correction proposée** : le même conteneur que les autres pages ; `layout-probe.mjs --check`
dira si la largeur tient.

### D2 — « Every 5 s » est affiché, la page interroge toutes les 30 s *(haute, observé + lu)*

Le sélecteur reprend `REFRESH_OPTIONS` du tableau de bord (2 s … 5 min, défaut 5 s) et la ligne
d'état dit « refreshing every 5 s », mais le sondage passe par `activityIntervalMs`, qui la plancher
à `ACTIVITY_FLOOR_MS` = 30 s (`dashboardRefresh.ts:96`). Observé : deux rafraîchissements en
30 s. Trois des six options (2 s, 5 s, 15 s) promettent une cadence que la page ne tient pas — la
phrase d'état affirme quelque chose de faux, ce que `describeRefreshStatus` existe justement pour
éviter.

**Correction proposée** : offrir sur cette page les seules options ≥ 30 s (défaut 30 s), ou faire
dire à la ligne d'état la période effective. La première est plus simple : un choix qui ne
change rien ne devrait pas être offert.

## Topic Explorer

### T1 — Le mode de lecture part sur Earliest *(moyenne, observé + lu)*

`useState('earliest-offset')` (`TopicExplorer.tsx:679`) : l'aperçu montre les plus anciens
messages d'un topic, alors que l'éditeur SQL vient de passer à Latest par défaut et que la
question ordinaire en ouvrant un topic est « qu'est-ce qui y arrive ». Les deux pages répondent
désormais différemment au même premier geste.

### T2 — Le mode de lecture ne gouverne pas la recherche *(moyenne, observé)*

Read mode affiché sur Earliest, une recherche de `ORD-1042` répond « Newest 4,318 records
scanned » et propose « Scan further back ». La recherche a sa propre notion de fenêtre (le select
« Whole topic » et les options repliées) : deux réglages de « où lire » sur le même écran, dont
un seul s'applique à ce qu'on fait. Rien ne dit lequel.

**Correction proposée** : placer Read mode dans l'onglet Messages, au-dessus de l'aperçu qu'il
gouverne, plutôt qu'en tuile d'en-tête qui semble régir toute la page.

### T3 — Une barre de recherche chargée, dont deux cases qui recouvrent des modes *(moyenne, observé)*

Cinq modes (Text, Regex, Field, Header, Key), un select de portée, trois cases, quatre
boutons d'info, un dépliant « Scan options » — 34 contrôles au-dessus du pli au total. « Search
keys too », coché par défaut, recouvre le mode Key ; « Search headers too » recouvre le mode
Header. *(Le select « Whole topic » avait d'abord été noté comme natif : c'est bien le `Select`
partagé, rectifié à la lecture de `TopicSearchPanel.tsx`.)*

**Correction proposée** : ranger les deux cases sous « Scan options », annoncées repliées quand
elles quittent le défaut.

### T4 — Un panneau vide sous les résultats *(basse, observé + lu)*

« Select a row to read the record » (`TopicExplorer.tsx:1449`) occupe un bloc pleine largeur
sous la table avant toute sélection — de la hauteur pour rien sur la première vue.

### T5 — La ligne d'actions des résultats passe à la ligne *(basse, observé)*

À 1440 px, compteurs + « Scan further back » + Link, Pin, CSV, JSON + Clear ne tiennent pas :
« Clear » tombe seul sur une seconde ligne.

## Stream Flow

### S1 — Par défaut, la trace scanne tout le cluster *(moyenne, observé)*

Target Topics vide = « the whole cluster is scanned, one consumer per topic », et le texte même
de la page ajoute que nommer les topics est « much faster ». Le défaut est donc le cas lent, et la
seule aide est un paragraphe sous le champ. Observé sur les fixtures : 28/28 topics lus pour 6
correspondances. Le bon point : Entrée lance la trace, la dernière clé et les traces récentes
reviennent après rechargement.

**Correction proposée** : préremplir la cible avec le motif de la dernière trace (ou du préfixe
commun des topics trouvés), le scan complet restant à un clic.

### S2 — Le graphe recadré rend des nœuds illisibles *(moyenne, observé)*

Six nœuds en ligne, recadrés pour tenir dans la largeur : chaque nœud fait ~65 px et son texte
est illisible à 100 %. Il faut zoomer pour lire un nom de topic que la table, juste dessous,
affiche en clair. Le recadrage devrait borner l'échelle minimale et laisser défiler
(`useGraphViewport`), ou disposer une chaîne longue sur plusieurs rangs.

### S3 — Le placeholder de « Search Path » est tronqué *(basse, observé)*

`order.id · $.orderId · /order…` coupé dans la colonne de 288 px ; le troisième exemple (XPath)
ne se lit pas.

## Hors périmètre, relevé en passant

### X1 — Chaque chargement de Metrics écrit une erreur console *(basse, observé)*

`GET /api/forecasts/{id}/shadow` répond 404 quand aucune comparaison n'existe, ce qui est vrai,
mais le navigateur l'écrit « Failed to load resource » à chaque ouverture. Un 200 avec un corps
vide dirait la même chose sans bruit.
