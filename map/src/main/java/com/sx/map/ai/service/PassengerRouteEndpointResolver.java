package com.sx.map.ai.service;

import com.sx.map.ai.model.PassengerRouteSnapshot;
import com.sx.map.model.dto.AmapPoiCandidate;
import com.sx.map.model.dto.Point;
import com.sx.map.service.AmapPoiSearchService;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

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
        String name = requiredText(placeName, "起终点名称", MAX_NAME_LENGTH);
        String searchRegion = optionalText(region, "地区", MAX_REGION_LENGTH);
        List<AmapPoiCandidate> candidates = poiSearchService.search(name, searchRegion, null);
        if (candidates == null) {
            throw new IllegalStateException("地图地点查询未返回候选集合");
        }
        if (candidates.isEmpty()) {
            return Resolution.unresolved(Status.NOT_FOUND, List.of());
        }

        List<CandidateSummary> summaries = new ArrayList<>(candidates.size());
        List<SelectableCandidate> selectableCandidates = new ArrayList<>(candidates.size());
        List<AmapPoiCandidate> exactMatches = new ArrayList<>();
        boolean hasInvalidCandidate = false;
        for (AmapPoiCandidate candidate : candidates) {
            if (candidate == null || candidate.getName() == null || candidate.getName().isBlank()) {
                hasInvalidCandidate = true;
                continue;
            }
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
