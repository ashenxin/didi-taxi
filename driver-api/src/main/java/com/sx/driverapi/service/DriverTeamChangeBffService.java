package com.sx.driverapi.service;

import com.sx.driverapi.client.CapacityTeamChangeClient;
import com.sx.driverapi.client.CapacityDriverClient;
import com.sx.driverapi.client.CoreResponseVo;
import com.sx.driverapi.common.exception.BizErrorException;
import com.sx.driverapi.model.capacity.CapacityDriverDetail;
import com.sx.driverapi.model.teamchange.CapacityCompanyRow;
import com.sx.driverapi.model.teamchange.CapacityDriverTeamChangeRequestVO;
import com.sx.driverapi.model.teamchange.CompanySearchItemVO;
import com.sx.driverapi.model.teamchange.DriverBelongingVO;
import com.sx.driverapi.model.teamchange.PageListVo;
import com.sx.driverapi.model.teamchange.TeamChangeRequestVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

@Service
@Slf4j
public class DriverTeamChangeBffService {

    private final CapacityTeamChangeClient capacityTeamChangeClient;
    private final CapacityDriverClient capacityDriverClient;

    public DriverTeamChangeBffService(CapacityTeamChangeClient capacityTeamChangeClient,
                                      CapacityDriverClient capacityDriverClient) {
        this.capacityTeamChangeClient = capacityTeamChangeClient;
        this.capacityDriverClient = capacityDriverClient;
    }

    public PageListVo<CompanySearchItemVO> searchCompanies(String cityCode,
                                                          String companyKeyword,
                                                          String teamKeyword,
                                                          Integer pageNo,
                                                          Integer pageSize) {
        int safePageNo = pageNo == null || pageNo < 1 ? 1 : pageNo;
        int safePageSize = pageSize == null || pageSize < 1 ? 10 : Math.min(pageSize, 20);

        // 先按 companyName / cityCode 用下游筛一轮，再在 BFF 层对 teamKeyword 做二次过滤
        CoreResponseVo<PageListVo<CapacityCompanyRow>> resp = capacityTeamChangeClient.pageCompanies(
                1, 200, null,
                StringUtils.hasText(cityCode) ? cityCode.trim() : null,
                null,
                StringUtils.hasText(companyKeyword) ? companyKeyword.trim() : null
        );
        unwrap(resp, "搜索运力主体");
        PageListVo<CapacityCompanyRow> data = resp.getData();
        List<CapacityCompanyRow> rows = data == null ? List.of() : (data.getList() == null ? List.of() : data.getList());

        String teamKw = StringUtils.hasText(teamKeyword) ? teamKeyword.trim().toLowerCase(Locale.ROOT) : null;
        String compKw = StringUtils.hasText(companyKeyword) ? companyKeyword.trim().toLowerCase(Locale.ROOT) : null;
        List<CapacityCompanyRow> filtered = rows.stream().filter(r -> {
            if (teamKw != null) {
                String t = r.getTeam() == null ? "" : r.getTeam();
                if (!t.toLowerCase(Locale.ROOT).contains(teamKw)) return false;
            }
            if (compKw != null) {
                String cn = r.getCompanyName() == null ? "" : r.getCompanyName();
                // capacity 已按 companyName like 过，这里保守再过滤一次
                return cn.toLowerCase(Locale.ROOT).contains(compKw);
            }
            return true;
        }).toList();

        long total = filtered.size();
        int from = Math.min((safePageNo - 1) * safePageSize, filtered.size());
        int to = Math.min(from + safePageSize, filtered.size());
        List<CompanySearchItemVO> page = filtered.subList(from, to).stream().map(this::toCompanySearchItem).collect(Collectors.toList());

        PageListVo<CompanySearchItemVO> out = new PageListVo<>();
        out.setList(page);
        out.setTotal(total);
        out.setPageNo(safePageNo);
        out.setPageSize(safePageSize);
        return out;
    }

    public Long submit(Long driverId, Long toCompanyId, String requestReason) {
        Map<String, Object> body = new HashMap<>();
        body.put("driverId", driverId);
        body.put("toTeamId", toCompanyId);
        body.put("reason", requestReason);
        body.put("requestedBy", String.valueOf(driverId));

        CoreResponseVo<Map<String, Object>> resp = capacityTeamChangeClient.submit(body);
        // capacity 侧业务异常会返回 400 code 字段（HTTP 200），这里对齐司机端期望的 409/403
        if (resp != null && resp.getCode() != null && resp.getCode() != 200) {
            String msg = resp.getMsg() == null ? "提交失败" : resp.getMsg();
            if (msg.contains("待审核")) {
                throw new BizErrorException(409, msg);
            }
            if (msg.contains("禁止操作其他司机")) {
                throw new BizErrorException(403, msg);
            }
            throw new BizErrorException(400, msg);
        }
        unwrap(resp, "提交换队申请");
        Object requestId = resp.getData() == null ? null : resp.getData().get("requestId");
        if (requestId instanceof Number n) {
            return n.longValue();
        }
        if (requestId != null) {
            try {
                return Long.valueOf(String.valueOf(requestId));
            } catch (NumberFormatException ignore) {
                // fallthrough
            }
        }
        throw new BizErrorException(502, "提交换队申请：下游未返回 requestId");
    }

