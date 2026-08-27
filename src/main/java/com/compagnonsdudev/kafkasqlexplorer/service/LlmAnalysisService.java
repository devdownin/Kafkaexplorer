// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.service;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.compagnonsdudev.kafkasqlexplorer.config.ClaudeConfig;
import com.compagnonsdudev.kafkasqlexplorer.config.ProcessMiningConfig;
import com.compagnonsdudev.kafkasqlexplorer.domain.AnomalyReport;
import com.compagnonsdudev.kafkasqlexplorer.domain.FieldMapping;
import com.compagnonsdudev.kafkasqlexplorer.domain.KafkaMessage;
import com.compagnonsdudev.kafkasqlexplorer.domain.LlmResponse;
import com.compagnonsdudev.kafkasqlexplorer.domain.PayloadDigest;
import com.compagnonsdudev.kafkasqlexplorer.domain.PayloadShape;
import com.compagnonsdudev.kafkasqlexplorer.domain.ProcessMiningCoverage;
import com.compagnonsdudev.kafkasqlexplorer.domain.ProcessMiningResult;
import com.compagnonsdudev.kafkasqlexplorer.domain.MetricPriority;
import com.compagnonsdudev.kafkasqlexplorer.domain.ProcessModel;
import com.compagnonsdudev.kafkasqlexplorer.domain.SnapshotConfig;
import com.compagnonsdudev.kafkasqlexplorer.domain.SnapshotRead;
import com.compagnonsdudev.kafkasqlexplorer.domain.TopicCoverage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;

@Service
public class LlmAnalysisService {

    private static final Logger log = LoggerFactory.getLogger(LlmAnalysisService.class);

    private final KafkaSnapshotReader snapshotReader;
    private final ClaudeConfig claudeConfig;
    private final ProcessMiningConfig processMiningConfig;
    private final PayloadDigestService payloadDigestService;
    /** Computes the event log's aggregate — the evidence the model reasons from. */
    private final ProcessModelBuilder processModelBuilder;
    /**
     * Resolved per call, never cached in a field: {@code POST /api/config} can change the provider
     * and the API key at runtime, and a client captured at construction kept every analysis on the
     * provider configured at startup. See {@link LlmClientProvider}.
     */
    private final Supplier<LlmClient> llmClient;
    /**
     * Lenient about keys it does not know, because the answer comes from a model rather than from
     * another service. A bare {@code new ObjectMapper()} keeps Jackson's default
     * {@code FAIL_ON_UNKNOWN_PROPERTIES}, so a model that added one helpful key — a {@code summary}
     * beside {@code comments}, a {@code confidence} on an anomaly — failed the <em>whole</em>
     * analysis, and the failure was reported with a hint naming {@code claude.max-tokens}, which
     * would not have fixed it. Every unconstrained path leads here: SpectraLLM, which has no notion
     * of schemas; an {@code OPENAI_COMPATIBLE} gateway, which {@code AUTO} deliberately leaves
     * alone; {@code structured-output: OFF}; and any endpoint that refused a schema once. Extra keys
     * are not a reason to throw an analysis away.
     */
    private final ObjectMapper objectMapper = JsonMapper.builder()
        .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
        .build();

    /**
     * The shape the answer must take. Sent alongside the prompt, not instead of it: a provider that
     * cannot constrain its output ignores it, and the prompt below still describes the JSON — which
     * is why the "Return ONLY valid JSON" instruction stays.
     */
    private static final LlmOutputSchema ANALYSIS_SCHEMA =
        new LlmOutputSchema("process_mining_result", LlmSchemas.processMiningResult());

    private static final String SYSTEM_PROMPT = """
        Expert Apache Kafka & Process Mining.
        Analyze Kafka messages to produce a Mermaid flowchart and anomaly report.
        Messages arrive as bounded digests (structure + selected values), never as raw payloads:
        reason about flows and shapes, and treat missing values as unobserved, not absent.
        Return ONLY valid JSON (camelCase). NO markdown, NO prose outside JSON.
        probableCause and sqlSuggestion may be null: say nothing rather than inventing one.
        metricPriorities: at most 4, and ONLY ids copied from the MÉTRIQUES CANDIDATES list.
        Never invent an id, a metric, a query or a threshold — those are measured, not chosen.
        sqlSuggestion is Flink SQL (SELECT / EXPLAIN / CREATE TABLE) — never ksqlDB.

        JSON structure:
        {
          "flowchart": "flowchart TD\\n...",
          "comments": "Short description",
          "hypotheses": ["..."],
          "blindSpots": ["..."],
          "metricPriorities": [{"id": "<one of the ids listed under MÉTRIQUES CANDIDATES>", "why": "..."}],
          "anomalies": [
            {
              "id": "ANO-001",
              "topic": "topic-name",
              "type": "SEQUENCE|TEMPORAL|STRUCTURAL|CARDINALITY|BUSINESS",
              "severity": "CRITICAL|MAJOR|MINOR",
              "fields": ["$.field"],
              "description": "...",
              "probableCause": "... or null",
              "sqlSuggestion": "SELECT ... FROM ... WHERE ...  (Flink SQL, or null)"
            }
          ]
        }

        Mermaid Rules:
        - Topics: [TopicName]
        - Services: (ServiceName)
        - Decisions: {condition}
        - Normal flow: -->
        - Anomaly flow: -.->
        """;

    /** Read by both inlining paths, so the two cannot describe one record format differently. */
    private static final String MESSAGE_FORMAT_LEGEND = """
## FORMAT DES MESSAGES
Chaque message est un résumé du payload d'origine :
  shape  = identifiant de structure (section ci-dessus)
  bytes  = taille réelle du payload d'origine
  fields = valeurs aux chemins déclarés dans le mapping
  sample = quelques autres valeurs scalaires, tronquées et volontairement peu nombreuses
  arrays = chemin de tableau -> nombre d'éléments réels
  partial = true quand le résumé est incomplet (payload plus grand que le budget d'analyse)

""";

