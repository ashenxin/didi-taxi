package com.sx.adminapi.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sx.adminapi.client.CalculateClient;
import com.sx.adminapi.common.enums.ExceptionCode;
import com.sx.adminapi.common.exception.BizErrorException;
import com.sx.adminapi.model.capacity.AdminPageVO;
import com.sx.adminapi.model.pricing.AdminFareRuleVO;
import com.sx.adminapi.model.pricing.FareRuleUpsertBody;
import com.sx.adminapi.security.AdminDataScope;
import com.sx.adminapi.security.AdminLoginUser;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 计价规则 BFF：分页/详情/增删改均经 {@link AdminDataScope} 裁剪查询条件或写 body 中的省、市。
 */
@Service
@Slf4j
public class AdminPricingService {

    private final CalculateClient calculateClient;
    private final ObjectMapper objectMapper;

    public AdminPricingService(CalculateClient calculateClient, ObjectMapper objectMapper) {
        this.calculateClient = calculateClient;
        this.objectMapper = objectMapper;
    }

    /** 规则分页；{@code provinceCode}/{@code cityCode} 与登录域合并。 */
    @SuppressWarnings("unchecked")
    public AdminPageVO<AdminFareRuleVO> page(Integer pageNo,
                                             Integer pageSize,
                                             String provinceCode,
                                             String cityCode,
                                             String productCode,
                                             String ruleName,
                                             Integer active) {
        AdminLoginUser login = AdminDataScope.requireUser();
        AdminDataScope.RegionQuery rq = AdminDataScope.mergeRegionForQuery(login, provinceCode, cityCode);

        Map<String, Object> params = new HashMap<>();
        params.put("pageNo", pageNo);
        params.put("pageSize", pageSize);
        putIfNotBlank(params, "provinceCode", rq.provinceCode());
        putIfNotBlank(params, "cityCode", rq.cityCode());
        putIfNotBlank(params, "productCode", productCode);
        putIfNotBlank(params, "ruleName", ruleName);
        if (active != null) {
            params.put("active", active);
        }

        Map<String, Object> wrapper = calculateClient.page(params);
        Map<String, Object> data = castMap(unwrapData(wrapper));

        AdminPageVO<AdminFareRuleVO> result = new AdminPageVO<>();
        if (data == null) {
            result.setList(List.of());
            result.setTotal(0L);
            result.setPageNo(pageNo);
            result.setPageSize(pageSize);
            return result;
        }
        List<AdminFareRuleVO> list = convertToList(data.get("list"), AdminFareRuleVO.class);
        if (list == null) {
            list = new ArrayList<>();
        }
        result.setList(list);
        result.setTotal(toLong(data.get("total"), 0L));
        result.setPageNo(toInt(data.get("pageNo"), pageNo));
        result.setPageSize(toInt(data.get("pageSize"), pageSize));
        return result;
    }

    /** 规则详情；跨域 {@link AdminDataScope#assertFareRuleReadable}。 */
    public AdminFareRuleVO detail(Long id) {
        Map<String, Object> wrapper = calculateClient.detail(id);
        Object data = unwrapData(wrapper);
        if (data == null) {
            return null;
        }
        AdminFareRuleVO vo = objectMapper.convertValue(data, AdminFareRuleVO.class);
        AdminDataScope.assertFareRuleReadable(AdminDataScope.requireUser(), vo.getProvinceCode(), vo.getCityCode());
        return vo;
    }

    /** 创建；body 深拷贝后 {@link AdminDataScope#scopeFareRuleWrite}。 */
    public Long create(FareRuleUpsertBody body) {
        FareRuleUpsertBody scoped = objectMapper.convertValue(objectMapper.convertValue(body, Map.class), FareRuleUpsertBody.class);
        AdminDataScope.scopeFareRuleWrite(AdminDataScope.requireUser(), scoped);
        Map<String, Object> wrapper = calculateClient.create(objectMapper.convertValue(scoped, Map.class));
        Object data = unwrapData(wrapper);
        if (data == null) {
            return null;
        }
        Long id = Long.valueOf(String.valueOf(data));
        log.info("admin fare rule created id={} city={}", id, scoped.getCityCode());
        return id;
    }

    /** 更新；先 {@link #detailForMutation} 做域校验，再锁定 body 省、市。 */
    public void update(Long id, FareRuleUpsertBody body) {
        AdminFareRuleVO existing = detailForMutation(id);
        AdminDataScope.assertFareRuleReadable(AdminDataScope.requireUser(), existing.getProvinceCode(), existing.getCityCode());
        FareRuleUpsertBody scoped = objectMapper.convertValue(objectMapper.convertValue(body, Map.class), FareRuleUpsertBody.class);
        AdminDataScope.scopeFareRuleWrite(AdminDataScope.requireUser(), scoped);
        Map<String, Object> wrapper = calculateClient.update(id, objectMapper.convertValue(scoped, Map.class));
        unwrapData(wrapper);
        log.info("admin fare rule updated id={}", id);
    }

    /** 删除；域校验同 {@link #update}。 */
    public void delete(Long id) {
        AdminFareRuleVO existing = detailForMutation(id);
        AdminDataScope.assertFareRuleReadable(AdminDataScope.requireUser(), existing.getProvinceCode(), existing.getCityCode());
        Map<String, Object> wrapper = calculateClient.delete(id);
        unwrapData(wrapper);
        log.info("admin fare rule deleted id={}", id);
    }

    /** 写操作前拉取规则；不经过 {@link #detail(Long)}，避免重复做可读性断言。 */
    private AdminFareRuleVO detailForMutation(Long id) {
        Map<String, Object> wrapper = calculateClient.detail(id);
        Object data = unwrapData(wrapper);
        if (data == null) {
            throw new BizErrorException(ExceptionCode.NOT_FOUND.getValue(), "规则不存在");
        }
        return objectMapper.convertValue(data, AdminFareRuleVO.class);
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
            // calculate-service 目前 HTTP 始终 200，这里仅按 body.code 判断
            String msg = String.valueOf(wrapper.getOrDefault("msg", "服务暂时不可用，请稍后重试"));
            Integer errorCode = Integer.parseInt(String.valueOf(code));
            throw new BizErrorException(errorCode, msg);
        }
        return wrapper.get("data");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> castMap(Object data) {
        if (data instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        return null;
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

