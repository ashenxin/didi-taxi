package com.sx.passengerapi.auth.action;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.http.server.PathContainer;

import static org.assertj.core.api.Assertions.assertThat;

class PassengerActionResolverTest {

    private final PassengerActionResolver resolver = new PassengerActionResolver();

    @ParameterizedTest
    @CsvSource({
            "POST,/app/api/v1/auth/logout,SESSION_LOGOUT",
            "POST,/app/api/v1/auth/ws-token,WS_CONNECT",
            "POST,/app/api/v1/orders,RIDE_CREATE",
            "POST,/app/api/v1/orders/create,RIDE_CREATE",
            "GET,/app/api/v1/orders,ORDER_READ",
            "GET,/app/api/v1/orders/O-1,ORDER_READ",
            "POST,/app/api/v1/orders/O-1/cancel,ORDER_CANCEL",
            "GET,/app/api/v1/orders/O-1/settlement,ORDER_READ",
            "POST,/app/api/v1/orders/O-1/payments,DEBT_PAYMENT",
            "GET,/app/api/v1/settings/profile,PROFILE_READ",
            "POST,/app/api/v1/account-lifecycle/cancellations/precheck,ACCOUNT_CANCEL",
            "POST,/app/api/v1/account-lifecycle/cancellations/sms/send,ACCOUNT_CANCEL",
            "POST,/app/api/v1/account-lifecycle/cancellations,ACCOUNT_CANCEL",
            "GET,/app/api/v1/account-lifecycle/operations/LC-1,ACCOUNT_CANCEL",
            "POST,/app/api/v1/account-lifecycle/operations/LC-1/abort,ACCOUNT_CANCEL",
            "POST,/app/api/v1/account-lifecycle/operations/LC-1/recheck,ACCOUNT_CANCEL",
            "POST,/app/api/v1/account-lifecycle/phone-changes/sms/send,PHONE_CHANGE",
            "POST,/app/api/v1/account-lifecycle/phone-changes,PHONE_CHANGE",
            "POST,/app/api/v1/settings/phone-change/sms/send,PHONE_CHANGE",
            "POST,/app/api/v1/settings/phone-change/confirm,PHONE_CHANGE",
            "POST,/app/api/v1/settings/account-cancel/sms/send,ACCOUNT_CANCEL",
            "POST,/app/api/v1/settings/account-cancel/confirm,ACCOUNT_CANCEL",
            "GET,/app/api/v1/wallet/summary,WALLET_READ",
            "GET,/app/api/v1/wallet/auto-pay/agreements,WALLET_READ",
            "POST,/app/api/v1/wallet/auto-pay/agreements/sign,AUTO_PAY_SIGN",
            "POST,/app/api/v1/wallet/auto-pay/agreements/AG-1/default,AUTO_PAY_MANAGE",
            "POST,/app/api/v1/wallet/auto-pay/agreements/AG-1/close,AUTO_PAY_MANAGE",
            "GET,/app/api/v1/wallet/coupons,COUPON_READ",
            "GET,/app/api/v1/wallet/coupons/available,COUPON_READ",
            "GET,/app/api/v1/wallet/coupons/claimable,COUPON_READ",
            "POST,/app/api/v1/wallet/coupons/claim,COUPON_CLAIM",
            "POST,/app/api/v1/wallet/coupons/claim-all,COUPON_CLAIM",
            "GET,/app/api/v1/benefits/overview,BENEFIT_READ",
            "GET,/app/api/v1/benefits/points,BENEFIT_READ",
            "POST,/app/api/v1/benefits/sign-in,BENEFIT_SIGN_IN"
    })
    void resolvesEveryProtectedPassengerRoute(String method, String path, PassengerActionCode expected) {
        assertThat(resolver.resolve(method, PathContainer.parsePath(path))).contains(expected);
    }

    @ParameterizedTest
    @CsvSource({
            "GET,/app/api/v1/not-mapped",
            "DELETE,/app/api/v1/orders/O-1",
            "POST,/app/api/v1/wallet/coupons/available"
    })
    void rejectsUnmappedMethodAndPathCombinations(String method, String path) {
        assertThat(resolver.resolve(method, PathContainer.parsePath(path))).isEmpty();
    }
}
