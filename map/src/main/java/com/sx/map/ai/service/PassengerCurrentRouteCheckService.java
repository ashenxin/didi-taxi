package com.sx.map.ai.service;

import com.sx.map.ai.model.PassengerRouteIntent;
import com.sx.map.ai.model.CurrentRouteCheckMode;
import com.sx.map.ai.model.PassengerRouteSnapshot;
import com.sx.map.ai.model.PassengerRouteSnapshot.BusinessType;
import com.sx.map.ai.model.PassengerRouteSnapshot.GeoPoint;
import com.sx.map.ai.model.PassengerRouteSnapshot.Place;
import com.sx.map.ai.service.PassengerRoutePlaceChoiceCache.ChoiceRole;
import com.sx.map.ai.service.PassengerRoutePlaceChoiceCache.ChoiceSummary;
import com.sx.map.ai.service.PassengerRoutePlaceChoiceCache.ChoiceTask;
import com.sx.map.ai.service.PassengerRoutePlaceChoiceCache.PlaceCandidate;
import com.sx.map.ai.service.PassengerRoutePlaceChoiceCache.SelectedChoice;
import com.sx.map.ai.service.PassengerCurrentRouteClarificationCache.ClarificationTask;
import com.sx.map.ai.service.PassengerCurrentRouteClarificationCache.SelectedMode;
import com.sx.map.ai.tool.PassengerRoutePoiTools;
import com.sx.map.model.dto.AmapPoiCandidate;
import com.sx.map.model.dto.Point;
import com.sx.map.service.CurrentRoutePassageService;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 编排 AI 客服对“当前这条路线是否经过指定地点”的核查。
 *
 * 当前路线只能由 passenger-api 从本会话最新已持久化的路线卡取得 routeRef；本类随后让
 * {@link PassengerRouteSnapshotCache} 重新校验乘客、会话、已确认起终点和有效期。地点只从
 * 高德 POI 查询或 map-service 的短时地点选项恢复。模型和客户端不能提交坐标、折线、POI ID
 * 或经过结论。
 *
 * 本类只核查已有路线，不重新规划路线，也不把另一条可经过目标地点的路线当作当前路线。
 */
@Service
public class PassengerCurrentRouteCheckService {
    private static final int MAX_CHOICES = 10;
    private static final int MAX_USER_TEXT_LENGTH = 1000;

    private final PassengerRouteSnapshotCache snapshotCache;
    private final PassengerRoutePoiTools poiTools;
    private final PassengerRoutePlaceChoiceCache placeChoiceCache;
    private final PassengerCurrentRouteClarificationCache clarificationCache;
    private final CurrentRoutePassageService passageService;

    public PassengerCurrentRouteCheckService(PassengerRouteSnapshotCache snapshotCache,
                                             PassengerRoutePoiTools poiTools,
                                             PassengerRoutePlaceChoiceCache placeChoiceCache,
                                             PassengerCurrentRouteClarificationCache clarificationCache,
                                             CurrentRoutePassageService passageService) {
        this.snapshotCache = snapshotCache;
        this.poiTools = poiTools;
        this.placeChoiceCache = placeChoiceCache;
        this.clarificationCache = clarificationCache;
        this.passageService = passageService;
    }

