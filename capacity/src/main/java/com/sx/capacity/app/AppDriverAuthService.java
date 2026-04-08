package com.sx.capacity.app;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sx.capacity.app.dto.AppAuthDriverBrief;
import com.sx.capacity.app.dto.AppPasswordLoginRequest;
import com.sx.capacity.app.dto.AppPasswordRegisterRequest;
import com.sx.capacity.app.dto.AppSmsLoginRequest;
import com.sx.capacity.app.dto.AppSmsRegisterRequest;
import com.sx.capacity.common.util.ResultUtil;
import com.sx.capacity.common.vo.ResponseVo;
import com.sx.capacity.dao.DriverEntityMapper;
import com.sx.capacity.model.Driver;
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

/**
 * 司机 App 侧：短信验证码发送、注册、登录（Redis 存 OTP + 频控 + 失败次数）。
 * <p>注意：本服务只处理“能否登录/注册”。“能否接单”由 audit_status + can_accept_order 等在业务接口处拦截。</p>
 */
@Service
public class AppDriverAuthService {

    private static final Logger log = LoggerFactory.getLogger(AppDriverAuthService.class);
    private static final BCryptPasswordEncoder BCRYPT = new BCryptPasswordEncoder();
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final ZoneId CN_ZONE = ZoneId.of("Asia/Shanghai");

    /**
     * Redis key 前缀约定（司机端认证）。
     * <ul>
     *   <li><b>{@code driver:sms:*}</b>：短信发送频控与日计数</li>
     *   <li><b>{@code driver:otp:*}</b>：短信验证码（一次性口令）</li>
     *   <li><b>{@code driver:login:*}</b>：登录失败次数统计与当日禁用标记（密码+验证码合并）</li>
     * </ul>
     */
    private static final String KEY_SMS_GAP_PREFIX = "driver:sms:gap:"; // 同手机号发送间隔锁（phone）
    private static final String KEY_SMS_DAILY_PREFIX = "driver:sms:daily:"; // 同手机号自然日发送次数（phone:yyyy-MM-dd）
    private static final String KEY_OTP_PREFIX = "driver:otp:"; // OTP 验证码（phone）
    private static final String KEY_LOGIN_FAIL_PREFIX = "driver:login:fail:"; // 登录失败次数（phone:yyyy-MM-dd）
    private static final String KEY_LOGIN_BAN_PREFIX = "driver:login:ban:"; // 当日禁用标记（phone:yyyy-MM-dd）

    private final DriverEntityMapper driverMapper;
    private final StringRedisTemplate redis;
    private final DriverAuthProperties props;

    public AppDriverAuthService(DriverEntityMapper driverMapper, StringRedisTemplate redis, DriverAuthProperties props) {
        this.driverMapper = driverMapper;
        this.redis = redis;
        this.props = props;
    }

    public ResponseVo<Void> sendSmsCode(String phone) {
        String p = phone == null ? null : phone.trim();
        if (p == null || p.isBlank()) {
            return ResultUtil.error(400, "手机号不能为空");
        }

        String gapKey = KEY_SMS_GAP_PREFIX + p;
        Boolean firstGap = redis.opsForValue().setIfAbsent(gapKey, "1", Duration.ofSeconds(props.getMinIntervalSeconds()));
        if (Boolean.FALSE.equals(firstGap)) {
            return ResultUtil.error(429, "发送过于频繁，请稍后再试");
        }

        String day = LocalDate.now(CN_ZONE).toString();
        String dailyKey = KEY_SMS_DAILY_PREFIX + p + ":" + day;
        Long n = redis.opsForValue().increment(dailyKey);
        if (n != null && n == 1) {
            redis.expire(dailyKey, 2, TimeUnit.DAYS);
        }
        if (n != null && n > props.getDailySmsLimitPerPhone()) {
            redis.delete(gapKey);
            return ResultUtil.error(429, "今日验证码发送次数已达上限，请明天再试");
        }

        String code = String.format("%06d", RANDOM.nextInt(1_000_000));
        String otpKey = KEY_OTP_PREFIX + p;
        redis.opsForValue().set(otpKey, code, props.getCodeTtlSeconds(), TimeUnit.SECONDS);

        if (props.isMockSendEnabled()) {
            log.info("[capacity.app.driver-auth] mock SMS otp phone={} code={} (mockSendEnabled=true)", p, code);
        } else {
            log.warn("[capacity.app.driver-auth] mockSendEnabled=false but no SMS provider wired; phone={}", p);
        }
        return ResultUtil.success(null);
    }

