package com.sx.order.common.util;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 订单号生成工具（MVP）。
 *
 * <p>后续可替换为雪花算法/号段服务等。</p>
 */
public class OrderNoUtil {
    private static final SecureRandom RND = new SecureRandom();
    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS");

    private OrderNoUtil() {
    }

    public static String nextOrderNo() {
        // 时间戳 + 4 位随机数，满足 MVP 唯一性诉求
        int suffix = RND.nextInt(10_000);
        return LocalDateTime.now().format(FMT) + String.format("%04d", suffix);
    }
}

