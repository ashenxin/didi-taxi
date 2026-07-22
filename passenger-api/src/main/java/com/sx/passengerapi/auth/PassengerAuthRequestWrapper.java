package com.sx.passengerapi.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;

/**
 * 校验 JWT 和数据库权威状态后注入可信身份头，
 * 与网关转发行为对齐，供 Controller 沿用 {@code @RequestHeader}。
 */
public class PassengerAuthRequestWrapper extends HttpServletRequestWrapper {

    public static final String USER_ID = "X-User-Id";
    public static final String USER_PHONE = "X-User-Phone";
    public static final String AUTH_EPOCH = "X-Auth-Epoch";
    public static final String AUTH_SCOPE = "X-Auth-Scope";
    public static final String LIFECYCLE_OPERATION_NO = "X-Lifecycle-Operation-No";

    private final String userIdHeaderValue;
    private final String userPhoneHeaderValue;
    private final String authEpochHeaderValue;
    private final String authScopeHeaderValue;
    private final String lifecycleOperationNoHeaderValue;

    public PassengerAuthRequestWrapper(HttpServletRequest request, PassengerAuthContext context) {
        super(request);
        this.userIdHeaderValue = String.valueOf(context.customerId());
        this.userPhoneHeaderValue = context.phone() == null ? "" : context.phone();
        this.authEpochHeaderValue = String.valueOf(context.authEpoch());
        this.authScopeHeaderValue = context.scope().name();
        this.lifecycleOperationNoHeaderValue = context.operationNo();
    }

    @Override
    public String getHeader(String name) {
        if (USER_ID.equalsIgnoreCase(name)) {
            return userIdHeaderValue;
        }
        if (USER_PHONE.equalsIgnoreCase(name)) {
            return userPhoneHeaderValue;
        }
        if (AUTH_EPOCH.equalsIgnoreCase(name)) {
            return authEpochHeaderValue;
        }
        if (AUTH_SCOPE.equalsIgnoreCase(name)) {
            return authScopeHeaderValue;
        }
        if (LIFECYCLE_OPERATION_NO.equalsIgnoreCase(name)) {
            return lifecycleOperationNoHeaderValue;
        }
        return super.getHeader(name);
    }

    @Override
    public Enumeration<String> getHeaders(String name) {
        String trusted = getTrustedHeader(name);
        if (isTrustedHeader(name)) {
            return trusted == null
                    ? Collections.emptyEnumeration()
                    : Collections.enumeration(List.of(trusted));
        }
        return super.getHeaders(name);
    }

    @Override
    public Enumeration<String> getHeaderNames() {
        List<String> names = new ArrayList<>();
        Enumeration<String> e = super.getHeaderNames();
        while (e.hasMoreElements()) {
            String name = e.nextElement();
            if (!isTrustedHeader(name)) {
                names.add(name);
            }
        }
        names.add(USER_ID);
        names.add(USER_PHONE);
        names.add(AUTH_EPOCH);
        names.add(AUTH_SCOPE);
        if (lifecycleOperationNoHeaderValue != null) {
            names.add(LIFECYCLE_OPERATION_NO);
        }
        return Collections.enumeration(names);
    }

    private String getTrustedHeader(String name) {
        if (USER_ID.equalsIgnoreCase(name)) {
            return userIdHeaderValue;
        }
        if (USER_PHONE.equalsIgnoreCase(name)) {
            return userPhoneHeaderValue;
        }
        if (AUTH_EPOCH.equalsIgnoreCase(name)) {
            return authEpochHeaderValue;
        }
        if (AUTH_SCOPE.equalsIgnoreCase(name)) {
            return authScopeHeaderValue;
        }
        if (LIFECYCLE_OPERATION_NO.equalsIgnoreCase(name)) {
            return lifecycleOperationNoHeaderValue;
        }
        return null;
    }

    private static boolean isTrustedHeader(String name) {
        return name != null && (USER_ID.equalsIgnoreCase(name)
                || USER_PHONE.equalsIgnoreCase(name)
                || AUTH_EPOCH.equalsIgnoreCase(name)
                || AUTH_SCOPE.equalsIgnoreCase(name)
                || LIFECYCLE_OPERATION_NO.equalsIgnoreCase(name));
    }
}
