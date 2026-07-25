package com.sx.wallet.dao;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sx.wallet.model.WalletPaymentOrder;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

public interface WalletPaymentOrderMapper extends BaseMapper<WalletPaymentOrder> {
    @Select("SELECT * FROM wallet_payment_order WHERE passenger_id=#{passengerId} AND status IN ('PAYING','CONFIRMING','PROCESSING','DUPLICATE_SUCCESS') ORDER BY id ASC LIMIT 20 FOR UPDATE")
    List<WalletPaymentOrder> selectLifecycleRisksForUpdate(@Param("passengerId") long passengerId);

    @Select("SELECT * FROM wallet_payment_order WHERE passenger_id=#{passengerId} AND status IN ('PAYING','CONFIRMING','PROCESSING','DUPLICATE_SUCCESS') ORDER BY id ASC LIMIT 20")
    List<WalletPaymentOrder> selectLifecycleRisks(@Param("passengerId") long passengerId);
}
