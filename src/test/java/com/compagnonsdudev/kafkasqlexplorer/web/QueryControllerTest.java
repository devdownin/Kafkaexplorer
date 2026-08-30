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
     * Il n'y a plus de {@code POST /api/query/jobs}.
     *
     * <p>Il soumettait un {@code INSERT INTO} comme job Flink continu, pour le mode « Flink job »
     * du SQL editor. Ce mode ne fonctionnait pas et a été retiré ; l'endpoint est parti avec,
     * plutôt que de rester un second chemin non authentifié vers le moteur de requêtes que plus
     * aucun appelant n'exerce. Trois cas couvraient ici la façon dont ses refus se nommaient : ils
     * n'ont plus d'objet, et ce qui les remplace est l'absence elle-même.
     *
     * <p>Le statut attendu est <b>405 et non 404</b>, et c'est mesuré plutôt que supposé :
     * {@code GET /jobs} existe toujours (le registre est alimenté par les lectures synchrones),
     * donc le chemin résout et c'est la méthode qui ne passe pas. Restaurer le {@code @PostMapping}
     * ferait passer l'appel et échouer ce cas, ce qui est tout ce qu'on lui demande — même argument
     * que {@code MetricControllerTest.theUncalledSqlPreviewEndpointIsGone}.
     */
    @Test
    void thereIsNoJobSubmissionEndpoint() throws Exception {
        mockMvc.perform(post("/api/query/jobs")
                .contentType("application/json")
                .content("{\"sql\":\"INSERT INTO sink SELECT id FROM orders\"}"))
            .andExpect(status().isMethodNotAllowed());
    }

    /**
     * Et ce n'est pas non plus une question de charge utile : quel que soit le corps, la méthode
     * n'est pas servie sur ce chemin. Les deux cas qui vivaient ici décrivaient la *façon* dont ce
     * point d'entrée nommait ses refus ; ils n'ont plus d'objet, et ce qui les remplace est
     * l'absence elle-même — vérifiée sur les deux corps qu'ils envoyaient.
     */
    @Test
    void andNotForAStatementItWouldHaveRefusedEither() throws Exception {
        mockMvc.perform(post("/api/query/jobs")
                .contentType("application/json")
                .content("{\"sql\":\"SELECT 1\"}"))
            .andExpect(status().isMethodNotAllowed());
    }

    /** Le registre que les trois lectures servent reste servi : une lecture synchrone l'alimente. */
    @Test
    void theJobRegistryIsStillReadable() throws Exception {
        when(flinkJobService.listJobs()).thenReturn(java.util.List.of());

        mockMvc.perform(get("/api/query/jobs"))
            .andExpect(status().isOk());
    }

    /**
     * Il n'y a plus de {@code GET /api/query/sink-ddl}.
     *
     * <p>Il rendait le {@code CREATE TABLE} d'une cible d'INSERT, dérivé des colonnes de la source,
     * et son seul appelant était le bouton « créer la cible » du panneau de job soumis — parti avec
     * le mode Job. Un point d'entrée que personne n'appelle est un point d'entrée que personne ne
     * garde, et celui-ci prenait deux paramètres de requête pour aller lire un schéma : c'est la
     * règle que ce dépôt applique déjà à {@code POST /api/metrics/preview} et à l'ancien
     * {@code TableController}. Trois cas décrivaient ici la façon dont il refusait ; ce qui les
     * remplace est l'absence elle-même.
     *
     * <p>404 et non 405 : aucune autre méthode n'est servie sur ce chemin, contrairement à
     * {@code /jobs} dont le GET existe toujours.
     */
    @Test
    void thereIsNoSinkDdlEndpoint() throws Exception {
        mockMvc.perform(get("/api/query/sink-ddl")
                .param("source", "demo_orders")
                .param("topic", "demo.orders.out"))
            .andExpect(status().isNotFound());
    }
}
