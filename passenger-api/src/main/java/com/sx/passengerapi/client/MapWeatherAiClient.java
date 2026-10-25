package com.sx.passengerapi.client;

import com.sx.passengerapi.client.dto.maproute.MapWeatherDtos.WeatherForecastRequest;
import com.sx.passengerapi.client.dto.maproute.MapWeatherDtos.WeatherForecastResponse;
import com.sx.passengerapi.common.vo.ResponseVo;
import com.sx.passengerapi.config.MapInternalFeignConfiguration;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

/** map-service 天气 skill 内部接口客户端。 */
@FeignClient(name = "map-service", contextId = "mapWeatherAi",
        configuration = MapInternalFeignConfiguration.class)
public interface MapWeatherAiClient {

    @PostMapping("/api/v1/internal/ai/weather/forecast")
    ResponseVo<WeatherForecastResponse> forecast(@RequestBody WeatherForecastRequest request);
}