    @org.springframework.beans.factory.annotation.Autowired
    public LlmAnalysisService(KafkaSnapshotReader snapshotReader, ClaudeConfig claudeConfig,
                              ProcessMiningConfig processMiningConfig,
                              PayloadDigestService payloadDigestService,
                              ProcessModelBuilder processModelBuilder,
                              LlmClientProvider llmClientProvider) {
        this(snapshotReader, claudeConfig, processMiningConfig, payloadDigestService,
            processModelBuilder, llmClientProvider::get);
    }

    /** Test seam: defaults the ingestion tuning so unit tests only have to supply an LlmClient. */
    public LlmAnalysisService(KafkaSnapshotReader snapshotReader, ClaudeConfig claudeConfig, LlmClient llmClient) {
        this(snapshotReader, claudeConfig, new ProcessMiningConfig(),
            new PayloadDigestService(new ProcessMiningConfig()),
            new ProcessModelBuilder(new ProcessMiningConfig()), () -> llmClient);
    }

    public LlmAnalysisService(KafkaSnapshotReader snapshotReader, ClaudeConfig claudeConfig,
                              ProcessMiningConfig processMiningConfig,
                              PayloadDigestService payloadDigestService,
                              ProcessModelBuilder processModelBuilder,
                              Supplier<LlmClient> llmClient) {
        this.snapshotReader = snapshotReader;
        this.claudeConfig = claudeConfig;
        this.processMiningConfig = processMiningConfig;
        this.payloadDigestService = payloadDigestService;
        this.processModelBuilder = processModelBuilder;
        this.llmClient = llmClient;
    }

    public ProcessMiningResult analyzeSnapshot(List<String> topics, SnapshotConfig depth,
                                                FieldMapping fieldMapping) {
        return analyzeSnapshot(topics, depth, fieldMapping, null);
    }

    public ProcessMiningResult analyzeSnapshot(List<String> topics, SnapshotConfig depth,
                                                FieldMapping fieldMapping, String auditFocus) {
        // The API key is checked *after* the read and the measurement, not before. The
        // directly-follows graph, the variants and the latencies are counting over records this
        // side already holds — only the reading of them needs a model — and refusing the whole
        // gesture for want of a key withheld the half that was free. See step 3b.

        // 1. Read and digest in one pass — payloads are summarized as they arrive and never
        //    accumulate in memory nor reach the prompt verbatim (a snapshot of 500 messages per
        //    topic at 1 MB each would be gigabytes of context)
        SnapshotRead read = snapshotReader.readSnapshot(
            topics, depth, fieldMapping, processMiningConfig.getMaxSampleFields());

        // 2. Group by topic, sort by timestamp
        Map<String, List<PayloadDigest>> byTopic = groupAndSort(topics, read.digests());

        // 3. A read that yielded nothing is not a question worth asking. The model would be handed
        //    a prompt of headings and would answer about it — inventing a plausible pipeline, or
        //    reporting an empty cluster — and either way the operator pays for a call whose subject
        //    is the absence of data. Saying so instead costs nothing and names the case: an absent
        //    topic, an empty one, or a read that failed.
        if (read.isEmpty()) {
            ProcessMiningCoverage coverage =
                coverageOf(topics, read, new PromptScope(Map.of(), Map.of()), 0);
            return ProcessMiningResult.failed(read.emptyReadExplanation()).withCoverage(coverage);
        }

        // 3a. Measure the process. Computed over every record read, so it is the one part of the
        //     evidence no sampling below can weaken — and the one part no model is needed for.
        ProcessModel model = processModelBuilder.build(allDigests(byTopic), fieldMapping);

        // 3b. Without a model there is no flowchart, no narrative and no anomalies — and the
        //     measurement is untouched by that. Reporting the refusal *with* what was measured is
        //     the same rule coverage already follows on a failed analysis: these are measurements
        //     taken on this side of the call, so losing the call cannot invalidate them.
        if (isApiKeyMissing()) {
            return ProcessMiningResult.failed(noLlmExplanation(model))
                .withProcessModel(model)
                .withCoverage(coverageOf(topics, read,
                    new PromptScope(measuredByTopic(byTopic, fieldMapping, model), Map.of()), 0));
        }

        // 4. Build user prompt, keeping what of the read actually reached it
        BuiltPrompt prompt = buildSnapshotPrompt(byTopic, fieldMapping, auditFocus, model);

        // 5. Call the configured LLM and parse. The measurement and the coverage travel on the
        //    answer whatever it is — an analysis that failed still knows what it read and what the
        //    records said, and the next attempt is sized from that.
        return attachMetricPriorities(callLlmAndParse(prompt.text()), model)
            .withProcessModel(model)
            .withCoverage(coverageOf(topics, read, prompt.scope(), prompt.text().length()));
    }

    /**
     * What the run looked at, per topic: read on one side, carried into the prompt on the other.
     *
     * <p>Deliberately structured rather than a list of sentences — {@code pages/processMiningCoverage.ts}
     * turns these rows into the wording, in one place. {@code warnings} is left for what the rows
     * cannot express, such as a field mapping the controller could not resolve.
     */
    private ProcessMiningCoverage coverageOf(List<String> topics, SnapshotRead read,
                                              PromptScope scope, int promptChars) {
        List<TopicCoverage> rows = new ArrayList<>();
        for (String topic : topics) {
            rows.add(new TopicCoverage(
                topic,
                read.messagesByTopic().getOrDefault(topic, 0),
                scope.measured().getOrDefault(topic, 0),
                scope.detailed().getOrDefault(topic, 0),
                !read.unreadableTopics().contains(topic)));
        }
        return ProcessMiningCoverage.of(rows, promptChars, processMiningConfig.getPromptCharBudget(),
            read.budgetExhausted(), read.readError(), List.of());
    }

