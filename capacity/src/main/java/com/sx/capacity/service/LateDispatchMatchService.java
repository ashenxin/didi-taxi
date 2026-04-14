package com.sx.capacity.service;

import com.sx.capacity.client.order.OrderServiceClient;
import com.sx.capacity.client.order.OrderServiceResponseVo;
import com.sx.capacity.client.order.dto.AssignOrderFeignBody;
import com.sx.capacity.client.order.dto.OpenDriverOfferFeignBody;
import com.sx.capacity.client.order.dto.PendingDispatchFeignDto;
import com.sx.capacity.service.geo.DriverGeoRedisPool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

/**
 * 司机入池后尝试迟滞匹配：仅当该司机在 GEO 上为订单上车点的最近候选时，才落库 assign + 打开确认窗口。
 */
@Service
@Slf4j
public class LateDispatchMatchService {

    private final OrderServiceClient orderServiceClient;
    private final DriverGeoRedisPool driverGeoRedisPool;
    private final NearestDriverQueryService nearestDriverQueryService;
    private final DispatchOrderPoolService dispatchOrderPoolService;
    private final double matchRadiusMeters;
    private final int driverOfferSeconds;

    public LateDispatchMatchService(OrderServiceClient orderServiceClient,
                                    DriverGeoRedisPool driverGeoRedisPool,
                                    NearestDriverQueryService nearestDriverQueryService,
                                    DispatchOrderPoolService dispatchOrderPoolService,
                                    @Value("${capacity.dispatch.match-radius-meters:3000}") double matchRadiusMeters,
                                    @Value("${capacity.dispatch.driver-offer-seconds:10}") int driverOfferSeconds) {
        this.orderServiceClient = orderServiceClient;
        this.driverGeoRedisPool = driverGeoRedisPool;
        this.nearestDriverQueryService = nearestDriverQueryService;
        this.dispatchOrderPoolService = dispatchOrderPoolService;
        this.matchRadiusMeters = matchRadiusMeters;
        this.driverOfferSeconds = driverOfferSeconds;
    }

    /**
     * 司机上线并入 GEO 后调用：至多成功指派一条 CREATED 订单（与「先入池再匹配」主路径一致）。
     */
    public void tryMatchAfterDriverOnline(Long driverId, String cityCode, double lat, double lng) {
        if (driverId == null || cityCode == null || cityCode.isBlank()) {
            return;
        }
        OrderServiceResponseVo<List<PendingDispatchFeignDto>> resp = orderServiceClient.listPendingDispatch(cityCode, 50);
        if (resp == null || resp.getCode() == null || resp.getCode() != 200 || resp.getData() == null) {
            log.warn("late dispatch: pending list failed cityCode={} msg={}", cityCode, resp == null ? null : resp.getMsg());
            return;
        }
        for (PendingDispatchFeignDto order : resp.getData()) {
            if (order == null || order.getOrderNo() == null) {
                continue;
            }
            BigDecimal olat = order.getOriginLat();
            BigDecimal olng = order.getOriginLng();
            if (olat == null || olng == null) {
                continue;
            }
            if (!isDriverNearestAtOrder(cityCode, driverId, olat.doubleValue(), olng.doubleValue())) {
                continue;
            }
            var nearest = nearestDriverQueryService.buildEligibleForDriver(driverId, cityCode, order.getProductCode());
            if (nearest == null) {
                continue;
            }
            try {
                assignAndOpenOffer(order.getOrderNo(), nearest);
                dispatchOrderPoolService.addPending(driverId, order.getOrderNo());
                log.info("late dispatch matched orderNo={} driverId={}", order.getOrderNo(), driverId);
                return;
            } catch (Exception e) {
                log.warn("late dispatch assign failed orderNo={} driverId={}: {}", order.getOrderNo(), driverId, e.toString());
            }
        }
    }

    private boolean isDriverNearestAtOrder(String cityCode, Long driverId, double originLat, double originLng) {
        var first = driverGeoRedisPool.findNearestDriverId(cityCode, originLat, originLng, matchRadiusMeters);
        return first.isPresent() && Objects.equals(first.get(), driverId);
    }

    private void assignAndOpenOffer(String orderNo, com.sx.capacity.model.dto.NearestDriverResult nr) {
        AssignOrderFeignBody assign = new AssignOrderFeignBody();
        assign.setDriverId(nr.getDriverId());
        assign.setCarId(nr.getCarId());
        assign.setCompanyId(nr.getCompanyId());
        assign.setEtaSeconds(null);
        OrderServiceResponseVo<Void> a = orderServiceClient.assign(orderNo, assign);
        if (a == null || a.getCode() == null || a.getCode() != 200) {
            throw new IllegalStateException(a == null ? "assign null" : (a.getMsg() == null ? "assign failed" : a.getMsg()));
        }
        OpenDriverOfferFeignBody offer = new OpenDriverOfferFeignBody();
        offer.setOfferSeconds(driverOfferSeconds);
        OrderServiceResponseVo<Void> o = orderServiceClient.openDriverOffer(orderNo, offer);
        if (o == null || o.getCode() == null || o.getCode() != 200) {
            throw new IllegalStateException(o == null ? "offer null" : (o.getMsg() == null ? "offer failed" : o.getMsg()));
        }
    }
}
