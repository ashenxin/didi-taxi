package com.sx.passengerapi.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 校验 JWT 后注入 {@code X-User-Id}，与网关转发行为对齐，供 Controller 沿用 {@code @RequestHeader}。
 */
public class PassengerAuthRequestWrapper extends HttpServletRequestWrapper {

    private final String userIdHeaderValue;

    public PassengerAuthRequestWrapper(HttpServletRequest request, long customerId) {
        super(request);
        this.userIdHeaderValue = String.valueOf(customerId);
    }

    @Override
    public String getHeader(String name) {
        if ("X-User-Id".equalsIgnoreCase(name)) {
            return userIdHeaderValue;
        }
        return super.getHeader(name);
    }

    @Override
    public Enumeration<String> getHeaders(String name) {
        if ("X-User-Id".equalsIgnoreCase(name)) {
            return Collections.enumeration(List.of(userIdHeaderValue));
        }
        return super.getHeaders(name);
    }

    @Override
    public Enumeration<String> getHeaderNames() {
        Set<String> names = new HashSet<>();
        Enumeration<String> e = super.getHeaderNames();
        while (e.hasMoreElements()) {
            names.add(e.nextElement());
        }
        names.add("X-User-Id");
        return Collections.enumeration(new ArrayList<>(names));
    }
}
