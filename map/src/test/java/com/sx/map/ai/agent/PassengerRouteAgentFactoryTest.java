package com.sx.map.ai.agent;

import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.agent.node.AgentLlmNode;
import com.alibaba.cloud.ai.graph.agent.node.AgentToolNode;
import com.sx.map.ai.tool.PassengerRoutePlanningTools;
import com.sx.map.ai.tool.PassengerRoutePoiTools;
import com.sx.map.model.dto.PassengerRouteCandidate;
import com.sx.map.model.dto.Point;
import com.sx.map.service.WaypointRoutePlanningService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class PassengerRouteAgentFactoryTest {

    private static final String TEST_SYSTEM_PROMPT = "你是乘客路线助手，只能使用当前阶段开放的工具。";

    private PassengerRouteAgentFactory factory;

    @BeforeEach
    void setUp() {
        ChatModel chatModel = mock(ChatModel.class);
        PassengerRoutePlanningTools planningTools = new PassengerRoutePlanningTools(
                mock(PassengerRoutePoiTools.class),
                mock(WaypointRoutePlanningService.class)
        );
        factory = new PassengerRouteAgentFactory(
                chatModel,
                planningTools,
                promptResource(TEST_SYSTEM_PROMPT)
        );
    }

    /**
     * Agent 只看到具名地点规划工具，职责描述也不能暗示已实现当前路线核查或方案发现。
     * 原始 POI 查询留在 Java 内部，模型无法提交 POI ID 来指定道路某一侧。
     */
    @Test
    void createsPlanningAgentWithOnlyPlanningTool() {
        ReactAgent agent = factory.createRoutePlanningAgent(validPlanningContext());

        assertThat(agent.name()).isEqualTo(PassengerRouteAgentFactory.PLANNING_AGENT_NAME);
        assertThat(agent.description())
                .contains("已验证经过指定地点的路线摘要")
                .contains("不核查当前路线或发现未指定名称的收费站方案")
                .doesNotContain("方向正确", "routeCandidateId");
        assertThat(toolNames(agent)).containsExactly("plan_route_candidates");
        assertThat(systemPrompt(agent)).isEqualTo(TEST_SYSTEM_PROMPT);
    }

    /**
     * 读取随应用打包的真实提示词，防止 Agent 再回到每次必选路线和比较时间差的旧流程。
     * 这里只核对装配与关键业务边界，不调用模型，也不把文案断言当成模型行为验收。
     */
    @Test
    void bundledPromptKeepsCurrentRouteAndDirectCardBoundaries() {
        PassengerRoutePlanningTools planningTools = new PassengerRoutePlanningTools(
                mock(PassengerRoutePoiTools.class), mock(WaypointRoutePlanningService.class));
        PassengerRouteAgentFactory bundledFactory = new PassengerRouteAgentFactory(
                mock(ChatModel.class), planningTools,
                new ClassPathResource("ai/passenger-route-system-prompt.md"));

        String prompt = systemPrompt(bundledFactory.createRoutePlanningAgent(validPlanningContext()));

        assertThat(prompt)
                .contains("具体路线及其核查结论")
                .contains("本轮回答即可结束")
                .contains("直接配合一张路线卡片")
                .contains("服务端已验证的收费站路线方案")
                .contains("不能核查当前路线")
                .doesNotContain("routeCandidateId", "路线差值", "由乘客选择路线");
    }

    @Test
    void rejectsPlanningAgentWithoutTrustedContext() {
        assertThatThrownBy(() -> factory.createRoutePlanningAgent(Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(PassengerRoutePlanningTools.ORIGIN_CONTEXT_KEY);
    }

    @Test
    void rejectsBlankSystemPrompt() {
        assertThatThrownBy(() -> new PassengerRouteAgentFactory(
                mock(ChatModel.class),
                mock(PassengerRoutePlanningTools.class),
                promptResource("   ")
        ))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("系统提示词不能为空");
    }

    private static Map<String, Object> validPlanningContext() {
        Point origin = new Point();
        origin.setLng(120.15);
        origin.setLat(30.28);
        Point destination = new Point();
        destination.setLng(120.21);
        destination.setLat(30.32);
        return Map.of(
                PassengerRoutePlanningTools.ORIGIN_CONTEXT_KEY, origin,
                PassengerRoutePlanningTools.DESTINATION_CONTEXT_KEY, destination,
                PassengerRoutePlanningTools.RESULT_REFERENCE_CONTEXT_KEY,
                new AtomicReference<List<PassengerRouteCandidate>>()
        );
    }

    private static ByteArrayResource promptResource(String content) {
        return new ByteArrayResource(content.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * ReactAgent 1.1.2.0 没有公开只读的工具清单和系统提示词访问器。
     * 测试通过反射读取框架内部已构建节点，只验证装配结果，不修改节点状态，也不调用模型。
     */
    private static List<String> toolNames(ReactAgent agent) {
        AgentToolNode toolNode = fieldValue(agent, ReactAgent.class, "toolNode", AgentToolNode.class);
        return toolNode.getToolCallbacks().stream()
                .map(ToolCallback::getToolDefinition)
                .map(definition -> definition.name())
                .toList();
    }

    private static String systemPrompt(ReactAgent agent) {
        AgentLlmNode llmNode = fieldValue(agent, ReactAgent.class, "llmNode", AgentLlmNode.class);
        return fieldValue(llmNode, AgentLlmNode.class, "systemPrompt", String.class);
    }

    private static <T> T fieldValue(Object target, Class<?> owner, String fieldName, Class<T> valueType) {
        try {
            Field field = owner.getDeclaredField(fieldName);
            field.setAccessible(true);
            return valueType.cast(field.get(target));
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("无法读取 Agent 装配结果字段：" + fieldName, e);
        }
    }
}
