// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.mcp.tools;

import com.compagnonsdudev.kafkasqlexplorer.domain.MessageFormat;
import com.compagnonsdudev.kafkasqlexplorer.mcp.McpProperties;
import com.compagnonsdudev.kafkasqlexplorer.mcp.contract.StopReason;
import com.compagnonsdudev.kafkasqlexplorer.mcp.guard.DlpScrubber;
import com.compagnonsdudev.kafkasqlexplorer.mcp.guard.McpScopeViolationException;
import com.compagnonsdudev.kafkasqlexplorer.mcp.guard.ToolGuard;
import com.compagnonsdudev.kafkasqlexplorer.service.DdlGeneratorService;
import com.compagnonsdudev.kafkasqlexplorer.service.SchemaInferenceService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SchemaMcpToolsTest {

    @Mock SchemaInferenceService schemas;
    @Mock DdlGeneratorService ddl;

    private SchemaMcpTools toolsScopedTo(String... prefixes) {
        McpProperties properties = new McpProperties();
        properties.setAllowedTopicPrefixes(List.of(prefixes));
        return new SchemaMcpTools(schemas, ddl, new ToolGuard(properties, new DlpScrubber(properties)));
    }

    @Test
    void topics_outside_the_scope_are_refused_before_any_sampling() {
        SchemaMcpTools tools = toolsScopedTo("demo.");

        assertThatThrownBy(() -> tools.inferSchema(List.of("demo.orders", "prod.payments"), null))
                .isInstanceOf(McpScopeViolationException.class);

        verifyNoInteractions(schemas);
    }

    @Test
    void a_topic_nothing_could_be_inferred_from_is_named_not_returned_with_zero_columns() {
        // A row with an empty column map reads as "this topic has no fields", which is a claim.
        // "Not reached, and here is why" is the measurement.
        given(schemas.getSampleMessages("demo.opaque")).willReturn(List.of("<binary>"));
        given(schemas.detectFormat(anyString(), any())).willReturn(MessageFormat.JSON);
        given(schemas.inferSchema(anyString(), any(), any())).willReturn(Map.of());

        var result = toolsScopedTo("*").inferSchema(List.of("demo.opaque"), null);

        assertThat(result.data()).isEmpty();
        assertThat(result.coverage().complete()).isFalse();
        assertThat(result.coverage().stopReason()).isEqualTo(StopReason.PARTIAL_FAILURE);
        assertThat(result.coverage().topicsNotReached()).containsExactly("demo.opaque");
        assertThat(result.warnings()).anySatisfy(w ->
                assertThat(w.code()).isEqualTo("SCHEMA_NOT_INFERRED"));
    }

    @Test
    void the_sample_size_behind_the_inference_is_reported() {
        // The confidence lives here: a schema drawn from four records is not the topic's schema,
        // and only the count says so.
        Map<String, String> columns = new LinkedHashMap<>(Map.of("id", "STRING"));
        given(schemas.getSampleMessages("demo.orders")).willReturn(List.of("{}", "{}", "{}", "{}"));
        given(schemas.detectFormat(anyString(), any())).willReturn(MessageFormat.JSON);
        given(schemas.inferSchema(anyString(), any(), any())).willReturn(columns);
        given(ddl.generateDdl(anyString(), any(), any())).willReturn("CREATE TABLE orders (id STRING)");

        var result = toolsScopedTo("*").inferSchema(List.of("demo.orders"), null);

        assertThat(result.data()).singleElement().satisfies(schema -> {
            assertThat(schema.sampleSize()).isEqualTo(4);
            assertThat(schema.columns()).containsEntry("id", "STRING");
            assertThat(schema.format()).isEqualTo("JSON");
        });
        assertThat(result.coverage().recordsScanned()).isEqualTo(4L);
        assertThat(result.coverage().complete()).isTrue();
    }

    @Test
    void an_unknown_format_falls_back_to_detection_and_says_it_was_ignored() {
        // Refusing would be defensible; going silent would not. The caller must not be left
        // believing the format it named was honoured.
        given(schemas.getSampleMessages(anyString())).willReturn(List.of("{}"));
        given(schemas.detectFormat(anyString(), any())).willReturn(MessageFormat.JSON);
        given(schemas.inferSchema(anyString(), any(), any())).willReturn(Map.of("id", "STRING"));
        given(ddl.generateDdl(anyString(), any(), any())).willReturn("CREATE TABLE t (id STRING)");

        var result = toolsScopedTo("*").inferSchema(List.of("demo.orders"), "PROTOBUF");

        assertThat(result.warnings()).anySatisfy(w -> {
            assertThat(w.code()).isEqualTo("FORMAT_IGNORED");
            assertThat(w.message()).contains("PROTOBUF");
        });
        assertThat(result.data()).singleElement()
                .satisfies(schema -> assertThat(schema.format()).isEqualTo("JSON"));
    }

    @Test
    void the_generated_ddl_leaves_with_its_credentials_redacted() {
        given(schemas.getSampleMessages(anyString())).willReturn(List.of("{}"));
        given(schemas.detectFormat(anyString(), any())).willReturn(MessageFormat.JSON);
        given(schemas.inferSchema(anyString(), any(), any())).willReturn(Map.of("id", "STRING"));
        given(ddl.generateDdl(anyString(), any(), any())).willReturn(
                "CREATE TABLE t (id STRING) WITH ('properties.ssl.truststore.password' = 's3cr3t')");

        var result = toolsScopedTo("*").inferSchema(List.of("demo.orders"), "JSON");

        assertThat(result.data()).singleElement()
                .satisfies(schema -> assertThat(schema.flinkDdl()).doesNotContain("s3cr3t"));
    }
}
