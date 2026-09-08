// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.service;

import com.compagnonsdudev.kafkasqlexplorer.domain.DataModelColumn;
import com.compagnonsdudev.kafkasqlexplorer.domain.DataModelEntity;
import com.compagnonsdudev.kafkasqlexplorer.domain.DataModelRelation;
import com.compagnonsdudev.kafkasqlexplorer.domain.DataModelResponse;
import org.springframework.stereotype.Service;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * What a deduced data model can be turned into: a join query, and a Mermaid ER diagram.
 *
 * <p><b>This mirrors {@code buildMultiJoinSql} / {@code toMermaidEr} in
 * {@code src/main/webapp/src/pages/dataModelGraph.ts}, deliberately, on the same terms as
 * {@code ConsumerGroupLag.Health} and {@code components/topic/topicConsumers.ts}.</b> The page
 * recomputes the join preview as the selection changes, inside a {@code useMemo}: moving that
 * behind a round trip would turn a preview that keeps up with the pointer into one that lags it,
 * and the rule is deterministic graph-and-string work rather than a heuristic, so two readings of
 * it cannot disagree about a judgement call. {@code DataModelSqlServiceTest} runs the same cases
 * as {@code dataModelGraph.test.ts}, so a divergence fails one side or the other.
 *
 * <p>The one rule that matters in both: <b>it refuses rather than inventing a predicate.</b> A set
 * of entities the deduced relations do not connect has no join, and manufacturing one would assert
 * an equality nothing supports — which is the single thing the diagram avoids everywhere else.
 */
@Service
public class DataModelSqlService {

    /** Columns projected per table. Beyond this a preview stops being one. */
    private static final int JOIN_MAX_COLUMNS = 5;

    /**
     * A join query, or the reason there is none.
     *
     * @param sql      the query, {@code null} when {@code problem} says why there is none
     * @param caveats  what the query assumes and the operator has to check before running it
     * @param problem  why no query could be written, {@code null} when one was
     */
    public record JoinSql(String sql, List<String> caveats, String problem) {

        public JoinSql {
            caveats = caveats == null ? List.of() : List.copyOf(caveats);
        }

        static JoinSql refused(String problem) {
            return new JoinSql(null, List.of(), problem);
        }
    }

    /**
     * The query that joins several entities along the relations deduced between them.
     *
     * <p>The spanning tree is built breadth-first from the first entity, so every table joined is
     * joined to a table already introduced — a {@code JOIN} whose predicate cites a table not yet
     * in the query does not parse.
     */
    public JoinSql buildJoin(List<String> entityIds, DataModelResponse model) {
        Map<String, DataModelEntity> byId = new LinkedHashMap<>();
        model.entities().forEach(entity -> byId.put(entity.id(), entity));

        List<String> unknown = entityIds.stream().filter(id -> !byId.containsKey(id)).toList();
        if (!unknown.isEmpty()) {
            return JoinSql.refused("This model holds no entity called " + String.join(", ", unknown)
                    + ". Run the model over the topics you mean first — an entity id is the "
                    + "registered table name, which is the topic's name.");
        }

        List<DataModelEntity> selected = entityIds.stream().distinct().map(byId::get).toList();
        if (selected.size() < 2) {
            return JoinSql.refused("Pick at least two entities to join.");
        }

        Set<String> inSet = new LinkedHashSet<>(selected.stream().map(DataModelEntity::id).toList());
        Map<String, DataModelEntity> selectedById = new LinkedHashMap<>();
        selected.forEach(entity -> selectedById.put(entity.id(), entity));

        List<DataModelRelation> usable = model.relations().stream()
                .filter(r -> inSet.contains(r.from()) && inSet.contains(r.to()))
                .filter(r -> predicateOf(r, selectedById) != null)
                .toList();

        Map<String, List<DataModelRelation>> adjacency = new LinkedHashMap<>();
        for (DataModelRelation relation : usable) {
            adjacency.computeIfAbsent(relation.from(), k -> new ArrayList<>()).add(relation);
            adjacency.computeIfAbsent(relation.to(), k -> new ArrayList<>()).add(relation);
        }

        DataModelEntity root = selected.get(0);
        Set<String> joined = new LinkedHashSet<>();
        joined.add(root.id());
        List<Step> order = new ArrayList<>();
        Deque<String> queue = new ArrayDeque<>();
        queue.add(root.id());
        while (!queue.isEmpty()) {
            String current = queue.poll();
            for (DataModelRelation relation : adjacency.getOrDefault(current, List.of())) {
                String nextId = relation.from().equals(current) ? relation.to() : relation.from();
                if (!joined.add(nextId)) {
                    continue;
                }
                order.add(new Step(selectedById.get(nextId), relation));
                queue.add(nextId);
            }
        }

        List<String> unreached = selected.stream().map(DataModelEntity::id)
                .filter(id -> !joined.contains(id)).toList();
        if (!unreached.isEmpty()) {
            return JoinSql.refused("No deduced relation connects " + String.join(", ", unreached)
                    + " to the rest of the selection — a join needs a predicate for every table, "
                    + "and inventing one would assert an equality nothing supports.");
        }

        List<DataModelEntity> inOrder = new ArrayList<>();
        inOrder.add(root);
        order.forEach(step -> inOrder.add(step.entity()));

        List<String> aliasList = joinAliasesFor(inOrder.stream().map(DataModelEntity::topic).toList());
        Map<String, String> aliasOf = new LinkedHashMap<>();
        for (int i = 0; i < inOrder.size(); i++) {
            aliasOf.put(inOrder.get(i).id(), aliasList.get(i));
        }

        Set<String> caveats = new LinkedHashSet<>();
        Map<String, Set<String>> joinColumns = new LinkedHashMap<>();
        for (Step step : order) {
            String target = predicateOf(step.relation(), selectedById);
            joinColumns.computeIfAbsent(step.relation().from(), k -> new LinkedHashSet<>())
                    .add(step.relation().fromColumn());
            joinColumns.computeIfAbsent(step.relation().to(), k -> new LinkedHashSet<>()).add(target);
            if (step.relation().fromColumn().contains(".") || target.contains(".")) {
                caveats.add("A join column is a nested path — check how it resolves before running.");
            }
        }

        List<String> columns = new ArrayList<>();
        for (DataModelEntity entity : inOrder) {
            columns.addAll(project(entity, aliasOf.get(entity.id()),
                    joinColumns.getOrDefault(entity.id(), Set.of()), caveats));
        }

        List<String> lines = new ArrayList<>();
        String grades = order.stream().map(step -> step.relation().confidence().name().toLowerCase(Locale.ROOT))
                .distinct().reduce((a, b) -> a + ", " + b).orElse("");
        lines.add("-- %d entities joined along %d deduced relation(s) (%s)."
                .formatted(selected.size(), order.size(), grades));
        order.forEach(step -> lines.add("-- " + step.relation().reason()));
        caveats.forEach(caveat -> lines.add("-- " + caveat));
        lines.add("-- Regular join: Flink keeps every side in state — fine here, not on large topics.");
        lines.add("SELECT");
        lines.add(String.join(",\n", columns));
        lines.add("FROM %s AS %s".formatted(root.id(), aliasOf.get(root.id())));
        for (Step step : order) {
            String target = predicateOf(step.relation(), selectedById);
            lines.add("JOIN %s AS %s\n  ON %s.%s = %s.%s".formatted(
                    step.entity().id(), aliasOf.get(step.entity().id()),
                    aliasOf.get(step.relation().from()), step.relation().fromColumn(),
                    aliasOf.get(step.relation().to()), target));
        }
        lines.add("LIMIT 50");

        return new JoinSql(String.join("\n", lines), List.copyOf(caveats), null);
    }

