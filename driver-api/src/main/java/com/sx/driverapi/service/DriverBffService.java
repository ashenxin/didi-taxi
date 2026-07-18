package com.sx.driverapi.service;

import com.sx.driverapi.client.CapacityDriverClient;
import com.sx.driverapi.client.CoreResponseVo;
import com.sx.driverapi.client.OrderClient;
import com.sx.driverapi.client.PassengerNotifyClient;
import com.sx.driverapi.common.exception.BizErrorException;
import com.sx.driverapi.model.capacity.CapacityDriverDetail;
import com.sx.driverapi.model.capacity.DriverListeningStatusVO;
import com.sx.driverapi.model.capacity.DriverOnlineBody;
import com.sx.driverapi.model.capacity.DriverHeartbeatBody;
import com.sx.driverapi.model.order.AssignedOrderItemVO;
import com.sx.driverapi.model.order.DriverIdBody;
import com.sx.driverapi.model.order.DriverOrderReasonBody;
import com.sx.driverapi.model.order.FinishOrderBody;
import com.sx.driverapi.model.ordercore.TripOrderRow;
import com.sx.driverapi.model.ordercore.DriverActionResult;
import com.sx.driverapi.model.ordercore.AcceptOrderPreflightResult;
import com.sx.driverapi.model.passenger.OrderChangedNotifyBody;
import feign.FeignException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Service
@Slf4j
public class DriverBffService {

    /** 登出时批量拒单写入 order_event / 与手动拒单同链路（含司乘隔离匹配） */
    public static final String REASON_DRIVER_LOGOUT = "DRIVER_LOGOUT";

    private static final int STATUS_ASSIGNED = 1;
    private static final int STATUS_PENDING_DRIVER_CONFIRM = 7;
    private static final int STATUS_FINISHED = 5;
    private static final int STATUS_CANCELLED = 6;

    private final CapacityDriverClient capacityDriverClient;
    private final OrderClient orderClient;
    private final PassengerNotifyClient passengerNotifyClient;

    public DriverBffService(CapacityDriverClient capacityDriverClient,
                            OrderClient orderClient,
                            PassengerNotifyClient passengerNotifyClient) {
        this.capacityDriverClient = capacityDriverClient;
        this.orderClient = orderClient;
        this.passengerNotifyClient = passengerNotifyClient;
    }

    public void setOnline(Long driverId, boolean online, Double lat, Double lng) {
        DriverOnlineBody body = new DriverOnlineBody();
        body.setOnline(online);
        body.setLat(lat);
        body.setLng(lng);
        unwrap(capacityDriverClient.setOnline(driverId, body), "运力上线状态");
        log.info("司机在线状态已更新 driverId={} online={}", driverId, online);
    }

    public void heartbeat(Long driverId, Double lat, Double lng) {
        DriverHeartbeatBody body = new DriverHeartbeatBody();
        body.setLat(lat);
        body.setLng(lng);
        unwrap(capacityDriverClient.heartbeat(driverId, body), "司机听单心跳");
    }

    /**
     * 当前司机听单状态（与运力 {@code monitor_status} 一致），供前端禁用重复上线/下线。
     */
    public DriverListeningStatusVO getListeningStatus(Long driverId) {
        CoreResponseVo<CapacityDriverDetail> resp = capacityDriverClient.getDriver(driverId);
        unwrap(resp, "拉取司机听单状态");
        CapacityDriverDetail snap = resp.getData();
        DriverListeningStatusVO vo = new DriverListeningStatusVO();
        vo.setMonitorStatus(snap != null ? snap.getMonitorStatus() : null);
        return vo;
    }

