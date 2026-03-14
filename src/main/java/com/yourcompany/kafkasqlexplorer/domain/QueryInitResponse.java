package com.yourcompany.kafkasqlexplorer.domain;

import java.util.List;

public record QueryInitResponse(
    List<String> topics,
    List<String> tables,
    boolean health
) {}
