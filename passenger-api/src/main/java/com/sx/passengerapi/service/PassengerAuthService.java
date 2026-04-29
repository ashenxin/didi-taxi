package com.sx.passengerapi.service;

import com.sx.passengerapi.auth.AppJwtService;
import com.sx.passengerapi.auth.PassengerTokenVersionStore;
import com.sx.passengerapi.client.PassengerCoreAuthClient;
import com.sx.passengerapi.client.dto.AppAuthCustomerBrief;
import com.sx.passengerapi.client.dto.AppLoginPasswordRequest;
import com.sx.passengerapi.client.dto.AppSmsLoginRequest;
import com.sx.passengerapi.client.dto.AppSmsSendRequest;
import com.sx.passengerapi.common.exception.BizErrorException;
import com.sx.passengerapi.common.vo.ResponseVo;
import com.sx.passengerapi.model.auth.CustomerLoginResponse;
import com.sx.passengerapi.model.auth.CustomerProfileVO;
import com.sx.passengerapi.model.auth.PassengerLogoutResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@Slf4j
public class PassengerAuthService {

    private final PassengerCoreAuthClient passengerCoreAuthClient;
    private final AppJwtService jwtService;
    private final PassengerTokenVersionStore tokenVersionStore;
    private final PassengerOrderService passengerOrderService;

    public PassengerAuthService(
            PassengerCoreAuthClient passengerCoreAuthClient,
            AppJwtService jwtService,
            PassengerTokenVersionStore tokenVersionStore,
            PassengerOrderService passengerOrderService) {
        this.passengerCoreAuthClient = passengerCoreAuthClient;
        this.jwtService = jwtService;
        this.tokenVersionStore = tokenVersionStore;
        this.passengerOrderService = passengerOrderService;
    }

    public void sendSms(String phone) {
        ResponseVo<Void> body = passengerCoreAuthClient.sendSms(new AppSmsSendRequest(phone));
        if (body == null || body.getCode() == null) {
            throw new BizErrorException(502, "服务暂时不可用，请稍后重试");
        }
        if (body.getCode() != 200) {
            throw new BizErrorException(body.getCode(), body.getMsg());
        }
        log.info("乘客短信发送请求已提交 phone={}", maskPhone(phone));
    }

    public CustomerLoginResponse loginSms(String phone, String code) {
        AppAuthCustomerBrief brief = unwrap(passengerCoreAuthClient.loginSms(new AppSmsLoginRequest(phone, code)));
        return toLoginResponse(brief);
    }

    public CustomerLoginResponse loginPassword(String phone, String password) {
        AppAuthCustomerBrief brief = unwrap(passengerCoreAuthClient.loginPassword(new AppLoginPasswordRequest(phone, password)));
        return toLoginResponse(brief);
    }

    /**
     * 登出：到达前在途单按 PRD §5.6 代乘客取消；到达后/行程中不取消仅提示；最后递增 token 版本使 JWT 失效。
     */
    public PassengerLogoutResult logout(long passengerId) {
        if (passengerId <= 0) {
            throw new BizErrorException(400, "乘客ID非法");
        }
        PassengerLogoutResult side = passengerOrderService.cancelInFlightOrdersOnPassengerLogout(passengerId);
        tokenVersionStore.nextVersion(passengerId);
        log.info("乘客已登出 customerId={}", passengerId);
        return side;
    }

    private AppAuthCustomerBrief unwrap(ResponseVo<AppAuthCustomerBrief> body) {
        if (body == null) {
            throw new BizErrorException(502, "服务暂时不可用，请稍后重试");
        }
        Integer code = body.getCode();
        if (code == null) {
            throw new BizErrorException(502, "服务暂时不可用，请稍后重试");
        }
        if (code != 200) {
            throw new BizErrorException(code, body.getMsg());
        }
        if (body.getData() == null || body.getData().getId() == null) {
            throw new BizErrorException(502, "服务暂时不可用，请稍后重试");
        }
        return body.getData();
    }

    private CustomerLoginResponse toLoginResponse(AppAuthCustomerBrief brief) {
        CustomerProfileVO profile = new CustomerProfileVO();
        profile.setId(brief.getId());
        profile.setPhone(brief.getPhone());
        profile.setNickname(brief.getNickname());

        long tv = tokenVersionStore.nextVersion(brief.getId());
        String token = jwtService.createPassengerToken(brief.getId(), brief.getPhone(), tv);
        CustomerLoginResponse resp = new CustomerLoginResponse();
        resp.setAccessToken(token);
        resp.setTokenType("Bearer");
        resp.setExpiresIn(jwtService.getExpirationSeconds());
        resp.setCustomer(profile);
        log.info("乘客登录成功 customerId={} phone={}", brief.getId(), maskPhone(brief.getPhone()));
        return resp;
    }

    private static String maskPhone(String phone) {
        if (phone == null || phone.length() < 4) {
            return "****";
        }
        return "****" + phone.substring(phone.length() - 4);
    }
}

