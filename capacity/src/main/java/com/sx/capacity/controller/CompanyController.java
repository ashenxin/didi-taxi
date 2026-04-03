package com.sx.capacity.controller;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sx.capacity.common.util.ResultUtil;
import com.sx.capacity.common.vo.PageVo;
import com.sx.capacity.common.vo.ResponseVo;
import com.sx.capacity.dao.CompanyEntityMapper;
import com.sx.capacity.model.Company;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 运力公司分页查询。
 * <p>统一前缀：{@code /api/v1/companies}。</p>
 */
@RestController
@RequestMapping("/api/v1/companies")
public class CompanyController {

    private final CompanyEntityMapper companyEntityMapper;

    public CompanyController(CompanyEntityMapper companyEntityMapper) {
        this.companyEntityMapper = companyEntityMapper;
    }

    /**
     * 公司分页列表。
     * <p>{@code GET /api/v1/companies?pageNo=&pageSize=&provinceCode=&cityCode=&companyNo=&companyName=}</p>
     */
    @GetMapping
    public ResponseVo<PageVo<Company>> page(@RequestParam(defaultValue = "1") Integer pageNo,
                                           @RequestParam(defaultValue = "10") Integer pageSize,
                                           @RequestParam(required = false) String provinceCode,
                                           @RequestParam(required = false) String cityCode,
                                           @RequestParam(required = false) String companyNo,
                                           @RequestParam(required = false) String companyName) {
        int safePageNo = pageNo == null || pageNo < 1 ? 1 : pageNo;
        int safePageSize = pageSize == null || pageSize < 1 ? 10 : Math.min(pageSize, 200);
        long offset = (long) (safePageNo - 1) * safePageSize;

        var qw = Wrappers.<Company>lambdaQuery()
                .eq(Company::getIsDeleted, 0)
                .eq(provinceCode != null && !provinceCode.isBlank(), Company::getProvinceCode, provinceCode)
                .eq(cityCode != null && !cityCode.isBlank(), Company::getCityCode, cityCode)
                .like(companyNo != null && !companyNo.isBlank(), Company::getCompanyNo, companyNo)
                .like(companyName != null && !companyName.isBlank(), Company::getCompanyName, companyName)
                .orderByDesc(Company::getId);
        Long total = companyEntityMapper.selectCount(qw);
        var rows = companyEntityMapper.selectList(qw.last("LIMIT " + offset + "," + safePageSize));

        PageVo<Company> resp = new PageVo<>();
        resp.setList(rows);
        resp.setTotal(total == null ? 0L : total);
        resp.setPageNo(safePageNo);
        resp.setPageSize(safePageSize);
        return ResultUtil.success(resp);
    }
}

