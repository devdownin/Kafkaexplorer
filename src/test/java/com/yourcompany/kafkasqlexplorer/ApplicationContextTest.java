package com.yourcompany.kafkasqlexplorer;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest
class ApplicationContextTest {

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("kafka.bootstrap-servers", () -> "localhost:9092");
    }

    @Test
    void contextLoads() {
    }
}
