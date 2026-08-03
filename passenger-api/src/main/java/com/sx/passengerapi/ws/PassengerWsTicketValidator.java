package com.sx.passengerapi.ws;

import com.sx.passengerapi.auth.AppJwtService;
import com.sx.passengerapi.auth.InvalidPassengerSessionException;
import com.sx.passengerapi.auth.ParsedPassengerJwt;
import com.sx.passengerapi.auth.PassengerAuthDecisionService;
import com.sx.passengerapi.auth.PassengerAuthMetrics;
import com.sx.passengerapi.auth.PassengerSessionRejectionClassifier;
import com.sx.passengerapi.client.PassengerCoreAuthStateClient;
import com.sx.passengerapi.client.dto.InternalAuthStateResponse;
import com.sx.passengerapi.common.vo.ResponseVo;
import feign.FeignException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Objects;

import static com.sx.passengerapi.auth.PassengerSessionScope.LIFECYCLE_RESTRICTED;

/** HTTP 预检与最终 WS 握手共用的小票解析、DB 权威状态回查和会话裁决。 */
@Service
public class PassengerWsTicketValidator {
    private final AppJwtService jwtService;
    private final PassengerCoreAuthStateClient authStateClient;
    private final PassengerAuthDecisionService decisionService;
    private final PassengerAuthMetrics metrics;

    public PassengerWsTicketValidator(AppJwtService jwtService,
                                      PassengerCoreAuthStateClient authStateClient,
                                      PassengerAuthDecisionService decisionService,
                                      PassengerAuthMetrics metrics) {
        this.jwtService = jwtService;
        this.authStateClient = authStateClient;
        this.decisionService = decisionService;
        this.metrics = metrics;
    }

    public ParsedPassengerJwt parse(String ticket) {
        return jwtService.parseAndVerify(ticket);
    }

    /** 返回已验证的小票；认证状态不可用、会话失效和受限会话分别抛出不同异常。 */
    public ParsedPassengerJwt validate(ParsedPassengerJwt parsed) {
        InternalAuthStateResponse authoritativeState = null;
        long queryStartedAt = System.nanoTime();
        try {
            ResponseVo<InternalAuthStateResponse> result = authStateClient.get(parsed.customerId());
            if (result == null || !Objects.equals(result.getCode(), HttpStatus.OK.value())
                    || result.getData() == null) {
                metrics.authStateQuery(Duration.ofNanos(System.nanoTime() - queryStartedAt),
                        PassengerAuthMetrics.AuthStateResult.INVALID_RESPONSE);
                throw new PassengerWsAuthStateUnavailableException();
            }
            metrics.authStateQuery(Duration.ofNanos(System.nanoTime() - queryStartedAt),
                    PassengerAuthMetrics.AuthStateResult.SUCCESS);
            authoritativeState = result.getData();
            if (parsed.scope() == LIFECYCLE_RESTRICTED) {
                decisionService.verify(parsed, authoritativeState, 1);
                metrics.jwtRejected(PassengerAuthMetrics.JwtRejectReason.RESTRICTED);
                throw new PassengerWsRestrictedException();
            }
            decisionService.verify(parsed, authoritativeState, 2);
            return parsed;
        } catch (InvalidPassengerSessionException e) {
            metrics.jwtRejected(PassengerSessionRejectionClassifier.classify(parsed, authoritativeState));
            throw e;
        } catch (FeignException e) {
            metrics.authStateQuery(Duration.ofNanos(System.nanoTime() - queryStartedAt),
                    PassengerAuthMetrics.AuthStateResult.UNAVAILABLE);
            throw e;
        }
    }
}
