package com.sx.capacity.controller;

import com.sx.capacity.common.util.ResultUtil;
import com.sx.capacity.common.vo.PageVo;
import com.sx.capacity.common.vo.ResponseVo;
import com.sx.capacity.model.dto.ApproveTeamChangeBody;
import com.sx.capacity.model.dto.DriverTeamChangeRequestVO;
import com.sx.capacity.model.dto.RejectTeamChangeBody;
import com.sx.capacity.service.DriverTeamChangeService;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Date;

/**
 * 运力服务直连：司机换队申请列表、详情、审核（与 {@code admin-api} BFF 路径不同，功能对等）。
 * <p>统一前缀：{@code /api/v1/admin/driver-team-change-requests}。</p>
 */
@Validated
@RestController
@RequestMapping("/api/v1/admin/driver-team-change-requests")
public class AdminDriverTeamChangeController {

    private final DriverTeamChangeService driverTeamChangeService;

    public AdminDriverTeamChangeController(DriverTeamChangeService driverTeamChangeService) {
        this.driverTeamChangeService = driverTeamChangeService;
    }

    /**
     * 换队申请分页列表（默认待审由服务层处理）。
     * <p>{@code GET /api/v1/admin/driver-team-change-requests?...&provinceCode=&cityCode=}</p>
     * <p>可选 {@code provinceCode}/{@code cityCode}：按司机档案城市缩小结果集，供 BFF 注入登录域；与其它筛选条件组合为 AND。</p>
     */
    @GetMapping
    public ResponseVo<PageVo<DriverTeamChangeRequestVO>> page(
            @RequestParam(defaultValue = "1") Integer pageNo,
            @RequestParam(defaultValue = "10") Integer pageSize,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Long driverId,
            @RequestParam(required = false) String driverPhone,
            @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") Date startTime,
            @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") Date endTime,
            @RequestParam(required = false) String provinceCode,
            @RequestParam(required = false) String cityCode) {
        return ResultUtil.success(driverTeamChangeService.page(
                pageNo, pageSize, status, driverId, driverPhone, startTime, endTime, provinceCode, cityCode));
    }

    /**
     * 换队申请详情。
     * <p>{@code GET /api/v1/admin/driver-team-change-requests/{id}}</p>
     */
    @GetMapping("/{id}")
    public ResponseVo<DriverTeamChangeRequestVO> detail(@PathVariable Long id) {
        return ResultUtil.success(driverTeamChangeService.detail(id));
    }

    /**
     * 审核通过。
     * <p>{@code POST /api/v1/admin/driver-team-change-requests/{id}/approve?reviewedBy=}</p>
     *
     * @param reviewedBy 审核人标识（鉴权未接时可传占位，如 admin 登录名）
     */
    @PostMapping("/{id}/approve")
    public ResponseVo<Void> approve(@PathVariable Long id,
                                    @RequestBody(required = false) ApproveTeamChangeBody body,
                                    @RequestParam(required = false, defaultValue = "system") String reviewedBy) {
        String reason = body != null ? body.getReviewReason() : null;
        driverTeamChangeService.approve(id, reason, reviewedBy);
        return ResultUtil.success(null);
    }

    /**
     * 审核拒绝（原因必填）。
     * <p>{@code POST /api/v1/admin/driver-team-change-requests/{id}/reject?reviewedBy=}</p>
     */
    @PostMapping("/{id}/reject")
    public ResponseVo<Void> reject(@PathVariable Long id,
                                   @Valid @RequestBody RejectTeamChangeBody body,
                                   @RequestParam(required = false, defaultValue = "system") String reviewedBy) {
        driverTeamChangeService.reject(id, body.getReviewReason(), reviewedBy);
        return ResultUtil.success(null);
    }
}
