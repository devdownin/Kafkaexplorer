package com.yourcompany.kafkasqlexplorer.web;

import com.yourcompany.kafkasqlexplorer.domain.QueryRequest;
import com.yourcompany.kafkasqlexplorer.domain.QueryResult;
import com.yourcompany.kafkasqlexplorer.service.FlinkSqlService;
import com.yourcompany.kafkasqlexplorer.service.KafkaAdminService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Controller
public class QueryController {

    private final FlinkSqlService flinkSqlService;
    private final KafkaAdminService kafkaAdminService;

    public QueryController(FlinkSqlService flinkSqlService, KafkaAdminService kafkaAdminService) {
        this.flinkSqlService = flinkSqlService;
        this.kafkaAdminService = kafkaAdminService;
    }

    @GetMapping("/query")
    public String query(Model model) {
        boolean isConnected = false;
        List<String> topics = Collections.emptyList();
        List<String> tables = Collections.emptyList();

        try {
            isConnected = kafkaAdminService.ping();
            if (isConnected) {
                topics = kafkaAdminService.listTopics();
            }
        } catch (Exception e) {
            // Log as warning and continue with empty list
        }

        try {
            tables = flinkSqlService.listTables();
        } catch (Exception e) {
            // Flink might be starting up
        }

        model.addAttribute("health", isConnected);
        model.addAttribute("topics", topics);
        model.addAttribute("tables", tables);
        return "query";
    }

    @PostMapping(value = "/query", produces = "application/json")
    @ResponseBody
    public QueryResult execute(@RequestBody QueryRequest request) {
        return flinkSqlService.executeSql(request);
    }

    @GetMapping(value = "/api/schema/{tableName}", produces = "application/json")
    @ResponseBody
    public Map<String, String> getSchema(@PathVariable String tableName) {
        return flinkSqlService.getTableSchema(tableName);
    }

    @PostMapping("/query/cancel/{queryId}")
    @ResponseBody
    public void cancel(@PathVariable String queryId) {
        flinkSqlService.cancelQuery(queryId);
    }
}
