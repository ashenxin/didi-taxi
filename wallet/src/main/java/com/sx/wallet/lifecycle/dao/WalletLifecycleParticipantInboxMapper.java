package com.sx.wallet.lifecycle.dao;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sx.wallet.lifecycle.model.WalletLifecycleParticipantInbox;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface WalletLifecycleParticipantInboxMapper
        extends BaseMapper<WalletLifecycleParticipantInbox> {
    @Select("SELECT * FROM wallet_lifecycle_participant_inbox WHERE operation_no=#{operationNo} AND step_code=#{stepCode}")
    WalletLifecycleParticipantInbox find(@Param("operationNo") String operationNo,
                                         @Param("stepCode") String stepCode);

    @Select("SELECT * FROM wallet_lifecycle_participant_inbox WHERE operation_no=#{operationNo} AND step_code=#{stepCode} FOR UPDATE")
    WalletLifecycleParticipantInbox findForUpdate(@Param("operationNo") String operationNo,
                                                  @Param("stepCode") String stepCode);
}
