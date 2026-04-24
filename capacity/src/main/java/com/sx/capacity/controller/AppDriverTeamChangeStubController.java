package com.sx.capacity.controller;

import com.sx.capacity.common.util.ResultUtil;
import com.sx.capacity.common.vo.ResponseVo;
import com.sx.capacity.model.dto.AppCancelTeamChangeBody;
import com.sx.capacity.model.dto.AppSubmitTeamChangeBody;
import com.sx.capacity.model.dto.DriverTeamChangeRequestVO;
import com.sx.capacity.service.DriverTeamChangeService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 司机端 App 换队申请（供 driver-api 调用的 capacity 内部接口）。
 *
 * <p>注意：对外建议统一走 driver-api（网关鉴权、灰度与统一响应）。capacity 侧此接口仅承载数据与事务。</p>
 *
 * <ul>
 *   <li>{@code POST /api/v1/app/driver-team-change-requests} 提交申请</li>
 *   <li>{@code GET /api/v1/app/driver-team-change-requests/current?driverId=} 查询当前/最新</li>
 *   <li>{@code POST /api/v1/app/driver-team-change-requests/{id}/cancel} 撤销/放弃并恢复</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/app/driver-team-change-requests")
public class AppDriverTeamChangeStubController {

    private final DriverTeamChangeService driverTeamChangeService;

    public AppDriverTeamChangeStubController(DriverTeamChangeService driverTeamChangeService) {
        this.driverTeamChangeService = driverTeamChangeService;
    }

    /**
     * {@code POST /api/v1/app/driver-team-change-requests}
     */
    @PostMapping
    public ResponseVo<?> submit(@RequestBody @Valid AppSubmitTeamChangeBody body) {
        Long requestId = driverTeamChangeService.submit(
                body.getDriverId(),
                body.getToTeamId(),
                body.getReason(),
                body.getRequestedBy()
        );
        return ResultUtil.success(new java.util.HashMap<String, Object>() {{
            put("requestId", requestId);
        }});
    }

    /**
     * {@code GET /api/v1/app/driver-team-change-requests/current?driverId=}
     */
    @GetMapping("/current")
    public ResponseVo<DriverTeamChangeRequestVO> current(@org.springframework.web.bind.annotation.RequestParam Long driverId) {
        return ResultUtil.success(driverTeamChangeService.current(driverId));
    }

    /**
     * {@code POST /api/v1/app/driver-team-change-requests/{id}/cancel}
     */
    @PostMapping("/{id}/cancel")
    public ResponseVo<Void> cancel(@PathVariable("id") Long id, @RequestBody @Valid AppCancelTeamChangeBody body) {
        driverTeamChangeService.cancelAndRestore(body.getDriverId(), id, body.getRequestedBy());
        return ResultUtil.success(null);
    }
}
