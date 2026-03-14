package com.yourcompany.kafkasqlexplorer.web;

import com.yourcompany.kafkasqlexplorer.domain.QueryInitResponse;
import com.yourcompany.kafkasqlexplorer.domain.QueryRequest;
import com.yourcompany.kafkasqlexplorer.domain.QueryResult;
import com.yourcompany.kafkasqlexplorer.service.FlinkSqlService;
import com.yourcompany.kafkasqlexplorer.service.KafkaAdminService;
import org.springframework.web.bind.annotation.*;

import java.util.Collections;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/query")
public class QueryController {

    private final FlinkSqlService flinkSqlService;
    private final KafkaAdminService kafkaAdminService;

    public QueryController(FlinkSqlService flinkSqlService, KafkaAdminService kafkaAdminService) {
        this.flinkSqlService = flinkSqlService;
        this.kafkaAdminService = kafkaAdminService;
    }

    @GetMapping("/init")
    public QueryInitResponse init() {
        boolean isConnected = false;
        List<String> topics = Collections.emptyList();
        List<String> tables = Collections.emptyList();

        try {
            isConnected = kafkaAdminService.ping();
            if (isConnected) {
                topics = kafkaAdminService.listTopics();
            }
        } catch (Exception e) {
            // Ignore and show empty list
        }

        try {
            tables = flinkSqlService.listTables();
        } catch (Exception e) {
            // Flink might be starting up
        }

        return new QueryInitResponse(topics, tables, isConnected);
    }

    @PostMapping(produces = "application/json")
    public QueryResult execute(@RequestBody QueryRequest request) {
        return flinkSqlService.executeSql(request);
    }

    @GetMapping(value = "/schema/{tableName}", produces = "application/json")
    public Map<String, String> getSchema(@PathVariable String tableName) {
        return flinkSqlService.getTableSchema(tableName);
    }

    @PostMapping("/cancel/{queryId}")
    public void cancel(@PathVariable String queryId) {
        flinkSqlService.cancelQuery(queryId);
    }
}
