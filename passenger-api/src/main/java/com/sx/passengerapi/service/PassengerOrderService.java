package com.sx.passengerapi.service;

import com.sx.passengerapi.client.CalculateClient;
import com.sx.passengerapi.client.CapacityDispatchClient;
import com.sx.passengerapi.client.MapClient;
import com.sx.passengerapi.client.OrderClient;
import com.sx.passengerapi.common.exception.BizErrorException;
import com.sx.passengerapi.model.calculate.EstimateFareBody;
import com.sx.passengerapi.model.calculate.EstimateFareResult;
import com.sx.passengerapi.model.capacity.NearestDriverResult;
import com.sx.passengerapi.model.map.GeocodeDemoResponse;
import com.sx.passengerapi.model.map.Point;
import com.sx.passengerapi.model.map.RouteRequest;
import com.sx.passengerapi.model.map.RouteResponse;
import com.sx.passengerapi.model.order.CancelOrderRequest;
import com.sx.passengerapi.model.order.CreateAndAssignOrderBody;
import com.sx.passengerapi.model.order.CreateAndAssignOrderResult;
import com.sx.passengerapi.model.order.CreateOrderResultV1;
import com.sx.passengerapi.model.order.OrderStatus;
import com.sx.passengerapi.model.order.PassengerOrderActionVO;
import com.sx.passengerapi.model.order.PassengerOrderDetailVO;
import com.sx.passengerapi.model.order.PassengerOrderDriverVO;
import com.sx.passengerapi.model.order.PassengerOrderListItemVO;
import com.sx.passengerapi.model.order.PassengerOrderListType;
import com.sx.passengerapi.model.order.PassengerOrderPageVO;
import com.sx.passengerapi.model.auth.PassengerLogoutResult;
import com.sx.passengerapi.model.order.PassengerOrderTimestamps;
import com.sx.passengerapi.model.ordercore.OrderPageData;
import com.sx.passengerapi.model.ordercore.AssignOrderBody;
import com.sx.passengerapi.model.ordercore.CancelOrderBody;
import com.sx.passengerapi.model.ordercore.CreateOrderBody;
import com.sx.passengerapi.model.ordercore.CreateOrderResult;
import com.sx.passengerapi.model.ordercore.OrderEventRow;
import com.sx.passengerapi.model.ordercore.Place;
import com.sx.passengerapi.model.capacity.PendingOrderIndexBody;
import com.sx.passengerapi.model.ordercore.TripOrderRow;
import com.sx.passengerapi.ws.PassengerWsNotifyService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
@Slf4j
public class PassengerOrderService {
    private static final Set<String> REDISPATCH_EVENT_TYPES = Set.of(
            "ORDER_DRIVER_REJECTED",
            "ORDER_DRIVER_CANCELLED_BEFORE_ARRIVE",
            "ORDER_OFFER_TIMED_OUT"
    );

    /** cityCode → 高德 geocode 可选 city 参数（中文/全拼/adcode 等，见高德文档） */
    private static final Map<String, String> CITY_CODE_TO_GEOCODE_CITY = Map.of(
            "330100", "杭州"
    );

    private final MapClient mapClient;
    private final CalculateClient calculateClient;
    private final OrderClient orderClient;
    private final CapacityDispatchClient capacityDispatchClient;

    private final PassengerWsNotifyService passengerWsNotifyService;

    public PassengerOrderService(MapClient mapClient,
                                 CalculateClient calculateClient,
                                 OrderClient orderClient,
                                 CapacityDispatchClient capacityDispatchClient,
                                 PassengerWsNotifyService passengerWsNotifyService) {
        this.mapClient = mapClient;
        this.calculateClient = calculateClient;
        this.orderClient = orderClient;
        this.capacityDispatchClient = capacityDispatchClient;
        this.passengerWsNotifyService = passengerWsNotifyService;
    }

