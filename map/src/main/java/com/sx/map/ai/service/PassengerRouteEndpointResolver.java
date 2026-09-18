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
 * 返回的 Place 和候选摘要只供服务端编排；客户端不能提交 Place 作为可信条件。
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
     * 其余状态由客服追问城市、地址或更具体的名称；不会选取相似名称代替乘客决定。
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
        List<AmapPoiCandidate> exactMatches = new ArrayList<>();
        boolean hasInvalidCandidate = false;
        for (AmapPoiCandidate candidate : candidates) {
            if (candidate == null || candidate.getName() == null || candidate.getName().isBlank()) {
                hasInvalidCandidate = true;
                continue;
            }
            String candidateName = candidate.getName().strip();
            summaries.add(new CandidateSummary(candidateName, blankToNull(candidate.getType()),
                    blankToNull(candidate.getCity()), blankToNull(candidate.getDistrict()),
                    blankToNull(candidate.getAddress())));
            if (name.equals(candidateName)) {
                exactMatches.add(candidate);
            }
        }
        List<CandidateSummary> visibleCandidates = List.copyOf(summaries);
        if (hasInvalidCandidate || candidates.size() >= SEARCH_PAGE_SIZE) {
            return Resolution.unresolved(Status.INCOMPLETE_RESULTS, visibleCandidates);
        }
        if (exactMatches.size() != 1) {
            return Resolution.unresolved(Status.NEEDS_CLARIFICATION, visibleCandidates);
        }

        AmapPoiCandidate selected = exactMatches.getFirst();
        Point center = selected.getCenterPoint();
        if (center == null || center.getLng() == null || center.getLat() == null
                || (blankToNull(selected.getCity()) == null
                && blankToNull(selected.getAddress()) == null)) {
            return Resolution.unresolved(Status.INVALID_MAP_DATA, visibleCandidates);
        }
        try {
            PassengerRouteSnapshot.GeoPoint coordinate = new PassengerRouteSnapshot.GeoPoint(
                    center.getLng(), center.getLat());
            PassengerRouteSnapshot.Place place = new PassengerRouteSnapshot.Place(
                    blankToNull(selected.getPoiId()), selected.getName().strip(),
                    blankToNull(selected.getAddress()), blankToNull(selected.getCity()), coordinate);
            return Resolution.unique(place);
        } catch (IllegalArgumentException e) {
            return Resolution.unresolved(Status.INVALID_MAP_DATA, visibleCandidates);
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

    public record Resolution(Status status,
                             PassengerRouteSnapshot.Place place,
                             List<CandidateSummary> candidates) {
        public Resolution {
            Objects.requireNonNull(status, "地点解析状态不能为空");
            candidates = List.copyOf(Objects.requireNonNull(candidates, "地点候选摘要不能为空"));
            if (status == Status.UNIQUE && place == null) {
                throw new IllegalArgumentException("唯一地点缺少地图身份");
            }
            if (status != Status.UNIQUE && place != null) {
                throw new IllegalArgumentException("未确认唯一地点时不能返回可算路地点");
            }
        }

        private static Resolution unique(PassengerRouteSnapshot.Place place) {
            return new Resolution(Status.UNIQUE, place, List.of());
        }

        private static Resolution unresolved(Status status, List<CandidateSummary> candidates) {
            return new Resolution(status, null, candidates);
        }
    }
}
