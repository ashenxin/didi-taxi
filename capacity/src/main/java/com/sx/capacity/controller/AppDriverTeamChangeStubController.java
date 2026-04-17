package com.sx.capacity.controller;

import com.sx.capacity.common.util.ResultUtil;
import com.sx.capacity.common.vo.ResponseVo;
import com.sx.capacity.service.DriverTeamChangeService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 司机端 App 换队申请占位接口（未实现）。
 * 正式接入后在此路径开放 {@code POST}，并调用 {@link DriverTeamChangeService#submit(Long, Long, String, String)}。
 * 当前 {@code POST /api/v1/app/driver-team-change-requests} 返回业务码 501。
 */
@RestController
@RequestMapping("/api/v1/app/driver-team-change-requests")
public class AppDriverTeamChangeStubController {

    /**
     * 占位：换队申请未开放。
     * {@code POST /api/v1/app/driver-team-change-requests}
     */
    @PostMapping
    public ResponseVo<Void> submitNotImplementedYet() {
        return ResultUtil.notImplemented(
                "司机端 App 尚未接入。实现时请 POST JSON：driverId、toTeamId、reason?，"
                        + "并调用 DriverTeamChangeService.submit(driverId, toTeamId, reason, requestedBy)；"
                        + "需先执行运力库建表 capacity_schema.sql（含 driver_team_change_request）。");
    }
}
