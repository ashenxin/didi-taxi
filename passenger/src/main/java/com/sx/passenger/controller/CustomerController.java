package com.sx.passenger.controller;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sx.passenger.common.util.ResultUtil;
import com.sx.passenger.common.vo.ResponseVo;
import com.sx.passenger.dao.CustomerEntityMapper;
import com.sx.passenger.model.Customer;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 乘客主数据查询（passenger-service 核心接口）。
 * 统一前缀：{@code /api/v1/customers}。
 */
@RestController
@RequestMapping("/api/v1/customers")
public class CustomerController {

    private final CustomerEntityMapper customerEntityMapper;

    public CustomerController(CustomerEntityMapper customerEntityMapper) {
        this.customerEntityMapper = customerEntityMapper;
    }

    /**
     * 乘客列表（MVP 全量，无分页）。
     * {@code GET /api/v1/customers}
     */
    @GetMapping
    public ResponseVo<List<Customer>> list() {
        return ResultUtil.success(customerEntityMapper.selectList(null));
    }

    /**
     * 按乘客 ID 查询详情。
     * {@code GET /api/v1/customers/{id}}，不存在时 HTTP 404。
     */
    @GetMapping("/{id}")
    public ResponseEntity<ResponseVo<Customer>> getById(@PathVariable Long id) {
        Customer row = customerEntityMapper.selectById(id);
        return row == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(ResultUtil.success(row));
    }

    /**
     * 按手机号查询乘客（未逻辑删除）。
     * {@code GET /api/v1/customers/by-phone?phone=}，不存在时 HTTP 404。
     */
    @GetMapping("/by-phone")
    public ResponseEntity<ResponseVo<Customer>> getByPhone(@RequestParam String phone) {
        Customer row = customerEntityMapper.selectOne(
                Wrappers.<Customer>lambdaQuery()
                        .eq(Customer::getPhone, phone)
                        .eq(Customer::getIsDeleted, 0)
                        .last("LIMIT 1"));
        return row == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(ResultUtil.success(row));
    }
}
