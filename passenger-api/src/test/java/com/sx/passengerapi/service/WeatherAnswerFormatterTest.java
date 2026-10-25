package com.sx.passengerapi.service;

import com.sx.passengerapi.client.dto.maproute.MapWeatherDtos.WeatherForecastResponse;
import com.sx.passengerapi.client.dto.maproute.MapWeatherDtos.WeatherForecastResponse.Forecast;
import com.sx.passengerapi.client.dto.maproute.MapWeatherDtos.WeatherForecastResponse.Location;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class WeatherAnswerFormatterTest {

    @Test
    void formatsDayNightWeekWindAndRainTip() {
        WeatherForecastResponse response = response(new Forecast(
                "2026-10-26", "1", "多云", "小雨", 24, 18, "东北", "1-3"));

        assertThat(WeatherAnswerFormatter.text(response, 1, "杭州"))
                .isEqualTo("杭州明天（10月26日，星期一）白天多云，夜间小雨，气温 18~24℃，"
                        + "东北风 1-3级。夜间有雨，出门记得带伞。");
        assertThat(WeatherAnswerFormatter.payload(response).toString())
                .contains("windDirection=东北风")
                .contains("windPower=1-3级")
                .contains("week=星期一");
    }

    @Test
    void sameDayNightWeatherUsesWholeDayAndSkipsMissingWind() {
        WeatherForecastResponse response = response(new Forecast(
                "2026-10-26", "星期一", "多云", "多云", 26, 20, null, null));

        assertThat(WeatherAnswerFormatter.text(response, 1, "杭州"))
                .isEqualTo("杭州明天（10月26日，星期一）全天多云，气温 20~26℃。");
    }

    @Test
    void missingForecastIsExplicitlyUnknown() {
        WeatherForecastResponse response = new WeatherForecastResponse(
                "OK", new Location("杭州市", null, null, null, null),
                null, null, null, "amap");

        assertThat(WeatherAnswerFormatter.text(response, 0, "杭州"))
                .isEqualTo("杭州今天天气信息暂缺，气温暂缺。");
    }

    private static WeatherForecastResponse response(Forecast forecast) {
        return new WeatherForecastResponse("OK",
                new Location("杭州市", "浙江省", "杭州市", "西湖区", "330100"),
                forecast, null, null, "amap");
    }
}
