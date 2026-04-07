package com.sx.passenger.app;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sx.passenger.app.dto.AppAuthCustomerBrief;
import com.sx.passenger.app.dto.AppLoginPasswordRequest;
import com.sx.passenger.app.dto.AppSmsLoginRequest;
import com.sx.passenger.dao.CustomerEntityMapper;
import com.sx.passenger.model.Customer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.concurrent.TimeUnit;

import com.sx.passenger.common.util.ResultUtil;
import com.sx.passenger.common.vo.ResponseVo;

/**
 * 乘客 App 侧：密码登录、注册、短信验证码发送与登录（Redis 存 OTP + 频控）。
 */
@Service
public class AppCustomerAuthService {

    private static final Logger log = LoggerFactory.getLogger(AppCustomerAuthService.class);
    private static final BCryptPasswordEncoder BCRYPT = new BCryptPasswordEncoder();
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final ZoneId CN_ZONE = ZoneId.of("Asia/Shanghai");

    /**
     * Redis key 前缀约定（App 端认证）。
     * <p>命名空间说明：</p>
     * <ul>
     *   <li><b>{@code app:sms:*}</b>：短信发送频控与日计数</li>
     *   <li><b>{@code app:otp:*}</b>：短信验证码（一次性口令）</li>
     *   <li><b>{@code app:login:*}</b>：登录失败次数统计与当日禁用标记（密码+验证码合并）</li>
     * </ul>
     */
    private static final String KEY_SMS_GAP_PREFIX = "app:sms:gap:"; // 同手机号发送间隔锁（phone）
    private static final String KEY_SMS_DAILY_PREFIX = "app:sms:daily:"; // 同手机号自然日发送次数（phone:yyyy-MM-dd）
    private static final String KEY_OTP_PREFIX = "app:otp:"; // OTP 验证码（phone）
    private static final String KEY_LOGIN_FAIL_PREFIX = "app:login:fail:"; // 登录失败次数（phone:yyyy-MM-dd）
    private static final String KEY_LOGIN_BAN_PREFIX = "app:login:ban:"; // 当日禁用标记（phone:yyyy-MM-dd）

    private final CustomerEntityMapper customerMapper;
    private final StringRedisTemplate redis;
    private final AppCustomerAuthProperties smsProps;

    public AppCustomerAuthService(
            CustomerEntityMapper customerMapper,
            StringRedisTemplate redis,
            AppCustomerAuthProperties smsProps) {
        this.customerMapper = customerMapper;
        this.redis = redis;
        this.smsProps = smsProps;
    }

    public ResponseVo<AppAuthCustomerBrief> loginPassword(AppLoginPasswordRequest req) {
        if (isLoginBannedToday(req.getPhone())) {
            return bannedToday();
        }
        Customer c = findActiveByPhone(req.getPhone());
        if (c == null) {
            recordLoginFail(req.getPhone());
            return unauthorized();
        }
        if (c.getStatus() != null && c.getStatus() != 0) {
            return ResultUtil.forbidden("账号已冻结，请联系客服");
        }
        if (c.getPasswordHash() == null || c.getPasswordHash().isBlank()) {
            recordLoginFail(req.getPhone());
            return ResultUtil.unauthorized("请使用验证码登录");
        }
        if (!BCRYPT.matches(req.getPassword(), c.getPasswordHash())) {
            recordLoginFail(req.getPhone());
            return unauthorized();
        }
        return ResultUtil.success(toBrief(c));
    }

