// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.web;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.server.ResponseStatusException;

/**
 * Every client-side route is served by the SPA, so a page refresh, a bookmark or a shared link
 * lands on the application rather than on a 404.
 *
 * <p>The patterns exclude a dot from the last segment on purpose: that is what lets
 * {@code /assets/index-a1b2c3.js} fall through to the resource handler instead of being answered
 * with {@code index.html}. What that rule got wrong is the one client route whose parameter is a
 * <b>Kafka topic name</b> — and Kafka topic names contain dots, every one of the demo cluster's
 * included ({@code demo.orders.1.received}). So {@code /topic/demo.orders.1.received} matched
 * nothing here, fell through to the resource handler and answered <b>404</b>: opening a shared
 * link to a topic, or pressing F5 on the Topic Explorer, was broken for essentially every topic
 * this application is pointed at. It is mapped explicitly rather than by loosening the dot rule,
 * which would take the asset paths with it — nothing static lives under {@code /topic/}.
 *
 * <p>Pinned by {@code SpaRoutingTest}, which walks the router of {@code App.tsx} and also asserts
 * the other direction, the one {@link #unmappedApi()} exists to make true: the catch-all must not
 * swallow {@code /api/**}.
 *
 * <p><b>And it must not swallow {@code /mcp} either, which it did.</b> The MCP transport is a
 * {@code RouterFunction}, and an annotated mapping outranks one whatever the pattern:
 * {@code RequestMappingHandlerMapping} is order 0 and {@code RouterFunctionMapping} order 3. So
 * every request to the endpoint this application advertises — in its console, its README,
 * {@code compose/mcp.yml} and {@code mcp-probe.sh} — came here instead, and a {@code POST}
 * forwarded to {@code index.html} reached the static resource handler, which serves GET and HEAD,
 * and answered <b>405</b>. Fifteen tools registered and no way to call any of them, shipped that
 * way, because five phases of MCP tests read beans and none of them ever crossed a socket.
 *
 * <p>The exclusion is in the <b>pattern</b>, and it has to be: restricting the catch-all to GET and
 * HEAD was tried first and changed nothing, because a path that matches with an unsupported method
 * is a terminal 405 inside {@code RequestMappingHandlerMapping} — it does not fall through to the
 * next {@code HandlerMapping}. A path this controller must not serve has to stop <em>matching</em>,
 * not stop being allowed. {@code MCP_ENDPOINT} is therefore the one place the path is written on
 * this side; it agrees with {@code spring.ai.mcp.server.streamable-http.mcp-endpoint}, which
 * {@code application.yml} leaves at its default, and {@code SpaRoutingTest} asserts the two have
 * not drifted apart.
 */
@Controller
public class SpaController {

    /**
     * The MCP transport's path, excluded from the catch-all below.
     *
     * <p>A constant because {@code @RequestMapping} takes compile-time values, and one place
     * because two copies of a path are two things to keep in step. {@code SpaRoutingTest} resolves
     * it against the property Spring AI actually binds.
     */
    static final String MCP_ENDPOINT = "mcp";

    @RequestMapping(value = {
        "/",
        // Every dotless path except the MCP endpoint, which belongs to a RouterFunction this
        // mapping would otherwise outrank — see the class comment.
        "/{path:(?!" + MCP_ENDPOINT + "$)[^\\.]*}",
        "/**/{path:(?!" + MCP_ENDPOINT + "$)[^\\.]*}",
        // The Topic Explorer: its parameter is a topic name, which legitimately carries dots.
        "/topic/**",
    })
    public String forward() {
        return "forward:/index.html";
    }

    /**
     * The other direction, and the only reason the rule above is safe to state: an <b>unmapped</b>
     * API path answers 404, not the SPA.
     *
     * <p>Being outranked on {@code /api/metrics} is not that guarantee — a mapped endpoint wins on
     * specificity and always did. The hole was the paths no controller claims: {@code
     * /**}{@code /{path:[^\\.]*}} matches any dotless path under {@code /api/}, so {@code GET
     * /api/topics} — the plural of the real {@code /api/topic} — answered <b>200 {@code
     * text/html}</b> with {@code index.html}. That reaches axios and dies inside {@code JSON.parse}
     * as a syntax error on {@code <!doctype}, which is a long way from the missing route it is.
     *
     * <p>{@code /api/**} is strictly more specific than the catch-all and strictly less specific
     * than every mapping under {@code /api/} in this package — literal and path-variable alike — so
     * it shadows none of them and collects only what would have fallen through to the SPA. Any
     * method, so a typo'd verb on a real endpoint is a 404 here rather than a page of HTML.
     */
    @RequestMapping("/api/**")
    public void unmappedApi() {
        // No request detail in the reason: it is echoed into the error response, and nothing that
        // came off the wire belongs there.
        throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No such API endpoint");
    }
}
