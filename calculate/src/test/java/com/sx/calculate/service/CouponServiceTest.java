package com.sx.calculate.service;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.sx.calculate.dao.CouponTemplateMapper;
import com.sx.calculate.dao.CouponUseRecordMapper;
import com.sx.calculate.dao.UserCouponMapper;
import com.sx.calculate.model.UserCoupon;
import com.sx.calculate.model.CouponUseRecord;
import com.sx.calculate.model.dto.CouponLockRequest;
import com.sx.calculate.model.dto.CouponLockResult;
import com.sx.calculate.model.dto.CouponUseRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;

class CouponServiceTest {

    private final CouponTemplateMapper templateMapper = mock(CouponTemplateMapper.class);
    private final UserCouponMapper userCouponMapper = mock(UserCouponMapper.class);
    private final CouponUseRecordMapper recordMapper = mock(CouponUseRecordMapper.class);
    private CouponService service;

    @BeforeEach
    void setUp() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "coupon-test"),
                UserCoupon.class);
        service = new CouponService(templateMapper, userCouponMapper, recordMapper);
    }

    @Test
    void discountGreaterThanFareIsRejectedInsteadOfSilentlyClamped() {
        UserCoupon coupon = coupon(1L, "AMOUNT_OFF", "50.00", LocalDateTime.now().plusDays(2));
        when(userCouponMapper.selectById(1L)).thenReturn(coupon);

        assertThatThrownBy(() -> service.lock(request(1L, "30.00")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("优惠金额不能大于最终车费");
    }

    @Test
    void negativeDiscountIsRejectedAsInvalidCouponData() {
        UserCoupon coupon = coupon(2L, "AMOUNT_OFF", "-1.00", LocalDateTime.now().plusDays(2));
        when(userCouponMapper.selectById(2L)).thenReturn(coupon);

        assertThatThrownBy(() -> service.lock(request(2L, "30.00")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("优惠金额不能为负数");
    }

    @Test
    void noAvailableCouponReturnsZeroDiscountBill() {
        when(userCouponMapper.selectList(any(Wrapper.class))).thenReturn(List.of());

        CouponLockResult result = service.lock(request(null, "30.00"));

        assertThat(result.getCouponId()).isNull();
        assertThat(result.getDiscountAmount()).isEqualByComparingTo("0.00");
        assertThat(result.getPayableAmount()).isEqualByComparingTo("30.00");
    }

    @Test
    void equalDiscountPrefersEarlierExpiryThenLowerCouponId() {
        LocalDateTime early = LocalDateTime.now().plusDays(1);
        UserCoupon later = coupon(9L, "AMOUNT_OFF", "5.00", early.plusDays(1));
        UserCoupon lowerIdEarly = coupon(3L, "AMOUNT_OFF", "5.00", early);
        UserCoupon higherIdEarly = coupon(4L, "AMOUNT_OFF", "5.00", early);
        when(userCouponMapper.selectList(any(Wrapper.class)))
                .thenReturn(List.of(later, higherIdEarly, lowerIdEarly));
        when(userCouponMapper.selectById(3L)).thenReturn(lowerIdEarly);
        when(userCouponMapper.update(any(), any(Wrapper.class))).thenReturn(1);
        when(recordMapper.insert(any(CouponUseRecord.class))).thenReturn(1);

        CouponLockResult result = service.lock(request(null, "30.00"));

        assertThat(result.getCouponId()).isEqualTo(3L);
    }

    @Test
    void releaseIsRecordedAsInvalidBillCompensationNotPaymentFailure() {
        UserCoupon coupon = coupon(5L, "AMOUNT_OFF", "5.00", LocalDateTime.now().plusDays(2));
        coupon.setStatus("LOCKED");
        coupon.setLockedOrderNo("T202607170001");
        coupon.setLockedFinalAmount(new BigDecimal("30.00"));
        coupon.setLockedDiscountAmount(new BigDecimal("5.00"));
        when(userCouponMapper.selectById(5L)).thenReturn(coupon);
        when(userCouponMapper.update(any(), any(Wrapper.class))).thenReturn(1);
        when(recordMapper.insert(any(CouponUseRecord.class))).thenReturn(1);
        CouponUseRequest request = new CouponUseRequest();
        request.setCouponId(5L);
        request.setPassengerId(10001L);
        request.setOrderNo("T202607170001");
        request.setDiscountAmount(new BigDecimal("5.00"));

        service.release(request);

        ArgumentCaptor<CouponUseRecord> captor = ArgumentCaptor.forClass(CouponUseRecord.class);
        verify(recordMapper).insert(captor.capture());
        assertThat(captor.getValue().getReason()).isEqualTo("结算金额固化失败补偿");
    }

    @Test
    void replayReturnsCouponAlreadyLockedBySameOrderWithoutSelectingAnother() {
        UserCoupon coupon = coupon(6L, "AMOUNT_OFF", "5.00", LocalDateTime.now().plusDays(2));
        coupon.setStatus("LOCKED");
        coupon.setLockedOrderNo("T202607170001");
        coupon.setLockedFinalAmount(new BigDecimal("30.00"));
        coupon.setLockedDiscountAmount(new BigDecimal("5.00"));
        when(userCouponMapper.selectOne(any(Wrapper.class))).thenReturn(coupon);

        CouponLockResult result = service.lock(request(null, "30.00"));

        assertThat(result.getCouponId()).isEqualTo(6L);
        assertThat(result.getDiscountAmount()).isEqualByComparingTo("5.00");
        assertThat(result.getPayableAmount()).isEqualByComparingTo("25.00");
        verify(userCouponMapper, never()).update(any(), any(Wrapper.class));
    }

    @Test
    void replayAfterZeroBillCouponWasUsedReturnsOriginalCoupon() {
        UserCoupon coupon = coupon(7L, "AMOUNT_OFF", "30.00", LocalDateTime.now().plusDays(2));
        coupon.setStatus("USED");
        coupon.setLockedOrderNo("T202607170001");
        coupon.setLockedFinalAmount(new BigDecimal("30.00"));
        coupon.setLockedDiscountAmount(new BigDecimal("30.00"));
        when(userCouponMapper.selectOne(any(Wrapper.class))).thenReturn(coupon);

        CouponLockResult result = service.lock(request(null, "30.00"));

        assertThat(result.getCouponId()).isEqualTo(7L);
        assertThat(result.getDiscountAmount()).isEqualByComparingTo("30.00");
        assertThat(result.getPayableAmount()).isZero();
        verify(userCouponMapper, never()).update(any(), any(Wrapper.class));
    }

    @Test
    void replayRejectsChangedFinalAmountInsteadOfRecalculatingLockedCoupon() {
        UserCoupon coupon = coupon(8L, "PERCENT_OFF", "0.00", LocalDateTime.now().plusDays(2));
        coupon.setDiscountRate(new BigDecimal("0.8000"));
        coupon.setStatus("LOCKED");
        coupon.setLockedOrderNo("T202607170001");
        coupon.setLockedFinalAmount(new BigDecimal("30.00"));
        coupon.setLockedDiscountAmount(new BigDecimal("6.00"));
        when(userCouponMapper.selectOne(any(Wrapper.class))).thenReturn(coupon);

        assertThatThrownBy(() -> service.lock(request(null, "31.00")))
                .hasMessageContaining("锁券金额与原请求不一致");
    }

    @Test
    void usedCouponIsIdempotentOnlyForItsOriginalOrder() {
        UserCoupon coupon = coupon(9L, "AMOUNT_OFF", "5.00", LocalDateTime.now().plusDays(2));
        coupon.setStatus("USED");
        coupon.setLockedOrderNo("ANOTHER-ORDER");
        when(userCouponMapper.selectById(9L)).thenReturn(coupon);
        CouponUseRequest request = new CouponUseRequest();
        request.setCouponId(9L);
        request.setPassengerId(10001L);
        request.setOrderNo("T202607170001");

        assertThatThrownBy(() -> service.use(request))
                .hasMessageContaining("其他订单");
    }

    private static CouponLockRequest request(Long couponId, String finalAmount) {
        CouponLockRequest request = new CouponLockRequest();
        request.setPassengerId(10001L);
        request.setOrderNo("T202607170001");
        request.setCouponId(couponId);
        request.setFinalAmount(new BigDecimal(finalAmount));
        request.setCompanyId(9L);
        request.setCityCode("330100");
        request.setProductCode("ECONOMY");
        return request;
    }

    private static UserCoupon coupon(Long id, String type, String discount, LocalDateTime validEndAt) {
        return new UserCoupon()
                .setId(id)
                .setTemplateId(100L + id)
                .setPassengerId(10001L)
                .setCompanyId(9L)
                .setCouponType(type)
                .setThresholdAmount(BigDecimal.ZERO)
                .setDiscountAmount(new BigDecimal(discount))
                .setCityCode("330100")
                .setProductCode("ECONOMY")
                .setStatus("UNUSED")
                .setValidStartAt(LocalDateTime.now().minusDays(1))
                .setValidEndAt(validEndAt);
    }
}
