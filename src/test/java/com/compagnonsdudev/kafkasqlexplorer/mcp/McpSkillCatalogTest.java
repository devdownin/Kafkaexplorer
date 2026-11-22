// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.mcp;

import com.compagnonsdudev.kafkasqlexplorer.mcp.skills.McpSkillCatalog;
import com.compagnonsdudev.kafkasqlexplorer.mcp.skills.McpSkillResources;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class McpSkillCatalogTest {
    private final McpSkillCatalog catalogue = new McpSkillCatalog();

    @Test
    void packaged_skills_preserve_frontmatter_and_exact_bytes() throws Exception {
        assertThat(catalogue.summaries()).hasSize(5);
        assertThat(catalogue.summaries()).extracting(McpSkillCatalog.Summary::name)
                .contains("kafka-incident-triage", "kafka-forecast-analyst",
                        "kafka-data-architect", "kafkaexplorer-ci-repair", "kafkaexplorer-release");
        var resources = new McpSkillResources(catalogue, new McpProperties());

        for (var entry : catalogue.entries()) {
            String name = entry.name();
            String content = resources.read(name);
            byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
            String digest = "sha256:" + HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
            assertThat(entry.uri()).isEqualTo("skill://" + name + "/SKILL.md");
            assertThat(content).startsWith("---\nname: " + name + "\n");
            assertThat(entry.frontmatter().get("description")).isNotBlank();
            assertThat(entry.resources()).hasSize(1);
            assertThat(entry.resources().getFirst().uri()).isEqualTo(entry.uri());
            assertThat(entry.resources().getFirst().size()).isEqualTo(bytes.length);
            assertThat(entry.resources().getFirst().digest()).isEqualTo(digest);
        }
    }

    @Test
    void resource_template_refuses_arbitrary_paths_and_unlisted_skills() {
        var resources = new McpSkillResources(catalogue, new McpProperties());
        assertThat(catalogue.find("../secrets")).isEmpty();
        assertThatThrownBy(() -> resources.read("../secrets")).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> resources.read("not-in-catalogue")).isInstanceOf(RuntimeException.class);
    }

    @Test
    void configured_mcp_output_ceiling_is_enforced() {
        McpProperties properties = new McpProperties();
        properties.setHardMaxOutputBytes(10);
        var resources = new McpSkillResources(catalogue, properties);
        assertThatThrownBy(resources::catalogue).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> resources.read("kafka-data-architect"))
                .isInstanceOf(RuntimeException.class);
    }
}
