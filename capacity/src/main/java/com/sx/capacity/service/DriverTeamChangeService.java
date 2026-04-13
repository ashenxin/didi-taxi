package com.sx.capacity.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sx.capacity.common.exception.CapacityBizException;
import com.sx.capacity.common.vo.PageVo;
import com.sx.capacity.dao.CompanyEntityMapper;
import com.sx.capacity.dao.DriverEntityMapper;
import com.sx.capacity.dao.DriverTeamChangeRequestMapper;
import com.sx.capacity.model.Company;
import com.sx.capacity.model.Driver;
import com.sx.capacity.model.DriverTeamChangeRequest;
import com.sx.capacity.model.dto.DriverTeamChangeRequestVO;
import com.sx.capacity.model.enums.DriverTeamChangeStatus;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 司机换运力主体（换队）申请：提交、分页、详情、审核。
 */
@Service
@Slf4j
public class DriverTeamChangeService {

    private final DriverTeamChangeRequestMapper requestMapper;
    private final DriverEntityMapper driverMapper;
    private final CompanyEntityMapper companyMapper;

    public DriverTeamChangeService(DriverTeamChangeRequestMapper requestMapper,
                                   DriverEntityMapper driverMapper,
                                   CompanyEntityMapper companyMapper) {
        this.requestMapper = requestMapper;
        this.driverMapper = driverMapper;
        this.companyMapper = companyMapper;
    }

    /**
     * 司机端提交申请：写入待审记录，并将司机解绑运力且不可接单。
     */
    @Transactional(rollbackFor = Exception.class)
    public Long submit(Long driverId, Long toTeamId, String reason, String requestedBy) {
        Driver driver = driverMapper.selectById(driverId);
        if (driver == null || Objects.equals(driver.getIsDeleted(), 1)) {
            throw new CapacityBizException("司机不存在");
        }
        Company toCompany = companyMapper.selectById(toTeamId);
        if (toCompany == null || Objects.equals(toCompany.getIsDeleted(), 1)) {
            throw new CapacityBizException("目标运力主体不存在");
        }
        long pending = requestMapper.selectCount(Wrappers.<DriverTeamChangeRequest>lambdaQuery()
                .eq(DriverTeamChangeRequest::getDriverId, driverId)
                .eq(DriverTeamChangeRequest::getStatus, DriverTeamChangeStatus.PENDING.name())
                .eq(DriverTeamChangeRequest::getIsDeleted, 0));
        if (pending > 0) {
            throw new CapacityBizException("该司机已有待审核申请");
        }

        Date now = new Date();
        DriverTeamChangeRequest row = new DriverTeamChangeRequest()
                .setDriverId(driverId)
                .setFromCompanyId(driver.getCompanyId())
                .setToCompanyId(toTeamId)
                .setStatus(DriverTeamChangeStatus.PENDING.name())
                .setRequestReason(reason)
                .setRequestedBy(requestedBy)
                .setRequestedAt(now)
                .setCreatedAt(now)
                .setUpdatedAt(now)
                .setIsDeleted(0);
        requestMapper.insert(row);

        driver.setCompanyId(null);
        driver.setCanAcceptOrder(0);
        driver.setUpdatedAt(now);
        driverMapper.updateById(driver);

        log.info("team change submitted requestId={} driverId={} toTeamId={}", row.getId(), driverId, toTeamId);
        return row.getId();
    }

