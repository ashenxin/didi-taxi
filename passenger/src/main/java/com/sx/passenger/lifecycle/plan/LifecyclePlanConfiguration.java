package com.sx.passenger.lifecycle.plan;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.support.ResourcePatternResolver;

/**
 * 生命周期计划的 Spring 装配入口。
 *
 * <p>服务启动时加载 classpath 下的全部计划 YAML，完成严格校验后构建不可变注册表。
 * 任一计划格式错误、重复生效或缺失必要操作类型时，Bean 创建失败并阻止服务带病启动。
 */
@Configuration
public class LifecyclePlanConfiguration {
    /** 加载并注册打包在应用中的账号生命周期计划。 */
    @Bean
    LifecyclePlanRegistry lifecyclePlanRegistry(ResourcePatternResolver resources) {
        LifecyclePlanLoader loader = new LifecyclePlanLoader();
        return ImmutableLifecyclePlanRegistry.from(
                loader.load(resources, "classpath*:account-lifecycle/*.yml"),
                new LifecyclePlanValidator(), new LifecyclePlanDigest());
    }
}
