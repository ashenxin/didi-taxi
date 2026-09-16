package com.sx.map.ai.tool;

import com.sx.map.exception.AmapApiException;
import com.sx.map.model.dto.AmapPoiCandidate;
import com.sx.map.model.dto.Point;
import com.sx.map.model.dto.RouteResponse;
import com.sx.map.model.dto.WaypointRoutePlanResponse;
import com.sx.map.service.WaypointRoutePlanningService;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 乘客指定途经点场景的真实路线规划工具。
 *
 * 本工具只允许模型提交地点查询条件、已经选中的高德 POI ID，以及入口或出口角色。
 * 起点和终点由应用代码通过 {@link ToolContext} 注入，不会出现在模型可填写的 JSON Schema 中，
 * 因此模型不能篡改页面当前的起终点坐标。
 *
 * 为了防止模型伪造 POI，本工具会再次执行受控地点查询，并要求 {@code poiId} 与高德返回的
 * 候选完全匹配。只有匹配成功的候选才能交给 {@link WaypointRoutePlanningService} 算路。
 *
 * 完整路线通常包含大量折线坐标，直接作为工具结果发回模型会浪费 Token。
 * 因此工具只返回便于模型组织回答的路线摘要；完整结果写入调用方提供的
 * {@link AtomicReference}，由后续 Agent 接口直接返回给前端绘图。
 */
@Component
public class PassengerRoutePlanningTools {

    /** 页面当前起点，值类型必须是 {@link Point}，坐标系为 WGS84。 */
    public static final String ORIGIN_CONTEXT_KEY = "passengerRouteOrigin";

    /** 页面当前终点，值类型必须是 {@link Point}，坐标系为 WGS84。 */
    public static final String DESTINATION_CONTEXT_KEY = "passengerRouteDestination";

    /**
     * 完整路线结果接收器，值类型必须是 {@code AtomicReference<WaypointRoutePlanResponse>}。
     * 该对象只在本次 Agent 请求内使用，不能跨乘客或跨会话共享。
     */
    public static final String RESULT_REFERENCE_CONTEXT_KEY = "passengerRoutePlanResultReference";

    private static final int MAX_POI_ID_LENGTH = 80;

    private final PassengerRoutePoiTools poiTools;
    private final WaypointRoutePlanningService routePlanningService;

    public PassengerRoutePlanningTools(PassengerRoutePoiTools poiTools,
                                       WaypointRoutePlanningService routePlanningService) {
        this.poiTools = poiTools;
        this.routePlanningService = routePlanningService;
    }

