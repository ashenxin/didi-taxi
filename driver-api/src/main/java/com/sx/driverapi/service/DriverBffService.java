package com.sx.driverapi.service;

import com.sx.driverapi.client.CapacityDriverClient;
import com.sx.driverapi.client.CoreResponseVo;
import com.sx.driverapi.client.OrderClient;
import com.sx.driverapi.common.exception.BizErrorException;
import com.sx.driverapi.model.capacity.CapacityDriverSnapshot;
import com.sx.driverapi.model.capacity.DriverListeningStatusVO;
import com.sx.driverapi.model.capacity.DriverOnlineBody;
import com.sx.driverapi.model.order.AssignedOrderItemVO;
import com.sx.driverapi.model.order.DriverIdBody;
import com.sx.driverapi.model.order.FinishOrderBody;
import com.sx.driverapi.model.ordercore.TripOrderRow;
import feign.FeignException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

@Service
@Slf4j
public class DriverBffService {

    private static final int STATUS_ASSIGNED = 1;
    private static final int STATUS_PENDING_DRIVER_CONFIRM = 7;
    private static final int STATUS_FINISHED = 5;
    private static final int STATUS_CANCELLED = 6;

    private final CapacityDriverClient capacityDriverClient;
    private final OrderClient orderClient;

    public DriverBffService(CapacityDriverClient capacityDriverClient, OrderClient orderClient) {
        this.capacityDriverClient = capacityDriverClient;
        this.orderClient = orderClient;
    }

    public void setOnline(Long driverId, boolean online, Double lat, Double lng) {
        DriverOnlineBody body = new DriverOnlineBody();
        body.setOnline(online);
        body.setLat(lat);
        body.setLng(lng);
        unwrap(capacityDriverClient.setOnline(driverId, body), "运力上线状态");
        log.info("driver online updated driverId={} online={}", driverId, online);
    }

    /**
     * 当前司机听单状态（与运力 {@code monitor_status} 一致），供前端禁用重复上线/下线。
     */
    public DriverListeningStatusVO getListeningStatus(Long driverId) {
        CoreResponseVo<CapacityDriverSnapshot> resp = capacityDriverClient.getDriver(driverId);
        unwrap(resp, "拉取司机听单状态");
        CapacityDriverSnapshot snap = resp.getData();
        DriverListeningStatusVO vo = new DriverListeningStatusVO();
        vo.setMonitorStatus(snap != null ? snap.getMonitorStatus() : null);
        return vo;
    }

    public List<AssignedOrderItemVO> listAssigned(Long driverId) {
        CoreResponseVo<List<TripOrderRow>> resp = orderClient.listAssigned(driverId);
        unwrap(resp, "拉取指派订单");
        List<TripOrderRow> rows = resp.getData();
        if (rows == null) {
            return List.of();
        }
        List<AssignedOrderItemVO> out = new ArrayList<>(rows.size());
        for (TripOrderRow row : rows) {
            Integer st = row.getStatus();
            if (st == null || (st != STATUS_ASSIGNED && st != STATUS_PENDING_DRIVER_CONFIRM)) {
                if (st != null && (st == STATUS_FINISHED || st == STATUS_CANCELLED)) {
                    log.debug("skip terminal order in assigned list orderNo={} status={}", row.getOrderNo(), st);
                } else if (st != null) {
                    log.warn("skip unexpected status in assigned list orderNo={} status={}", row.getOrderNo(), st);
                }
                continue;
            }
            AssignedOrderItemVO vo = new AssignedOrderItemVO();
            vo.setOrderNo(row.getOrderNo());
            vo.setStatus(statusToName(row.getStatus()));
            AssignedOrderItemVO.Pickup p = new AssignedOrderItemVO.Pickup();
            p.setName(row.getOriginAddress());
            vo.setPickup(p);
            vo.setEtaSeconds(null);
            vo.setOfferExpiresAt(row.getOfferExpiresAt());
            out.add(vo);
        }
        return out;
    }

    public void accept(String orderNo, Long driverId) {
        unwrap(capacityDriverClient.acceptReadiness(driverId), "接单资格校验");
        DriverIdBody body = new DriverIdBody();
        body.setDriverId(driverId);
        unwrap(orderClient.accept(orderNo, body), "确认接单");
        log.info("driver accept order orderNo={} driverId={}", orderNo, driverId);
    }

    public void arrive(String orderNo, Long driverId) {
        DriverIdBody body = new DriverIdBody();
        body.setDriverId(driverId);
        unwrap(orderClient.arrive(orderNo, body), "到达上报");
        log.info("driver arrive orderNo={} driverId={}", orderNo, driverId);
    }

    public void start(String orderNo, Long driverId) {
        DriverIdBody body = new DriverIdBody();
        body.setDriverId(driverId);
        unwrap(orderClient.start(orderNo, body), "开始行程");
        log.info("driver start trip orderNo={} driverId={}", orderNo, driverId);
    }

    public void finish(String orderNo, FinishOrderBody body) {
        unwrap(orderClient.finish(orderNo, body), "完单");
        log.info("driver finish order orderNo={} driverId={}", orderNo, body != null ? body.getDriverId() : null);
    }

    /**
     * 当前司机名下订单详情（用于接单后行程推进；校验 {@code driver_id} 归属）。
     */
    public TripOrderRow getOrderForDriver(String orderNo, Long driverId) {
        CoreResponseVo<TripOrderRow> resp;
        try {
            resp = orderClient.getByOrderNo(orderNo);
        } catch (FeignException e) {
            if (e.status() == 404) {
                throw new BizErrorException(404, "订单不存在");
            }
            throw new BizErrorException(e.status() > 0 ? e.status() : 502, "订单服务调用失败");
        }
        unwrap(resp, "订单详情");
        TripOrderRow row = resp.getData();
        if (row == null) {
            throw new BizErrorException(404, "订单不存在");
        }
        if (row.getDriverId() == null || !Objects.equals(row.getDriverId(), driverId)) {
            throw new BizErrorException(403, "非本单司机");
        }
        return row;
    }

    private static String statusToName(Integer code) {
        if (code == null) {
            return "UNKNOWN";
        }
        if (code == STATUS_ASSIGNED) {
            return "ASSIGNED";
        }
        if (code == STATUS_PENDING_DRIVER_CONFIRM) {
            return "PENDING_DRIVER_CONFIRM";
        }
        return "STATUS_" + code;
    }

    private static void unwrap(CoreResponseVo<?> resp, String action) {
        if (resp == null) {
            throw new BizErrorException(502, action + "：下游响应为空");
        }
        Integer code = resp.getCode();
        if (code == null || code != 200) {
            int c = code == null ? 502 : code;
            String msg = resp.getMsg();
            throw new BizErrorException(c, msg == null ? action + "失败" : msg);
        }
    }
}
