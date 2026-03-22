package com.yourcompany.kafkasqlexplorer.domain;

import lombok.Builder;

@Builder
public record QueryRequest(
    String sql,
    String topic,
    Integer maxRows,
    Long timeout,
    String readMode
) {}
