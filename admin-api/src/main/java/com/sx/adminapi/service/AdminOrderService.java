package com.sx.adminapi.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sx.adminapi.client.OrderClient;
import com.sx.adminapi.client.PassengerClient;
import com.sx.adminapi.common.enums.ExceptionCode;
import com.sx.adminapi.common.exception.BizErrorException;
import com.sx.adminapi.model.order.AdminOrderDetailVO;
import com.sx.adminapi.security.AdminDataScope;
import com.sx.adminapi.security.AdminLoginUser;
import com.sx.adminapi.model.order.AdminOrderEventVO;
import com.sx.adminapi.model.order.AdminOrderPageVO;
import com.sx.adminapi.model.order.AdminOrderVO;
import feign.FeignException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Service
@Slf4j
public class AdminOrderService {

    private final OrderClient orderClient;
    private final PassengerClient passengerClient;
    private final ObjectMapper objectMapper;

    public AdminOrderService(OrderClient orderClient, PassengerClient passengerClient, ObjectMapper objectMapper) {
        this.orderClient = orderClient;
        this.passengerClient = passengerClient;
        this.objectMapper = objectMapper;
    }

    /**
     * 后台管理端分页查询订单列表（仅补充状态中文等轻量字段）。
     *
     * 列表不返回乘客 ID/手机号（避免 N+1 调 passenger），详情页再聚合手机号。
     *
     * 过滤条件：订单号、乘客手机号、地区、状态、下单时间区间。省、市查询参数与当前登录用户数据域合并（{@link AdminDataScope#mergeRegionForQuery}）。
     * 若仅传手机号（未传订单号），会先到乘客服务 {@code by-phone} 换取 passengerId 再查订单；乘客不存在则空页。
     *
     * @param orderNo         订单号（可选）
     * @param phone           乘客手机号（可选）
     * @param provinceCode    省份编码（可选）
     * @param cityCode        城市编码（可选）
     * @param status          订单状态（可选）
     * @param createdAtStart  下单时间起（可选）
     * @param createdAtEnd    下单时间止（可选）
     * @param pageNo          页码（从 1 开始）
     * @param pageSize        每页大小
     * @return 分页数据（list/total/pageNo/pageSize）
     */
    @SuppressWarnings("unchecked")
    public AdminOrderPageVO page(String orderNo,
                                 String phone,
                                 String provinceCode,
                                 String cityCode,
                                 Integer status,
                                 LocalDateTime createdAtStart,
                                 LocalDateTime createdAtEnd,
                                 Integer pageNo,
                                 Integer pageSize) {
        Long passengerId = null;
        if ((orderNo == null || orderNo.isBlank()) && phone != null && !phone.isBlank()) {
            passengerId = queryPassengerIdByPhone(phone);
            if (passengerId == null) {
                return emptyPage(pageNo, pageSize);
            }
        }

        AdminLoginUser login = AdminDataScope.requireUser();
        AdminDataScope.RegionQuery rq = AdminDataScope.mergeRegionForQuery(login, provinceCode, cityCode);

        Map<String, Object> params = new HashMap<>();
        params.put("pageNo", pageNo);
        params.put("pageSize", pageSize);
        appendQuery(params, "orderNo", orderNo);
        appendQuery(params, "provinceCode", rq.provinceCode());
        appendQuery(params, "cityCode", rq.cityCode());
        appendQuery(params, "createdAtStart", formatDateTime(createdAtStart));
        appendQuery(params, "createdAtEnd", formatDateTime(createdAtEnd));
        if (status != null) {
            params.put("status", status);
        }
        if (passengerId != null) {
            params.put("passengerId", passengerId);
        }

        Map<String, Object> wrapper = orderClient.page(params);
        Map<String, Object> result = castMap(unwrapData(wrapper));
        if (result == null || result.isEmpty()) {
            return emptyPage(pageNo, pageSize);
        }

        List<AdminOrderVO> list = convertToList(result.get("list"), AdminOrderVO.class);
        if (list == null) {
            list = new ArrayList<>();
        }
        for (AdminOrderVO row : list) {
            row.setStatusText(statusText(row.getStatus()));
            row.setPassengerId(null);
            row.setPassengerPhone(null);
        }

        AdminOrderPageVO resp = new AdminOrderPageVO();
        resp.setList(list);
        resp.setTotal(toLong(result.get("total"), 0L));
        resp.setPageNo(toInt(result.get("pageNo"), pageNo));
        resp.setPageSize(toInt(result.get("pageSize"), pageSize));
        return resp;
    }

