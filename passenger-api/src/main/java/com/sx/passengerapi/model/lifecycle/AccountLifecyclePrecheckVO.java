package com.sx.passengerapi.model.lifecycle;

import java.util.List;

/** 面向乘客的注销预检结果。 */
public record AccountLifecyclePrecheckVO(
        String decision,
        List<BlockerVO> blockers) {

    public record BlockerVO(
            String domain, String code, String resourceType,
            String resourceNo, String action) {
    }
}
