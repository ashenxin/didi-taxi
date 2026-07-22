package com.sx.passengerapi.client.dto;

/** 所有会作废当前会话的账号生命周期结果必须实现的统一契约。 */
public interface PassengerLifecycleResult {

    Long getCustomerId();

    Long getNewAuthEpoch();

    Boolean getRequireLogin();

    String getRevocationReason();
}