    /**
     * 首次核查指定地点。名称完全一致的唯一可信候选可直接核查；重名或地图规范名称发生变化时，
     * 只生成短时地点选项等待乘客确认，不能把地图的模糊匹配静默升级为乘客的真实意图。
     */
    public Result check(long customerId,
                        String conversationNo,
                        String routeRef,
                        long requestVersion,
                        String placeName,
                        String placeRegion,
                        CurrentRouteCheckMode checkMode,
                        String originalUserText) {
        if (requestVersion <= 0) {
            throw new IllegalArgumentException("当前路线核查缺少请求版本");
        }
        String userText = requiredUserText(originalUserText);
        CurrentRouteCheckMode requestedMode = checkMode == null
                ? CurrentRouteCheckMode.UNSPECIFIED : checkMode;
        PassengerRouteSnapshot snapshot = currentSnapshot(routeRef, customerId, conversationNo);
        if (snapshot == null) {
            return Result.contextUnavailable();
        }

        List<AmapPoiCandidate> rawCandidates = poiTools.searchCurrentRouteCheckCandidates(
                placeName, placeRegion);
        if (rawCandidates == null || rawCandidates.isEmpty()) {
            return Result.placeNotFound();
        }
        List<Candidate> candidates = rawCandidates.stream()
                .map(this::trustedCandidate)
                .filter(Objects::nonNull)
                .toList();
        if (candidates.isEmpty()) {
            return Result.placeUnverifiable("地图结果不属于收费站、服务区或加油站，或缺少可核查的位置");
        }
        if (candidates.size() > MAX_CHOICES) {
            return Result.placeUnverifiable("同名地点过多，请补充城市或更具体的位置");
        }

        PassengerRouteSnapshot latest = refresh(snapshot);
        if (latest == null) {
            return Result.contextUnavailable();
        }
        // 地图规范名称与乘客原词不一致时，即使只有一个结果也必须确认。
        // 这既允许“三墩高速路口”解析为“三墩收费站”，也防止把“五常”静默升级成收费站。
        if (candidates.size() == 1 && samePlaceName(placeName, candidates.getFirst().raw().getName())) {
            Candidate candidate = candidates.getFirst();
            return checkOrClarify(latest, requestVersion, userText, requestedMode, candidate.cached());
        }

        ChoiceTask task = placeChoiceCache.saveCurrentRouteCheck(customerId, latest.conversationNo(),
                latest.conditionVersion(), requestVersion, userText,
                latest.origin(), latest.destination(),
                requestedMode,
                candidates.stream().map(Candidate::cached).toList());
        return Result.placeChoices(task.taskNo(), task.summaries(), task.expiresAt());
    }

    /** 兼容旧内部调用；未提供语义时必须进入追问。 */
    public Result check(long customerId, String conversationNo, String routeRef,
                        long requestVersion, String placeName, String placeRegion,
                        String originalUserText) {
        return check(customerId, conversationNo, routeRef, requestVersion, placeName,
                placeRegion, CurrentRouteCheckMode.UNSPECIFIED, originalUserText);
    }

    /**
     * 从短时选项恢复乘客选中的地点，再核查同一个 routeRef。routeRef 仍须来自服务端保存的路线卡；
     * 选项只确定地点身份，不能授权读取路线，也不能覆盖路线所属的起终点。
     */
    public Result checkSelected(long customerId,
                                String conversationNo,
                                String routeRef,
                                long choiceRequestVersion,
                                long currentRequestVersion,
                                String taskNo,
                                String placeChoiceId) {
        if (choiceRequestVersion <= 0 || currentRequestVersion <= choiceRequestVersion) {
            return Result.contextUnavailable();
        }
        PassengerRouteSnapshot snapshot = currentSnapshot(routeRef, customerId, conversationNo);
        if (snapshot == null) {
            return Result.contextUnavailable();
        }
        SelectedChoice selected = placeChoiceCache.select(taskNo, placeChoiceId, customerId,
                conversationNo, snapshot.conditionVersion(), choiceRequestVersion).orElse(null);
        if (selected == null || selected.role() != ChoiceRole.WAYPOINT
                || selected.intent() != PassengerRouteIntent.CURRENT_ROUTE_CHECK
                || !snapshot.origin().equals(selected.origin())
                || !snapshot.destination().equals(selected.destination())) {
            return Result.contextUnavailable();
        }
        PassengerRouteSnapshot latest = refresh(snapshot);
        if (latest == null || !latest.origin().equals(selected.origin())
                || !latest.destination().equals(selected.destination())) {
            return Result.contextUnavailable();
        }
        return checkOrClarify(latest, currentRequestVersion, selected.originalUserText(),
                selected.checkMode(), selected.candidate());
    }

