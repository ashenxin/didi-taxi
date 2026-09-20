package com.sx.map.ai.dto;

/**
 * 路线意图提取结果。默认路线分支使用起终点字段，当前路线核查分支使用核查地点字段。
 * 模型只负责提取文字；地点身份与坐标仍由地图查询确认。
 */
public record ExtractEndpointsResponse(String originName,
                                      String destinationName,
                                      String originRegion,
                                      String destinationRegion,
                                      String intent,
                                      String checkPlaceName,
                                      String checkPlaceRegion,
                                      String checkMode) {

    /** 保留默认路线现有调用方式；未识别当前路线核查时，核查地点字段为空。 */
    public ExtractEndpointsResponse(String originName, String destinationName,
                                    String originRegion, String destinationRegion,
                                    String intent) {
        this(originName, destinationName, originRegion, destinationRegion, intent,
                null, null, null);
    }

    /** 兼容已有调用；当前路线旧格式默认进入语义追问。 */
    public ExtractEndpointsResponse(String originName, String destinationName,
                                    String originRegion, String destinationRegion,
                                    String intent, String checkPlaceName,
                                    String checkPlaceRegion) {
        this(originName, destinationName, originRegion, destinationRegion, intent,
                checkPlaceName, checkPlaceRegion,
                "CURRENT_ROUTE_CHECK".equals(intent) ? "UNSPECIFIED" : null);
    }
}
