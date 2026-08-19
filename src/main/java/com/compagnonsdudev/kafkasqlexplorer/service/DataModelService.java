// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.service;

import com.compagnonsdudev.kafkasqlexplorer.domain.DataModelColumn;
import com.compagnonsdudev.kafkasqlexplorer.domain.DataModelEntity;
import com.compagnonsdudev.kafkasqlexplorer.domain.DataModelRelation;
import com.compagnonsdudev.kafkasqlexplorer.domain.DataModelResponse;
import com.compagnonsdudev.kafkasqlexplorer.domain.MessageFormat;
import com.compagnonsdudev.kafkasqlexplorer.config.ExplorerConfig;
import com.compagnonsdudev.kafkasqlexplorer.domain.RelationConfidence;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Builds a data model — entities and deduced relations — from a selection of Kafka topics.
 *
 * <p>Each topic becomes an entity: its schema is inferred from a bounded sample (the same
 * shared-sample discipline as the audit: one fetch feeds format detection and inference), its
 * table name is the one Flink SQL would use, and its key column is <em>detected, never
 * invented</em> — an entity whose payloads carry nothing id-like has {@code primaryKey = null}.
 *
 * <p>Relations are deduced, and every deduction states its evidence. Kafka has no foreign
 * keys, so the only signals available are lexical:
 * <ul>
 *   <li>a column whose name reads as a reference ({@code order_id}, {@code customerId},
 *       {@code product_ref}) pointing at an entity whose topic name matches the base word —
 *       {@link RelationConfidence#HIGH} when the target's own key column agrees with the
 *       referencing column, {@link RelationConfidence#MEDIUM} when only the name matched;</li>
 *   <li>a column that is another entity's detected key, carried verbatim —
 *       {@link RelationConfidence#LOW}, because a shared column name alone is the weakest of
 *       the three signals.</li>
 * </ul>
 * Generic correlation fields ({@code correlation_id}, {@code trace_id}) fall out naturally:
 * they only produce an edge if an entity actually owns that name, which is the same rule as
 * everything else rather than a hand-kept blacklist.
 *
 * <p>Bounded on both axes: at most {@link #DEFAULT_MAX_TOPICS} topics per request unless it asks
 * for more, capped by {@code explorer.data-model-max-topics} (the rest are
 * named in a warning, never silently dropped) and a wall-clock budget per topic read. A topic
 * whose read fails costs that topic — reported with its reason — not the model.
 */
@Service
public class DataModelService {

    private static final Logger log = LoggerFactory.getLogger(DataModelService.class);

    /**
     * What one request analyses when it does not say. The ceiling that bounds a request which
     * *does* say lives in {@code explorer.data-model-max-topics}: this is a sensible default for
     * a first look, that one is what stops a mistyped number tying up the pool for hours.
     */
    public static final int DEFAULT_MAX_TOPICS = 30;

    /** Per-topic wall clock. Schema inference on an unreachable partition must not pin the request. */
    private static final long PER_TOPIC_TIMEOUT_MS = 20_000;

    private static final int THREAD_POOL_SIZE = 4;

    /**
     * A field that reads as an identifier. Two shapes, matched separately so that words which
     * merely <em>end</em> in "id" ({@code paid}, {@code valid}) are not read as references:
     * snake_case requires a separator before the suffix ({@code order_id}, {@code product-ref},
     * or a bare {@code id}), camelCase requires the suffix's capital ({@code orderId}).
     */
    private static final Pattern SNAKE_ID = Pattern.compile(
            "(?i)^(?:(.*?)[_-])?(id|uuid|key|ref|reference|code)$");
    private static final Pattern CAMEL_ID = Pattern.compile(
            "^(.*?)(Id|Uuid|Key|Ref|Reference|Code)$");

    private final KafkaAdminService kafkaAdminService;
    private final SchemaInferenceService schemaInferenceService;
    private final ExplorerConfig explorerConfig;
    private final ExecutorService executorService;

    public DataModelService(KafkaAdminService kafkaAdminService,
                            SchemaInferenceService schemaInferenceService,
                            ExplorerConfig explorerConfig) {
        this.kafkaAdminService = kafkaAdminService;
        this.schemaInferenceService = schemaInferenceService;
        this.explorerConfig = explorerConfig;
        AtomicInteger counter = new AtomicInteger();
        ThreadFactory factory = runnable -> {
            Thread thread = new Thread(runnable, "data-model-" + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
        this.executorService = Executors.newFixedThreadPool(THREAD_POOL_SIZE, factory);
    }

    @PreDestroy
    public void shutdown() {
        ShutdownBudget.shutdown(executorService);
    }

    public DataModelResponse buildModel(List<String> requestedTopics) {
        return buildModel(requestedTopics, null);
    }

    /**
     * @param requestedMaxTopics how many topics this run may analyse, or {@code null} for
     *                           {@link #DEFAULT_MAX_TOPICS}. Clamped to at least 1 and to
     *                           {@code explorer.data-model-max-topics}: the caller chooses within
     *                           a bound it does not get to raise, and what the clamp refused is
     *                           said in the warnings rather than applied in silence.
     */
    public DataModelResponse buildModel(List<String> requestedTopics, Integer requestedMaxTopics) {
        List<String> topics = requestedTopics.stream()
                .filter(t -> t != null && !t.isBlank())
                .map(String::trim)
                .distinct()
                .toList();
        if (topics.isEmpty()) {
            throw new IllegalArgumentException("At least one topic is required.");
        }

        List<String> warnings = new ArrayList<>();
        int ceiling = Math.max(1, explorerConfig.getDataModelMaxTopics());
        int asked = requestedMaxTopics == null ? DEFAULT_MAX_TOPICS : requestedMaxTopics;
        int maxTopics = Math.min(Math.max(1, asked), ceiling);
        if (asked > ceiling) {
            warnings.add("This run asked for " + asked + " topics; the server allows "
                    + ceiling + " (explorer.data-model-max-topics).");
        }

        boolean truncated = topics.size() > maxTopics;
        List<String> inScope = truncated ? topics.subList(0, maxTopics) : topics;
        if (truncated) {
            warnings.add("Only the first " + maxTopics + " of the " + topics.size()
                    + " selected topics were analyzed — narrow the selection to cover the rest.");
        }

        // One batched size read for every topic; a failure costs the counts, not the model.
        Map<String, Long> sizes;
        try {
            sizes = kafkaAdminService.getTopicsSize(inScope);
        } catch (Exception e) {
            sizes = Map.of();
            warnings.add("Message counts could not be read: " + reasonOf(e));
        }

        // Inference fans out on the bounded pool; results are collected back in request order
        // so the response is deterministic whatever the completion order.
        Map<String, Future<DataModelEntity>> futures = new LinkedHashMap<>();
        Map<String, Long> finalSizes = sizes;
        for (String topic : inScope) {
            futures.put(topic, executorService.submit(() -> inferEntity(topic, finalSizes.get(topic))));
        }

        List<DataModelEntity> entities = new ArrayList<>();
        for (Map.Entry<String, Future<DataModelEntity>> entry : futures.entrySet()) {
            String topic = entry.getKey();
            try {
                DataModelEntity entity = entry.getValue().get(PER_TOPIC_TIMEOUT_MS, TimeUnit.MILLISECONDS);
                if (entity.columns().isEmpty()) {
                    // A topic with no inferable schema is a legitimate answer, but an empty
                    // node on an ER diagram reads as an empty table — say why it is absent.
                    warnings.add("Topic '" + topic + "' yielded no schema (empty topic, or "
                            + "payloads that are neither JSON, XML nor Avro) — not shown.");
                } else {
                    entities.add(entity);
                }
            } catch (TimeoutException e) {
                entry.getValue().cancel(true);
                warnings.add("Topic '" + topic + "' was not read within "
                        + (PER_TOPIC_TIMEOUT_MS / 1000) + "s — not shown.");
            } catch (ExecutionException e) {
                warnings.add("Topic '" + topic + "' could not be read: "
                        + reasonOf(e.getCause() != null ? e.getCause() : e) + " — not shown.");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                warnings.add("Model build interrupted — topics past '" + topic + "' were not read.");
                break;
            }
        }

        List<DataModelRelation> relations = deduceRelations(entities);
        entities = markReferences(entities, relations);

        return new DataModelResponse(entities, relations, List.copyOf(warnings),
                topics.size(), entities.size(), truncated);
    }

    private DataModelEntity inferEntity(String topic, Long messageCount) {
        List<String> samples = schemaInferenceService.getSampleMessages(topic);
        MessageFormat format = schemaInferenceService.detectFormat(topic, samples);
        Map<String, String> schema = schemaInferenceService.inferSchema(topic, format, samples);

        String primaryKey = detectPrimaryKey(topic, schema.keySet());
        List<String> ownTokens = topicTokens(topic);
        List<DataModelColumn> columns = schema.entrySet().stream()
                .map(e -> new DataModelColumn(e.getKey(), e.getValue(),
                        e.getKey().equals(primaryKey), null,
                        referencingBase(e.getKey(), ownTokens)))
                .toList();

        return new DataModelEntity(DdlGeneratorService.toTableName(topic), topic, format,
                columns, primaryKey, messageCount);
    }

    // ── Relation inference (static and pure, which is what makes it testable) ─────────────

    /**
     * The words a topic name is made of, lowercased, numeric segments dropped:
     * {@code demo.orders.3.enriched} → {@code [demo, orders, enriched]}.
     */
    static List<String> topicTokens(String topic) {
        List<String> tokens = new ArrayList<>();
        for (String segment : topic.toLowerCase(Locale.ROOT).split("[._\\-]+")) {
            if (!segment.isEmpty() && !segment.chars().allMatch(Character::isDigit)) {
                tokens.add(segment);
            }
        }
        return tokens;
    }

    /** Whether an entity token names the given base word — exact, or as its plain plural. */
    static boolean tokenMatches(String token, String base) {
        if (token.equals(base)) return true;
        // orders ↔ order, addresses ↔ address. English-plural only, on purpose: a smarter
        // stemmer would start matching words that merely resemble each other.
        return token.equals(base + "s") || token.equals(base + "es")
                || (token.endsWith("ies") && base.endsWith("y")
                    && token.regionMatches(0, base, 0, base.length() - 1));
    }

    /**
     * The base word of an id-like field name, or {@code null} when the field does not read as
     * an identifier. {@code order_id} → {@code order}; plain {@code id} → {@code ""} (an
     * identifier of the entity itself); {@code amount} → {@code null}. Only the last path
     * segment is considered, so {@code customer.id} reads as an id owned by {@code customer}.
     */
    static String idBaseOf(String fieldPath) {
        String leaf = fieldPath.substring(fieldPath.lastIndexOf('.') + 1);
        Matcher m = SNAKE_ID.matcher(leaf);
        if (!m.matches()) {
            m = CAMEL_ID.matcher(leaf);
            if (!m.matches()) return null;
        }
        String base = m.group(1);
        if (base == null || base.isEmpty()) return "";
        // The FK convention is <entity><suffix>; in a camelCase compound the entity word is
        // the one just before the suffix, so keep the last hump ("customerAccountId" → account).
        // Split on a lower→upper boundary only, or an ALL_CAPS base would shatter per letter.
        String[] humps = base.split("(?<=\\p{Lower})(?=\\p{Upper})");
        return humps[humps.length - 1].toLowerCase(Locale.ROOT);
    }

    /**
     * The entity word a field name <em>points at</em>, or {@code null} when it points at nobody:
     * either it does not read as an identifier ({@code amount}), or it identifies the entity
     * that carries it — a bare {@code id}, or a name echoing the topic's own words
     * ({@code order_id} on {@code demo.orders.received}), which is identity, not reference.
     *
     * <p>The single rule behind two things that must never disagree: which columns
     * {@link #deduceRelations} looks for a target from, and which ones the UI may mark as
     * reading like a foreign key. Both call this; neither restates it.
     */
    static String referencingBase(String fieldPath, List<String> ownTokens) {
        String base = idBaseOf(fieldPath);
        if (base == null || base.isEmpty()) return null;
        return ownTokens.stream().anyMatch(t -> tokenMatches(t, base)) ? null : base;
    }

    /**
     * The column that identifies this entity, or {@code null}. A field whose base word matches
     * the topic's own name ({@code order_id} on topic {@code orders}) wins over a bare
     * {@code id}: the former is a deliberate name, the latter a convention. Top-level fields
     * win over nested ones — a nested {@code customer.id} identifies the customer, not this
     * entity.
     */
    static String detectPrimaryKey(String topic, Set<String> fields) {
        List<String> tokens = topicTokens(topic);
        String named = null, bare = null;
        for (String field : fields) {
            String base = idBaseOf(field);
            if (base == null) continue;
            boolean topLevel = field.indexOf('.') < 0;
            if (base.isEmpty()) {
                if (topLevel && bare == null) bare = field;
            } else if (tokens.stream().anyMatch(t -> tokenMatches(t, base))) {
                if (topLevel && named == null) named = field;
            }
        }
        return named != null ? named : bare;
    }

    /**
     * Deduces the relations of the model. Deterministic: entities are examined in list order,
     * fields in schema order, and the result is sorted — the same selection must always draw
     * the same graph.
     */
    static List<DataModelRelation> deduceRelations(List<DataModelEntity> entities) {
        List<DataModelRelation> relations = new ArrayList<>();
        Set<String> seen = new HashSet<>();

        for (DataModelEntity from : entities) {
            List<String> ownTokens = topicTokens(from.topic());
            for (DataModelColumn column : from.columns()) {
                String base = referencingBase(column.name(), ownTokens);
                if (base == null) continue;

                for (DataModelEntity to : entities) {
                    if (to == from) continue;
                    if (topicTokens(to.topic()).stream().noneMatch(t -> tokenMatches(t, base))) continue;

                    String dedupeKey = from.id() + ' ' + to.id() + ' ' + column.name();
                    if (!seen.add(dedupeKey)) continue;

                    String toColumn = resolveTargetColumn(to, column.name());
                    boolean keyAgrees = toColumn != null;
                    relations.add(new DataModelRelation(
                            from.id(), to.id(), column.name(), toColumn,
                            keyAgrees ? RelationConfidence.HIGH : RelationConfidence.MEDIUM,
                            keyAgrees
                                ? "'" + column.name() + "' names topic '" + to.topic()
                                    + "' and matches its key column '" + toColumn + "'."
                                : "'" + column.name() + "' names topic '" + to.topic()
                                    + "', which has no matching key column."));
                }
            }
        }

        // Second pass, weakest signal: a column that is verbatim another entity's detected
        // key, without the name of that entity anywhere in it (e.g. a denormalized copy).
        for (DataModelEntity from : entities) {
            Set<String> fromColumns = new LinkedHashSet<>();
            from.columns().forEach(c -> fromColumns.add(c.name()));
            for (DataModelEntity to : entities) {
                if (to == from || to.primaryKey() == null) continue;
                String pk = to.primaryKey();
                // A bare "id" is shared by construction, not by reference.
                String pkBase = idBaseOf(pk);
                if (pkBase == null || pkBase.isEmpty()) continue;
                if (!fromColumns.contains(pk)) continue;
                if (from.primaryKey() != null && from.primaryKey().equals(pk)) continue;
                String dedupeKey = from.id() + ' ' + to.id() + ' ' + pk;
                if (!seen.add(dedupeKey)) continue;
                relations.add(new DataModelRelation(from.id(), to.id(), pk, pk,
                        RelationConfidence.LOW,
                        "Both carry '" + pk + "', the key column of topic '" + to.topic() + "'."));
            }
        }

        relations.sort(Comparator.comparing(DataModelRelation::from)
                .thenComparing(DataModelRelation::to)
                .thenComparing(DataModelRelation::fromColumn));
        return relations;
    }

    /**
     * The column of {@code to} a reference resolves onto: its detected key when the names
     * agree (identical leaf, or the key is a bare {@code id}), otherwise the referencing
     * column carried verbatim, otherwise {@code null} — never a guessed name.
     */
    private static String resolveTargetColumn(DataModelEntity to, String fromColumn) {
        String fromLeaf = fromColumn.substring(fromColumn.lastIndexOf('.') + 1);
        String pk = to.primaryKey();
        if (pk != null) {
            String pkBase = idBaseOf(pk);
            if (pk.equalsIgnoreCase(fromLeaf) || (pkBase != null && pkBase.isEmpty())) return pk;
        }
        for (DataModelColumn column : to.columns()) {
            if (column.name().equalsIgnoreCase(fromLeaf)) return column.name();
        }
        return null;
    }

    /** Rewrites the columns so each referencing one carries the id of the entity it points at. */
    private static List<DataModelEntity> markReferences(List<DataModelEntity> entities,
                                                        List<DataModelRelation> relations) {
        if (relations.isEmpty()) return entities;
        Map<String, String> refByColumn = new LinkedHashMap<>();
        for (DataModelRelation relation : relations) {
            // First relation wins for a column that references several entities; the full list
            // stays available in the relations themselves.
            refByColumn.putIfAbsent(relation.from() + ' ' + relation.fromColumn(), relation.to());
        }
        List<DataModelEntity> marked = new ArrayList<>(entities.size());
        for (DataModelEntity entity : entities) {
            List<DataModelColumn> columns = entity.columns().stream()
                    .map(c -> {
                        String ref = refByColumn.get(entity.id() + ' ' + c.name());
                        return ref == null ? c
                                : new DataModelColumn(c.name(), c.type(), c.primaryKey(), ref,
                                        c.keyBase());
                    })
                    .toList();
            marked.add(new DataModelEntity(entity.id(), entity.topic(), entity.format(),
                    columns, entity.primaryKey(), entity.messageCount()));
        }
        return marked;
    }

    private static String reasonOf(Throwable t) {
        String message = t.getMessage();
        if (message == null || message.isBlank()) return t.getClass().getSimpleName();
        return message;
    }
}
