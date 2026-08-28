// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.web;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;

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
 * the other direction: the catch-all must not swallow {@code /api/**}.
 */
@Controller
public class SpaController {

    @RequestMapping(value = {
        "/",
        "/{path:[^\\.]*}",
        "/**/{path:[^\\.]*}",
        // The Topic Explorer: its parameter is a topic name, which legitimately carries dots.
        "/topic/**",
    })
    public String forward() {
        return "forward:/index.html";
    }
}