    /**
     * 按订单号查询订单详情与事件时间线（用于后台订单详情页）。
     *
     * 当下游订单服务返回 404 时会转换为业务异常“订单不存在”。拉齐主单后按订单省市做 {@link AdminDataScope#assertOrderReadable}。
     *
     * @param orderNo 订单号
     * @return 订单详情（订单主体 + 事件列表）
     */
    public AdminOrderDetailVO detail(String orderNo) {
        AdminOrderVO order;
        try {
            Map<String, Object> wrapper = orderClient.detail(orderNo);
            order = convertTo(unwrapData(wrapper), AdminOrderVO.class);
        } catch (FeignException.NotFound e) {
            throw new BizErrorException(ExceptionCode.NOT_FOUND.getValue(), "订单不存在");
        }
        if (order == null || order.getOrderNo() == null || order.getOrderNo().isBlank()) {
            throw new BizErrorException(ExceptionCode.NOT_FOUND.getValue(), "订单不存在");
        }
        AdminDataScope.assertOrderReadable(
                AdminDataScope.requireUser(), order.getProvinceCode(), order.getCityCode());
        order.setStatusText(statusText(order.getStatus()));

        Map<String, Object> eventWrapper = orderClient.events(orderNo);
        List<AdminOrderEventVO> events = convertToList(unwrapData(eventWrapper), AdminOrderEventVO.class);
        if (events == null) {
            events = new ArrayList<>();
        }
        for (AdminOrderEventVO event : events) {
            event.setEventTypeText(eventTypeText(event.getEventType()));
            event.setFromStatusText(statusText(event.getFromStatus()));
            event.setToStatusText(statusText(event.getToStatus()));
            event.setOperatorTypeText(operatorTypeText(event.getOperatorType()));
        }

        if (order.getPassengerId() != null) {
            String phone = queryPassengerPhoneById(String.valueOf(order.getPassengerId()));
            if (phone != null) {
                order.setPassengerPhone(phone);
            }
        }

        AdminOrderDetailVO resp = new AdminOrderDetailVO();
        resp.setOrder(order);
        resp.setEvents(events);
        log.info("管理端订单详情已加载 orderNo={} events={}", orderNo, events.size());
        return resp;
    }

    /**
     * 通过手机号查询乘客ID。
     *
     * 用于后台订单列表按手机号过滤：先解析出 passengerId 再去订单服务筛选。
     *
     * @param phone 手机号
     * @return 乘客ID，不存在则返回 null
     */
    @SuppressWarnings("unchecked")
    private Long queryPassengerIdByPhone(String phone) {
        try {
            Map<String, Object> wrapper = passengerClient.byPhone(phone);
            Map<String, Object> row = castMap(unwrapData(wrapper));
            if (row == null || row.get("id") == null) {
                return null;
            }
            return Long.valueOf(String.valueOf(row.get("id")));
        } catch (FeignException.NotFound e) {
            return null;
        }
    }

    /**
     * 通过乘客ID查询手机号。
     *
     * 仅用于订单详情页补全展示手机号；列表不再调用。
     *
     * @param passengerId 乘客ID
     * @return 手机号，不存在则返回 null
     */
    @SuppressWarnings("unchecked")
    private String queryPassengerPhoneById(String passengerId) {
        try {
            Map<String, Object> wrapper = passengerClient.get(passengerId);
            Map<String, Object> row = castMap(unwrapData(wrapper));
            return row == null ? null : Objects.toString(row.get("phone"), null);
        } catch (FeignException.NotFound e) {
            return null;
        }
    }

