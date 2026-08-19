// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.service;

import com.compagnonsdudev.kafkasqlexplorer.config.ExplorerConfig;
import com.compagnonsdudev.kafkasqlexplorer.domain.DataModelColumn;
import com.compagnonsdudev.kafkasqlexplorer.domain.DataModelEntity;
import com.compagnonsdudev.kafkasqlexplorer.domain.DataModelRelation;
import com.compagnonsdudev.kafkasqlexplorer.domain.DataModelResponse;
import com.compagnonsdudev.kafkasqlexplorer.domain.MessageFormat;
import com.compagnonsdudev.kafkasqlexplorer.domain.RelationConfidence;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The relation-inference rules, pinned one by one: they are lexical heuristics, and a
 * heuristic without a test drifts into matching things nobody intended (the first draft read
 * {@code paid} as a reference to an entity named "pa").
 */
class DataModelServiceTest {

    private KafkaAdminService kafkaAdminService;
    private SchemaInferenceService schemaInferenceService;
    private final ExplorerConfig explorerConfig = new ExplorerConfig();
    private DataModelService service;

    @BeforeEach
    void setUp() {
        kafkaAdminService = mock(KafkaAdminService.class);
        schemaInferenceService = mock(SchemaInferenceService.class);
        service = new DataModelService(kafkaAdminService, schemaInferenceService, explorerConfig);
    }

    @AfterEach
    void tearDown() {
        service.shutdown();
    }

    // ── idBaseOf: what reads as an identifier ─────────────────────────────────────────────

    @Test
    void snakeCaseAndCamelCaseIdentifiersAreRecognised() {
        assertEquals("order", DataModelService.idBaseOf("order_id"));
        assertEquals("order", DataModelService.idBaseOf("orderId"));
        assertEquals("product", DataModelService.idBaseOf("product-ref"));
        assertEquals("customer", DataModelService.idBaseOf("CUSTOMER_ID"));
        assertEquals("", DataModelService.idBaseOf("id"));
        assertEquals("", DataModelService.idBaseOf("uuid"));
    }

    @Test
    void wordsMerelyEndingInIdAreNotIdentifiers() {
        assertNull(DataModelService.idBaseOf("paid"));
        assertNull(DataModelService.idBaseOf("valid"));
        assertNull(DataModelService.idBaseOf("amount"));
    }

    @Test
    void nestedPathsAreJudgedByTheirLeaf() {
        assertEquals("order", DataModelService.idBaseOf("payload.order_id"));
    }

    @Test
    void camelCompoundsKeepTheWordNextToTheSuffix() {
        assertEquals("account", DataModelService.idBaseOf("customerAccountId"));
    }

    // ── referencingBase: what a name points at, as opposed to what it merely reads like ───

    @Test
    void aNameEchoingItsOwnTopicIsIdentityNotReference() {
        // 'order_id' on an orders topic identifies this entity; it points at nobody else, which
        // is exactly why deduceRelations skips it.
        assertNull(DataModelService.referencingBase("order_id",
                DataModelService.topicTokens("demo.orders.1.received")));
    }

    @Test
    void aNameEchoingAnotherEntityPointsAtIt() {
        assertEquals("customer", DataModelService.referencingBase("customer_id",
                DataModelService.topicTokens("demo.orders.1.received")));
    }

    @Test
    void aBareIdPointsAtNobody() {
        assertNull(DataModelService.referencingBase("id",
                DataModelService.topicTokens("demo.orders")));
    }

    @Test
    void anOrdinaryColumnPointsAtNobody() {
        List<String> tokens = DataModelService.topicTokens("demo.invoices");
        assertNull(DataModelService.referencingBase("amount", tokens));
        assertNull(DataModelService.referencingBase("paid", tokens));
    }

    @Test
    void theColumnsCarryTheSameBaseTheDeductionUses() {
        // A column that points somewhere but found no target keeps its base, and that is what
        // lets the UI mark it: a non-null keyBase with a null references means no selected topic
        // carries the name — a target present in the model always yields a relation.
        DataModelEntity orders = entity("demo.orders.1.received",
                schema("order_id", "STRING", "customer_id", "STRING", "amount", "DOUBLE"));
        Map<String, DataModelColumn> byName = orders.columns().stream()
                .collect(java.util.stream.Collectors.toMap(DataModelColumn::name, c -> c));

        assertEquals("customer", byName.get("customer_id").keyBase());
        assertNull(byName.get("customer_id").references());
        assertNull(byName.get("order_id").keyBase());
        assertNull(byName.get("amount").keyBase());
    }

