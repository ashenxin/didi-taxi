package com.sx.capacity.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sx.capacity.dao.DriverEntityMapper;
import com.sx.capacity.model.Driver;
import com.sx.capacity.service.geo.DriverGeoRedisPool;
import com.sx.capacity.service.geo.DriverPresenceRedisPool;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Date;

@Service
public class DriverPresenceCleanupService {

    private final DriverPresenceRedisPool presencePool;
    private final DriverGeoRedisPool geoPool;
    private final DriverEntityMapper driverMapper;
    private final int batchLimit;

    public DriverPresenceCleanupService(DriverPresenceRedisPool presencePool,
                                        DriverGeoRedisPool geoPool,
                                        DriverEntityMapper driverMapper,
                                        @Value("${capacity.dispatch.driver-presence-cleanup-batch-limit:200}") int batchLimit) {
        this.presencePool = presencePool;
        this.geoPool = geoPool;
        this.driverMapper = driverMapper;
        this.batchLimit = Math.max(1, batchLimit);
    }

    public int cleanupExpired() {
        int cleaned = 0;
        for (var expired : presencePool.listExpired(batchLimit)) {
            if (!presencePool.removeIfExpired(expired)) continue;
            geoPool.remove(expired.cityCode(), expired.driverId());
            int updated = driverMapper.update(null, Wrappers.<Driver>update()
                    .set("monitor_status", 0)
                    .set("updated_at", new Date())
                    .eq("id", expired.driverId())
                    .eq("city_code", expired.cityCode())
                    .eq("monitor_status", 1)
                    .eq("is_deleted", 0));
            if (updated > 0) cleaned++;
        }
        return cleaned;
    }
}
