package com.sx.order.lifecycle.model;

import java.util.List;

public record OrderLifecycleParticipantResult(
        OrderLifecycleDecision decision,
        List<OrderLifecycleBlocker> blockers) {

    public OrderLifecycleParticipantResult {
        blockers = blockers == null ? List.of() : List.copyOf(blockers);
    }
}