    public ProcessMiningResult analyzeLive(List<KafkaMessage> windowMessages,
                                            FieldMapping fieldMapping,
                                            String referenceFlowchart) {
        return analyzeLive(windowMessages, fieldMapping, referenceFlowchart, null);
    }

    public ProcessMiningResult analyzeLive(List<KafkaMessage> windowMessages,
                                            FieldMapping fieldMapping,
                                            String referenceFlowchart,
                                            String auditFocus) {
        return analyzeLiveDigests(payloadDigestService.digestAll(windowMessages, fieldMapping),
            fieldMapping, referenceFlowchart, auditFocus);
    }

    /**
     * Live analysis over already-digested records — the path the live consumer uses, so raw
     * payloads are never buffered nor carried across threads.
     */
    public ProcessMiningResult analyzeLiveDigests(List<PayloadDigest> window,
                                                   FieldMapping fieldMapping,
                                                   String referenceFlowchart,
                                                   String auditFocus) {
        List<String> topics = window.stream()
            .map(PayloadDigest::topic)
            .distinct()
            .sorted()
            .toList();
        Map<String, List<PayloadDigest>> byTopic = groupAndSort(topics, window);
        ProcessModel model = processModelBuilder.build(window, fieldMapping);

        if (isApiKeyMissing()) {
            return ProcessMiningResult.failed(noLlmExplanation(model)).withProcessModel(model);
        }

        String userPrompt =
            buildLivePrompt(byTopic, fieldMapping, referenceFlowchart, auditFocus, model);
        return attachMetricPriorities(callLlmAndParse(userPrompt), model).withProcessModel(model);
    }

    /**
     * Why there is no narrative, and what there is instead.
     *
     * <p>Two different sentences, because the operator's next move differs: with a measured process
     * in hand the missing half is the interpretation, and the page has something to show; without a
     * field mapping there is nothing on either side and the mapping is the thing to go and fix.
     */
    private static String noLlmExplanation(ProcessModel model) {
        String base = "No LLM is configured, so nothing interpreted the run: the flowchart, the "
            + "narrative and the anomalies all need a model. Set an API key in Settings to get them.";
        return model.available()
            ? base + " The measured process below needed none and is complete."
            : base + " " + model.unavailableReason();
    }

    private Map<String, List<PayloadDigest>> groupAndSort(List<String> topics,
                                                           List<PayloadDigest> digests) {
        Map<String, List<PayloadDigest>> byTopic = new LinkedHashMap<>();
        for (String topic : topics) {
            byTopic.put(topic, new ArrayList<>());
        }
        for (PayloadDigest digest : digests) {
            byTopic.computeIfAbsent(digest.topic(), k -> new ArrayList<>()).add(digest);
        }
        byTopic.values().forEach(list -> list.sort(Comparator.comparingLong(PayloadDigest::timestamp)));
        return byTopic;
    }

    /**
     * A prompt and the accounting of what it carried: how many of each topic's digests really made
     * it in. The second half is what lets the answer state its own scope — the character budget
     * silently decides it, and until now it was told only to the model.
     */
    private record BuiltPrompt(String text, PromptScope scope) {
    }

    /**
     * What of the read the prompt carried, in its two forms.
     *
     * <p>They are counted apart because they are different claims. {@code measured} is every record
     * that entered the event log, and the aggregate built from it opens the prompt — so those
     * records bear on the answer whether or not any of them is shown. {@code detailed} is what was
     * inlined verbatim. Folding the two into one number reported "6 of 3,000" about a run that had
     * measured all three thousand.
     */
    private record PromptScope(Map<String, Integer> measured, Map<String, Integer> detailed) {
    }

    private BuiltPrompt buildSnapshotPrompt(Map<String, List<PayloadDigest>> byTopic,
                                        FieldMapping fieldMapping,
                                        String auditFocus,
                                        ProcessModel model) {
        StringBuilder sb = new StringBuilder();
        sb.append("## MODE: ANALYSE SNAPSHOT\n\n");
        PromptScope scope = appendCommonSections(sb, byTopic, fieldMapping, null, auditFocus, model);
        return new BuiltPrompt(sb.toString(), scope);
    }

    private String buildLivePrompt(Map<String, List<PayloadDigest>> byTopic,
                                    FieldMapping fieldMapping,
                                    String referenceFlowchart,
                                    String auditFocus,
                                    ProcessModel model) {
        StringBuilder sb = new StringBuilder();
        sb.append("## MODE: ANALYSE LIVE\n\n");
        String ref = (referenceFlowchart == null || referenceFlowchart.isBlank())
            ? "INCONNU" : referenceFlowchart;
        appendCommonSections(sb, byTopic, fieldMapping, ref, auditFocus, model);
        return sb.toString();
    }

