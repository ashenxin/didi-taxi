package com.sx.adminapi.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.Map;

/**
 * 调用 {@code capacity-service} 的 Feign 客户端（运力公司 / 司机 / 车辆 / 换队审核）。
 * 管理端实际鉴权与省、市数据域在 {@code admin-api} BFF 中处理；本客户端仅转发参数与结果 JSON（{@code ResponseVo} 包装）。
 */
@FeignClient(name = "capacity", url = "${services.capacity.base-url:http://127.0.0.1:8090}")
public interface CapacityClient {

    /**
     * 运力公司分页。Query 中常用：{@code pageNo}、{@code pageSize}、{@code provinceCode}、{@code cityCode}、{@code companyNo}、{@code companyName}。
     */
    @GetMapping("/api/v1/companies")
    Map<String, Object> companyPage(@RequestParam Map<String, Object> params);

    /**
     * 司机分页。Query 中常用：{@code pageNo}、{@code pageSize}、{@code companyId}、{@code name}、{@code phone}、{@code online}、
     * {@code provinceCode}（省内按市码前缀筛）、{@code cityCode}（精确市码）。
     */
    @GetMapping("/api/v1/drivers")
    Map<String, Object> driverPage(@RequestParam Map<String, Object> params);

    /**
     * 司机主键详情。供 BFF 在查询「某司机名下车辆」前拉取 {@code cityCode}，做数据域校验；无记录时下游 body 非 200。
     */
    @GetMapping("/api/v1/drivers/{driverId}")
    Map<String, Object> driverDetail(@PathVariable("driverId") Long driverId);

    /**
     * 指定司机名下车辆分页（一般为 0～1 条）。
     */
    @GetMapping("/api/v1/drivers/{driverId}/cars")
    Map<String, Object> carsByDriver(@PathVariable("driverId") Long driverId, @RequestParam Map<String, Object> params);

    /**
     * 换队申请分页。Query 中含 {@code pageNo}、{@code pageSize}、{@code status}、{@code driverId}、{@code driverPhone}、时间范围，以及
     * {@code provinceCode}、{@code cityCode}（按司机档案城市缩小列表；由 BFF 注入登录域）。
     */
    @GetMapping("/api/v1/admin/driver-team-change-requests")
    Map<String, Object> driverTeamChangePage(@RequestParam Map<String, Object> params);

    @GetMapping("/api/v1/admin/driver-team-change-requests/{id}")
    Map<String, Object> driverTeamChangeDetail(@PathVariable("id") Long id);

    @PostMapping("/api/v1/admin/driver-team-change-requests/{id}/approve")
    Map<String, Object> approveDriverTeamChange(@PathVariable("id") Long id,
                                                @RequestBody Map<String, Object> body,
                                                @RequestParam(value = "reviewedBy", required = false) String reviewedBy);

    @PostMapping("/api/v1/admin/driver-team-change-requests/{id}/reject")
    Map<String, Object> rejectDriverTeamChange(@PathVariable("id") Long id,
                                                @RequestBody Map<String, Object> body,
                                                @RequestParam(value = "reviewedBy", required = false) String reviewedBy);
}

