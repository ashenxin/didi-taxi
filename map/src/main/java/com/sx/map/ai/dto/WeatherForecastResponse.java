package com.sx.map.ai.dto;

/**
 * 天气 skill 的结构化结果。地图服务只返回事实和稳定状态，不生成乘客话术；
 * 话术由 passenger-api 根据 status 和 forecast 组织。
 */
public record WeatherForecastResponse(String status,
                                      Location location,
                                      Forecast forecast,
                                      String reason,
                                      String detail,
                                      String source) {

    public static final String STATUS_OK = "OK";
    public static final String STATUS_PLACE_REQUIRED = "PLACE_REQUIRED";
    public static final String STATUS_PLACE_NOT_FOUND = "PLACE_NOT_FOUND";
    public static final String STATUS_DATE_OUT_OF_RANGE = "DATE_OUT_OF_RANGE";

    public static WeatherForecastResponse ok(Location location, Forecast forecast) {
        return new WeatherForecastResponse(STATUS_OK, location, forecast, null, null, "amap");
    }

    public static WeatherForecastResponse placeRequired() {
        return new WeatherForecastResponse(STATUS_PLACE_REQUIRED, null, null,
                "PLACE_REQUIRED", "请告诉我城市或区县名称", null);
    }

    public static WeatherForecastResponse placeNotFound(String detail) {
        return new WeatherForecastResponse(STATUS_PLACE_NOT_FOUND, null, null,
                "PLACE_NOT_FOUND", detail, null);
    }

    public static WeatherForecastResponse dateOutOfRange(String detail) {
        return new WeatherForecastResponse(STATUS_DATE_OUT_OF_RANGE, null, null,
                "DATE_OUT_OF_RANGE", detail, null);
    }

    /** 仅返回展示所需的地点摘要，不包含坐标或原始 POI。 */
    public record Location(String name, String province, String city, String district, String adcode) {
    }

    /** 单日预报；温度单位为摄氏度。 */
    public record Forecast(String date,
                           String week,
                           String dayWeather,
                           String nightWeather,
                           Integer dayTemp,
                           Integer nightTemp,
                           String windDirection,
                           String windPower) {
    }
}
