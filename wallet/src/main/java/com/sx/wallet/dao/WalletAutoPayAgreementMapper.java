package com.sx.wallet.dao;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sx.wallet.model.WalletAutoPayAgreement;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

public interface WalletAutoPayAgreementMapper extends BaseMapper<WalletAutoPayAgreement> {
    @Select("SELECT * FROM wallet_auto_pay_agreement WHERE passenger_id=#{passengerId} AND is_deleted=0 AND agreement_status IN ('SIGNING','ACTIVE') ORDER BY id ASC FOR UPDATE")
    List<WalletAutoPayAgreement> selectOpenForUpdate(@Param("passengerId") long passengerId);
}
