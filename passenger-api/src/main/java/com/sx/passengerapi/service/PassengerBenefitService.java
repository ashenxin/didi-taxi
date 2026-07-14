package com.sx.passengerapi.service;

import com.sx.passengerapi.client.CalculateClient;
import com.sx.passengerapi.common.exception.BizErrorException;
import com.sx.passengerapi.common.vo.ResponseVo;
import com.sx.passengerapi.model.benefit.BenefitOverviewVO;
import com.sx.passengerapi.model.benefit.BenefitPointsVO;
import com.sx.passengerapi.model.benefit.BenefitSignInResult;
import org.springframework.stereotype.Service;

@Service
public class PassengerBenefitService {
    private final CalculateClient calculateClient;

    public PassengerBenefitService(CalculateClient calculateClient) {
        this.calculateClient = calculateClient;
    }

    public BenefitOverviewVO overview(long customerId) {
        return unwrap(calculateClient.benefitOverview(customerId));
    }

    public BenefitPointsVO points(long customerId) {
        return unwrap(calculateClient.benefitPoints(customerId));
    }

    public BenefitSignInResult signIn(long customerId, String requestId) {
        return unwrap(calculateClient.benefitSignIn(customerId, requestId));
    }

    private static <T> T unwrap(ResponseVo<T> body) {
        if (body == null || body.getCode() == null) {
            throw new BizErrorException(502, "福利服务暂时不可用，请稍后重试");
        }
        if (body.getCode() != 200) {
            throw new BizErrorException(body.getCode(), body.getMsg());
        }
        return body.getData();
    }
}
