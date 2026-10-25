package com.sx.map.service;

import com.sx.map.ai.dto.WeatherForecastResponse;
import com.sx.map.config.AmapProperties;
import com.sx.map.exception.AmapApiException;
import com.sx.map.model.dto.GeocodeDemoResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.LocalDate;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class AmapWeatherServiceTest {

    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");

    private AmapGeocodeService geocodeService;
    private AmapProperties properties;
    private MockRestServiceServer server;
    private AmapWeatherService service;

    @BeforeEach
    void setUp() {
        geocodeService = mock(AmapGeocodeService.class);
        properties = new AmapProperties();
        properties.setKey("test-key");
        properties.setBaseUrl("https://restapi.amap.com");
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        service = new AmapWeatherService(builder.build(), properties, geocodeService);
    }

    @Test
    void returnsSelectedForecastDayByDate() {
        when(geocodeService.geocode("杭州", null)).thenReturn(geocode());
        String today = LocalDate.now(SHANGHAI).toString();
        String tomorrow = LocalDate.now(SHANGHAI).plusDays(1).toString();
        server.expect(requestTo(org.hamcrest.Matchers.allOf(
                        org.hamcrest.Matchers.containsString("/v3/weather/weatherInfo"),
                        org.hamcrest.Matchers.containsString("city=330100"),
                        org.hamcrest.Matchers.containsString("extensions=all"))))
                .andRespond(withSuccess(forecastJson(today, tomorrow), MediaType.APPLICATION_JSON));

        WeatherForecastResponse response = service.forecast("杭州", null, 1);

        assertThat(response.status()).isEqualTo(WeatherForecastResponse.STATUS_OK);
        assertThat(response.forecast().date()).isEqualTo(tomorrow);
        assertThat(response.forecast().dayWeather()).isEqualTo("多云");
        assertThat(response.forecast().nightWeather()).isEqualTo("小雨");
        assertThat(response.forecast().dayTemp()).isEqualTo(24);
        assertThat(response.forecast().nightTemp()).isEqualTo(18);
        assertThat(response.forecast().windDirection()).isEqualTo("东北风");
        assertThat(response.forecast().windPower()).isEqualTo("1-3级");
        assertThat(response.location().name()).isEqualTo("杭州市");
        assertThat(response.location().adcode()).isEqualTo("330100");
        server.verify();
    }

    @Test
    void blankPlaceReturnsPlaceRequiredWithoutCallingAmap() {
        WeatherForecastResponse response = service.forecast(" ", null, 1);

        assertThat(response.status()).isEqualTo(WeatherForecastResponse.STATUS_PLACE_REQUIRED);
        verifyNoInteractions(geocodeService);
        server.verify();
    }

    @Test
    void unsupportedDayOffsetReturnsDateOutOfRangeWithoutCallingAmap() {
        WeatherForecastResponse response = service.forecast("杭州", null, 4);

        assertThat(response.status()).isEqualTo(WeatherForecastResponse.STATUS_DATE_OUT_OF_RANGE);
        verifyNoInteractions(geocodeService);
        server.verify();
    }

    @Test
    void unknownPlaceReturnsPlaceNotFound() {
        when(geocodeService.geocode("不存在市", null))
                .thenThrow(new AmapApiException("高德未返回地理编码结果（geocodes 为空）"));

        WeatherForecastResponse response = service.forecast("不存在市", null, 0);

        assertThat(response.status()).isEqualTo(WeatherForecastResponse.STATUS_PLACE_NOT_FOUND);
        assertThat(response.detail()).contains("不存在市");
    }

    @Test
    void amapBusinessErrorIsUpstreamFailure() {
        when(geocodeService.geocode("杭州", null)).thenReturn(geocode());
        server.expect(requestTo(org.hamcrest.Matchers.containsString("/v3/weather/weatherInfo")))
                .andRespond(withSuccess("""
                        {"status":"0","info":"INVALID_USER_KEY","infocode":"10001"}
                        """, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> service.forecast("杭州", null, 0))
                .isInstanceOf(AmapApiException.class)
                .hasMessageContaining("INVALID_USER_KEY");
    }

    @Test
    void amapHttpErrorIsUpstreamFailure() {
        when(geocodeService.geocode("杭州", null)).thenReturn(geocode());
        server.expect(requestTo(org.hamcrest.Matchers.containsString("/v3/weather/weatherInfo")))
                .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));

        assertThatThrownBy(() -> service.forecast("杭州", null, 0))
                .isInstanceOf(AmapApiException.class)
                .hasMessageContaining("HTTP 500");
    }

    @Test
    void missingKeyFailsBeforeCallingAmap() {
        properties.setKey("");

        assertThatThrownBy(() -> service.forecast("杭州", null, 0))
                .isInstanceOf(AmapApiException.class)
                .hasMessageContaining("未配置高德 Key");
        verifyNoInteractions(geocodeService);
        server.verify();
    }

    private static GeocodeDemoResponse geocode() {
        GeocodeDemoResponse response = new GeocodeDemoResponse();
        response.setAdcode("330100");
        response.setProvince("浙江省");
        response.setCityName("杭州市");
        response.setDistrict("西湖区");
        return response;
    }

    private static String forecastJson(String today, String tomorrow) {
        return """
                {"status":"1","info":"OK","infocode":"10000","forecasts":[
                  {"city":"杭州市","province":"浙江省","adcode":"330100","casts":[
                    {"date":"%s","week":"6","dayweather":"晴","nightweather":"晴",
                     "daytemp":"25","nighttemp":"17","daywind":"东北","daypower":"1-3"},
                    {"date":"%s","week":"7","dayweather":"多云","nightweather":"小雨",
                     "daytemp":"24","nighttemp":"18","daywind":"东北","daypower":"1-3"}
                  ]}
                ]}
                """.formatted(today, tomorrow);
    }
}
