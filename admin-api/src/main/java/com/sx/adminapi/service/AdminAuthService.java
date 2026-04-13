package com.sx.adminapi.service;

import com.sx.adminapi.auth.JwtService;
import com.sx.adminapi.client.PassengerAdminSysClient;
import com.sx.adminapi.client.dto.PassengerMenuNodeData;
import com.sx.adminapi.client.dto.PassengerSecurityContextData;
import com.sx.adminapi.client.dto.PassengerVerifyCredentialsData;
import com.sx.adminapi.client.dto.PassengerVerifyCredentialsRequest;
import com.sx.adminapi.common.enums.ExceptionCode;
import com.sx.adminapi.common.exception.BizErrorException;
import com.sx.adminapi.common.vo.ResponseVo;
import com.sx.adminapi.model.auth.AdminLoginResponse;
import com.sx.adminapi.model.auth.AdminMenuNodeVO;
import com.sx.adminapi.model.auth.AdminUserVO;
import feign.FeignException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
@Slf4j
public class AdminAuthService {

    private final PassengerAdminSysClient passengerAdminSysClient;
    private final JwtService jwtService;

    public AdminAuthService(PassengerAdminSysClient passengerAdminSysClient, JwtService jwtService) {
        this.passengerAdminSysClient = passengerAdminSysClient;
        this.jwtService = jwtService;
    }

    public AdminLoginResponse login(String username, String password) {
        final ResponseVo<PassengerVerifyCredentialsData> verified;
        try {
            verified = passengerAdminSysClient.verifyCredentials(new PassengerVerifyCredentialsRequest(username, password));
        } catch (FeignException.Unauthorized e) {
            throw new BizErrorException(ExceptionCode.UNAUTHORIZED.getValue(), "用户名或密码错误");
        } catch (FeignException e) {
            log.error("admin login verifyCredentials feign status={}", e.status(), e);
            throw new BizErrorException(ExceptionCode.BAD_GATEWAY.getValue(), "服务暂时不可用，请稍后重试");
        }

        if (verified.getCode() == null || verified.getCode() != 200 || verified.getData() == null) {
            throw new BizErrorException(ExceptionCode.UNAUTHORIZED.getValue(), "用户名或密码错误");
        }
        PassengerVerifyCredentialsData cred = verified.getData();
        if (cred.getStatus() == null || cred.getStatus() != 1) {
            throw new BizErrorException(ExceptionCode.UNAUTHORIZED.getValue(), "用户名或密码错误");
        }

        final ResponseVo<PassengerSecurityContextData> ctxVo;
        try {
            ctxVo = passengerAdminSysClient.securityContext(cred.getUserId());
        } catch (FeignException.NotFound e) {
            throw new BizErrorException(ExceptionCode.UNAUTHORIZED.getValue(), "用户名或密码错误");
        } catch (FeignException.Forbidden e) {
            throw new BizErrorException(ExceptionCode.FORBIDDEN.getValue(), "账号数据不一致，请联系管理员");
        } catch (FeignException e) {
            log.error("admin login securityContext feign status={}", e.status(), e);
            throw new BizErrorException(ExceptionCode.BAD_GATEWAY.getValue(), "服务暂时不可用，请稍后重试");
        }

        if (ctxVo.getCode() == null || ctxVo.getCode() != 200 || ctxVo.getData() == null) {
            throw new BizErrorException(ExceptionCode.UNAUTHORIZED.getValue(), "用户名或密码错误");
        }
        PassengerSecurityContextData ctx = ctxVo.getData();
        if (!java.util.Objects.equals(ctx.getTokenVersion(), cred.getTokenVersion())) {
            throw new BizErrorException(ExceptionCode.UNAUTHORIZED.getValue(), "用户名或密码错误");
        }

        String accessToken = jwtService.createToken(cred.getUserId(), cred.getTokenVersion(), ctx.getUsername());
        AdminUserVO user = toUserVo(ctx);
        log.info("admin bff login success userId={} username={}", cred.getUserId(), ctx.getUsername());
        return new AdminLoginResponse(accessToken, "Bearer", jwtService.getExpirationSeconds(), user);
    }

    public List<AdminMenuNodeVO> menus(long userId) {
        final ResponseVo<List<PassengerMenuNodeData>> vo;
        try {
            vo = passengerAdminSysClient.userMenus(userId);
        } catch (FeignException.NotFound e) {
            throw new BizErrorException(ExceptionCode.NOT_FOUND.getValue(), "资源不存在");
        } catch (FeignException.Forbidden e) {
            throw new BizErrorException(ExceptionCode.FORBIDDEN.getValue(), "账号数据不一致，请联系管理员");
        } catch (FeignException e) {
            log.error("admin menus feign userId={} status={}", userId, e.status(), e);
            throw new BizErrorException(ExceptionCode.BAD_GATEWAY.getValue(), "服务暂时不可用，请稍后重试");
        }
        if (vo.getCode() == null || vo.getCode() != 200 || vo.getData() == null) {
            throw new BizErrorException(ExceptionCode.BAD_GATEWAY.getValue(), "服务暂时不可用，请稍后重试");
        }
        return mapMenuNodes(vo.getData());
    }

    private static List<AdminMenuNodeVO> mapMenuNodes(List<PassengerMenuNodeData> nodes) {
        if (nodes == null || nodes.isEmpty()) {
            return List.of();
        }
        List<AdminMenuNodeVO> out = new ArrayList<>(nodes.size());
        for (PassengerMenuNodeData n : nodes) {
            out.add(mapMenuNode(n));
        }
        return out;
    }

    private static AdminMenuNodeVO mapMenuNode(PassengerMenuNodeData n) {
        AdminMenuNodeVO v = new AdminMenuNodeVO();
        v.setId(n.getId());
        v.setParentId(n.getParentId());
        v.setPath(n.getPath());
        v.setName(n.getName());
        v.setIcon(n.getIcon());
        v.setComponent(n.getComponent());
        v.setPerms(n.getPerms());
        v.setSort(n.getSort());
        v.setVisible(n.isVisible());
        v.setChildren(mapMenuNodes(n.getChildren() == null ? List.of() : n.getChildren()));
        return v;
    }

    private static AdminUserVO toUserVo(PassengerSecurityContextData ctx) {
        List<String> roles = ctx.getRoleCodes() == null ? List.of() : List.copyOf(ctx.getRoleCodes());
        return new AdminUserVO(
                ctx.getUserId(),
                ctx.getUsername(),
                ctx.getDisplayName(),
                roles,
                ctx.getProvinceCode(),
                ctx.getCityCode(),
                ctx.getTokenVersion());
    }
}