    /**
     * 起终点缺经纬度时，调 map 地理编码补全；已带齐 lat/lng 则跳过（兼容地图 SDK 选点）。
     * 顺序：geocode 起点 → geocode 终点 → 后续 {@link #route} 驾车规划。
     */
    public void resolveCoordinatesByGeocodeIfNeeded(CreateAndAssignOrderBody body) {
        String geocodeCity = CITY_CODE_TO_GEOCODE_CITY.get(body.getCityCode());
        fillPlaceByGeocodeIfNeeded(body.getOrigin(), geocodeCity, "起点");
        fillPlaceByGeocodeIfNeeded(body.getDest(), geocodeCity, "终点");
    }

    private void fillPlaceByGeocodeIfNeeded(com.sx.passengerapi.model.order.Place place, String geocodeCity, String label) {
        if (place == null) {
            throw new BizErrorException(400, label + "不能为空");
        }
        if (place.getLat() != null && place.getLng() != null) {
            return;
        }
        String address = geocodeAddressLine(place);
        if (!StringUtils.hasText(address)) {
            throw new BizErrorException(400, label + "请提供 address 或 name 以供地理编码，或直接传 lat/lng");
        }
        GeocodeDemoResponse geo = geocodeOrThrow(address, geocodeCity, label);
        place.setLng(geo.getLng());
        place.setLat(geo.getLat());
    }

    private static String geocodeAddressLine(com.sx.passengerapi.model.order.Place place) {
        if (StringUtils.hasText(place.getAddress())) {
            return place.getAddress().trim();
        }
        if (StringUtils.hasText(place.getName())) {
            return place.getName().trim();
        }
        return "";
    }

    private GeocodeDemoResponse geocodeOrThrow(String address, String city, String label) {
        var resp = mapClient.geocode(address, StringUtils.hasText(city) ? city : null);
        if (resp == null) {
            throw new BizErrorException(502, "地图地理编码响应为空");
        }
        if (resp.getCode() == null || resp.getCode() != 200) {
            throw new BizErrorException(resp.getCode() == null ? 502 : resp.getCode(),
                    label + "地理编码失败: " + resp.getMsg());
        }
        GeocodeDemoResponse data = resp.getData();
        if (data == null || data.getLng() == null || data.getLat() == null) {
            throw new BizErrorException(502, label + "地理编码未返回坐标");
        }
        return data;
    }

    /**
     * 调用地图服务驾车路径规划（里程/时长）。
     *
     * 调用：{@code map-service POST /api/v1/map/demo/amap-driving}
     */
    public RouteResponse route(CreateAndAssignOrderBody body) {
        RouteRequest req = new RouteRequest();
        req.setOrigin(toPoint(body.getOrigin()));
        req.setDest(toPoint(body.getDest()));

        var resp = mapClient.drivingRoute(req);
        if (resp == null) {
            throw new BizErrorException(502, "地图服务响应为空");
        }
        if (resp.getCode() == null || resp.getCode() != 200) {
            throw new BizErrorException(resp.getCode() == null ? 502 : resp.getCode(), "地图服务调用失败: " + resp.getMsg());
        }
        return resp.getData();
    }

    /**
     * 调用计费服务进行费用预估。
     *
     * 调用：{@code calculate-service POST /api/v1/calculate/estimate}
     *
     * 入参依赖 route 的 distance/duration；MVP 先按 fare_rule 规则计算。
     */
    public EstimateFareResult estimate(CreateAndAssignOrderBody body, RouteResponse route, Long companyId) {
        if (companyId == null) {
            return null;
        }
        EstimateFareBody req = new EstimateFareBody();
        req.setCompanyId(companyId);
        req.setProvinceCode(body.getProvinceCode());
        req.setCityCode(body.getCityCode());
        req.setProductCode(body.getProductCode());
        req.setDistanceMeters(route == null ? null : route.getDistanceMeters());
        req.setDurationSeconds(route == null ? null : route.getDurationSeconds());

        var resp = calculateClient.estimate(req);
        if (resp == null) {
            throw new BizErrorException(502, "计费服务响应为空");
        }
        if (resp.getCode() != null && resp.getCode() == 404) {
            log.warn("估价：该公司无可用计价规则 companyId={} city={} product={}",
                    companyId, body.getCityCode(), body.getProductCode());
            return null;
        }
        if (resp.getCode() == null || resp.getCode() != 200) {
            throw new BizErrorException(resp.getCode() == null ? 502 : resp.getCode(), "计费服务调用失败: " + resp.getMsg());
        }
        return resp.getData();
    }

