package com.sx.wallet.lifecycle.dao;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sx.wallet.lifecycle.model.WalletLifecycleEventInbox;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface WalletLifecycleEventInboxMapper extends BaseMapper<WalletLifecycleEventInbox> {
    @Select("SELECT * FROM wallet_account_lifecycle_event_inbox WHERE source_event_id=#{id} FOR UPDATE")
    WalletLifecycleEventInbox selectForUpdate(@Param("id") String sourceEventId);
}
