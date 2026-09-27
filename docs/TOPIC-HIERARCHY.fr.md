# Hiérarchie des topics

Ouvrez **Dashboard → Topics → Topic hierarchy** (ou `/topics/hierarchy`) pour parcourir les topics selon leur nom. Il s'agit d'une aide à la navigation : les topics Kafka restent plats et un préfixe commun ne prouve aucun lien de parenté ni de circulation des données. Utilisez Lineage ou Stream Flow pour examiner ces relations.

## Règles de nommage

Choisissez un séparateur dans **Naming separator**. Chaque convention produit son propre arbre ; les conventions ne sont pas mélangées.

| Nom | Résultat |
|---|---|
| `orders.eu.created` | Arbre « point » : `orders` → `eu` → `created` |
| `orders-eu-created` | Arbre « tiret » : `orders` → `eu` → `created` |
| `orders_eu_created` | Arbre « souligné » : `orders` → `eu` → `created` |
| `orders.eu-created` | Exclu : séparateurs mélangés |
| `orders..created` | Exclu : niveau vide |
| `orders` | Exclu : aucun séparateur |

Un nom admissible contient au moins deux niveaux non vides et un seul type de séparateur parmi `.`, `-` et `_`. Un même cluster peut employer plusieurs conventions : choisissez le séparateur correspondant pour afficher chaque arbre. Un préfixe peut être un vrai topic : `orders.eu` reste accessible même si `orders.eu.created` figure sous lui. Les autres topics restent présents dans la liste ordinaire du Dashboard.

Cliquez sur **Inspect excluded names** pour afficher chaque nom non classable, son motif et un lien vers le topic. Les 100 premiers noms apparaissent d'abord ; **Show next** affiche la suite.

## Parcourir l'arbre

- Saisissez une partie du nom dans **Filter hierarchy**. Les topics correspondants et leurs chemins apparaissent ; les branches utiles s'ouvrent automatiquement. Effacez le filtre pour retrouver les branches que vous aviez ouvertes.
- Cliquez sur un topic pour ouvrir Topic Explorer. Le séparateur, le filtre et les branches ouvertes manuellement sont conservés dans l'onglet du navigateur : au retour dans l'arbre, la vue est restaurée. Ces préférences ne sont pas partagées entre navigateurs ou utilisateurs.
- Seule une petite fenêtre autour des lignes visibles est rendue, même si des milliers de topics correspondent. Le compteur d'une branche inclut les topics sous celle-ci, y compris le topic portant le nom de la branche s'il existe. Avec un filtre, il ne compte que les topics correspondants.

### Au clavier

Accédez à l'arbre avec **Tab**, puis utilisez :

| Touche | Action |
|---|---|
| ↑ / ↓ | Nœud visible précédent / suivant |
| → | Ouvrir une branche fermée ; si elle est ouverte, aller à son premier enfant |
| ← | Fermer une branche ouverte ; sinon, aller à son parent |
| Home / End | Premier / dernier nœud visible |
| Entrée | Ouvrir le topic si le nœud en est un ; sinon, basculer sa branche |
| Espace | Basculer une branche ou ouvrir un topic feuille |

Pendant un filtrage, les chemins correspondants restent ouverts : effacez le filtre pour pouvoir replier leurs branches. Lorsqu'un nœud est à la fois un topic et une branche, **Entrée** ouvre le topic et **Espace** déplie ou replie la branche.
