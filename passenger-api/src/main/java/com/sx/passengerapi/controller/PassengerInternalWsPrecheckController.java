package com.sx.passengerapi.controller;

import com.sx.passengerapi.auth.InvalidPassengerSessionException;
import com.sx.passengerapi.common.exception.BizErrorException;
import com.sx.passengerapi.common.util.ResultUtil;
import com.sx.passengerapi.common.vo.ResponseVo;
import com.sx.passengerapi.config.PassengerInternalClientProperties;
import com.sx.passengerapi.model.auth.WsTicketPrecheckRequest;
import com.sx.passengerapi.ws.PassengerWsAuthStateUnavailableException;
import com.sx.passengerapi.ws.PassengerWsRestrictedException;
import com.sx.passengerapi.ws.PassengerWsTicketValidator;
import feign.FeignException;
import io.jsonwebtoken.JwtException;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** 仅供网关在 WebSocket Upgrade 前调用；最终握手仍会再次校验。 */
@RestController
@RequestMapping("/app/internal/v1/ws")
public class PassengerInternalWsPrecheckController {
    private static final String INTERNAL_TOKEN = "X-Internal-Service-Token";

    private final PassengerWsTicketValidator validator;
    private final PassengerInternalClientProperties internalAuth;

    public PassengerInternalWsPrecheckController(PassengerWsTicketValidator validator,
                                                 PassengerInternalClientProperties internalAuth) {
        this.validator = validator;
        this.internalAuth = internalAuth;
    }

    @PostMapping("/precheck")
    public ResponseVo<Void> precheck(
            @RequestHeader(value = INTERNAL_TOKEN, required = false) String suppliedToken,
            @Valid @RequestBody WsTicketPrecheckRequest request) {
        if (!constantTimeEquals(internalAuth.getToken(), suppliedToken)) {
            throw new BizErrorException(401, "内部服务身份无效");
        }
        try {
            validator.validate(validator.parse(request.ticket()));
            return ResultUtil.success(null);
        } catch (PassengerWsRestrictedException e) {
            throw new BizErrorException(403, "受限会话不可使用实时通道");
        } catch (InvalidPassengerSessionException | JwtException | IllegalArgumentException e) {
            throw new BizErrorException(401, "登录已失效，请重新登录");
        } catch (PassengerWsAuthStateUnavailableException | FeignException e) {
            throw new BizErrorException(503, "认证服务暂时不可用");
        }
    }

    private static boolean constantTimeEquals(String expected, String supplied) {
        if (expected == null || expected.isBlank() || supplied == null) return false;
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                supplied.getBytes(StandardCharsets.UTF_8));
    }
}
