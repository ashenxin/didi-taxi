package com.sx.map.ai.dto;

/**
 * AI 客服意图提取结果。默认路线分支使用起终点字段，指定途经点分支使用途经地点字段，
 * 当前路线核查分支使用核查地点字段，天气分支使用城市和日期偏移字段。模型只负责提取
 * 乘客原话中的文字；地点身份、业务类型、坐标和天气事实仍由地图服务确认。
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
                                      String checkMode,
                                      String weatherPlaceName,
                                      String weatherPlaceRegion,
                                      Integer weatherDayOffset) {

    /** 保留默认路线现有调用方式；途经地点、核查地点与天气字段均为空。 */
    public ExtractEndpointsResponse(String originName, String destinationName,
                                    String originRegion, String destinationRegion,
                                    String intent) {
        this(originName, destinationName, originRegion, destinationRegion, intent,
                null, null, null, null, null, null, null, null);
    }

    /** 兼容已有调用；当前路线旧格式未指定动作，按复合关系核查。 */
    public ExtractEndpointsResponse(String originName, String destinationName,
                                    String originRegion, String destinationRegion,
                                    String intent, String checkPlaceName,
                                    String checkPlaceRegion) {
        this(originName, destinationName, originRegion, destinationRegion, intent,
                null, null, checkPlaceName, checkPlaceRegion, null, null, null, null);
    }

    /** 兼容已有当前路线核查调用；该分支不携带指定途经地点或天气字段。 */
    public ExtractEndpointsResponse(String originName, String destinationName,
                                    String originRegion, String destinationRegion,
                                    String intent, String checkPlaceName,
                                    String checkPlaceRegion, String checkMode) {
        this(originName, destinationName, originRegion, destinationRegion, intent,
                null, null, checkPlaceName, checkPlaceRegion, checkMode, null, null, null);
    }

    /** 兼容已有路线提取调用；天气字段为空。 */
    public ExtractEndpointsResponse(String originName, String destinationName,
                                    String originRegion, String destinationRegion,
                                    String intent, String viaPlaceName,
                                    String viaPlaceRegion, String checkPlaceName,
                                    String checkPlaceRegion, String checkMode) {
        this(originName, destinationName, originRegion, destinationRegion, intent,
                viaPlaceName, viaPlaceRegion, checkPlaceName, checkPlaceRegion, checkMode,
                null, null, null);
    }
}
