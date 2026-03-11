package com.yourcompany.kafkasqlexplorer.domain;

import java.util.List;

public record StreamFlowRequest(
        String messageKey,
        int maxMessagesPerTopic,
        String searchPath,
        Integer timeLimitMinutes,
        boolean useRegex,
        List<String> targetTopics
) {
}
