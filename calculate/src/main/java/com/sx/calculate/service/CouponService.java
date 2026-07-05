package com.sx.calculate.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.sx.calculate.dao.CouponUseRecordMapper;
import com.sx.calculate.dao.UserCouponMapper;
import com.sx.calculate.model.CouponUseRecord;
import com.sx.calculate.model.UserCoupon;
import com.sx.calculate.model.dto.CouponLockRequest;
import com.sx.calculate.model.dto.CouponLockResult;
import com.sx.calculate.model.dto.CouponPageVO;
import com.sx.calculate.model.dto.CouponUseRequest;
import com.sx.calculate.model.dto.CouponVO;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Service
public class CouponService {
    private static final String UNUSED = "UNUSED";
    private static final String LOCKED = "LOCKED";
    private static final String USED = "USED";

    private final UserCouponMapper userCouponMapper;
    private final CouponUseRecordMapper recordMapper;

    public CouponService(UserCouponMapper userCouponMapper, CouponUseRecordMapper recordMapper) {
        this.userCouponMapper = userCouponMapper;
        this.recordMapper = recordMapper;
    }

    public CouponPageVO page(Long passengerId, String status, int pageNo, int pageSize) {
        int safePageNo = Math.max(pageNo, 1);
        int safePageSize = Math.min(Math.max(pageSize, 1), 50);
        LambdaQueryWrapper<UserCoupon> wrapper = Wrappers.<UserCoupon>lambdaQuery()
                .eq(UserCoupon::getPassengerId, passengerId)
                .orderByAsc(UserCoupon::getValidEndAt)
                .orderByDesc(UserCoupon::getId);
        if (status != null && !status.isBlank()) {
            wrapper.eq(UserCoupon::getStatus, status);
        }
        Page<UserCoupon> page = userCouponMapper.selectPage(Page.of(safePageNo, safePageSize), wrapper);
        CouponPageVO vo = new CouponPageVO();
        vo.setPageNo(safePageNo);
        vo.setPageSize(safePageSize);
        vo.setTotal(page.getTotal());
        vo.setList(page.getRecords().stream().map(this::toVO).toList());
        return vo;
    }

    public List<CouponVO> listAvailable(Long passengerId, BigDecimal finalAmount, String cityCode, String productCode) {
        LocalDateTime now = LocalDateTime.now();
        return userCouponMapper.selectList(Wrappers.<UserCoupon>lambdaQuery()
                        .eq(UserCoupon::getPassengerId, passengerId)
                        .eq(UserCoupon::getStatus, UNUSED)
                        .le(UserCoupon::getValidStartAt, now)
                        .gt(UserCoupon::getValidEndAt, now)
                        .le(UserCoupon::getThresholdAmount, finalAmount)
                        .and(w -> w.isNull(UserCoupon::getCityCode).or().eq(UserCoupon::getCityCode, cityCode))
                        .and(w -> w.isNull(UserCoupon::getProductCode).or().eq(UserCoupon::getProductCode, productCode))
                        .orderByDesc(UserCoupon::getDiscountAmount)
                        .orderByAsc(UserCoupon::getValidEndAt))
                .stream()
                .map(this::toVO)
                .toList();
    }

    @Transactional
    public CouponLockResult lock(CouponLockRequest request) {
        UserCoupon coupon = request.getCouponId() == null
                ? bestCoupon(request)
                : userCouponMapper.selectById(request.getCouponId());
        if (coupon == null || !canUse(coupon, request)) {
            throw new IllegalArgumentException("无可用优惠券");
        }
        int updated = userCouponMapper.update(null, Wrappers.<UserCoupon>lambdaUpdate()
                .eq(UserCoupon::getId, coupon.getId())
                .eq(UserCoupon::getPassengerId, request.getPassengerId())
                .eq(UserCoupon::getStatus, UNUSED)
                .set(UserCoupon::getStatus, LOCKED)
                .set(UserCoupon::getLockedOrderNo, request.getOrderNo())
                .set(UserCoupon::getUpdatedAt, LocalDateTime.now()));
        if (updated != 1) {
            throw new IllegalArgumentException("优惠券已被使用或锁定");
        }
        record(coupon, request.getOrderNo(), "LOCK", UNUSED, LOCKED, coupon.getDiscountAmount(), "订单结算锁定");

        CouponLockResult result = new CouponLockResult();
        result.setCouponId(coupon.getId());
        result.setDiscountAmount(coupon.getDiscountAmount());
        result.setPayableAmount(request.getFinalAmount().subtract(coupon.getDiscountAmount()).max(BigDecimal.ZERO));
        return result;
    }

