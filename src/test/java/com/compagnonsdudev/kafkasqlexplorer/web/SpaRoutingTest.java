// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.web;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.forwardedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Every client-side route reaches the SPA, and no controller shadows one.
 *
 * <p>This is the rule this repository has broken four times: {@code StreamFlowController} carried a
 * {@code GET /stream-flow} returning the view name {@code "stream-flow"}, {@code ConfigController}
 * the same for {@code /config}, and {@code TableController} was an entire controller whose only
 * mapping took {@code /table/*} away from {@link SpaController} — each answering a page refresh
 * with a circular-view-path 500, since there is no template engine. {@code CLAUDE.md} still carries
 * the standing warning not to add a {@code GET /audit} mapping.
 *
 * <p>What guarded it until now was piecemeal: a 404 assertion inside three different controller
 * tests, in standalone MockMvc — which proves *that* controller does not map the route, and says
 * nothing about the other twelve, nor about whether the SPA actually serves it. `/audit`,
 * `/lineage`, `/cluster` and `/compare` were guarded by nothing at all, and `SpaController` had no
 * test.
 *
 * <p>So this one boots the <b>real</b> web layer: a forward to {@code /index.html} proves both
 * halves at once — the SPA serves the route, and nothing intercepted it on the way. A controller
 * added later that maps one of these paths fails here, which is the point.
 *
 * <p>The property matches {@code HealthProbesTest}'s so the two share one cached context rather
 * than booting twice; the address is deliberately unreachable, since none of this needs a broker.
 */
@SpringBootTest
class SpaRoutingTest {

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("kafka.bootstrap-servers", () -> "localhost:9092");
    }

    /*
     * MockMvc is built from the context rather than injected: Spring Boot 4 moved
     * `@AutoConfigureMockMvc` into a web test slice this project does not depend on, and adding a
     * dependency to obtain one line of wiring is not a trade this repository makes. `webAppContextSetup`
     * gives the same thing — the real dispatcher over the real context — out of `spring-test`, which
     * is already here.
     */
    @Autowired
    private WebApplicationContext context;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).build();
    }

    /** Every path in the router of {@code App.tsx}, in its order. */
    @ParameterizedTest
    @ValueSource(strings = {
        "/", "/query", "/compare", "/lineage", "/metrics", "/metrics/help", "/audit",
        "/stream-flow", "/config", "/help", "/cluster", "/process-mining", "/data-model",
    })
    void everyClientRouteReachesTheSpa(String route) throws Exception {
        mockMvc.perform(get(route)).andExpect(forwardedUrl("/index.html"));
    }

    /**
     * The Topic Explorer's route carries a Kafka topic name, and Kafka topic names contain dots —
     * the demo cluster's are all of the form {@code demo.orders.1.received}. That matters because
     * {@link SpaController}'s patterns exclude a dot from the last segment, a convention meant to
     * let static files fall through to the resource handler.
     */
    @Test
    void aTopicNameWithDotsStillReachesTheSpa() throws Exception {
        mockMvc.perform(get("/topic/demo.orders.1.received")).andExpect(forwardedUrl("/index.html"));
    }

    /**
     * And the other direction, which is what makes the rule above safe to state: the catch-all
     * must not swallow the API. A forward here would mean every endpoint answers with the SPA.
     *
     * <p>This half was always true and proves the weaker of the two claims: {@code /api/metrics} is
     * mapped by {@code MetricController}, and a mapped endpoint outranks the catch-all on
     * specificity. It cannot fail on the rule {@link SpaController} documents, which is about the
     * paths no controller claims — that is the test below.
     */
    @Test
    void theApiIsNotForwardedToTheSpa() throws Exception {
        mockMvc.perform(get("/api/metrics")).andExpect(forwardedUrl(null));
    }

    /**
     * The rule itself: an <b>unmapped</b> API path answers 404, not the SPA.
     *
     * <p>{@code /api/topics} is the plural of the real {@code /api/topic} — a renamed endpoint, or
     * one letter of a typo — and it carries no dot, so {@code SpaController}'s
     * {@code /**}{@code /{path}} catch-all matched it and answered <b>200 {@code text/html}</b>
     * with {@code index.html}. axios then died inside {@code JSON.parse} on {@code <!doctype},
     * which names neither the route nor the mistake. The status is asserted as well as the absence
     * of a forward: a 404 reached by some other route would be the right answer for the wrong
     * reason, and a forward with a 404 status is not a thing this can produce.
     */
    @Test
    void anUnmappedApiPathIsA404RatherThanTheSpa() throws Exception {
        mockMvc.perform(get("/api/topics"))
            .andExpect(status().isNotFound())
            .andExpect(forwardedUrl(null));
    }

    /**
     * And the guarantee holds one segment down, where a path variable could have absorbed it:
     * {@code /api/topic/{name}} is mapped, {@code /api/topic/demo.orders.1.received/consumer} (the
     * singular of {@code /consumers}) is not — and it is dotless in its last segment, so it is
     * exactly the shape the catch-all takes.
     */
    @Test
    void anUnmappedApiSubPathIsA404Too() throws Exception {
        mockMvc.perform(get("/api/topic/demo.orders.1.received/consumer"))
            .andExpect(status().isNotFound())
            .andExpect(forwardedUrl(null));
    }
}
