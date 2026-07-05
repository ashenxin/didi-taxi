package com.sx.passenger.app;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sx.passenger.app.dto.AppAccountCancelConfirmRequest;
import com.sx.passenger.app.dto.AppAccountCancelResult;
import com.sx.passenger.app.dto.AppAccountCancelSmsSendResult;
import com.sx.passenger.app.dto.AppPhoneChangeConfirmRequest;
import com.sx.passenger.app.dto.AppPhoneChangeResult;
import com.sx.passenger.app.dto.AppPhoneChangeSmsSendRequest;
import com.sx.passenger.app.dto.AppSettingsProfileResponse;
import com.sx.passenger.common.util.ResultUtil;
import com.sx.passenger.common.vo.ResponseVo;
import com.sx.passenger.dao.CustomerEntityMapper;
import com.sx.passenger.model.Customer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.concurrent.TimeUnit;

@Service
public class AppCustomerSettingsService {
    private static final Logger log = LoggerFactory.getLogger(AppCustomerSettingsService.class);
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final ZoneId CN_ZONE = ZoneId.of("Asia/Shanghai");

    private static final String KEY_PHONE_CHANGE_OTP_PREFIX = "app:settings:phone-change:new:otp:"; // 新手机号验证码（customerId:newPhone）
    private static final String KEY_PHONE_CHANGE_GAP_PREFIX = "app:settings:phone-change:sms:gap:"; // 更换手机号发送间隔锁
    private static final String KEY_PHONE_CHANGE_DAILY_PREFIX = "app:settings:phone-change:sms:daily:"; // 更换手机号自然日发送次数
    private static final String KEY_ACCOUNT_CANCEL_OTP_PREFIX = "app:settings:account-cancel:otp:"; // 注销验证码（customerId）
    private static final String KEY_ACCOUNT_CANCEL_GAP_PREFIX = "app:settings:account-cancel:sms:gap:"; // 注销验证码发送间隔锁
    private static final String KEY_ACCOUNT_CANCEL_DAILY_PREFIX = "app:settings:account-cancel:sms:daily:"; // 注销验证码自然日发送次数

    private final CustomerEntityMapper customerMapper;
    private final StringRedisTemplate redis;
    private final AppCustomerAuthProperties smsProps;

    public AppCustomerSettingsService(
            CustomerEntityMapper customerMapper,
            StringRedisTemplate redis,
            AppCustomerAuthProperties smsProps) {
        this.customerMapper = customerMapper;
        this.redis = redis;
        this.smsProps = smsProps;
    }

    public ResponseVo<AppSettingsProfileResponse> profile(Long customerId) {
        Customer c = findActiveById(customerId);
        if (c == null) {
            return ResultUtil.error(404, "账号不存在或已注销");
        }
        AppSettingsProfileResponse out = new AppSettingsProfileResponse();
        out.setCustomerId(c.getId());
        out.setMaskedPhone(maskPhone(c.getPhone()));
        out.setStatus(c.getStatus());
        out.setDeleted(c.getIsDeleted() != null && c.getIsDeleted() != 0);
        return ResultUtil.success(out);
    }

