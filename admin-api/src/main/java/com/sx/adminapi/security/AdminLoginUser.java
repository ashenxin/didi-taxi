package com.sx.adminapi.security;

import java.util.List;

/** JWT 校验通过后放入 {@link org.springframework.security.core.context.SecurityContext} 的 principal。 */
public record AdminLoginUser(
        long userId,
        long tokenVersion,
        String username,
        String displayName,
        List<String> roleCodes,
        String provinceCode,
        String cityCode,
        int status
) {
}
