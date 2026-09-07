package com.sx.driverapi.model.trip;
import java.util.List;
public record DriverTripPage(List<DriverTripView> list, long total, int pageNo, int pageSize, String snapshotId) {}
