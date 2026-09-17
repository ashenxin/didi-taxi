package com.sx.map.ai.tool;

import com.sx.map.exception.AmapApiException;
import com.sx.map.model.dto.AmapPoiCandidate;
import com.sx.map.model.dto.PassengerRouteCandidate;
import com.sx.map.model.dto.Point;
import com.sx.map.model.dto.RouteResponse;
import com.sx.map.service.WaypointRoutePlanningService;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 乘客指定途经点场景的路线候选规划工具。
 *
 * 模型只允许提交地点名称、地区和受控业务类型，不能提交POI ID、坐标或入口出口角色。
 * 起点、终点以及可选的可信POI候选集合由应用代码通过 {@link ToolContext} 注入，不会出现
 * 在模型可填写的JSON Schema中，因此模型不能挑选道路某一侧的POI。
 *
 * 编排层已经取得POI候选时，本工具直接使用可信上下文；当前编排尚未提供候选时，本工具会
 * 通过 {@link PassengerRoutePoiTools} 重新执行一次受控高德查询。两种路径都不会接受模型
 * 自报POI ID。{@link WaypointRoutePlanningService} 只返回地图导航证据已证明经过的路线。
 *
 * 本工具只处理具名地点规划，不核查页面当前路线，也不发现未指定名称的收费站方案。
 * 完整路线包含大量折线，本工具只向模型返回地点及本路线预计时间等摘要；完整候选
 * 集合写入调用方提供的 {@link AtomicReference}，供服务端生成卡片和保存快照。
 */
@Component
public class PassengerRoutePlanningTools {

    /** 页面当前起点，值类型必须是 {@link Point}，坐标系为 WGS84。 */
    public static final String ORIGIN_CONTEXT_KEY = "passengerRouteOrigin";

    /** 页面当前终点，值类型必须是 {@link Point}，坐标系为 WGS84。 */
    public static final String DESTINATION_CONTEXT_KEY = "passengerRouteDestination";

    /**
     * 可选的可信POI候选集合，值类型必须是 {@code List<AmapPoiCandidate>}。
     * 该集合来自本轮受控POI查询，不能由客户端或模型构造。
     */
    public static final String POI_CANDIDATES_CONTEXT_KEY = "passengerRoutePoiCandidates";

    /**
     * 完整路线候选接收器，值类型必须是 {@code AtomicReference<List<PassengerRouteCandidate>>}。
     * 该对象只在本次 Agent 请求内使用，不能跨乘客或跨会话共享。
     */
    public static final String RESULT_REFERENCE_CONTEXT_KEY = "passengerRoutePlanResultReference";

    private final PassengerRoutePoiTools poiTools;
    private final WaypointRoutePlanningService routePlanningService;

    public PassengerRoutePlanningTools(PassengerRoutePoiTools poiTools,
                                       WaypointRoutePlanningService routePlanningService) {
        this.poiTools = poiTools;
        this.routePlanningService = routePlanningService;
    }

    /**
     * 根据具名地点条件规划一组已验证实际经过的路线候选。
     *
     * @param keywords    原始地点名称，例如“下沙服务区”
     * @param region      可选城市或行政区
     * @param poiType     TOLL_STATION、SERVICE_AREA或GAS_STATION
     * @param toolContext 应用注入的可信起终点、可选POI集合和完整结果接收器
     * @return 供模型解释的轻量路线摘要集合，不包含折线和导航点坐标
     */
    @Tool(
            name = "plan_route_candidates",
            description = "按具名收费站、服务区或加油站规划已验证经过该地点的路线候选，"
                    + "返回各路线预计总里程和行驶时间。本工具不核查当前路线，不能发现未指定名称的收费站方案。"
                    + "模型不能提交或选择POI ID、坐标和入口出口角色。"
    )
    public List<Map<String, Object>> planRouteCandidates(
            @ToolParam(description = "候选查询使用的地点名称，例如：下沙服务区")
            String keywords,
            @ToolParam(required = false, description = "候选查询使用的可选城市或行政区，例如：杭州、330100")
            String region,
            @ToolParam(description = "地点业务类型，只允许：TOLL_STATION、SERVICE_AREA、GAS_STATION")
            String poiType,
            ToolContext toolContext) {
        // 同一请求内重复调用工具时，失败调用不能留下上一次的完整路线供编排层误用。
        AtomicReference<List<PassengerRouteCandidate>> resultReference = resultReference(toolContext);
        resultReference.set(null);
        // 先验证可信上下文，再发起可能计费的 POI 查询。上下文不完整时不能产生外部调用费用。
        Point origin = contextPoint(toolContext, ORIGIN_CONTEXT_KEY, "页面起点");
        Point destination = contextPoint(toolContext, DESTINATION_CONTEXT_KEY, "页面终点");

        String normalizedKeywords = requiredText(keywords, "地点名称", 80);
        String normalizedPoiType = requiredText(poiType, "地点业务类型", 30);
        List<AmapPoiCandidate> candidates = trustedCandidates(toolContext);
        if (candidates == null) {
            candidates = poiTools.searchWaypointCandidates(normalizedKeywords, region, normalizedPoiType);
        }
        List<PassengerRouteCandidate> result = routePlanningService.planCandidates(
                origin,
                destination,
                candidates,
                normalizedPoiType
        );
        if (result == null || result.isEmpty()) {
            throw new AmapApiException("本次没有已验证经过指定地点的路线");
        }
        List<PassengerRouteCandidate> snapshot = List.copyOf(result);
        List<Map<String, Object>> summaries = snapshot.stream()
                .map(PassengerRoutePlanningTools::summary).toList();
        resultReference.set(snapshot);
        return summaries;
    }

