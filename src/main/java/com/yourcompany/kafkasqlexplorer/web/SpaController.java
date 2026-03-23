// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.yourcompany.kafkasqlexplorer.web;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;

@Controller
public class SpaController {

    @RequestMapping(value = { "/", "/{path:[^\\.]*}", "/**/{path:[^\\.]*}" })
    public String forward() {
        return "forward:/index.html";
    }
}
