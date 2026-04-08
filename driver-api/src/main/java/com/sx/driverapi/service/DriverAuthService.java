package com.sx.driverapi.service;

import com.sx.driverapi.auth.DriverJwtService;
import com.sx.driverapi.client.CapacityDriverAuthClient;
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
import feign.FeignException;
import org.springframework.stereotype.Service;

@Service
public class DriverAuthService {

    private final CapacityDriverAuthClient capacityDriverAuthClient;
    private final DriverJwtService jwtService;

    public DriverAuthService(CapacityDriverAuthClient capacityDriverAuthClient, DriverJwtService jwtService) {
        this.capacityDriverAuthClient = capacityDriverAuthClient;
        this.jwtService = jwtService;
    }

    public void sendSms(String phone) {
        try {
            CoreResponseVo<Void> resp = capacityDriverAuthClient.sendSms(new AppSmsSendRequest(phone));
            unwrapOk(resp);
        } catch (FeignException e) {
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
        String token = jwtService.createDriverToken(brief.getId(), brief.getPhone());
        DriverProfileVO driver = new DriverProfileVO();
        driver.setId(brief.getId());
        driver.setPhone(brief.getPhone());
        driver.setAuditStatus(brief.getAuditStatus());

        DriverLoginResponse out = new DriverLoginResponse();
        out.setAccessToken(token);
        out.setTokenType("Bearer");
        out.setExpiresIn(jwtService.getExpirationSeconds());
        out.setDriver(driver);
        return out;
    }
}