    private static Point contextPoint(ToolContext toolContext, String key, String label) {
        Object value = contextValue(toolContext, key);
        if (!(value instanceof Point point) || point.getLng() == null || point.getLat() == null) {
            throw new AmapApiException(label + "上下文缺少有效经纬度");
        }
        return point;
    }

    /**
     * Java泛型在运行期会擦除，因此这里验证容器类型；真正写入的集合始终由规划服务创建。
     */
    @SuppressWarnings("unchecked")
    private static AtomicReference<List<PassengerRouteCandidate>> resultReference(ToolContext toolContext) {
        Object value = contextValue(toolContext, RESULT_REFERENCE_CONTEXT_KEY);
        if (!(value instanceof AtomicReference<?> reference)) {
            throw new AmapApiException("路线候选结果接收器上下文缺失");
        }
        return (AtomicReference<List<PassengerRouteCandidate>>) reference;
    }

    /**
     * 编排层提供候选时验证集合元素类型并复制，防止本轮执行期间被其他线程修改。
     */
    private static List<AmapPoiCandidate> trustedCandidates(ToolContext toolContext) {
        if (toolContext == null || toolContext.getContext() == null
                || !toolContext.getContext().containsKey(POI_CANDIDATES_CONTEXT_KEY)) {
            return null;
        }
        Object value = toolContext.getContext().get(POI_CANDIDATES_CONTEXT_KEY);
        if (!(value instanceof List<?> values)) {
            throw new AmapApiException("可信POI候选上下文类型错误");
        }
        List<AmapPoiCandidate> candidates = new ArrayList<>(values.size());
        for (Object candidate : values) {
            if (!(candidate instanceof AmapPoiCandidate poiCandidate)) {
                throw new AmapApiException("可信POI候选集合包含非法元素");
            }
            candidates.add(poiCandidate);
        }
        return List.copyOf(candidates);
    }

    private static Object contextValue(ToolContext toolContext, String key) {
        if (toolContext == null || toolContext.getContext() == null) {
            throw new AmapApiException("路线工具上下文缺失");
        }
        return toolContext.getContext().get(key);
    }

    /**
     * 只把模型解释本条路线所需的事实放回上下文。结果 ID、比较值、折线及导航点
     * 均留在服务端；路线卡片和是否需要地点澄清由后续编排依据完整结果决定。
     */
    private static Map<String, Object> summary(PassengerRouteCandidate candidate) {
        RouteResponse route = candidate.route();
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("poiName", candidate.waypoint().getName());
        summary.put("passageMode", candidate.passageMode());
        summary.put("distanceMeters", route.getDistanceMeters());
        summary.put("durationSeconds", route.getDurationSeconds());
        summary.put("passageVerified", true);
        return summary;
    }

    private static String requiredText(String value, String label, int maxLength) {
        if (!StringUtils.hasText(value)) {
            throw new AmapApiException(label + "不能为空");
        }
        String normalized = value.trim();
        if (normalized.length() > maxLength) {
            throw new AmapApiException(label + "长度不能超过" + maxLength + "个字符");
        }
        return normalized;
    }
}
