package com.sx.passenger.lifecycle.plan;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.ResourcePatternResolver;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 从 Spring Resource 中加载生命周期 YAML 计划。
 *
 * <p>加载器只负责资源读取和严格反序列化，不负责业务规则校验。未知 YAML 字段会被拒绝，
 * 防止配置拼写错误被静默忽略。返回结果按资源名排序，保证启动和测试结果稳定。
 */
public final class LifecyclePlanLoader {
    /** 开启未知字段失败策略的 YAML 解析器。 */
    private final YAMLMapper mapper;

    /** 创建使用严格字段检查的计划加载器。 */
    public LifecyclePlanLoader() {
        mapper = YAMLMapper.builder()
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .build();
    }

    /**
     * 加载匹配资源表达式的全部计划。
     *
     * @param resolver Spring 资源解析器
     * @param pattern 例如 {@code classpath*:account-lifecycle/*.yml}
     * @return 按资源名排序的不可变计划列表
     */
    public List<LoadedLifecyclePlan> load(ResourcePatternResolver resolver, String pattern) {
        try {
            Resource[] resources = resolver.getResources(pattern);
            List<LoadedLifecyclePlan> plans = new ArrayList<>(resources.length);
            for (Resource resource : resources) {
                plans.add(new LoadedLifecyclePlan(resource.getFilename(),
                        mapper.readValue(resource.getInputStream(), LifecyclePlanDefinition.class)));
            }
            plans.sort(Comparator.comparing(LoadedLifecyclePlan::sourceName));
            return List.copyOf(plans);
        } catch (IOException e) {
            throw new InvalidLifecyclePlanException("Failed to load lifecycle plan resources: " + pattern, e);
        }
    }
}
