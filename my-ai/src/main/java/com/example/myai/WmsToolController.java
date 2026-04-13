package com.example.myai;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/tools")
@Validated
public class WmsToolController {
    private final InoutRepository inoutRepository;

    public WmsToolController(InoutRepository inoutRepository) {
        this.inoutRepository = inoutRepository;
    }

    @GetMapping("/query_stock")
    public Map<String, Object> queryStock(@RequestParam @NotBlank(message = "不能为空") @Size(max = 128, message = "长度不能超过 128") String productName) {
        List<Inout> results = inoutRepository.findByProductName(productName.trim());

        Map<String, Object> response = new HashMap<>();
        response.put("status", "success");
        response.put("data", results);
        response.put("msg", "查询到 " + results.size() + " 条相关记录");
        return response;
    }

    @GetMapping("/low_stock")
    public List<Inout> getLowStock() {
        return inoutRepository.findLowStock();
    }
}
