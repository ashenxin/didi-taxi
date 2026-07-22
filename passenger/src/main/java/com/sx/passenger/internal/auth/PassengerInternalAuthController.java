package com.sx.passenger.internal.auth;

import com.sx.passenger.auth.session.AuthoritativeAuthState;
import com.sx.passenger.auth.session.PassengerAuthEpochService;
import com.sx.passenger.common.util.ResultUtil;
import com.sx.passenger.common.vo.ResponseVo;
import com.sx.passenger.internal.auth.dto.InternalAuthStateResponse;
import com.sx.passenger.internal.auth.dto.InternalLogoutRequest;
import com.sx.passenger.internal.auth.dto.InternalLogoutResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/internal/auth-state")
public class PassengerInternalAuthController {

    private final PassengerAuthEpochService authEpochService;

    public PassengerInternalAuthController(PassengerAuthEpochService authEpochService) {
        this.authEpochService = authEpochService;
    }

    @GetMapping("/{customerId}")
    public ResponseVo<InternalAuthStateResponse> loadState(@PathVariable long customerId) {
        AuthoritativeAuthState state = authEpochService.loadState(customerId);
        return ResultUtil.success(InternalAuthStateResponse.from(state));
    }

    @PostMapping("/logout")
    public ResponseVo<InternalLogoutResponse> logout(@RequestBody InternalLogoutRequest request) {
        long authEpoch = authEpochService.logout(request.customerId(), request.expectedAuthEpoch());
        return ResultUtil.success(new InternalLogoutResponse(request.customerId(), authEpoch));
    }
}
