package com.sx.passenger.lifecycle.orchestration;

import java.util.List;
import java.util.Map;

/** 参与者同步预检或异步动作的标准裁决、阻断项和脱敏结果。 */
public record LifecycleParticipantResult(
        String decision,
        List<Blocker> blockers,
        Map<String, Object> result) {

    /** 将空集合规范化并防御性复制。 */
    public LifecycleParticipantResult {
        blockers = blockers == null ? List.of() : List.copyOf(blockers);
        result = result == null ? Map.of() : Map.copyOf(result);
    }

    /** 单个域内阻断原因、资源及建议动作。 */
    public record Blocker(String code, String resourceType, String resourceNo, String action) {}
}
