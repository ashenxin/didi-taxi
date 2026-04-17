package com.sx.capacity.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sx.capacity.common.exception.CapacityBizException;
import com.sx.capacity.dao.CompanyEntityMapper;
import com.sx.capacity.dao.DriverEntityMapper;
import com.sx.capacity.model.Company;
import com.sx.capacity.model.Driver;
import com.sx.capacity.model.dto.CompanyCreateRequest;
import com.sx.capacity.model.dto.CompanyUpdateRequest;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Date;
import java.util.Objects;

/**
 * 运力公司（一行 = 公司 + 车队）创建与删除。
 */
@Service
public class CompanyService {

    private final CompanyEntityMapper companyMapper;
    private final DriverEntityMapper driverMapper;

    public CompanyService(CompanyEntityMapper companyMapper, DriverEntityMapper driverMapper) {
        this.companyMapper = companyMapper;
        this.driverMapper = driverMapper;
    }

    public Company getByIdOrNull(Long id) {
        if (id == null) {
            return null;
        }
        Company c = companyMapper.selectById(id);
        if (c == null || Objects.equals(c.getIsDeleted(), 1)) {
            return null;
        }
        return c;
    }

    @Transactional(rollbackFor = Exception.class)
    public Company create(CompanyCreateRequest req) {
        Long teamId = req.getTeamId();
        if (teamId == null) {
            teamId = allocateNextTeamId();
        } else {
            if (teamId <= 0) {
                throw new CapacityBizException("车队业务编码必须为正整数");
            }
            Long exists = companyMapper.selectCount(Wrappers.<Company>lambdaQuery().eq(Company::getTeamId, teamId));
            if (exists != null && exists > 0) {
                throw new CapacityBizException("车队业务编码已存在");
            }
        }

        Date now = new Date();
        Company row = new Company()
                .setProvinceCode(trim(req.getProvinceCode()))
                .setProvinceName(trim(req.getProvinceName()))
                .setCityCode(trim(req.getCityCode()))
                .setCityName(trim(req.getCityName()))
                .setCompanyNo(trim(req.getCompanyNo()))
                .setCompanyName(trim(req.getCompanyName()))
                .setTeam(trim(req.getTeam()))
                .setTeamId(teamId)
                .setIsDeleted(0)
                .setCreatedAt(now)
                .setUpdatedAt(now);

        try {
            companyMapper.insert(row);
        } catch (DuplicateKeyException e) {
            throw new CapacityBizException("车队业务编码已存在");
        }
        return row;
    }

    @Transactional(rollbackFor = Exception.class)
    public Company updateNames(Long id, CompanyUpdateRequest req) {
        Company c = companyMapper.selectById(id);
        if (c == null || Objects.equals(c.getIsDeleted(), 1)) {
            throw new CapacityBizException("公司不存在");
        }
        Company patch = new Company()
                .setId(id)
                .setCompanyName(trim(req.getCompanyName()))
                .setTeam(trim(req.getTeam()))
                .setUpdatedAt(new Date());
        companyMapper.updateById(patch);
        return companyMapper.selectById(id);
    }

    private Long allocateNextTeamId() {
        Long baseline = companyMapper.selectMaxTeamIdBaseline();
        long candidate = (baseline == null ? 2000L : baseline) + 1;
        for (int i = 0; i < 32; i++) {
            Long cnt = companyMapper.selectCount(Wrappers.<Company>lambdaQuery().eq(Company::getTeamId, candidate));
            if (cnt == null || cnt == 0) {
                return candidate;
            }
            candidate++;
        }
        throw new CapacityBizException("无法分配车队业务编码，请稍后重试");
    }

    @Transactional(rollbackFor = Exception.class)
    public void deleteById(Long id) {
        Company c = companyMapper.selectById(id);
        if (c == null || Objects.equals(c.getIsDeleted(), 1)) {
            throw new CapacityBizException("公司不存在");
        }
        Long driverCnt = driverMapper.selectCount(Wrappers.<Driver>lambdaQuery()
                .eq(Driver::getCompanyId, id)
                .eq(Driver::getIsDeleted, 0));
        if (driverCnt != null && driverCnt > 0) {
            throw new CapacityBizException("存在归属司机，无法删除");
        }
        Company patch = new Company().setId(id).setIsDeleted(1).setUpdatedAt(new Date());
        companyMapper.updateById(patch);
    }

    private static String trim(String s) {
        return s == null ? null : s.trim();
    }
}
