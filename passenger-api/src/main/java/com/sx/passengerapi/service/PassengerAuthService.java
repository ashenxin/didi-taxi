package com.sx.passengerapi.service;

import com.sx.passengerapi.auth.AppJwtService;
import com.sx.passengerapi.auth.PassengerAuthContext;
import com.sx.passengerapi.auth.PassengerSessionScope;
import com.sx.passengerapi.client.PassengerCoreAuthClient;
import com.sx.passengerapi.client.PassengerCoreAuthStateClient;
import com.sx.passengerapi.client.dto.AppAuthCustomerBrief;
import com.sx.passengerapi.client.dto.AppLoginPasswordRequest;
import com.sx.passengerapi.client.dto.AppSmsLoginRequest;
import com.sx.passengerapi.client.dto.AppSmsSendRequest;
import com.sx.passengerapi.client.dto.AppSmsSendResult;
import com.sx.passengerapi.client.dto.InternalLogoutRequest;
import com.sx.passengerapi.client.dto.InternalLogoutResponse;
import com.sx.passengerapi.common.exception.BizErrorException;
import com.sx.passengerapi.common.vo.ResponseVo;
import com.sx.passengerapi.model.auth.CustomerLoginResponse;
import com.sx.passengerapi.model.auth.CustomerProfileVO;
import com.sx.passengerapi.model.auth.PassengerLogoutResult;
import com.sx.passengerapi.model.auth.SmsSendResult;
import com.sx.passengerapi.ws.PassengerWsProperties;
import com.sx.passengerapi.ws.PassengerWsSessionRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import static com.sx.passengerapi.auth.PassengerSessionScope.NORMAL;

@Service
@Slf4j
public class PassengerAuthService {

    private final PassengerCoreAuthClient passengerCoreAuthClient;
    private final PassengerCoreAuthStateClient authStateClient;
    private final AppJwtService jwtService;
    private final PassengerOrderService passengerOrderService;
    private final PassengerWsProperties passengerWsProperties;
    private final PassengerWsSessionRegistry sessions;

    public PassengerAuthService(
            PassengerCoreAuthClient passengerCoreAuthClient,
            PassengerCoreAuthStateClient authStateClient,
            AppJwtService jwtService,
            PassengerOrderService passengerOrderService,
            PassengerWsProperties passengerWsProperties,
            PassengerWsSessionRegistry sessions) {
        this.passengerCoreAuthClient = passengerCoreAuthClient;
        this.authStateClient = authStateClient;
        this.jwtService = jwtService;
        this.passengerOrderService = passengerOrderService;
        this.passengerWsProperties = passengerWsProperties;
        this.sessions = sessions;
    }

    public SmsSendResult sendSms(String phone) {
        ResponseVo<AppSmsSendResult> body = passengerCoreAuthClient.sendSms(new AppSmsSendRequest(phone));
        if (body == null || body.getCode() == null) {
            throw new BizErrorException(502, "服务暂时不可用，请稍后重试");
        }
        if (body.getCode() != 200) {
            throw new BizErrorException(body.getCode(), body.getMsg());
        }
        log.info("乘客短信发送请求已提交");
        AppSmsSendResult data = body.getData();
        return new SmsSendResult(data == null ? null : data.getMockCode());
    }

    public CustomerLoginResponse loginSms(String phone, String code) {
        AppAuthCustomerBrief brief = unwrap(passengerCoreAuthClient.loginSms(new AppSmsLoginRequest(phone, code)));
        return toLoginResponse(brief);
    }

    public CustomerLoginResponse loginPassword(String phone, String password) {
        AppAuthCustomerBrief brief = unwrap(
                passengerCoreAuthClient.loginPassword(new AppLoginPasswordRequest(phone, password)));
        return toLoginResponse(brief);
    }