    @Transactional
    public void use(CouponUseRequest request) {
        UserCoupon coupon = userCouponMapper.selectById(request.getCouponId());
        if (coupon == null || !coupon.getPassengerId().equals(request.getPassengerId())) {
            throw new IllegalArgumentException("优惠券不存在");
        }
        if (USED.equals(coupon.getStatus())) {
            return;
        }
        int updated = userCouponMapper.update(null, Wrappers.<UserCoupon>lambdaUpdate()
                .eq(UserCoupon::getId, request.getCouponId())
                .eq(UserCoupon::getPassengerId, request.getPassengerId())
                .eq(UserCoupon::getStatus, LOCKED)
                .eq(UserCoupon::getLockedOrderNo, request.getOrderNo())
                .set(UserCoupon::getStatus, USED)
                .set(UserCoupon::getUsedAt, LocalDateTime.now())
                .set(UserCoupon::getUpdatedAt, LocalDateTime.now()));
        if (updated != 1) {
            throw new IllegalArgumentException("优惠券未被当前订单锁定");
        }
        record(coupon, request.getOrderNo(), "USE", LOCKED, USED, coupon.getDiscountAmount(), "支付成功核销");
    }

    @Transactional
    public void release(CouponUseRequest request) {
        UserCoupon coupon = userCouponMapper.selectById(request.getCouponId());
        if (coupon == null || !coupon.getPassengerId().equals(request.getPassengerId())) {
            throw new IllegalArgumentException("优惠券不存在");
        }
        int updated = userCouponMapper.update(null, Wrappers.<UserCoupon>lambdaUpdate()
                .eq(UserCoupon::getId, request.getCouponId())
                .eq(UserCoupon::getPassengerId, request.getPassengerId())
                .eq(UserCoupon::getStatus, LOCKED)
                .eq(UserCoupon::getLockedOrderNo, request.getOrderNo())
                .set(UserCoupon::getStatus, UNUSED)
                .set(UserCoupon::getLockedOrderNo, null)
                .set(UserCoupon::getUpdatedAt, LocalDateTime.now()));
        if (updated == 1) {
            record(coupon, request.getOrderNo(), "RELEASE", LOCKED, UNUSED, coupon.getDiscountAmount(), "支付失败释放");
        }
    }

    private UserCoupon bestCoupon(CouponLockRequest request) {
        LocalDateTime now = LocalDateTime.now();
        return userCouponMapper.selectOne(Wrappers.<UserCoupon>lambdaQuery()
                .eq(UserCoupon::getPassengerId, request.getPassengerId())
                .eq(UserCoupon::getStatus, UNUSED)
                .le(UserCoupon::getValidStartAt, now)
                .gt(UserCoupon::getValidEndAt, now)
                .le(UserCoupon::getThresholdAmount, request.getFinalAmount())
                .and(w -> w.isNull(UserCoupon::getCityCode).or().eq(UserCoupon::getCityCode, request.getCityCode()))
                .and(w -> w.isNull(UserCoupon::getProductCode).or().eq(UserCoupon::getProductCode, request.getProductCode()))
                .orderByDesc(UserCoupon::getDiscountAmount)
                .orderByAsc(UserCoupon::getValidEndAt)
                .last("LIMIT 1"));
    }

    private boolean canUse(UserCoupon coupon, CouponLockRequest request) {
        LocalDateTime now = LocalDateTime.now();
        return coupon.getPassengerId().equals(request.getPassengerId())
                && UNUSED.equals(coupon.getStatus())
                && !coupon.getValidStartAt().isAfter(now)
                && coupon.getValidEndAt().isAfter(now)
                && coupon.getThresholdAmount().compareTo(request.getFinalAmount()) <= 0
                && matches(coupon.getCityCode(), request.getCityCode())
                && matches(coupon.getProductCode(), request.getProductCode());
    }

    private boolean matches(String couponScope, String orderScope) {
        return couponScope == null || couponScope.isBlank() || couponScope.equals(orderScope);
    }

    private void record(UserCoupon coupon, String orderNo, String action, String before, String after,
                        BigDecimal discountAmount, String reason) {
        recordMapper.insert(new CouponUseRecord()
                .setUserCouponId(coupon.getId())
                .setTemplateId(coupon.getTemplateId())
                .setPassengerId(coupon.getPassengerId())
                .setOrderNo(orderNo)
                .setActionType(action)
                .setDiscountAmount(discountAmount)
                .setBeforeStatus(before)
                .setAfterStatus(after)
                .setReason(reason)
                .setCreatedAt(LocalDateTime.now()));
    }

    private CouponVO toVO(UserCoupon coupon) {
        CouponVO vo = new CouponVO();
        vo.setCouponId(coupon.getId());
        vo.setCouponName(coupon.getCouponName());
        vo.setThresholdAmount(coupon.getThresholdAmount());
        vo.setDiscountAmount(coupon.getDiscountAmount());
        vo.setCityCode(coupon.getCityCode());
        vo.setProductCode(coupon.getProductCode());
        vo.setStatus(coupon.getStatus());
        vo.setValidStartAt(coupon.getValidStartAt());
        vo.setValidEndAt(coupon.getValidEndAt());
        return vo;
    }
}
