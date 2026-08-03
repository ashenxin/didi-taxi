package com.sx.passenger.lifecycle.application;

import com.sx.passenger.lifecycle.domain.LifecycleOperationStatus;
import com.sx.passenger.lifecycle.domain.LifecycleStepStatus;
import com.sx.passenger.lifecycle.persistence.entity.LifecycleEventEntity;
import com.sx.passenger.lifecycle.persistence.entity.LifecycleOperationEntity;
import com.sx.passenger.lifecycle.persistence.entity.LifecycleOutboxEntity;
import com.sx.passenger.lifecycle.persistence.entity.LifecycleStepEntity;
import com.sx.passenger.lifecycle.plan.LifecyclePlanRegistry;
import com.sx.passenger.lifecycle.plan.LifecycleStepDefinition;
import com.sx.passenger.lifecycle.plan.ValidatedLifecyclePlan;
import com.sx.passenger.time.PassengerPersistenceTime;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 生命周期运行时快照工厂。
 *
 * <p>该工厂负责把一次“创建账号生命周期操作”的请求，与当前生效的生命周期计划结合，
 * 组装成一组可以由 {@link LifecycleSnapshotStore} 在同一数据库事务中保存的运行时对象：
 *
 * <ul>
 *     <li>{@link LifecycleOperationEntity}：本次换号、注销等生命周期操作的主记录；</li>
 *     <li>{@link LifecycleStepEntity}：从计划配置中复制出来的步骤快照；</li>
 *     <li>{@link LifecycleEventEntity}：记录“操作已被请求”的审计事件；</li>
 *     <li>{@link LifecycleOutboxEntity}：等待可靠发布到消息系统的 Outbox 事件。</li>
 * </ul>
 *
 * <p>这里生成的是“运行时快照”，而不是继续引用可变化的计划配置。计划版本、摘要、步骤参数、
 * 重试策略和超时时间都会在创建操作时固化下来。因此，即使系统随后发布了新版计划，
 * 已经创建的操作仍会按照创建当时的规则继续执行，避免运行中的流程被配置变更污染。
 *
 * <p>本类只负责对象组装，不负责写数据库、发送 Kafka 消息或推进 Saga。持久化由
 * {@link LifecycleSnapshotStore} 负责，后续编排由生命周期编排组件负责。
 */
public final class LifecycleRuntimeSnapshotFactory {
    /**
     * “生命周期操作已请求”事件类型。
     *
     * <p>审计事件和 Outbox 事件使用相同的业务事件类型，但二者用途不同：
     * 审计事件用于数据库内追踪，Outbox 事件用于向其他组件可靠传播。
     */
    private static final String REQUESTED_EVENT = "LIFECYCLE_OPERATION_REQUESTED";

    /** 提供当前生效且已经完成结构校验的生命周期计划。 */
    private final LifecyclePlanRegistry plans;

    /** 为 Operation 和 Event 生成全局业务标识。 */
    private final LifecycleIdentifierGenerator identifiers;

    /** 将步骤定义和消息载荷序列化为 JSON。 */
    private final LifecycleJson json;

    /**
     * 创建运行时快照工厂。
     *
     * @param plans 生命周期计划注册表，用于按操作类型选择当前生效计划
     * @param identifiers 生命周期业务编号生成器
     * @param json 生命周期 JSON 序列化组件
     */
    public LifecycleRuntimeSnapshotFactory(LifecyclePlanRegistry plans,
                                           LifecycleIdentifierGenerator identifiers,
                                           LifecycleJson json) {
        this.plans = plans;
        this.identifiers = identifiers;
        this.json = json;
    }

