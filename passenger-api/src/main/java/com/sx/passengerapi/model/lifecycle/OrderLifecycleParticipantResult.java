package com.sx.passengerapi.model.lifecycle;

import java.util.List;

public record OrderLifecycleParticipantResult(
        String decision,
        List<OrderLifecycleBlocker> blockers) {

    public record OrderLifecycleBlocker(
            String code, String resourceType, String resourceNo, String action) {
    }
}
