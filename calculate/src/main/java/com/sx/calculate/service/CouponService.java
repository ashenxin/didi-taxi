package com.sx.calculate.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sx.calculate.dao.CouponTemplateMapper;
import com.sx.calculate.dao.CouponUseRecordMapper;
import com.sx.calculate.dao.UserCouponMapper;
import com.sx.calculate.model.CouponTemplate;
import com.sx.calculate.model.CouponUseRecord;
import com.sx.calculate.model.UserCoupon;
import com.sx.calculate.model.dto.CouponClaimResult;
import com.sx.calculate.model.dto.CouponLockRequest;
import com.sx.calculate.model.dto.CouponLockResult;
import com.sx.calculate.model.dto.CouponPageVO;
import com.sx.calculate.model.dto.CouponTemplatePageVO;
import com.sx.calculate.model.dto.CouponTemplateUpsertRequest;
import com.sx.calculate.model.dto.CouponTemplateVO;
import com.sx.calculate.model.dto.CouponUseRequest;
import com.sx.calculate.model.dto.CouponVO;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

@Service
public class CouponService {
    private static final String UNUSED = "UNUSED";
    private static final String LOCKED = "LOCKED";
    private static final String USED = "USED";
    private static final String INVALID = "INVALID";
    private static final String EXPIRED = "EXPIRED";
    private static final String TEMPLATE_DRAFT = "DRAFT";
    private static final String TEMPLATE_PUBLISHED = "PUBLISHED";
    private static final String TEMPLATE_OFFLINE = "OFFLINE";
    private static final String CLAIM_IDENTITY_PHONE = "PHONE";
    private static final String INVALID_REASON_ACCOUNT_CANCEL = "ACCOUNT_CANCEL";
    private static final String TYPE_AMOUNT_OFF = "AMOUNT_OFF";
    private static final String TYPE_PERCENT_OFF = "PERCENT_OFF";

    private final CouponTemplateMapper templateMapper;
    private final UserCouponMapper userCouponMapper;
    private final CouponUseRecordMapper recordMapper;

    public CouponService(CouponTemplateMapper templateMapper,
                         UserCouponMapper userCouponMapper,
                         CouponUseRecordMapper recordMapper) {
        this.templateMapper = templateMapper;
        this.userCouponMapper = userCouponMapper;
        this.recordMapper = recordMapper;
    }

    public CouponPageVO page(Long passengerId, String status, int pageNo, int pageSize) {
        int safePageNo = Math.max(pageNo, 1);
        int safePageSize = Math.min(Math.max(pageSize, 1), 50);
        LambdaQueryWrapper<UserCoupon> countWrapper = Wrappers.<UserCoupon>lambdaQuery()
                .eq(UserCoupon::getPassengerId, passengerId);
        LambdaQueryWrapper<UserCoupon> wrapper = Wrappers.<UserCoupon>lambdaQuery()
                .eq(UserCoupon::getPassengerId, passengerId);
        if (status != null && !status.isBlank()) {
            countWrapper.eq(UserCoupon::getStatus, status);
            wrapper.eq(UserCoupon::getStatus, status);
        }
        long offset = (long) (safePageNo - 1) * safePageSize;
        wrapper.orderByAsc(UserCoupon::getValidEndAt)
                .orderByDesc(UserCoupon::getId)
                .last("LIMIT " + offset + "," + safePageSize);
        Long total = userCouponMapper.selectCount(countWrapper);
        CouponPageVO vo = new CouponPageVO();
        vo.setPageNo(safePageNo);
        vo.setPageSize(safePageSize);
        vo.setTotal(total == null ? 0 : total);
        vo.setList(userCouponMapper.selectList(wrapper).stream().map(coupon -> toVO(coupon, null)).toList());
        return vo;
    }

    public List<CouponVO> listAvailable(Long passengerId, Long companyId, BigDecimal finalAmount,
                                        String cityCode, String productCode) {
        return listAvailableCoupons(passengerId, companyId, finalAmount, cityCode, productCode)
                .stream()
                .map(coupon -> toVO(coupon, finalAmount))
                .sorted(availableComparator())
                .toList();
    }

