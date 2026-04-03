package com.sx.adminapi.controller;

import com.sx.adminapi.common.util.ResultUtil;
import com.sx.adminapi.common.vo.ResponseVo;
import com.sx.adminapi.model.capacity.AdminDriverTeamChangeRequestVO;
import com.sx.adminapi.model.capacity.AdminPageVO;
import com.sx.adminapi.model.capacity.ApproveTeamChangeBody;
import com.sx.adminapi.model.capacity.RejectTeamChangeBody;
import com.sx.adminapi.service.AdminTeamChangeService;
import jakarta.validation.Valid;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 管理后台：司机换队申请 BFF，转发 {@code capacity-service}。
 * <p>统一前缀：{@code /admin/api/v1/capacity/team-change-requests}。</p>
 * <p>列表与待审数仅包含「司机城市」落在当前用户省/市域内的申请；详情与审核前做同域校验（跨域 404）。</p>
 */
@Validated
@RestController
@RequestMapping("/admin/api/v1/capacity/team-change-requests")
public class AdminTeamChangeController {

    private final AdminTeamChangeService adminTeamChangeService;

    public AdminTeamChangeController(AdminTeamChangeService adminTeamChangeService) {
        this.adminTeamChangeService = adminTeamChangeService;
    }

    /**
     * 待审核换队申请数量（菜单角标）；统计范围受当前用户省/市数据域约束。
     * <p>{@code GET /admin/api/v1/capacity/team-change-requests/pending-count}</p>
     */
    @GetMapping("/pending-count")
    public ResponseVo<Long> pendingCount() {
        return ResultUtil.success(adminTeamChangeService.pendingCount());
    }

    /**
     * 换队申请分页列表；省/市过滤由 BFF 按登录域注入下游，前端无需传 {@code provinceCode}/{@code cityCode}。
     * <p>{@code GET /admin/api/v1/capacity/team-change-requests?pageNo=&pageSize=&status=&driverId=&driverPhone=&startTime=&endTime=}</p>
     */
    @GetMapping
    public ResponseVo<AdminPageVO<AdminDriverTeamChangeRequestVO>> page(
            @RequestParam(defaultValue = "1") Integer pageNo,
            @RequestParam(defaultValue = "10") Integer pageSize,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Long driverId,
            @RequestParam(required = false) String driverPhone,
            @RequestParam(required = false) String startTime,
            @RequestParam(required = false) String endTime) {
        return ResultUtil.success(adminTeamChangeService.page(
                pageNo, pageSize, status, driverId, driverPhone, startTime, endTime));
    }

    /**
     * 换队申请详情；申请关联司机不在当前用户域内时 404。
     * <p>{@code GET /admin/api/v1/capacity/team-change-requests/{id}}</p>
     */
    @GetMapping("/{id}")
    public ResponseVo<AdminDriverTeamChangeRequestVO> detail(@PathVariable Long id) {
        return ResultUtil.success(adminTeamChangeService.detail(id));
    }

    /**
     * 审核通过；先校验申请对应司机城市在数据域内，再调用 capacity。
     * <p>{@code POST /admin/api/v1/capacity/team-change-requests/{id}/approve?reviewedBy=}</p>
     */
    @PostMapping("/{id}/approve")
    public ResponseVo<Void> approve(@PathVariable Long id,
                                    @RequestBody(required = false) ApproveTeamChangeBody body,
                                    @RequestParam(required = false, defaultValue = "system") String reviewedBy) {
        String reason = body != null ? body.getReviewReason() : null;
        adminTeamChangeService.approve(id, reason, reviewedBy);
        return ResultUtil.success(null);
    }

    /**
     * 审核拒绝（原因必填）；数据域校验同 {@link #approve}。
     * <p>{@code POST /admin/api/v1/capacity/team-change-requests/{id}/reject?reviewedBy=}</p>
     */
    @PostMapping("/{id}/reject")
    public ResponseVo<Void> reject(@PathVariable Long id,
                                   @Valid @RequestBody RejectTeamChangeBody body,
                                   @RequestParam(required = false, defaultValue = "system") String reviewedBy) {
        adminTeamChangeService.reject(id, body.getReviewReason(), reviewedBy);
        return ResultUtil.success(null);
    }
}
