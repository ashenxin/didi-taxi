package com.sx.order.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sx.order.dao.DriverTripRecordMapper;
import com.sx.order.model.DriverTripRecord;
import com.sx.order.model.TripOrder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.time.Duration;
import java.time.LocalDateTime;

/** 只在订单状态 CAS 成功的事务内维护，既不改变订单裁决，也不创建额外异步一致性窗口。 */
@Service
@Transactional(propagation = Propagation.MANDATORY)
public class DriverTripService {
    private final DriverTripRecordMapper records;
    public DriverTripService(DriverTripRecordMapper records) { this.records = records; }

    public void accepted(TripOrder order, long acceptedEventId, LocalDateTime now) {
        records.insert(snapshot(order, "event:" + acceptedEventId, now));
    }

    public void advanced(TripOrder before, int status, LocalDateTime now, Integer cancelBy, String reason) {
        if (before.getDriverId() == null || before.getStatus() == null || before.getStatus() < 2
                || before.getStatus() > 4) return;
        DriverTripRecord row = records.selectOne(Wrappers.<DriverTripRecord>lambdaQuery()
                .eq(DriverTripRecord::getOrderNo, before.getOrderNo())
                .eq(DriverTripRecord::getDriverId, before.getDriverId())
                .eq(DriverTripRecord::getActiveFlag, 1));
        // 升级时已经接单的存量订单：仅使用现存事实补记录，不猜测接单时间。
        if (row == null) {
            if (before.getAcceptedAt() == null) return;
            row = snapshot(before, "legacy:" + before.getId() + ":" + before.getDriverId() + ":" + before.getAcceptedAt(), before.getAcceptedAt());
            row.setArrivedAt(before.getArrivedAt()).setStartedAt(before.getStartedAt());
            records.insert(row);
        }
        var update = Wrappers.<DriverTripRecord>lambdaUpdate()
                .eq(DriverTripRecord::getId, row.getId()).eq(DriverTripRecord::getActiveFlag, 1)
                .set(DriverTripRecord::getStatus, status).set(DriverTripRecord::getUpdatedAt, now);
        if (status == 3) update.set(DriverTripRecord::getArrivedAt, now);
        if (status == 4) update.set(DriverTripRecord::getStartedAt, now);
        if (status == 5) {
            Long seconds = row.getStartedAt() == null || now.isBefore(row.getStartedAt())
                    ? null : Duration.between(row.getStartedAt(), now).getSeconds();
            update.set(DriverTripRecord::getFinishedAt, now).set(DriverTripRecord::getServiceDurationSeconds, seconds)
                    .set(DriverTripRecord::getActiveFlag, null);
        }
        if (status == 6) update.set(DriverTripRecord::getCancelledAt, now)
                .set(DriverTripRecord::getCancelBy, cancelBy).set(DriverTripRecord::getCancelReason, reason)
                .set(DriverTripRecord::getActiveFlag, null);
        if (records.update(null, update) != 1) throw new IllegalStateException("司机行程记录更新冲突");
    }

    private DriverTripRecord snapshot(TripOrder order, String source, LocalDateTime acceptedAt) {
        return new DriverTripRecord().setOrderId(order.getId()).setOrderNo(order.getOrderNo())
                .setSourceKey(source).setDriverId(order.getDriverId()).setCarId(order.getCarId())
                .setCompanyId(order.getCompanyId()).setCityCode(order.getCityCode()).setProductCode(order.getProductCode())
                .setOriginAddress(order.getOriginAddress()).setDestAddress(order.getDestAddress())
                .setStatus(2).setActiveFlag(1).setEstimatedAmount(order.getEstimatedAmount())
                .setDistanceMeters(order.getPlannedDistanceMeters()).setDistanceSource(order.getDistanceSource())
                .setOrderedAt(order.getCreatedAt()).setAcceptedAt(acceptedAt).setUpdatedAt(acceptedAt);
    }
}
