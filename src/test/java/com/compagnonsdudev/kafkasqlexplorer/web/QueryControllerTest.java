// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.web;

import com.compagnonsdudev.kafkasqlexplorer.domain.MessageFormat;
import com.compagnonsdudev.kafkasqlexplorer.service.DdlGeneratorService;
import com.compagnonsdudev.kafkasqlexplorer.service.FlinkJobService;
import com.compagnonsdudev.kafkasqlexplorer.service.FlinkSqlService;
import com.compagnonsdudev.kafkasqlexplorer.service.KafkaAdminService;
import com.compagnonsdudev.kafkasqlexplorer.service.SchemaInferenceService;
import com.compagnonsdudev.kafkasqlexplorer.service.SqlExplorationService;
import com.compagnonsdudev.kafkasqlexplorer.service.SqlQueryValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The endpoints of this controller that answer when something has gone wrong — none of which had a
 * test, and each of which threw away what the caller needed to know.
 *
 * <p>{@code /ddl-preview} used to return {@code Map.of("error", e.getMessage())}. {@code Map.of}
 * rejects a null value and {@code getMessage()} is null for a {@link NullPointerException}, so an
 * inference failing that way turned the handler's own error path into a 500 — which the UI could
 * only report as a generic "Failed to generate DDL preview".
 *
 * <p>{@code /init} caught both of its probes and ignored them ({@code // Ignore and show empty
 * list}), so an unreachable broker and a Flink runtime still starting up produced exactly the same
 * screen with nothing to tell them apart.
 *
 * <p>{@code /cancel} returned {@code void} and answered 200 whatever happened, so a caller could
 * not tell a cancelled Flink job from an id with no live job behind it — and a {@code KAFKA_DIRECT}
 * scan has no Flink job by construction, which makes that the common case rather than the edge one.
 *
 * <p>Standalone MockMvc: nothing here needs a Spring context.
 */
class QueryControllerTest {

