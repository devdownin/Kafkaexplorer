package com.yourcompany.kafkasqlexplorer.web;

import com.yourcompany.kafkasqlexplorer.domain.StreamFlowRequest;
import com.yourcompany.kafkasqlexplorer.domain.StreamFlowResponse;
import com.yourcompany.kafkasqlexplorer.service.StreamFlowService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/stream-flow")
public class StreamFlowRestController {

    private final StreamFlowService streamFlowService;

    public StreamFlowRestController(StreamFlowService streamFlowService) {
        this.streamFlowService = streamFlowService;
    }

    @PostMapping
    public StreamFlowResponse getStreamFlow(@RequestBody StreamFlowRequest request) {
        return streamFlowService.getStreamFlow(request);
    }
}
