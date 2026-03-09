package com.yourcompany.kafkasqlexplorer.web;

import com.yourcompany.kafkasqlexplorer.domain.QueryRequest;
import com.yourcompany.kafkasqlexplorer.domain.QueryResult;
import com.yourcompany.kafkasqlexplorer.service.FlinkSqlService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

import java.util.Map;

@Controller
public class TableController {

    private final FlinkSqlService flinkSqlService;

    public TableController(FlinkSqlService flinkSqlService) {
        this.flinkSqlService = flinkSqlService;
    }

    @GetMapping("/table/{name}")
    public String detail(@PathVariable String name, Model model) {
        Map<String, String> schema = flinkSqlService.getTableSchema(name);

        QueryResult preview = flinkSqlService.executeSql(new QueryRequest(
            "SELECT * FROM " + name + " LIMIT 10",
            name, 10, 5000L, "EARLIEST"
        ));

        model.addAttribute("tableName", name);
        model.addAttribute("schema", schema);
        model.addAttribute("preview", preview);

        return "table-detail";
    }
}
