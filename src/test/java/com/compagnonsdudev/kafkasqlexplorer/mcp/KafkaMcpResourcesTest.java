// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.mcp;

import com.compagnonsdudev.kafkasqlexplorer.mcp.contract.Coverage;
import com.compagnonsdudev.kafkasqlexplorer.mcp.contract.ToolResult;
import com.compagnonsdudev.kafkasqlexplorer.mcp.guard.DlpScrubber;
import com.compagnonsdudev.kafkasqlexplorer.mcp.guard.McpRateLimiter;
import com.compagnonsdudev.kafkasqlexplorer.mcp.guard.McpRuntimeSwitches;
import com.compagnonsdudev.kafkasqlexplorer.mcp.guard.McpToolException;
import com.compagnonsdudev.kafkasqlexplorer.mcp.guard.ToolGuard;
import com.compagnonsdudev.kafkasqlexplorer.mcp.observability.McpCatalogService;
import com.compagnonsdudev.kafkasqlexplorer.mcp.observability.ToolCategory;
import com.compagnonsdudev.kafkasqlexplorer.mcp.observability.ToolDescriptor;
import com.compagnonsdudev.kafkasqlexplorer.mcp.observability.Visibility;
import com.compagnonsdudev.kafkasqlexplorer.mcp.resources.KafkaMcpResources;
import com.compagnonsdudev.kafkasqlexplorer.mcp.tools.TopicMcpTools;
import com.compagnonsdudev.kafkasqlexplorer.mcp.tools.TopicView;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class KafkaMcpResourcesTest {
    private final McpProperties properties = new McpProperties();
    private final ToolGuard guard = new ToolGuard(properties, new DlpScrubber(properties));
    private final TopicMcpTools tools = mock(TopicMcpTools.class);
    private final McpCatalogService catalog = mock(McpCatalogService.class);
    private final McpRuntimeSwitches switches = new McpRuntimeSwitches(properties);
    private final KafkaMcpResources resources = new KafkaMcpResources(
            tools, catalog, switches, new McpRateLimiter(properties), guard);

    private void expose(String tool) {
        when(catalog.exposed()).thenReturn(List.of(
                new ToolDescriptor(tool, ToolCategory.EXPLORATION, "read", Visibility.exposed(),
                        null, null, null, properties.getHardMaxOutputBytes())));
    }

    @Test
    void inventory_uses_the_existing_measured_tool_adapter() {
        expose("kex_list_topics");
        when(tools.listTopics(null, false, 20)).thenReturn(
                ToolResult.complete(List.<TopicView.TopicSummary>of(), Coverage.exhausted(0L, 0L)));

        String body = resources.topicInventory();
        assertThat(body).contains("coverage", "EXHAUSTED");
        verify(tools).listTopics(null, false, 20);
    }

    @Test
    void deny_listed_tool_cannot_be_bypassed_via_resources_read() {
        when(catalog.exposed()).thenReturn(List.of());
        assertThatThrownBy(resources::topicInventory).isInstanceOf(McpToolException.class);
        verifyNoInteractions(tools);
    }

    @Test
    void an_out_of_scope_topic_is_rejected_before_a_kafka_read() {
        expose("kex_describe_topic");
        properties.setAllowedTopicPrefixes(List.of("demo."));
        assertThatThrownBy(() -> resources.topic("internal.credentials"))
                .isInstanceOf(McpToolException.class);
        verifyNoInteractions(tools);
    }

    @Test
    void a_runtime_disabled_tool_is_refused_for_resources_too() {
        expose("kex_list_topics");
        properties.getConsole().setAllowRuntimeToggle(true);
        switches.disableTool("kex_list_topics", "operator", "incident");
        assertThatThrownBy(resources::topicInventory).isInstanceOf(McpToolException.class);
        verifyNoInteractions(tools);
    }
}