    /**
     * The model as a Mermaid {@code erDiagram} — the text form of the same picture.
     *
     * <p>It exists for what an image cannot do: be read back and diffed. A PNG pasted into a review
     * does not say what changed since the last one; these lines do.
     */
    public String toMermaidEr(DataModelResponse model, List<String> caption) {
        List<String> lines = new ArrayList<>();
        caption.forEach(line -> lines.add("%% " + line));
        lines.add("erDiagram");

        Set<String> drawn = new LinkedHashSet<>(model.entities().stream()
                .map(DataModelEntity::id).toList());
        for (DataModelRelation relation : model.relations()) {
            if (!drawn.contains(relation.from()) || !drawn.contains(relation.to())) {
                continue;
            }
            String label = "%s (%s)".formatted(relation.fromColumn(),
                    relation.confidence().name().toLowerCase(Locale.ROOT));
            lines.add("    %s ||--o{ %s : \"%s\"".formatted(
                    mermaidId(relation.to()), mermaidId(relation.from()), mermaidLabel(label)));
        }

        for (DataModelEntity entity : model.entities()) {
            lines.add("    %s {".formatted(mermaidId(entity.id())));
            for (DataModelColumn column : entity.columns()) {
                String key = column.primaryKey() ? " PK" : column.references() != null ? " FK" : "";
                // The original name as a comment: sanitising must not cost information.
                String comment = mermaidId(column.name()).equals(column.name())
                        ? "" : " \"%s\"".formatted(mermaidLabel(column.name()));
                lines.add("        %s %s%s%s".formatted(
                        mermaidId(column.type()), mermaidId(column.name()), key, comment));
            }
            lines.add("    }");
        }

        return String.join("\n", lines) + "\n";
    }

