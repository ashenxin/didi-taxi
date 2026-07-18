package com.sx.adminapi.security;

import com.sx.adminapi.common.exception.BizErrorException;
import com.sx.adminapi.model.capacity.CompanyCreateBody;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AdminDataScopeTest {

    @Test
    void cityOperatorQueryIsLockedToOwnRegion() {
        AdminLoginUser user = user(List.of("CITY_OPERATOR"), "330000", "330100");

        AdminDataScope.RegionQuery query = AdminDataScope.mergeRegionForQuery(user, null, null);

        assertThat(query.provinceCode()).isEqualTo("330000");
        assertThat(query.cityCode()).isEqualTo("330100");
    }

    @Test
    void cityOperatorCannotQueryAnotherCity() {
        AdminLoginUser user = user(List.of("CITY_OPERATOR"), "330000", "330100");

        BizErrorException error = assertThrows(BizErrorException.class,
                () -> AdminDataScope.mergeRegionForQuery(user, "330000", "330200"));

        assertThat(error.getErrorCode()).isEqualTo(403);
    }

    @Test
    void provinceAdminCannotReadCrossProvinceOrder() {
        AdminLoginUser user = user(List.of("PROVINCE_ADMIN"), "330000", null);

        BizErrorException error = assertThrows(BizErrorException.class,
                () -> AdminDataScope.assertOrderReadable(user, "310000", "310100"));

        assertThat(error.getErrorCode()).isEqualTo(404);
        assertThat(error.getErrorMessage()).isEqualTo("订单不存在");
    }

    @Test
    void cityOperatorCompanyWriteUsesTrustedRegion() {
        AdminLoginUser user = user(List.of("CITY_OPERATOR"), "330000", "330100");
        CompanyCreateBody body = new CompanyCreateBody();
        body.setProvinceCode("310000");
        body.setCityCode("310100");

        CompanyCreateBody scoped = AdminDataScope.scopeCompanyWrite(user, body);

        assertThat(scoped.getProvinceCode()).isEqualTo("330000");
        assertThat(scoped.getCityCode()).isEqualTo("330100");
    }

    @Test
    void provinceAdminRejectsCityOutsideProvincePrefix() {
        AdminLoginUser user = user(List.of("PROVINCE_ADMIN"), "330000", null);

        BizErrorException error = assertThrows(BizErrorException.class,
                () -> AdminDataScope.mergeRegionForQuery(user, null, "310100"));

        assertThat(error.getErrorCode()).isEqualTo(403);
    }

    private static AdminLoginUser user(List<String> roles, String provinceCode, String cityCode) {
        return new AdminLoginUser(1L, 1L, "admin", "管理员", roles, provinceCode, cityCode, 1);
    }
}
