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
import com.sx.map.ai.service.PassengerCurrentRouteClarificationCache.SelectedMode;
import com.sx.map.ai.tool.PassengerRoutePoiTools;
import com.sx.map.model.dto.AmapPoiCandidate;
import com.sx.map.model.dto.Point;
import com.sx.map.service.CurrentRoutePassageService;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Predicate;

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
     * 首次核查指定地点。
     *
     * 乘客明确说“某收费站”且没有指定入口/出口时，按站名过滤同站入口/出口变体并做站级评估，
     * 不再要求乘客选择方向；只有明确问入口/出口、站名本身有歧义，或常规地点名称与地图
     * 规范名称不一致时，才生成短时地点选项等待确认。
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
        String stationBase = requestedStationBase(placeName);
        boolean stationIntent = (requestedMode == CurrentRouteCheckMode.UNSPECIFIED
                || requestedMode == CurrentRouteCheckMode.PASS_NEARBY)
                && placeName.contains("收费站")
                && !containsDirectionMarker(placeName)
                && stationBase.length() >= 2;
        if (!stationIntent && candidates.size() > MAX_CHOICES) {
            return Result.placeUnverifiable("同名地点过多，请补充城市或更具体的位置");
        }

        PassengerRouteSnapshot latest = refresh(snapshot);
        if (latest == null) {
            return Result.contextUnavailable();
        }

        if (stationIntent) {
            List<Candidate> stationCandidates = candidates.stream()
                    .filter(candidate -> candidate.cached().businessType() == BusinessType.TOLL_STATION
                            && stationBaseOf(candidate.cached()).contains(stationBase))
                    .toList();
            if (stationCandidates.isEmpty()) {
                return Result.placeNotFound();
            }
            Map<String, List<Candidate>> groups = groupByStation(stationCandidates);
            if (groups.size() > 1) {
                return Result.placeUnverifiable("找到多个同名收费站，请补充城市或地址");
            }
            return checkStationGroup(latest, groups.values().iterator().next());
        }

        // 地图规范名称与乘客原词不一致时，即使只有一个结果也必须确认。
        // 这既允许“三墩高速路口”解析为“三墩收费站”，也防止把“五常”静默升级成收费站。
        if (candidates.size() == 1 && samePlaceName(placeName, candidates.getFirst().raw().getName())) {
            Candidate candidate = candidates.getFirst();
            return checkRequestedMode(latest, requestedMode, candidate.cached());
        }

        ChoiceTask task = placeChoiceCache.saveCurrentRouteCheck(customerId, latest.conversationNo(),
                latest.conditionVersion(), requestVersion, userText,
                latest.origin(), latest.destination(),
                requestedMode,
                candidates.stream().map(Candidate::cached).toList());
        return Result.placeChoices(task.taskNo(), task.summaries(), task.expiresAt());
    }

    /** 兼容旧内部调用；未提供语义时按“实际通过/仅附近/不经过/无法确认”核查。 */
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
        return checkRequestedMode(latest, selected.checkMode(), selected.candidate());
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

    /** “三墩高速收费站” → “三墩”；只用于站级核查的站名相关性过滤。 */
    private static String requestedStationBase(String placeName) {
        String base = placeName.split("[（(]", 2)[0].strip();
        return base.replace("高速收费站", "")
                .replace("收费站", "")
                .replace("高速路口", "")
                .replace("高速口", "")
                .replace("高速", "")
                .strip();
    }

    /** 乘客明确问入口/出口或方向时，保留方向级候选，不合并成站级。 */
    private static boolean containsDirectionMarker(String placeName) {
        return placeName.contains("入口") || placeName.contains("出口")
                || placeName.contains("东向") || placeName.contains("西向")
                || placeName.contains("南向") || placeName.contains("北向");
    }

    /** 地图 POI 的站名部分，例如“三墩收费站(G25长深高速东入口)” → “三墩收费站”。 */
    private static String stationBaseOf(PlaceCandidate candidate) {
        String name = candidate.place().name().split("[（(]", 2)[0].strip();
        return name.replaceFirst("(入口|出口)$", "").strip();
    }

    /** 同一收费站的不同入口/出口/方向变体归为一组；不同城市同名站不合并。 */
    private static Map<String, List<Candidate>> groupByStation(List<Candidate> candidates) {
        Map<String, List<Candidate>> groups = new LinkedHashMap<>();
        for (Candidate candidate : candidates) {
            PlaceCandidate cached = candidate.cached();
            String location = hasText(cached.place().city())
                    ? cached.place().city().strip() : cached.place().address();
            String station = stationBaseOf(cached).replace("高速收费站", "收费站");
            String key = station + "|" + (location == null ? "" : location.strip());
            groups.computeIfAbsent(key, ignored -> new ArrayList<>()).add(candidate);
        }
        return groups;
    }

    /**
     * 站级评估：同一收费站的所有入口/出口都作为该站的 access point。
     * 有任一实际通过证据即回答通过；否则有任一附近证据回答附近；全部远离回答不经过；
     * 其余情况无法确认。目标地点展示为站名，不再让乘客选择方向。
     */
    private Result checkStationGroup(PassengerRouteSnapshot snapshot, List<Candidate> group) {
        if (group.isEmpty()) {
            return Result.placeNotFound();
        }
        List<CurrentRoutePassageService.RelationCheck> checks = group.stream()
                .map(candidate -> passageService.checkRelation(
                        snapshot, toRawCandidate(candidate.cached())))
                .toList();
        CurrentRoutePassageService.RelationCheck through = firstMatching(checks,
                check -> check.verdict() == CurrentRoutePassageService.Verdict.PASSED
                        && check.mode() == CurrentRouteCheckMode.VISIT_PLACE);
        if (through != null) {
            return stationResult(group, through);
        }
        CurrentRoutePassageService.RelationCheck nearby = firstMatching(checks,
                check -> check.verdict() == CurrentRoutePassageService.Verdict.PASSED
                        && check.mode() == CurrentRouteCheckMode.PASS_NEARBY);
        if (nearby != null) {
            return stationResult(group, nearby);
        }
        if (checks.stream().allMatch(check ->
                check.verdict() == CurrentRoutePassageService.Verdict.NOT_PASSED)) {
            return stationResult(group, checks.getFirst());
        }
        CurrentRoutePassageService.RelationCheck unknown = firstMatching(checks,
                check -> check.verdict() == CurrentRoutePassageService.Verdict.UNVERIFIABLE);
        return stationResult(group, unknown == null ? checks.getFirst() : unknown);
    }

    private static CurrentRoutePassageService.RelationCheck firstMatching(
            List<CurrentRoutePassageService.RelationCheck> checks,
            Predicate<CurrentRoutePassageService.RelationCheck> predicate) {
        return checks.stream().filter(predicate).findFirst().orElse(null);
    }

    private static Result stationResult(List<Candidate> group,
                                        CurrentRoutePassageService.RelationCheck check) {
        PlaceCandidate first = group.getFirst().cached();
        String stationName = stationBaseOf(first).replace("高速收费站", "收费站");
        TargetSummary target = new TargetSummary(stationName, first.displayType(),
                first.place().city(), first.place().address(), BusinessType.TOLL_STATION);
        return new Result(Status.CHECKED, check.verdict(), check.reason(), check.detail(),
                target, check.mode(), null, List.of(), null, null);
    }

    /**
     * 乘客没有指定“驶入/驶出/进入”动作时，不再追问核查含义，直接给出复合关系结论。
     * 显式动作仍按原有动作证据和方向一致性核查。
     */
    private Result checkRequestedMode(PassengerRouteSnapshot snapshot,
                                      CurrentRouteCheckMode checkMode,
                                      PlaceCandidate candidate) {
        if (checkMode != null && checkMode != CurrentRouteCheckMode.UNSPECIFIED) {
            return checked(passageService.check(snapshot, toRawCandidate(candidate), checkMode),
                    candidate, checkMode);
        }
        return checkedRelation(passageService.checkRelation(snapshot, toRawCandidate(candidate)),
                candidate);
    }

    private static Result checked(CurrentRoutePassageService.CheckResult check,
                                  PlaceCandidate candidate,
                                  CurrentRouteCheckMode checkMode) {
        TargetSummary target = targetSummary(candidate);
        return new Result(Status.CHECKED, check.verdict(), check.reason(), check.detail(),
                target, checkMode, null, List.of(), null, null);
    }

    private static Result checkedRelation(CurrentRoutePassageService.RelationCheck check,
                                          PlaceCandidate candidate) {
        return new Result(Status.CHECKED, check.verdict(), check.reason(), check.detail(),
                targetSummary(candidate), check.mode(), null, List.of(), null, null);
    }

    private static TargetSummary targetSummary(PlaceCandidate candidate) {
        TargetSummary target = new TargetSummary(candidate.place().name(), candidate.displayType(),
                candidate.place().city(), candidate.place().address(), candidate.businessType());
        return target;
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
