package com.sx.adminapi.security;

import com.sx.adminapi.common.enums.ExceptionCode;
import com.sx.adminapi.common.exception.BizErrorException;
import com.sx.adminapi.model.capacity.CompanyCreateBody;
import com.sx.adminapi.model.pricing.FareRuleUpsertBody;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * 管理后台数据域：非 SUPER 按账号省/市裁剪查询与详情，防止纵向越权。
 * 接入方：订单、计价规则、运力（公司/司机/车辆）、换队申请等 BFF Service；403/404 语义见《后台管理系统_权限与接口文档》§4.7。
 */
public final class AdminDataScope {

    private AdminDataScope() {}

    /** 省、市均可为 null（null 表示该维度不追加等于条件；市 null + 省非 null 通常表示省内全部市）。 */
    public record RegionQuery(String provinceCode, String cityCode) {}

    public static AdminLoginUser requireUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof AdminLoginUser u)) {
            throw new BizErrorException(ExceptionCode.UNAUTHORIZED.getValue(), "未登录");
        }
        return u;
    }

    public static boolean isSuper(AdminLoginUser u) {
        return u.roleCodes() != null && u.roleCodes().contains("SUPER");
    }

    public static boolean isProvinceAdmin(AdminLoginUser u) {
        return u.roleCodes() != null && u.roleCodes().contains("PROVINCE_ADMIN");
    }

    public static boolean isCityOperator(AdminLoginUser u) {
        return u.roleCodes() != null && u.roleCodes().contains("CITY_OPERATOR");
    }

    /**
     * 列表/统计：将请求中的省、市与登录域合并；非法越权返回 403。
     */
    public static RegionQuery mergeRegionForQuery(AdminLoginUser u, String requestProvince, String requestCity) {
        String rp = trimToNull(requestProvince);
        String rc = trimToNull(requestCity);
        if (isSuper(u)) {
            return new RegionQuery(rp, rc);
        }
        if (isProvinceAdmin(u)) {
            String fixedP = trimToNull(u.provinceCode());
            if (fixedP == null) {
                throw new BizErrorException(ExceptionCode.FORBIDDEN.getValue(), "账号缺少省份信息");
            }
            if (rp != null && !rp.equals(fixedP)) {
                throw new BizErrorException(ExceptionCode.FORBIDDEN.getValue(), "无权查询该省份数据");
            }
            if (rc != null && !cityBelongsToProvince(rc, fixedP)) {
                throw new BizErrorException(ExceptionCode.FORBIDDEN.getValue(), "无权查询该城市数据");
            }
            return new RegionQuery(fixedP, rc);
        }
        if (isCityOperator(u)) {
            String fixedP = trimToNull(u.provinceCode());
            String fixedC = trimToNull(u.cityCode());
            if (fixedP == null || fixedC == null) {
                throw new BizErrorException(ExceptionCode.FORBIDDEN.getValue(), "账号缺少省/市信息");
            }
            if (rp != null && !rp.equals(fixedP)) {
                throw new BizErrorException(ExceptionCode.FORBIDDEN.getValue(), "无权查询该省份数据");
            }
            if (rc != null && !rc.equals(fixedC)) {
                throw new BizErrorException(ExceptionCode.FORBIDDEN.getValue(), "无权查询该城市数据");
            }
            return new RegionQuery(fixedP, fixedC);
        }
        return new RegionQuery(rp, rc);
    }

    public static void assertOrderReadable(AdminLoginUser u, String orderProvince, String orderCity) {
        if (isSuper(u)) {
            return;
        }
        if (isProvinceAdmin(u)) {
            String fixedP = u.provinceCode();
            if (fixedP == null || orderProvince == null || !fixedP.equals(orderProvince)) {
                throw new BizErrorException(ExceptionCode.NOT_FOUND.getValue(), "订单不存在");
            }
            return;
        }
        if (isCityOperator(u)) {
            String fixedC = u.cityCode();
            if (fixedC == null || orderCity == null || !fixedC.equals(orderCity)) {
                throw new BizErrorException(ExceptionCode.NOT_FOUND.getValue(), "订单不存在");
            }
        }
    }

    public static void assertFareRuleReadable(AdminLoginUser u, String ruleProvince, String ruleCity) {
        if (isSuper(u)) {
            return;
        }
        if (isProvinceAdmin(u)) {
            String fixedP = u.provinceCode();
            if (fixedP == null || ruleProvince == null || !fixedP.equals(ruleProvince)) {
                throw new BizErrorException(ExceptionCode.NOT_FOUND.getValue(), "规则不存在");
            }
            return;
        }
        if (isCityOperator(u)) {
            String fixedC = u.cityCode();
            if (fixedC == null || ruleCity == null || !fixedC.equals(ruleCity)) {
                throw new BizErrorException(ExceptionCode.NOT_FOUND.getValue(), "规则不存在");
            }
        }
    }

    /** 公司与计价规则一致：省管按省、市管按市 */
    public static void assertCompanyReadable(AdminLoginUser u, String companyProvince, String companyCity) {
        assertFareRuleReadable(u, companyProvince, companyCity);
    }

    public static void assertDriverCityReadable(AdminLoginUser u, String driverCityCode) {
        if (isSuper(u)) {
            return;
        }
        if (isProvinceAdmin(u)) {
            String fixedP = u.provinceCode();
            if (fixedP == null || driverCityCode == null || !cityBelongsToProvince(driverCityCode, fixedP)) {
                throw new BizErrorException(ExceptionCode.NOT_FOUND.getValue(), "司机不存在");
            }
            return;
        }
        if (isCityOperator(u)) {
            String fixedC = u.cityCode();
            if (fixedC == null || driverCityCode == null || !fixedC.equals(driverCityCode)) {
                throw new BizErrorException(ExceptionCode.NOT_FOUND.getValue(), "司机不存在");
            }
        }
    }

    /**
     * 创建运力公司：省管锁定本省，市管锁定本市；SUPER 不改动请求体中的省市区（由下游校验非空）。
     */
    public static CompanyCreateBody scopeCompanyWrite(AdminLoginUser u, CompanyCreateBody body) {
        if (body == null) {
            throw new BizErrorException(ExceptionCode.BAD_REQUEST.getValue(), "请求体不能为空");
        }
        if (isSuper(u)) {
            return body;
        }
        if (isProvinceAdmin(u)) {
            String fixedP = trimToNull(u.provinceCode());
            if (fixedP == null) {
                throw new BizErrorException(ExceptionCode.FORBIDDEN.getValue(), "账号缺少省份信息");
            }
            String bp = trimToNull(body.getProvinceCode());
            if (bp != null && !bp.equals(fixedP)) {
                throw new BizErrorException(ExceptionCode.FORBIDDEN.getValue(), "无权在其它省份创建数据");
            }
            body.setProvinceCode(fixedP);
            String bc = trimToNull(body.getCityCode());
            if (bc != null && !cityBelongsToProvince(bc, fixedP)) {
                throw new BizErrorException(ExceptionCode.FORBIDDEN.getValue(), "城市不属于本省");
            }
            return body;
        }
        if (isCityOperator(u)) {
            String fixedP = trimToNull(u.provinceCode());
            String fixedC = trimToNull(u.cityCode());
            if (fixedP == null || fixedC == null) {
                throw new BizErrorException(ExceptionCode.FORBIDDEN.getValue(), "账号缺少省/市信息");
            }
            body.setProvinceCode(fixedP);
            body.setCityCode(fixedC);
            return body;
        }
        throw new BizErrorException(ExceptionCode.FORBIDDEN.getValue(), "无权创建运力公司");
    }

    /**
     * 写计价规则：省管锁定本省，市管锁定本市；SUPER 不改动。
     */
    public static FareRuleUpsertBody scopeFareRuleWrite(AdminLoginUser u, FareRuleUpsertBody body) {
        if (body == null) {
            return null;
        }
        if (isSuper(u)) {
            return body;
        }
        if (isProvinceAdmin(u)) {
            String fixedP = trimToNull(u.provinceCode());
            if (fixedP == null) {
                throw new BizErrorException(ExceptionCode.FORBIDDEN.getValue(), "账号缺少省份信息");
            }
            if (body.getProvinceCode() != null && !body.getProvinceCode().isBlank()
                    && !fixedP.equals(body.getProvinceCode().trim())) {
                throw new BizErrorException(ExceptionCode.FORBIDDEN.getValue(), "无权操作其它省份规则");
            }
            body.setProvinceCode(fixedP);
            if (body.getCityCode() != null && !body.getCityCode().isBlank()
                    && !cityBelongsToProvince(body.getCityCode().trim(), fixedP)) {
                throw new BizErrorException(ExceptionCode.FORBIDDEN.getValue(), "城市不属于本省");
            }
            return body;
        }
        if (isCityOperator(u)) {
            String fixedP = trimToNull(u.provinceCode());
            String fixedC = trimToNull(u.cityCode());
            if (fixedP == null || fixedC == null) {
                throw new BizErrorException(ExceptionCode.FORBIDDEN.getValue(), "账号缺少省/市信息");
            }
            body.setProvinceCode(fixedP);
            body.setCityCode(fixedC);
            return body;
        }
        return body;
    }

    public static boolean cityBelongsToProvince(String cityCode, String provinceCode) {
        if (cityCode == null || provinceCode == null || cityCode.length() < 2 || provinceCode.length() < 2) {
            return false;
        }
        return cityCode.startsWith(provinceCode.substring(0, 2));
    }

    private static String trimToNull(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }
}
