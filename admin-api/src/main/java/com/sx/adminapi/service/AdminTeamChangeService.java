package com.sx.adminapi.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sx.adminapi.client.CapacityClient;
import com.sx.adminapi.common.enums.ExceptionCode;
import com.sx.adminapi.common.exception.BizErrorException;
import com.sx.adminapi.security.AdminDataScope;
import com.sx.adminapi.security.AdminLoginUser;
import com.sx.adminapi.model.capacity.AdminDriverTeamChangeRequestVO;
import com.sx.adminapi.model.capacity.AdminPageVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 换队申请 BFF：列表/待审数携带登录省、市至 capacity；详情与审核依据 VO 的 {@code driverCityCode} 做数据域断言。
 */
@Service
@Slf4j
public class AdminTeamChangeService {

    private final CapacityClient capacityClient;
    private final ObjectMapper objectMapper;

    public AdminTeamChangeService(CapacityClient capacityClient, ObjectMapper objectMapper) {
        this.capacityClient = capacityClient;
        this.objectMapper = objectMapper;
    }

    /**
     * 换队申请分页；{@code provinceCode}/{@code cityCode} 仅取登录域（{@code mergeRegionForQuery(login, null, null)}），不下发给前端拼装。
     */
    @SuppressWarnings("unchecked")
    public AdminPageVO<AdminDriverTeamChangeRequestVO> page(Integer pageNo,
                                                            Integer pageSize,
                                                            String status,
                                                            Long driverId,
                                                            String driverPhone,
                                                            String startTime,
                                                            String endTime) {
        AdminLoginUser login = AdminDataScope.requireUser();
        AdminDataScope.RegionQuery rq = AdminDataScope.mergeRegionForQuery(login, null, null);

        Map<String, Object> params = new HashMap<>();
        params.put("pageNo", pageNo);
        params.put("pageSize", pageSize);
        putIfNotBlank(params, "status", status);
        if (driverId != null) {
            params.put("driverId", driverId);
        }
        putIfNotBlank(params, "driverPhone", driverPhone);
        putIfNotBlank(params, "startTime", startTime);
        putIfNotBlank(params, "endTime", endTime);
        putIfNotBlank(params, "provinceCode", rq.provinceCode());
        putIfNotBlank(params, "cityCode", rq.cityCode());

        Map<String, Object> wrapper = capacityClient.driverTeamChangePage(params);
        Map<String, Object> data = castMap(unwrapData(wrapper));
        return toPage(data, AdminDriverTeamChangeRequestVO.class, pageNo, pageSize);
    }

    /**
     * 换队详情；依赖下游 VO 中 {@code driverCityCode}，不在当前用户数据域内时与「司机不存在」同类掩蔽处理。
     */
    public AdminDriverTeamChangeRequestVO detail(Long id) {
        Map<String, Object> wrapper = capacityClient.driverTeamChangeDetail(id);
        Object data = unwrapData(wrapper);
        if (data == null) {
            return null;
        }
        AdminDriverTeamChangeRequestVO vo = objectMapper.convertValue(data, AdminDriverTeamChangeRequestVO.class);
        AdminDataScope.assertDriverCityReadable(AdminDataScope.requireUser(), vo.getDriverCityCode());
        return vo;
    }

    /**
     * 待审核（PENDING）申请总数（菜单角标）；统计范围与 {@link #page} 一致，受登录省/市约束。
     */
    public long pendingCount() {
        AdminLoginUser login = AdminDataScope.requireUser();
        AdminDataScope.RegionQuery rq = AdminDataScope.mergeRegionForQuery(login, null, null);

        Map<String, Object> params = new HashMap<>();
        params.put("pageNo", 1);
        params.put("pageSize", 1);
        params.put("status", "PENDING");
        putIfNotBlank(params, "provinceCode", rq.provinceCode());
        putIfNotBlank(params, "cityCode", rq.cityCode());
        Map<String, Object> wrapper = capacityClient.driverTeamChangePage(params);
        Map<String, Object> data = castMap(unwrapData(wrapper));
        if (data == null) {
            return 0L;
        }
        return toLong(data.get("total"), 0L);
    }

