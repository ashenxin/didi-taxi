package com.sx.passenger.lifecycle.plan;

import com.sx.passenger.lifecycle.domain.LifecycleOperationType;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class ImmutableLifecyclePlanRegistry implements LifecyclePlanRegistry {
    private final Map<LifecycleOperationType, ValidatedLifecyclePlan> active;
    private final Map<String, ValidatedLifecyclePlan> versions;

    private ImmutableLifecyclePlanRegistry(Map<LifecycleOperationType, ValidatedLifecyclePlan> active,
                                           Map<String, ValidatedLifecyclePlan> versions) {
        this.active = Map.copyOf(active);
        this.versions = Map.copyOf(versions);
    }

    public static LifecyclePlanRegistry from(List<LoadedLifecyclePlan> loaded,
                                             LifecyclePlanValidator validator,
                                             LifecyclePlanDigest digest) {
        Map<LifecycleOperationType, ValidatedLifecyclePlan> active = new EnumMap<>(LifecycleOperationType.class);
        Map<String, ValidatedLifecyclePlan> versions = new HashMap<>();
        for (LoadedLifecyclePlan item : loaded) {
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
        for (LifecycleOperationType type : LifecycleOperationType.values()) {
            if (!active.containsKey(type)) {
                throw new InvalidLifecyclePlanException("Missing ACTIVE lifecycle plan for " + type);
            }
        }
        return new ImmutableLifecyclePlanRegistry(active, versions);
    }

    @Override
    public ValidatedLifecyclePlan activePlan(LifecycleOperationType operationType) {
        ValidatedLifecyclePlan plan = active.get(operationType);
        if (plan == null) throw new InvalidLifecyclePlanException("Missing ACTIVE lifecycle plan for " + operationType);
        return plan;
    }

    @Override
    public ValidatedLifecyclePlan get(String code, int version) {
        ValidatedLifecyclePlan plan = versions.get(key(code, version));
        if (plan == null) throw new InvalidLifecyclePlanException("Lifecycle plan not found: " + code + " v" + version);
        return plan;
    }

    private static String key(String code, int version) {
        return code + ':' + version;
    }
}