    /** 先由 passenger core CAS 失效认证代次，再撤销本节点 WS，最后处理订单清单。 */
    public PassengerLogoutResult logout(long passengerId, long tokenAuthEpoch) {
        if (passengerId <= 0 || tokenAuthEpoch <= 0) {
            throw new BizErrorException(400, "乘客认证信息非法");
        }
        unwrapLogout(authStateClient.logout(new InternalLogoutRequest(passengerId, tokenAuthEpoch)));
        sessions.closeCustomerSessions(passengerId, "logout");
        try {
            PassengerLogoutResult result = passengerOrderService.cancelInFlightOrdersOnPassengerLogout(passengerId);
            if (result == null) {
                result = new PassengerLogoutResult();
            }
            result.setLoggedOut(true);
            result.setOrderCleanupPending(false);
            log.info("乘客已登出 customerId={}", passengerId);
            return result;
        } catch (RuntimeException ex) {
            log.error("登出已生效但订单处理失败 customerId={}", passengerId, ex);
            return PassengerLogoutResult.loggedOutWithPendingCleanup("已经登出，订单处理需重试或查询");
        }
    }

    /** 用已由 Filter 验证的 NORMAL HTTP 上下文签发同认证代次的 audit=2 WS 小票。 */
    public CustomerLoginResponse issueWsToken(PassengerAuthContext context) {
        if (!passengerWsProperties.isEnabled()) {
            throw new BizErrorException(503, "实时通道暂未开放（WebSocket 已关闭）");
        }
        if (context == null || context.customerId() <= 0 || context.authEpoch() <= 0 || context.audit() != 1) {
            throw new BizErrorException(401, "登录已失效，请重新登录");
        }
        if (context.scope() != NORMAL) {
            throw new BizErrorException(403, "受限会话不可使用实时通道");
        }
        String wsToken = jwtService.createPassengerToken(
                context.customerId(), context.phone(), context.authEpoch(), NORMAL, 2, null);
        CustomerProfileVO profile = new CustomerProfileVO();
        profile.setId(context.customerId());
        profile.setPhone(null);

        CustomerLoginResponse out = new CustomerLoginResponse();
        out.setAccessToken(wsToken);
        out.setTokenType("Bearer");
        out.setExpiresIn(jwtService.getExpirationSeconds(NORMAL));
        out.setScope(NORMAL.name());
        out.setOperationNo(null);
        out.setCustomer(profile);
        log.info("乘客 WebSocket Token 已签发 customerId={}", context.customerId());
        return out;
    }

    private AppAuthCustomerBrief unwrap(ResponseVo<AppAuthCustomerBrief> body) {
        if (body == null || body.getCode() == null) {
            throw new BizErrorException(502, "服务暂时不可用，请稍后重试");
        }
        if (body.getCode() != 200) {
            throw new BizErrorException(body.getCode(), body.getMsg());
        }
        if (body.getData() == null || body.getData().getId() == null) {
            throw new BizErrorException(502, "服务暂时不可用，请稍后重试");
        }
        return body.getData();
    }

    private static void unwrapLogout(ResponseVo<InternalLogoutResponse> body) {
        if (body == null || body.getCode() == null) {
            throw new BizErrorException(502, "认证服务暂时不可用，请稍后重试");
        }
        if (body.getCode() != 200) {
            throw new BizErrorException(body.getCode(), body.getMsg());
        }
    }

    private CustomerLoginResponse toLoginResponse(AppAuthCustomerBrief brief) {
        if (brief.getAuthEpoch() == null || brief.getAuthEpoch() <= 0 || brief.getScope() == null) {
            throw new BizErrorException(502, "认证服务返回的登录状态不完整");
        }
        final PassengerSessionScope scope;
        try {
            scope = PassengerSessionScope.valueOf(brief.getScope());
        } catch (IllegalArgumentException ex) {
            throw new BizErrorException(502, "认证服务返回的登录状态不完整");
        }

        CustomerProfileVO profile = new CustomerProfileVO();
        profile.setId(brief.getId());
        profile.setPhone(brief.getPhone());
        profile.setNickname(brief.getNickname());

        sessions.closeCustomerSessions(brief.getId(), "auth_epoch_changed");
        String token = jwtService.createPassengerToken(
                brief.getId(), brief.getPhone(), brief.getAuthEpoch(), scope, 1, brief.getOperationNo());
        CustomerLoginResponse response = new CustomerLoginResponse();
        response.setAccessToken(token);
        response.setTokenType("Bearer");
        response.setExpiresIn(jwtService.getExpirationSeconds(scope));
        response.setScope(scope.name());
        response.setOperationNo(brief.getOperationNo());
        response.setCustomer(profile);
        log.info("乘客登录成功 customerId={}", brief.getId());
        return response;
    }
}
