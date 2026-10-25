package com.sx.map.ai.controller;

import com.sx.map.ai.dto.WeatherForecastRequest;
import com.sx.map.ai.dto.WeatherForecastResponse;
import com.sx.map.common.util.ResultUtil;
import com.sx.map.common.vo.ResponseVo;
import com.sx.map.exception.AiRouteBusinessException;
import com.sx.map.exception.AmapApiException;
import com.sx.map.service.AmapWeatherService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * AI 客服天气 skill 的内部接口，供 passenger-api 编排调用。
 * 鉴权由 MapInternalAuthFilter 统一处理（X-Internal-Service-Token）。
 */
@RestController
@RequestMapping("/api/v1/internal/ai/weather")
public class AiWeatherInternalController {

    private final AmapWeatherService weatherService;

    public AiWeatherInternalController(AmapWeatherService weatherService) {
        this.weatherService = weatherService;
    }

    @PostMapping("/forecast")
    public ResponseVo<WeatherForecastResponse> forecast(@RequestBody WeatherForecastRequest request) {
        try {
            return ResultUtil.success(weatherService.forecast(
                    request.placeName(), request.placeRegion(), request.dayOffset()));
        } catch (AmapApiException e) {
            throw new AiRouteBusinessException("AI_WEATHER_UNAVAILABLE",
                    HttpStatus.SERVICE_UNAVAILABLE, "天气服务暂时不可用，请稍后重试", e);
        }
    }
}