    /**
     * 分页查询申请列表；默认状态为待审核（PENDING）。
     * 若传 {@code provinceCode}/{@code cityCode}，先筛出匹配城市的司机 id 再过滤申请（与手机号筛选组合时为同一套司机条件）。
     */
    public PageVo<DriverTeamChangeRequestVO> page(Integer pageNo,
                                                  Integer pageSize,
                                                  String status,
                                                  Long driverId,
                                                  String driverPhone,
                                                  Date startTime,
                                                  Date endTime,
                                                  String provinceCode,
                                                  String cityCode) {
        int safePageNo = pageNo == null || pageNo < 1 ? 1 : pageNo;
        int safePageSize = pageSize == null || pageSize < 1 ? 10 : Math.min(pageSize, 200);
        long offset = (long) (safePageNo - 1) * safePageSize;

        String st = StringUtils.hasText(status) ? status.trim() : DriverTeamChangeStatus.PENDING.name();

        LambdaQueryWrapper<DriverTeamChangeRequest> qw = Wrappers.<DriverTeamChangeRequest>lambdaQuery()
                .eq(DriverTeamChangeRequest::getIsDeleted, 0)
                .eq(DriverTeamChangeRequest::getStatus, st)
                .eq(driverId != null, DriverTeamChangeRequest::getDriverId, driverId)
                .ge(startTime != null, DriverTeamChangeRequest::getRequestedAt, startTime)
                .le(endTime != null, DriverTeamChangeRequest::getRequestedAt, endTime)
                .orderByDesc(DriverTeamChangeRequest::getRequestedAt);

        boolean filterDrivers = StringUtils.hasText(driverPhone)
                || StringUtils.hasText(provinceCode)
                || StringUtils.hasText(cityCode);
        if (filterDrivers) {
            LambdaQueryWrapper<Driver> dw = Wrappers.<Driver>lambdaQuery()
                    .eq(Driver::getIsDeleted, 0);
            if (StringUtils.hasText(driverPhone)) {
                dw.like(Driver::getPhone, driverPhone.trim());
            }
            if (StringUtils.hasText(cityCode)) {
                dw.eq(Driver::getCityCode, cityCode.trim());
            } else if (StringUtils.hasText(provinceCode)) {
                String p = provinceCode.trim();
                if (p.length() >= 2) {
                    dw.likeRight(Driver::getCityCode, p.substring(0, 2));
                }
            }
            List<Driver> drivers = driverMapper.selectList(dw);
            if (drivers.isEmpty()) {
                PageVo<DriverTeamChangeRequestVO> empty = new PageVo<>();
                empty.setList(Collections.emptyList());
                empty.setTotal(0L);
                empty.setPageNo(safePageNo);
                empty.setPageSize(safePageSize);
                return empty;
            }
            List<Long> ids = drivers.stream().map(Driver::getId).collect(Collectors.toList());
            qw.in(DriverTeamChangeRequest::getDriverId, ids);
        }

        Long total = requestMapper.selectCount(qw);
        List<DriverTeamChangeRequest> rows = requestMapper.selectList(qw.last("LIMIT " + offset + "," + safePageSize));

        PageVo<DriverTeamChangeRequestVO> resp = new PageVo<>();
        resp.setList(rows.stream().map(this::toVo).collect(Collectors.toList()));
        resp.setTotal(total == null ? 0L : total);
        resp.setPageNo(safePageNo);
        resp.setPageSize(safePageSize);
        return resp;
    }

    public DriverTeamChangeRequestVO detail(Long id) {
        DriverTeamChangeRequest row = requestMapper.selectById(id);
        if (row == null || Objects.equals(row.getIsDeleted(), 1)) {
            throw new CapacityBizException("申请不存在");
        }
        return toVo(row);
    }

