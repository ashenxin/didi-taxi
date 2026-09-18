package com.sx.map.ai.controller;

import com.sx.map.ai.dto.DefaultRouteRequest;
import com.sx.map.ai.dto.ExtractEndpointsRequest;
import com.sx.map.ai.dto.ExtractEndpointsResponse;
import com.sx.map.ai.dto.RouteCardDto;
import com.sx.map.ai.dto.RouteConfirmRequest;
import com.sx.map.ai.dto.RouteConfirmResponse;
import com.sx.map.ai.dto.RoutePreparationRequest;
import com.sx.map.ai.dto.RoutePreparationResponse;
import com.sx.map.ai.dto.RouteReplayRequest;
import com.sx.map.ai.dto.RouteReplayResponse;
import com.sx.map.ai.model.PassengerRouteIntent;
import com.sx.map.ai.service.PassengerRouteConfirmationService;
import com.sx.map.ai.service.PassengerRouteEndpointPreparationService;
import com.sx.map.ai.service.PassengerRouteExtractionService;
import com.sx.map.ai.service.PassengerDefaultRouteService;
import com.sx.map.ai.service.RouteCardAssembler;
import com.sx.map.ai.service.PassengerRouteSnapshotCache;
import com.sx.map.common.util.ResultUtil;
import com.sx.map.common.vo.ResponseVo;
import com.sx.map.exception.AiRouteBusinessException;
import com.sx.map.exception.AmapApiException;
import com.sx.map.exception.AmapRouteNotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * AI 客服路线能力内部接口，供 passenger-api 编排调用。
 * 鉴权由 MapInternalAuthFilter 统一处理（X-Internal-Service-Token）。
 * 地点身份、路线与时间只来自地图服务确定性能力，模型与客户端不能覆盖。
 */
@RestController
@RequestMapping("/api/v1/internal/ai/route")
public class AiRouteInternalController {

    private final PassengerRouteExtractionService extractionService;
    private final PassengerRouteEndpointPreparationService preparationService;
    private final PassengerRouteConfirmationService confirmationService;
    private final PassengerDefaultRouteService defaultRouteService;
    private final RouteCardAssembler routeCardAssembler;
    private final PassengerRouteSnapshotCache snapshotCache;

    public AiRouteInternalController(PassengerRouteExtractionService extractionService,
                                     PassengerRouteEndpointPreparationService preparationService,
                                     PassengerRouteConfirmationService confirmationService,
                                     PassengerDefaultRouteService defaultRouteService,
                                     RouteCardAssembler routeCardAssembler,
                                     PassengerRouteSnapshotCache snapshotCache) {
        this.extractionService = extractionService;
        this.preparationService = preparationService;
        this.confirmationService = confirmationService;
        this.defaultRouteService = defaultRouteService;
        this.routeCardAssembler = routeCardAssembler;
        this.snapshotCache = snapshotCache;
    }

    @PostMapping("/extract")
    public ResponseVo<ExtractEndpointsResponse> extract(@RequestBody ExtractEndpointsRequest request) {
        return ResultUtil.success(extractionService.extract(request.userText()));
    }

    @PostMapping("/prepare")
    public ResponseVo<RoutePreparationResponse> prepare(@RequestBody RoutePreparationRequest request) {
        PassengerRouteEndpointPreparationService.PreparationResult result;
        try {
            result = preparationService.prepare(
                    request.customerId(), request.conversationNo(), request.confirmationRequestNo(),
                    request.conditionVersion(), request.originName(), request.originRegion(),
                    request.destinationName(), request.destinationRegion(),
                    parseIntent(request.intent()), request.originalUserText());
        } catch (AmapApiException e) {
            throw new AiRouteBusinessException("AI_POI_LOOKUP_FAILED", HttpStatus.SERVICE_UNAVAILABLE,
                    "地点查询暂时失败，请稍后重试", e);
        }
        return ResultUtil.success(new RoutePreparationResponse(
                result.status().name(),
                feedbackView(result.origin()),
                feedbackView(result.destination()),
                result.expiresAt()));
    }

    @PostMapping("/confirm")
    public ResponseVo<RouteConfirmResponse> confirm(@RequestBody RouteConfirmRequest request) {
        PassengerRouteConfirmationService.ConfirmationResult result = confirmationService.confirm(
                request.customerId(), request.conversationNo(), request.latestConfirmationRequestNo(),
                request.conditionVersion(), request.replyText());
        return ResultUtil.success(new RouteConfirmResponse(result.status().name(), result.originalUserText()));
    }

    @PostMapping("/default")
    public ResponseVo<RouteCardDto> defaultRoute(@RequestBody DefaultRouteRequest request) {
        try {
            return ResultUtil.success(routeCardAssembler.fromSnapshot(defaultRouteService.createAndCache(
                    request.customerId(), request.conversationNo(), request.conditionVersion())));
        } catch (AmapRouteNotFoundException e) {
            throw new AiRouteBusinessException("AI_ROUTE_NOT_FOUND", HttpStatus.UNPROCESSABLE_ENTITY,
                    "当前起终点未找到可达路线，请更换地点后重试", e);
        } catch (AmapApiException e) {
            throw new AiRouteBusinessException("AI_MAP_UNAVAILABLE", HttpStatus.SERVICE_UNAVAILABLE,
                    "地图算路暂时不可用，请稍后重试", e);
        }
    }

    @PostMapping("/replay")
    public ResponseVo<RouteReplayResponse> replay(@RequestBody RouteReplayRequest request) {
        RouteCardDto card = snapshotCache.findForReplay(
                        request.routeRef(), request.customerId(), request.conversationNo())
                .map(routeCardAssembler::fromSnapshot).orElse(null);
        return ResultUtil.success(new RouteReplayResponse(card));
    }

    private static RoutePreparationResponse.EndpointFeedbackView feedbackView(
            PassengerRouteEndpointPreparationService.EndpointFeedback feedback) {
        return new RoutePreparationResponse.EndpointFeedbackView(
                feedback.status().name(),
                feedback.candidates().stream()
                        .map(candidate -> new RoutePreparationResponse.CandidateView(
                                candidate.name(), candidate.type(), candidate.city(),
                                candidate.district(), candidate.address()))
                        .toList());
    }

    /** 模型输出不可信：未知意图回落为默认路线规划，分支决策由编排层收敛。 */
    private static PassengerRouteIntent parseIntent(String intent) {
        if (intent == null || intent.isBlank()) {
            return PassengerRouteIntent.DEFAULT_ROUTE;
        }
        try {
            return PassengerRouteIntent.valueOf(intent);
        } catch (IllegalArgumentException e) {
            return PassengerRouteIntent.DEFAULT_ROUTE;
        }
    }
}
