package com.sx.passenger.lifecycle.plan;

import com.sx.passenger.lifecycle.domain.LifecycleOperationType;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 生命周期计划注册表的不可变实现。
 *
 * <p>内部同时维护“操作类型到当前生效计划”和“计划代码版本到任意历史计划”两套索引。
 * 构建完成后不支持热修改，避免运行期间计划引用发生漂移。
 */
public final class ImmutableLifecyclePlanRegistry implements LifecyclePlanRegistry {
    /** 每种操作类型唯一的当前生效计划。 */
    private final Map<LifecycleOperationType, ValidatedLifecyclePlan> active;
    /** 按 code:version 索引的全部已加载版本。 */
    private final Map<String, ValidatedLifecyclePlan> versions;

    /** 仅允许通过 {@link #from(List, LifecyclePlanValidator, LifecyclePlanDigest)} 完整构建。 */
    private ImmutableLifecyclePlanRegistry(Map<LifecycleOperationType, ValidatedLifecyclePlan> active,
                                           Map<String, ValidatedLifecyclePlan> versions) {
        this.active = Map.copyOf(active);
        this.versions = Map.copyOf(versions);
    }

    /**
     * 校验全部计划、计算摘要并构建注册表。
     *
     * <p>计划代码和版本必须全局唯一；同一操作类型只能存在一个 ACTIVE 计划；
     * 系统支持的每一种操作类型都必须配置 ACTIVE 计划。
     */
    public static LifecyclePlanRegistry from(List<LoadedLifecyclePlan> loaded,
                                             LifecyclePlanValidator validator,
                                             LifecyclePlanDigest digest) {
        Map<LifecycleOperationType, ValidatedLifecyclePlan> active = new EnumMap<>(LifecycleOperationType.class);
        Map<String, ValidatedLifecyclePlan> versions = new HashMap<>();
        for (LoadedLifecyclePlan item : loaded) {
            // 单个计划先完成校验，再进入任何索引，避免注册半合法配置。
            LifecyclePlanDefinition definition = item.definition();
            validator.validate(definition, item.sourceName());
            LifecycleOperationType type = LifecycleOperationType.valueOf(definition.operationType());
            ValidatedLifecyclePlan plan = new ValidatedLifecyclePlan(definition.code(), definition.version(), type,
                    digest.sha256(definition), List.copyOf(definition.steps()));
            if (versions.putIfAbsent(key(plan.code(), plan.version()), plan) != null) {
                throw new InvalidLifecyclePlanException("Duplicate lifecycle plan: " + plan.code() + " v" + plan.version());
            }
            if ("ACTIVE".equals(definition.status()) && active.putIfAbsent(type, plan) != null) {
                throw new InvalidLifecyclePlanException("multiple ACTIVE lifecycle plans for " + type);
            }
        }
        // 启动时一次性确认所有操作类型都有可用于新建 Operation 的生效计划。
        for (LifecycleOperationType type : LifecycleOperationType.values()) {
            if (!active.containsKey(type)) {
                throw new InvalidLifecyclePlanException("Missing ACTIVE lifecycle plan for " + type);
            }
        }
        return new ImmutableLifecyclePlanRegistry(active, versions);
    }

    /** {@inheritDoc} */
    @Override
    public ValidatedLifecyclePlan activePlan(LifecycleOperationType operationType) {
        ValidatedLifecyclePlan plan = active.get(operationType);
        if (plan == null) throw new InvalidLifecyclePlanException("Missing ACTIVE lifecycle plan for " + operationType);
        return plan;
    }

    /** {@inheritDoc} */
    @Override
    public ValidatedLifecyclePlan get(String code, int version) {
        ValidatedLifecyclePlan plan = versions.get(key(code, version));
        if (plan == null) throw new InvalidLifecyclePlanException("Lifecycle plan not found: " + code + " v" + version);
        return plan;
    }

    /** 生成内部版本索引键；不是对外业务标识。 */
    private static String key(String code, int version) {
        return code + ':' + version;
    }
}