    @Test
    void aBaseThatPointsAtAnEntityInTheModelAlwaysYieldsARelation() {
        // The invariant the UI's wording rests on: when the target is in the model, a relation
        // comes out — so a column left with a base and no reference means the target is absent,
        // which is a fact rather than a guess.
        DataModelEntity payments = entity("demo.payments.authorized",
                schema("payment_id", "STRING", "order_id", "STRING"));
        DataModelEntity orders = entity("demo.orders.1.received",
                schema("order_id", "STRING"));

        DataModelColumn fk = payments.columns().stream()
                .filter(c -> c.name().equals("order_id")).findFirst().orElseThrow();
        assertEquals("order", fk.keyBase());

        List<DataModelRelation> relations =
                DataModelService.deduceRelations(List.of(payments, orders));
        assertTrue(relations.stream().anyMatch(r -> r.from().equals(payments.id())
                && r.fromColumn().equals("order_id") && r.to().equals(orders.id())));
    }

    // ── detectPrimaryKey ──────────────────────────────────────────────────────────────────

    @Test
    void aFieldNamedAfterTheTopicBeatsABareId() {
        String pk = DataModelService.detectPrimaryKey("demo.orders",
                Set.of("id", "order_id", "amount"));
        assertEquals("order_id", pk);
    }

    @Test
    void aBareIdIsTheFallback() {
        assertEquals("id", DataModelService.detectPrimaryKey("demo.orders", Set.of("id", "amount")));
    }

    @Test
    void noIdLikeFieldMeansNoPrimaryKeyNotAnInventedOne() {
        assertNull(DataModelService.detectPrimaryKey("demo.orders", Set.of("amount", "status")));
    }

    @Test
    void aNestedIdDoesNotIdentifyThisEntity() {
        // customer.id identifies the customer object, not the orders topic.
        assertNull(DataModelService.detectPrimaryKey("demo.orders", Set.of("customer.id", "amount")));
    }

    // ── deduceRelations ───────────────────────────────────────────────────────────────────

    private static DataModelEntity entity(String topic, Map<String, String> schema) {
        String pk = DataModelService.detectPrimaryKey(topic, schema.keySet());
        // Mirrors inferEntity, keyBase included: a fixture shaped unlike what the service emits
        // would test a response that never occurs.
        List<String> ownTokens = DataModelService.topicTokens(topic);
        List<DataModelColumn> columns = schema.entrySet().stream()
                .map(e -> new DataModelColumn(e.getKey(), e.getValue(), e.getKey().equals(pk), null,
                        DataModelService.referencingBase(e.getKey(), ownTokens)))
                .toList();
        return new DataModelEntity(DdlGeneratorService.toTableName(topic), topic,
                MessageFormat.JSON, columns, pk, null);
    }

    private static Map<String, String> schema(String... namesAndTypes) {
        Map<String, String> schema = new LinkedHashMap<>();
        for (int i = 0; i < namesAndTypes.length; i += 2) {
            schema.put(namesAndTypes[i], namesAndTypes[i + 1]);
        }
        return schema;
    }

    @Test
    void aColumnNamingAnotherTopicWithAgreeingKeyIsHighConfidence() {
        DataModelEntity orders = entity("demo.orders", schema("order_id", "STRING", "amount", "DOUBLE"));
        DataModelEntity payments = entity("demo.payments",
                schema("payment_id", "STRING", "order_id", "STRING"));

        List<DataModelRelation> relations = DataModelService.deduceRelations(List.of(orders, payments));

        assertEquals(1, relations.size());
        DataModelRelation r = relations.get(0);
        assertEquals("demo_payments", r.from());
        assertEquals("demo_orders", r.to());
        assertEquals("order_id", r.fromColumn());
        assertEquals("order_id", r.toColumn());
        assertEquals(RelationConfidence.HIGH, r.confidence());
    }

    @Test
    void aNameMatchWithoutAKeyColumnIsMediumConfidence() {
        DataModelEntity customers = entity("demo.customers", schema("name", "STRING"));
        DataModelEntity orders = entity("demo.orders",
                schema("order_id", "STRING", "customer_id", "STRING"));

        List<DataModelRelation> relations = DataModelService.deduceRelations(List.of(customers, orders));

        assertEquals(1, relations.size());
        DataModelRelation r = relations.get(0);
        assertEquals("demo_orders", r.from());
        assertEquals("demo_customers", r.to());
        assertNull(r.toColumn());
        assertEquals(RelationConfidence.MEDIUM, r.confidence());
    }