    /** 恢复含义选项时仍重新验证当前路线；选项不能携带或覆盖地点与路线事实。 */
    public Result checkSelectedMode(long customerId,
                                    String conversationNo,
                                    String routeRef,
                                    long choiceRequestVersion,
                                    long currentRequestVersion,
                                    String taskNo,
                                    String selectedMode) {
        PassengerRouteSnapshot snapshot = currentSnapshot(routeRef, customerId, conversationNo);
        if (snapshot == null) return Result.contextUnavailable();
        SelectedMode selected = clarificationCache.select(taskNo, selectedMode, customerId,
                conversationNo, routeRef, choiceRequestVersion, currentRequestVersion).orElse(null);
        if (selected == null || selected.conditionVersion() != snapshot.conditionVersion()) {
            return Result.contextUnavailable();
        }
        PassengerRouteSnapshot latest = refresh(snapshot);
        if (latest == null || latest.conditionVersion() != selected.conditionVersion()) {
            return Result.contextUnavailable();
        }
        return checked(passageService.check(latest, toRawCandidate(selected.candidate()), selected.mode()),
                selected.candidate(), selected.mode());
    }

    private PassengerRouteSnapshot currentSnapshot(String routeRef, long customerId,
                                                    String conversationNo) {
        return snapshotCache.findForReplay(routeRef, customerId, conversationNo).orElse(null);
    }

    /** 地图查询或乘客选择期间条件可能变化，产出结论前再次读取同一路线。 */
    private PassengerRouteSnapshot refresh(PassengerRouteSnapshot snapshot) {
        return snapshotCache.find(snapshot.routeRef(), snapshot.customerId(),
                snapshot.conversationNo(), snapshot.conditionVersion()).orElse(null);
    }

    private Result checkOrClarify(PassengerRouteSnapshot snapshot,
                                  long requestVersion,
                                  String originalUserText,
                                  CurrentRouteCheckMode checkMode,
                                  PlaceCandidate candidate) {
        if (checkMode != CurrentRouteCheckMode.UNSPECIFIED) {
            return checked(passageService.check(snapshot, toRawCandidate(candidate), checkMode),
                    candidate, checkMode);
        }
        List<CurrentRouteCheckMode> modes = clarificationModes(candidate);
        ClarificationTask task = clarificationCache.save(snapshot, requestVersion,
                originalUserText, candidate, modes);
        TargetSummary target = targetSummary(candidate);
        List<ModeChoiceSummary> choices = modes.stream()
                .map(mode -> new ModeChoiceSummary(mode.name(), modeLabel(mode, candidate.businessType())))
                .toList();
        return Result.modeChoices(new ModeClarification(task.taskNo(), target, choices, task.expiresAt()));
    }

    private static Result checked(CurrentRoutePassageService.CheckResult check,
                                  PlaceCandidate candidate,
                                  CurrentRouteCheckMode checkMode) {
        TargetSummary target = targetSummary(candidate);
        return new Result(Status.CHECKED, check.verdict(), check.reason(), check.detail(),
                target, checkMode, null, List.of(), null, null);
    }

    private static TargetSummary targetSummary(PlaceCandidate candidate) {
        TargetSummary target = new TargetSummary(candidate.place().name(), candidate.displayType(),
                candidate.place().city(), candidate.place().address(), candidate.businessType());
        return target;
    }

    private static List<CurrentRouteCheckMode> clarificationModes(PlaceCandidate candidate) {
        if (candidate.businessType() != BusinessType.TOLL_STATION) {
            return List.of(CurrentRouteCheckMode.PASS_NEARBY, CurrentRouteCheckMode.VISIT_PLACE);
        }
        String name = candidate.place().name();
        if (name.contains("出口")) {
            return List.of(CurrentRouteCheckMode.PASS_NEARBY, CurrentRouteCheckMode.USE_EXIT);
        }
        if (name.contains("入口")) {
            return List.of(CurrentRouteCheckMode.PASS_NEARBY, CurrentRouteCheckMode.ENTER_HIGHWAY);
        }
        return List.of(CurrentRouteCheckMode.PASS_NEARBY, CurrentRouteCheckMode.VISIT_PLACE);
    }

