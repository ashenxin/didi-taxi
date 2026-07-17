-- 完单结算锁券幂等：同一订单最多关联一张锁定或已核销优惠券。
-- 执行前如已有重复 locked_order_no，须先由运营核对并清理异常数据。
ALTER TABLE `user_coupon`
    ADD COLUMN `locked_final_amount` DECIMAL(10,2) NULL COMMENT '锁券时最终车费快照' AFTER `locked_order_no`,
    ADD COLUMN `locked_discount_amount` DECIMAL(10,2) NULL COMMENT '锁券时实际优惠金额快照' AFTER `locked_final_amount`,
    DROP INDEX `idx_user_coupon_order`,
    ADD UNIQUE KEY `uk_user_coupon_locked_order` (`locked_order_no`);