    /**
     * The requested topics are not a parameter here, and that is not an omission: {@code byTopic}
     * is seeded by {@link #groupAndSort} with every requested topic, in the requested order, before
     * any digest is filed under it. A topic that yielded nothing is therefore an entry with an
     * empty list rather than a missing key — which is what lets this method say "0 message(s)"
     * about it — so a second list of the same names could only ever disagree with the map. It was
     * carried unused through three signatures until CodeQL said so.
     */
    private PromptScope appendCommonSections(StringBuilder sb,
                                       Map<String, List<PayloadDigest>> byTopic,
                                       FieldMapping fieldMapping,
                                       String referenceFlowchart,
                                       String auditFocus,
                                       ProcessModel model) {
        sb.append("## MAPPING DES CHAMPS\n");
        if (fieldMapping != null) {
            sb.append(fieldMapping.toPromptBlock());
        } else {
            sb.append("(aucun mapping fourni)\n");
        }
        sb.append("\n");

        if (auditFocus != null && !auditFocus.isBlank()) {
            sb.append("## AUDIT CIBLÉ\n");
            sb.append("Concentre l'analyse en priorité sur les points d'audit suivants. "
                + "Pour chaque anomalie trouvée, respecte la structure JSON demandée "
                + "(type et sévérité).\n");
            sb.append(auditFocus.strip()).append("\n\n");
        }

        if (referenceFlowchart != null) {
            sb.append("## FLOWCHART DE RÉFÉRENCE\n");
            sb.append(referenceFlowchart).append("\n\n");
            sb.append("Si aucun changement structurel détecté, retourne \"NO_CHANGE\" dans le champ flowchart.\n\n");
        }

        // What gets inlined is decided first, into a buffer, so the shapes section can describe
        // exactly the records that follow it rather than a different sample of them.
        StringBuilder body = new StringBuilder();
        List<PayloadDigest> inlined = new ArrayList<>();
        Map<String, Integer> detailed = model.available()
            ? appendCaseTraces(body, byTopic, model, fieldMapping, inlined)
            : appendTopicSamples(body, byTopic, inlined);

        appendProcessModel(sb, model);
        appendMetricCandidates(sb, model);
        appendShapes(sb, inlined);
        sb.append(body);

        sb.append("""

## INSTRUCTIONS
1. Pars du PROCESSUS MESURÉ : il est calculé sur tous les messages lus, pas sur un
   échantillon. Ne recalcule pas ses chiffres et ne les contredis pas — explique-les.
2. Génère le flowchart Mermaid à partir des TRANSITIONS listées, pas des noms de topics :
   un arc que la mesure ne montre pas n'existe pas, même s'il paraît évident.
3. Sers-toi des VARIANTES pour distinguer le flux nominal des déviations, et des CAS
   DÉTAILLÉS comme preuves à citer (id de cas, offsets).
4. Liste les anomalies avec leur sévérité, en t'appuyant sur les latences p95/max, les
   répétitions et la distribution des fins de cas.
5. Propose des hypothèses sur l'architecture sous-jacente, marquées comme telles.
6. Identifie les angles morts, en tenant compte des LIMITES DE LA MESURE ci-dessus.
7. Si une information est incertaine, préfère une liste vide à un texte hors format.
8. Les payloads sont résumés, pas complets : ne conclus jamais à l'absence d'un champ
   à partir de son absence dans "sample" — seul "shape" fait foi sur la structure.
""");
        /*
         * The instruction goes with its list, not with the prompt.
         *
         * Emitted unconditionally it told the model to choose among "MÉTRIQUES CANDIDATES" on the
         * runs where that section does not exist — a run with no event log has no transitions —
         * which is precisely the invitation to fill in an absent list that the section's own guard
         * was written against. The section is silent there; so is the instruction that reads it.
         */
        if (!MetricCandidates.from(model).isEmpty()) {
            sb.append("""
9. metricPriorities : parmi les MÉTRIQUES CANDIDATES, désigne au plus les 4 qui méritent
   d'être suivies sur CE parc, et dis en une phrase pourquoi celle-là plutôt qu'une autre.
   Recopie l'id tel quel. N'en invente aucune, ne propose ni requête ni seuil : ces cartes
   sont déjà construites et leurs seuils sortent d'une mesure. Moins de 4 est une réponse ;
   si aucune ne se distingue, renvoie une liste vide plutôt que de compléter.
""");
        }
        return new PromptScope(measuredByTopic(byTopic, fieldMapping, model), detailed);
    }

