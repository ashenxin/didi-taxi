package com.sx.adminapi.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sx.adminapi.client.CapacityClient;
import com.sx.adminapi.common.enums.ExceptionCode;
import com.sx.adminapi.common.exception.BizErrorException;
import com.sx.adminapi.security.AdminDataScope;
import com.sx.adminapi.security.AdminLoginUser;
import com.sx.adminapi.model.capacity.AdminCarVO;
import com.sx.adminapi.model.capacity.AdminCompanyVO;
import com.sx.adminapi.model.capacity.AdminDriverVO;
import com.sx.adminapi.model.capacity.AdminPageVO;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 运力 BFF 服务：公司 / 司机 / 车辆分页；请求参数中的省、市与 {@link AdminDataScope} 合并后再调 {@link com.sx.adminapi.client.CapacityClient}。
 */
@Service
public class AdminCapacityService {

    private final CapacityClient capacityClient;
    private final ObjectMapper objectMapper;

    public AdminCapacityService(CapacityClient capacityClient, ObjectMapper objectMapper) {
        this.capacityClient = capacityClient;
        this.objectMapper = objectMapper;
    }

    /**
     * 公司分页；{@code provinceCode}/{@code cityCode} 经 {@link AdminDataScope#mergeRegionForQuery} 与当前用户域合并。
     */
    @SuppressWarnings("unchecked")
    public AdminPageVO<AdminCompanyVO> companyPage(Integer pageNo, Integer pageSize, String provinceCode, String cityCode, String companyNo, String companyName) {
        AdminLoginUser login = AdminDataScope.requireUser();
        AdminDataScope.RegionQuery rq = AdminDataScope.mergeRegionForQuery(login, provinceCode, cityCode);

        Map<String, Object> params = new HashMap<>();
        params.put("pageNo", pageNo);
        params.put("pageSize", pageSize);
        putIfNotBlank(params, "provinceCode", rq.provinceCode());
        putIfNotBlank(params, "cityCode", rq.cityCode());
        putIfNotBlank(params, "companyNo", companyNo);
        putIfNotBlank(params, "companyName", companyName);

        Map<String, Object> wrapper = capacityClient.companyPage(params);
        Map<String, Object> data = castMap(unwrapData(wrapper));
        return toPage(data, AdminCompanyVO.class, pageNo, pageSize);
    }

    /**
     * 司机分页；地区参数合并规则同 {@link #companyPage}。
     */
    @SuppressWarnings("unchecked")
    public AdminPageVO<AdminDriverVO> driverPage(Integer pageNo,
                                                 Integer pageSize,
                                                 Long companyId,
                                                 String name,
                                                 String phone,
                                                 Integer online,
                                                 String provinceCode,
                                                 String cityCode) {
        AdminLoginUser login = AdminDataScope.requireUser();
        AdminDataScope.RegionQuery rq = AdminDataScope.mergeRegionForQuery(login, provinceCode, cityCode);

        Map<String, Object> params = new HashMap<>();
        params.put("pageNo", pageNo);
        params.put("pageSize", pageSize);
        if (companyId != null) {
            params.put("companyId", companyId);
        }
        putIfNotBlank(params, "name", name);
        putIfNotBlank(params, "phone", phone);
        if (online != null) {
            params.put("online", online);
        }
        putIfNotBlank(params, "provinceCode", rq.provinceCode());
        putIfNotBlank(params, "cityCode", rq.cityCode());

        Map<String, Object> wrapper = capacityClient.driverPage(params);
        Map<String, Object> data = castMap(unwrapData(wrapper));
        return toPage(data, AdminDriverVO.class, pageNo, pageSize);
    }

    /**
     * 指定司机名下车辆；先 {@link #fetchDriverOrThrow} 再 {@link AdminDataScope#assertDriverCityReadable}，通过后才查车。
     */
    @SuppressWarnings("unchecked")
    public AdminPageVO<AdminCarVO> carsByDriver(Long driverId, Integer pageNo, Integer pageSize) {
        AdminDriverVO driver = fetchDriverOrThrow(driverId);
        AdminDataScope.assertDriverCityReadable(AdminDataScope.requireUser(), driver.getCityCode());

        Map<String, Object> params = new HashMap<>();
        params.put("pageNo", pageNo);
        params.put("pageSize", pageSize);

        Map<String, Object> wrapper = capacityClient.carsByDriver(driverId, params);
        Map<String, Object> data = castMap(unwrapData(wrapper));
        return toPage(data, AdminCarVO.class, pageNo, pageSize);
    }

    /** Feign 拉取司机；用于车辆列表前的域校验。 */
    private AdminDriverVO fetchDriverOrThrow(Long driverId) {
        Map<String, Object> wrapper = capacityClient.driverDetail(driverId);
        Object data = unwrapData(wrapper);
        if (data == null) {
            throw new BizErrorException(ExceptionCode.NOT_FOUND.getValue(), "司机不存在");
        }
        return objectMapper.convertValue(data, AdminDriverVO.class);
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
            String msg = String.valueOf(wrapper.getOrDefault("msg", "服务暂时不可用，请稍后重试"));
            int errorCode;
            try {
                errorCode = Integer.parseInt(String.valueOf(code));
            } catch (NumberFormatException e) {
                errorCode = ExceptionCode.SERVER_ERROR.getValue();
            }
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