    public CouponTemplatePageVO pageTemplates(Long companyId, String cityCode, String productCode, String status,
                                              int pageNo, int pageSize) {
        int safePageNo = Math.max(pageNo, 1);
        int safePageSize = Math.min(Math.max(pageSize, 1), 50);
        LambdaQueryWrapper<CouponTemplate> countWrapper = Wrappers.<CouponTemplate>lambdaQuery()
                .eq(CouponTemplate::getIsDeleted, 0);
        LambdaQueryWrapper<CouponTemplate> wrapper = Wrappers.<CouponTemplate>lambdaQuery()
                .eq(CouponTemplate::getIsDeleted, 0);
        if (companyId != null) {
            countWrapper.eq(CouponTemplate::getCompanyId, companyId);
            wrapper.eq(CouponTemplate::getCompanyId, companyId);
        }
        if (cityCode != null && !cityCode.isBlank()) {
            countWrapper.eq(CouponTemplate::getCityCode, cityCode);
            wrapper.eq(CouponTemplate::getCityCode, cityCode);
        }
        if (productCode != null && !productCode.isBlank()) {
            countWrapper.eq(CouponTemplate::getProductCode, productCode);
            wrapper.eq(CouponTemplate::getProductCode, productCode);
        }
        if (status != null && !status.isBlank()) {
            countWrapper.eq(CouponTemplate::getStatus, status);
            wrapper.eq(CouponTemplate::getStatus, status);
        }
        long offset = (long) (safePageNo - 1) * safePageSize;
        wrapper.orderByDesc(CouponTemplate::getId)
                .last("LIMIT " + offset + "," + safePageSize);
        Long total = templateMapper.selectCount(countWrapper);
        CouponTemplatePageVO vo = new CouponTemplatePageVO();
        vo.setPageNo(safePageNo);
        vo.setPageSize(safePageSize);
        vo.setTotal(total == null ? 0 : total);
        vo.setList(templateMapper.selectList(wrapper).stream().map(this::toTemplateVO).toList());
        return vo;
    }

    @Transactional
    public CouponTemplateVO createTemplate(CouponTemplateUpsertRequest request) {
        validateTemplate(request);
        LocalDateTime now = LocalDateTime.now();
        CouponTemplate template = new CouponTemplate()
                .setCompanyId(request.getCompanyId())
                .setCompanyNo(request.getCompanyNo())
                .setCompanyNameSnapshot(request.getCompanyNameSnapshot())
                .setTeamIdSnapshot(request.getTeamIdSnapshot())
                .setTeamNameSnapshot(request.getTeamNameSnapshot())
                .setName(request.getName())
                .setCouponType(request.getCouponType())
                .setThresholdAmount(defaultAmount(request.getThresholdAmount()))
                .setDiscountAmount(request.getDiscountAmount())
                .setDiscountRate(request.getDiscountRate())
                .setMaxDiscountAmount(request.getMaxDiscountAmount())
                .setCityCode(request.getCityCode())
                .setProductCode(request.getProductCode())
                .setValidDays(request.getValidDays())
                .setValidStartAt(request.getValidStartAt())
                .setValidEndAt(request.getValidEndAt())
                .setTotalCount(request.getTotalCount())
                .setReceivedCount(0)
                .setUsedCount(0)
                .setPerUserLimit(request.getPerUserLimit() == null ? 1 : request.getPerUserLimit())
                .setIssueType(blankToDefault(request.getIssueType(), "LOGIN_POPUP"))
                .setSourceType(blankToDefault(request.getSourceType(), "NORMAL"))
                .setActivityCode(request.getActivityCode())
                .setRuleConfig(request.getRuleConfig())
                .setStatus(TEMPLATE_DRAFT)
                .setCreatedBy(request.getOperatorId())
                .setUpdatedBy(request.getOperatorId())
                .setCreatedAt(now)
                .setUpdatedAt(now)
                .setIsDeleted(0);
        templateMapper.insert(template);
        return toTemplateVO(template);
    }

