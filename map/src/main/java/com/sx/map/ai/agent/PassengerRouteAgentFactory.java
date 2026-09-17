package com.sx.map.ai.agent;

import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.sx.map.ai.tool.PassengerRoutePlanningTools;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.core.io.Resource;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Objects;

/**
 * 创建乘客指定途经点场景使用的 Spring AI Alibaba {@link ReactAgent}。
 *
 * 这个工厂不会保存乘客会话。产品侧的长期聊天历史仍由 passenger-service 持久化，
 * 调用方只把本轮需要的有限消息交给 Agent。每次执行结束后释放 Graph 线程状态，避免
 * map-service 内存状态被误当成长期会话，也避免不同乘客之间复用临时上下文。
 *
 * 本工厂只注册 {@link PassengerRoutePlanningTools}，处理起终点已确认、地点已具名的规划请求。
 * 原始 POI 由 Java 内部查询，地图路线由确定性规则验证实际经过；模型只取得解释所需的
 * 地点和预计时间摘要。完整路线留在服务端，指定地点规划成功后由编排层生成卡片。
 * 当前路线核查、普通默认路线和未指定名称的收费站方案尚无对应工具，不能靠本 Agent 的
 * 提示词或描述代替服务端实现。
 *
 * 本类保持为普通 Java 工厂，暂不注册为 Spring Bean。下一步由单独的配置类决定它使用
 * 哪个 {@link ChatModel}，从而让 DeepSeek、测试替身和未来其他模型保持可替换。
 */
public class PassengerRouteAgentFactory {

    static final String PLANNING_AGENT_NAME = "passenger_route_planning_agent";

    private final ChatModel chatModel;
    private final PassengerRoutePlanningTools planningTools;
    private final String systemPrompt;

    public PassengerRouteAgentFactory(ChatModel chatModel,
                                      PassengerRoutePlanningTools planningTools,
                                      Resource systemPromptResource) {
        this.chatModel = Objects.requireNonNull(chatModel, "ChatModel 不能为空");
        this.planningTools = Objects.requireNonNull(planningTools, "路线规划工具不能为空");
        this.systemPrompt = loadSystemPrompt(systemPromptResource);
    }

    /**
     * 创建指定途经点路线规划 Agent。
     *
     * 调用方把页面起点、终点和完整结果接收器放进 {@code trustedToolContext}。这些值由
     * 应用注入，不会成为模型参数。编排层已经完成受控 POI 查询时，也可以把整组候选放入
     * 上下文复用；否则路线工具在 Java 内部自行查询，再验证地图路线实际经过指定地点。
     *
     * @param trustedToolContext 本次请求的可信工具上下文
     */
    public ReactAgent createRoutePlanningAgent(Map<String, Object> trustedToolContext) {
        Map<String, Object> context = validatedPlanningContext(trustedToolContext);
        return ReactAgent.builder()
                .name(PLANNING_AGENT_NAME)
                .description("根据已确认的起终点和具名收费站、服务区或加油站条件，"
                        + "生成已验证经过指定地点的路线摘要；不核查当前路线或发现未指定名称的收费站方案")
                .model(chatModel)
                .systemPrompt(systemPrompt)
                .methodTools(planningTools)
                .toolContext(context)
                .releaseThread(true)
                .build();
    }

    private static Map<String, Object> validatedPlanningContext(Map<String, Object> trustedToolContext) {
        Objects.requireNonNull(trustedToolContext, "路线规划工具上下文不能为空");
        requireContextValue(trustedToolContext, PassengerRoutePlanningTools.ORIGIN_CONTEXT_KEY);
        requireContextValue(trustedToolContext, PassengerRoutePlanningTools.DESTINATION_CONTEXT_KEY);
        requireContextValue(trustedToolContext, PassengerRoutePlanningTools.RESULT_REFERENCE_CONTEXT_KEY);
        return Map.copyOf(trustedToolContext);
    }

    private static void requireContextValue(Map<String, Object> context, String key) {
        if (!context.containsKey(key) || context.get(key) == null) {
            throw new IllegalArgumentException("路线规划工具上下文缺少 " + key);
        }
    }

    private static String loadSystemPrompt(Resource resource) {
        Objects.requireNonNull(resource, "系统提示词资源不能为空");
        try {
            String content = resource.getContentAsString(StandardCharsets.UTF_8);
            if (!StringUtils.hasText(content)) {
                throw new IllegalStateException("乘客路线 Agent 系统提示词不能为空");
            }
            return content;
        } catch (IOException e) {
            throw new IllegalStateException("读取乘客路线 Agent 系统提示词失败", e);
        }
    }
}
