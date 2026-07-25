package com.sx.calculate.lifecycle.model;

import java.util.List;
import java.util.Map;

public record CalculateLifecycleParticipantResult(
        CalculateLifecycleDecision decision,
        List<CalculateLifecycleBlocker> blockers,
        Map<String, Object> result) {

    public CalculateLifecycleParticipantResult {
        blockers = blockers == null ? List.of() : List.copyOf(blockers);
        result = result == null ? Map.of() : Map.copyOf(result);
    }
}
