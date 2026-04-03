package com.sx.driverapi.service;

import com.sx.driverapi.client.CapacityDriverClient;
import com.sx.driverapi.client.CoreResponseVo;
import com.sx.driverapi.client.OrderClient;
import com.sx.driverapi.common.exception.BizErrorException;
import com.sx.driverapi.model.capacity.DriverOnlineBody;
import com.sx.driverapi.model.order.AssignedOrderItemVO;
import com.sx.driverapi.model.order.DriverIdBody;
import com.sx.driverapi.model.order.FinishOrderBody;
import com.sx.driverapi.model.ordercore.TripOrderRow;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
public class DriverBffService {

    private static final int STATUS_ASSIGNED = 1;

    private final CapacityDriverClient capacityDriverClient;
    private final OrderClient orderClient;

    public DriverBffService(CapacityDriverClient capacityDriverClient, OrderClient orderClient) {
        this.capacityDriverClient = capacityDriverClient;
        this.orderClient = orderClient;
    }

    public void setOnline(Long driverId, boolean online) {
        DriverOnlineBody body = new DriverOnlineBody();
        body.setOnline(online);
        unwrap(capacityDriverClient.setOnline(driverId, body), "运力上线状态");
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
            AssignedOrderItemVO vo = new AssignedOrderItemVO();
            vo.setOrderNo(row.getOrderNo());
            vo.setStatus(statusToName(row.getStatus()));
            AssignedOrderItemVO.Pickup p = new AssignedOrderItemVO.Pickup();
            p.setName(row.getOriginAddress());
            vo.setPickup(p);
            vo.setEtaSeconds(null);
            out.add(vo);
        }
        return out;
    }

    public void accept(String orderNo, Long driverId) {
        unwrap(capacityDriverClient.acceptReadiness(driverId), "接单资格校验");
        DriverIdBody body = new DriverIdBody();
        body.setDriverId(driverId);
        unwrap(orderClient.accept(orderNo, body), "确认接单");
    }

    public void arrive(String orderNo, Long driverId) {
        DriverIdBody body = new DriverIdBody();
        body.setDriverId(driverId);
        unwrap(orderClient.arrive(orderNo, body), "到达上报");
    }

    public void start(String orderNo, Long driverId) {
        DriverIdBody body = new DriverIdBody();
        body.setDriverId(driverId);
        unwrap(orderClient.start(orderNo, body), "开始行程");
    }

    public void finish(String orderNo, FinishOrderBody body) {
        unwrap(orderClient.finish(orderNo, body), "完单");
    }

    private static String statusToName(Integer code) {
        if (code == null) {
            return "UNKNOWN";
        }
        if (code == STATUS_ASSIGNED) {
            return "ASSIGNED";
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
