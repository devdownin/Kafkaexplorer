package com.yourcompany.kafkasqlexplorer.domain;

import java.util.Map;

public record TopicDescriptor(
    String name,
    int partitions,
    Map<Integer, Long> minOffsets,
    Map<Integer, Long> maxOffsets,
    MessageFormat detectedFormat,
    long estimatedSize
) {}