    @Transactional
    public CouponTemplateVO updateTemplate(Long templateId, CouponTemplateUpsertRequest request) {
        CouponTemplate existing = requireTemplate(templateId);
        if (TEMPLATE_PUBLISHED.equals(existing.getStatus())) {
            throw new IllegalArgumentException("已启用的优惠券方案不可编辑，请先停用");
        }
        validateTemplate(request);
        existing.setCompanyId(request.getCompanyId())
                .setCompanyNo(request.getCompanyNo())
                .setCompanyNameSnapshot(request.getCompanyNameSnapshot())
                .setTeamIdSnapshot(request.getTeamIdSnapshot())
                .setTeamNameSnapshot(request.getTeamNameSnapshot())
                .setName(request.getName())
                .setCouponType(request.getCouponType())
                .setThresholdAmount(defaultAmount(request.getThresholdAmount()))
                .setDiscountAmount(request.getDiscountAmount())
                .setDiscountRate(request.getDiscountRate())
                .setMaxDiscountAmount(request.getMaxDiscountAmount())
                .setCityCode(request.getCityCode())
                .setProductCode(request.getProductCode())
                .setValidDays(request.getValidDays())
                .setValidStartAt(request.getValidStartAt())
                .setValidEndAt(request.getValidEndAt())
                .setTotalCount(request.getTotalCount())
                .setPerUserLimit(request.getPerUserLimit() == null ? 1 : request.getPerUserLimit())
                .setIssueType(blankToDefault(request.getIssueType(), "LOGIN_POPUP"))
                .setSourceType(blankToDefault(request.getSourceType(), "NORMAL"))
                .setActivityCode(request.getActivityCode())
                .setRuleConfig(request.getRuleConfig())
                .setUpdatedBy(request.getOperatorId())
                .setUpdatedAt(LocalDateTime.now());
        templateMapper.updateById(existing);
        return toTemplateVO(existing);
    }

    @Transactional
    public CouponTemplateVO publishTemplate(Long templateId, Long operatorId) {
        CouponTemplate template = requireTemplate(templateId);
        LocalDateTime now = LocalDateTime.now();
        template.setStatus(TEMPLATE_PUBLISHED)
                .setPublishedAt(now)
                .setOfflineAt(null)
                .setUpdatedBy(operatorId)
                .setUpdatedAt(now);
        templateMapper.updateById(template);
        return toTemplateVO(template);
    }

    @Transactional
    public CouponTemplateVO offlineTemplate(Long templateId, Long operatorId) {
        CouponTemplate template = requireTemplate(templateId);
        LocalDateTime now = LocalDateTime.now();
        template.setStatus(TEMPLATE_OFFLINE)
                .setOfflineAt(now)
                .setUpdatedBy(operatorId)
                .setUpdatedAt(now);
        templateMapper.updateById(template);
        return toTemplateVO(template);
    }

    public List<CouponTemplateVO> claimable(Long passengerId) {
        return claimable(passengerId, null, null);
    }

    public List<CouponTemplateVO> claimable(Long passengerId, String claimIdentityType, String claimIdentityHash) {
        LocalDateTime now = LocalDateTime.now();
        return templateMapper.selectList(Wrappers.<CouponTemplate>lambdaQuery()
                        .eq(CouponTemplate::getStatus, TEMPLATE_PUBLISHED)
                        .eq(CouponTemplate::getIsDeleted, 0)
                        .le(CouponTemplate::getValidStartAt, now)
                        .gt(CouponTemplate::getValidEndAt, now)
                        .apply("received_count < total_count")
                        .orderByAsc(CouponTemplate::getValidEndAt)
                        .orderByDesc(CouponTemplate::getId))
                .stream()
                .filter(template -> !hasClaimed(passengerId, template.getId(), claimIdentityType, claimIdentityHash))
                .map(this::toTemplateVO)
                .toList();
    }

    @Transactional
    public CouponClaimResult claimAll(Long passengerId) {
        return claimAll(passengerId, null, null);
    }

    @Transactional
    public CouponClaimResult claimAll(Long passengerId, String claimIdentityType, String claimIdentityHash) {
        CouponClaimResult result = new CouponClaimResult();
        int claimed = 0;
        int skipped = 0;
        for (CouponTemplateVO item : claimable(passengerId, claimIdentityType, claimIdentityHash)) {
            if (claimOne(passengerId, item.getId(), claimIdentityType, claimIdentityHash)) {
                claimed++;
            } else {
                skipped++;
            }
        }
        result.setClaimedCount(claimed);
        result.setSkippedCount(skipped);
        return result;
    }

