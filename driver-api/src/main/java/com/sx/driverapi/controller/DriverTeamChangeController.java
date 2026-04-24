package com.sx.driverapi.controller;

import com.sx.driverapi.common.util.ResultUtil;
import com.sx.driverapi.common.vo.ResponseVo;
import com.sx.driverapi.model.teamchange.CompanySearchItemVO;
import com.sx.driverapi.model.teamchange.DriverBelongingVO;
import com.sx.driverapi.model.teamchange.PageListVo;
import com.sx.driverapi.model.teamchange.SubmitTeamChangeBody;
import com.sx.driverapi.model.teamchange.TeamChangeRequestVO;
import com.sx.driverapi.service.DriverTeamChangeBffService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

/**
 * 司机端换队（更换运力主体/车队）接口。
 *
 * <p>统一前缀：{@code /driver/api/v1}</p>
 */
@RestController
@RequestMapping("/driver/api/v1")
public class DriverTeamChangeController {

    private static final String USER_ID_HEADER = "X-User-Id";

    private final DriverTeamChangeBffService service;

    public DriverTeamChangeController(DriverTeamChangeBffService service) {
        this.service = service;
    }

    /**
     * 搜索目标运力主体（公司/车队）。
     * {@code GET /driver/api/v1/capacity/companies/search?cityCode=&companyKeyword=&teamKeyword=&pageNo=&pageSize=}
     */
    @GetMapping("/capacity/companies/search")
    public ResponseVo<PageListVo<CompanySearchItemVO>> searchCompanies(@RequestHeader(value = USER_ID_HEADER, required = false) String userId,
                                                                      @RequestParam(required = false) String cityCode,
                                                                      @RequestParam(required = false) String companyKeyword,
                                                                      @RequestParam(required = false) String teamKeyword,
                                                                      @RequestParam(required = false) Integer pageNo,
                                                                      @RequestParam(required = false) Integer pageSize) {
        requireAuthedDriverId(userId);
        return ResultUtil.success(service.searchCompanies(cityCode, companyKeyword, teamKeyword, pageNo, pageSize));
    }

    /**
     * 查询我的换队申请（当前 PENDING 优先，否则最新一条；无则 data=null）。
     * {@code GET /driver/api/v1/team-change-requests/current}
     */
    @GetMapping("/team-change-requests/current")
    public ResponseVo<TeamChangeRequestVO> current(@RequestHeader(value = USER_ID_HEADER, required = false) String userId) {
        Long authedDriverId = requireAuthedDriverId(userId);
        return ResultUtil.success(service.current(authedDriverId));
    }

    /**
     * 查询司机「当前归属（只读）」信息，供换队申请页展示。
     * {@code GET /driver/api/v1/team-change/belonging}
     */
    @GetMapping("/team-change/belonging")
    public ResponseVo<DriverBelongingVO> belonging(@RequestHeader(value = USER_ID_HEADER, required = false) String userId) {
        Long authedDriverId = requireAuthedDriverId(userId);
        return ResultUtil.success(service.belonging(authedDriverId));
    }

    /**
     * 提交换队申请。
     * {@code POST /driver/api/v1/team-change-requests}
     */
    @PostMapping("/team-change-requests")
    public ResponseVo<Map<String, Object>> submit(@RequestHeader(value = USER_ID_HEADER, required = false) String userId,
                                                  @RequestBody @Valid SubmitTeamChangeBody body) {
        Long authedDriverId = requireAuthedDriverId(userId);
        Long requestId = service.submit(authedDriverId, body.getToCompanyId(), body.getRequestReason());
        Map<String, Object> resp = new HashMap<>();
        resp.put("requestId", requestId);
        return ResultUtil.success(resp);
    }

    /**
     * 撤销/放弃换队并恢复接单（方案 A）。
     * {@code POST /driver/api/v1/team-change-requests/{id}/cancel}
     */
    @PostMapping("/team-change-requests/{id}/cancel")
    public ResponseVo<Void> cancel(@RequestHeader(value = USER_ID_HEADER, required = false) String userId,
                                   @PathVariable("id") Long id) {
        Long authedDriverId = requireAuthedDriverId(userId);
        service.cancel(authedDriverId, id);
        return ResultUtil.success();
    }

    private static Long requireAuthedDriverId(String userId) {
        if (userId == null || userId.isBlank()) {
            throw new com.sx.driverapi.common.exception.BizErrorException(401, "未授权，请重新登录");
        }
        try {
            return Long.valueOf(userId.trim());
        } catch (NumberFormatException e) {
            throw new com.sx.driverapi.common.exception.BizErrorException(401, "未授权，请重新登录");
        }
    }
}

