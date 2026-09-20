package com.sx.map.ai.controller;

import com.sx.map.ai.dto.DefaultRouteRequest;
import com.sx.map.ai.dto.ExtractEndpointsRequest;
import com.sx.map.ai.dto.ExtractEndpointsResponse;
import com.sx.map.ai.dto.RouteCardDto;
import com.sx.map.ai.dto.RouteConfirmRequest;
import com.sx.map.ai.dto.RouteConfirmResponse;
import com.sx.map.ai.dto.RoutePreparationRequest;
import com.sx.map.ai.dto.RoutePreparationResponse;
import com.sx.map.ai.dto.RoutePlaceChoiceRequest;
import com.sx.map.ai.dto.RouteReplayRequest;
import com.sx.map.ai.dto.RouteReplayResponse;
import com.sx.map.ai.model.PassengerRouteIntent;
import com.sx.map.ai.model.CurrentRouteCheckMode;
import com.sx.map.ai.model.PassengerRouteSnapshot.BusinessType;
import com.sx.map.ai.service.PassengerCurrentRouteCheckService;
import com.sx.map.ai.service.PassengerCurrentRouteCheckService.Result;
import com.sx.map.ai.service.PassengerRouteConfirmationService;
import com.sx.map.ai.service.PassengerRouteEndpointPreparationService;
import com.sx.map.ai.service.PassengerRouteEndpointPreparationService.PreparationResult;
import com.sx.map.ai.service.PassengerRouteEndpointPreparationService.PreparationStatus;
import com.sx.map.ai.service.PassengerRouteExtractionService;
import com.sx.map.ai.service.PassengerDefaultRouteService;
import com.sx.map.ai.service.RouteCardAssembler;
import com.sx.map.ai.service.PassengerRouteSnapshotCache;
import com.sx.map.service.CurrentRoutePassageService;
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