    @Transactional
    public CouponClaimResult claimSelected(Long passengerId, List<Long> templateIds,
                                           String claimIdentityType, String claimIdentityHash) {
        CouponClaimResult result = new CouponClaimResult();
        if (templateIds == null || templateIds.isEmpty()) {
            return result;
        }
        int claimed = 0;
        int skipped = 0;
        for (Long templateId : templateIds.stream().filter(Objects::nonNull).distinct().toList()) {
            if (claimOne(passengerId, templateId, claimIdentityType, claimIdentityHash)) {
                claimed++;
            } else {
                skipped++;
            }
        }
        result.setClaimedCount(claimed);
        result.setSkippedCount(skipped);
        return result;
    }

    private boolean claimOne(Long passengerId, Long templateId) {
        return claimOne(passengerId, templateId, null, null);
    }

    private boolean claimOne(Long passengerId, Long templateId, String claimIdentityType, String claimIdentityHash) {
        CouponTemplate template = templateMapper.selectById(templateId);
        LocalDateTime now = LocalDateTime.now();
        String safeIdentityType = normalizeClaimIdentityType(claimIdentityType);
        String safeIdentityHash = normalizeClaimIdentityHash(claimIdentityHash);
        if (template == null
                || !TEMPLATE_PUBLISHED.equals(template.getStatus())
                || template.getIsDeleted() == null
                || template.getIsDeleted() != 0
                || template.getValidStartAt() == null
                || template.getValidEndAt() == null
                || template.getValidStartAt().isAfter(now)
                || !template.getValidEndAt().isAfter(now)
                || hasClaimed(passengerId, template.getId(), safeIdentityType, safeIdentityHash)) {
            return false;
        }
        int received = templateMapper.update(null, Wrappers.<CouponTemplate>lambdaUpdate()
                .eq(CouponTemplate::getId, template.getId())
                .eq(CouponTemplate::getStatus, TEMPLATE_PUBLISHED)
                .eq(CouponTemplate::getIsDeleted, 0)
                .apply("received_count < total_count")
                .setSql("received_count = received_count + 1")
                .set(CouponTemplate::getUpdatedAt, LocalDateTime.now()));
        if (received != 1) {
            return false;
        }
        userCouponMapper.insert(new UserCoupon()
                .setTemplateId(template.getId())
                .setPassengerId(passengerId)
                .setClaimIdentityType(safeIdentityType)
                .setClaimIdentityHash(safeIdentityHash)
                .setCompanyId(template.getCompanyId())
                .setCompanyNo(template.getCompanyNo())
                .setCompanyNameSnapshot(template.getCompanyNameSnapshot())
                .setTeamIdSnapshot(template.getTeamIdSnapshot())
                .setTeamNameSnapshot(template.getTeamNameSnapshot())
                .setCouponName(template.getName())
                .setCouponType(template.getCouponType())
                .setThresholdAmount(template.getThresholdAmount())
                .setDiscountAmount(template.getDiscountAmount())
                .setDiscountRate(template.getDiscountRate())
                .setMaxDiscountAmount(template.getMaxDiscountAmount())
                .setCityCode(template.getCityCode())
                .setProductCode(template.getProductCode())
                .setValidStartAt(template.getValidStartAt())
                .setValidEndAt(template.getValidEndAt())
                .setRuleSnapshot(template.getRuleConfig())
                .setStatus(UNUSED)
                .setCreatedAt(LocalDateTime.now())
                .setUpdatedAt(LocalDateTime.now()));
        return true;
    }

    @Transactional
    public void assertNoLockedCoupons(Long passengerId) {
        Long locked = userCouponMapper.selectCount(Wrappers.<UserCoupon>lambdaQuery()
                .eq(UserCoupon::getPassengerId, passengerId)
                .eq(UserCoupon::getStatus, LOCKED));
        if (locked != null && locked > 0) {
            throw new IllegalArgumentException("存在锁定中的优惠券，请先完成或取消相关订单");
        }
    }