    private static String modeLabel(CurrentRouteCheckMode mode, BusinessType businessType) {
        return switch (mode) {
            case PASS_NEARBY -> "沿路线经过该地点附近";
            case USE_EXIT -> "从该出口驶出高速";
            case ENTER_HIGHWAY -> "从该入口驶入高速";
            case VISIT_PLACE -> businessType == BusinessType.TOLL_STATION
                    ? "实际通过该收费站" : "实际进入该地点";
            case UNSPECIFIED -> throw new IllegalArgumentException("不能展示未明确的核查含义");
        };
    }

    private Candidate trustedCandidate(AmapPoiCandidate raw) {
        BusinessType businessType = poiTools.currentRouteCheckBusinessType(raw);
        if (raw == null || !hasText(raw.getName()) || !validPoint(raw.getCenterPoint())
                || !hasText(raw.getCity()) && !hasText(raw.getAddress())
                || businessType == null) {
            return null;
        }
        List<GeoPoint> navigationPoints = new ArrayList<>(2);
        if (validPoint(raw.getEntrancePoint())) {
            navigationPoints.add(toGeoPoint(raw.getEntrancePoint()));
        }
        if (validPoint(raw.getExitPoint())) {
            navigationPoints.add(toGeoPoint(raw.getExitPoint()));
        }
        if (businessType != BusinessType.TOLL_STATION && navigationPoints.isEmpty()) {
            return null;
        }
        Place place;
        try {
            place = new Place(blankToNull(raw.getPoiId()), raw.getName().strip(),
                    blankToNull(raw.getAddress()), blankToNull(raw.getCity()),
                    toGeoPoint(raw.getCenterPoint()));
        } catch (IllegalArgumentException e) {
            return null;
        }
        PlaceCandidate cached = new PlaceCandidate(place, businessType, navigationPoints,
                blankToNull(raw.getType()));
        return new Candidate(raw, cached);
    }

    private static boolean samePlaceName(String requested, String candidate) {
        return hasText(requested) && hasText(candidate)
                && requested.strip().equalsIgnoreCase(candidate.strip());
    }

    private static AmapPoiCandidate toRawCandidate(PlaceCandidate cached) {
        AmapPoiCandidate raw = new AmapPoiCandidate();
        raw.setPoiId(cached.place().placeId());
        raw.setName(cached.place().name());
        raw.setAddress(cached.place().address());
        raw.setCity(cached.place().city());
        raw.setType(cached.displayType());
        raw.setTypeCode(typeCode(cached.businessType()));
        raw.setCenterPoint(toPoint(cached.place().coordinate()));
        if (!cached.navigationPoints().isEmpty()) {
            raw.setEntrancePoint(toPoint(cached.navigationPoints().getFirst()));
        }
        if (cached.navigationPoints().size() > 1) {
            raw.setExitPoint(toPoint(cached.navigationPoints().get(1)));
        }
        return raw;
    }

    private static String typeCode(BusinessType type) {
        return switch (type) {
            case TOLL_STATION -> "180200";
            case SERVICE_AREA -> "180300";
            case GAS_STATION -> "010100";
        };
    }

    private static GeoPoint toGeoPoint(Point point) {
        return new GeoPoint(point.getLng(), point.getLat());
    }

    private static Point toPoint(GeoPoint point) {
        Point result = new Point();
        result.setLng(point.longitude());
        result.setLat(point.latitude());
        return result;
    }

    private static boolean validPoint(Point point) {
        return point != null && point.getLng() != null && point.getLat() != null
                && Double.isFinite(point.getLng()) && Double.isFinite(point.getLat());
    }

