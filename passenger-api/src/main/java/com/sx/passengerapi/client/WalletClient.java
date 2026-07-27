package com.sx.passengerapi.client;

import com.sx.passengerapi.common.vo.ResponseVo;
import com.sx.passengerapi.model.wallet.AutoPayAgreementVO;
import com.sx.passengerapi.model.wallet.AutoPaySignRequest;
import com.sx.passengerapi.model.wallet.AutoPaySignResult;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;

@FeignClient(name = "wallet-service")
public interface WalletClient {
    @GetMapping("/api/v1/wallet/auto-pay/agreements")
    ResponseVo<List<AutoPayAgreementVO>> listAgreements(@RequestParam("passengerId") Long passengerId);

    @GetMapping("/api/v1/wallet/auto-pay/default")
    ResponseVo<AutoPayAgreementVO> defaultAgreement(@RequestParam("passengerId") Long passengerId);

    @PostMapping("/api/v1/wallet/auto-pay/agreements/sign")
    ResponseVo<AutoPaySignResult> sign(@RequestParam("passengerId") Long passengerId,
                                       @RequestBody AutoPaySignRequest request);

    @PostMapping("/api/v1/wallet/auto-pay/agreements/{agreementId}/default")
    ResponseVo<AutoPayAgreementVO> setDefault(@RequestParam("passengerId") Long passengerId,
                                              @PathVariable("agreementId") Long agreementId);

    @PostMapping("/api/v1/wallet/auto-pay/agreements/{agreementId}/close")
    ResponseVo<AutoPayAgreementVO> close(@RequestParam("passengerId") Long passengerId,
                                         @PathVariable("agreementId") Long agreementId);
}