    /**
     * 创建订单主表（status=CREATED）并写入创建事件。
     *
     * 调用：{@code order-service POST /api/v1/orders}
     *
     * 当前实现会把 estimate 的 {@code estimatedAmount/ruleId} 透传给 order-service，
     * 便于订单侧留痕与后续对账。
     */
    public CreateOrderResult createOrder(CreateAndAssignOrderBody body, EstimateFareResult estimate, String idempotencyKey) {
        CreateOrderBody req = new CreateOrderBody();
        req.setPassengerId(body.getPassengerId());
        req.setProvinceCode(body.getProvinceCode());
        req.setCityCode(body.getCityCode());
        req.setProductCode(body.getProductCode());
        req.setOrigin(toOrderPlace(body.getOrigin()));
        req.setDest(toOrderPlace(body.getDest()));
        req.setEstimatedAmount(estimate == null ? null : estimate.getEstimatedAmount());
        req.setFareRuleId(estimate == null ? null : estimate.getRuleId());
        req.setFareRuleSnapshot(null);

        var resp = orderClient.create(idempotencyKey, req);
        if (resp == null) {
            throw new BizErrorException(502, "订单服务响应为空");
        }
        if (resp.getCode() == null || resp.getCode() != 200) {
            int code = resp.getCode() == null ? 502 : resp.getCode();
            String msg = (resp.getMsg() != null && !resp.getMsg().isBlank())
                    ? resp.getMsg()
                    : "订单创建失败";
            throw new BizErrorException(code, msg);
        }
        return resp.getData();
    }

    /**
     * 查询派单候选（最近司机）。
     *
     * 调用：{@code capacity-service GET /api/v1/dispatch/nearest-driver}
     *
     * MVP 约定：查不到司机时返回 {@code null}（capacity 用 404 表示“无可用司机”）。
     */
    public NearestDriverResult searchNearestDriver(CreateAndAssignOrderBody body) {
        Double olat = body.getOrigin() == null ? null : body.getOrigin().getLat();
        Double olng = body.getOrigin() == null ? null : body.getOrigin().getLng();
        var resp = capacityDispatchClient.nearestDriver(body.getCityCode(), body.getProductCode(), olat, olng, body.getPassengerId());
        if (resp == null) {
            throw new BizErrorException(502, "运力服务响应为空");
        }
        if (resp.getCode() != null && resp.getCode() == 404) {
            return null;
        }
        if (resp.getCode() == null || resp.getCode() != 200) {
            throw new BizErrorException(resp.getCode() == null ? 502 : resp.getCode(), "运力服务调用失败: " + resp.getMsg());
        }
        return resp.getData();
    }

    /**
     * 指派司机到订单（CREATED -> ASSIGNED）。
     *
     * 调用：{@code order-service POST /api/v1/orders/{orderNo}/assign}
     *
     * MVP：ETA 先用 route.duration 作为占位；后续接入 map.matrix 后再替换为“司机到上车点 ETA”。
     */
    public void assignOrder(String orderNo, NearestDriverResult driver, Long etaSeconds) {
        if (driver == null) {
            return;
        }
        AssignOrderBody req = new AssignOrderBody();
        req.setDriverId(driver.getDriverId());
        req.setCarId(driver.getCarId());
        req.setCompanyId(driver.getCompanyId());
        req.setEtaSeconds(etaSeconds);

        var resp = orderClient.assign(orderNo, req);
        if (resp == null) {
            throw new BizErrorException(502, "订单服务响应为空");
        }
        if (resp.getCode() == null || resp.getCode() != 200) {
            int code = resp.getCode() == null ? 502 : resp.getCode();
            String msg = (resp.getMsg() != null && !resp.getMsg().isBlank()) ? resp.getMsg() : "订单指派失败";
            throw new BizErrorException(code, msg);
        }
    }