    /** 审核通过；{@link #assertTeamChangeInScope} 防纵向越权。 */
    public void approve(Long id, String reviewReason, String reviewedBy) {
        assertTeamChangeInScope(id);
        Map<String, Object> body = new HashMap<>();
        if (reviewReason != null && !reviewReason.isBlank()) {
            body.put("reviewReason", reviewReason);
        }
        Map<String, Object> wrapper = capacityClient.approveDriverTeamChange(id, body, reviewedBy);
        assertSuccess(wrapper);
        log.info("admin team change approved id={} by={}", id, reviewedBy);
    }

    /** 审核拒绝；域校验同 {@link #approve}。 */
    public void reject(Long id, String reviewReason, String reviewedBy) {
        assertTeamChangeInScope(id);
        Map<String, Object> body = new HashMap<>();
        body.put("reviewReason", reviewReason);
        Map<String, Object> wrapper = capacityClient.rejectDriverTeamChange(id, body, reviewedBy);
        assertSuccess(wrapper);
        log.info("admin team change rejected id={} by={}", id, reviewedBy);
    }

    /** 拉详情（不做可读断言）后校验司机城市是否在域内。 */
    private void assertTeamChangeInScope(Long id) {
        AdminDriverTeamChangeRequestVO vo = detailUnchecked(id);
        if (vo == null) {
            throw new BizErrorException(ExceptionCode.NOT_FOUND.getValue(), "申请不存在");
        }
        AdminDataScope.assertDriverCityReadable(AdminDataScope.requireUser(), vo.getDriverCityCode());
    }

    /** 仅反序列化，不调用 {@link #detail}，避免公开详情上的双重断言。 */
    private AdminDriverTeamChangeRequestVO detailUnchecked(Long id) {
        Map<String, Object> wrapper = capacityClient.driverTeamChangeDetail(id);
        Object data = unwrapData(wrapper);
        if (data == null) {
            return null;
        }
        return objectMapper.convertValue(data, AdminDriverTeamChangeRequestVO.class);
    }

    private void putIfNotBlank(Map<String, Object> params, String key, String value) {
        if (value != null && !value.isBlank()) {
            params.put(key, value);
        }
    }

    @SuppressWarnings("unchecked")
    private Object unwrapData(Map<String, Object> wrapper) {
        if (wrapper == null) {
            return null;
        }
        Object code = wrapper.get("code");
        if (code != null && !"200".equals(String.valueOf(code))) {
            throw new BizErrorException(ExceptionCode.SERVER_ERROR.getValue(), "服务暂时不可用，请稍后重试");
        }
        return wrapper.get("data");
    }

    private void assertSuccess(Map<String, Object> wrapper) {
        unwrapData(wrapper);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> castMap(Object data) {
        if (data instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        return null;
    }

    private <T> AdminPageVO<T> toPage(Map<String, Object> data, Class<T> clazz, Integer pageNo, Integer pageSize) {
        AdminPageVO<T> result = new AdminPageVO<>();
        if (data == null) {
            result.setList(List.of());
            result.setTotal(0L);
            result.setPageNo(pageNo);
            result.setPageSize(pageSize);
            return result;
        }
        List<T> list = convertToList(data.get("list"), clazz);
        if (list == null) {
            list = new ArrayList<>();
        }
        result.setList(list);
        result.setTotal(toLong(data.get("total"), 0L));
        result.setPageNo(toInt(data.get("pageNo"), pageNo));
        result.setPageSize(toInt(data.get("pageSize"), pageSize));
        return result;
    }

    private <T> List<T> convertToList(Object data, Class<T> clazz) {
        if (!(data instanceof List<?> list)) {
            return null;
        }
        List<T> result = new ArrayList<>(list.size());
        for (Object item : list) {
            result.add(objectMapper.convertValue(item, clazz));
        }
        return result;
    }

    private Long toLong(Object value, Long defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private Integer toInt(Object value, Integer defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }
}
