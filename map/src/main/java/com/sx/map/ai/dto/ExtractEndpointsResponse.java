package com.sx.map.ai.dto;

/**
 * 路线意图提取结果。默认路线分支使用起终点字段，指定途经点分支使用途经地点字段，
 * 当前路线核查分支使用核查地点字段。模型只负责提取乘客原话中的文字；地点身份、
 * 业务类型与坐标仍由地图查询确认。
 */
public record ExtractEndpointsResponse(String originName,
                                      String destinationName,
                                      String originRegion,
                                      String destinationRegion,
                                      String intent,
                                      String viaPlaceName,
                                      String viaPlaceRegion,
                                      String checkPlaceName,
                                      String checkPlaceRegion,
                                      String checkMode) {

    /** 保留默认路线现有调用方式；途经地点与核查地点字段均为空。 */
    public ExtractEndpointsResponse(String originName, String destinationName,
                                    String originRegion, String destinationRegion,
                                    String intent) {
        this(originName, destinationName, originRegion, destinationRegion, intent,
                null, null, null, null, null);
    }

    /** 兼容已有调用；当前路线旧格式默认进入语义追问。 */
    public ExtractEndpointsResponse(String originName, String destinationName,
                                    String originRegion, String destinationRegion,
                                    String intent, String checkPlaceName,
                                    String checkPlaceRegion) {
        this(originName, destinationName, originRegion, destinationRegion, intent,
                null, null, checkPlaceName, checkPlaceRegion,
                "CURRENT_ROUTE_CHECK".equals(intent) ? "UNSPECIFIED" : null);
    }

    /** 兼容已有当前路线核查调用；该分支不携带指定途经地点。 */
    public ExtractEndpointsResponse(String originName, String destinationName,
                                    String originRegion, String destinationRegion,
                                    String intent, String checkPlaceName,
                                    String checkPlaceRegion, String checkMode) {
        this(originName, destinationName, originRegion, destinationRegion, intent,
                null, null, checkPlaceName, checkPlaceRegion, checkMode);
    }
}
