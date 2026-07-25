package com.sx.wallet.lifecycle.model;

import java.util.List;
import java.util.Map;

public record WalletLifecycleParticipantResult(String decision,
                                               List<WalletLifecycleBlocker> blockers,
                                               Map<String, Object> result) {
}