    @Transactional
    public int invalidateByPassenger(Long passengerId, String reason) {
        assertNoLockedCoupons(passengerId);
        List<UserCoupon> coupons = userCouponMapper.selectList(Wrappers.<UserCoupon>lambdaQuery()
                .eq(UserCoupon::getPassengerId, passengerId)
                .eq(UserCoupon::getStatus, UNUSED));
        LocalDateTime now = LocalDateTime.now();
        int count = 0;
        String safeReason = reason == null || reason.isBlank() ? INVALID_REASON_ACCOUNT_CANCEL : reason.trim();
        for (UserCoupon coupon : coupons) {
            int updated = userCouponMapper.update(null, Wrappers.<UserCoupon>lambdaUpdate()
                    .eq(UserCoupon::getId, coupon.getId())
                    .eq(UserCoupon::getPassengerId, passengerId)
                    .eq(UserCoupon::getStatus, UNUSED)
                    .set(UserCoupon::getStatus, INVALID)
                    .set(UserCoupon::getInvalidReason, safeReason)
                    .set(UserCoupon::getInvalidAt, now)
                    .set(UserCoupon::getUpdatedAt, now));
            if (updated == 1) {
                record(coupon, null, "INVALIDATE", UNUSED, INVALID, BigDecimal.ZERO, safeReason);
                count++;
            }
        }
        return count;
    }

    @Transactional
    public CouponLockResult lock(CouponLockRequest request) {
        if (Boolean.TRUE.equals(request.getManualNoCoupon())) {
            CouponLockResult noCoupon = new CouponLockResult();
            noCoupon.setDiscountAmount(BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP));
            noCoupon.setPayableAmount(scale(request.getFinalAmount()));
            return noCoupon;
        }
        UserCoupon coupon = request.getCouponId() == null
                ? bestCoupon(request)
                : userCouponMapper.selectById(request.getCouponId());
        if (coupon == null || !canUse(coupon, request)) {
            throw new IllegalArgumentException("无可用优惠券");
        }
        BigDecimal discountAmount = calculateDiscount(coupon, request.getFinalAmount());
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
        record(coupon, request.getOrderNo(), "LOCK", UNUSED, LOCKED, discountAmount, "订单结算锁定");