    /**
     * Records that entered the event log, per topic.
     *
     * <p>These are what the measured process is computed over, so they bear on the answer whether or
     * not any of them is inlined below. A record carrying no value at the mapped correlation path is
     * not one of them — it was read and digested, and the prompt says how many there were, but it is
     * in no transition, variant or latency.
     *
     * <p>All zeroes when no event log could be built, which is not a shortfall but the other path:
     * there the per-topic sample is the whole of what reached the model, and the totals say so
     * without a second flag that could drift from this one.
     */
    private static Map<String, Integer> measuredByTopic(Map<String, List<PayloadDigest>> byTopic,
                                                         FieldMapping mapping, ProcessModel model) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        byTopic.keySet().forEach(topic -> counts.put(topic, 0));
        if (!model.available()) {
            return counts;
        }
        byTopic.forEach((topic, digests) -> counts.put(topic, (int) digests.stream()
            .filter(digest -> ProcessModelBuilder.caseIdOf(digest, mapping) != null)
            .count()));
        return counts;
    }

    private static List<PayloadDigest> allDigests(Map<String, List<PayloadDigest>> byTopic) {
        List<PayloadDigest> all = new ArrayList<>();
        byTopic.values().forEach(all::addAll);
        return all;
    }

    /**
     * Structures, deduplicated. A window of a thousand documents that share a schema costs one
     * shape block; without this the same ten-level skeleton would be repeated per message.
     */
    private void appendShapes(StringBuilder sb, List<PayloadDigest> inlined) {
        Set<String> shapeIds = new LinkedHashSet<>();
        for (PayloadDigest digest : inlined) {
            if (digest.shapeId() != null) {
                shapeIds.add(digest.shapeId());
            }
        }
        List<PayloadShape> shapes = payloadDigestService.shapesFor(shapeIds);
        if (shapes.isEmpty()) {
            return;
        }

        sb.append("## STRUCTURES DE PAYLOAD (chemins des feuilles, index de tableau réduits à [])\n");
        for (PayloadShape shape : shapes) {
            sb.append(shape.toPromptBlock(processMiningConfig.getMaxShapePathsInPrompt()));
        }
        sb.append("\n");
    }

    /**
     * The process, as measured — the section the model is meant to reason <em>from</em>.
     *
     * <p>Every figure is computed over each record read, so this part of the evidence is immune to
     * the sampling that follows it. That inversion is the whole change: the model used to be handed
     * a few dozen records drawn per topic and asked which correlation ids never reached a terminal
     * state, a question that sample cannot answer — so what it answered from was the topic names.
     *
     * <p>When there is no mapping there is no event log, and the section says so and forbids the
     * inference rather than falling silent. A prompt that simply omits the flows is one the model
     * fills in for itself, which is the failure this exists to remove.
     */
    /**
     * The KPIs this process supports, offered by id so the model can choose without inventing.
     *
     * <p>Nothing is emitted when the event log could not be built: with no case id there are no
     * transitions, so there is nothing to choose between — and a heading over an empty list is an
     * invitation to fill it, which on a model is not a rhetorical risk but the observed failure
     * mode this whole prompt is written against.
     *
     * <p>The list is short by construction ({@link MetricCandidates} applies the panel's own caps),
     * so it costs a few hundred characters against a budget of 120 000 — which is why this rides
     * the analysis call rather than paying for one of its own. It also has to: on the Metrics page
     * the candidates still exist but the anomalies the model has just found do not, and those are
     * exactly the context that makes "which of these matter here" answerable.
     */
    private void appendMetricCandidates(StringBuilder sb, ProcessModel model) {
        List<MetricCandidates.Candidate> candidates = MetricCandidates.from(model);
        if (candidates.isEmpty()) return;
        sb.append("## MÉTRIQUES CANDIDATES\n");
        sb.append("Cartes KPI déjà construites à partir des mesures ci-dessus, avec leurs seuils. "
            + "Tu n'as qu'à en désigner au plus 4 dans metricPriorities, en recopiant l'id.\n");
        for (MetricCandidates.Candidate candidate : candidates) {
            sb.append("- ").append(candidate.id()).append(" : ").append(candidate.label()).append('\n');
        }
        sb.append('\n');
    }

    /**
     * Keep the priorities that name a candidate the prompt actually offered.
     *
     * <p>An id the model made up is a hallucination and is treated as one — dropped, and counted in
     * a blind spot rather than in silence, because the operator is entitled to know the model went
     * outside the list. The cap is applied here too: the instruction says at most four and the
     * schema cannot express it, so the code does.
     */
    private ProcessMiningResult attachMetricPriorities(ProcessMiningResult result, ProcessModel model) {
        if (result == null || result.error() != null) return result;
        List<MetricCandidates.Candidate> candidates = MetricCandidates.from(model);
        if (candidates.isEmpty()) return result.withMetricPriorities(List.of());

        Set<String> known = candidates.stream()
            .map(MetricCandidates.Candidate::id).collect(Collectors.toSet());
        List<MetricPriority> kept = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        int invented = 0;
        for (MetricPriority priority : result.metricPriorities() == null ? List.<MetricPriority>of()
                                                                        : result.metricPriorities()) {
            if (priority == null || priority.id() == null) continue;
            String id = priority.id().trim();
            if (!known.contains(id)) { invented++; continue; }
            if (!seen.add(id)) continue;
            if (kept.size() >= MAX_METRIC_PRIORITIES) continue;
            kept.add(new MetricPriority(id, priority.why()));
        }
        ProcessMiningResult withPriorities = result.withMetricPriorities(kept);
        if (invented == 0) return withPriorities;
        return withPriorities.withBlindSpot(invented + " KPI(s) named by the analysis matched no "
            + "candidate it was shown, so they were dropped: a metric this application cannot trace "
            + "back to a measurement is one it will not propose.");
    }

    /** At most four, because a list of everything is not a choice. */
    private static final int MAX_METRIC_PRIORITIES = 4;

    private void appendProcessModel(StringBuilder sb, ProcessModel model) {
        sb.append("## PROCESSUS MESURÉ\n");
        if (!model.available()) {
            sb.append("(non calculé — ").append(model.unavailableReason()).append(")\n")
              .append("Décris les topics et leurs structures, mais n'affirme AUCUNE séquence, "
                  + "latence ni corrélation entre topics : rien ici ne les a observées, et les "
                  + "noms des topics ne sont pas une observation. Signale cette limite dans "
                  + "blindSpots.\n\n");
            return;
        }

        sb.append("Chiffres calculés sur la totalité des messages lus, pas sur l'échantillon "
            + "ci-dessous. Ne les recalcule pas : explique-les.\n");
        sb.append("Cas : ").append(model.cases())
          .append(" · Événements : ").append(model.events());
        if (model.eventsWithoutCase() > 0) {
            sb.append(" · Hors corrélation : ").append(model.eventsWithoutCase());
        }
        sb.append("\nFenêtre : ").append(Instant.ofEpochMilli(model.windowStartMs()))
          .append(" → ").append(Instant.ofEpochMilli(model.windowEndMs()))
          .append(" (horloge : ").append(timeSourceLabel(model.eventTimeSource())).append(")\n");

        sb.append("\n### ACTIVITÉS — occurrences, cas concernés\n");
        for (ProcessModel.Activity activity : model.activities()) {
            sb.append("  ").append(activity.name())
              .append(" : ").append(activity.occurrences()).append(" occ, ")
              .append(activity.cases()).append(" cas\n");
        }

        sb.append("\n### TRANSITIONS OBSERVÉES — A → B, avec la latence entre les deux\n");
        if (model.edges().isEmpty()) {
            sb.append("  (aucune : aucun cas ne compte deux événements dans cette fenêtre)\n");
        }
        for (ProcessModel.Edge edge : model.edges()) {
            sb.append("  ").append(edge.from()).append(" → ").append(edge.to())
              .append(" : ").append(edge.occurrences()).append(" occ, ")
              .append(edge.cases()).append(" cas, p50 ").append(formatMillis(edge.p50Ms()))
              .append(", p95 ").append(formatMillis(edge.p95Ms()))
              .append(", max ").append(formatMillis(edge.maxMs()));
            if (edge.outOfOrderCount() > 0) {
                sb.append(" — ").append(edge.outOfOrderCount())
                  .append(" occurrence(s) produites dans l'ordre inverse de leur horodatage "
                      + "métier (horloges désynchronisées ou événement antidaté)");
            }
            sb.append("\n");
        }
        if (model.edgesOmitted() > 0) {
            sb.append("  (").append(model.edgesOmitted())
              .append(" transition(s) plus rare(s) non listée(s))\n");
        }

        sb.append("\n### VARIANTES — chemins distincts de bout en bout\n");
        for (ProcessModel.Variant variant : model.variants()) {
            sb.append("  ").append(variant.cases()).append(" cas (")
              .append(percent(variant.cases(), model.cases())).append(") : ")
              .append(String.join(" → ", variant.path()))
              .append("  [ex. ").append(variant.example()).append("]\n");
        }
        if (model.variantsOmitted() > 0) {
            sb.append("  (").append(model.variantsOmitted())
              .append(" variante(s) non listée(s) ; celles ci-dessus sont les plus fréquentes et "
                  + "les plus rares)\n");
        }

        sb.append("\n### DÉBUTS DE CAS\n");
        model.starts().forEach(e -> sb.append("  ").append(e.activity()).append(" : ")
            .append(e.cases()).append(" cas\n"));
        sb.append("### FINS DE CAS — dernière activité atteinte\n");
        model.ends().forEach(e -> sb.append("  ").append(e.activity()).append(" : ")
            .append(e.cases()).append(" cas (").append(percent(e.cases(), model.cases()))
            .append(")\n"));

        if (!model.repeats().isEmpty()) {
            sb.append("\n### RÉPÉTITIONS — un même cas revu sur la même activité\n");
            model.repeats().forEach(r -> sb.append("  ").append(r.activity()).append(" : ")
                .append(r.casesAffected()).append(" cas, jusqu'à ")
                .append(r.maxOccurrencesInOneCase()).append(" fois pour un seul cas\n"));
        }

        if (!model.notes().isEmpty()) {
            sb.append("\n### LIMITES DE LA MESURE\n");
            model.notes().forEach(note -> sb.append("  - ").append(note).append("\n"));
        }
        sb.append("\n");
    }

    /**
     * Whole case traces, one per variant the model nominated — the worked examples.
     *
     * <p>This is what replaces sampling messages per topic. The old rule drew its sample from each
     * topic independently, on offset order, so whether one case survived in two topics at once was
     * an accident of those topics carrying the same cases at comparable volume; every question the
     * audit prompts ask is about a case, and none of them was answerable from that. A trace is the
     * unit the question is asked in, so it is the unit that goes in.
     *
     * <p>They are examples and are labelled as such: the proportions live in the VARIANTES section
     * above, computed over everything. Presenting a dozen chosen traces as a representative sample
     * would be the same category of claim this whole change removes.
     */
    private Map<String, Integer> appendCaseTraces(StringBuilder sb,
                                   Map<String, List<PayloadDigest>> byTopic,
                                   ProcessModel model,
                                   FieldMapping fieldMapping,
                                   List<PayloadDigest> inlined) {
        Map<String, Integer> written = new LinkedHashMap<>();
        byTopic.keySet().forEach(topic -> written.put(topic, 0));

        Map<String, List<PayloadDigest>> byCase =
            ProcessModelBuilder.groupByCase(allDigests(byTopic), fieldMapping);

        sb.append(MESSAGE_FORMAT_LEGEND);
        sb.append("""
## CAS DÉTAILLÉS
Traces complètes, un cas par variante ci-dessus : ce sont des exemples vérifiables
(id de cas, partition, offset), PAS un échantillon représentatif — les proportions
sont dans la section VARIANTES.
""");

        int budget = processMiningConfig.getPromptCharBudget();
        int start = sb.length();
        int casesWritten = 0;
        for (String caseId : model.spotlightCases()) {
            List<PayloadDigest> trace = byCase.get(caseId);
            if (trace == null || trace.isEmpty()) {
                continue;
            }
            if (sb.length() - start > budget) {
                break;
            }
            sb.append("\n### Cas ").append(caseId).append(" — ")
              .append(trace.size()).append(" événement(s)\n[\n");
            for (int i = 0; i < trace.size(); i++) {
                if (i > 0) {
                    sb.append(",\n");
                }
                appendDigest(sb, trace.get(i));
                inlined.add(trace.get(i));
                written.merge(trace.get(i).topic(), 1, Integer::sum);
            }
            sb.append("\n]\n");
            casesWritten++;
        }

        int remaining = model.cases() - casesWritten;
        if (remaining > 0) {
            sb.append("\n(").append(remaining)
              .append(" autre(s) cas non détaillé(s) ici — ils sont comptés dans le PROCESSUS "
                  + "MESURÉ ci-dessus, qui porte sur tous les messages lus.)\n");
        }

        List<String> silent = written.entrySet().stream()
            .filter(e -> e.getValue() == 0 && !byTopic.get(e.getKey()).isEmpty())
            .map(Map.Entry::getKey)
            .toList();
        if (!silent.isEmpty()) {
            sb.append("(Aucun cas détaillé ne passe par : ").append(String.join(", ", silent))
              .append(". Ces topics ont pourtant été lus — leur absence ici est un effet du choix "
                  + "des exemples, pas une observation à leur sujet.)\n");
        }
        return written;
    }

    /**
     * Message digests sampled per topic — the path taken when no event log could be built.
     *
     * <p>Each topic gets an equal share; within a topic the messages are an evenly spaced sample
     * (first and last always kept) so a burst never crowds out the rest of the window. Kept for the
     * unmapped case, where there is no case id to sample by and describing the topics is all that
     * is on offer — {@link #appendProcessModel} is what stops the model turning that into a
     * pipeline it never saw.
     */
    private Map<String, Integer> appendTopicSamples(StringBuilder sb,
                                 Map<String, List<PayloadDigest>> byTopic,
                                 List<PayloadDigest> inlined) {
        Map<String, List<PayloadDigest>> sampled = new LinkedHashMap<>();
        int perTopicLimit = Math.max(1, processMiningConfig.getMaxMessagesPerTopicInPrompt());
        byTopic.forEach((topic, digests) -> sampled.put(topic, evenSample(digests, perTopicLimit)));
        Map<String, Integer> written = new LinkedHashMap<>();
        sb.append(MESSAGE_FORMAT_LEGEND);
        sb.append("## MESSAGES PAR TOPIC\n");

        int topicCount = Math.max(1, byTopic.size());
        // The per-topic share has a floor, because a topic allotted 200 characters contributes
        // nothing an analysis can use. But a floor multiplied by the topic count is not a budget:
        // at 2 000 characters apiece, 100 topics claim 200 000 against a 120 000 budget, and the
        // ceiling the whole digest pipeline exists to enforce was quietly exceeded by the section
        // that inlines the messages. So the floor still governs what one topic gets, and a global
        // remainder governs how many topics get it — the ones past it are named, not dropped in
        // silence.
        int perTopicBudget = Math.max(2_000, processMiningConfig.getPromptCharBudget() / topicCount);
        int globalRemaining = Math.max(perTopicBudget, processMiningConfig.getPromptCharBudget());
        int topicsOmitted = 0;

        for (Map.Entry<String, List<PayloadDigest>> entry : byTopic.entrySet()) {
            List<PayloadDigest> all = entry.getValue();
            List<PayloadDigest> selected = sampled.getOrDefault(entry.getKey(), List.of());

            if (globalRemaining <= 0) {
                topicsOmitted++;
                written.put(entry.getKey(), 0);
                continue;
            }
            int topicBudget = Math.min(perTopicBudget, globalRemaining);

            long totalBytes = all.stream().mapToLong(PayloadDigest::payloadBytes).sum();
            long maxBytes = all.stream().mapToLong(PayloadDigest::payloadBytes).max().orElse(0);

            sb.append("\n### Topic: ").append(entry.getKey())
              .append(" — ").append(all.size()).append(" message(s), ")
              .append(formatBytes(totalBytes)).append(" au total, ")
              .append("payload max ").append(formatBytes(maxBytes)).append("\n");

            int budgetStart = sb.length();
            int count = 0;
            sb.append("[\n");
            for (PayloadDigest digest : selected) {
                if (sb.length() - budgetStart > topicBudget) {
                    break;
                }
                if (count > 0) {
                    sb.append(",\n");
                }
                appendDigest(sb, digest);
                inlined.add(digest);
                count++;
            }
            sb.append("\n]\n");

            if (count < all.size()) {
                sb.append("(").append(all.size() - count)
                  .append(" message(s) non inclus — échantillon régulier sur la fenêtre)\n");
            }
            written.put(entry.getKey(), count);
            globalRemaining -= (sb.length() - budgetStart);
        }

        if (topicsOmitted > 0) {
            sb.append("\n(").append(topicsOmitted)
              .append(" topic(s) sans message inclus — budget global du prompt atteint. "
                  + "Leur absence ici ne signifie pas qu'ils sont vides.)\n");
        }
        return written;
    }

    private void appendDigest(StringBuilder sb, PayloadDigest digest) {
        sb.append("  {\"offset\": ").append(digest.offset());
        sb.append(", \"partition\": ").append(digest.partition());
        sb.append(", \"timestamp\": ").append(digest.timestamp());
        sb.append(", \"key\": ");
        appendJsonString(sb, digest.key());
        sb.append(", \"bytes\": ").append(digest.payloadBytes());
        sb.append(", \"format\": ");
        appendJsonString(sb, digest.format());
        if (digest.shapeId() != null) {
            sb.append(", \"shape\": ");
            appendJsonString(sb, digest.shapeId());
        }
        appendJsonMap(sb, "fields", digest.fields(), Integer.MAX_VALUE);
        // W3: the digest carries up to `max-sample-fields` scalars because profiling aggregates
        // them per path across a whole topic. Inlining that many *per message* is what spent a
        // topic's share in a handful of records — and on values that are, by construction, the ones
        // the mapping did not name. A few keep a record recognisable; the shape section already
        // describes the structure once, for every record that shares it.
        appendJsonMap(sb, "sample", digest.sample(),
            Math.max(0, processMiningConfig.getMaxSampleFieldsInPrompt()));
        if (digest.arrayCounts() != null && !digest.arrayCounts().isEmpty()) {
            sb.append(", \"arrays\": {");
            boolean first = true;
            for (Map.Entry<String, Integer> entry : digest.arrayCounts().entrySet()) {
                if (!first) sb.append(", ");
                appendJsonString(sb, entry.getKey());
                sb.append(": ").append(entry.getValue());
                first = false;
            }
            sb.append("}");
        }
        if (digest.preview() != null) {
            sb.append(", \"preview\": ");
            appendJsonString(sb, digest.preview());
        }
        if (digest.parseError() != null) {
            sb.append(", \"parseError\": ");
            appendJsonString(sb, digest.parseError());
        }
        if (digest.truncated()) {
            sb.append(", \"partial\": true");
        }
        sb.append("}");
    }

    private void appendJsonMap(StringBuilder sb, String name, Map<String, String> values, int limit) {
        if (values == null || values.isEmpty() || limit <= 0) {
            return;
        }
        sb.append(", \"").append(name).append("\": {");
        int written = 0;
        for (Map.Entry<String, String> entry : values.entrySet()) {
            if (written >= limit) {
                break;
            }
            if (written > 0) sb.append(", ");
            appendJsonString(sb, entry.getKey());
            sb.append(": ");
            appendJsonString(sb, entry.getValue());
            written++;
        }
        sb.append("}");
        if (values.size() > written) {
            sb.append(", \"").append(name).append("Omitted\": ").append(values.size() - written);
        }
    }

    /**
     * Picks at most {@code limit} elements spread evenly over the list, always keeping the first
     * and the last so the window's boundaries stay visible.
     */
    static <T> List<T> evenSample(List<T> values, int limit) {
        if (values.size() <= limit) {
            return values;
        }
        if (limit <= 1) {
            return values.isEmpty() ? values : List.of(values.get(0));
        }
        List<T> sampled = new ArrayList<>(limit);
        double step = (double) (values.size() - 1) / (limit - 1);
        for (int i = 0; i < limit; i++) {
            sampled.add(values.get((int) Math.round(i * step)));
        }
        return sampled;
    }

    /** A duration a reader can weigh: "812 ms", "3.2 s", "4.1 min" — never a bare millisecond count. */
    static String formatMillis(long millis) {
        long magnitude = Math.abs(millis);
        String rendered;
        if (magnitude < 1_000) {
            rendered = magnitude + " ms";
        } else if (magnitude < 60_000) {
            rendered = String.format(Locale.ROOT, "%.1f s", magnitude / 1_000.0);
        } else if (magnitude < 3_600_000) {
            rendered = String.format(Locale.ROOT, "%.1f min", magnitude / 60_000.0);
        } else {
            rendered = String.format(Locale.ROOT, "%.1f h", magnitude / 3_600_000.0);
        }
        return millis < 0 ? "-" + rendered : rendered;
    }

    private static String percent(int part, int total) {
        return total <= 0 ? "?" : String.format(Locale.ROOT, "%.1f%%", 100.0 * part / total);
    }

    /** Names the clock, because a latency measured on produce time is a different measurement. */
    private static String timeSourceLabel(ProcessModel.TimeSource source) {
        return switch (source) {
            case MAPPED_FIELD -> "horodatage métier du mapping";
            case MIXED -> "horodatage métier, avec repli partiel sur l'horodatage Kafka";
            case RECORD_TIMESTAMP -> "horodatage Kafka (produce time), faute d'horodatage métier";
        };
    }

    private static String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " o";
        if (bytes < 1024 * 1024) return (bytes / 1024) + " Ko";
        return String.format("%.1f Mo", bytes / (1024.0 * 1024.0));
    }

    /** Single pass: chaining String.replace() copied every 1 MB payload four times over. */
    private void appendJsonString(StringBuilder sb, String value) {
        if (value == null) {
            sb.append("null");
            return;
        }
        sb.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
    }

    private boolean isApiKeyMissing() {
        return claudeConfig.isApiKeyRequired() && !claudeConfig.isApiKeyConfigured();
    }

    private ProcessMiningResult callLlmAndParse(String userPrompt) {
        LlmResponse response;
        try {
            response = llmClient.get().generateWithMeta(SYSTEM_PROMPT, userPrompt, ANALYSIS_SCHEMA);
        } catch (Exception e) {
            // Surface the real cause (timeout, bad URL/model/key, provider 5xx) instead of a
            // generic "empty response" — it is what the caller shows, as an error rather than as
            // analysis prose.
            log.error("Error calling LLM API for analysis: {}", e.getMessage(), e);
            return errorResult("LLM call failed: " + e.getMessage());
        }

        String rawResponse = response.text();
        // One line per analysis, at INFO: this is the only place the cost of a run is observable,
        // and a number nobody can see is a number nobody tunes against.
        if (response.usage() != null) {
            log.info("Process Mining analysis — {}", response.usage().summary());
        }
        log.debug("LLM analysis response (first 500 chars): {}",
            rawResponse != null && rawResponse.length() > 500 ? rawResponse.substring(0, 500) : rawResponse);

        if (rawResponse == null || rawResponse.isBlank()) {
            return ProcessMiningResult.failed("LLM returned an empty response.", response.usage());
        }

        String json = LlmJsonSupport.extractJsonPayload(rawResponse);

        // A reasoning model that never stopped thinking produced no answer at all. Saying so beats
        // the generic parse error that used to follow, which sent the reader looking for a
        // malformed brace in text the model never got round to writing.
        if (json.isBlank()) {
            return ProcessMiningResult.failed(reasoningOnlyHint(rawResponse), response.usage());
        }

        try {
            ProcessMiningResult parsed = objectMapper.readValue(json, ProcessMiningResult.class);
            // Attach RAG citations (SpectraLLM); other providers return none.
            return parsed.withRagSources(response.sources()).withUsage(response.usage());
        } catch (Exception e) {
            log.error("Failed to parse LLM analysis response: {}", e.getMessage());
            log.debug("Raw response was: {}", rawResponse);
            return ProcessMiningResult.failed("Failed to parse the model's response as JSON: "
                + e.getMessage() + truncationHint(json), response.usage());
        }
    }

    /**
     * The commonest cause of an unparseable answer is not a model that ignored the format but one
     * that ran out of room mid-object: {@code claude.max-tokens} caps the whole JSON, anomalies
     * included. An unclosed brace is the signature, and naming the setting is the difference
     * between a dead end and a fix.
     */
    private String truncationHint(String json) {
        if (json == null || json.isBlank()) {
            return "";
        }
        String trimmed = json.strip();
        if (trimmed.startsWith("{") && !trimmed.endsWith("}")) {
            return " — the answer stops mid-object, which usually means it hit the output cap;"
                + " raise claude.max-tokens (currently " + claudeConfig.getMaxTokens()
                + ") or narrow the analysis to fewer topics.";
        }
        return "";
    }

    /**
     * Why an answer carried no JSON. The two cases are worth separating: a model still reasoning
     * when it hit the cap has a setting to raise, whereas one that answered in prose has a prompt or
     * a model to change.
     */
    private String reasoningOnlyHint(String rawResponse) {
        if (LlmJsonSupport.hasUnterminatedReasoning(rawResponse)) {
            return "The model spent its whole output budget reasoning and never reached an answer."
                + " Raise claude.max-tokens (currently " + claudeConfig.getMaxTokens()
                + "), or use a model that does not think before answering.";
        }
        return "The model's answer contained no JSON object.";
    }

    private ProcessMiningResult errorResult(String message) {
        return ProcessMiningResult.failed(message);
    }
}
