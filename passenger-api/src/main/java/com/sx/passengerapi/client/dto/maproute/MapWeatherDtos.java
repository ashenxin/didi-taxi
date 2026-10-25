package com.sx.passengerapi.client.dto.maproute;

/**
 * map-service 天气 skill 的内部契约。只含城市文本和相对日期偏移，
 * 不接受客户端提交 adcode、坐标或天气结论。
 */
public final class MapWeatherDtos {

    private MapWeatherDtos() {
    }

    public record WeatherForecastRequest(String requestNo,
                                         String placeName,
                                         String placeRegion,
                                         Integer dayOffset) {
    }

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

        public record Location(String name, String province, String city,
                               String district, String adcode) {
        }

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
}