        CouponLockResult result = new CouponLockResult();
        result.setCouponId(coupon.getId());
        result.setTemplateId(coupon.getTemplateId());
        result.setCompanyId(coupon.getCompanyId());
        result.setCompanyNo(coupon.getCompanyNo());
        result.setCompanyNameSnapshot(coupon.getCompanyNameSnapshot());
        result.setTeamIdSnapshot(coupon.getTeamIdSnapshot());
        result.setTeamNameSnapshot(coupon.getTeamNameSnapshot());
        result.setCouponType(coupon.getCouponType());
        result.setCouponRuleSnapshot(coupon.getRuleSnapshot());
        result.setDiscountAmount(discountAmount);
        result.setPayableAmount(scale(request.getFinalAmount().subtract(discountAmount).max(BigDecimal.ZERO)));
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
        BigDecimal discountAmount = request.getDiscountAmount() == null
                ? defaultAmount(coupon.getDiscountAmount())
                : request.getDiscountAmount();
        templateMapper.update(null, Wrappers.<CouponTemplate>lambdaUpdate()
                .eq(CouponTemplate::getId, coupon.getTemplateId())
                .setSql("used_count = used_count + 1")
                .set(CouponTemplate::getUpdatedAt, LocalDateTime.now()));
        record(coupon, request.getOrderNo(), "USE", LOCKED, USED, discountAmount, "支付成功核销");
    }

    @Transactional
    public void release(CouponUseRequest request) {
        UserCoupon coupon = userCouponMapper.selectById(request.getCouponId());
        if (coupon == null || !coupon.getPassengerId().equals(request.getPassengerId())) {
            throw new IllegalArgumentException("优惠券不存在");
        }
        String targetStatus = coupon.getValidEndAt() != null && !coupon.getValidEndAt().isAfter(LocalDateTime.now())
                ? EXPIRED
                : UNUSED;
        int updated = userCouponMapper.update(null, Wrappers.<UserCoupon>lambdaUpdate()
                .eq(UserCoupon::getId, request.getCouponId())
                .eq(UserCoupon::getPassengerId, request.getPassengerId())
                .eq(UserCoupon::getStatus, LOCKED)
                .eq(UserCoupon::getLockedOrderNo, request.getOrderNo())
                .set(UserCoupon::getStatus, targetStatus)
                .set(UserCoupon::getLockedOrderNo, null)
                .set(UserCoupon::getUpdatedAt, LocalDateTime.now()));
        if (updated == 1) {
            BigDecimal discountAmount = request.getDiscountAmount() == null
                    ? defaultAmount(coupon.getDiscountAmount())
                    : request.getDiscountAmount();
            record(coupon, request.getOrderNo(), "RELEASE", LOCKED, targetStatus, discountAmount, "支付失败释放");
        }
    }

    private List<UserCoupon> listAvailableCoupons(Long passengerId, Long companyId, BigDecimal finalAmount,
                                                  String cityCode, String productCode) {
        LocalDateTime now = LocalDateTime.now();
        return userCouponMapper.selectList(Wrappers.<UserCoupon>lambdaQuery()
                .eq(UserCoupon::getPassengerId, passengerId)
                .eq(UserCoupon::getCompanyId, companyId)
                .eq(UserCoupon::getStatus, UNUSED)
                .le(UserCoupon::getValidStartAt, now)
                .gt(UserCoupon::getValidEndAt, now)
                .le(UserCoupon::getThresholdAmount, finalAmount)
                .eq(UserCoupon::getCityCode, cityCode)
                .eq(UserCoupon::getProductCode, productCode))
                .stream()
                .filter(coupon -> calculateDiscount(coupon, finalAmount).compareTo(BigDecimal.ZERO) > 0)
                .toList();
    }

    private UserCoupon bestCoupon(CouponLockRequest request) {
        return listAvailableCoupons(request.getPassengerId(), request.getCompanyId(), request.getFinalAmount(),
                        request.getCityCode(), request.getProductCode())
                .stream()
                .map(coupon -> toVO(coupon, request.getFinalAmount()))
                .sorted(availableComparator())
                .map(vo -> userCouponMapper.selectById(vo.getCouponId()))
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(null);
    }

    private boolean canUse(UserCoupon coupon, CouponLockRequest request) {
        LocalDateTime now = LocalDateTime.now();
        return coupon.getPassengerId().equals(request.getPassengerId())
                && Objects.equals(coupon.getCompanyId(), request.getCompanyId())
                && UNUSED.equals(coupon.getStatus())
                && !coupon.getValidStartAt().isAfter(now)
                && coupon.getValidEndAt().isAfter(now)
                && coupon.getThresholdAmount().compareTo(request.getFinalAmount()) <= 0
                && Objects.equals(coupon.getCityCode(), request.getCityCode())
                && Objects.equals(coupon.getProductCode(), request.getProductCode())
                && calculateDiscount(coupon, request.getFinalAmount()).compareTo(BigDecimal.ZERO) > 0;
    }

    private BigDecimal calculateDiscount(UserCoupon coupon, BigDecimal finalAmount) {
        BigDecimal amount = defaultAmount(finalAmount);
        BigDecimal discount;
        if (TYPE_PERCENT_OFF.equals(coupon.getCouponType())) {
            BigDecimal rate = coupon.getDiscountRate() == null ? BigDecimal.ONE : coupon.getDiscountRate();
            discount = amount.multiply(BigDecimal.ONE.subtract(rate));
            if (coupon.getMaxDiscountAmount() != null && coupon.getMaxDiscountAmount().compareTo(BigDecimal.ZERO) > 0) {
                discount = discount.min(coupon.getMaxDiscountAmount());
            }
        } else if (TYPE_AMOUNT_OFF.equals(coupon.getCouponType())) {
            discount = defaultAmount(coupon.getDiscountAmount());
        } else {
            discount = defaultAmount(coupon.getDiscountAmount());
        }
        return scale(discount.max(BigDecimal.ZERO).min(amount));
    }

    private Comparator<CouponVO> availableComparator() {
        return Comparator.comparing(CouponVO::getActualDiscountAmount, Comparator.nullsLast(BigDecimal::compareTo))
                .reversed()
                .thenComparing(CouponVO::getValidEndAt, Comparator.nullsLast(LocalDateTime::compareTo))
                .thenComparing(CouponVO::getCouponId, Comparator.nullsLast(Long::compareTo));
    }

    private void record(UserCoupon coupon, String orderNo, String action, String before, String after,
                        BigDecimal discountAmount, String reason) {
        recordMapper.insert(new CouponUseRecord()
                .setUserCouponId(coupon.getId())
                .setTemplateId(coupon.getTemplateId())
                .setPassengerId(coupon.getPassengerId())
                .setOrderNo(orderNo)
                .setActionType(action)
                .setDiscountAmount(scale(discountAmount))
                .setBeforeStatus(before)
                .setAfterStatus(after)
                .setReason(reason)
                .setRuleSnapshot(coupon.getRuleSnapshot())
                .setCreatedAt(LocalDateTime.now()));
    }

    private boolean hasClaimed(Long passengerId, Long templateId, String claimIdentityType, String claimIdentityHash) {
        Long passengerClaimed = userCouponMapper.selectCount(Wrappers.<UserCoupon>lambdaQuery()
                .eq(UserCoupon::getPassengerId, passengerId)
                .eq(UserCoupon::getTemplateId, templateId));
        if (passengerClaimed != null && passengerClaimed > 0) {
            return true;
        }
        String safeIdentityType = normalizeClaimIdentityType(claimIdentityType);
        String safeIdentityHash = normalizeClaimIdentityHash(claimIdentityHash);
        if (safeIdentityType == null || safeIdentityHash == null) {
            return false;
        }
        Long identityClaimed = userCouponMapper.selectCount(Wrappers.<UserCoupon>lambdaQuery()
                .eq(UserCoupon::getTemplateId, templateId)
                .eq(UserCoupon::getClaimIdentityType, safeIdentityType)
                .eq(UserCoupon::getClaimIdentityHash, safeIdentityHash));
        return identityClaimed != null && identityClaimed > 0;
    }

    private String normalizeClaimIdentityType(String claimIdentityType) {
        if (claimIdentityType == null || claimIdentityType.isBlank()) {
            return null;
        }
        String normalized = claimIdentityType.trim().toUpperCase();
        return CLAIM_IDENTITY_PHONE.equals(normalized) ? CLAIM_IDENTITY_PHONE : normalized;
    }

    private String normalizeClaimIdentityHash(String claimIdentityHash) {
        if (claimIdentityHash == null || claimIdentityHash.isBlank()) {
            return null;
        }
        return claimIdentityHash.trim();
    }

    private CouponTemplate requireTemplate(Long templateId) {
        CouponTemplate template = templateMapper.selectById(templateId);
        if (template == null || Integer.valueOf(1).equals(template.getIsDeleted())) {
            throw new IllegalArgumentException("优惠券方案不存在");
        }
        return template;
    }

    private void validateTemplate(CouponTemplateUpsertRequest request) {
        if (request.getValidEndAt().isBefore(request.getValidStartAt())
                || request.getValidEndAt().isEqual(request.getValidStartAt())) {
            throw new IllegalArgumentException("优惠券有效期结束时间必须晚于开始时间");
        }
        if (request.getTotalCount() <= 0) {
            throw new IllegalArgumentException("优惠券发放总量必须大于0");
        }
        if (request.getPerUserLimit() != null && request.getPerUserLimit() != 1) {
            throw new IllegalArgumentException("当前阶段仅支持每人限领1张");
        }
        if (TYPE_PERCENT_OFF.equals(request.getCouponType())) {
            if (request.getDiscountRate() == null
                    || request.getDiscountRate().compareTo(BigDecimal.ZERO) <= 0
                    || request.getDiscountRate().compareTo(BigDecimal.ONE) >= 0) {
                throw new IllegalArgumentException("比例折扣必须配置0到1之间的discountRate");
            }
        } else if (TYPE_AMOUNT_OFF.equals(request.getCouponType())) {
            if (request.getDiscountAmount() == null || request.getDiscountAmount().compareTo(BigDecimal.ZERO) <= 0) {
                throw new IllegalArgumentException("固定金额减免必须配置大于0的discountAmount");
            }
        } else if (request.getRuleConfig() == null || request.getRuleConfig().isBlank()) {
            throw new IllegalArgumentException("特殊优惠券必须配置ruleConfig");
        }
    }

    private CouponVO toVO(UserCoupon coupon, BigDecimal finalAmount) {
        CouponVO vo = new CouponVO();
        vo.setCouponId(coupon.getId());
        vo.setTemplateId(coupon.getTemplateId());
        vo.setCompanyId(coupon.getCompanyId());
        vo.setCompanyNameSnapshot(coupon.getCompanyNameSnapshot());
        vo.setCouponName(coupon.getCouponName());
        vo.setCouponType(coupon.getCouponType());
        vo.setThresholdAmount(coupon.getThresholdAmount());
        vo.setDiscountAmount(coupon.getDiscountAmount());
        vo.setDiscountRate(coupon.getDiscountRate());
        vo.setMaxDiscountAmount(coupon.getMaxDiscountAmount());
        if (finalAmount != null) {
            BigDecimal actualDiscount = calculateDiscount(coupon, finalAmount);
            vo.setActualDiscountAmount(actualDiscount);
            vo.setPayableAmount(scale(finalAmount.subtract(actualDiscount).max(BigDecimal.ZERO)));
        }
        vo.setCityCode(coupon.getCityCode());
        vo.setProductCode(coupon.getProductCode());
        vo.setStatus(coupon.getStatus());
        vo.setValidStartAt(coupon.getValidStartAt());
        vo.setValidEndAt(coupon.getValidEndAt());
        return vo;
    }

    private CouponTemplateVO toTemplateVO(CouponTemplate template) {
        CouponTemplateVO vo = new CouponTemplateVO();
        vo.setId(template.getId());
        vo.setCompanyId(template.getCompanyId());
        vo.setCompanyNo(template.getCompanyNo());
        vo.setCompanyNameSnapshot(template.getCompanyNameSnapshot());
        vo.setTeamIdSnapshot(template.getTeamIdSnapshot());
        vo.setTeamNameSnapshot(template.getTeamNameSnapshot());
        vo.setName(template.getName());
        vo.setCouponType(template.getCouponType());
        vo.setThresholdAmount(template.getThresholdAmount());
        vo.setDiscountAmount(template.getDiscountAmount());
        vo.setDiscountRate(template.getDiscountRate());
        vo.setMaxDiscountAmount(template.getMaxDiscountAmount());
        vo.setCityCode(template.getCityCode());
        vo.setProductCode(template.getProductCode());
        vo.setValidDays(template.getValidDays());
        vo.setValidStartAt(template.getValidStartAt());
        vo.setValidEndAt(template.getValidEndAt());
        vo.setTotalCount(template.getTotalCount());
        vo.setReceivedCount(template.getReceivedCount());
        vo.setUsedCount(template.getUsedCount());
        vo.setPerUserLimit(template.getPerUserLimit());
        vo.setIssueType(template.getIssueType());
        vo.setSourceType(template.getSourceType());
        vo.setActivityCode(template.getActivityCode());
        vo.setRuleConfig(template.getRuleConfig());
        vo.setStatus(template.getStatus());
        vo.setCreatedBy(template.getCreatedBy());
        vo.setUpdatedBy(template.getUpdatedBy());
        vo.setPublishedAt(template.getPublishedAt());
        vo.setOfflineAt(template.getOfflineAt());
        vo.setCreatedAt(template.getCreatedAt());
        vo.setUpdatedAt(template.getUpdatedAt());
        return vo;
    }

    private BigDecimal defaultAmount(BigDecimal amount) {
        return amount == null ? BigDecimal.ZERO : amount;
    }

    private BigDecimal scale(BigDecimal amount) {
        return defaultAmount(amount).setScale(2, RoundingMode.HALF_UP);
    }

    private String blankToDefault(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value;
    }
}
