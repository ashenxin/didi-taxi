package com.sx.capacity.service;

import com.sx.capacity.dao.CarEntityMapper;
import com.sx.capacity.dao.DriverEntityMapper;
import com.sx.capacity.model.Car;
import com.sx.capacity.model.Driver;
import com.sx.capacity.model.dto.NearestDriverResult;
import com.sx.capacity.service.geo.DriverGeoRedisPool;
import com.sx.capacity.service.geo.DriverPresenceRedisPool;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class NearestDriverQueryServiceTest {

    private final DriverEntityMapper driverMapper = mock(DriverEntityMapper.class);
    private final CarEntityMapper carMapper = mock(CarEntityMapper.class);
    private final DriverGeoRedisPool geoPool = mock(DriverGeoRedisPool.class);
    private final DriverPresenceRedisPool presencePool = mock(DriverPresenceRedisPool.class);
    private final DriverPassengerMatchBlockService matchBlockService = mock(DriverPassengerMatchBlockService.class);
    private final NearestDriverQueryService service = new NearestDriverQueryService(
            driverMapper, carMapper, geoPool, presencePool, matchBlockService, 3000);

    @Test
    void topNCandidatesSkipBlockedDriversBeforeApplyingLimit() {
        when(geoPool.listNearestDriverIds("330100", 30.25, 120.21, 3000, 32))
                .thenReturn(List.of(1L, 2L));
        when(matchBlockService.isBlocked(1L, 88L)).thenReturn(true);
        when(matchBlockService.isBlocked(2L, 88L)).thenReturn(false);
        when(presencePool.isFresh("330100", 2L)).thenReturn(true);
        when(carMapper.selectOne(any())).thenReturn(car(2L));
        when(driverMapper.selectById(2L)).thenReturn(driver(2L));

        List<NearestDriverResult> results = service.findNearestEligibleDrivers(
                "330100", "ECONOMY", 30.25, 120.21, 1, 88L);

        assertThat(results).hasSize(1);
        assertThat(results.getFirst().getDriverId()).isEqualTo(2L);
        verify(driverMapper, never()).selectById(1L);
    }

    private static Driver driver(Long id) {
        return new Driver()
                .setId(id)
                .setCompanyId(100L + id)
                .setCityCode("330100")
                .setCanAcceptOrder(1)
                .setMonitorStatus(1)
                .setIsDeleted(0);
    }

    private static Car car(Long driverId) {
        return new Car()
                .setId(200L + driverId)
                .setDriverId(driverId)
                .setCityCode("330100")
                .setCarNo("浙A000" + driverId)
                .setRideTypeId("ECONOMY")
                .setCarState(0)
                .setIsDeleted(0);
    }
}