    /**
     * 根据已经确认的高德候选地点规划单途经点路线。
     *
     * 只有以下两种情况可以调用：
     * 1. 地点查询只返回一个高匹配候选，并且不存在方向歧义；
     * 2. Agent 已经向乘客展示候选，并由乘客明确选择了其中一个。
     *
     * 如果候选 ID 不再存在，工具会失败并要求重新查询，不能退回到第一条候选。
     *
     * @param keywords      原始地点名称，例如“下沙服务区”
     * @param region        可选城市或行政区，必须与候选查询时一致
     * @param poiType       TOLL_STATION、SERVICE_AREA 或 GAS_STATION
     * @param poiId         乘客确认的高德 POI ID
     * @param waypointAccess ENTRANCE 表示从入口进入，EXIT 表示从出口方向通过
     * @param toolContext   应用注入的可信起终点和完整结果接收器，不暴露给模型
     * @return 供模型组织自然语言回答的轻量摘要，不包含路线折线
     */
    @Tool(
            name = "plan_route_via_selected_waypoint",
            description = "按乘客已经确认的高德 POI 规划单途经点路线，并计算与普通路线的里程和时间差。"
                    + "只有候选唯一且无方向歧义，或乘客明确选择候选后才能调用。"
                    + "禁止猜测 POI ID，禁止把地点中心点当作入口或出口。"
    )
    public Map<String, Object> planRouteViaSelectedWaypoint(
            @ToolParam(description = "候选查询使用的地点名称，例如：下沙服务区")
            String keywords,
            @ToolParam(required = false, description = "候选查询使用的可选城市或行政区，例如：杭州、330100")
            String region,
            @ToolParam(description = "地点业务类型，只允许：TOLL_STATION、SERVICE_AREA、GAS_STATION")
            String poiType,
            @ToolParam(description = "乘客确认的高德 POI ID，必须来自 search_waypoint_candidates 返回值")
            String poiId,
            @ToolParam(description = "途经点角色，只允许 ENTRANCE 或 EXIT")
            String waypointAccess,
            ToolContext toolContext) {
        String normalizedPoiId = requiredText(poiId, "POI ID", MAX_POI_ID_LENGTH);
        WaypointRoutePlanningService.WaypointAccess access = parseAccess(waypointAccess);

        // 先验证可信上下文，再发起可能计费的 POI 查询。上下文不完整时不能产生外部调用费用。
        Point origin = contextPoint(toolContext, ORIGIN_CONTEXT_KEY, "页面起点");
        Point destination = contextPoint(toolContext, DESTINATION_CONTEXT_KEY, "页面终点");
        AtomicReference<WaypointRoutePlanResponse> resultReference = resultReference(toolContext);

        List<AmapPoiCandidate> candidates = poiTools.searchWaypointCandidates(keywords, region, poiType);
        AmapPoiCandidate selected = candidates.stream()
                .filter(candidate -> normalizedPoiId.equals(candidate.getPoiId()))
                .findFirst()
                .orElseThrow(() -> new AmapApiException("已选择的 POI 不在最新高德候选中，请重新查询并确认"));

        WaypointRoutePlanResponse result = routePlanningService.plan(
                origin,
                destination,
                selected,
                access
        );
        resultReference.set(result);
        return summary(result);
    }

    private static WaypointRoutePlanningService.WaypointAccess parseAccess(String value) {
        String normalized = requiredText(value, "途经点角色", 20).toUpperCase(Locale.ROOT);
        try {
            return WaypointRoutePlanningService.WaypointAccess.valueOf(normalized);
        } catch (IllegalArgumentException e) {
            throw new AmapApiException("途经点角色仅支持 ENTRANCE 或 EXIT");
        }
    }

    private static Point contextPoint(ToolContext toolContext, String key, String label) {
        Object value = contextValue(toolContext, key);
        if (!(value instanceof Point point) || point.getLng() == null || point.getLat() == null) {
            throw new AmapApiException(label + "上下文缺少有效经纬度");
        }
        return point;
    }

    /**
     * Java 泛型在运行期会擦除，因此这里先验证容器类型；真正写入的对象始终由本工具创建。
     */
    @SuppressWarnings("unchecked")
    private static AtomicReference<WaypointRoutePlanResponse> resultReference(ToolContext toolContext) {
        Object value = contextValue(toolContext, RESULT_REFERENCE_CONTEXT_KEY);
        if (!(value instanceof AtomicReference<?> reference)) {
            throw new AmapApiException("路线结果接收器上下文缺失");
        }
        return (AtomicReference<WaypointRoutePlanResponse>) reference;
    }

    private static Object contextValue(ToolContext toolContext, String key) {
        if (toolContext == null || toolContext.getContext() == null) {
            throw new AmapApiException("路线工具上下文缺失");
        }
        return toolContext.getContext().get(key);
    }

    /**
     * 只把模型编写回答所需的事实放回模型上下文。折线仍保存在完整结果中交给前端。
     */
    private static Map<String, Object> summary(WaypointRoutePlanResponse result) {
        RouteResponse route = result.getRoute();
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("poiId", result.getWaypoint().getPoiId());
        summary.put("poiName", result.getWaypoint().getName());
        summary.put("waypointAccess", result.getWaypointAccess());
        summary.put("distanceMeters", route.getDistanceMeters());
        summary.put("durationSeconds", route.getDurationSeconds());
        summary.put("distanceDeltaMeters", result.getDistanceDeltaMeters());
        summary.put("durationDeltaSeconds", result.getDurationDeltaSeconds());
        summary.put("comparisonAvailable", result.getReferenceRoute() != null);
        summary.put("polylineAvailable", route.getPolyline() != null && !route.getPolyline().isEmpty());
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