    /**
     * 审核通过：CAS 更新为 APPROVED，司机归属目标运力并可接单。
     */
    @Transactional(rollbackFor = Exception.class)
    public void approve(Long id, String reviewReason, String reviewedBy) {
        Date now = new Date();
        LambdaUpdateWrapper<DriverTeamChangeRequest> uw = Wrappers.<DriverTeamChangeRequest>lambdaUpdate()
                .set(DriverTeamChangeRequest::getStatus, DriverTeamChangeStatus.APPROVED.name())
                .set(DriverTeamChangeRequest::getReviewedAt, now)
                .set(DriverTeamChangeRequest::getReviewedBy, reviewedBy)
                .set(DriverTeamChangeRequest::getReviewReason, reviewReason)
                .set(DriverTeamChangeRequest::getUpdatedAt, now)
                .eq(DriverTeamChangeRequest::getId, id)
                .eq(DriverTeamChangeRequest::getStatus, DriverTeamChangeStatus.PENDING.name())
                .eq(DriverTeamChangeRequest::getIsDeleted, 0);
        int n = requestMapper.update(null, uw);
        if (n == 0) {
            throw new CapacityBizException("申请不存在或已审核");
        }
        DriverTeamChangeRequest fresh = requestMapper.selectById(id);
        Driver driver = driverMapper.selectById(fresh.getDriverId());
        if (driver == null || Objects.equals(driver.getIsDeleted(), 1)) {
            throw new CapacityBizException("司机不存在");
        }
        driver.setCompanyId(fresh.getToCompanyId());
        driver.setCanAcceptOrder(1);
        driver.setUpdatedAt(now);
        driverMapper.updateById(driver);
        log.info("team change approved requestId={} driverId={}", id, fresh.getDriverId());
    }

    /**
     * 审核拒绝：CAS 更新为 REJECTED；司机保持解绑且不可接单。
     */
    @Transactional(rollbackFor = Exception.class)
    public void reject(Long id, String reviewReason, String reviewedBy) {
        Date now = new Date();
        LambdaUpdateWrapper<DriverTeamChangeRequest> uw = Wrappers.<DriverTeamChangeRequest>lambdaUpdate()
                .set(DriverTeamChangeRequest::getStatus, DriverTeamChangeStatus.REJECTED.name())
                .set(DriverTeamChangeRequest::getReviewedAt, now)
                .set(DriverTeamChangeRequest::getReviewedBy, reviewedBy)
                .set(DriverTeamChangeRequest::getReviewReason, reviewReason)
                .set(DriverTeamChangeRequest::getUpdatedAt, now)
                .eq(DriverTeamChangeRequest::getId, id)
                .eq(DriverTeamChangeRequest::getStatus, DriverTeamChangeStatus.PENDING.name())
                .eq(DriverTeamChangeRequest::getIsDeleted, 0);
        int n = requestMapper.update(null, uw);
        if (n == 0) {
            throw new CapacityBizException("申请不存在或已审核");
        }
        log.info("team change rejected requestId={}", id);
    }

    private DriverTeamChangeRequestVO toVo(DriverTeamChangeRequest r) {
        Driver driver = driverMapper.selectById(r.getDriverId());
        Company fromC = r.getFromCompanyId() != null ? companyMapper.selectById(r.getFromCompanyId()) : null;
        Company toC = r.getToCompanyId() != null ? companyMapper.selectById(r.getToCompanyId()) : null;

        DriverTeamChangeRequestVO vo = new DriverTeamChangeRequestVO()
                .setId(r.getId())
                .setStatus(r.getStatus())
                .setRequestedAt(r.getRequestedAt())
                .setRequestedBy(r.getRequestedBy())
                .setReviewedAt(r.getReviewedAt())
                .setReviewedBy(r.getReviewedBy())
                .setReviewReason(r.getReviewReason())
                .setRequestReason(r.getRequestReason())
                .setDriverId(r.getDriverId())
                .setDriverName(driver != null ? driver.getName() : null)
                .setDriverPhone(driver != null ? driver.getPhone() : null)
                .setDriverCityCode(driver != null ? driver.getCityCode() : null)
                .setFromTeamId(r.getFromCompanyId())
                .setFromTeamName(teamDisplayName(fromC))
                .setToTeamId(r.getToCompanyId())
                .setToTeamName(teamDisplayName(toC));
        if (toC != null) {
            vo.setCompanyId(toC.getId());
            vo.setCompanyName(toC.getCompanyName());
        }
        return vo;
    }

    private static String teamDisplayName(Company c) {
        if (c == null) {
            return null;
        }
        if (StringUtils.hasText(c.getTeam())) {
            return c.getTeam();
        }
        return c.getCompanyName();
    }
}
