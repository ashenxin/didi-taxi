package com.sx.adminapi.service;

import com.sx.adminapi.client.PassengerAdminSysClient;
import com.sx.adminapi.client.dto.PassengerStaffCreateRequest;
import com.sx.adminapi.client.dto.PassengerStaffPageResponse;
import com.sx.adminapi.client.dto.PassengerStaffUpdateRequest;
import com.sx.adminapi.client.dto.PassengerStaffUserVO;
import com.sx.adminapi.common.enums.ExceptionCode;
import com.sx.adminapi.common.exception.BizErrorException;
import com.sx.adminapi.common.vo.ResponseVo;
import com.sx.adminapi.model.capacity.AdminPageVO;
import com.sx.adminapi.model.system.AdminStaffCreateBody;
import com.sx.adminapi.model.system.AdminStaffUpdateBody;
import com.sx.adminapi.model.system.AdminSystemStaffUserVO;
import com.sx.adminapi.security.AdminLoginUser;
import feign.FeignException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
public class AdminSystemStaffService {

    private final PassengerAdminSysClient passengerAdminSysClient;

    public AdminSystemStaffService(PassengerAdminSysClient passengerAdminSysClient) {
        this.passengerAdminSysClient = passengerAdminSysClient;
    }

    public AdminPageVO<AdminSystemStaffUserVO> page(
            int pageNo, int pageSize, String provinceCode, String cityCode, String username, String roleCode) {
        AdminLoginUser caller = requireStaffManager();
        ResponseVo<PassengerStaffPageResponse> vo = callPassengerResponse(
                () -> passengerAdminSysClient.staffPage(caller.userId(), pageNo, pageSize, provinceCode, cityCode, username, roleCode));
        PassengerStaffPageResponse data = requireOk(vo, new PassengerStaffPageResponse());
        AdminPageVO<AdminSystemStaffUserVO> out = new AdminPageVO<>();
        out.setPageNo(pageNo);
        out.setPageSize(pageSize);
        out.setTotal(data.getTotal());
        List<AdminSystemStaffUserVO> list = new ArrayList<>();
        if (data.getList() != null) {
            for (PassengerStaffUserVO p : data.getList()) {
                list.add(map(p));
            }
        }
        out.setList(list);
        return out;
    }

    public AdminSystemStaffUserVO get(long id) {
        AdminLoginUser caller = requireStaffManager();
        ResponseVo<PassengerStaffUserVO> vo = callPassengerResponse(
                () -> passengerAdminSysClient.staffGet(caller.userId(), id));
        return map(requireOk(vo, null));
    }

    public AdminSystemStaffUserVO create(AdminStaffCreateBody body) {
        AdminLoginUser caller = requireStaffManager();
        PassengerStaffCreateRequest req = new PassengerStaffCreateRequest(
                body.getUsername(),
                body.getPassword(),
                body.getDisplayName(),
                body.getRoleCode(),
                body.getProvinceCode(),
                body.getCityCode());
        ResponseVo<PassengerStaffUserVO> vo = callPassengerResponse(
                () -> passengerAdminSysClient.staffCreate(caller.userId(), req));
        return map(requireOk(vo, null));
    }

    public AdminSystemStaffUserVO update(long id, AdminStaffUpdateBody body) {
        AdminLoginUser caller = requireStaffManager();
        PassengerStaffUpdateRequest req = new PassengerStaffUpdateRequest();
        req.setDisplayName(body.getDisplayName());
        req.setStatus(body.getStatus());
        req.setPassword(body.getPassword());
        req.setProvinceCode(body.getProvinceCode());
        req.setCityCode(body.getCityCode());
        ResponseVo<PassengerStaffUserVO> vo = callPassengerResponse(
                () -> passengerAdminSysClient.staffUpdate(caller.userId(), id, req));
        return map(requireOk(vo, null));
    }

    public void delete(long id) {
        AdminLoginUser caller = requireStaffManager();
        ResponseVo<Void> vo = callPassengerResponse(() -> passengerAdminSysClient.staffDelete(caller.userId(), id));
        requireOk(vo, null);
    }

    private static AdminLoginUser requireStaffManager() {
        AdminLoginUser u = (AdminLoginUser) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        boolean superAdmin = u.roleCodes() != null && u.roleCodes().contains("SUPER");
        boolean prov = u.roleCodes() != null && u.roleCodes().contains("PROVINCE_ADMIN");
        if (!superAdmin && !prov) {
            throw new BizErrorException(ExceptionCode.FORBIDDEN.getValue(), "无权限管理人员");
        }
        return u;
    }

    @FunctionalInterface
    private interface PassengerResponseCall<T> {
        ResponseVo<T> get();
    }

    private static <T> ResponseVo<T> callPassengerResponse(PassengerResponseCall<T> call) {
        try {
            return call.get();
        } catch (FeignException.Forbidden e) {
            throw new BizErrorException(ExceptionCode.FORBIDDEN.getValue(), "无权限执行该操作");
        } catch (FeignException.NotFound e) {
            throw new BizErrorException(ExceptionCode.NOT_FOUND.getValue(), "资源不存在");
        } catch (FeignException e) {
            if (e.status() >= 400 && e.status() < 500) {
                throw new BizErrorException(e.status(), "请求被拒绝");
            }
            throw new BizErrorException(ExceptionCode.BAD_GATEWAY.getValue(), "服务暂时不可用，请稍后重试");
        }
    }

    private static <T> T requireOk(ResponseVo<T> vo, T emptyFallback) {
        if (vo == null || vo.getCode() == null || vo.getCode() != 200) {
            String msg = vo != null && vo.getMsg() != null ? vo.getMsg() : "下游返回异常";
            throw new BizErrorException(ExceptionCode.BAD_GATEWAY.getValue(), msg);
        }
        return vo.getData() != null ? vo.getData() : emptyFallback;
    }

    private static AdminSystemStaffUserVO map(PassengerStaffUserVO p) {
        if (p == null) {
            return null;
        }
        AdminSystemStaffUserVO v = new AdminSystemStaffUserVO();
        v.setId(p.getId());
        v.setUsername(p.getUsername());
        v.setDisplayName(p.getDisplayName());
        v.setProvinceCode(p.getProvinceCode());
        v.setCityCode(p.getCityCode());
        v.setStatus(p.getStatus());
        v.setRoleCode(p.getRoleCode());
        return v;
    }
}
