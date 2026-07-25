package com.sx.calculate.lifecycle;

import com.sx.calculate.dao.BenefitPointsAccountMapper;
import com.sx.calculate.dao.BenefitPointsFlowMapper;
import com.sx.calculate.dao.CouponUseRecordMapper;
import com.sx.calculate.dao.UserCouponMapper;
import com.sx.calculate.lifecycle.dao.CalculateAccountLifecycleEventInboxMapper;
import com.sx.calculate.lifecycle.dao.CalculateAccountLifecycleProjectionMapper;
import com.sx.calculate.lifecycle.dao.CalculateLifecycleParticipantInboxMapper;
import com.sx.calculate.lifecycle.model.CalculateLifecycleCommand;
import com.sx.calculate.lifecycle.model.CalculateLifecycleDecision;
import com.sx.calculate.lifecycle.service.AccountLifecycleCalculateParticipantService;
import com.sx.calculate.lifecycle.service.CalculateLifecycleProjectionService;
import com.sx.calculate.model.BenefitPointsAccount;
import com.sx.calculate.model.UserCoupon;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = "calculate.lifecycle.write-fence.mode=ENFORCE")
class AccountLifecycleCalculateParticipantIntegrationTest {
    private static final long CUSTOMER_ID = 22001L;

    @Autowired private AccountLifecycleCalculateParticipantService participant;
    @Autowired private CalculateLifecycleProjectionService projections;
    @Autowired private CalculateLifecycleParticipantInboxMapper participantInboxes;
    @Autowired private CalculateAccountLifecycleProjectionMapper lifecycleProjections;
    @Autowired private CalculateAccountLifecycleEventInboxMapper lifecycleEvents;
    @Autowired private UserCouponMapper userCoupons;
    @Autowired private CouponUseRecordMapper couponRecords;
    @Autowired private BenefitPointsAccountMapper pointAccounts;
    @Autowired private BenefitPointsFlowMapper pointFlows;

    @BeforeEach
    void clean() {
        participantInboxes.delete(null);
        lifecycleProjections.delete(null);
        lifecycleEvents.delete(null);
        couponRecords.delete(null);
        userCoupons.delete(null);
        pointFlows.delete(null);
        pointAccounts.delete(null);
    }

    @Test
    void completeParticipantFlowIsTransactionalIdempotentAndQueryable() {
        projections.seedActive(CUSTOMER_ID, "seed-calculate-22001", LocalDateTime.now());
        userCoupons.insert(unusedCoupon(1L));
        userCoupons.insert(unusedCoupon(2L));
        pointAccounts.insert(new BenefitPointsAccount()
                .setCustomerId(CUSTOMER_ID)
                .setAvailablePoints(25)
                .setTotalEarnedPoints(25)
                .setTotalUsedPoints(0)
                .setTotalClearedPoints(0)
                .setStatus("ACTIVE")
                .setVersion(0)
                .setCreatedAt(LocalDateTime.now())
                .setUpdatedAt(LocalDateTime.now()));

        var check = participant.fence(command("op-22001", "CALCULATE_FINAL_CHECK", 1));
        var coupons = participant.action(command(
                "op-22001", "CALCULATE_INVALIDATE_UNUSED_COUPONS", 1));
        var points = participant.action(command("op-22001", "CALCULATE_CLEAR_POINTS", 1));

        assertThat(check.decision()).isEqualTo(CalculateLifecycleDecision.PASS);
        assertThat(coupons.result()).containsEntry("invalidatedCount", 2);
        assertThat(points.result()).containsEntry("clearedPoints", 25);
        assertThat(userCoupons.selectList(null)).allMatch(coupon -> "INVALID".equals(coupon.getStatus()));
        assertThat(couponRecords.selectCount(null)).isEqualTo(2L);
        assertThat(pointFlows.selectCount(null)).isEqualTo(1L);
        assertThat(pointAccounts.selectByCustomerIdForUpdate(CUSTOMER_ID).getAvailablePoints()).isZero();

        var couponReplay = participant.action(command(
                "op-22001", "CALCULATE_INVALIDATE_UNUSED_COUPONS", 1));
        var pointsReplay = participant.action(command("op-22001", "CALCULATE_CLEAR_POINTS", 1));
        assertThat(couponReplay.result()).containsEntry("invalidatedCount", 2);
        assertThat(pointsReplay.result()).containsEntry("clearedPoints", 25);
        assertThat(couponRecords.selectCount(null)).isEqualTo(2L);
        assertThat(pointFlows.selectCount(null)).isEqualTo(1L);
        assertThat(participant.findResult(
                "op-22001", "CALCULATE_CLEAR_POINTS").result())
                .containsEntry("clearedPoints", 25);
    }

    @Test
    void lockedCouponBlocksFinalCheckWithPermanentResult() {
        projections.seedActive(CUSTOMER_ID, "seed-calculate-locked", LocalDateTime.now());
        UserCoupon locked = unusedCoupon(3L)
                .setStatus("LOCKED")
                .setLockedOrderNo("ORDER-LOCKED-1")
                .setLockedFinalAmount(new BigDecimal("20.00"))
                .setLockedDiscountAmount(new BigDecimal("5.00"));
        userCoupons.insert(locked);

        var result = participant.fence(command("op-locked", "CALCULATE_FINAL_CHECK", 1));

        assertThat(result.decision()).isEqualTo(CalculateLifecycleDecision.BLOCKED);
        assertThat(result.blockers()).singleElement()
                .extracting("resourceNo").isEqualTo("ORDER-LOCKED-1");
        assertThat(participant.findResult("op-locked", "CALCULATE_FINAL_CHECK"))
                .isEqualTo(result);
    }

    private static UserCoupon unusedCoupon(long templateId) {
        LocalDateTime now = LocalDateTime.now();
        return new UserCoupon()
                .setTemplateId(templateId)
                .setPassengerId(CUSTOMER_ID)
                .setCompanyId(1L)
                .setCouponName("测试券" + templateId)
                .setCouponType("AMOUNT_OFF")
                .setThresholdAmount(BigDecimal.ZERO)
                .setDiscountAmount(new BigDecimal("5.00"))
                .setCityCode("110100")
                .setProductCode("ECONOMY")
                .setStatus("UNUSED")
                .setReceivedAt(now)
                .setValidStartAt(now.minusDays(1))
                .setValidEndAt(now.plusDays(2))
                .setCreatedAt(now)
                .setUpdatedAt(now);
    }

    private static CalculateLifecycleCommand command(
            String operationNo, String stepCode, long version) {
        return new CalculateLifecycleCommand(operationNo, stepCode, CUSTOMER_ID, version,
                "CANCELLING", operationNo + "-" + stepCode, LocalDateTime.now());
    }
}
