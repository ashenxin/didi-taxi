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
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Collections;
import java.util.concurrent.TimeUnit;

/**
 * 司机 App 侧：短信验证码发送、注册、登录（Redis 存 OTP + 频控 + 失败次数）。
 * 注意：本服务只处理“能否登录/注册”。“能否接单”由 audit_status + can_accept_order 等在业务接口处拦截。
 * Redis key 前缀概览：
 * <ul>
 *   <li>{@code driver:sms:gap:} / {@code driver:sms:daily:}：短信频控（见各常量说明）</li>
 *   <li>{@code driver:otp:}：短信验证码</li>
 *   <li>{@code driver:login:}：登录失败与当日封禁</li>
 * </ul>
 */
@Service
public class AppDriverAuthService {

    private static final Logger log = LoggerFactory.getLogger(AppDriverAuthService.class);
    private static final BCryptPasswordEncoder BCRYPT = new BCryptPasswordEncoder();
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final ZoneId CN_ZONE = ZoneId.of("Asia/Shanghai");

    /**
     * 短信「最短发送间隔」锁的 Redis 键前缀；完整键为本前缀与手机号拼接。
     * 含义：同一手机号两次调用发码接口之间，至少间隔 {@link DriverAuthProperties#getMinIntervalSeconds()} 秒（默认 60）。
     * 实现上用 {@code SET key "1" NX EX minIntervalSeconds}：仅当键不存在时才设置成功并开启倒计时；键仍存在表示处于冷却期，返回 429「发送过于频繁」。
     * 与 {@code driver:sms:daily:*} 的配合顺序：先过间隔锁，再过自然日条数上限；若因日上限拒绝，会删除本间隔键，避免用户同时被「过于频繁」与「今日已达上限」两种提示叠加困扰。
     */
    private static final String KEY_SMS_GAP_PREFIX = "driver:sms:gap:";
    private static final String KEY_SMS_DAILY_PREFIX = "driver:sms:daily:"; // 同手机号自然日发送次数（phone:yyyy-MM-dd）
    private static final String KEY_OTP_PREFIX = "driver:otp:"; // OTP 验证码（phone）
    private static final String KEY_LOGIN_FAIL_PREFIX = "driver:login:fail:"; // 登录失败次数（phone:yyyy-MM-dd）
    private static final String KEY_LOGIN_BAN_PREFIX = "driver:login:ban:"; // 当日禁用标记（phone:yyyy-MM-dd）

    /**
     * 原子校验并消费 OTP：避免并发下 “先 get 后 delete” 导致的重复消费。
     *
     * 返回值约定：1=校验通过并已删除；0=验证码不匹配；-1=验证码不存在/已过期。
     */
    private static final DefaultRedisScript<Long> OTP_VERIFY_AND_DELETE_SCRIPT = new DefaultRedisScript<>(
            """
            local v = redis.call('GET', KEYS[1])
            if (not v) then
              return -1
            end
            if (v == ARGV[1]) then
              redis.call('DEL', KEYS[1])
              return 1
            end
            return 0
            """,
            Long.class
    );

    private final DriverEntityMapper driverMapper;
    private final StringRedisTemplate redis;
    private final DriverAuthProperties props;

    /**
     * 注入持久化、Redis 与认证相关配置。
     */
    public AppDriverAuthService(DriverEntityMapper driverMapper, StringRedisTemplate redis, DriverAuthProperties props) {
        this.driverMapper = driverMapper;
        this.redis = redis;
        this.props = props;
    }

    /**
     * 向指定手机号发送短信验证码：校验间隔与日上限，生成 OTP 写入 Redis；mock 模式下仅打日志。
     *
     * @param phone 手机号
     * @return 成功无正文，失败带业务错误码与提示
     */
    public ResponseVo<Void> sendSmsCode(String phone) {
        String p = phone == null ? null : phone.trim();
        if (p == null || p.isBlank()) {
            return ResultUtil.error(400, "手机号不能为空");
        }

        // 最短发送间隔：SET NX + TTL，见 KEY_SMS_GAP_PREFIX JavaDoc
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

    /**
     * 短信验证码注册：校验当日是否封禁、OTP，未存在司机则创建无密码账号。
     *
     * @param req 手机号与验证码
     * @return 新建司机简要信息，或冲突/验证失败等错误
     */
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
        clearLoginFailToday(phone);
        log.info("driver registerSms success driverId={} phone={}", d.getId(), maskPhone(phone));
        return ResultUtil.success(toBrief(d));
    }

