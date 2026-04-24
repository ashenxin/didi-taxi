package com.sx.driverapi.client;

import com.sx.driverapi.model.teamchange.CapacityCompanyRow;
import com.sx.driverapi.model.teamchange.CapacityDriverTeamChangeRequestVO;
import com.sx.driverapi.model.teamchange.PageListVo;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.Map;

@FeignClient(name = "capacity-team-change", url = "${services.capacity.base-url:http://127.0.0.1:8090}")
public interface CapacityTeamChangeClient {

    @GetMapping("/api/v1/companies")
    CoreResponseVo<PageListVo<CapacityCompanyRow>> pageCompanies(@RequestParam("pageNo") Integer pageNo,
                                                                @RequestParam("pageSize") Integer pageSize,
                                                                @RequestParam(value = "provinceCode", required = false) String provinceCode,
                                                                @RequestParam(value = "cityCode", required = false) String cityCode,
                                                                @RequestParam(value = "companyNo", required = false) String companyNo,
                                                                @RequestParam(value = "companyName", required = false) String companyName);

    @PostMapping("/api/v1/app/driver-team-change-requests")
    CoreResponseVo<Map<String, Object>> submit(@RequestBody Map<String, Object> body);

    @GetMapping("/api/v1/app/driver-team-change-requests/current")
    CoreResponseVo<CapacityDriverTeamChangeRequestVO> current(@RequestParam("driverId") Long driverId);

    @PostMapping("/api/v1/app/driver-team-change-requests/{id}/cancel")
    CoreResponseVo<Void> cancel(@PathVariable("id") Long id, @RequestBody Map<String, Object> body);
}

