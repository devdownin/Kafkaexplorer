// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.CacheControl;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.concurrent.TimeUnit;

@Configuration
@EnableCaching
public class WebConfig implements WebMvcConfigurer {

    /**
     * Cache policy for the SPA's own files. Nothing set one before, so Spring sent no
     * {@code Cache-Control} at all and every browser applied its own heuristic — including
     * to {@code index.html}, the one file that must never be stale.
     *
     * <p>That asymmetry is the whole point. Vite emits content-hashed asset names
     * ({@code Metrics-axy9HlH6.js}), so a given URL's content can never change: caching it
     * for a year is free and correct. {@code index.html} is the opposite — it is the index
     * <em>of</em> those hashes, and a browser reusing yesterday's copy asks for chunks this
     * build no longer has. The dynamic import then fails with "Failed to fetch dynamically
     * imported module" and the page dies on an error the user cannot act on.
     *
     * <p>This does not cover a tab left open across a redeploy, which no header can reach —
     * the document is already loaded and already names the old chunks. That case is handled
     * in the SPA, by {@code staleChunk.ts}. The two are complementary, not alternatives.
     */
    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/assets/**")
                .addResourceLocations("classpath:/static/assets/")
                .setCacheControl(CacheControl.maxAge(365, TimeUnit.DAYS).immutable());

        // Everything else, index.html above all: revalidate on every load. `noCache` still
        // allows a 304, so this costs one conditional request, not a re-download.
        registry.addResourceHandler("/**")
                .addResourceLocations("classpath:/static/")
                .setCacheControl(CacheControl.noCache());
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOrigins("http://localhost:5173")
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .allowCredentials(true);
    }

    @Bean
    public CacheManager cacheManager(ExplorerConfig explorerConfig) {
        CaffeineCacheManager cacheManager = new CaffeineCacheManager(
                "kafkaTopics", "topicDescriptor", "topicSizes", "topicLastMessages", "clusterDetails",
                "topicConsumers", "lineage", "auditHistory");
        cacheManager.setCaffeine(Caffeine.newBuilder()
                .expireAfterWrite(explorerConfig.getCacheExpireSeconds(), TimeUnit.SECONDS)
                // Two things at once, and the second is why it is here rather than being a matter
                // of taste: Micrometer binds every one of these caches and logs an INFO line per
                // cache at each boot saying it can register nothing but `cache.size` without this
                // — eight lines of a start that is meant to be readable. And the meters it then
                // does register are the ones worth having on an application whose read path leans
                // on a 30 s cache: a hit ratio is what says whether the broker is being spared.
                .recordStats()
                .maximumSize(100));
        return cacheManager;
    }
}