    public ResponseVo<Void> sendSmsCode(String phone) {
        String gapKey = KEY_SMS_GAP_PREFIX + phone;
        Boolean firstGap = redis.opsForValue().setIfAbsent(gapKey, "1", Duration.ofSeconds(smsProps.getMinIntervalSeconds()));
        if (Boolean.FALSE.equals(firstGap)) {
            return ResultUtil.error(429, "发送过于频繁，请稍后再试");
        }

        String day = LocalDate.now(CN_ZONE).toString();
        String dailyKey = KEY_SMS_DAILY_PREFIX + phone + ":" + day;
        Long n = redis.opsForValue().increment(dailyKey);
        if (n != null && n == 1) {
            redis.expire(dailyKey, 2, TimeUnit.DAYS);
        }
        if (n != null && n > smsProps.getDailyLimitPerPhone()) {
            redis.delete(gapKey);
            return ResultUtil.error(429, "今日验证码发送次数已达上限，请明天再试");
        }

        String code = String.format("%06d", RANDOM.nextInt(1_000_000));
        String otpKey = KEY_OTP_PREFIX + phone;
        redis.opsForValue().set(otpKey, code, smsProps.getCodeTtlSeconds(), TimeUnit.SECONDS);

        if (smsProps.isMockSendEnabled()) {
            log.info("[passenger.app.customer-auth] mock SMS otp phone={} code={} (mockSendEnabled=true)", phone, code);
        } else {
            // 生产：在此调用短信网关；失败时应 delete gapKey、回滚 daily 计数（简化可接受少量误差）
            log.warn("[passenger.app.customer-auth] mockSendEnabled=false but no SMS provider wired; phone={}", phone);
        }
        return ResultUtil.success(null);
    }

    @Transactional
    public ResponseVo<AppAuthCustomerBrief> loginSms(AppSmsLoginRequest req) {
        if (isLoginBannedToday(req.getPhone())) {
            return bannedToday();
        }
        String otpKey = KEY_OTP_PREFIX + req.getPhone();
        String expected = redis.opsForValue().get(otpKey);
        if (expected == null || !expected.equals(req.getCode().trim())) {
            recordLoginFail(req.getPhone());
            return unauthorizedSms();
        }
        redis.delete(otpKey);

        Customer c = findActiveByPhone(req.getPhone());
        if (c == null) {
            c = new Customer();
            c.setPhone(req.getPhone());
            c.setPasswordHash(null);
            c.setNickname(null);
            c.setStatus(0);
            c.setIsDeleted(0);
            try {
                customerMapper.insert(c);
            } catch (DuplicateKeyException e) {
                c = findActiveByPhone(req.getPhone());
                if (c == null) {
                    return ResultUtil.error(500, "注册失败，请重试");
                }
            }
        }
        if (c.getStatus() != null && c.getStatus() != 0) {
            return ResultUtil.forbidden("账号已冻结，请联系客服");
        }
        return ResultUtil.success(toBrief(c));
    }

    private Customer findActiveByPhone(String phone) {
        return customerMapper.selectOne(
                Wrappers.<Customer>lambdaQuery()
                        .eq(Customer::getPhone, phone)
                        .eq(Customer::getIsDeleted, 0)
                        .last("LIMIT 1"));
    }

    private static AppAuthCustomerBrief toBrief(Customer c) {
        AppAuthCustomerBrief b = new AppAuthCustomerBrief();
        b.setId(c.getId());
        b.setPhone(c.getPhone());
        b.setNickname(c.getNickname());
        return b;
    }

    private static ResponseVo<AppAuthCustomerBrief> unauthorized() {
        return ResultUtil.unauthorized("手机号或密码错误");
    }

    private static ResponseVo<AppAuthCustomerBrief> unauthorizedSms() {
        return ResultUtil.unauthorized("验证码错误或已过期");
    }

    private boolean isLoginBannedToday(String phone) {
        String day = LocalDate.now(CN_ZONE).toString();
        String banKey = KEY_LOGIN_BAN_PREFIX + phone + ":" + day;
        String v = redis.opsForValue().get(banKey);
        return v != null && !v.isBlank();
    }

    private void recordLoginFail(String phone) {
        String day = LocalDate.now(CN_ZONE).toString();
        String failKey = KEY_LOGIN_FAIL_PREFIX + phone + ":" + day;
        Long n = redis.opsForValue().increment(failKey);
        if (n != null && n == 1) {
            redis.expire(failKey, 2, TimeUnit.DAYS);
        }
        if (n != null && n >= smsProps.getDailyLoginFailLimitPerPhone()) {
            String banKey = KEY_LOGIN_BAN_PREFIX + phone + ":" + day;
            redis.opsForValue().set(banKey, "1", 2, TimeUnit.DAYS);
        }
    }

    private static ResponseVo<AppAuthCustomerBrief> bannedToday() {
        return ResultUtil.error(429, "登录失败次数过多，请明天再试");
    }
}
