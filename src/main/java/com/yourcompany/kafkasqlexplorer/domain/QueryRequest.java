package com.yourcompany.kafkasqlexplorer.domain;

public record QueryRequest(
    String sql,
    String topic,
    Integer maxRows,
    Long timeout,
    String readMode
) {}