    @Test
    void pluralTopicNamesMatchSingularColumnBases() {
        DataModelEntity categories = entity("categories", schema("category_id", "STRING"));
        DataModelEntity products = entity("products",
                schema("product_id", "STRING", "category_id", "STRING"));

        List<DataModelRelation> relations = DataModelService.deduceRelations(List.of(categories, products));

        assertEquals(1, relations.size());
        assertEquals("products", relations.get(0).from());
        assertEquals("categories", relations.get(0).to());
        assertEquals(RelationConfidence.HIGH, relations.get(0).confidence());
    }

    @Test
    void anEntitysOwnKeyIsNotAReferenceToItself() {
        DataModelEntity orders = entity("demo.orders", schema("order_id", "STRING"));
        DataModelEntity enriched = entity("demo.orders.enriched", schema("order_id", "STRING"));

        List<DataModelRelation> relations = DataModelService.deduceRelations(List.of(orders, enriched));

        // Both topics contain the token "orders", so order_id is each one's own identity —
        // no self-reference, and no edge between the two from the name rule. The shared-key
        // rule does not fire either: order_id is the primary key of both.
        assertTrue(relations.isEmpty());
    }

    @Test
    void aGenericCorrelationIdLinksNothingUnlessAnEntityOwnsTheName() {
        DataModelEntity orders = entity("demo.orders",
                schema("order_id", "STRING", "correlation_id", "STRING"));
        DataModelEntity shipments = entity("demo.shipments",
                schema("shipment_id", "STRING", "correlation_id", "STRING"));

        List<DataModelRelation> relations = DataModelService.deduceRelations(List.of(orders, shipments));

        assertTrue(relations.isEmpty());
    }

    @Test
    void aSharedKeyColumnWhoseNameIsAmbiguousIsLowConfidence() {
        // demo.order.audit contains the word "order", so its order_id column could be its own
        // identity — the name rule stays silent on it. But audit_id is its detected key, and
        // order_id is verbatim the key of demo.orders: a weak edge that states its evidence
        // beats no edge at all.
        DataModelEntity orders = entity("demo.orders", schema("order_id", "STRING"));
        DataModelEntity audit = entity("demo.order.audit",
                schema("audit_id", "STRING", "order_id", "STRING"));

        List<DataModelRelation> relations = DataModelService.deduceRelations(List.of(orders, audit));

        assertEquals(1, relations.size());
        DataModelRelation r = relations.get(0);
        assertEquals("demo_order_audit", r.from());
        assertEquals("demo_orders", r.to());
        assertEquals("order_id", r.fromColumn());
        assertEquals(RelationConfidence.LOW, r.confidence());
    }

    @Test
    void relationsAreDeterministicallyOrdered() {
        DataModelEntity orders = entity("orders", schema("order_id", "STRING"));
        DataModelEntity a = entity("zeta", schema("order_id", "STRING", "zeta_id", "STRING"));
        DataModelEntity b = entity("alpha", schema("order_id", "STRING", "alpha_id", "STRING"));

        List<DataModelRelation> relations = DataModelService.deduceRelations(List.of(orders, a, b));

        assertEquals(2, relations.size());
        assertEquals("alpha", relations.get(0).from());
        assertEquals("zeta", relations.get(1).from());
    }

    // ── buildModel: bounds and degraded reads ─────────────────────────────────────────────