    /**
     * 对外“一步下单”入口：HTTP 请求只保证订单创建成功，派单由 order Outbox + Kafka + capacity 异步推进。
     *
     * 返回结构沿用 {@link CreateAndAssignOrderResult}，但主路径不再同步执行 assign/openOffer。
     */
    public CreateAndAssignOrderResult createAndAssign(CreateAndAssignOrderBody body, String idempotencyKey) {
        CreateOrderResultV1 created = createTwoPhase(body, idempotencyKey);
        CreateAndAssignOrderResult out = new CreateAndAssignOrderResult();
        out.setOrderNo(created.getOrderNo());
        out.setStatus(created.getStatus());
        out.setAssignedDriver(created.getAssignedDriver());
        out.setRoute(created.getRoute());
        out.setEstimate(created.getEstimate());
        log.info("一步下单已创建，派单异步推进 orderNo={} passengerId={}", out.getOrderNo(), body.getPassengerId());
        return out;
    }

    /**
     * 对外“两段式 create”：路线预估 →（可选）最近司机用于 company 维度估价 → 创建订单；
     * 不做同步指派与打开确认窗口。
     */
    public CreateOrderResultV1 createTwoPhase(CreateAndAssignOrderBody body, String idempotencyKey) {
        resolveCoordinatesByGeocodeIfNeeded(body);
        RouteResponse route = route(body);
        NearestDriverResult nearest = searchNearestDriver(body);
        Long companyId = nearest == null ? null : nearest.getCompanyId();
        EstimateFareResult estimate = estimate(body, route, companyId);
        CreateOrderResult created = createOrder(body, estimate, idempotencyKey);
        String orderNo = created == null ? null : created.getOrderNo();
        if (orderNo == null || orderNo.isBlank()) {
            throw new BizErrorException(502, "订单创建失败：orderNo为空");
        }

        CreateOrderResultV1 out = new CreateOrderResultV1();
        out.setOrderNo(orderNo);
        out.setStatus(OrderStatus.CREATED);
        out.setAssignedDriver(null);
        out.setRoute(route);
        out.setEstimate(estimate);
        log.info("两段式下单完成 orderNo={} passengerId={}", orderNo, body.getPassengerId());
        Long pid = body.getPassengerId();
        if (pid != null) {
            passengerWsNotifyService.notifyOrderChanged(pid, orderNo);
        }
        return out;
    }

    /**
     * 查询订单详情（轮询）：透传 order-service，并校验 {@code passengerId} 归属。
     */
    public PassengerOrderDetailVO getOrderDetail(String orderNo, Long passengerId) {
        if (passengerId == null) {
            throw new BizErrorException(400, "passengerId不能为空");
        }
        var resp = orderClient.getByOrderNo(orderNo);
        if (resp == null) {
            throw new BizErrorException(502, "订单服务响应为空");
        }
        if (resp.getCode() == null || resp.getCode() != 200) {
            throw new BizErrorException(resp.getCode() == null ? 502 : resp.getCode(), "订单服务调用失败: " + resp.getMsg());
        }
        TripOrderRow row = resp.getData();
        if (row == null) {
            throw new BizErrorException(404, "订单不存在");
        }
        if (!passengerId.equals(row.getPassengerId())) {
            throw new BizErrorException(403, "无权查看该订单");
        }
        PassengerOrderDetailVO vo = toDetailVO(row);
        vo.setReDispatching(isRedispatching(orderNo, row.getStatus()));
        return vo;
    }

