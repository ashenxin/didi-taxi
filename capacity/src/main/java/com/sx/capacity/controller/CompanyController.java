package com.sx.capacity.controller;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sx.capacity.common.util.ResultUtil;
import com.sx.capacity.common.vo.PageVo;
import com.sx.capacity.common.vo.ResponseVo;
import com.sx.capacity.dao.CompanyEntityMapper;
import com.sx.capacity.model.Company;
import com.sx.capacity.model.dto.CompanyCreateRequest;
import com.sx.capacity.model.dto.CompanyUpdateRequest;
import com.sx.capacity.service.CompanyService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 运力公司：分页、详情、创建、逻辑删除。
 * 统一前缀：{@code /api/v1/companies}。
 */
@RestController
@RequestMapping("/api/v1/companies")
public class CompanyController {

    private final CompanyEntityMapper companyEntityMapper;
    private final CompanyService companyService;

    public CompanyController(CompanyEntityMapper companyEntityMapper, CompanyService companyService) {
        this.companyEntityMapper = companyEntityMapper;
        this.companyService = companyService;
    }

    /**
     * 公司分页列表。
     * {@code GET /api/v1/companies?pageNo=&pageSize=&provinceCode=&cityCode=&companyNo=&companyName=}
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

    /**
     * 详情（未删除）。
     * {@code GET /api/v1/companies/{id}}
     */
    @GetMapping("/{id}")
    public ResponseVo<Company> detail(@PathVariable Long id) {
        Company c = companyService.getByIdOrNull(id);
        if (c == null) {
            return ResultUtil.notFound("公司不存在");
        }
        return ResultUtil.success(c);
    }

    /**
     * 创建「公司 + 车队」。
     * {@code POST /api/v1/companies}
     */
    @PostMapping
    public ResponseVo<Company> create(@RequestBody @Valid CompanyCreateRequest body) {
        Company created = companyService.create(body);
        return ResultUtil.success(created);
    }

    /**
     * 更新公司名称、车队名称（仅此二字段）。
     * {@code PUT /api/v1/companies/{id}}
     */
    @PutMapping("/{id}")
    public ResponseVo<Company> update(@PathVariable Long id, @RequestBody @Valid CompanyUpdateRequest body) {
        Company updated = companyService.updateNames(id, body);
        return ResultUtil.success(updated);
    }

    /**
     * 逻辑删除；存在归属司机时拒绝。
     * {@code DELETE /api/v1/companies/{id}}
     */
    @DeleteMapping("/{id}")
    public ResponseVo<Void> delete(@PathVariable Long id) {
        companyService.deleteById(id);
        return ResultUtil.success(null);
    }
}
