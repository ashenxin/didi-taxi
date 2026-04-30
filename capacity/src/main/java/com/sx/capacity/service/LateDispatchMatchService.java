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
 * 迟滞匹配：司机上线入 GEO 后按「最近邻」尝试一条 CREATED；定时任务按 GEO 最近候选列表依次尝试 assign + 打开确认窗口（不依赖新司机登录）。
 */
@Service
@Slf4j
public class LateDispatchMatchService {

    private static final int SCHEDULED_NEAREST_LIMIT = 32;

    private final OrderServiceClient orderServiceClient;
    private final DriverGeoRedisPool driverGeoRedisPool;
    private final NearestDriverQueryService nearestDriverQueryService;
    private final DispatchOrderPoolService dispatchOrderPoolService;
    private final DriverPassengerMatchBlockService matchBlockService;
    private final double matchRadiusMeters;
    private final int driverOfferSeconds;
    private final int scheduledScanBatchLimit;

    public LateDispatchMatchService(OrderServiceClient orderServiceClient,
                                    DriverGeoRedisPool driverGeoRedisPool,
                                    NearestDriverQueryService nearestDriverQueryService,
                                    DispatchOrderPoolService dispatchOrderPoolService,
                                    DriverPassengerMatchBlockService matchBlockService,
                                    @Value("${capacity.dispatch.match-radius-meters:3000}") double matchRadiusMeters,
                                    @Value("${capacity.dispatch.driver-offer-seconds:30}") int driverOfferSeconds,
                                    @Value("${capacity.dispatch.late-match-batch-limit:50}") int scheduledScanBatchLimit) {
        this.orderServiceClient = orderServiceClient;
        this.driverGeoRedisPool = driverGeoRedisPool;
        this.nearestDriverQueryService = nearestDriverQueryService;
        this.dispatchOrderPoolService = dispatchOrderPoolService;
        this.matchBlockService = matchBlockService;
        this.matchRadiusMeters = matchRadiusMeters;
        this.driverOfferSeconds = driverOfferSeconds;
        this.scheduledScanBatchLimit = scheduledScanBatchLimit;
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
            log.warn("迟滞派单：待派单列表拉取失败 cityCode={} msg={}", cityCode, resp == null ? null : resp.getMsg());
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
            if (matchBlockService.isBlocked(driverId, order.getPassengerId())) {
                continue;
            }
            try {
                assignAndOpenOffer(order.getOrderNo(), nearest);
                dispatchOrderPoolService.addPending(driverId, order.getOrderNo());
                log.info("迟滞派单已匹配 orderNo={} driverId={}", order.getOrderNo(), driverId);
                return;
            } catch (Exception e) {
                log.warn("迟滞派单指派失败 orderNo={} driverId={}: {}", order.getOrderNo(), driverId, e.toString());
            }
        }
    }

    /**
     * 定时扫描：拉取一批 CREATED 待派单，对每条订单按 GEO 最近若干司机依次尝试 assign + openOffer，成功则写入运力侧待确认池。
     *
     * @return 本轮成功指派并打开确认窗口的订单数
     */
    public int tryMatchScheduledScan() {
        OrderServiceResponseVo<List<PendingDispatchFeignDto>> resp =
                orderServiceClient.listPendingDispatchAll(scheduledScanBatchLimit);
        if (resp == null || resp.getCode() == null || resp.getCode() != 200 || resp.getData() == null) {
            log.warn("迟滞派单定时扫描：全量待派单列表拉取失败 msg={}", resp == null ? null : resp.getMsg());
            return 0;
        }
        int matched = 0;
        for (PendingDispatchFeignDto order : resp.getData()) {
            if (order == null || order.getOrderNo() == null) {
                continue;
            }
            BigDecimal olat = order.getOriginLat();
            BigDecimal olng = order.getOriginLng();
            String cityCode = order.getCityCode();
            if (olat == null || olng == null || cityCode == null || cityCode.isBlank()) {
                continue;
            }
            List<Long> driverIds = driverGeoRedisPool.listNearestDriverIds(
                    cityCode, olat.doubleValue(), olng.doubleValue(), matchRadiusMeters, SCHEDULED_NEAREST_LIMIT);
            if (driverIds == null || driverIds.isEmpty()) {
                continue;
            }
            for (Long driverId : driverIds) {
                var nearest = nearestDriverQueryService.buildEligibleForDriver(driverId, cityCode, order.getProductCode());
                if (nearest == null) {
                    continue;
                }
                if (matchBlockService.isBlocked(driverId, order.getPassengerId())) {
                    continue;
                }
                try {
                    assignAndOpenOffer(order.getOrderNo(), nearest);
                    dispatchOrderPoolService.addPending(driverId, order.getOrderNo());
                    matched++;
                    log.info("迟滞派单定时扫描已匹配 orderNo={} driverId={}", order.getOrderNo(), driverId);
                    break;
                } catch (Exception e) {
                    log.debug("迟滞派单定时扫描跳过 orderNo={} driverId={}: {}",
                            order.getOrderNo(), driverId, e.toString());
                }
            }
        }
        return matched;
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
