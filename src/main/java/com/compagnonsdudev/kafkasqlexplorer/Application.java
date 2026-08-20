// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer;

import com.compagnonsdudev.kafkasqlexplorer.config.StoredSettingsInitializer;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableCaching
@EnableAsync
@EnableScheduling
public class Application {

    public static void main(String[] args) {
        SpringApplication app = new SpringApplication(Application.class);
        // What the operator typed on the Settings page, put back into the environment before any
        // bean binds it. Registered here rather than through spring.factories so it is visible
        // from the entry point, and so that @SpringBootTest contexts — which never call this
        // method — cannot pick up a settings file left in the working directory.
        app.addInitializers(new StoredSettingsInitializer());
        app.run(args);
    }
}
