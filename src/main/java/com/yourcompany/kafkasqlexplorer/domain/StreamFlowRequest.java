package com.yourcompany.kafkasqlexplorer.domain;

public record StreamFlowRequest(
        String messageKey,
        int maxMessagesPerTopic,
        String searchPath,
        Integer timeLimitMinutes
) {
}