    @Transactional
    public ResponseVo<AppAuthDriverBrief> registerSms(AppSmsRegisterRequest req) {
        String phone = req.getPhone().trim();
        if (isLoginBannedToday(phone)) {
            return bannedToday();
        }
        ResponseVo<Void> otpOk = verifyOtpOnce(phone, req.getCode());
        if (otpOk.getCode() != 200) {
            recordLoginFail(phone);
            return ResultUtil.error(otpOk.getCode(), otpOk.getMsg());
        }
        Driver d = findActiveByPhone(phone);
        if (d != null) {
            return ResultUtil.error(409, "该手机号已注册，请直接登录");
        }

        d = new Driver()
                .setPhone(phone)
                .setPasswordHash(null)
                .setDriverSource(0)
                .setAuditStatus(0)
                .setCanAcceptOrder(0)
                .setIsDeleted(0);
        try {
            driverMapper.insert(d);
        } catch (DuplicateKeyException e) {
            d = findActiveByPhone(phone);
            if (d == null) {
                return ResultUtil.error(500, "注册失败，请重试");
            }
            return ResultUtil.error(409, "该手机号已注册，请直接登录");
        }
        return ResultUtil.success(toBrief(d));
    }

    @Transactional
    public ResponseVo<AppAuthDriverBrief> registerPassword(AppPasswordRegisterRequest req) {
        String phone = req.getPhone().trim();
        if (isLoginBannedToday(phone)) {
            return bannedToday();
        }
        ResponseVo<Void> otpOk = verifyOtpOnce(phone, req.getCode());
        if (otpOk.getCode() != 200) {
            recordLoginFail(phone);
            return ResultUtil.error(otpOk.getCode(), otpOk.getMsg());
        }

        Driver d = findActiveByPhone(phone);
        if (d != null) {
            return ResultUtil.error(409, "该手机号已注册，请直接登录");
        }
        d = new Driver()
                .setPhone(phone)
                .setDriverSource(0)
                .setAuditStatus(0)
                .setCanAcceptOrder(0)
                .setIsDeleted(0);
        try {
            driverMapper.insert(d);
        } catch (DuplicateKeyException e) {
            return ResultUtil.error(409, "该手机号已注册，请直接登录");
        }
        String hash = BCRYPT.encode(req.getPassword());
        driverMapper.update(null, Wrappers.<Driver>lambdaUpdate()
                .set(Driver::getPasswordHash, hash)
                .eq(Driver::getId, d.getId())
                .eq(Driver::getIsDeleted, 0));
        Driver refreshed = driverMapper.selectById(d.getId());
        return ResultUtil.success(toBrief(refreshed == null ? d : refreshed));
    }

    public ResponseVo<AppAuthDriverBrief> loginSms(AppSmsLoginRequest req) {
        String phone = req.getPhone().trim();
        if (isLoginBannedToday(phone)) {
            return bannedToday();
        }
        Driver d = findActiveByPhone(phone);
        if (d == null) {
            recordLoginFail(phone);
            return ResultUtil.error(404, "该手机号未注册，请先注册");
        }
        ResponseVo<Void> otpOk = verifyOtpOnce(phone, req.getCode());
        if (otpOk.getCode() != 200) {
            recordLoginFail(phone);
            return ResultUtil.error(otpOk.getCode(), otpOk.getMsg());
        }
        return ResultUtil.success(toBrief(d));
    }

    public ResponseVo<AppAuthDriverBrief> loginPassword(AppPasswordLoginRequest req) {
        String phone = req.getPhone().trim();
        if (isLoginBannedToday(phone)) {
            return bannedToday();
        }
        Driver d = findActiveByPhone(phone);
        if (d == null) {
            recordLoginFail(phone);
            return ResultUtil.error(401, "手机号或密码错误");
        }
        if (d.getPasswordHash() == null || d.getPasswordHash().isBlank()) {
            recordLoginFail(phone);
            return ResultUtil.error(401, "未设置密码，请使用验证码登录或先设置密码");
        }
        if (!BCRYPT.matches(req.getPassword(), d.getPasswordHash())) {
            recordLoginFail(phone);
            return ResultUtil.error(401, "手机号或密码错误");
        }
        return ResultUtil.success(toBrief(d));
    }

    private Driver findActiveByPhone(String phone) {
        return driverMapper.selectOne(
                Wrappers.<Driver>lambdaQuery()
                        .eq(Driver::getPhone, phone)
                        .eq(Driver::getIsDeleted, 0)
                        .last("LIMIT 1"));
    }

    private static AppAuthDriverBrief toBrief(Driver d) {
        AppAuthDriverBrief b = new AppAuthDriverBrief();
        b.setId(d.getId());
        b.setPhone(d.getPhone());
        b.setAuditStatus(d.getAuditStatus());
        return b;
    }

    private ResponseVo<Void> verifyOtpOnce(String phone, String code) {
        String otpKey = KEY_OTP_PREFIX + phone;
        String expected = redis.opsForValue().get(otpKey);
        if (expected == null || code == null || !expected.equals(code.trim())) {
            return ResultUtil.error(401, "验证码错误或已过期");
        }
        redis.delete(otpKey);
        return ResultUtil.success(null);
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
        if (n != null && n >= props.getDailyLoginFailLimitPerPhone()) {
            String banKey = KEY_LOGIN_BAN_PREFIX + phone + ":" + day;
            redis.opsForValue().set(banKey, "1", 2, TimeUnit.DAYS);
        }
    }

    private static ResponseVo<AppAuthDriverBrief> bannedToday() {
        return ResultUtil.error(429, "登录失败次数过多，请明天再试");
    }
}

