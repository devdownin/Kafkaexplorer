package com.yourcompany.kafkasqlexplorer.web;

import com.yourcompany.kafkasqlexplorer.domain.StreamFlowRequest;
import com.yourcompany.kafkasqlexplorer.domain.StreamFlowResponse;
import com.yourcompany.kafkasqlexplorer.service.StreamFlowService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseBody;

@Controller
public class StreamFlowController {

    private final StreamFlowService streamFlowService;

    public StreamFlowController(StreamFlowService streamFlowService) {
        this.streamFlowService = streamFlowService;
    }

    @GetMapping("/stream-flow")
    public String streamFlowPage(Model model) {
        return "stream-flow";
    }

    @PostMapping(value = "/api/stream-flow", consumes = "application/json", produces = "application/json")
    @ResponseBody
    public StreamFlowResponse getStreamFlow(@RequestBody StreamFlowRequest request) {
        return streamFlowService.getStreamFlow(request);
    }
}
