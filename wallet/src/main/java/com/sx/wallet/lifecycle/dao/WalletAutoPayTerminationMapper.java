package com.sx.wallet.lifecycle.dao;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sx.wallet.lifecycle.model.WalletAutoPayTermination;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface WalletAutoPayTerminationMapper extends BaseMapper<WalletAutoPayTermination> {
    @Select("SELECT * FROM wallet_auto_pay_termination WHERE operation_no=#{operationNo} AND step_code=#{stepCode} ORDER BY id ASC FOR UPDATE")
    List<WalletAutoPayTermination> selectForUpdate(@Param("operationNo") String operationNo,
                                                   @Param("stepCode") String stepCode);

    @Select("SELECT * FROM wallet_auto_pay_termination WHERE operation_no=#{operationNo} AND step_code=#{stepCode} AND agreement_id=#{agreementId} FOR UPDATE")
    WalletAutoPayTermination findForUpdate(@Param("operationNo") String operationNo,
                                           @Param("stepCode") String stepCode,
                                           @Param("agreementId") long agreementId);
}
