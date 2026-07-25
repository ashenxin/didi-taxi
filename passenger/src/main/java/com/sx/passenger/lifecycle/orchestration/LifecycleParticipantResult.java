package com.sx.passenger.lifecycle.orchestration;

import java.util.List;
import java.util.Map;

public record LifecycleParticipantResult(
        String decision,
        List<Blocker> blockers,
        Map<String, Object> result) {

    public LifecycleParticipantResult {
        blockers = blockers == null ? List.of() : List.copyOf(blockers);
        result = result == null ? Map.of() : Map.copyOf(result);
    }

    public record Blocker(String code, String resourceType, String resourceNo, String action) {}
}