    /**
     * 根据请求命令和当前生效计划，创建一份完整的生命周期运行时快照。
     *
     * <p>该方法只在内存中构造对象，不产生数据库写入和消息发送。调用方应把返回的四类对象
     * 作为一个整体交给 {@link LifecycleSnapshotStore}，在同一事务中完成持久化，避免出现
     * “有 Operation 但没有 Step”或“业务记录已创建但 Outbox 事件丢失”的不完整状态。
     *
     * @param command 创建生命周期快照所需的业务身份、幂等信息、版本和审计上下文
     * @return 包含 Operation、Steps、请求审计事件和请求 Outbox 事件的完整快照
     * @throws IllegalArgumentException 当命令缺少必填字段，或者请求摘要、幂等键不合法时抛出
     */
    public LifecycleRuntimeSnapshot create(CreateLifecycleSnapshotCommand command) {
        // 在读取计划、分配业务编号之前先拒绝不完整命令，避免无效请求消耗编号或产生半成品对象。
        validate(command);

        /*
         * 根据操作类型取得当前生效计划。返回值已经由计划加载阶段完成结构校验，
         * 因此这里可以直接使用计划编码、版本、摘要和步骤定义。
         */
        ValidatedLifecyclePlan plan = plans.activePlan(command.operationType());

        // Passenger 的 DATETIME 统一使用上海本地时间，并保证各实体来自同一个 requestedAt。
        LocalDateTime now = PassengerPersistenceTime.fromInstant(command.requestedAt());

        // operationNo 是生命周期操作对外使用的稳定业务编号，不依赖数据库自增主键。
        String operationNo = identifiers.nextOperationNo();

        /*
         * 创建生命周期操作主记录。
         *
         * REQUESTED 表示系统已经受理请求，但各参与者步骤尚未开始执行。
         * planCode、planVersion、planDigest 共同锁定本次操作使用的计划版本；
         * expectedLifecycleVersion 用于后续通过 CAS 防止并发换号、注销等操作相互覆盖。
         */
        LifecycleOperationEntity operation = new LifecycleOperationEntity()
                .setOperationNo(operationNo).setCustomerId(command.customerId())
                .setOperationType(command.operationType().name()).setStatus(LifecycleOperationStatus.REQUESTED.name())
                .setIdempotencyKey(command.idempotencyKey()).setRequestHash(command.requestHash())
                .setExpectedLifecycleVersion(command.expectedLifecycleVersion())
                .setPlanCode(plan.code()).setPlanVersion(plan.version()).setPlanDigest(plan.digest())
                // 新操作尚未进入不可逆阶段，也尚未产生业务阻断项。
                .setIrreversibleStarted(0).setActiveBlockerCount(0).setRowVersion(0L)
                // requestContext 必须由上游先脱敏，工厂只负责将脱敏后的审计上下文固化。
                .setRequestContext(command.sanitizedRequestContextJson()).setRequestedAt(now)
                .setCreatedAt(now).setUpdatedAt(now);

        /*
         * 将配置计划中的步骤定义复制为数据库运行时步骤。
         *
         * 这里必须保存步骤的执行模式、关键性、顺序、重试和超时参数，而不能在每次执行时
         * 重新读取最新计划。这样可以保证长时间运行或正在重试的操作始终遵循创建时的规则。
         */
        List<LifecycleStepEntity> steps = new ArrayList<>(plan.steps().size());
        for (LifecycleStepDefinition definition : plan.steps()) {
            steps.add(new LifecycleStepEntity()
                    // stepCode 标识步骤；participantCode 标识负责执行该步骤的业务参与者。
                    .setStepCode(definition.code()).setParticipantCode(definition.participant())
                    // phase、executionMode、criticality 和 sequenceNo 决定编排顺序与失败处理方式。
                    .setPhase(definition.phase()).setExecutionMode(definition.executionMode())
                    .setCriticality(definition.criticality()).setSequenceNo(definition.sequence())
                    // 新步骤统一从 PENDING 开始，尚未产生任何执行尝试。
                    .setStatus(LifecycleStepStatus.PENDING.name()).setAttemptCount(0)
                    // 将重试上限、首次退避时间和单次执行超时固化到步骤记录。
                    .setMaxRetryCount(definition.retry().maxAttempts())
                    .setRetryInitialSeconds(definition.retry().initialIntervalSeconds())
                    // stepConfig 保存完整步骤定义，供运行时诊断、审计或扩展参数读取。
                    .setTimeoutSeconds(definition.timeoutSeconds()).setStepConfig(json.write(definition))
                    .setCreatedAt(now).setUpdatedAt(now));
        }

        /*
         * 生成数据库内的审计事件。
         *
         * auditEventId 唯一标识这条状态变更事实；toStatus=REQUESTED 表示操作被创建后到达的状态。
         * actorType、actorId 和 traceId 用于回答“谁在什么调用链中发起了这次操作”。
         */
        String auditEventId = identifiers.nextEventId();
        LifecycleEventEntity event = new LifecycleEventEntity()
                .setEventId(auditEventId).setCustomerId(command.customerId()).setEventType(REQUESTED_EVENT)
                .setToStatus(LifecycleOperationStatus.REQUESTED.name()).setActorType(command.actorType().name())
                // 初始事件没有前置状态；payloadSnapshot 暂无额外业务快照，因此保存为空 JSON 对象。
                .setActorId(command.actorId()).setTraceId(command.traceId()).setPayloadSnapshot("{}")
                .setCreatedAt(now);

        /*
         * 为同一业务事实生成待发布的 Outbox 事件。
         *
         * Outbox 与 Operation、Steps、审计 Event 一起落库。事务提交后，由独立发布任务读取
         * PENDING 记录并发送 Kafka，从而避免“数据库事务成功但消息没有发出”的双写不一致。
         */
        String outboxEventId = identifiers.nextEventId();

        // LinkedHashMap 保留字段插入顺序，使生成的 JSON 更稳定，也便于日志、测试和人工排查。
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("eventId", outboxEventId);
        payload.put("operationNo", operationNo);
        payload.put("operationType", command.operationType().name());
        payload.put("customerId", command.customerId());
        // 消费方可以利用期望版本识别过期命令或进行并发保护。
        payload.put("expectedLifecycleVersion", command.expectedLifecycleVersion());
        payload.put("occurredAt", command.requestedAt().toString());

        LifecycleOutboxEntity outbox = new LifecycleOutboxEntity()
                // aggregateId 使用 operationNo，把同一次生命周期操作归入同一聚合。
                .setEventId(outboxEventId).setAggregateType("ACCOUNT_LIFECYCLE").setAggregateId(operationNo)
                /*
                 * causationEventId 指向上面的审计事件，表明该待发布消息由哪条数据库事实引起；
                 * traceId 则把它与最初的 HTTP 或内部服务调用链关联起来。
                 */
                .setEventType(REQUESTED_EVENT).setCausationEventId(auditEventId).setTraceId(command.traceId())
                /*
                 * partitionKey 使用 customerId，确保同一乘客的生命周期消息在 Kafka 中
                 * 尽量落到同一分区，从而保持该乘客相关事件的消费顺序。
                 */
                .setTopic("account.lifecycle.requested.v1").setPartitionKey(Long.toString(command.customerId()))
                /*
                 * PENDING 表示尚未发布；初始重试次数为 0，最多重试 10 次；
                 * nextRetryAt=now 表示事务提交后即可被 Outbox 发布任务选中。
                 */
                .setPayload(json.write(payload)).setStatus("PENDING").setRetryCount(0).setMaxRetryCount(10)
                .setNextRetryAt(now).setCreatedAt(now).setUpdatedAt(now);

        // 四类对象作为不可拆分的快照返回，由存储组件在一个事务中统一保存。
        return new LifecycleRuntimeSnapshot(operation, steps, event, outbox);
    }