    private static String requiredUserText(String value) {
        if (!hasText(value)) {
            throw new IllegalArgumentException("当前路线核查缺少原始提问");
        }
        String normalized = value.strip();
        if (normalized.length() > MAX_USER_TEXT_LENGTH) {
            throw new IllegalArgumentException("原始提问超过长度限制");
        }
        return normalized;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static String blankToNull(String value) {
        return hasText(value) ? value.strip() : null;
    }

    public enum Status {
        CHECKED,
        NEEDS_PLACE_CHOICE,
        NEEDS_CHECK_MODE_CHOICE,
        PLACE_NOT_FOUND,
        PLACE_UNVERIFIABLE,
        CONTEXT_UNAVAILABLE
    }

    /** 返回编排层的结构化结果不包含路线折线、坐标或 POI ID。 */
    public record Result(Status status,
                         CurrentRoutePassageService.Verdict verdict,
                         String reason,
                         String detail,
                         TargetSummary target,
                         CurrentRouteCheckMode checkMode,
                         String choiceTaskNo,
                         List<ChoiceSummary> choices,
                         Instant expiresAt,
                         ModeClarification modeClarification) {
        public Result(Status status, CurrentRoutePassageService.Verdict verdict,
                      String reason, String detail, TargetSummary target,
                      String choiceTaskNo, List<ChoiceSummary> choices, Instant expiresAt) {
            this(status, verdict, reason, detail, target,
                    status == Status.CHECKED ? CurrentRouteCheckMode.VISIT_PLACE : null,
                    choiceTaskNo, choices, expiresAt, null);
        }

        public Result {
            Objects.requireNonNull(status, "当前路线核查状态不能为空");
            choices = choices == null ? List.of() : List.copyOf(choices);
            if (status == Status.CHECKED && (verdict == null || target == null)) {
                throw new IllegalArgumentException("路线核查结论缺少判定或地点摘要");
            }
            if (status == Status.NEEDS_PLACE_CHOICE
                    && (!hasText(choiceTaskNo) || choices.isEmpty() || expiresAt == null)) {
                throw new IllegalArgumentException("地点选择结果缺少任务、选项或有效期");
            }
            if (status == Status.NEEDS_CHECK_MODE_CHOICE && modeClarification == null) {
                throw new IllegalArgumentException("核查含义选择结果缺少选项");
            }
        }

        private static Result placeChoices(String taskNo, List<ChoiceSummary> choices, Instant expiresAt) {
            return new Result(Status.NEEDS_PLACE_CHOICE, null, null, null,
                    null, null, taskNo, choices, expiresAt, null);
        }

        private static Result modeChoices(ModeClarification clarification) {
            return new Result(Status.NEEDS_CHECK_MODE_CHOICE, null, null, null,
                    clarification.target(), null, null, List.of(), clarification.expiresAt(), clarification);
        }

        private static Result placeNotFound() {
            return new Result(Status.PLACE_NOT_FOUND, null, null, "没有找到指定地点",
                    null, null, null, List.of(), null, null);
        }

        private static Result placeUnverifiable(String detail) {
            return new Result(Status.PLACE_UNVERIFIABLE, null, null, detail,
                    null, null, null, List.of(), null, null);
        }

        private static Result contextUnavailable() {
            return new Result(Status.CONTEXT_UNAVAILABLE, null, null,
                    "当前路线不存在、已过期或与会话条件不一致",
                    null, null, null, List.of(), null, null);
        }
    }

    public record TargetSummary(String name, String displayType, String city,
                                String address, BusinessType businessType) {
    }

    public record ModeClarification(String taskNo,
                                    TargetSummary target,
                                    List<ModeChoiceSummary> choices,
                                    Instant expiresAt) {
        public ModeClarification {
            if (!hasText(taskNo) || target == null || choices == null || choices.size() < 2
                    || expiresAt == null) {
                throw new IllegalArgumentException("核查含义选项不完整");
            }
            choices = List.copyOf(choices);
        }
    }

    public record ModeChoiceSummary(String choiceId, String label) {
        public ModeChoiceSummary {
            if (!hasText(choiceId) || !hasText(label)) {
                throw new IllegalArgumentException("核查含义选项缺少编号或文案");
            }
        }
    }

    private record Candidate(AmapPoiCandidate raw, PlaceCandidate cached) {
    }
}