    /**
     * 更换手机号只校验新手机号验证码；当前账号身份由 BFF 从 JWT 中解析 customerId 后传入。
     */
    public ResponseVo<com.sx.passenger.app.dto.AppSmsSendResult> sendPhoneChangeSms(AppPhoneChangeSmsSendRequest req) {
        Customer current = findActiveById(req.getCustomerId());
        if (current == null) {
            return ResultUtil.error(404, "账号不存在或已注销");
        }
        String newPhone = normalizePhone(req.getNewPhone());
        if (newPhone.equals(current.getPhone())) {
            return ResultUtil.requestError("新手机号不能与当前手机号相同");
        }
        if (findActiveByPhone(newPhone) != null) {
            return ResultUtil.error(409, "该手机号已被使用");
        }
        String code = createAndSaveCode(
                KEY_PHONE_CHANGE_GAP_PREFIX + req.getCustomerId() + ":" + newPhone,
                KEY_PHONE_CHANGE_DAILY_PREFIX + req.getCustomerId() + ":" + newPhone + ":" + LocalDate.now(CN_ZONE),
                KEY_PHONE_CHANGE_OTP_PREFIX + req.getCustomerId() + ":" + newPhone,
                newPhone);
        if (code == null) {
            return ResultUtil.error(429, "发送过于频繁，请稍后再试");
        }
        log.info("[乘客设置] 更换手机号验证码 customerId={} newPhone={} code={}",
                req.getCustomerId(), maskPhone(newPhone), smsProps.isMockSendEnabled() ? code : "******");
        return ResultUtil.success(new com.sx.passenger.app.dto.AppSmsSendResult(smsProps.isMockSendEnabled() ? code : null));
    }

    @Transactional
    public ResponseVo<AppPhoneChangeResult> confirmPhoneChange(AppPhoneChangeConfirmRequest req) {
        Customer current = findActiveById(req.getCustomerId());
        if (current == null) {
            return ResultUtil.error(404, "账号不存在或已注销");
        }
        String newPhone = normalizePhone(req.getNewPhone());
        if (newPhone.equals(current.getPhone())) {
            return ResultUtil.requestError("新手机号不能与当前手机号相同");
        }
        String otpKey = KEY_PHONE_CHANGE_OTP_PREFIX + req.getCustomerId() + ":" + newPhone;
        if (!verifyCode(otpKey, req.getCode())) {
            return ResultUtil.unauthorized("验证码错误或已过期");
        }
        // 防止验证码发送后，新手机号被其他未注销账号抢先注册或绑定。
        Customer occupied = findActiveByPhone(newPhone);
        if (occupied != null && !occupied.getId().equals(req.getCustomerId())) {
            return ResultUtil.error(409, "该手机号已被使用");
        }

        Customer update = new Customer();
        update.setPhone(newPhone);
        try {
            // 只更新同一个 customer.id，历史订单仍通过 passenger_id 关联到原用户。
            int updated = customerMapper.update(
                    update,
                    Wrappers.<Customer>lambdaUpdate()
                            .eq(Customer::getId, req.getCustomerId())
                            .eq(Customer::getIsDeleted, 0));
            if (updated <= 0) {
                return ResultUtil.error(404, "账号不存在或已注销");
            }
        } catch (DuplicateKeyException e) {
            return ResultUtil.error(409, "该手机号已被使用");
        }
        redis.delete(otpKey);

        AppPhoneChangeResult out = new AppPhoneChangeResult();
        out.setChanged(true);
        out.setRequireLogin(true);
        out.setMaskedNewPhone(maskPhone(newPhone));
        log.info("乘客更换手机号成功 customerId={} oldPhone={} newPhone={}",
                req.getCustomerId(), maskPhone(current.getPhone()), maskPhone(newPhone));
        return ResultUtil.success(out);
    }

    /**
     * 注销验证码发往当前绑定手机号，避免用户误填其他手机号后绕开账号归属确认。
     */
    public ResponseVo<AppAccountCancelSmsSendResult> sendAccountCancelSms(Long customerId) {
        Customer current = findActiveById(customerId);
        if (current == null) {
            return ResultUtil.error(404, "账号不存在或已注销");
        }
        String phone = current.getPhone();
        String code = createAndSaveCode(
                KEY_ACCOUNT_CANCEL_GAP_PREFIX + customerId,
                KEY_ACCOUNT_CANCEL_DAILY_PREFIX + customerId + ":" + LocalDate.now(CN_ZONE),
                KEY_ACCOUNT_CANCEL_OTP_PREFIX + customerId,
                phone);
        if (code == null) {
            return ResultUtil.error(429, "发送过于频繁，请稍后再试");
        }
        AppAccountCancelSmsSendResult out = new AppAccountCancelSmsSendResult();
        out.setMockCode(smsProps.isMockSendEnabled() ? code : null);
        out.setMaskedPhone(maskPhone(phone));
        log.info("[乘客设置] 注销账号验证码 customerId={} phone={} code={}",
                customerId, maskPhone(phone), smsProps.isMockSendEnabled() ? code : "******");
        return ResultUtil.success(out);
    }

