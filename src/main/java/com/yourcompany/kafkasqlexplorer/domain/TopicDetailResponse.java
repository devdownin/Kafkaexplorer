package com.yourcompany.kafkasqlexplorer.domain;

import java.util.List;
import java.util.Map;

public record TopicDetailResponse(
    TopicDescriptor topic,
    MessageFormat format,
    Map<String, String> schema,
    String ddl,
    List<String> samples
) {}