    /**
     * 乘客个人中心“我的订单”列表：支持全部 / 待出发 / 退款与取消筛选。
     * 列表按创建时间倒序，按钮仅展示，不承载业务动作。
     */
    public PassengerOrderPageVO listMyOrders(Long passengerId, PassengerOrderListType type, Integer pageNo, Integer pageSize) {
        if (passengerId == null) {
            throw new BizErrorException(400, "passengerId不能为空");
        }
        PassengerOrderListType effectiveType = type == null ? PassengerOrderListType.ALL : type;
        int current = pageNo == null || pageNo < 1 ? 1 : pageNo;
        int size = pageSize == null || pageSize < 1 ? 10 : pageSize;

        List<TripOrderRow> allRows = loadAllPassengerOrders(passengerId);
        List<TripOrderRow> filteredRows = new ArrayList<>();
        for (TripOrderRow row : allRows) {
            if (effectiveType.matches(row)) {
                filteredRows.add(row);
            }
        }

        int from = Math.max(0, (current - 1) * size);
        int to = Math.min(filteredRows.size(), from + size);
        List<PassengerOrderListItemVO> list = from >= filteredRows.size()
                ? List.of()
                : filteredRows.subList(from, to).stream().map(this::toListItemVO).toList();

        PassengerOrderPageVO out = new PassengerOrderPageVO();
        out.setList(list);
        out.setTotal(filteredRows.size());
        out.setPageNo(current);
        out.setPageSize(size);
        out.setType(effectiveType);
        return out;
    }

    private boolean isRedispatching(String orderNo, Integer statusCode) {
        // 仅在 CREATED 展示“正在重新派单”
        if (statusCode == null || statusCode != 0) {
            return false;
        }
        try {
            var eventsResp = orderClient.listEvents(orderNo);
            if (eventsResp == null || eventsResp.getCode() == null || eventsResp.getCode() != 200 || eventsResp.getData() == null) {
                return false;
            }
            return eventsResp.getData().stream()
                    .map(OrderEventRow::getEventType)
                    .anyMatch(REDISPATCH_EVENT_TYPES::contains);
        } catch (Exception e) {
            log.warn("查询订单事件失败，按非重新派单处理 orderNo={}: {}", orderNo, e.toString());
            return false;
        }
    }

    /**
     * 乘客取消订单：透传 order-service {@code POST /api/v1/orders/{orderNo}/cancel}。
     */
    public void cancelOrder(String orderNo, CancelOrderRequest req) {
        CancelOrderBody body = new CancelOrderBody();
        body.setPassengerId(req.getPassengerId());
        body.setCancelReason(req.getCancelReason());
        var resp = orderClient.cancel(orderNo, body);
        if (resp == null) {
            throw new BizErrorException(502, "订单服务响应为空");
        }
        if (resp.getCode() == null || resp.getCode() != 200) {
            throw new BizErrorException(resp.getCode() == null ? 502 : resp.getCode(),
                    resp.getMsg() == null ? "取消订单失败" : resp.getMsg());
        }
        log.info("乘客取消订单 orderNo={} passengerId={}", orderNo, req.getPassengerId());
        passengerWsNotifyService.notifyOrderChanged(req.getPassengerId(), orderNo);
    }

