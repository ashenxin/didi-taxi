package com.sx.map.ai.service;

import com.sx.map.ai.model.PassengerRouteSnapshot;
import com.sx.map.ai.service.PassengerRoutePartialEndpointCache.EndpointRole;
import com.sx.map.model.dto.AmapPoiCandidate;
import com.sx.map.model.dto.Point;
import com.sx.map.service.AmapPoiSearchService;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 在地图服务内部将客服会话提到的起点或终点名称解析为高德地点。
 *
 * 这里只确认地图地点身份，不代表乘客已确认使用该地点。搜索结果可能有同名地点，
 * 因此不能把高德排序第一项、地理编码第一项或模型提供的坐标直接交给算路服务。
 * 返回的 Place 和可选择候选只供 map-service 内部编排；客户端只能看到候选摘要，
 * 不能提交 Place 作为可信条件。
 */
@Service
public class PassengerRouteEndpointResolver {
    private static final int MAX_NAME_LENGTH = 80;
    private static final int MAX_REGION_LENGTH = 30;
    // AmapPoiSearchService 只请求第一页，每页 10 条；达到上限时无法证明后续没有同名地点。
    private static final int SEARCH_PAGE_SIZE = 10;
    private static final int MAX_FOCUSED_CHOICES = 5;

    private final AmapPoiSearchService poiSearchService;

    public PassengerRouteEndpointResolver(AmapPoiSearchService poiSearchService) {
        this.poiSearchService = poiSearchService;
    }

    /**
     * 使用可选城市或行政区收窄查询。只有未触及分页上限、且恰好一个候选的名称
     * 与乘客指定名称完全一致时，才返回带高德 GCJ02 坐标的 Place。
     * 其余状态由客服追问城市、地址或更具体的名称；有效候选的地图身份仅留在
     * map-service，供后续生成短时地点选项，不会选取相似名称代替乘客决定。
     */
    public Resolution resolve(String placeName, String region) {
        return resolve(placeName, region, null);
    }

    public Resolution resolve(String placeName, String region, EndpointRole role) {
        String name = requiredText(placeName, "起终点名称", MAX_NAME_LENGTH);
        String searchRegion = optionalText(region, "地区", MAX_REGION_LENGTH);
        if (!directionCompatible(name, null, role)) {
            // 用户明确说的是反向出入口，不能展示同站另一方向诱导其误选。
            return Resolution.unresolved(Status.NOT_FOUND, List.of());
        }
        List<AmapPoiCandidate> candidates = poiSearchService.search(name, searchRegion, null);
        if (candidates == null) {
            throw new IllegalStateException("地图地点查询未返回候选集合");
        }
        if (candidates.isEmpty()) {
            return Resolution.unresolved(Status.NOT_FOUND, List.of());
        }

        boolean highwayAccess = isHighwayAccessRequest(name);
        boolean scenicPlace = !highwayAccess && isScenicPlaceRequest(name);
        List<AmapPoiCandidate> relevant = new ArrayList<>(candidates.size());
        Set<String> seenIds = new HashSet<>();
        Set<String> seenPlaces = new HashSet<>();
        for (AmapPoiCandidate candidate : candidates) {
            if (candidate == null || candidate.getName() == null || candidate.getName().isBlank()) {
                continue;
            }
            String candidateName = candidate.getName().strip();
            if (!directionCompatible(candidateName, candidate.getType(), role)
                    || !relevantToRequest(name, candidate, highwayAccess, scenicPlace)
                    || !endpointSelectable(candidateName)) {
                continue;
            }
            String id = blankToNull(candidate.getPoiId());
            if ((id != null && seenIds.contains(id))
                    || !seenPlaces.add(candidateIdentity(candidate))) continue;
            if (id != null) seenIds.add(id);
            relevant.add(candidate);
        }
        if (highwayAccess || scenicPlace) {
            // 高德第一页的相关度不等同于乘客意图；优先展示主地点和可通行入口。
            relevant.sort(Comparator.comparingInt((AmapPoiCandidate candidate) ->
                            toTrustedPlace(candidate) == null ? 1 : 0)
                    .thenComparingInt(candidate -> candidatePriority(name, candidate, scenicPlace, role)));
            if (relevant.size() > MAX_FOCUSED_CHOICES) {
                relevant = new ArrayList<>(relevant.subList(0, MAX_FOCUSED_CHOICES));
            }
        }

        List<CandidateSummary> summaries = new ArrayList<>(relevant.size());
        List<SelectableCandidate> selectableCandidates = new ArrayList<>(relevant.size());
        List<AmapPoiCandidate> exactMatches = new ArrayList<>();
        boolean hasInvalidCandidate = candidates.stream().anyMatch(candidate ->
                candidate == null || candidate.getName() == null || candidate.getName().isBlank());
        for (AmapPoiCandidate candidate : relevant) {
            String candidateName = candidate.getName().strip();
            CandidateSummary summary = new CandidateSummary(candidateName, blankToNull(candidate.getType()),
                    blankToNull(candidate.getCity()), blankToNull(candidate.getDistrict()),
                    blankToNull(candidate.getAddress()));
            summaries.add(summary);
            // 只把具有可核验地图地点身份的候选交给后续选择流程。展示摘要本身没有坐标，
            // 不能在乘客选择后再拿同名文字猜测或采用搜索结果第一项。
            PassengerRouteSnapshot.Place trustedPlace = toTrustedPlace(candidate);
            if (trustedPlace != null) {
                selectableCandidates.add(new SelectableCandidate(summary, trustedPlace));
            }
            if (name.equals(candidateName)) {
                exactMatches.add(candidate);
            }
        }
        List<CandidateSummary> visibleCandidates = List.copyOf(summaries);
        if (visibleCandidates.isEmpty() && !hasInvalidCandidate && candidates.size() < SEARCH_PAGE_SIZE) {
            return Resolution.unresolved(Status.NOT_FOUND, List.of());
        }
        if (hasInvalidCandidate || candidates.size() >= SEARCH_PAGE_SIZE) {
            return Resolution.unresolved(Status.INCOMPLETE_RESULTS, visibleCandidates, selectableCandidates);
        }
        if (exactMatches.size() != 1) {
            return Resolution.unresolved(Status.NEEDS_CLARIFICATION, visibleCandidates, selectableCandidates);
        }

        PassengerRouteSnapshot.Place place = toTrustedPlace(exactMatches.getFirst());
        return place == null
                ? Resolution.unresolved(Status.INVALID_MAP_DATA, visibleCandidates)
                : Resolution.unique(place);
    }

