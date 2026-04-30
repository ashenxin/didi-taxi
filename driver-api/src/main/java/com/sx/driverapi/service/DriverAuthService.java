package com.sx.driverapi.service;

import com.sx.driverapi.auth.DriverJwtService;
import com.sx.driverapi.auth.DriverTokenVersionStore;
import com.sx.driverapi.client.CapacityDriverAuthClient;
import com.sx.driverapi.client.CapacityDriverClient;
import com.sx.driverapi.client.CoreResponseVo;
import com.sx.driverapi.client.dto.AppAuthDriverBrief;
import com.sx.driverapi.client.dto.AppPasswordLoginRequest;
import com.sx.driverapi.client.dto.AppPasswordRegisterRequest;
import com.sx.driverapi.client.dto.AppSmsLoginRequest;
import com.sx.driverapi.client.dto.AppSmsRegisterRequest;
import com.sx.driverapi.client.dto.AppSmsSendRequest;
import com.sx.driverapi.common.exception.BizErrorException;
import com.sx.driverapi.model.auth.DriverLoginResponse;
import com.sx.driverapi.model.auth.DriverProfileVO;
import com.sx.driverapi.model.capacity.DriverOnlineBody;
import feign.FeignException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@Slf4j
public class DriverAuthService {

    private final CapacityDriverAuthClient capacityDriverAuthClient;
    private final CapacityDriverClient capacityDriverClient;
    private final DriverBffService driverBffService;
    private final DriverJwtService jwtService;
    private final DriverTokenVersionStore tokenVersionStore;

    public DriverAuthService(CapacityDriverAuthClient capacityDriverAuthClient,
                             CapacityDriverClient capacityDriverClient,
                             DriverBffService driverBffService,
                             DriverJwtService jwtService,
                             DriverTokenVersionStore tokenVersionStore) {
        this.capacityDriverAuthClient = capacityDriverAuthClient;
        this.capacityDriverClient = capacityDriverClient;
        this.driverBffService = driverBffService;
        this.jwtService = jwtService;
        this.tokenVersionStore = tokenVersionStore;
    }

    public void sendSms(String phone) {
        try {
            CoreResponseVo<Void> resp = capacityDriverAuthClient.sendSms(new AppSmsSendRequest(phone));
            unwrapOk(resp);
            log.info("司机短信发送请求已提交 phone={}", maskPhone(phone));
        } catch (FeignException e) {
            log.error("司机短信发送 Feign 异常 phone={} status={}", maskPhone(phone), e.status(), e);
            throw new BizErrorException(502, "服务暂时不可用，请稍后重试");
        }
    }

    public DriverLoginResponse registerSms(String phone, String code) {
        AppAuthDriverBrief brief = unwrapData(capacityDriverAuthClient.registerSms(new AppSmsRegisterRequest(phone, code)));
        return toLoginResponse(brief);
    }

    public DriverLoginResponse registerPassword(String phone, String code, String password) {
        AppAuthDriverBrief brief = unwrapData(capacityDriverAuthClient.registerPassword(new AppPasswordRegisterRequest(phone, code, password)));
        return toLoginResponse(brief);
    }

    public DriverLoginResponse loginSms(String phone, String code) {
        AppAuthDriverBrief brief = unwrapData(capacityDriverAuthClient.loginSms(new AppSmsLoginRequest(phone, code)));
        return toLoginResponse(brief);
    }

    public DriverLoginResponse loginPassword(String phone, String password) {
        AppAuthDriverBrief brief = unwrapData(capacityDriverAuthClient.loginPassword(new AppPasswordLoginRequest(phone, password)));
        return toLoginResponse(brief);
    }

    /**
     * 登出：先拒掉名下全部待接指派（与乘客侧重新派单口径一致），再通知运力下线听单并删除 Redis 司机池 GEO，
     * 最后递增 token 版本使 JWT 失效。
     */
    public void logout(long driverId) {
        if (driverId <= 0) {
            throw new BizErrorException(400, "driverId非法");
        }
        driverBffService.rejectAllPendingAssignsOnLogout(driverId);
        try {
            DriverOnlineBody body = new DriverOnlineBody();
            body.setOnline(false);
            CoreResponseVo<Void> resp = capacityDriverClient.setOnline(driverId, body);
            if (resp != null && resp.getCode() != null && resp.getCode() == 200) {
                log.debug("登出：运力下线成功 driverId={}", driverId);
            } else {
                log.warn("登出：运力下线返回非200 driverId={} code={} msg={}",
                        driverId, resp == null ? null : resp.getCode(), resp == null ? null : resp.getMsg());
            }
        } catch (FeignException e) {
            log.warn("登出：运力 Feign 调用失败 driverId={} status={}", driverId, e.status(), e);
        }
        tokenVersionStore.nextVersion(driverId);
        log.info("司机已登出 driverId={}", driverId);
    }

    /**
     * 用 HTTP API token（audit=1）换取 WebSocket 握手用 token（audit=2），{@code tv} 不变。
     */
    public DriverLoginResponse issueWsToken(long driverId, String phone) {
        Long tv = tokenVersionStore.currentVersion(driverId);
        if (tv == null) {
            throw new BizErrorException(401, "登录已失效，请重新登录");
        }
        String wsTok = jwtService.createDriverToken(driverId, phone == null ? "" : phone, tv, 2);
        DriverProfileVO driver = new DriverProfileVO();
        driver.setId(driverId);
        driver.setPhone(phone);

        DriverLoginResponse out = new DriverLoginResponse();
        out.setAccessToken(wsTok);
        out.setTokenType("Bearer");
        out.setExpiresIn(jwtService.getExpirationSeconds());
        out.setDriver(driver);
        log.info("司机 WebSocket Token 已签发 driverId={}", driverId);
        return out;
    }

    private static String maskPhone(String phone) {
        if (phone == null || phone.length() < 4) {
            return "****";
        }
        return "****" + phone.substring(phone.length() - 4);
    }

    private static <T> T unwrapData(CoreResponseVo<T> resp) {
        if (resp == null || resp.getCode() == null) {
            throw new BizErrorException(502, "服务暂时不可用，请稍后重试");
        }
        if (resp.getCode() != 200) {
            throw new BizErrorException(resp.getCode(), resp.getMsg() == null ? "请求失败" : resp.getMsg());
        }
        if (resp.getData() == null) {
            throw new BizErrorException(502, "服务暂时不可用，请稍后重试");
        }
        return resp.getData();
    }

    private static void unwrapOk(CoreResponseVo<?> resp) {
        if (resp == null || resp.getCode() == null) {
            throw new BizErrorException(502, "服务暂时不可用，请稍后重试");
        }
        if (resp.getCode() != 200) {
            throw new BizErrorException(resp.getCode(), resp.getMsg() == null ? "请求失败" : resp.getMsg());
        }
    }

    private DriverLoginResponse toLoginResponse(AppAuthDriverBrief brief) {
        long tv = tokenVersionStore.nextVersion(brief.getId());
        String token = jwtService.createDriverToken(brief.getId(), brief.getPhone(), tv, 1);
        DriverProfileVO driver = new DriverProfileVO();
        driver.setId(brief.getId());
        driver.setPhone(brief.getPhone());
        driver.setAuditStatus(brief.getAuditStatus());

        DriverLoginResponse out = new DriverLoginResponse();
        out.setAccessToken(token);
        out.setTokenType("Bearer");
        out.setExpiresIn(jwtService.getExpirationSeconds());
        out.setDriver(driver);
        log.info("司机登录成功 driverId={} phone={}", brief.getId(), maskPhone(brief.getPhone()));
        return out;
    }
}