    /**
     * 乘客退出登录：若存在「司机到达前」在途单则逐单代取消；若存在已到达/行程中单则仅返回提示、不取消（PRD §5.6）。
     */
    public PassengerLogoutResult cancelInFlightOrdersOnPassengerLogout(long passengerId) {
        PassengerLogoutResult out = new PassengerLogoutResult();
        if (passengerId <= 0) {
            return out;
        }
        List<TripOrderRow> rows = loadAllPassengerOrders(passengerId);
        if (rows == null || rows.isEmpty()) {
            return out;
        }
        final int finished = 5;
        final int cancelled = 6;
        final int arrived = 3;
        final int started = 4;
        for (TripOrderRow row : rows) {
            Integer st = row.getStatus();
            if (st == null || st == finished || st == cancelled) {
                continue;
            }
            if (st == arrived || st == started) {
                out.setHint("司机已到达或行程已开始，无法通过退出登录取消订单；您已退出登录。");
                return out;
            }
        }
        int cancelledCount = 0;
        for (TripOrderRow row : rows) {
            Integer st = row.getStatus();
            if (st == null || st == finished || st == cancelled) {
                continue;
            }
            if (st != 0 && st != 1 && st != 2 && st != 7) {
                continue;
            }
            String orderNo = row.getOrderNo();
            if (orderNo == null || orderNo.isBlank()) {
                continue;
            }
            try {
                CancelOrderRequest req = new CancelOrderRequest();
                req.setPassengerId(passengerId);
                req.setCancelReason("乘客退出登录");
                cancelOrder(orderNo, req);
                cancelledCount++;
            } catch (BizErrorException e) {
                log.warn("登出代取消失败 orderNo={} passengerId={} msg={}", orderNo, passengerId, e.getMessage());
            }
        }
        if (cancelledCount > 0) {
            out.setHint("已为您取消进行中的订单（退出登录）。");
        }
        return out;
    }

    private List<TripOrderRow> loadAllPassengerOrders(Long passengerId) {
        List<TripOrderRow> rows = new ArrayList<>();
        final int corePageSize = 100;
        int corePageNo = 1;
        while (true) {
            var resp = orderClient.pageOrders(passengerId, corePageNo, corePageSize);
            if (resp == null || resp.getCode() == null || resp.getCode() != 200) {
                log.warn("查询乘客订单失败 passengerId={} pageNo={} code={} msg={}",
                        passengerId, corePageNo, resp == null ? null : resp.getCode(), resp == null ? null : resp.getMsg());
                return Collections.emptyList();
            }
            OrderPageData data = resp.getData();
            if (data == null || data.getList() == null || data.getList().isEmpty()) {
                break;
            }
            rows.addAll(data.getList());
            Integer total = data.getTotal();
            if (total != null && rows.size() >= total) {
                break;
            }
            corePageNo++;
        }
        return rows;
    }

    private PassengerOrderListItemVO toListItemVO(TripOrderRow row) {
        PassengerOrderListItemVO vo = new PassengerOrderListItemVO();
        vo.setOrderNo(row.getOrderNo());
        vo.setOriginAddress(row.getOriginAddress());
        vo.setDestAddress(row.getDestAddress());
        vo.setStatus(OrderStatus.fromCode(row.getStatus()));
        vo.setEstimatedAmount(row.getEstimatedAmount());
        vo.setFinalAmount(row.getFinalAmount());
        if (row.getDriverId() != null) {
            PassengerOrderDriverVO d = new PassengerOrderDriverVO();
            d.setDriverId(row.getDriverId());
            d.setCarId(row.getCarId());
            d.setCompanyId(row.getCompanyId());
            vo.setDriver(d);
        }
        PassengerOrderTimestamps ts = new PassengerOrderTimestamps();
        ts.setCreatedAt(row.getCreatedAt());
        ts.setAssignedAt(row.getAssignedAt());
        ts.setAcceptedAt(row.getAcceptedAt());
        ts.setArrivedAt(row.getArrivedAt());
        ts.setStartedAt(row.getStartedAt());
        ts.setFinishedAt(row.getFinishedAt());
        ts.setCancelledAt(row.getCancelledAt());
        vo.setTimestamps(ts);
        vo.setCancelBy(row.getCancelBy());
        vo.setCancelReason(row.getCancelReason());
        vo.setReDispatching(isRedispatching(row.getOrderNo(), row.getStatus()));
        vo.setActions(defaultActions());
        return vo;
    }