    private static boolean isHighwayAccessRequest(String name) {
        return name.contains("收费站") || name.contains("高速口") || name.contains("高速路口")
                || name.contains("高速入口") || name.contains("高速出口");
    }

    private static boolean isScenicPlaceRequest(String name) {
        return name.contains("湿地") || name.contains("公园") || name.contains("景区")
                || name.contains("风景区");
    }

    /**
     * 与 {@link PassengerRouteEndpointDirectionValidator} 的端点身份规则对齐：
     * “XX高速入口/出口”如果没有可核对的“收费站”身份，后续必然无法证明上下高速方向，
     * 因此不能作为可选项展示给乘客。
     */
    private static boolean endpointSelectable(String name) {
        String base = name.split("[（(]", 2)[0].strip().replaceFirst("(入口|出口)$", "");
        return base.contains("收费站")
                || !(name.contains("高速") && (name.contains("入口") || name.contains("出口")));
    }

    private static boolean relevantToRequest(String requestedName, AmapPoiCandidate candidate,
                                             boolean highwayAccess, boolean scenicPlace) {
        String candidateName = candidate.getName().strip();
        String type = candidate.getType() == null ? "" : candidate.getType();
        if (highwayAccess) {
            // “服务区与高速交叉口”等路口名不是乘客可以直接选择的高速收费站出入口。
            return candidateName.contains("收费站") || type.contains("收费站")
                    || type.contains("高速路入口") || type.contains("高速路出口")
                    || candidateName.contains("高速入口") || candidateName.contains("高速出口");
        }
        if (scenicPlace) {
            // 景区名称搜索常混入地铁、售票点和内部小景物；明确点名时仍允许精确搜索。
            return candidateName.equals(requestedName) || !containsAny(candidateName + ";" + type,
                    "地铁站", "公交站", "售票处", "停车场",
                    "水塘", "餐厅", "商店", "卫生间", "游船码头");
        }
        return true;
    }

    private static boolean containsAny(String value, String... fragments) {
        for (String fragment : fragments) {
            if (value.contains(fragment)) return true;
        }
        return false;
    }

    private static int candidatePriority(String requestedName, AmapPoiCandidate candidate,
                                         boolean scenicPlace, EndpointRole role) {
        String candidateName = candidate.getName().strip();
        if (candidateName.equals(requestedName)) return 0;
        if (!scenicPlace) {
            String placeHint = requestedName.replace("高速路口", "").replace("高速口", "")
                    .replace("收费站", "").replace("高速入口", "").replace("高速出口", "").strip();
            String locality = candidateName + ';' + candidate.getCity() + ';'
                    + candidate.getDistrict() + ';' + candidate.getAddress();
            boolean localMatch = !placeHint.isEmpty() && locality.contains(placeHint);
            String type = candidate.getType() == null ? "" : candidate.getType();
            boolean directedAccess = role == EndpointRole.ORIGIN
                    ? candidateName.contains("入口") || type.contains("高速路入口")
                    : role == EndpointRole.DESTINATION
                    && (candidateName.contains("出口") || type.contains("高速路出口"));
            return (localMatch ? 0 : 2) + (directedAccess ? 1 : 2);
        }
        if (candidateName.endsWith("湿地") || candidateName.endsWith("公园")
                || candidateName.endsWith("景区")) return 1;
        if (containsAny(candidateName, "入口", "大门")) return 2;
        if (candidateName.endsWith("园") || candidateName.endsWith("区")) return 3;
        return 4 + Math.min(candidateName.length(), 100);
    }

