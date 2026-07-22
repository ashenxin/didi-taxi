package com.sx.passenger.lifecycle.application.phone;

public record ChangeCustomerPhoneResult(long operationId,
                                        String operationNo,
                                        long customerId,
                                        long appliedLifecycleVersion,
                                        long newAuthEpoch,
                                        boolean requireLogin) {
}
