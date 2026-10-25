package com.sx.map.ai.dto;

/**
 * 天气 skill 的内部查询请求。只接收城市文本和相对日期偏移，不接收坐标、adcode 或天气结论；
 * 城市到 adcode 的转换由 map-service 完成。
 */
public record WeatherForecastRequest(String requestNo,
                                     String placeName,
                                     String placeRegion,
                                     Integer dayOffset) {
}
