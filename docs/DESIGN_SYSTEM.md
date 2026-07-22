# Kafka Explorer — Design System & Refonte UI

> Refonte UX/UI complète alignée sur le rendu **Spectra** : thème sombre premium,
> tokens Material-like, kit de composants réutilisable. Aucune fonctionnalité
> métier n'a été retirée — seule la couche de présentation change.

Ce document est le livrable de référence : **audit**, **roadmap**, **design
system**, **charte graphique** et **recommandations de perception qualité**.

---

## 1. Audit global

### 1.1 Constat initial

L'application partait d'un thème « terminal » (cyan `#25f4f4` sur teal
`#102222`, capitales agressives, micro-texte 9–11 px, couleurs codées en dur).
Fonctionnellement riche (16 pages), mais visuellement daté et incohérent :
chaque page ré-inventait ses cartes, boutons, tableaux et badges.

| Axe | Points faibles identifiés | Correctif apporté |
|-----|---------------------------|-------------------|
| **Cohérence** | Boutons/cartes/tableaux réimplémentés page par page ; ~40 nuances de couleur ad hoc (`slate`, `emerald`, `red`, `amber`, hex bruts). | Kit `components/ui` unique + palette tokenisée. Toutes les couleurs ad hoc mappées sur des tokens sémantiques. |
| **Hiérarchie** | Titres en capitales + `tracking-widest`, tailles incohérentes, pas d'overline. | `PageHeader` normalisé (kicker / titre / description / actions). Casse normale, chasse resserrée. |
| **Lisibilité** | Micro-texte 9–11 px sous le seuil WCAG ; accent cyan éblouissant sur fond très sombre. | Plancher typographique 11–12 px ; accent indigo `#a3adff` (contraste doux, non éblouissant). |
| **Navigation** | Sidebar plate de 9 entrées non groupées + faux profil « Admin User » ; header avec boutons décoratifs (cloche non fonctionnelle). | Sidebar **groupée** (Explore / Observe / Analyze), repliable, drawer mobile. Header = fil d'Ariane + état de connexion réel + recherche. Faux contenu retiré. |
| **États** | Chargements = spinner nu ; états vides = texte gris ; erreurs = ligne rouge. | `Spinner`/`ProgressBar`, `Skeleton`, `EmptyState`, `ErrorBanner` premium et cohérents. |
| **Robustesse** | Polices (Inter, Material Symbols) chargées depuis le CDN Google Fonts → dépendance réseau, risque d'icônes cassées hors-ligne. | Polices **auto-hébergées** (`@fontsource`, `material-symbols`). Zéro dépendance CDN au runtime. |
| **Performance** | Bundle unique monolithique (toutes les pages chargées d'emblée). | **Lazy loading** par route (code splitting) + fallback `ProgressBar`. |
| **Accessibilité** | Peu de `aria-*`, focus peu visibles, contrastes limites. | Focus ring global `:focus-visible`, labels/`aria` sur contrôles, respect de `prefers-reduced-motion`, contrastes AA. |

### 1.2 Analyse par page (synthèse)

- **Dashboard** — refonte complète sur le kit : KPI en `Stat` (accent de tonalité), table en primitives `Table`, badges d'état, pagination et « kill job » via `Button`. Logique (tri, filtres, pagination, refresh 5 s, trend « since last visit ») **inchangée**.
- **Cluster** — `PageHeader` + `Button` ; section « Critical Path » recolorée en tokens `error`.
- **Toutes les autres pages** (Query, Metrics, Audit, Lineage, StreamFlow, Compare, TopicExplorer, Config, Help, ProcessMining…) — reskinnées automatiquement via le **remap des tokens hérités** (voir §4.4) : couleurs, arrondis, ombres et typographie premium sans réécriture, donc **sans risque de régression métier**. Charts Recharts, graphes SVG (Lineage/StreamFlow) et thème Mermaid re-thémés sur la palette indigo.

---

## 2. Roadmap de refonte priorisée

### ✅ Quick Wins — *livrés dans cette itération*

1. **Tokeniser la palette** et remapper les tokens hérités → reskin instantané des 16 pages.
2. **Kit `components/ui`** (Button, Card, Badge, Field, Stat, PageHeader, EmptyState, Table, Skeleton, Spinner).
3. **Shell premium** : Sidebar groupée/repliable, Header (fil d'Ariane + statut + recherche), drawer mobile.
4. **Auto-hébergement des polices** (suppression du CDN).
5. **Lazy loading** des routes + états 404 / chargement soignés.
6. **Normalisation** des couleurs ad hoc et du micro-texte (WCAG).

### 🔷 Améliorations moyennes — *prochaines étapes*

1. Migrer **QueryWorkbench**, **Metrics**, **Audit**, **Compare**, **StreamFlow** vers les primitives `Table`/`Card`/`Field` (aujourd'hui reskinnées mais encore en markup local).
2. **Command Palette (⌘K)** unifiant recherche + navigation + actions (topics, tables, pages).
3. **Skeletons** dédiés par page (remplacer les spinners pleine zone) pour un chargement perçu plus fluide.
4. **Auto-héberger Monaco** (aujourd'hui chargé via CDN jsdelivr — même fragilité que les polices avant correction).
5. **Tooltips** accessibles et **ConfirmDialog** réutilisable pour les actions destructives (kill job, delete metric…).

### 🟣 Refonte majeure — *vision produit*

1. **Mode clair** optionnel (les tokens sont déjà prêts : il suffit d'un set clair + bascule `data-theme`).
2. **Densité configurable** (compact / confortable) sur les tables volumineuses.
3. **Virtualisation** des listes de topics/messages (au-delà du `content-visibility` natif).
4. **Onboarding / empty-first-run** guidant vers la première requête.
5. **Internationalisation** (fr/en) comme dans Spectra.

---

## 3. Design System

### 3.1 Kit de composants (`src/components/ui`)

| Composant | Rôle | Variantes clés |
|-----------|------|----------------|
| `Button` | Action | `primary` / `secondary` / `outline` / `ghost` / `danger` · tailles `sm/md/lg` · `icon`, `loading` |
| `Card` + `CardHeader` | Panneau de contenu | `interactive`, `padding` none/sm/md/lg |
| `Badge` | Statut / tag | tons `neutral/primary/secondary/success/warning/error` · `dot` |
| `Stat` | Tuile de métrique | `tone`, `icon`, `hint`, `loading` |
| `PageHeader` | En-tête de page | `kicker`, `description`, `actions` |
| `EmptyState` | État vide | `icon`, `action` |
| `Table` (+ `TableHead/Body/Row/Th/Td`) | Données tabulaires | carte scrollable, en-tête discret, hover de ligne |
| `Field` + `Input/Select/Textarea` | Formulaires | label + description/erreur + liaison `aria` auto |
| `Skeleton` / `SkeletonText` | Chargement | shimmer |
| `Spinner` / `ProgressBar` | Chargement indéterminé | tailles libres |

**Règle d'usage** : toute nouvelle surface doit consommer le kit plutôt que du
markup Tailwind local, pour garantir l'uniformité.

### 3.2 Navigation

Source unique de vérité : `src/navigation.ts` (nom, route, icône, groupe).
Consommée par la Sidebar **et** le fil d'Ariane du Header — impossible de
diverger. Regroupement pensé pour réduire la charge cognitive :

- **Overview** : Dashboard (épinglé)
- **Explore** : SQL Editor · Compare · Stream Flow
- **Observe** : Metrics · Audit · Cluster
- **Analyze** : Lineage · Process Mining
- **Utility** (bas de sidebar) : Settings · Help

---

## 4. Charte graphique

### 4.1 Couleurs (tokens)

Thème sombre, neutres légèrement froids, accent **indigo** (marque) + **violet**
(secondaire). Définis dans `tailwind.config.js`, consommés comme
`bg-surface-container`, `text-on-surface-variant`, `text-primary`, etc.

**Neutres / surfaces**

| Token | Hex | Usage |
|-------|-----|-------|
| `background` / `surface` | `#0b0d10` | Fond de l'application |
| `surface-container-low` | `#0e1114` | Sidebar, champs |
| `surface-container` | `#12151a` | Cartes, panneaux |
| `surface-container-high` | `#191d24` | Survols, en-têtes de table, toasts |
| `surface-container-highest` | `#21262f` | Éléments actifs |
| `on-surface` | `#e8eaf0` | Texte principal |
| `on-surface-variant` | `#9aa3b2` | Texte secondaire |
| `outline` | `#79839a` | Texte tertiaire / icônes discrètes |
| `outline-variant` | `#2a303b` | Bordures, séparateurs |

**Accent & sémantique**

| Token | Hex | Usage |
|-------|-----|-------|
| `primary` | `#a3adff` | Accent de marque, liens, actions |
| `on-primary` | `#141833` | Texte sur `primary` |
| `secondary` | `#c9a9f7` | Accent secondaire (violet) |
| `success` | `#6ee7a0` | OK / healthy |
| `warning` | `#f5c264` | Avertissement / DLT |
| `error` | `#f58c8c` | Erreur / critique |

> Les tons pleins (`success`, `error`, …) sont **clairs** : les surfaces d'état
> utilisent l'opacité (`bg-success/10`, `border-success/30`, `text-success`),
> jamais du texte clair sur fond plein.

### 4.2 Typographie

- **Famille unique** : `Inter` (auto-hébergée). Hiérarchie par graisse/taille, pas par famille.
- **Mono** : `JetBrains Mono` (noms de topics, SQL, IDs).
- **Icônes** : `Material Symbols Outlined` (`wght 350`), auto-hébergées.
- Échelle : titre page `text-2xl/600`, titre section `15px/600`, corps `13px`, légende `12px`, overline `11px uppercase tracking-[0.05em]`.
- Chiffres clés en `tabular-nums` pour la stabilité.

### 4.3 Espacements, rayons, élévation

- **Grille 8 px** : paddings `p-4/5/6`, gaps `gap-2/3/4`, sections `space-y-6`.
- **Rayons** : `sm 6px` (chips) · `md 8px` (contrôles) · `lg 12px` (cartes) · `xl 16px`.
- **Bordures** : liseré 1 px `ring-white/[0.045]` (cartes) ou `border-outline-variant`.
- **Ombres** : discrètes, réservées au survol (`.card-hover`) et aux overlays. Pas de glow.
- **Animations** : 150–250 ms, `ease-out` ; neutralisées sous `prefers-reduced-motion`.

### 4.4 Compatibilité (remap des tokens hérités)

Les anciens tokens sont redéfinis vers les valeurs premium, ce qui reskin les
pages historiques **sans les réécrire** :

| Ancien | → | Nouveau |
|--------|---|---------|
| `primary` (cyan `#25f4f4`) | → | indigo `#a3adff` |
| `background-dark` (`#102222`) | → | `#0b0d10` |
| `neutral-dark` (`#1b2d2d`) | → | `surface-container` `#12151a` |
| `border-dark` (`#2d4444`) | → | `outline-variant` `#2a303b` |

---

## 5. Recommandations — perception de qualité

1. **Cohérence avant tout** : un seul kit, un seul jeu de tokens. La régularité (mêmes rayons, mêmes espacements, mêmes états) est le premier signal « premium ».
2. **Retenue chromatique** : un accent (indigo), un secondaire (violet), trois couleurs d'état. Le reste est neutre. Éviter le confetti de couleurs.
3. **Micro-interactions discrètes** : hover d'élévation, transitions 200 ms, focus rings nets — jamais de glow ni d'animation gratuite.
4. **États soignés** : chaque écran doit gérer chargement (skeleton), vide (guidage), erreur (récupération). C'est là que se perçoit le soin.
5. **Densité maîtrisée** : chiffres tabulaires, alignement à droite des nombres, tables scrollables plutôt que débordantes.
6. **Robustesse = qualité** : auto-héberger polices (fait) et Monaco (à faire) évite les régressions visuelles hors-ligne.
7. **Accessibilité = professionnalisme** : contraste AA, navigation clavier, `aria` — un produit accessible paraît plus abouti.

---

*Fondations livrées et validées (build vert, captures Dashboard/Cluster/mobile/404).
Les étapes « moyennes » et « majeures » ci-dessus constituent la suite recommandée.*
