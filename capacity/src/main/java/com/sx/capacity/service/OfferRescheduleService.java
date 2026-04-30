package com.sx.capacity.service;

import com.sx.capacity.client.order.OrderServiceClient;
import com.sx.capacity.client.order.OrderServiceResponseVo;
import com.sx.capacity.client.order.dto.AssignOrderFeignBody;
import com.sx.capacity.client.order.dto.AssignedAwaitingRescheduleFeignDto;
import com.sx.capacity.client.order.dto.OpenDriverOfferFeignBody;
import com.sx.capacity.model.dto.NearestDriverResult;
import com.sx.capacity.service.geo.DriverGeoRedisPool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

/**
 * offer 超时打回 {@code ASSIGNED} 后的调度：同司机在配置轮次内可再开确认窗口；否则 GEO 改派并重新打开窗口。
 */
@Service
@Slf4j
public class OfferRescheduleService {

    private static final int GEO_CANDIDATE_LIMIT = 32;

    private final OrderServiceClient orderServiceClient;
    private final DriverGeoRedisPool driverGeoRedisPool;
    private final NearestDriverQueryService nearestDriverQueryService;
    private final DispatchOrderPoolService dispatchOrderPoolService;
    private final double matchRadiusMeters;
    private final int driverOfferSeconds;
    private final int batchLimit;
    private final int sameDriverMaxOfferRounds;

    public OfferRescheduleService(OrderServiceClient orderServiceClient,
                                  DriverGeoRedisPool driverGeoRedisPool,
                                  NearestDriverQueryService nearestDriverQueryService,
                                  DispatchOrderPoolService dispatchOrderPoolService,
                                  @Value("${capacity.dispatch.match-radius-meters:3000}") double matchRadiusMeters,
                                  @Value("${capacity.dispatch.driver-offer-seconds:30}") int driverOfferSeconds,
                                  @Value("${capacity.dispatch.offer-reschedule.batch-limit:50}") int batchLimit,
                                  @Value("${capacity.dispatch.offer-reschedule.same-driver-max-offer-rounds:2}") int sameDriverMaxOfferRounds) {
        this.orderServiceClient = orderServiceClient;
        this.driverGeoRedisPool = driverGeoRedisPool;
        this.nearestDriverQueryService = nearestDriverQueryService;
        this.dispatchOrderPoolService = dispatchOrderPoolService;
        this.matchRadiusMeters = matchRadiusMeters;
        this.driverOfferSeconds = driverOfferSeconds;
        this.batchLimit = batchLimit;
        this.sameDriverMaxOfferRounds = sameDriverMaxOfferRounds;
    }

    /**
     * @return 本轮成功推进（打开确认窗口或改派后打开）的订单数
     */
    public int processRescheduleBatch() {
        OrderServiceResponseVo<List<AssignedAwaitingRescheduleFeignDto>> resp =
                orderServiceClient.listAssignedAwaitingReschedule(batchLimit);
        if (resp == null || resp.getCode() == null || resp.getCode() != 200 || resp.getData() == null) {
            log.warn("确认窗口改派调度：列表拉取失败 msg={}", resp == null ? null : resp.getMsg());
            return 0;
        }
        int advanced = 0;
        for (AssignedAwaitingRescheduleFeignDto row : resp.getData()) {
            if (row == null || row.getOrderNo() == null) {
                continue;
            }
            try {
                if (tryAdvanceOne(row)) {
                    advanced++;
                }
            } catch (Exception e) {
                log.debug("确认窗口改派调度跳过 orderNo={}: {}", row.getOrderNo(), e.toString());
            }
        }
        return advanced;
    }

    private boolean tryAdvanceOne(AssignedAwaitingRescheduleFeignDto row) {
        String orderNo = row.getOrderNo();
        String cityCode = row.getCityCode();
        BigDecimal olat = row.getOriginLat();
        BigDecimal olng = row.getOriginLng();
        Long currentDriverId = row.getDriverId();
        if (cityCode == null || cityCode.isBlank() || olat == null || olng == null || currentDriverId == null) {
            return false;
        }
        int completedRound = row.getOfferRound() == null ? 0 : row.getOfferRound();
        if (completedRound < sameDriverMaxOfferRounds) {
            return openOfferSameDriver(orderNo, currentDriverId);
        }
        return redispatchToAnotherDriver(row, cityCode, olat.doubleValue(), olng.doubleValue(), currentDriverId);
    }

    private boolean openOfferSameDriver(String orderNo, Long driverId) {
        OpenDriverOfferFeignBody offer = new OpenDriverOfferFeignBody();
        offer.setOfferSeconds(driverOfferSeconds);
        OrderServiceResponseVo<Void> o = orderServiceClient.openDriverOffer(orderNo, offer);
        if (o == null || o.getCode() == null || o.getCode() != 200) {
            throw new IllegalStateException(o == null ? "offer null" : (o.getMsg() == null ? "offer failed" : o.getMsg()));
        }
        dispatchOrderPoolService.addPending(driverId, orderNo);
        log.info("确认窗口改派：同司机再开窗口 orderNo={} driverId={}", orderNo, driverId);
        return true;
    }

    private boolean redispatchToAnotherDriver(AssignedAwaitingRescheduleFeignDto row, String cityCode,
                                              double originLat, double originLng, Long oldDriverId) {
        List<Long> ids = driverGeoRedisPool.listNearestDriverIds(
                cityCode, originLat, originLng, matchRadiusMeters, GEO_CANDIDATE_LIMIT);
        if (ids == null || ids.isEmpty()) {
            log.debug("确认窗口改派：无 GEO 候选司机 orderNo={}", row.getOrderNo());
            return false;
        }
        String orderNo = row.getOrderNo();
        String productCode = row.getProductCode();
        for (Long candidate : ids) {
            if (candidate == null || Objects.equals(candidate, oldDriverId)) {
                continue;
            }
            NearestDriverResult nr = nearestDriverQueryService.buildEligibleForDriver(candidate, cityCode, productCode);
            if (nr == null) {
                continue;
            }
            try {
                AssignOrderFeignBody body = new AssignOrderFeignBody();
                body.setDriverId(nr.getDriverId());
                body.setCarId(nr.getCarId());
                body.setCompanyId(nr.getCompanyId());
                body.setEtaSeconds(null);
                OrderServiceResponseVo<Void> r = orderServiceClient.reassign(orderNo, body);
                if (r == null || r.getCode() == null || r.getCode() != 200) {
                    throw new IllegalStateException(r == null ? "reassign null" : (r.getMsg() == null ? "reassign failed" : r.getMsg()));
                }
                dispatchOrderPoolService.removePending(oldDriverId, orderNo);
                OpenDriverOfferFeignBody offer = new OpenDriverOfferFeignBody();
                offer.setOfferSeconds(driverOfferSeconds);
                OrderServiceResponseVo<Void> o = orderServiceClient.openDriverOffer(orderNo, offer);
                if (o == null || o.getCode() == null || o.getCode() != 200) {
                    throw new IllegalStateException(o == null ? "offer null" : (o.getMsg() == null ? "offer failed" : o.getMsg()));
                }
                dispatchOrderPoolService.addPending(nr.getDriverId(), orderNo);
                log.info("确认窗口改派：已改派司机 orderNo={} oldDriverId={} newDriverId={}",
                        orderNo, oldDriverId, nr.getDriverId());
                return true;
            } catch (Exception e) {
                log.debug("确认窗口改派：尝试改派 orderNo={} candidate={}: {}",
                        orderNo, candidate, e.toString());
            }
        }
        return false;
    }
}
