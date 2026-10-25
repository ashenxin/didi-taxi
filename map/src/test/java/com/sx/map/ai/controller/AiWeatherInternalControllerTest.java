package com.sx.map.ai.controller;

import com.sx.map.ai.dto.WeatherForecastResponse;
import com.sx.map.common.exception.GlobalExceptionHandler;
import com.sx.map.exception.AmapApiException;
import com.sx.map.service.AmapWeatherService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AiWeatherInternalControllerTest {

    private final AmapWeatherService weatherService = mock(AmapWeatherService.class);
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new AiWeatherInternalController(weatherService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void forecastReturnsStructuredWeather() throws Exception {
        when(weatherService.forecast("杭州", null, 1)).thenReturn(WeatherForecastResponse.ok(
                new WeatherForecastResponse.Location("杭州市", "浙江省", "杭州市", "西湖区", "330100"),
                new WeatherForecastResponse.Forecast("2026-10-26", "星期一", "多云", "小雨",
                        24, 18, "东南风", "3级")));

        mvc.perform(post("/api/v1/internal/ai/weather/forecast")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestNo":"REQ-1","placeName":"杭州","placeRegion":null,"dayOffset":1}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.status").value("OK"))
                .andExpect(jsonPath("$.data.location.adcode").value("330100"))
                .andExpect(jsonPath("$.data.forecast.dayTemp").value(24))
                .andExpect(jsonPath("$.data.forecast.nightTemp").value(18));
    }

    @Test
    void amapFailureReturns503WithStableAiErrorCode() throws Exception {
        when(weatherService.forecast("杭州", null, 1))
                .thenThrow(new AmapApiException("timeout"));

        mvc.perform(post("/api/v1/internal/ai/weather/forecast")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestNo":"REQ-1","placeName":"杭州","placeRegion":null,"dayOffset":1}
                                """))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("X-Ai-Error-Code", "AI_WEATHER_UNAVAILABLE"))
                .andExpect(jsonPath("$.code").value(503));
    }
}
