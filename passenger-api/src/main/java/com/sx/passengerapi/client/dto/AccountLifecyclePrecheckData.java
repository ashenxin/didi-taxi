package com.sx.passengerapi.client.dto;

import java.util.List;

/** passenger 返回的综合注销预检结果。 */
public record AccountLifecyclePrecheckData(
        String decision,
        List<BlockerData> blockers) {

    public record BlockerData(
            String domain, String code, String resourceType,
            String resourceNo, String action) {
    }
}
