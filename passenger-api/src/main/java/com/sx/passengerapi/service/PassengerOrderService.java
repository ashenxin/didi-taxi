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
import com.sx.passengerapi.model.order.AssignedDriver;
import com.sx.passengerapi.model.order.CancelOrderRequest;
import com.sx.passengerapi.model.order.CreateAndAssignOrderBody;
import com.sx.passengerapi.model.order.CreateAndAssignOrderResult;
import com.sx.passengerapi.model.order.OrderStatus;
import com.sx.passengerapi.model.order.PassengerOrderDetailVO;
import com.sx.passengerapi.model.order.PassengerOrderDriverVO;
import com.sx.passengerapi.model.order.PassengerOrderTimestamps;
import com.sx.passengerapi.model.ordercore.AssignOrderBody;
import com.sx.passengerapi.model.ordercore.CancelOrderBody;
import com.sx.passengerapi.model.ordercore.CreateOrderBody;
import com.sx.passengerapi.model.ordercore.CreateOrderResult;
import com.sx.passengerapi.model.ordercore.OpenDriverOfferBody;
import com.sx.passengerapi.model.ordercore.Place;
import com.sx.passengerapi.model.capacity.PendingOrderIndexBody;
import com.sx.passengerapi.model.ordercore.TripOrderRow;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.util.Map;

@Service
@Slf4j
public class PassengerOrderService {

    /** cityCode → 高德 geocode 可选 city 参数（中文/全拼/adcode 等，见高德文档） */
    private static final Map<String, String> CITY_CODE_TO_GEOCODE_CITY = Map.of(
            "330100", "杭州"
    );

    private final MapClient mapClient;
    private final CalculateClient calculateClient;
    private final OrderClient orderClient;
    private final CapacityDispatchClient capacityDispatchClient;

    private final int driverOfferSeconds;

    public PassengerOrderService(MapClient mapClient,
                                 CalculateClient calculateClient,
                                 OrderClient orderClient,
                                 CapacityDispatchClient capacityDispatchClient,
                                 @Value("${app.order.driver-offer-seconds:10}") int driverOfferSeconds) {
        this.mapClient = mapClient;
        this.calculateClient = calculateClient;
        this.orderClient = orderClient;
        this.capacityDispatchClient = capacityDispatchClient;
        this.driverOfferSeconds = driverOfferSeconds;
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
    public EstimateFareResult estimate(CreateAndAssignOrderBody body, RouteResponse route) {
        EstimateFareBody req = new EstimateFareBody();
        req.setProvinceCode(body.getProvinceCode());
        req.setCityCode(body.getCityCode());
        req.setProductCode(body.getProductCode());
        req.setDistanceMeters(route == null ? null : route.getDistanceMeters());
        req.setDurationSeconds(route == null ? null : route.getDurationSeconds());

        var resp = calculateClient.estimate(req);
        if (resp == null) {
            throw new BizErrorException(502, "计费服务响应为空");
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
    public CreateOrderResult createOrder(CreateAndAssignOrderBody body, EstimateFareResult estimate) {
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

        var resp = orderClient.create(req);
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
        var resp = capacityDispatchClient.nearestDriver(body.getCityCode(), body.getProductCode(), olat, olng);
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
     * 打开司机确认窗口（ASSIGNED → PENDING_DRIVER_CONFIRM）。
     */
    public void openDriverOffer(String orderNo) {
        OpenDriverOfferBody body = new OpenDriverOfferBody();
        body.setOfferSeconds(driverOfferSeconds);
        var resp = orderClient.openDriverOffer(orderNo, body);
        if (resp == null) {
            throw new BizErrorException(502, "订单服务响应为空");
        }
        if (resp.getCode() == null || resp.getCode() != 200) {
            throw new BizErrorException(resp.getCode() == null ? 502 : resp.getCode(),
                    "打开待司机确认失败: " + resp.getMsg());
        }
    }

    /**
     * 对外“一步 createAndAssign”的同步编排实现（当前版本）。
     *
     * 同步链路（便于联调）：地理编码补坐标（如需）→ route → estimate → order.create → nearestDriver → order.assign。
     *
     * 后续推荐演进为“对内两段式”：
     * 先落库创建订单 + 写 outbox/event，再由调度器异步执行找司机与指派，避免分布式事务与长耗时链路。
     */
    public CreateAndAssignOrderResult createAndAssign(CreateAndAssignOrderBody body) {
        resolveCoordinatesByGeocodeIfNeeded(body);//缺经纬度调map补齐
        RouteResponse route = route(body);//路线预估（高德驾车）
        EstimateFareResult estimate = estimate(body, route);//费用预估
        CreateOrderResult created = createOrder(body, estimate);//创建订单
        String orderNo = created == null ? null : created.getOrderNo();
        if (orderNo == null || orderNo.isBlank()) {
            throw new BizErrorException(502, "订单创建失败：orderNo为空");
        }

        NearestDriverResult nearest = searchNearestDriver(body);//最近司机
        // ETA 暂时用 route 的 duration 做占位（真实 ETA 后续接 map.matrix）
        Long etaSeconds = route == null ? null : route.getDurationSeconds();
        if (nearest != null) {
            assignOrder(orderNo, nearest, etaSeconds);//指派司机
            openDriverOffer(orderNo);
            registerPendingOrderIndex(nearest.getDriverId(), orderNo);
        }

        CreateAndAssignOrderResult out = new CreateAndAssignOrderResult();
        out.setOrderNo(orderNo);
        out.setRoute(route);
        out.setEstimate(estimate);

        if (nearest == null) {
            out.setStatus(OrderStatus.CREATED);
            out.setAssignedDriver(null);
        } else {
            var detail = orderClient.getByOrderNo(orderNo);
            if (detail == null || detail.getCode() == null || detail.getCode() != 200 || detail.getData() == null) {
                throw new BizErrorException(502, "订单状态刷新失败");
            }
            TripOrderRow row = detail.getData();
            out.setStatus(OrderStatus.fromCode(row.getStatus()));
            AssignedDriver ad = new AssignedDriver();
            ad.setDriverId(nearest.getDriverId());
            ad.setCarId(nearest.getCarId());
            ad.setCompanyId(nearest.getCompanyId());
            ad.setCarNo(nearest.getCarNo());
            ad.setEtaSeconds(etaSeconds);
            out.setAssignedDriver(ad);
        }
        log.info("createAndAssign done orderNo={} passengerId={} assigned={}",
                orderNo, body.getPassengerId(), nearest != null);
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
        return toDetailVO(row);
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
        log.info("passenger cancel order orderNo={} passengerId={}", orderNo, req.getPassengerId());
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
                log.warn("pending-order-index failed orderNo={} driverId={}", orderNo, driverId);
            }
        } catch (Exception e) {
            log.warn("pending-order-index error orderNo={} driverId={}: {}", orderNo, driverId, e.toString());
        }
    }
}