    private static List<PassengerOrderActionVO> defaultActions() {
        PassengerOrderActionVO invoice = new PassengerOrderActionVO();
        invoice.setCode("APPLY_INVOICE");
        invoice.setLabel("申请开票");
        invoice.setDisabled(Boolean.TRUE);
        invoice.setImplemented(Boolean.FALSE);

        PassengerOrderActionVO returnTrip = new PassengerOrderActionVO();
        returnTrip.setCode("RETURN_TRIP");
        returnTrip.setLabel("呼叫返程");
        returnTrip.setDisabled(Boolean.TRUE);
        returnTrip.setImplemented(Boolean.FALSE);

        PassengerOrderActionVO rate = new PassengerOrderActionVO();
        rate.setCode("RATE");
        rate.setLabel("评价");
        rate.setDisabled(Boolean.TRUE);
        rate.setImplemented(Boolean.FALSE);

        return List.of(invoice, returnTrip, rate);
    }

    private static PassengerOrderDetailVO toDetailVO(TripOrderRow row) {
        PassengerOrderDetailVO vo = new PassengerOrderDetailVO();
        vo.setOrderNo(row.getOrderNo());
        vo.setProductCode(row.getProductCode());
        vo.setProvinceCode(row.getProvinceCode());
        vo.setCityCode(row.getCityCode());
        vo.setOriginAddress(row.getOriginAddress());
        vo.setDestAddress(row.getDestAddress());
        vo.setStatus(OrderStatus.fromCode(row.getStatus()));
        vo.setCancelBy(row.getCancelBy());
        vo.setCancelReason(row.getCancelReason());
        vo.setEstimatedAmount(row.getEstimatedAmount());
        vo.setFinalAmount(row.getFinalAmount());
        if (row.getDriverId() != null) {
            PassengerOrderDriverVO d = new PassengerOrderDriverVO();
            d.setDriverId(row.getDriverId());
            d.setCarId(row.getCarId());
            d.setCompanyId(row.getCompanyId());
            vo.setDriver(d);
        } else {
            vo.setDriver(null);
        }
        PassengerOrderTimestamps ts = new PassengerOrderTimestamps();
        ts.setCreatedAt(row.getCreatedAt());
        ts.setAssignedAt(row.getAssignedAt());
        ts.setAcceptedAt(row.getAcceptedAt());
        ts.setArrivedAt(row.getArrivedAt());
        ts.setStartedAt(row.getStartedAt());
        ts.setFinishedAt(row.getFinishedAt());
        ts.setCancelledAt(row.getCancelledAt());
        vo.setTimestamps(ts);
        return vo;
    }

    private static Point toPoint(com.sx.passengerapi.model.order.Place place) {
        Point p = new Point();
        p.setLat(place.getLat());
        p.setLng(place.getLng());
        return p;
    }

    private static Place toOrderPlace(com.sx.passengerapi.model.order.Place place) {
        Place p = new Place();
        p.setLat(place.getLat() == null ? null : BigDecimal.valueOf(place.getLat()));
        p.setLng(place.getLng() == null ? null : BigDecimal.valueOf(place.getLng()));
        // order-service 要求 address 非空；MVP 用 address 优先，否则退化为 name
        String addr = place.getAddress();
        if (addr == null || addr.isBlank()) {
            addr = place.getName();
        }
        p.setAddress(addr);
        return p;
    }

    /**
     * 指派成功后写入运力侧订单池索引（派生缓存）；失败不影响主链路。
     */
    private void registerPendingOrderIndex(Long driverId, String orderNo) {
        if (driverId == null || orderNo == null || orderNo.isBlank()) {
            return;
        }
        try {
            PendingOrderIndexBody idx = new PendingOrderIndexBody();
            idx.setDriverId(driverId);
            idx.setOrderNo(orderNo);
            var resp = capacityDispatchClient.addPendingOrderIndex(idx);
            if (resp == null || resp.getCode() == null || resp.getCode() != 200) {
                log.warn("待确认订单索引写入失败 orderNo={} driverId={}", orderNo, driverId);
            }
        } catch (Exception e) {
            log.warn("待确认订单索引异常 orderNo={} driverId={}: {}", orderNo, driverId, e.toString());
        }
    }
}
