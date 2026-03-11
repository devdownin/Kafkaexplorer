package com.yourcompany.kafkasqlexplorer.domain;

import java.util.List;
import java.util.Map;

public record StreamFlowResponse(List<Map<String, String>> nodes, List<Map<String, String>> edges) {
}