    /**
     * 校验工厂创建快照时依赖的最低输入约束。
     *
     * <p>这里只校验与快照结构直接相关的通用条件。customerId、生命周期版本、actorId 等更具体的
     * 业务规则应由命令创建方或对应的换号、注销应用服务校验，避免工厂承担业务入口职责。
     *
     * @param command 待校验的快照创建命令
     */
    private static void validate(CreateLifecycleSnapshotCommand command) {
        // 操作类型、操作者类型和请求时间决定计划选择、审计内容及所有实体时间，均不可缺失。
        if (command == null || command.operationType() == null || command.actorType() == null
                || command.requestedAt() == null) {
            throw new IllegalArgumentException("lifecycle snapshot command is incomplete");
        }

        /*
         * requestHash 必须是小写十六进制 SHA-256，用于判断相同幂等键下的请求内容是否一致，
         * 防止调用方复用同一个幂等键提交不同的换号或注销请求。
         */
        if (command.requestHash() == null || !command.requestHash().matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("requestHash must be lowercase SHA-256");
        }

        // 幂等键用于识别重复请求；空值会使操作无法安全重放，因此在创建快照前直接拒绝。
        if (command.idempotencyKey() == null || command.idempotencyKey().isBlank()) {
            throw new IllegalArgumentException("idempotencyKey must not be blank");
        }
    }
}