    public TeamChangeRequestVO current(Long driverId) {
        CoreResponseVo<CapacityDriverTeamChangeRequestVO> resp = capacityTeamChangeClient.current(driverId);
        unwrap(resp, "查询换队申请");
        CapacityDriverTeamChangeRequestVO row = resp.getData();
        if (row == null) {
            return null;
        }
        TeamChangeRequestVO out = new TeamChangeRequestVO();
        out.setId(row.getId());
        out.setStatus(row.getStatus());
        out.setRequestedAt(row.getRequestedAt());
        out.setRequestReason(row.getRequestReason());
        out.setFromCompanyId(row.getFromTeamId());
        out.setFromTeamName(row.getFromTeamName());
        out.setToCompanyId(row.getToTeamId());
        out.setToTeamName(row.getToTeamName());
        out.setReviewedAt(row.getReviewedAt());
        out.setReviewedBy(row.getReviewedBy());
        out.setReviewReason(row.getReviewReason());
        return out;
    }

    public void cancel(Long driverId, Long requestId) {
        Map<String, Object> body = new HashMap<>();
        body.put("driverId", driverId);
        body.put("requestedBy", String.valueOf(driverId));
        CoreResponseVo<Void> resp = capacityTeamChangeClient.cancel(requestId, body);
        if (resp != null && resp.getCode() != null && resp.getCode() != 200) {
            String msg = resp.getMsg() == null ? "操作失败" : resp.getMsg();
            if (msg.contains("不允许撤销")) {
                throw new BizErrorException(409, msg);
            }
            if (msg.contains("禁止操作其他司机")) {
                throw new BizErrorException(403, msg);
            }
            if (msg.contains("不存在")) {
                throw new BizErrorException(404, msg);
            }
            throw new BizErrorException(400, msg);
        }
        unwrap(resp, "撤销并恢复接单");
    }

    /**
     * 换队申请页「当前归属」信息。
     *
     * <p>优先展示司机当前 {@code companyId} 对应的公司/车队；若司机已在换队流程中被解绑（{@code companyId=null}），
     * 则尝试从「当前/最新申请」的 {@code fromCompanyId} 回填原归属用于展示。</p>
     */
    public DriverBelongingVO belonging(Long driverId) {
        CoreResponseVo<CapacityDriverDetail> driverResp = capacityDriverClient.getDriver(driverId);
        unwrap(driverResp, "查询司机归属");
        CapacityDriverDetail d = driverResp.getData();
        if (d == null) {
            throw new BizErrorException(502, "查询司机归属：下游未返回司机信息");
        }

        Long fromCompanyId = d.getCompanyId();
        if (fromCompanyId == null) {
            CoreResponseVo<CapacityDriverTeamChangeRequestVO> reqResp = capacityTeamChangeClient.current(driverId);
            unwrap(reqResp, "查询换队申请");
            CapacityDriverTeamChangeRequestVO req = reqResp.getData();
            if (req != null) {
                fromCompanyId = req.getFromTeamId();
            }
        }

        CapacityCompanyRow c = null;
        if (fromCompanyId != null) {
            CoreResponseVo<CapacityCompanyRow> compResp = capacityDriverClient.getCompany(fromCompanyId);
            // 公司被删/不存在时仅忽略名称展示，不影响其它字段
            if (compResp != null && Objects.equals(compResp.getCode(), 200)) {
                c = compResp.getData();
            }
        }

        DriverBelongingVO out = new DriverBelongingVO();
        out.setCityCode(d.getCityCode());
        out.setCityName(d.getCityName());
        out.setFromCompanyId(fromCompanyId);
        out.setFromCompanyName(c == null ? null : c.getCompanyName());
        out.setFromTeamId(c == null ? null : c.getTeamId());
        out.setFromTeamName(c == null ? null : (StringUtils.hasText(c.getTeam()) ? c.getTeam() : c.getCompanyName()));
        out.setCanAcceptOrder(Objects.equals(d.getCanAcceptOrder(), 1));
        out.setMonitorStatus(d.getMonitorStatus());
        return out;
    }

    private CompanySearchItemVO toCompanySearchItem(CapacityCompanyRow r) {
        CompanySearchItemVO vo = new CompanySearchItemVO();
        vo.setCompanyId(r.getId());
        vo.setCompanyName(r.getCompanyName());
        vo.setTeamId(r.getTeamId());
        vo.setTeam(r.getTeam());
        vo.setCityCode(r.getCityCode());
        vo.setCityName(r.getCityName());
        vo.setProvinceCode(r.getProvinceCode());
        vo.setProvinceName(r.getProvinceName());
        return vo;
    }

    private static void unwrap(CoreResponseVo<?> resp, String action) {
        if (resp == null) {
            throw new BizErrorException(502, action + "：下游响应为空");
        }
        Integer code = resp.getCode();
        if (!Objects.equals(code, 200)) {
            int c = code == null ? 502 : code;
            String msg = resp.getMsg();
            throw new BizErrorException(c, msg == null ? action + "失败" : msg);
        }
    }
}

