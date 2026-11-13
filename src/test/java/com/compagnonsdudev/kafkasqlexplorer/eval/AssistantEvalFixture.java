// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.eval;

import com.compagnonsdudev.kafkasqlexplorer.config.ExplorerConfig;
import com.compagnonsdudev.kafkasqlexplorer.parser.JsonSchemaInferrer;
import com.compagnonsdudev.kafkasqlexplorer.service.DdlGeneratorService;
import com.compagnonsdudev.kafkasqlexplorer.service.FlinkRuntimeCoordinator;
import com.compagnonsdudev.kafkasqlexplorer.service.FlinkSqlService;
import com.compagnonsdudev.kafkasqlexplorer.service.FlinkTableStore;
import com.compagnonsdudev.kafkasqlexplorer.service.KafkaAdminService;
import com.compagnonsdudev.kafkasqlexplorer.service.SchemaInferenceService;
import com.compagnonsdudev.kafkasqlexplorer.service.SqlQueryValidator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.flink.api.common.typeinfo.TypeInformation;
import org.apache.flink.api.common.typeinfo.Types;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.table.api.DataTypes;
import org.apache.flink.table.api.EnvironmentSettings;
import org.apache.flink.table.api.Schema;
import org.apache.flink.table.api.TableResult;
import org.apache.flink.table.api.bridge.java.StreamTableEnvironment;
import org.apache.flink.types.Row;
import org.apache.flink.types.RowKind;
import org.apache.flink.util.CloseableIterator;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.mockito.Mockito.mock;

/**
 * The SQL assistant eval's world: the seeder's records as bounded tables, shaped exactly as this
 * application would register them from Kafka, and a way to compare two statements by what they
 * return.
 *
 * <p><b>Shaped as the application registers them</b>, not as would be convenient. Each table's
 * columns come from the real {@link JsonSchemaInferrer} run over the records, and the two technical
 * columns follow {@code DdlGeneratorService}'s rules: a computed {@code proc_time} always, and an
 * {@code event_time} that is the payload's own when the payload has one — a {@code BIGINT} of epoch
 * seconds, with no watermark, so no window TVF can plan over it — and otherwise the generator's,
 * a watermarked {@code TIMESTAMP(3)}. A fixture with a friendlier schema would measure a model on a
 * catalogue the product never shows it.
 *
 * <p>The records are in {@code src/test/resources/eval/assistant/fixture.json}, written from
 * {@code setup-demo.sh} with its clock frozen (the rule {@code CLAUDE.md} sets for eval fixtures:
 * written from the seeder, never captured from a cluster). Bounded, so a statement's job finishes
 * and its changelog can be folded into a final multiset of rows.
 */
final class AssistantEvalFixture implements AutoCloseable {

    static final String RESOURCE_DIR = "/eval/assistant/";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final long KAFKA_TIMESTAMP_BASE = 1_767_225_600L;

    /** One case of {@code cases.json}. */
    record Case(String id, String kind, String family, String question, String topic,
                String broken, String reference) {
    }