import java.time.Instant;
import java.util.List;

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
    private final PassengerCurrentRouteCheckService currentRouteCheckService;

    public AiRouteInternalController(PassengerRouteExtractionService extractionService,
                                     PassengerRouteEndpointPreparationService preparationService,
                                     PassengerRouteConfirmationService confirmationService,
                                     PassengerDefaultRouteService defaultRouteService,
                                     RouteCardAssembler routeCardAssembler,
                                     PassengerRouteSnapshotCache snapshotCache,
                                     PassengerCurrentRouteCheckService currentRouteCheckService) {
        this.extractionService = extractionService;
        this.preparationService = preparationService;
        this.confirmationService = confirmationService;
        this.defaultRouteService = defaultRouteService;
        this.routeCardAssembler = routeCardAssembler;
        this.snapshotCache = snapshotCache;
        this.currentRouteCheckService = currentRouteCheckService;
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
                    request.customerId(), request.conversationNo(), request.requestNo(),
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
                result.expiresAt(),
                placeChoicesView(result)));
    }

    @PostMapping("/confirm")
    public ResponseVo<RouteConfirmResponse> confirm(@RequestBody RouteConfirmRequest request) {
        PassengerRouteConfirmationService.ConfirmationResult result = confirmationService.confirm(
                request.customerId(), request.conversationNo(), request.latestConfirmationRequestNo(),
                request.conditionVersion(), request.replyText());
        return ResultUtil.success(new RouteConfirmResponse(result.status().name(), result.originalUserText()));
    }

    @PostMapping("/place-choice")
    public ResponseVo<RoutePreparationResponse> selectPlace(@RequestBody RoutePlaceChoiceRequest request) {
        PreparationResult result;
        try {
            result = preparationService.select(request.customerId(), request.conversationNo(),
                    request.taskNo(), request.placeChoiceId(), request.conditionVersion(),
                    request.choiceRequestVersion(), request.currentRequestVersion());
        } catch (AmapApiException e) {
            throw new AiRouteBusinessException("AI_POI_LOOKUP_FAILED", HttpStatus.SERVICE_UNAVAILABLE,
                    "地点查询暂时失败，请稍后重试", e);
        }
        if (result == null) {
            return ResultUtil.success(new RoutePreparationResponse("CONTEXT_UNAVAILABLE", null, null, null));
        }
        return ResultUtil.success(new RoutePreparationResponse(result.status().name(),
                feedbackView(result.origin()), feedbackView(result.destination()),
                result.expiresAt(), placeChoicesView(result)));
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

    /** routeRef 必须由 passenger-api 从本会话最新已持久化路线卡取得。 */
    @PostMapping("/current-check")
    public ResponseVo<CurrentRouteCheckResponse> currentRouteCheck(
            @RequestBody CurrentRouteCheckRequest request) {
        Result result;
        try {
            result = currentRouteCheckService.check(request.customerId(), request.conversationNo(),
                    request.routeRef(), request.requestVersion(), request.placeName(),
                    request.placeRegion(), parseCheckMode(request.checkMode()), request.originalUserText());
        } catch (AmapApiException e) {
            throw new AiRouteBusinessException("AI_POI_LOOKUP_FAILED", HttpStatus.SERVICE_UNAVAILABLE,
                    "地点查询暂时失败，请稍后重试", e);
        }
        return ResultUtil.success(currentRouteCheckResponse(result));
    }

    /** 选择只恢复 map-service 缓存的地点；客户端仍不能提交坐标、POI ID 或路线内容。 */
    @PostMapping("/current-check/place-choice")
    public ResponseVo<CurrentRouteCheckResponse> currentRouteCheckPlaceChoice(
            @RequestBody CurrentRouteCheckPlaceChoiceRequest request) {
        Result result = currentRouteCheckService.checkSelected(
                request.customerId(), request.conversationNo(), request.routeRef(),
                request.choiceRequestVersion(), request.currentRequestVersion(),
                request.taskNo(), request.placeChoiceId());
        return ResultUtil.success(currentRouteCheckResponse(result));
    }

    /** 含义选择只恢复服务端缓存的地点与路线绑定；客户端不能借此替换核查目标。 */
    @PostMapping("/current-check/mode-choice")
    public ResponseVo<CurrentRouteCheckResponse> currentRouteCheckModeChoice(
            @RequestBody CurrentRouteCheckModeChoiceRequest request) {
        Result result = currentRouteCheckService.checkSelectedMode(
                request.customerId(), request.conversationNo(), request.routeRef(),
                request.choiceRequestVersion(), request.currentRequestVersion(),
                request.taskNo(), request.checkMode());
        return ResultUtil.success(currentRouteCheckResponse(result));
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

    /** 地图身份只留在缓存中，内部响应也只发送不透明选项编号和展示摘要。 */
    private static RoutePreparationResponse.PlaceChoicesView placeChoicesView(PreparationResult result) {
        if (result.status() != PreparationStatus.NEEDS_PLACE_CHOICE) {
            return null;
        }
        return new RoutePreparationResponse.PlaceChoicesView(
                result.choiceTaskNo(), result.choiceRole().name(),
                result.choices().stream()
                        .map(choice -> new RoutePreparationResponse.PlaceChoiceView(
                                choice.placeChoiceId(), choice.name(), choice.displayType(),
                                choice.city(), choice.address(), choice.expiresAt()))
                        .toList(),
                result.expiresAt());
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

    private static CurrentRouteCheckMode parseCheckMode(String mode) {
        if (mode == null || mode.isBlank()) return CurrentRouteCheckMode.UNSPECIFIED;
        try {
            return CurrentRouteCheckMode.valueOf(mode);
        } catch (IllegalArgumentException e) {
            return CurrentRouteCheckMode.UNSPECIFIED;
        }
    }

    private static CurrentRouteCheckResponse currentRouteCheckResponse(Result result) {
        CurrentRouteCheckTargetView target = result.target() == null ? null
                : new CurrentRouteCheckTargetView(result.target().name(),
                result.target().displayType(), result.target().city(), result.target().address(),
                result.target().businessType().name());
        CurrentRouteCheckPlaceChoicesView choices = result.status()
                != PassengerCurrentRouteCheckService.Status.NEEDS_PLACE_CHOICE ? null
                : new CurrentRouteCheckPlaceChoicesView(result.choiceTaskNo(), "WAYPOINT",
                result.choices().stream().map(choice -> new CurrentRouteCheckPlaceChoiceView(
                        choice.placeChoiceId(), choice.name(), choice.displayType(), choice.city(),
                        choice.address(), choice.businessType().name(), choice.expiresAt())).toList(),
                result.expiresAt());
        CurrentRoutePassageService.Verdict verdict = result.verdict();
        CurrentRouteCheckModeChoicesView modeChoices = result.modeClarification() == null ? null
                : new CurrentRouteCheckModeChoicesView(
                result.modeClarification().taskNo(),
                target,
                result.modeClarification().choices().stream()
                        .map(choice -> new CurrentRouteCheckModeChoiceView(
                                choice.choiceId(), choice.label()))
                        .toList(),
                result.modeClarification().expiresAt());
        return new CurrentRouteCheckResponse(result.status().name(),
                verdict == null ? null : verdict.name(), result.reason(), result.detail(), target,
                result.checkMode() == null ? null : result.checkMode().name(), choices, modeChoices);
    }

    public record CurrentRouteCheckRequest(long customerId, String conversationNo, String routeRef,
                                           long requestVersion, String placeName, String placeRegion,
                                           String checkMode, String originalUserText) {
    }

    public record CurrentRouteCheckPlaceChoiceRequest(long customerId, String conversationNo,
                                                      String routeRef, long choiceRequestVersion,
                                                      long currentRequestVersion, String taskNo,
                                                      String placeChoiceId) {
    }

    public record CurrentRouteCheckModeChoiceRequest(long customerId, String conversationNo,
                                                     String routeRef, long choiceRequestVersion,
                                                     long currentRequestVersion, String taskNo,
                                                     String checkMode) {
    }

    public record CurrentRouteCheckResponse(String status, String verdict, String reason, String detail,
                                            CurrentRouteCheckTargetView target,
                                            String checkMode,
                                            CurrentRouteCheckPlaceChoicesView placeChoices,
                                            CurrentRouteCheckModeChoicesView modeChoices) {
    }

    public record CurrentRouteCheckTargetView(String name, String type, String city,
                                              String address, String businessType) {
    }

    public record CurrentRouteCheckPlaceChoicesView(String taskNo, String role,
                                                    List<CurrentRouteCheckPlaceChoiceView> choices,
                                                    Instant expiresAt) {
    }

    public record CurrentRouteCheckPlaceChoiceView(String placeChoiceId, String name, String type,
                                                   String city, String address, String businessType,
                                                   Instant expiresAt) {
    }

    public record CurrentRouteCheckModeChoicesView(String taskNo,
                                                   CurrentRouteCheckTargetView target,
                                                   List<CurrentRouteCheckModeChoiceView> choices,
                                                   Instant expiresAt) {
    }

    public record CurrentRouteCheckModeChoiceView(String choiceId, String label) {
    }
}
