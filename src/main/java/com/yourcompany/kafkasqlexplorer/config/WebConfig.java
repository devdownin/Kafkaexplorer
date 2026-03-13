package com.yourcompany.kafkasqlexplorer.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.concurrent.TimeUnit;

@Configuration
@EnableCaching
public class WebConfig implements WebMvcConfigurer {

    @Bean
    public CacheManager cacheManager(ExplorerConfig explorerConfig) {
        CaffeineCacheManager cacheManager = new CaffeineCacheManager("kafkaTopics", "topicDescriptor");
        cacheManager.setCaffeine(Caffeine.newBuilder()
                .expireAfterWrite(explorerConfig.getCacheExpireMinutes(), TimeUnit.MINUTES)
                .maximumSize(100));
        return cacheManager;
    }
}
