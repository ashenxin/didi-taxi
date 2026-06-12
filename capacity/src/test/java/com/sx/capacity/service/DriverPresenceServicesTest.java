package com.sx.capacity.service;

import com.sx.capacity.config.CapacityDispatchProperties;
import com.sx.capacity.dao.DriverEntityMapper;
import com.sx.capacity.model.Driver;
import com.sx.capacity.service.geo.DriverGeoRedisPool;
import com.sx.capacity.service.geo.DriverPresenceRedisPool;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DriverPresenceServicesTest {

    private final DriverEntityMapper driverMapper = mock(DriverEntityMapper.class);
    private final DriverGeoRedisPool geoPool = mock(DriverGeoRedisPool.class);
    private final DriverPresenceRedisPool presencePool = mock(DriverPresenceRedisPool.class);
    private final LateDispatchMatchService lateDispatchMatchService = mock(LateDispatchMatchService.class);
    private final DriverStatusService statusService = new DriverStatusService(
            driverMapper, geoPool, presencePool, lateDispatchMatchService, new CapacityDispatchProperties());

    @Test
    void heartbeatWithoutCoordinatesStillRenewsPresence() {
        when(driverMapper.selectOne(any())).thenReturn(listeningDriver());

        statusService.heartbeat(900006L, null, null);

        verify(presencePool).touch("330100", 900006L);
        verify(geoPool, never()).add(any(), any(), anyDouble(), anyDouble());
    }

    @Test
    void heartbeatWithCoordinatesUpdatesGeoAndPresence() {
        when(driverMapper.selectOne(any())).thenReturn(listeningDriver());

        statusService.heartbeat(900006L, 30.25, 120.21);

        verify(geoPool).add("330100", 900006L, 30.25, 120.21);
        verify(presencePool).touch("330100", 900006L);
    }

    @Test
    void heartbeatRejectsOfflineDriver() {
        when(driverMapper.selectOne(any())).thenReturn(listeningDriver().setMonitorStatus(0));

        assertThrows(IllegalArgumentException.class, () -> statusService.heartbeat(900006L, null, null));
    }

    @Test
    void cleanupRemovesExpiredDriverFromGeoAndMarksOffline() {
        DriverPresenceRedisPool.ExpiredPresence expired =
                new DriverPresenceRedisPool.ExpiredPresence("330100", 900006L, System.currentTimeMillis());
        when(presencePool.listExpired(200)).thenReturn(List.of(expired));
        when(presencePool.removeIfExpired(expired)).thenReturn(true);
        DriverPresenceCleanupService cleanup = new DriverPresenceCleanupService(presencePool, geoPool, driverMapper, 200);

        cleanup.cleanupExpired();

        verify(geoPool).remove("330100", 900006L);
        verify(driverMapper).update(any(), any());
    }

    private static Driver listeningDriver() {
        return new Driver()
                .setId(900006L)
                .setCityCode("330100")
                .setCanAcceptOrder(1)
                .setMonitorStatus(1)
                .setIsDeleted(0);
    }
}