    /**
     * 构造空分页响应（保持 pageNo/pageSize 回显）。
     */
    private AdminOrderPageVO emptyPage(Integer pageNo, Integer pageSize) {
        AdminOrderPageVO result = new AdminOrderPageVO();
        result.setPageNo(pageNo);
        result.setPageSize(pageSize);
        return result;
    }

    /**
     * 追加查询参数（忽略 null/空白字符串）。
     */
    private void appendQuery(Map<String, Object> params, String key, String value) {
        if (value != null && !value.isBlank()) {
            params.put(key, value);
        }
    }

    /**
     * 将时间格式化为下游接口约定的字符串（yyyy-MM-dd HH:mm:ss）。
     */
    private String formatDateTime(LocalDateTime value) {
        if (value == null) {
            return null;
        }
        return value.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
    }

    /**
     * 解包下游统一返回结构：code != 200 时抛出业务异常。
     */
    @SuppressWarnings("unchecked")
    private Object unwrapData(Map<String, Object> wrapper) {
        if (wrapper == null) {
            return null;
        }
        Object code = wrapper.get("code");
        if (code != null && !"200".equals(String.valueOf(code))) {
            throw new BizErrorException(ExceptionCode.SERVER_ERROR.getValue(), "服务暂时不可用，请稍后重试");
        }
        Object data = wrapper.get("data");
        return data;
    }

    /**
     * 将反序列化后的对象安全转为 Map（不匹配则返回 null）。
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> castMap(Object data) {
        if (data instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        return null;
    }

    /**
     * 将 data 转为目标类型对象（基于 Jackson convertValue）。
     */
    private <T> T convertTo(Object data, Class<T> clazz) {
        if (data == null) {
            return null;
        }
        return objectMapper.convertValue(data, clazz);
    }

    /**
     * 将 data 转为目标类型列表（基于 Jackson convertValue）。
     */
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

    /**
     * 尝试将对象转为 Long，失败则返回默认值。
     */
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

    /**
     * 尝试将对象转为 Integer，失败则返回默认值。
     */
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

    /**
     * 订单状态码转中文展示文案。
     */
    private String statusText(Integer status) {
        if (status == null) {
            return "未知";
        }
        return switch (status) {
            case 0 -> "已创建";
            case 1 -> "已派单";
            case 2 -> "司机已接单";
            case 3 -> "司机已到达";
            case 4 -> "行程中";
            case 5 -> "已完成";
            case 6 -> "已取消";
            default -> "未知";
        };
    }

    /**
     * 事件类型码/枚举名转中文展示文案（兼容数字/字符串）。
     */
    private String eventTypeText(String eventType) {
        if (eventType == null) {
            return "未知事件";
        }
        String normalized = eventType.trim().toUpperCase();
        return switch (normalized) {
            case "0", "CREATE" -> "创建订单";
            case "1", "ASSIGN" -> "派单";
            case "2", "ACCEPT" -> "司机接单";
            case "3", "ARRIVE" -> "司机到达";
            case "4", "START" -> "开始行程";
            case "5", "FINISH", "COMPLETE" -> "结束行程";
            case "6", "CANCEL" -> "取消订单";
            case "7", "PAY", "PAY_SUCCESS" -> "支付成功";
            default -> "其他事件";
        };
    }

    /**
     * 操作人类型转中文展示文案。
     */
    private String operatorTypeText(Integer operatorType) {
        if (operatorType == null) {
            return "系统";
        }
        return switch (operatorType) {
            case 0 -> "系统";
            case 1 -> "乘客";
            case 2 -> "司机";
            case 3 -> "客服";
            default -> "其他";
        };
    }
}
