package com.sx.order.job;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.sx.order.dao.TripOrderSettlementMapper;
import com.sx.order.model.TripOrderSettlement;
import com.sx.order.service.TripOrderSettlementService;
import com.xxl.job.core.handler.annotation.XxlJob;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class SettlementRecoveryJob {

    private final TripOrderSettlementMapper settlementMapper;
    private final TripOrderSettlementService settlementService;
    private final int batchSize;

    public SettlementRecoveryJob(TripOrderSettlementMapper settlementMapper,
                                 TripOrderSettlementService settlementService,
                                 @Value("${order.settlement.recovery-batch-size:50}") int batchSize) {
        this.settlementMapper = settlementMapper;
        this.settlementService = settlementService;
        this.batchSize = Math.max(1, batchSize);
    }

    @XxlJob("orderSettlementRecovery")
    public void recover() {
        List<TripOrderSettlement> candidates = settlementMapper.selectList(
                new QueryWrapper<TripOrderSettlement>()
                        .eq("settlement_status", "CALCULATING")
                        .eq("manual_action_required", 0)
                        .orderByAsc("updated_at")
                        .last("LIMIT " + batchSize));
        for (TripOrderSettlement settlement : candidates) {
            if (Integer.valueOf(1).equals(settlement.getManualActionRequired())) {
                continue;
            }
            settlementService.process(settlement.getOrderNo());
        }
    }
}
