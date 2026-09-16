package com.sx.map.ai.tool;

import com.sx.map.exception.AmapApiException;
import com.sx.map.model.dto.AmapPoiCandidate;
import com.sx.map.service.AmapPoiSearchService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class PassengerRoutePoiToolsTest {

    private AmapPoiSearchService poiSearchService;
    private PassengerRoutePoiTools tools;

    @BeforeEach
    void setUp() {
        poiSearchService = mock(AmapPoiSearchService.class);
        tools = new PassengerRoutePoiTools(poiSearchService);
    }

    /**
     * Agent 只能使用稳定的业务类型名称；这里锁定它们与高德父类型编码之间的映射，
     * 防止后续修改提示词或工具参数时意外扩大 POI 搜索范围。
     */
    @ParameterizedTest
    @CsvSource({
            "TOLL_STATION,180200",
            "SERVICE_AREA,180300",
            "GAS_STATION,010100"
    })
    void mapsAllowedBusinessTypeToAmapTypeCode(String poiType, String expectedTypeCode) {
        AmapPoiCandidate candidate = new AmapPoiCandidate();
        candidate.setPoiId("B0TEST001");
        candidate.setName("测试地点");
        List<AmapPoiCandidate> expected = List.of(candidate);
        when(poiSearchService.search("下沙服务区", "杭州", expectedTypeCode)).thenReturn(expected);

        List<AmapPoiCandidate> actual = tools.searchWaypointCandidates(
                "  下沙服务区  ",
                "  杭州  ",
                poiType.toLowerCase());

        assertThat(actual).isSameAs(expected);
        verify(poiSearchService).search("下沙服务区", "杭州", expectedTypeCode);
    }

    @Test
    void passesNullRegionWhenUserDidNotSpecifyOne() {
        when(poiSearchService.search("杭州北收费站", null, "180200")).thenReturn(List.of());

        assertThat(tools.searchWaypointCandidates("杭州北收费站", " ", "TOLL_STATION")).isEmpty();

        verify(poiSearchService).search("杭州北收费站", null, "180200");
    }

    /**
     * 未批准的地点类型必须在调用地图服务之前失败，避免模型产生的任意类型触发付费查询。
     */
    @Test
    void rejectsUnsupportedPoiTypeBeforeCallingAmap() {
        assertThatThrownBy(() -> tools.searchWaypointCandidates("西湖", "杭州", "SCENIC_SPOT"))
                .isInstanceOf(AmapApiException.class)
                .hasMessageContaining("仅支持");

        verifyNoInteractions(poiSearchService);
    }

    @Test
    void rejectsOverlongKeywordsBeforeCallingAmap() {
        String overlongKeywords = "服".repeat(81);

        assertThatThrownBy(() -> tools.searchWaypointCandidates(overlongKeywords, "杭州", "SERVICE_AREA"))
                .isInstanceOf(AmapApiException.class)
                .hasMessageContaining("长度不能超过80个字符");

        verify(poiSearchService, never()).search(overlongKeywords, "杭州", "180300");
    }
}