    final StreamTableEnvironment tableEnv;
    final ExplorerConfig explorerConfig;
    final FlinkRuntimeCoordinator coordinator;
    final FlinkSqlService flinkSqlService;
    final SqlQueryValidator validator;
    final List<String> tableNames = new ArrayList<>();
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "assistant-eval-collect");
        t.setDaemon(true);
        return t;
    });

    private AssistantEvalFixture() throws IOException {
        StreamExecutionEnvironment env = StreamExecutionEnvironment.createLocalEnvironment(1);
        tableEnv = StreamTableEnvironment.create(env, EnvironmentSettings.newInstance().inStreamingMode().build());
        JsonNode fixture = read("fixture.json");
        for (JsonNode table : fixture.get("tables")) {
            register(env, table.get("topic").asText(), table.get("records"));
        }
        explorerConfig = new ExplorerConfig();
        coordinator = new FlinkRuntimeCoordinator(tableEnv);
        validator = new SqlQueryValidator(explorerConfig, tableEnv, coordinator);
        // Kafka is mocked empty: every table is already in the catalogue, so auto-registration has
        // nothing to do and `withTopicTableNames` nothing to rewrite.
        flinkSqlService = new FlinkSqlService(tableEnv, coordinator, explorerConfig, validator,
            mock(KafkaAdminService.class), mock(SchemaInferenceService.class),
            mock(DdlGeneratorService.class), mock(FlinkTableStore.class));
    }

    static AssistantEvalFixture load() {
        try {
            return new AssistantEvalFixture();
        } catch (IOException e) {
            throw new IllegalStateException("The assistant eval fixture could not be read", e);
        }
    }

    static List<Case> cases() {
        try {
            List<Case> cases = new ArrayList<>();
            for (JsonNode c : read("cases.json").get("cases")) {
                cases.add(new Case(c.get("id").asText(), c.get("kind").asText(), c.get("family").asText(),
                    text(c, "question"), text(c, "topic"), text(c, "broken"), c.get("reference").asText()));
            }
            return cases;
        } catch (IOException e) {
            throw new IllegalStateException("The assistant eval cases could not be read", e);
        }
    }

    // ── tables ───────────────────────────────────────────────────────────────────────────────

    private void register(StreamExecutionEnvironment env, String topic, JsonNode records) {
        JsonSchemaInferrer inferrer = new JsonSchemaInferrer();
        Map<String, String> columns = new LinkedHashMap<>();
        for (JsonNode record : records) {
            for (Map.Entry<String, String> column : inferrer.infer(record.toString()).entrySet()) {
                String known = columns.get(column.getKey());
                // A field that is an integer in one record and a decimal in another is a decimal.
                if (known == null || ("BIGINT".equals(known) && "DOUBLE".equals(column.getValue()))) {
                    columns.put(column.getKey(), column.getValue());
                }
            }
        }
        boolean eventTimeIsOurs = !columns.containsKey("event_time");

        List<String> names = new ArrayList<>(columns.keySet());
        List<TypeInformation<?>> types = new ArrayList<>();
        Schema.Builder schema = Schema.newBuilder();
        for (String name : names) {
            types.add(typeInfo(columns.get(name)));
            schema.column(name, dataType(columns.get(name)));
        }
        if (eventTimeIsOurs) {
            names.add("event_time");
            types.add(Types.LOCAL_DATE_TIME);
            schema.column("event_time", DataTypes.TIMESTAMP(3));
        }
        schema.columnByExpression("proc_time", "PROCTIME()");
        if (eventTimeIsOurs) schema.watermark("event_time", "event_time - INTERVAL '5' SECOND");

        List<Row> rows = new ArrayList<>();
        int index = 0;
        for (JsonNode record : records) {
            Object[] values = new Object[names.size()];
            for (int i = 0; i < columns.size(); i++) {
                values[i] = value(record.get(names.get(i)), columns.get(names.get(i)));
            }
            // The Kafka record timestamp the generator's event_time reads: produced in order, one
            // second apart, at the seeder's frozen clock.
            if (eventTimeIsOurs) {
                values[names.size() - 1] =
                    LocalDateTime.ofEpochSecond(KAFKA_TIMESTAMP_BASE + index, 0, ZoneOffset.UTC);
            }
            rows.add(Row.of(values));
            index++;
        }
        TypeInformation<Row> rowType = Types.ROW_NAMED(names.toArray(String[]::new),
            types.toArray(TypeInformation[]::new));
        String table = DdlGeneratorService.toTableName(topic);
        tableEnv.createTemporaryView(table, tableEnv.fromDataStream(env.fromData(rows, rowType), schema.build()));
        tableNames.add(table);
    }

    private static TypeInformation<?> typeInfo(String sqlType) {
        return switch (sqlType) {
            case "BIGINT" -> Types.LONG;
            case "DOUBLE" -> Types.DOUBLE;
            case "BOOLEAN" -> Types.BOOLEAN;
            default -> Types.STRING;
        };
    }

    private static org.apache.flink.table.types.DataType dataType(String sqlType) {
        return switch (sqlType) {
            case "BIGINT" -> DataTypes.BIGINT();
            case "DOUBLE" -> DataTypes.DOUBLE();
            case "BOOLEAN" -> DataTypes.BOOLEAN();
            default -> DataTypes.STRING();
        };
    }

    private static Object value(JsonNode node, String sqlType) {
        if (node == null || node.isNull()) return null;
        return switch (sqlType) {
            case "BIGINT" -> node.asLong();
            case "DOUBLE" -> node.asDouble();
            case "BOOLEAN" -> node.asBoolean();
            default -> node.isTextual() ? node.asText() : node.toString();
        };
    }

    // ── execution ────────────────────────────────────────────────────────────────────────────

    /**
     * What a statement finally returns: its changelog folded into a multiset of rows, each row
     * normalised to strings so two engines' number types compare.
     */
    List<List<String>> execute(String sql, long timeoutSeconds) throws Exception {
        // Planned and submitted on the calling thread: Calcite keeps its metadata provider in a
        // thread-local, and a statement planned from another thread fails with
        // "metadataHandlerProvider" — and leaves that state broken for the next one. Only the
        // collection, which is what can hang, is bounded on a thread of its own.
        TableResult result = tableEnv.executeSql(sql);
        Future<List<List<String>>> run = executor.submit(() -> {
            List<List<String>> rows = new ArrayList<>();
            try (CloseableIterator<Row> it = result.collect()) {
                while (it.hasNext()) {
                    Row row = it.next();
                    List<String> values = normalise(row);
                    RowKind kind = row.getKind();
                    if (kind == RowKind.INSERT || kind == RowKind.UPDATE_AFTER) {
                        rows.add(values);
                    } else {
                        rows.remove(values);
                    }
                }
            }
            return rows;
        });
        try {
            return run.get(timeoutSeconds, TimeUnit.SECONDS);
        } catch (java.util.concurrent.TimeoutException e) {
            run.cancel(true);
            result.getJobClient().ifPresent(job -> job.cancel());
            throw new IllegalStateException("did not finish within " + timeoutSeconds + " s", e);
        } catch (java.util.concurrent.ExecutionException e) {
            throw e.getCause() instanceof Exception ex ? ex : e;
        }
    }

    static List<String> normalise(Row row) {
        List<String> values = new ArrayList<>(row.getArity());
        for (int i = 0; i < row.getArity(); i++) values.add(normalise(row.getField(i)));
        return values;
    }

    static String normalise(Object value) {
        if (value == null) return "null";
        if (value instanceof Number number) {
            return new BigDecimal(number.toString()).setScale(2, RoundingMode.HALF_UP)
                .stripTrailingZeros().toPlainString();
        }
        return value.toString();
    }

    /**
     * Whether {@code generated} answers what {@code reference} answers: the same number of rows,
     * and each reference row found — as a multiset of values — in a distinct generated row. Column
     * names and order do not count, extra columns are allowed; the representation of a value does.
     */
    static boolean answers(List<List<String>> reference, List<List<String>> generated) {
        if (reference.size() != generated.size()) return false;
        List<List<String>> pool = new ArrayList<>(generated);
        for (List<String> wanted : reference) {
            boolean found = false;
            for (Iterator<List<String>> it = pool.iterator(); it.hasNext(); ) {
                if (containsAll(it.next(), wanted)) {
                    it.remove();
                    found = true;
                    break;
                }
            }
            if (!found) return false;
        }
        return true;
    }

    private static boolean containsAll(List<String> row, List<String> wanted) {
        List<String> remaining = new ArrayList<>(row);
        for (String value : wanted) {
            if (!remaining.remove(value)) return false;
        }
        return true;
    }

    private static JsonNode read(String name) throws IOException {
        try (InputStream in = AssistantEvalFixture.class.getResourceAsStream(RESOURCE_DIR + name)) {
            if (in == null) throw new IOException("missing resource " + RESOURCE_DIR + name);
            return MAPPER.readTree(in);
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    @Override
    public void close() {
        executor.shutdownNow();
    }
}