    /**
     * 密码注册：在短信 OTP 通过后创建司机并写入 BCrypt 密码哈希。
     *
     * @param req 手机号、验证码与密码
     * @return 司机简要信息（含最新库记录），或冲突/验证失败等错误
     */
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
        clearLoginFailToday(phone);
        Driver out = refreshed == null ? d : refreshed;
        log.info("driver registerPassword success driverId={} phone={}", out.getId(), maskPhone(phone));
        return ResultUtil.success(toBrief(out));
    }

    /**
     * 短信验证码登录：司机须已存在，OTP 校验通过后返回简要信息；失败计入当日失败次数。
     *
     * @param req 手机号与验证码
     * @return 司机简要信息或业务错误
     */
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
        clearLoginFailToday(phone);
        log.info("driver loginSms success driverId={} phone={}", d.getId(), maskPhone(phone));
        return ResultUtil.success(toBrief(d));
    }

    /**
     * 密码登录：校验 BCrypt；未设置密码或账号不存在时统一错误提示并累计失败次数。
     *
     * @param req 手机号与明文密码
     * @return 司机简要信息或认证/封禁相关错误
     */
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
        clearLoginFailToday(phone);
        log.info("driver loginPassword success driverId={} phone={}", d.getId(), maskPhone(phone));
        return ResultUtil.success(toBrief(d));
    }

    private static String maskPhone(String phone) {
        if (phone == null || phone.length() < 4) {
            return "****";
        }
        return "****" + phone.substring(phone.length() - 4);
    }

    /**
     * 按手机号查询未删除的司机（最多一条）。
     */
    private Driver findActiveByPhone(String phone) {
        return driverMapper.selectOne(
                Wrappers.<Driver>lambdaQuery()
                        .eq(Driver::getPhone, phone)
                        .eq(Driver::getIsDeleted, 0)
                        .orderByDesc(Driver::getId)
                        .last("LIMIT 1"));
    }

    /**
     * 将 {@link Driver} 转为对外返回的简要 DTO（id、手机号、审核状态）。
     */
    private static AppAuthDriverBrief toBrief(Driver d) {
        AppAuthDriverBrief b = new AppAuthDriverBrief();
        b.setId(d.getId());
        b.setPhone(d.getPhone());
        b.setAuditStatus(d.getAuditStatus());
        return b;
    }

    /**
     * 一次性校验 Redis 中的 OTP：一致则删除 key，避免重复使用。
     *
     * @param phone 手机号
     * @param code  用户提交的验证码
     * @return 成功无正文，失败为 401 及提示文案
     */
    private ResponseVo<Void> verifyOtpOnce(String phone, String code) {
        String otpKey = KEY_OTP_PREFIX + phone;
        String c = code == null ? null : code.trim();
        if (c == null || c.isBlank()) {
            return ResultUtil.error(401, "验证码错误或已过期");
        }
        Long r = redis.execute(OTP_VERIFY_AND_DELETE_SCRIPT, Collections.singletonList(otpKey), c);
        if (r == null || r != 1L) {
            return ResultUtil.error(401, "验证码错误或已过期");
        }
        return ResultUtil.success(null);
    }

    /**
     * 判断该手机号在上海时区当前自然日是否因失败次数过多被标记为禁止登录。
     */
    private boolean isLoginBannedToday(String phone) {
        String day = LocalDate.now(CN_ZONE).toString();
        String banKey = KEY_LOGIN_BAN_PREFIX + phone + ":" + day;
        String v = redis.opsForValue().get(banKey);
        return v != null && !v.isBlank();
    }

    /**
     * 累加当日登录/验证失败次数，达到阈值后写入当日 ban 标记（有效期 2 天仅作 key 存活，业务按自然日 key 区分）。
     */
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

    private void clearLoginFailToday(String phone) {
        String day = LocalDate.now(CN_ZONE).toString();
        redis.delete(KEY_LOGIN_FAIL_PREFIX + phone + ":" + day);
        redis.delete(KEY_LOGIN_BAN_PREFIX + phone + ":" + day);
    }

    /**
     * 当日已被风控禁止登录时的统一错误响应。
     */
    private static ResponseVo<AppAuthDriverBrief> bannedToday() {
        return ResultUtil.error(429, "登录失败次数过多，请明天再试");
    }
}
