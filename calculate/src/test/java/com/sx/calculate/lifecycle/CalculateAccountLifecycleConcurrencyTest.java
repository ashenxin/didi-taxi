package com.sx.calculate.lifecycle;

import com.sx.calculate.dao.CouponUseRecordMapper;
import com.sx.calculate.dao.UserCouponMapper;
import com.sx.calculate.lifecycle.dao.CalculateAccountLifecycleEventInboxMapper;
import com.sx.calculate.lifecycle.dao.CalculateAccountLifecycleProjectionMapper;
import com.sx.calculate.lifecycle.dao.CalculateLifecycleParticipantInboxMapper;
import com.sx.calculate.lifecycle.exception.CalculateLifecycleBlockedException;
import com.sx.calculate.lifecycle.model.CalculateLifecycleCommand;
import com.sx.calculate.lifecycle.model.CalculateLifecycleDecision;
import com.sx.calculate.lifecycle.service.AccountLifecycleCalculateParticipantService;
import com.sx.calculate.lifecycle.service.CalculateLifecycleProjectionService;
import com.sx.calculate.model.UserCoupon;
import com.sx.calculate.model.dto.CouponLockRequest;
import com.sx.calculate.service.CouponService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = "calculate.lifecycle.write-fence.mode=ENFORCE")
class CalculateAccountLifecycleConcurrencyTest {
    private static final long CUSTOMER_ID = 23001L;

    @Autowired private AccountLifecycleCalculateParticipantService participant;
    @Autowired private CalculateLifecycleProjectionService projections;
    @Autowired private CouponService coupons;
    @Autowired private UserCouponMapper userCoupons;
    @Autowired private CouponUseRecordMapper couponRecords;
    @Autowired private CalculateLifecycleParticipantInboxMapper participantInboxes;
    @Autowired private CalculateAccountLifecycleProjectionMapper lifecycleProjections;
    @Autowired private CalculateAccountLifecycleEventInboxMapper lifecycleEvents;

    @BeforeEach
    void clean() {
        participantInboxes.delete(null);
        lifecycleProjections.delete(null);
        lifecycleEvents.delete(null);
        couponRecords.delete(null);
        userCoupons.delete(null);
    }

    @Test
    void finalCheckAndNewCouponLockCannotProducePassWithLockedCoupon() throws Exception {
        projections.seedActive(CUSTOMER_ID, "seed-concurrency-23001", LocalDateTime.now());
        UserCoupon coupon = validCoupon();
        userCoupons.insert(coupon);
        CouponLockRequest lockRequest = lockRequest(coupon.getId());
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        try (var executor = Executors.newFixedThreadPool(2)) {
            var finalFuture = executor.submit(() -> {
                ready.countDown();
                start.await(5, TimeUnit.SECONDS);
                return participant.fence(command()).decision();
            });
            var lockFuture = executor.submit(() -> {
                ready.countDown();
                start.await(5, TimeUnit.SECONDS);
                try {
                    coupons.lock(lockRequest);
                    return true;
                } catch (CalculateLifecycleBlockedException ex) {
                    return false;
                }
            });
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            CalculateLifecycleDecision finalDecision = finalFuture.get(10, TimeUnit.SECONDS);
            boolean lockSucceeded = lockFuture.get(10, TimeUnit.SECONDS);
            String finalCouponStatus = userCoupons.selectById(coupon.getId()).getStatus();

            assertThat(finalDecision == CalculateLifecycleDecision.PASS
                    && "LOCKED".equals(finalCouponStatus)).isFalse();
            if (lockSucceeded) {
                assertThat(finalDecision).isEqualTo(CalculateLifecycleDecision.BLOCKED);
            } else {
                assertThat(finalDecision).isEqualTo(CalculateLifecycleDecision.PASS);
            }
        }
    }

    private static UserCoupon validCoupon() {
        LocalDateTime now = LocalDateTime.now();
        return new UserCoupon()
                .setTemplateId(301L)
                .setPassengerId(CUSTOMER_ID)
                .setCompanyId(1L)
                .setCouponName("并发测试券")
                .setCouponType("AMOUNT_OFF")
                .setThresholdAmount(BigDecimal.ZERO)
                .setDiscountAmount(new BigDecimal("5.00"))
                .setCityCode("110100")
                .setProductCode("ECONOMY")
                .setStatus("UNUSED")
                .setReceivedAt(now)
                .setValidStartAt(now.minusDays(1))
                .setValidEndAt(now.plusDays(1))
                .setCreatedAt(now)
                .setUpdatedAt(now);
    }

    private static CouponLockRequest lockRequest(long couponId) {
        CouponLockRequest request = new CouponLockRequest();
        request.setPassengerId(CUSTOMER_ID);
        request.setOrderNo("ORDER-CONCURRENT-1");
        request.setCouponId(couponId);
        request.setFinalAmount(new BigDecimal("20.00"));
        request.setCompanyId(1L);
        request.setCityCode("110100");
        request.setProductCode("ECONOMY");
        request.setManualNoCoupon(false);
        return request;
    }

    private static CalculateLifecycleCommand command() {
        return new CalculateLifecycleCommand("op-concurrent", "CALCULATE_FINAL_CHECK",
                CUSTOMER_ID, 1, "CANCELLING", "event-concurrent-final", LocalDateTime.now());
    }
}
