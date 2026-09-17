package com.sx.map.ai.tool;

import com.sx.map.exception.AmapApiException;
import com.sx.map.model.dto.AmapPoiCandidate;
import com.sx.map.service.AmapPoiSearchService;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 乘客指定途经点场景的内部 POI 查询组件。
 *
 * 本类由 {@link PassengerRoutePlanningTools} 在 Java 代码内部调用，不直接注册为大模型工具。
 * 大模型只通过路线规划工具提交地点关键词、可选地区和受控业务类型，不能直接拼接高德 URL，
 * 也不能自行提供高德类型编码、POI ID 或坐标。这样可以保证地点名称、坐标和入口信息
 * 始终来自 {@link AmapPoiSearchService} 的真实查询结果，而不是由模型生成。
 *
 * 本类只负责查询原始 POI 候选。同名收费站、上下行服务区等候选必须完整交给确定性路线规划服务，
 * 由 Java 规则结合起终点、导航入口和真实路线判断方向与可达性。原始 POI 不展示给乘客选择，
 * 本组件也不会擅自选择第一条结果。
 */
@Component
public class PassengerRoutePoiTools {

    /**
     * 路线规划工具使用稳定的业务类型名称，本组件内部再转换为高德类型编码。
     *
     * 禁止让模型直接传入任意 types，避免模型扩大检索范围，把景点、商场等
     * 首期不支持的地点误当成指定途经点。
     */
    private static final Map<String, String> AMAP_TYPE_CODE_BY_POI_TYPE = Map.of(
            // 高速收费站
            "TOLL_STATION", "180200",
            // 高速服务区
            "SERVICE_AREA", "180300",
            // 加油站（父类型编码，同时覆盖中石化、中石油等子类型）
            "GAS_STATION", "010100"
    );

    private static final int MAX_KEYWORDS_LENGTH = 80;
    private static final int MAX_REGION_LENGTH = 30;

    private final AmapPoiSearchService poiSearchService;

    public PassengerRoutePoiTools(AmapPoiSearchService poiSearchService) {
        this.poiSearchService = poiSearchService;
    }

    /**
     * 查询用户指定的收费站、服务区或加油站候选。
     *
     * 返回值可能包含多条记录，这是正常的业务结果。例如同一个服务区可能在高速公路两侧
     * 各有一个 POI。调用方必须把完整集合交给路线规划服务做方向与异常绕行过滤，
     * 不能把道路两侧的原始 POI 交给乘客判断，也不能静默取第一条。
     *
     * @param keywords 用户提到的地点名称，只保留地点本身，例如“下沙服务区”
     * @param region   可选的城市或行政区，例如“杭州”；用户没有说明时可以不传
     * @param poiType  只允许 TOLL_STATION、SERVICE_AREA、GAS_STATION
     * @return 高德返回的真实候选，查无结果时返回空列表
     */
    public List<AmapPoiCandidate> searchWaypointCandidates(
            String keywords,
            String region,
            String poiType) {
        String normalizedKeywords = requiredText(keywords, "地点名称", MAX_KEYWORDS_LENGTH);
        String normalizedRegion = optionalText(region, "地区", MAX_REGION_LENGTH);
        String normalizedPoiType = requiredText(poiType, "地点业务类型", 30).toUpperCase(Locale.ROOT);

        String amapTypeCode = AMAP_TYPE_CODE_BY_POI_TYPE.get(normalizedPoiType);
        if (amapTypeCode == null) {
            throw new AmapApiException(
                    "地点业务类型仅支持 TOLL_STATION、SERVICE_AREA、GAS_STATION"
            );
        }
        return poiSearchService.search(normalizedKeywords, normalizedRegion, amapTypeCode);
    }

    /**
     * 必填文本在进入地图服务前统一去除首尾空白并限制长度。
     * 长度限制用于防止上游把整段对话或无关内容误传给付费的地图搜索接口。
     */
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

    /**
     * 可选地区为空时传递 {@code null}，由 POI 服务执行全国范围搜索；有值时同样做长度保护。
     */
    private static String optionalText(String value, String label, int maxLength) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        String normalized = value.trim();
        if (normalized.length() > maxLength) {
            throw new AmapApiException(label + "长度不能超过" + maxLength + "个字符");
        }
        return normalized;
    }
}
