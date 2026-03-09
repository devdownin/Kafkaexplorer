package com.yourcompany.kafkasqlexplorer.web;

import com.yourcompany.kafkasqlexplorer.domain.QueryRequest;
import com.yourcompany.kafkasqlexplorer.domain.QueryResult;
import com.yourcompany.kafkasqlexplorer.service.FlinkSqlService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class QueryController {

    private final FlinkSqlService flinkSqlService;

    public QueryController(FlinkSqlService flinkSqlService) {
        this.flinkSqlService = flinkSqlService;
    }

    @PostMapping("/query")
    public QueryResult execute(@RequestBody QueryRequest request) {
        return flinkSqlService.executeSql(request);
    }
}