    @Transactional
    public ResponseVo<AppAccountCancelResult> confirmAccountCancel(AppAccountCancelConfirmRequest req) {
        Customer current = findActiveById(req.getCustomerId());
        if (current == null) {
            return ResultUtil.error(404, "账号不存在或已注销");
        }
        String otpKey = KEY_ACCOUNT_CANCEL_OTP_PREFIX + req.getCustomerId();
        if (!verifyCode(otpKey, req.getCode())) {
            return ResultUtil.unauthorized("验证码错误或已过期");
        }

        Customer update = new Customer();
        update.setIsDeleted(1);
        // 逻辑删除后 phone_active 生成列应变为 NULL，旧手机号允许重新注册成新的 customer.id。
        int updated = customerMapper.update(
                update,
                Wrappers.<Customer>lambdaUpdate()
                        .eq(Customer::getId, req.getCustomerId())
                        .eq(Customer::getIsDeleted, 0));
        if (updated <= 0) {
            return ResultUtil.error(404, "账号不存在或已注销");
        }
        redis.delete(otpKey);

        AppAccountCancelResult out = new AppAccountCancelResult();
        out.setCancelled(true);
        out.setRequireLogin(true);
        log.info("乘客账号已逻辑注销 customerId={} phone={}", req.getCustomerId(), maskPhone(current.getPhone()));
        return ResultUtil.success(out);
    }

    /**
     * 设置功能使用独立 Redis key，避免与登录验证码复用导致后续业务边界不清。
     */
    private String createAndSaveCode(String gapKey, String dailyKey, String otpKey, String phoneForLog) {
        Boolean firstGap = redis.opsForValue().setIfAbsent(gapKey, "1", Duration.ofSeconds(smsProps.getMinIntervalSeconds()));
        if (Boolean.FALSE.equals(firstGap)) {
            return null;
        }
        Long n = redis.opsForValue().increment(dailyKey);
        if (n != null && n == 1) {
            redis.expire(dailyKey, 2, TimeUnit.DAYS);
        }
        if (n != null && n > smsProps.getDailyLimitPerPhone()) {
            redis.delete(gapKey);
            log.info("设置验证码发送达到日上限 phone={}", maskPhone(phoneForLog));
            return null;
        }
        String code = String.format("%06d", RANDOM.nextInt(1_000_000));
        redis.opsForValue().set(otpKey, code, smsProps.getCodeTtlSeconds(), TimeUnit.SECONDS);
        return code;
    }

    private boolean verifyCode(String key, String code) {
        if (code == null || code.isBlank()) {
            return false;
        }
        String expected = redis.opsForValue().get(key);
        return expected != null && expected.equals(code.trim());
    }

    private Customer findActiveById(Long id) {
        if (id == null || id <= 0) {
            return null;
        }
        return customerMapper.selectOne(
                Wrappers.<Customer>lambdaQuery()
                        .eq(Customer::getId, id)
                        .eq(Customer::getIsDeleted, 0)
                        .last("LIMIT 1"));
    }

    private Customer findActiveByPhone(String phone) {
        return customerMapper.selectOne(
                Wrappers.<Customer>lambdaQuery()
                        .eq(Customer::getPhone, phone)
                        .eq(Customer::getIsDeleted, 0)
                        .last("LIMIT 1"));
    }

    private static String normalizePhone(String phone) {
        return phone == null ? "" : phone.trim();
    }

    private static String maskPhone(String phone) {
        if (phone == null || phone.length() < 7) {
            return "****";
        }
        return phone.substring(0, 3) + "****" + phone.substring(phone.length() - 4);
    }
}
