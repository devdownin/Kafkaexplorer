# Topic hierarchy

Open **Explore → Topic hierarchy (or Dashboard → Topics → Topic hierarchy)** (or visit `/topics/hierarchy`) to browse topics by their names. This is a navigation aid: Kafka topics remain flat, and a shared prefix does **not** establish a data flow or parent-child relationship between topics. Use [Lineage](FEATURES.md#5-visual-query-lineage) or [Stream Flow](FEATURES.md#6-message-propagation-stream-flow) to investigate those relationships.

## Naming rules

Choose one separator in the **Naming separator** menu. The view builds a separate tree for each convention; it does not combine them.

| Name | Result |
|---|---|
| `orders.eu.created` | Dot tree: `orders` → `eu` → `created` |
| `orders-eu-created` | Hyphen tree: `orders` → `eu` → `created` |
| `orders_eu_created` | Underscore tree: `orders` → `eu` → `created` |
| `orders.eu-created` | Excluded: mixed separators |
| `orders..created` | Excluded: empty level |
| `orders` | Excluded: no separator |

Each eligible name has at least two nonempty levels and uses only one of `.`, `-`, `_`. The same cluster may contain topics using different conventions; select their separator to see each tree. A prefix can itself be a real topic: `orders.eu` remains clickable even when `orders.eu.created` exists below it. Other topics are never deleted or hidden from the Dashboard's ordinary list.

Select **Inspect excluded names** to see every name that cannot be placed in a tree, its reason and a link to that topic. The list initially shows 100 names; **Show next** reveals more.

## Explore the tree

- Enter part of a topic name in **Filter hierarchy**. Matching topics and their paths are shown; matching branches expand automatically. Clear the filter to return to the branches you had opened.
- Select a topic label to open its Topic Explorer. The separator, filter and manually opened branches are saved for the current browser tab, so returning to the tree restores that view. They are not shared across browsers or users.
- The tree renders a small window around the visible rows, even when thousands of topic names match. The count next to a branch is the number of topics under it, including a topic on the branch itself when one exists. With a filter, it counts matching topics only.

### Keyboard controls

Focus the tree with **Tab**, then use:

| Key | Action |
|---|---|
| ↑ / ↓ | Previous / next visible node |
| → | Expand a closed branch; on an open branch, move to its first child |
| ← | Collapse an open branch; otherwise move to its parent |
| Home / End | First / last visible node |
| Enter | Open the topic if the node is a topic; otherwise toggle its branch |
| Space | Toggle a branch, or open a leaf topic |

While filtering, matching paths stay expanded, so branch controls cannot collapse them until the filter is cleared. A node that is both a topic and a branch opens the topic with **Enter** and expands or collapses with **Space**.