    private static String candidateIdentity(AmapPoiCandidate candidate) {
        Point center = candidate.getCenterPoint();
        return candidate.getName().strip() + '|' + candidate.getType() + '|'
                + candidate.getCity() + '|' + candidate.getAddress() + '|'
                + (center == null ? "" : center.getLng() + "," + center.getLat());
    }

    /** 只按明确的高速入口/出口标签排除反向地点，不从同名站点推断可掉头或可反向通行。 */
    public static boolean directionCompatible(String name, String type, EndpointRole role) {
        if (role == null) return true;
        String placeName = name == null ? "" : name;
        String placeType = type == null ? "" : type;
        boolean highway = placeName.contains("收费站") || placeName.contains("高速")
                || placeType.contains("高速路入口") || placeType.contains("高速路出口")
                || placeType.contains("收费站");
        if (!highway) return true;
        boolean entrance = placeName.contains("入口") || placeType.contains("高速路入口");
        boolean exit = placeName.contains("出口") || placeType.contains("高速路出口");
        if (entrance && exit) return false; // 地图名称与类型互相矛盾，不能猜测通行方向。
        if (!entrance && !exit) return true;
        return role == EndpointRole.ORIGIN ? !exit : !entrance;
    }

    private static PassengerRouteSnapshot.Place toTrustedPlace(AmapPoiCandidate candidate) {
        Point center = candidate.getCenterPoint();
        if (center == null || center.getLng() == null || center.getLat() == null
                || (blankToNull(candidate.getCity()) == null
                && blankToNull(candidate.getAddress()) == null)) {
            return null;
        }
        try {
            PassengerRouteSnapshot.GeoPoint coordinate = new PassengerRouteSnapshot.GeoPoint(
                    center.getLng(), center.getLat());
            return new PassengerRouteSnapshot.Place(
                    blankToNull(candidate.getPoiId()), candidate.getName().strip(),
                    blankToNull(candidate.getAddress()), blankToNull(candidate.getCity()), coordinate);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String requiredText(String value, String field, int maxLength) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + "不能为空");
        }
        String normalized = value.strip();
        if (normalized.length() > maxLength) {
            throw new IllegalArgumentException(field + "超过长度限制");
        }
        return normalized;
    }

    private static String optionalText(String value, String field, int maxLength) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.strip();
        if (normalized.length() > maxLength) {
            throw new IllegalArgumentException(field + "超过长度限制");
        }
        return normalized;
    }

    private static String blankToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.strip();
    }

    public enum Status {
        UNIQUE,
        MISSING,
        NOT_FOUND,
        NEEDS_CLARIFICATION,
        INCOMPLETE_RESULTS,
        INVALID_MAP_DATA
    }

    /** 仅包含可用于向乘客澄清的名称、类型与位置描述，不包含地图 ID 或坐标。 */
    public record CandidateSummary(String name, String type, String city,
                                   String district, String address) {
        public CandidateSummary {
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("地点候选名称不能为空");
            }
        }
    }

    /**
     * 地图服务内部的可选择地点；summary 可展示，place 含 POI 身份与 GCJ02 坐标，
     * 只能保存到受乘客、会话和条件版本约束的短时选项中，不能直接序列化给客户端。
     */
    public record SelectableCandidate(CandidateSummary summary, PassengerRouteSnapshot.Place place) {
        public SelectableCandidate {
            Objects.requireNonNull(summary, "地点选项缺少展示摘要");
            Objects.requireNonNull(place, "地点选项缺少地图身份");
        }
    }

    public record Resolution(Status status,
                             PassengerRouteSnapshot.Place place,
                             List<CandidateSummary> candidates,
                             List<SelectableCandidate> selectableCandidates) {
        public Resolution {
            Objects.requireNonNull(status, "地点解析状态不能为空");
            candidates = List.copyOf(Objects.requireNonNull(candidates, "地点候选摘要不能为空"));
            selectableCandidates = List.copyOf(Objects.requireNonNull(
                    selectableCandidates, "可选择地点不能为空"));
            if (status == Status.UNIQUE && place == null) {
                throw new IllegalArgumentException("唯一地点缺少地图身份");
            }
            if (status != Status.UNIQUE && place != null) {
                throw new IllegalArgumentException("未确认唯一地点时不能返回可算路地点");
            }
            if (status != Status.NEEDS_CLARIFICATION && status != Status.INCOMPLETE_RESULTS
                    && !selectableCandidates.isEmpty()) {
                throw new IllegalArgumentException("当前地点状态不能返回可选择候选");
            }
        }

        private static Resolution unique(PassengerRouteSnapshot.Place place) {
            return new Resolution(Status.UNIQUE, place, List.of(), List.of());
        }

        private static Resolution unresolved(Status status, List<CandidateSummary> candidates) {
            return unresolved(status, candidates, List.of());
        }

        private static Resolution unresolved(Status status, List<CandidateSummary> candidates,
                                             List<SelectableCandidate> selectableCandidates) {
            return new Resolution(status, null, candidates, selectableCandidates);
        }
    }
}
