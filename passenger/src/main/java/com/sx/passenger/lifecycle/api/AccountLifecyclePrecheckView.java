package com.sx.passenger.lifecycle.api;

import java.util.List;

/** 正式建栅栏前的只读综合预检结果。 */
public record AccountLifecyclePrecheckView(
        String decision,
        List<BlockerView> blockers) {

    public record BlockerView(
            String domain,
            String code,
            String resourceType,
            String resourceNo,
            String action) {
    }
}
