package com.sx.order.service;
import com.sx.order.dao.DriverTripRecordMapper;
import com.sx.order.model.dto.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import java.time.*;
import java.util.Set;

@Service
@Transactional(readOnly=true, isolation=Isolation.REPEATABLE_READ)
public class DriverTripQueryService {
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private final DriverTripRecordMapper records;
    public DriverTripQueryService(DriverTripRecordMapper records) { this.records = records; }

    public DriverTripPage page(long driverId, String status, LocalDate startDate, LocalDate endDate,
                               String dateField, int pageNo, int pageSize, Long snapshotId) {
        requireDriver(driverId);
        if (!Set.of("ALL","IN_PROGRESS","FINISHED","CANCELLED").contains(status)
                || !Set.of("ACCEPTED","FINISHED","CANCELLED").contains(dateField)
                || pageNo < 1 || pageNo > 100000 || pageSize < 1 || pageSize > 100
                || (snapshotId != null && snapshotId < 0)
                || (startDate != null && endDate != null && startDate.isAfter(endDate)))
            throw new IllegalArgumentException("行程查询参数无效");
        long snapshot = snapshotId == null ? records.maxId(driverId) : snapshotId;
        var filter = new DriverTripFilter(driverId, status, dateField,
                startDate == null ? null : startDate.atStartOfDay(), endDate == null ? null : endDate.plusDays(1).atStartOfDay(),
                snapshot, (long)(pageNo-1)*pageSize, pageSize);
        return new DriverTripPage(records.pageTrips(filter), records.countTrips(filter), pageNo, pageSize, Long.toString(snapshot));
    }
    public DriverTripView detail(long driverId, long id) {
        requireDriver(driverId);
        DriverTripView view = records.detail(driverId,id);
        if (view == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"行程不存在");
        return view;
    }
    public DriverTodaySummary today(long driverId) { return today(driverId, LocalDate.now(ZONE)); }
    public DriverTodaySummary today(long driverId, LocalDate date) {
        requireDriver(driverId);
        LocalDateTime start=date.atStartOfDay(), end=date.plusDays(1).atStartOfDay();
        DriverTodaySummary summary=records.today(driverId,start,end);
        summary.setBusinessDate(date); summary.setTimezone(ZONE.getId());
        summary.setPeriodStart(start); summary.setPeriodEnd(end); summary.setAsOf(LocalDateTime.now(ZONE));
        summary.setLatestTrip(records.latest(driverId,start,end));
        return summary;
    }
    private void requireDriver(long id) {
        if (id<=0) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"未授权，请重新登录");
    }
}
