package com.sx.adminapi.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.Map;

@FeignClient(name = "calculate", url = "${services.calculate.base-url:http://127.0.0.1:8091}")
public interface CalculateClient {

    @GetMapping("/api/v1/fare-rules")
    Map<String, Object> page(@RequestParam Map<String, Object> params);

    @GetMapping("/api/v1/fare-rules/{id}")
    Map<String, Object> detail(@PathVariable("id") Long id);

    @PostMapping("/api/v1/fare-rules")
    Map<String, Object> create(@RequestBody Map<String, Object> body);

    @PutMapping("/api/v1/fare-rules/{id}")
    Map<String, Object> update(@PathVariable("id") Long id, @RequestBody Map<String, Object> body);

    @DeleteMapping("/api/v1/fare-rules/{id}")
    Map<String, Object> delete(@PathVariable("id") Long id);
}

