package com.yourcompany.kafkasqlexplorer.domain;

import java.util.List;

public record TopicAudit(
    String name,
    long messageCount,
    MessageFormat format,
    int poisonMessageCount,
    String healthStatus,
    List<String> issues
) {}
