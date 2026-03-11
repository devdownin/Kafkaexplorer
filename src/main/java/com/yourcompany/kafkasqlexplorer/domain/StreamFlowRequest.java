package com.yourcompany.kafkasqlexplorer.domain;

public record StreamFlowRequest(String messageKey, int maxMessagesPerTopic) {
}