    /**
     * Short, distinct and <em>valid</em> aliases for N topics.
     *
     * <p>First the distinctive segment {@link #topicDomains} isolates, then the tail, then a
     * numbered suffix. An alias starting with a digit is not a SQL identifier and the query would
     * die at the parser — which is what siblings like {@code demo.orders.1.received} and
     * {@code demo.orders.2.validated} produce when only the distinctive segment is considered.
     */
    public static List<String> joinAliasesFor(List<String> topics) {
        Map<String, String> domains = topicDomains(topics);

        List<String> byDomain = topics.stream()
                .map(topic -> sqlAlias(domains.getOrDefault(topic, ""))).toList();
        if (allDistinctAndUsable(byDomain)) {
            return byDomain;
        }
        List<String> byTail = topics.stream().map(DataModelSqlService::tailAlias).toList();
        if (allDistinctAndUsable(byTail)) {
            return byTail;
        }

        // Nothing distinct: keep the best available name and number the homonyms.
        List<String> bases = new ArrayList<>();
        for (int i = 0; i < topics.size(); i++) {
            String tail = byTail.get(i);
            String domain = byDomain.get(i);
            bases.add(!tail.isEmpty() ? tail : !domain.isEmpty() ? domain : "a");
        }
        Map<String, Integer> totals = new LinkedHashMap<>();
        bases.forEach(base -> totals.merge(base, 1, Integer::sum));
        Map<String, Integer> seen = new LinkedHashMap<>();
        return bases.stream().map(base -> {
            if (totals.get(base) == 1) {
                return base;
            }
            int n = seen.merge(base, 1, Integer::sum);
            return base + "_" + n;
        }).toList();
    }

    /**
     * Topic → the segment that actually distinguishes it, shared leading segments dropped.
     *
     * <p>{@code demo.payments.authorized} and {@code demo.orders.1.received} give {@code payments}
     * and {@code orders}, which is what reads best in an {@code ON}.
     */
    public static Map<String, String> topicDomains(List<String> topics) {
        Map<String, List<String>> segments = new LinkedHashMap<>();
        topics.forEach(topic -> segments.put(topic, split(topic)));

        int dropped = 0;
        while (true) {
            Set<String> heads = new LinkedHashSet<>();
            boolean allDeep = !topics.isEmpty();
            for (List<String> parts : segments.values()) {
                if (parts.size() - dropped < 2) {
                    allDeep = false;
                    break;
                }
                heads.add(parts.get(dropped).toLowerCase(Locale.ROOT));
            }
            if (!allDeep || heads.size() != 1) {
                break;
            }
            dropped += 1;
        }

        Map<String, String> domains = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> entry : segments.entrySet()) {
            List<String> parts = entry.getValue();
            String pick = dropped < parts.size() ? parts.get(dropped)
                    : parts.isEmpty() ? entry.getKey() : parts.get(0);
            domains.put(entry.getKey(), pick.toLowerCase(Locale.ROOT));
        }
        return domains;
    }

    private record Step(DataModelEntity entity, DataModelRelation relation) {}

    /** The column a relation names on its target, the target's detected key, or {@code null}. */
    private static String predicateOf(DataModelRelation relation, Map<String, DataModelEntity> byId) {
        DataModelEntity to = byId.get(relation.to());
        if (to == null) {
            return null;
        }
        if (relation.toColumn() != null) {
            return relation.toColumn();
        }
        return to.primaryKey();
    }

    /**
     * Never {@code SELECT *}: both sides carry the join column under the same name, so the star
     * produces two homonymous columns. Nested paths are left out with a caveat that says so — a
     * dotted path behind a table alias does not mean in Flink SQL what it means on the diagram.
     */
    private static List<String> project(DataModelEntity entity, String alias,
                                        Set<String> joinColumns, Set<String> caveats) {
        List<DataModelColumn> flat = entity.columns().stream()
                .filter(column -> !column.name().contains(".")).toList();
        if (flat.size() < entity.columns().size()) {
            caveats.add("%d nested field(s) of %s are left out of the projection — a nested path "
                    .formatted(entity.columns().size() - flat.size(), entity.id())
                    + "behind a table alias does not mean the same thing in Flink SQL as it does "
                    + "on the diagram.");
        }
        List<DataModelColumn> ordered = new ArrayList<>(
                flat.stream().filter(c -> joinColumns.contains(c.name()) || c.primaryKey()).toList());
        ordered.addAll(flat.stream()
                .filter(c -> !joinColumns.contains(c.name()) && !c.primaryKey()).toList());
        return ordered.stream().limit(JOIN_MAX_COLUMNS)
                .map(column -> "    %s.%s".formatted(alias, column.name())).toList();
    }

    private static boolean allDistinctAndUsable(List<String> names) {
        return names.stream().noneMatch(String::isEmpty)
                && new LinkedHashSet<>(names).size() == names.size();
    }

    private static String tailAlias(String topic) {
        return sqlAlias(split(topic).stream().filter(part -> !part.matches("\\d+"))
                .reduce((first, second) -> second).orElse(""));
    }

    private static List<String> split(String topic) {
        return java.util.Arrays.stream(topic.split("[._-]+")).filter(part -> !part.isEmpty()).toList();
    }

    /** An unquoted SQL identifier, or the empty string when nothing usable is left. */
    private static String sqlAlias(String value) {
        String cleaned = value.replaceAll("[^A-Za-z0-9_]", "_");
        return cleaned.matches("^[A-Za-z_].*") ? cleaned : "";
    }

    private static String mermaidId(String value) {
        String cleaned = value.replaceAll("[^A-Za-z0-9_]", "_");
        return cleaned.isEmpty() ? "_" : cleaned;
    }

    /** A quoted label: an inner quote would break the line. */
    private static String mermaidLabel(String value) {
        return value.replace("\"", "'");
    }
}
