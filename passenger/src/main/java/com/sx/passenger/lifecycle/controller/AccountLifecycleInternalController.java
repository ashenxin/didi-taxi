package com.sx.passenger.lifecycle.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.sx.passenger.lifecycle.orchestration.AccountCancellationOrchestrationTransaction;
import com.sx.passenger.lifecycle.orchestration.AccountCancellationOrchestrator;
import com.sx.passenger.lifecycle.persistence.entity.LifecycleBlockerEntity;
import com.sx.passenger.lifecycle.persistence.entity.LifecycleOperationEntity;
import com.sx.passenger.lifecycle.persistence.entity.LifecycleStepEntity;
import com.sx.passenger.lifecycle.persistence.mapper.LifecycleBlockerMapper;
import com.sx.passenger.lifecycle.persistence.mapper.LifecycleOperationMapper;
import com.sx.passenger.lifecycle.persistence.mapper.LifecycleStepMapper;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 生命周期内部运维接口。
 *
 * <p>提供 Operation 详情、重新推进和带证据的人工恢复，不面向乘客公开；
 * 服务身份认证由统一内部安全过滤器负责。
 */
@RestController
@RequestMapping("/api/v1/internal/account-lifecycle/operations")
public class AccountLifecycleInternalController {
    private final LifecycleOperationMapper operations;
    private final LifecycleStepMapper steps;
    private final LifecycleBlockerMapper blockers;
    private final AccountCancellationOrchestrator orchestrator;
    private final AccountCancellationOrchestrationTransaction transaction;

    public AccountLifecycleInternalController(
            LifecycleOperationMapper operations,
            LifecycleStepMapper steps,
            LifecycleBlockerMapper blockers,
            AccountCancellationOrchestrator orchestrator,
            AccountCancellationOrchestrationTransaction transaction) {
        this.operations = operations;
        this.steps = steps;
        this.blockers = blockers;
        this.orchestrator = orchestrator;
        this.transaction = transaction;
    }

    /** 尝试从当前持久化状态继续推进 Operation，并返回最新视图。 */
    @PostMapping("/{operationNo}/resume")
    public OperationView resume(@PathVariable String operationNo) {
        orchestrator.resume(operationNo);
        return view(operationNo);
    }

    /** 记录人工恢复审计信息，将可恢复步骤重新置为待执行后继续编排。 */
    @PostMapping("/{operationNo}/manual-recoveries")
    public OperationView manualRecovery(
            @PathVariable String operationNo, @RequestBody ManualRecoveryRequest request) {
        transaction.requestManualRetry(operationNo, request.actor(), request.reason(), request.evidenceId());
        orchestrator.resume(operationNo);
        return view(operationNo);
    }

    /** 查询 Operation、按顺序排列的 Steps 以及全部阻断项。 */
    @GetMapping("/{operationNo}")
    public OperationView view(@PathVariable String operationNo) {
        LifecycleOperationEntity operation = operations.selectOne(
                new LambdaQueryWrapper<LifecycleOperationEntity>()
                        .eq(LifecycleOperationEntity::getOperationNo, operationNo));
        if (operation == null) throw new IllegalArgumentException("生命周期操作不存在");
        List<LifecycleStepEntity> stepRows = steps.selectList(
                new LambdaQueryWrapper<LifecycleStepEntity>()
                        .eq(LifecycleStepEntity::getOperationId, operation.getId())
                        .orderByAsc(LifecycleStepEntity::getSequenceNo, LifecycleStepEntity::getId));
        List<LifecycleBlockerEntity> blockerRows = blockers.selectList(
                new LambdaQueryWrapper<LifecycleBlockerEntity>()
                        .eq(LifecycleBlockerEntity::getOperationId, operation.getId())
                        .orderByAsc(LifecycleBlockerEntity::getId));
        return new OperationView(operation, stepRows, blockerRows);
    }

    /** 人工恢复所需的操作者、原因和外部证据编号。 */
    public record ManualRecoveryRequest(String actor, String reason, String evidenceId) {}
    /** 运维查询返回的完整聚合视图。 */
    public record OperationView(LifecycleOperationEntity operation,
                                List<LifecycleStepEntity> steps,
                                List<LifecycleBlockerEntity> blockers) {}
}
