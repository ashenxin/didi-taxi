package com.sx.order.dao;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sx.order.model.DriverTripRecord;
import com.sx.order.model.dto.*;
import org.apache.ibatis.annotations.Param;
import java.time.LocalDateTime;
import java.util.List;
public interface DriverTripRecordMapper extends BaseMapper<DriverTripRecord> {
    long maxId(@Param("driverId") long driverId);
    long countTrips(@Param("f") DriverTripFilter filter);
    List<DriverTripView> pageTrips(@Param("f") DriverTripFilter filter);
    DriverTripView detail(@Param("driverId") long driverId, @Param("id") long id);
    DriverTodaySummary today(@Param("driverId") long driverId, @Param("start") LocalDateTime start,
                             @Param("end") LocalDateTime end);
    DriverTripView latest(@Param("driverId") long driverId, @Param("start") LocalDateTime start,
                          @Param("end") LocalDateTime end);
}
