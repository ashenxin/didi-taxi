package com.sx.wallet.lifecycle.model;

public record WalletManualResolutionRequest(String operationNo, String stepCode,
                                            long agreementId, String actor,
                                            String reason, String evidence) {
    public WalletManualResolutionRequest {
        if (operationNo == null || operationNo.isBlank()) throw new IllegalArgumentException("operationNo不能为空");
        if (stepCode == null || stepCode.isBlank()) throw new IllegalArgumentException("stepCode不能为空");
        if (agreementId <= 0) throw new IllegalArgumentException("agreementId必须为正数");
        if (actor == null || actor.isBlank()) throw new IllegalArgumentException("actor不能为空");
        if (reason == null || reason.isBlank()) throw new IllegalArgumentException("reason不能为空");
        if (evidence == null || evidence.isBlank()) throw new IllegalArgumentException("evidence不能为空");
        if (actor.trim().length() > 64 || reason.trim().length() > 512
                || evidence.trim().length() > 512) {
            throw new IllegalArgumentException("人工处置字段过长");
        }
    }
}
