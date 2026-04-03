package com.sx.adminapi.client;

import com.sx.adminapi.client.dto.PassengerSecurityContextData;
import com.sx.adminapi.client.dto.PassengerVerifyCredentialsData;
import com.sx.adminapi.client.dto.PassengerVerifyCredentialsRequest;
import com.sx.adminapi.client.dto.PassengerMenuNodeData;
import com.sx.adminapi.client.dto.PassengerStaffCreateRequest;
import com.sx.adminapi.client.dto.PassengerStaffPageResponse;
import com.sx.adminapi.client.dto.PassengerStaffUpdateRequest;
import com.sx.adminapi.client.dto.PassengerStaffUserVO;
import com.sx.adminapi.common.vo.ResponseVo;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;

@FeignClient(name = "passengerAdminSys", url = "${services.passenger.base-url:http://127.0.0.1:8092}")
public interface PassengerAdminSysClient {

    @PostMapping("/api/v1/admin/sys/auth/verify-credentials")
    ResponseVo<PassengerVerifyCredentialsData> verifyCredentials(@RequestBody PassengerVerifyCredentialsRequest body);

    @GetMapping("/api/v1/admin/sys/users/{userId}/security-context")
    ResponseVo<PassengerSecurityContextData> securityContext(@PathVariable("userId") long userId);

    @GetMapping("/api/v1/admin/sys/users/{userId}/menus")
    ResponseVo<List<PassengerMenuNodeData>> userMenus(@PathVariable("userId") long userId);

    @GetMapping("/api/v1/admin/sys/admin-users")
    ResponseVo<PassengerStaffPageResponse> staffPage(
            @RequestHeader("X-Caller-User-Id") long callerUserId,
            @RequestParam("pageNo") int pageNo,
            @RequestParam("pageSize") int pageSize,
            @RequestParam(value = "provinceCode", required = false) String provinceCode,
            @RequestParam(value = "cityCode", required = false) String cityCode,
            @RequestParam(value = "username", required = false) String username,
            @RequestParam(value = "roleCode", required = false) String roleCode);

    @GetMapping("/api/v1/admin/sys/admin-users/{id}")
    ResponseVo<PassengerStaffUserVO> staffGet(
            @RequestHeader("X-Caller-User-Id") long callerUserId,
            @PathVariable("id") long id);

    @PostMapping("/api/v1/admin/sys/admin-users")
    ResponseVo<PassengerStaffUserVO> staffCreate(
            @RequestHeader("X-Caller-User-Id") long callerUserId,
            @RequestBody PassengerStaffCreateRequest body);

    @PutMapping("/api/v1/admin/sys/admin-users/{id}")
    ResponseVo<PassengerStaffUserVO> staffUpdate(
            @RequestHeader("X-Caller-User-Id") long callerUserId,
            @PathVariable("id") long id,
            @RequestBody PassengerStaffUpdateRequest body);

    @DeleteMapping("/api/v1/admin/sys/admin-users/{id}")
    ResponseVo<Void> staffDelete(
            @RequestHeader("X-Caller-User-Id") long callerUserId,
            @PathVariable("id") long id);
}