    public List<AssignedOrderItemVO> listAssigned(Long driverId) {
        CoreResponseVo<List<TripOrderRow>> resp = orderClient.listAssigned(driverId, String.valueOf(driverId));
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
                    log.debug("指派列表跳过终态订单 orderNo={} status={}", row.getOrderNo(), st);
                } else if (st != null) {
                    log.warn("指派列表跳过异常状态 orderNo={} status={}", row.getOrderNo(), st);
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

    public DriverActionResult accept(String orderNo, Long driverId, String idempotencyKey) {
        DriverIdBody body = new DriverIdBody();
        body.setDriverId(driverId);
        CoreResponseVo<AcceptOrderPreflightResult> preflightResponse = orderClient.acceptPreflight(
                orderNo, String.valueOf(driverId), idempotencyKey, body);
        unwrap(preflightResponse, "接单幂等预检");
        AcceptOrderPreflightResult preflight = preflightResponse.getData();
        if (preflight == null) {
            throw new BizErrorException(502, "接单幂等预检：下游响应缺少预检结果");
        }
        if (preflight.replayed()) {
            DriverActionResult replayed = new DriverActionResult(true);
            notifyPassengerOrderChanged(orderNo, "司机接单重放");
            log.info("司机接单请求已重放 orderNo={} driverId={}", orderNo, driverId);
            return replayed;
        }
        unwrap(capacityDriverClient.acceptReadiness(driverId), "接单资格校验");
        DriverActionResult result = unwrapDriverAction(
                orderClient.accept(orderNo, String.valueOf(driverId), idempotencyKey, body), "确认接单");
        notifyPassengerOrderChanged(orderNo, "司机接单");
        log.info("司机已接单 orderNo={} driverId={} replayed={}", orderNo, driverId, result.replayed());
        return result;
    }

    public DriverActionResult reject(String orderNo, Long driverId, String reasonCode, String idempotencyKey) {
        DriverOrderReasonBody body = new DriverOrderReasonBody();
        body.setDriverId(driverId);
        body.setReasonCode(reasonCode);
        CoreResponseVo<DriverActionResult> resp = orderClient.reject(
                orderNo, String.valueOf(driverId), idempotencyKey, body);
        DriverActionResult result = unwrapDriverAction(resp, "拒单");
        notifyPassengerOrderChanged(orderNo, "司机拒单");
        log.info("司机已拒单 orderNo={} driverId={} reasonCode={} replayed={}",
                orderNo, driverId, reasonCode, result.replayed());
        return result;
    }

    /**
     * 登出前：拒绝当前司机名下全部待接指派（{@code ASSIGNED/PENDING_DRIVER_CONFIRM}），与乘客侧「重新派单」联动。
     * 单条失败不阻断登出，仅打日志。
     */
    public void rejectAllPendingAssignsOnLogout(long driverId) {
        List<AssignedOrderItemVO> pending = listAssigned(driverId);
        if (pending == null || pending.isEmpty()) {
            return;
        }
        for (AssignedOrderItemVO vo : pending) {
            if (vo == null || vo.getOrderNo() == null || vo.getOrderNo().isBlank()) {
                continue;
            }
            try {
                reject(vo.getOrderNo(), driverId, REASON_DRIVER_LOGOUT, newInternalIdempotencyKey("logout-reject"));
            } catch (Exception e) {
                log.warn("登出批量拒单跳过 orderNo={} driverId={} err={}", vo.getOrderNo(), driverId, e.toString());
            }
        }
        log.info("登出批量拒单完成 driverId={} attempted={}", driverId, pending.size());
    }

    /**
     * 登出前：释放当前司机名下已接单但未到达的订单（{@code ACCEPTED → CREATED}），进入重新派单。
     * 单条失败不阻断登出，仅打日志。
     */
    public void releaseAcceptedBeforeArriveOnLogout(long driverId) {
        List<TripOrderRow> accepted;
        try {
            accepted = listAcceptedBeforeArrive(driverId);
        } catch (Exception e) {
            log.warn("登出查询已接未到订单失败 driverId={} err={}", driverId, e.toString());
            return;
        }
        if (accepted == null || accepted.isEmpty()) {
            return;
        }
        int attempted = 0;
        for (TripOrderRow row : accepted) {
            if (row == null || row.getOrderNo() == null || row.getOrderNo().isBlank()) {
                continue;
            }
            attempted++;
            try {
                driverCancelBeforeArrive(row.getOrderNo(), driverId, REASON_DRIVER_LOGOUT,
                        newInternalIdempotencyKey("logout-cancel"));
            } catch (Exception e) {
                log.warn("登出释放已接未到订单跳过 orderNo={} driverId={} err={}",
                        row.getOrderNo(), driverId, e.toString());
            }
        }
        log.info("登出释放已接未到订单完成 driverId={} attempted={}", driverId, attempted);
    }

    public List<TripOrderRow> listAcceptedBeforeArrive(Long driverId) {
        CoreResponseVo<List<TripOrderRow>> resp = orderClient.listAcceptedBeforeArrive(driverId, String.valueOf(driverId));
        unwrap(resp, "拉取已接未到订单");
        return resp.getData() == null ? List.of() : resp.getData();
    }

    public DriverActionResult driverCancelBeforeArrive(String orderNo, Long driverId, String reasonCode,
                                                       String idempotencyKey) {
        DriverOrderReasonBody body = new DriverOrderReasonBody();
        body.setDriverId(driverId);
        body.setReasonCode(reasonCode);
        CoreResponseVo<DriverActionResult> resp = orderClient.driverCancelBeforeArrive(
                orderNo, String.valueOf(driverId), idempotencyKey, body);
        DriverActionResult result = unwrapDriverAction(resp, "司机取消");
        notifyPassengerOrderChanged(orderNo, "司机取消");
        log.info("司机已取消（到达前） orderNo={} driverId={} reasonCode={} replayed={}",
                orderNo, driverId, reasonCode, result.replayed());
        return result;
    }

    public DriverActionResult arrive(String orderNo, Long driverId, String idempotencyKey) {
        DriverIdBody body = new DriverIdBody();
        body.setDriverId(driverId);
        DriverActionResult result = unwrapDriverAction(
                orderClient.arrive(orderNo, String.valueOf(driverId), idempotencyKey, body), "到达上报");
        notifyPassengerOrderChanged(orderNo, "司机到达");
        log.info("司机已到达 orderNo={} driverId={} replayed={}", orderNo, driverId, result.replayed());
        return result;
    }

    public DriverActionResult start(String orderNo, Long driverId, String idempotencyKey) {
        DriverIdBody body = new DriverIdBody();
        body.setDriverId(driverId);
        DriverActionResult result = unwrapDriverAction(
                orderClient.start(orderNo, String.valueOf(driverId), idempotencyKey, body), "开始行程");
        notifyPassengerOrderChanged(orderNo, "开始行程");
        log.info("司机已开始行程 orderNo={} driverId={} replayed={}", orderNo, driverId, result.replayed());
        return result;
    }

    public DriverActionResult finish(String orderNo, FinishOrderBody body, String idempotencyKey) {
        Long driverId = body == null ? null : body.getDriverId();
        DriverActionResult result = unwrapDriverAction(orderClient.finish(
                orderNo, driverId == null ? "" : String.valueOf(driverId), idempotencyKey, body), "完单");
        notifyPassengerOrderChanged(orderNo, "司机完单");
        log.info("司机已完单 orderNo={} driverId={} replayed={}",
                orderNo, body != null ? body.getDriverId() : null, result.replayed());
        return result;
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

    private void notifyPassengerOrderChanged(String orderNo, String action) {
        if (orderNo == null || orderNo.isBlank()) {
            return;
        }
        try {
            CoreResponseVo<TripOrderRow> detail = orderClient.getByOrderNo(orderNo);
            unwrap(detail, action + "后查询订单");
            TripOrderRow row = detail.getData();
            Long passengerId = row == null ? null : row.getPassengerId();
            if (passengerId == null) {
                log.warn("{}后无法通知乘客：订单缺少 passengerId orderNo={}", action, orderNo);
                return;
            }
            OrderChangedNotifyBody body = new OrderChangedNotifyBody();
            body.setPassengerId(passengerId);
            body.setOrderNo(orderNo);
            CoreResponseVo<Void> resp = passengerNotifyClient.orderChanged(body);
            unwrap(resp, action + "后通知乘客");
        } catch (Exception e) {
            // 司机侧写操作已成功，乘客 WS 通知失败不能回滚订单状态；前端仍可手动查详情兜底。
            log.warn("{}后通知乘客失败 orderNo={} err={}", action, orderNo, e.toString());
        }
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

    private static DriverActionResult unwrapDriverAction(CoreResponseVo<DriverActionResult> resp,
                                                         String action) {
        unwrap(resp, action);
        if (resp.getData() == null) {
            throw new BizErrorException(502, action + "：下游响应缺少幂等结果");
        }
        return resp.getData();
    }

    private static String newInternalIdempotencyKey(String action) {
        return action + ":" + UUID.randomUUID();
    }
}
