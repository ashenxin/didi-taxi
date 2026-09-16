package com.sx.map.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sx.map.config.AmapProperties;
import com.sx.map.exception.AmapApiException;
import com.sx.map.model.dto.AmapPoiCandidate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class AmapPoiSearchServiceTest {

    private AmapProperties properties;
    private MockRestServiceServer server;
    private AmapPoiSearchService service;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        properties = new AmapProperties();
        properties.setKey("test-key");
        service = new AmapPoiSearchService(builder.build(), properties, new ObjectMapper());
    }

    @Test
    void parsesPoiAndNavigationPointsFromAmapResponse() {
        server.expect(request -> {
                    assertThat(request.getURI().getPath()).isEqualTo("/v5/place/text");
                    String query = URLDecoder.decode(request.getURI().getRawQuery(), StandardCharsets.UTF_8);
                    assertThat(query)
                            .contains("key=test-key")
                            .contains("keywords=下沙服务区")
                            .contains("region=杭州")
                            .contains("city_limit=true")
                            .contains("types=180301|180302")
                            .contains("show_fields=navi")
                            .contains("page_size=10");
                })
                .andRespond(withSuccess("""
                        {
                          "status": "1",
                          "info": "OK",
                          "count": "1",
                          "pois": [{
                            "id": "B0TEST001",
                            "name": "下沙服务区",
                            "type": "汽车服务;高速服务;高速服务区",
                            "typecode": "180301",
                            "pname": "浙江省",
                            "cityname": "杭州市",
                            "adname": "钱塘区",
                            "address": "G60沪昆高速附近",
                            "location": "120.366100,30.309100",
                            "navi": {
                              "entr_location": "120.366856,30.309568",
                              "exit_location": "120.367100,30.309900"
                            }
                          }]
                        }
                        """, MediaType.APPLICATION_JSON));

        List<AmapPoiCandidate> candidates = service.search("下沙服务区", "杭州", "180301|180302");

        assertThat(candidates).hasSize(1);
        AmapPoiCandidate candidate = candidates.getFirst();
        assertThat(candidate.getPoiId()).isEqualTo("B0TEST001");
        assertThat(candidate.getName()).isEqualTo("下沙服务区");
        assertThat(candidate.getTypeCode()).isEqualTo("180301");
        assertThat(candidate.getProvince()).isEqualTo("浙江省");
        assertThat(candidate.getCity()).isEqualTo("杭州市");
        assertThat(candidate.getDistrict()).isEqualTo("钱塘区");
        assertThat(candidate.getCenterPoint().getLng()).isEqualTo(120.366100);
        assertThat(candidate.getCenterPoint().getLat()).isEqualTo(30.309100);
        assertThat(candidate.getEntrancePoint().getLng()).isEqualTo(120.366856);
        assertThat(candidate.getEntrancePoint().getLat()).isEqualTo(30.309568);
        assertThat(candidate.getExitPoint().getLng()).isEqualTo(120.367100);
        assertThat(candidate.getExitPoint().getLat()).isEqualTo(30.309900);
        server.verify();
    }

    @Test
    void returnsEmptyListWhenAmapHasNoPoi() {
        server.expect(request -> assertThat(request.getURI().getPath()).isEqualTo("/v5/place/text"))
                .andRespond(withSuccess("{\"status\":\"1\",\"info\":\"OK\",\"count\":\"0\",\"pois\":[]}",
                        MediaType.APPLICATION_JSON));

        assertThat(service.search("不存在的服务区", null, null)).isEmpty();
        server.verify();
    }

    @Test
    void rejectsAmapBusinessFailureWithoutReturningCandidates() {
        server.expect(request -> assertThat(request.getURI().getPath()).isEqualTo("/v5/place/text"))
                .andRespond(withSuccess("{\"status\":\"0\",\"info\":\"INVALID_USER_KEY\"}",
                        MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> service.search("下沙服务区", "杭州", null))
                .isInstanceOf(AmapApiException.class)
                .hasMessageContaining("INVALID_USER_KEY");
        server.verify();
    }

    @Test
    void missingKeyFailsBeforeExternalRequest() {
        properties.setKey(" ");

        assertThatThrownBy(() -> service.search("下沙服务区", "杭州", null))
                .isInstanceOf(AmapApiException.class)
                .hasMessageContaining("未配置高德 Key");
        server.verify();
    }
}
