package com.sx.passengerapi.auth.action;

import org.springframework.http.server.PathContainer;
import org.springframework.stereotype.Component;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

import java.util.List;
import java.util.Optional;

import static com.sx.passengerapi.auth.action.PassengerActionCode.ACCOUNT_CANCEL;
import static com.sx.passengerapi.auth.action.PassengerActionCode.AUTO_PAY_MANAGE;
import static com.sx.passengerapi.auth.action.PassengerActionCode.AUTO_PAY_SIGN;
import static com.sx.passengerapi.auth.action.PassengerActionCode.BENEFIT_READ;
import static com.sx.passengerapi.auth.action.PassengerActionCode.BENEFIT_SIGN_IN;
import static com.sx.passengerapi.auth.action.PassengerActionCode.COUPON_CLAIM;
import static com.sx.passengerapi.auth.action.PassengerActionCode.COUPON_READ;
import static com.sx.passengerapi.auth.action.PassengerActionCode.DEBT_PAYMENT;
import static com.sx.passengerapi.auth.action.PassengerActionCode.ORDER_CANCEL;
import static com.sx.passengerapi.auth.action.PassengerActionCode.ORDER_READ;
import static com.sx.passengerapi.auth.action.PassengerActionCode.PHONE_CHANGE;
import static com.sx.passengerapi.auth.action.PassengerActionCode.PROFILE_READ;
import static com.sx.passengerapi.auth.action.PassengerActionCode.RIDE_CREATE;
import static com.sx.passengerapi.auth.action.PassengerActionCode.SESSION_LOGOUT;
import static com.sx.passengerapi.auth.action.PassengerActionCode.WALLET_READ;
import static com.sx.passengerapi.auth.action.PassengerActionCode.WS_CONNECT;

@Component
public class PassengerActionResolver {

    private static final List<RouteRule> RULES = List.of(
            rule("POST", "/app/api/v1/auth/logout", SESSION_LOGOUT),
            rule("POST", "/app/api/v1/auth/ws-token", WS_CONNECT),
            rule("POST", "/app/api/v1/orders/{orderNo}/payments", DEBT_PAYMENT),
            rule("GET", "/app/api/v1/orders/{orderNo}/settlement", ORDER_READ),
            rule("POST", "/app/api/v1/orders/{orderNo}/cancel", ORDER_CANCEL),
            rule("POST", "/app/api/v1/orders/create", RIDE_CREATE),
            rule("POST", "/app/api/v1/orders", RIDE_CREATE),
            rule("GET", "/app/api/v1/orders/{orderNo}", ORDER_READ),
            rule("GET", "/app/api/v1/orders", ORDER_READ),
            rule("GET", "/app/api/v1/settings/profile", PROFILE_READ),
            rule("GET", "/app/api/v1/account-lifecycle/operations/{operationNo}", ACCOUNT_CANCEL),
            rule("POST", "/app/api/v1/account-lifecycle/operations/{operationNo}/abort", ACCOUNT_CANCEL),
            rule("POST", "/app/api/v1/account-lifecycle/operations/{operationNo}/recheck", ACCOUNT_CANCEL),
            rule("POST", "/app/api/v1/account-lifecycle/cancellations/**", ACCOUNT_CANCEL),
            rule("POST", "/app/api/v1/account-lifecycle/cancellations", ACCOUNT_CANCEL),
            rule("POST", "/app/api/v1/account-lifecycle/phone-changes/**", PHONE_CHANGE),
            rule("POST", "/app/api/v1/account-lifecycle/phone-changes", PHONE_CHANGE),
            rule("POST", "/app/api/v1/settings/phone-change/**", PHONE_CHANGE),
            rule("POST", "/app/api/v1/settings/account-cancel/**", ACCOUNT_CANCEL),
            rule("POST", "/app/api/v1/wallet/auto-pay/agreements/sign", AUTO_PAY_SIGN),
            rule("POST", "/app/api/v1/wallet/auto-pay/agreements/{agreementId}/default", AUTO_PAY_MANAGE),
            rule("POST", "/app/api/v1/wallet/auto-pay/agreements/{agreementId}/close", AUTO_PAY_MANAGE),
            rule("GET", "/app/api/v1/wallet/auto-pay/agreements", WALLET_READ),
            rule("GET", "/app/api/v1/wallet/summary", WALLET_READ),
            rule("POST", "/app/api/v1/wallet/coupons/claim-all", COUPON_CLAIM),
            rule("POST", "/app/api/v1/wallet/coupons/claim", COUPON_CLAIM),
            rule("GET", "/app/api/v1/wallet/coupons/**", COUPON_READ),
            rule("GET", "/app/api/v1/wallet/coupons", COUPON_READ),
            rule("POST", "/app/api/v1/benefits/sign-in", BENEFIT_SIGN_IN),
            rule("GET", "/app/api/v1/benefits/overview", BENEFIT_READ),
            rule("GET", "/app/api/v1/benefits/points", BENEFIT_READ));

    public Optional<PassengerActionCode> resolve(String method, PathContainer path) {
        if (method == null || path == null) {
            return Optional.empty();
        }
        return RULES.stream()
                .filter(rule -> rule.method().equalsIgnoreCase(method) && rule.pattern().matches(path))
                .map(RouteRule::action)
                .findFirst();
    }

    private static RouteRule rule(String method, String path, PassengerActionCode action) {
        return new RouteRule(method, PathPatternParser.defaultInstance.parse(path), action);
    }

    private record RouteRule(String method, PathPattern pattern, PassengerActionCode action) {
    }
}
