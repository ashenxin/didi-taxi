package com.sx.passengerapi.client;

import com.sx.passengerapi.client.dto.maproute.MapRouteDtos.DefaultRouteRequest;
import com.sx.passengerapi.client.dto.maproute.MapRouteDtos.ExtractEndpointsRequest;
import com.sx.passengerapi.client.dto.maproute.MapRouteDtos.ExtractEndpointsResponse;
import com.sx.passengerapi.client.dto.maproute.MapRouteDtos.RouteCardDto;
import com.sx.passengerapi.client.dto.maproute.MapRouteDtos.RouteConfirmRequest;
import com.sx.passengerapi.client.dto.maproute.MapRouteDtos.RouteConfirmResponse;
import com.sx.passengerapi.client.dto.maproute.MapRouteDtos.RoutePreparationRequest;
import com.sx.passengerapi.client.dto.maproute.MapRouteDtos.RoutePreparationResponse;
import com.sx.passengerapi.client.dto.maproute.MapRouteDtos.RouteReplayRequest;
import com.sx.passengerapi.client.dto.maproute.MapRouteDtos.RouteReplayResponse;
import com.sx.passengerapi.common.vo.ResponseVo;
import com.sx.passengerapi.config.MapInternalFeignConfiguration;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

/** map-service AI 路线能力内部接口客户端。 */
@FeignClient(name = "map-service", contextId = "mapRouteAi",
        configuration = MapInternalFeignConfiguration.class)
public interface MapRouteAiClient {

    @PostMapping("/api/v1/internal/ai/route/extract")
    ResponseVo<ExtractEndpointsResponse> extract(@RequestBody ExtractEndpointsRequest request);

    @PostMapping("/api/v1/internal/ai/route/prepare")
    ResponseVo<RoutePreparationResponse> prepare(@RequestBody RoutePreparationRequest request);

    @PostMapping("/api/v1/internal/ai/route/confirm")
    ResponseVo<RouteConfirmResponse> confirm(@RequestBody RouteConfirmRequest request);

    @PostMapping("/api/v1/internal/ai/route/default")
    ResponseVo<RouteCardDto> defaultRoute(@RequestBody DefaultRouteRequest request);

    @PostMapping("/api/v1/internal/ai/route/replay")
    ResponseVo<RouteReplayResponse> replay(@RequestBody RouteReplayRequest request);
}