    @Test
    void anEmptySelectionIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> service.buildModel(List.of("  ")));
    }

    @Test
    void buildModelInfersMarksAndWarns() throws Exception {
        when(kafkaAdminService.getTopicsSize(anyList()))
                .thenReturn(Map.of("demo.orders", 100L, "demo.payments", 50L));
        stubTopic("demo.orders", schema("order_id", "STRING", "amount", "DOUBLE"));
        stubTopic("demo.payments", schema("payment_id", "STRING", "order_id", "STRING"));
        stubTopic("demo.empty", Map.of());

        DataModelResponse response =
                service.buildModel(List.of("demo.orders", "demo.payments", "demo.empty"));

        assertEquals(3, response.topicsRequested());
        assertEquals(2, response.topicsAnalyzed());
        assertFalse(response.truncated());
        assertEquals(1, response.relations().size());

        // The empty topic is absent from the graph and the warning says why.
        assertTrue(response.warnings().stream().anyMatch(w -> w.contains("demo.empty")));

        // The referencing column carries the id of the entity it points at.
        DataModelEntity payments = response.entities().stream()
                .filter(e -> e.topic().equals("demo.payments")).findFirst().orElseThrow();
        DataModelColumn fk = payments.columns().stream()
                .filter(c -> c.name().equals("order_id")).findFirst().orElseThrow();
        assertEquals("demo_orders", fk.references());
        assertEquals(Long.valueOf(50L), payments.messageCount());
    }

    @Test
    void aTopicWhoseReadFailsCostsThatTopicNotTheModel() throws Exception {
        when(kafkaAdminService.getTopicsSize(anyList())).thenReturn(Map.of());
        stubTopic("demo.orders", schema("order_id", "STRING"));
        when(schemaInferenceService.getSampleMessages("demo.broken"))
                .thenThrow(new RuntimeException("Timeout reading partition 0"));

        DataModelResponse response = service.buildModel(List.of("demo.orders", "demo.broken"));

        assertEquals(1, response.topicsAnalyzed());
        assertTrue(response.warnings().stream()
                .anyMatch(w -> w.contains("demo.broken") && w.contains("Timeout reading partition 0")));
    }

    @Test
    void pastTheCapTopicsAreDroppedWithAWarningNotSilently() throws Exception {
        when(kafkaAdminService.getTopicsSize(anyList())).thenReturn(Map.of());
        List<String> topics = new java.util.ArrayList<>();
        for (int i = 0; i < DataModelService.DEFAULT_MAX_TOPICS + 5; i++) {
            String topic = "t" + i;
            topics.add(topic);
            stubTopic(topic, schema("id", "STRING"));
        }

        DataModelResponse response = service.buildModel(topics);

        assertTrue(response.truncated());
        assertEquals(DataModelService.DEFAULT_MAX_TOPICS, response.topicsAnalyzed());
        assertTrue(response.warnings().stream().anyMatch(w -> w.contains("first " + DataModelService.DEFAULT_MAX_TOPICS)));
    }

    @Test
    void aRunMayAskForMoreThanTheDefault() throws Exception {
        when(kafkaAdminService.getTopicsSize(anyList())).thenReturn(Map.of());
        List<String> topics = stubTopics(DataModelService.DEFAULT_MAX_TOPICS + 5);

        DataModelResponse response = service.buildModel(topics, 40);

        assertFalse(response.truncated());
        assertEquals(topics.size(), response.topicsAnalyzed());
    }

    @Test
    void aRunCannotRaiseTheCeilingItIsGiven() throws Exception {
        // The point of the ceiling: the caller chooses within it, never past it, and what was
        // refused is said rather than applied in silence.
        explorerConfig.setDataModelMaxTopics(32);
        when(kafkaAdminService.getTopicsSize(anyList())).thenReturn(Map.of());
        List<String> topics = stubTopics(40);

        DataModelResponse response = service.buildModel(topics, 500);

        assertEquals(32, response.topicsAnalyzed());
        assertTrue(response.truncated());
        assertTrue(response.warnings().stream()
                .anyMatch(w -> w.contains("asked for 500") && w.contains("allows 32")));
    }

    @Test
    void anAbsentOrAbsurdRequestFallsBackToSomethingUsable() throws Exception {
        when(kafkaAdminService.getTopicsSize(anyList())).thenReturn(Map.of());
        List<String> topics = stubTopics(3);

        // null → the default; a nonsense value floors at one topic rather than analysing none.
        assertEquals(3, service.buildModel(topics, null).topicsAnalyzed());
        assertEquals(1, service.buildModel(topics, 0).topicsAnalyzed());
        assertEquals(1, service.buildModel(topics, -7).topicsAnalyzed());
    }

    private List<String> stubTopics(int count) {
        List<String> topics = new java.util.ArrayList<>();
        for (int i = 0; i < count; i++) {
            String topic = "t" + i;
            topics.add(topic);
            stubTopic(topic, schema("id", "STRING"));
        }
        return topics;
    }

    private void stubTopic(String topic, Map<String, String> schema) {
        List<String> samples = schema.isEmpty() ? List.of() : List.of("{\"sample\":true}");
        when(schemaInferenceService.getSampleMessages(topic)).thenReturn(samples);
        when(schemaInferenceService.detectFormat(eq(topic), any())).thenReturn(MessageFormat.JSON);
        when(schemaInferenceService.inferSchema(eq(topic), any(MessageFormat.class), anyList()))
                .thenReturn(schema);
    }
}
