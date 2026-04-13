package com.sx.passengerapi.service;

import com.sx.passengerapi.auth.AppJwtService;
import com.sx.passengerapi.client.PassengerCoreAuthClient;
import com.sx.passengerapi.client.dto.AppAuthCustomerBrief;
import com.sx.passengerapi.client.dto.AppLoginPasswordRequest;
import com.sx.passengerapi.client.dto.AppSmsLoginRequest;
import com.sx.passengerapi.client.dto.AppSmsSendRequest;
import com.sx.passengerapi.common.exception.BizErrorException;
import com.sx.passengerapi.common.vo.ResponseVo;
import com.sx.passengerapi.model.auth.CustomerLoginResponse;
import com.sx.passengerapi.model.auth.CustomerProfileVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@Slf4j
public class PassengerAuthService {

    private final PassengerCoreAuthClient passengerCoreAuthClient;
    private final AppJwtService jwtService;

    public PassengerAuthService(PassengerCoreAuthClient passengerCoreAuthClient, AppJwtService jwtService) {
        this.passengerCoreAuthClient = passengerCoreAuthClient;
        this.jwtService = jwtService;
    }

    public void sendSms(String phone) {
        ResponseVo<Void> body = passengerCoreAuthClient.sendSms(new AppSmsSendRequest(phone));
        if (body == null || body.getCode() == null) {
            throw new BizErrorException(502, "服务暂时不可用，请稍后重试");
        }
        if (body.getCode() != 200) {
            throw new BizErrorException(body.getCode(), body.getMsg());
        }
        log.info("passenger sms send requested phone={}", maskPhone(phone));
    }

    public CustomerLoginResponse loginSms(String phone, String code) {
        AppAuthCustomerBrief brief = unwrap(passengerCoreAuthClient.loginSms(new AppSmsLoginRequest(phone, code)));
        return toLoginResponse(brief);
    }

    public CustomerLoginResponse loginPassword(String phone, String password) {
        AppAuthCustomerBrief brief = unwrap(passengerCoreAuthClient.loginPassword(new AppLoginPasswordRequest(phone, password)));
        return toLoginResponse(brief);
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

        String token = jwtService.createPassengerToken(brief.getId(), brief.getPhone());
        CustomerLoginResponse resp = new CustomerLoginResponse();
        resp.setAccessToken(token);
        resp.setTokenType("Bearer");
        resp.setExpiresIn(jwtService.getExpirationSeconds());
        resp.setCustomer(profile);
        log.info("passenger login success customerId={} phone={}", brief.getId(), maskPhone(brief.getPhone()));
        return resp;
    }

    private static String maskPhone(String phone) {
        if (phone == null || phone.length() < 4) {
            return "****";
        }
        return "****" + phone.substring(phone.length() - 4);
    }
}