    private SchemaInferenceService schemaInferenceService;
    private DdlGeneratorService ddlGeneratorService;
    private KafkaAdminService kafkaAdminService;
    private FlinkSqlService flinkSqlService;
    private FlinkJobService flinkJobService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        schemaInferenceService = Mockito.mock(SchemaInferenceService.class);
        ddlGeneratorService = Mockito.mock(DdlGeneratorService.class);
        kafkaAdminService = Mockito.mock(KafkaAdminService.class);
        flinkSqlService = Mockito.mock(FlinkSqlService.class);
        flinkJobService = Mockito.mock(FlinkJobService.class);
        QueryController controller = new QueryController(
            flinkSqlService,
            Mockito.mock(SqlExplorationService.class),
            flinkJobService,
            kafkaAdminService,
            Mockito.mock(SqlQueryValidator.class),
            schemaInferenceService,
            ddlGeneratorService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void returnsTheGeneratedDdl() throws Exception {
        when(schemaInferenceService.detectFormat(anyString())).thenReturn(MessageFormat.JSON);
        when(schemaInferenceService.inferSchema(anyString(), any())).thenReturn(Map.of("id", "STRING"));
        when(ddlGeneratorService.generateDdl(anyString(), any(), any())).thenReturn("CREATE TABLE demo_orders (id STRING)");

        mockMvc.perform(get("/api/query/ddl-preview").param("topic", "demo.orders"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.ddl").value("CREATE TABLE demo_orders (id STRING)"));
    }

    @Test
    void reportsAFailureWithItsMessage() throws Exception {
        when(schemaInferenceService.detectFormat(anyString()))
            .thenThrow(new IllegalStateException("Topic demo.orders holds no message to sample"));

        mockMvc.perform(get("/api/query/ddl-preview").param("topic", "demo.orders"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.error").value("Topic demo.orders holds no message to sample"));
    }

    /**
     * The regression this handler shipped: a throwable with no message must still produce a body,
     * not a 500. {@code SqlErrorClassifier.explain} is documented never to return null or blank,
     * and falls back to the exception's class name.
     */
    @Test
    void answersWithABodyEvenWhenTheFailureCarriesNoMessage() throws Exception {
        when(schemaInferenceService.detectFormat(anyString())).thenThrow(new NullPointerException());

        mockMvc.perform(get("/api/query/ddl-preview").param("topic", "demo.orders"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.error").value("NullPointerException"));
    }

    // ── GET /init ────────────────────────────────────────────────────────────────
    //
    // Both halves of this endpoint used to end in an empty catch, so an unreachable broker and a
    // Flink runtime still starting up rendered as the same "Engine offline · 0 tables · 0 topics"
    // with nothing to act on.

    @Test
    void reportsWhyTheBrokerIsUnreachable() throws Exception {
        when(kafkaAdminService.pingDetail())
            .thenReturn(new KafkaAdminService.PingResult(false, "Connection to node -1 refused"));
        when(flinkSqlService.listTables()).thenReturn(List.of("orders"));

        mockMvc.perform(get("/api/query/init"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.health").value(false))
            .andExpect(jsonPath("$.kafkaError").value("Connection to node -1 refused"))
            // Flink answering is independent: one side failing must not empty the other.
            .andExpect(jsonPath("$.tables[0]").value("orders"))
            .andExpect(jsonPath("$.flinkError").doesNotExist());
    }

    @Test
    void reportsWhyTheTableListIsEmpty() throws Exception {
        when(kafkaAdminService.pingDetail()).thenReturn(new KafkaAdminService.PingResult(true, null));
        when(kafkaAdminService.listTopics()).thenReturn(List.of("demo.orders"));
        when(kafkaAdminService.getTopicsSize(any())).thenReturn(Map.of("demo.orders", 12L));
        when(flinkSqlService.listTables()).thenThrow(new IllegalStateException("Flink is still starting"));

        mockMvc.perform(get("/api/query/init"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.health").value(true))
            .andExpect(jsonPath("$.topics[0]").value("demo.orders"))
            .andExpect(jsonPath("$.kafkaError").doesNotExist())
            .andExpect(jsonPath("$.flinkError").value("Flink is still starting"));
    }

    /**
     * A broker that answers the probe but refuses the metadata call is not "offline": the health
     * flag stays true and the reason is reported beside it, so the UI does not tell an operator to
     * go and check a connection that is working.
     */
    @Test
    void keepsTheClusterHealthyWhenOnlyTheMetadataCallFails() throws Exception {
        when(kafkaAdminService.pingDetail()).thenReturn(new KafkaAdminService.PingResult(true, null));
        when(kafkaAdminService.listTopics()).thenThrow(new IllegalStateException("Not authorized to access topics"));
        when(flinkSqlService.listTables()).thenReturn(List.of());

        mockMvc.perform(get("/api/query/init"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.health").value(true))
            .andExpect(jsonPath("$.kafkaError").value("Not authorized to access topics"));
    }

    // ── POST /cancel ─────────────────────────────────────────────────────────────
    //
    // Both cancel endpoints returned void and answered 200 whatever happened, so a caller could not
    // tell a cancelled Flink job from an id with no live job behind it — and a KAFKA_DIRECT scan
    // has no Flink job by construction, so that is the common case, not the edge one.

    @Test
    void reportsThatAJobWasActuallyCancelled() throws Exception {
        when(flinkJobService.cancel("q-1")).thenReturn(FlinkSqlService.CancelOutcome.CANCELLED);

        mockMvc.perform(post("/api/query/cancel/q-1"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.cancelled").value(true))
            .andExpect(jsonPath("$.outcome").value("CANCELLED"));
    }

    @Test
    void reportsThatThereWasNoJobToCancel() throws Exception {
        when(flinkJobService.cancel("q-2")).thenReturn(FlinkSqlService.CancelOutcome.NO_ACTIVE_JOB);

        mockMvc.perform(post("/api/query/cancel/q-2"))
            // Still 200: "nothing to cancel" is a legitimate outcome of a well-formed request.
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.cancelled").value(false))
            .andExpect(jsonPath("$.outcome").value("NO_ACTIVE_JOB"));
    }

    @Test
    void theJobScopedCancelAnswersTheSameContract() throws Exception {
        when(flinkJobService.cancel("q-3")).thenReturn(FlinkSqlService.CancelOutcome.CANCELLED);

        mockMvc.perform(post("/api/query/jobs/q-3/cancel"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.cancelled").value(true));
    }

    @Test
    void reportsNoFailureWhenBothAnswer() throws Exception {
        when(kafkaAdminService.pingDetail()).thenReturn(new KafkaAdminService.PingResult(true, null));
        when(kafkaAdminService.listTopics()).thenReturn(List.of("demo.orders", "demo.empty"));
        when(kafkaAdminService.getTopicsSize(any())).thenReturn(Map.of("demo.orders", 12L, "demo.empty", 0L));
        when(flinkSqlService.listTables()).thenReturn(List.of("orders"));

        mockMvc.perform(get("/api/query/init"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.health").value(true))
            // A topic with no message is filtered out, as it always was.
            .andExpect(jsonPath("$.topics.length()").value(1))
            .andExpect(jsonPath("$.kafkaError").doesNotExist())
            .andExpect(jsonPath("$.flinkError").doesNotExist());
    }

    /**
     * {@code /table/{name}} belongs to the SPA, and nothing on the server may answer it.
     *
     * <p>A {@code @Controller} used to map exactly that, returning the view name
     * {@code "table-detail"} — the trap this codebase documents for {@code /stream-flow},
     * {@code /config} and {@code /audit}, and the fourth instance of it. Its mapping was the more
     * specific of the two, so it won over {@link SpaController}'s catch-all and took the URL away
     * from the SPA; there is no template engine and no such template, and no route, link or test
     * ever pointed at it. It also ran {@code "SELECT * FROM " + name} — the path variable
     * concatenated into a statement, submitted to the query engine on an unauthenticated GET — and
     * then threw the rows away into a model nothing rendered.
     *
     * <p>Registering only {@link QueryController} is what makes the 404 mean something: the live
     * endpoint for a table is {@code /api/query/table/{name}}, under {@code /api} like every other
     * domain endpoint here.
     */
    @Test
    void thereIsNoServerSideTablePage() throws Exception {
        mockMvc.perform(get("/table/orders")).andExpect(status().isNotFound());

        when(flinkSqlService.dropTable("orders")).thenReturn(FlinkSqlService.DropOutcome.DROPPED);
        mockMvc.perform(delete("/api/query/table/orders"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.dropped").value(true));
    }

    /**
     * Three outcomes, three sentences.
     *
     * <p>The endpoint answered with a boolean, so "there was no such table" was what it said
     * whenever the engine had refused to drop one that plainly exists — a claim about something
     * nobody had checked, on a table still sitting in the schema browser.
     */
    @Test
    void theAnswerDistinguishesNothingToDropFromARefusal() throws Exception {
        when(flinkSqlService.dropTable("absent")).thenReturn(FlinkSqlService.DropOutcome.NOT_FOUND);
        mockMvc.perform(delete("/api/query/table/absent"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.dropped").value(false))
            .andExpect(jsonPath("$.outcome").value("NOT_FOUND"))
            .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("was registered or stored")));

        when(flinkSqlService.dropTable("stubborn")).thenReturn(FlinkSqlService.DropOutcome.REFUSED);
        mockMvc.perform(delete("/api/query/table/stubborn"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.dropped").value(false))
            .andExpect(jsonPath("$.outcome").value("REFUSED"))
            .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("could not be dropped")));
    }

    /** A name that cannot go into a statement is refused, and is not echoed back. */
    @Test
    void aTableNameThatIsNotAnIdentifierIsRefused() throws Exception {
        when(flinkSqlService.dropTable(org.mockito.ArgumentMatchers.anyString()))
            .thenThrow(new IllegalArgumentException("That is not a table name this application "
                + "will put into a statement: only letters, digits, '_' and '$' are accepted, and "
                + "it must not start with a digit."));

        mockMvc.perform(delete("/api/query/table/{name}", "orders'"))
            .andExpect(status().isBadRequest());
    }

    /**
     * Une soumission refusée dit pourquoi, et le statut dit à qui la faute.
     *
     * <p>{@code POST /api/query/jobs} répondait « Internal Server Error » et rien d'autre : le
     * refus de la liste blanche voyageait dans une {@code ResponseStatusException}, dont la raison
     * atterrit dans le champ {@code message} du corps d'erreur par défaut de Spring — supprimé,
     * puisque {@code server.error.include-message} vaut {@code never} — et toute autre
     * {@code RuntimeException} n'était pas attrapée du tout. Le navigateur lit {@code message}
     * puis {@code error} dans le corps et n'avait ni l'un ni l'autre, si bien que le seul geste
     * de l'éditeur qui n'a aucun repli était aussi le seul dont on n'apprenait rien.
     *
     * <p>Trois cas plutôt qu'un, parce qu'ils envoient l'opérateur à trois endroits : ce que cette
     * application refuse d'exécuter, une faute de frappe dans un nom de table, et une panne du
     * moteur — qui reste un 500, la classification étant celle du moteur de requête et non une
     * seconde règle écrite ici.
     */
    @Test
    void aRefusedJobSubmissionCarriesItsReason() throws Exception {
        when(flinkJobService.submit(any()))
            .thenThrow(new IllegalArgumentException(
                "Only INSERT and STATEMENT SET statements are allowed in Flink Job mode."));

        mockMvc.perform(post("/api/query/jobs")
                .contentType("application/json")
                .content("{\"sql\":\"SELECT 1\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value(
                org.hamcrest.Matchers.containsString("Only INSERT and STATEMENT SET statements are allowed")));
    }

    @Test
    void aTypoInTheSinkNameIsTheCallersFaultAndSaysSo() throws Exception {
        when(flinkJobService.submit(any())).thenThrow(new IllegalStateException(
            "Cannot find table '`default_catalog`.`default_database`.`no_such_sink`' in any of "
                + "the catalogs [default_catalog], nor as a temporary table."));

        mockMvc.perform(post("/api/query/jobs")
                .contentType("application/json")
                .content("{\"sql\":\"INSERT INTO no_such_sink SELECT id FROM orders\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value(
                org.hamcrest.Matchers.containsString("no_such_sink")));
    }

    /**
     * Une panne moteur reste un 500 — mais un 500 qui porte la phrase du moteur.
     *
     * <p>C'est le cas qui a produit le rapport : le connecteur refusait une option du DDL généré,
     * et l'INSERT, seul chemin de cette page sans repli sur le lecteur direct, le rendait en
     * « Internal Server Error ». La cause est corrigée ailleurs ; ce que ce cas fixe, c'est que la
     * prochaine panne du même genre arrive nommée.
     */
    @Test
    void anEngineFailureStaysAFiveHundredButNamesItself() throws Exception {
        when(flinkJobService.submit(any())).thenThrow(new IllegalStateException(
            "Unsupported options found for 'kafka'. Unsupported options: json.ignore-parse-errors"));

        mockMvc.perform(post("/api/query/jobs")
                .contentType("application/json")
                .content("{\"sql\":\"INSERT INTO sink SELECT id FROM orders\"}"))
            .andExpect(status().isInternalServerError())
            .andExpect(jsonPath("$.error").value(
                org.hamcrest.Matchers.containsString("Unsupported options")));
    }

    /**
     * Le DDL d'une cible d'INSERT, dérivé des colonnes de la source.
     *
     * <p>En mode Job la cible doit exister, et rien n'aidait à la créer : il fallait repasser en
     * mode lecture et écrire le DDL à la main. Ce point d'entrée le rend — il ne crée rien, ce qui
     * écrit sur le cluster reste un geste délibéré — et il laisse dehors les colonnes calculées,
     * qui sont précisément celles qu'un sink refuse.
     */
    @Test
    void theSinkDdlIsDerivedFromTheSourceColumns() throws Exception {
        java.util.Map<String, String> schema = new java.util.LinkedHashMap<>();
        schema.put("order_id", "STRING NOT NULL");
        schema.put("proc_time", "TIMESTAMP_LTZ(3) NOT NULL *PROCTIME*");
        when(flinkSqlService.getTableSchema("demo_orders")).thenReturn(schema);
        when(ddlGeneratorService.generateDdl(anyString(), any(), any()))
            .thenReturn("CREATE TABLE demo_orders_out (order_id STRING) WITH ('connector'='kafka')");

        mockMvc.perform(get("/api/query/sink-ddl")
                .param("source", "demo_orders").param("topic", "demo.orders.out"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.ddl").value(org.hamcrest.Matchers.containsString("CREATE TABLE")));

        org.mockito.ArgumentCaptor<java.util.Map<String, String>> columns =
            org.mockito.ArgumentCaptor.forClass(java.util.Map.class);
        Mockito.verify(ddlGeneratorService).generateDdl(
            org.mockito.ArgumentMatchers.eq("demo.orders.out"), columns.capture(), any());
        assertEquals(java.util.List.of("order_id"), java.util.List.copyOf(columns.getValue().keySet()));
    }

    /**
     * Un nom que Kafka ne pourrait pas porter est refusé avant qu'aucun DDL ne soit bâti.
     *
     * <p>Ce point d'entrée est le seul qui génère le DDL d'un topic qui n'existe pas encore, donc
     * le seul où un nom arbitraire d'une requête se retrouve recopié dans une chaîne SQL puis
     * repassé par le masquage des identifiants — dont le motif est quadratique sur une entrée
     * choisie. Le refus est de toute façon la bonne réponse sur le fond.
     */
    @Test
    void aNameKafkaCouldNotCarryIsRefusedBeforeAnyDdlIsBuilt() throws Exception {
        mockMvc.perform(get("/api/query/sink-ddl")
                .param("source", "demo_orders").param("topic", "not a topic name'"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.ddl").doesNotExist())
            .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("Kafka")));

        Mockito.verify(ddlGeneratorService, Mockito.never()).generateDdl(anyString(), any(), any());
        Mockito.verify(flinkSqlService, Mockito.never()).getTableSchema(anyString());
    }

    /** Une source que Flink ne connaît pas ne rend pas un DDL vide : elle dit quoi faire. */
    @Test
    void anUnknownSourceIsReportedRatherThanTurnedIntoAnEmptyTable() throws Exception {
        when(flinkSqlService.getTableSchema(anyString())).thenReturn(new java.util.LinkedHashMap<>());

        mockMvc.perform(get("/api/query/sink-ddl")
                .param("source", "nope").param("topic", "nope.out"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.ddl").doesNotExist())
            .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("nope")));
    }
}
