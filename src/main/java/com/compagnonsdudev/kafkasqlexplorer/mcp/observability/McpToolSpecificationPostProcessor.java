// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.mcp.observability;

import com.compagnonsdudev.kafkasqlexplorer.mcp.guard.McpToolFilter;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;

import java.util.List;

/**
 * Puts {@link McpToolInterceptor} in front of every tool Spring AI registered.
 *
 * <p>A {@link BeanPostProcessor} on the specification list, rather than a replacement for the bean
 * that builds it. Spring AI's {@code toolSpecs} bean is declared by an autoconfiguration with no
 * {@code @ConditionalOnMissingBean}, so a bean of ours would not displace it — it would collide,
 * and only work with bean overriding enabled, which Boot disables by default for good reasons.
 * Post-processing takes the list the framework built and hands back the same tools with wrapped
 * handlers, which is both supported and indifferent to how that list came to be.
 *
 * <p><b>It wraps the stateful specification only</b>, which is what {@code STREAMABLE} — the
 * shipped protocol — produces. A deployment that switches {@code spring.ai.mcp.server.protocol} to
 * {@code STATELESS} gets a different specification type that this does not see, so it would lose
 * every guarantee the interceptor carries: no call recorded, no output ceiling, no JSON-RPC code on
 * a refusal. Silently. The startup check below refuses to let that be silent — it says the
 * instrumentation is not attached rather than leaving three claims standing that are no longer
 * true.
 */
public class McpToolSpecificationPostProcessor implements BeanPostProcessor {

    private static final Logger log = LoggerFactory.getLogger(McpToolSpecificationPostProcessor.class);

    /**
     * The interceptor through a provider: this post-processor is created very early, and injecting
     * the interceptor directly would drag its whole dependency graph — properties, guard, recorder,
     * meter registry — into that phase, where instantiating them defeats their own configuration.
     */
    private final ObjectProvider<McpToolInterceptor> interceptor;
    private final ObjectProvider<McpToolFilter> filter;

    private int wrapped;
    private java.util.List<String> removed = List.of();

    public McpToolSpecificationPostProcessor(ObjectProvider<McpToolInterceptor> interceptor,
                                             ObjectProvider<McpToolFilter> filter) {
        this.interceptor = interceptor;
        this.filter = filter;
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
        if (!(bean instanceof List<?> list) || list.isEmpty()) {
            return bean;
        }
        if (!(list.getFirst() instanceof SyncToolSpecification)) {
            return bean;
        }
        McpToolInterceptor wrapper = interceptor.getObject();
        McpToolFilter allowed = filter.getObject();

        List<SyncToolSpecification> all = list.stream().map(SyncToolSpecification.class::cast).toList();
        // Removed rather than left in and refused: a denied tool that appears in tools/list is
        // described to the model, chosen by it, and refused — a round trip spent on a surface that
        // advertises what it will not do. Absence is the same guarantee readonly already gives.
        List<SyncToolSpecification> permitted = all.stream()
                .filter(spec -> allowed.permits(spec.tool().name()))
                .toList();
        removed = all.stream().map(spec -> spec.tool().name())
                .filter(name -> !allowed.permits(name)).toList();

        List<SyncToolSpecification> instrumented = permitted.stream().map(wrapper::wrap).toList();
        wrapped += instrumented.size();
        log.info("MCP: {} tool(s) instrumented — calls recorded, refusals carry their JSON-RPC "
                + "code, output ceiling enforced", instrumented.size());
        if (!removed.isEmpty()) {
            log.info("MCP: {} tool(s) withheld by explorer.mcp.tools.allowed / .denied and absent "
                    + "from tools/list: {}", removed.size(), removed);
        }
        return instrumented;
    }

    /** How many tools were actually wrapped. Zero is what the startup check reports on. */
    public int wrappedCount() {
        return wrapped;
    }

    /** The tools the allow/deny lists kept out of {@code tools/list}, by name. */
    public List<String> removedTools() {
        return removed;
    }
}
