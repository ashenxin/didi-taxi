package com.sx.map.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sx.map.config.AmapProperties;
import com.sx.map.exception.AmapApiException;
import com.sx.map.model.dto.Point;
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

class AmapCoordinateConvertServiceTest {

    private MockRestServiceServer server;
    private AmapCoordinateConvertService service;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        AmapProperties properties = new AmapProperties();
        properties.setKey("test-key");
        service = new AmapCoordinateConvertService(builder.build(), properties, new ObjectMapper());
    }

    @Test
    void convertsWgs84PointsInOriginalOrder() {
        server.expect(request -> {
                    assertThat(request.getURI().getPath()).isEqualTo("/v3/assistant/coordinate/convert");
                    String query = URLDecoder.decode(request.getURI().getRawQuery(), StandardCharsets.UTF_8);
                    assertThat(query)
                            .contains("key=test-key")
                            .contains("locations=120.2156,30.2525|120.1655,30.2635")
                            .contains("coordsys=gps");
                })
                .andRespond(withSuccess("""
                        {"status":"1","info":"OK","locations":"120.220100,30.250100;120.170100,30.260100"}
                        """, MediaType.APPLICATION_JSON));

        List<Point> converted = service.convertWgs84ToAmap(List.of(
                point(120.2156, 30.2525),
                point(120.1655, 30.2635)));

        assertThat(converted).hasSize(2);
        assertThat(converted.get(0).getLng()).isEqualTo(120.220100);
        assertThat(converted.get(0).getLat()).isEqualTo(30.250100);
        assertThat(converted.get(1).getLng()).isEqualTo(120.170100);
        assertThat(converted.get(1).getLat()).isEqualTo(30.260100);
        server.verify();
    }

    @Test
    void rejectsBusinessFailure() {
        server.expect(request -> assertThat(request.getURI().getPath())
                        .isEqualTo("/v3/assistant/coordinate/convert"))
                .andRespond(withSuccess("{\"status\":\"0\",\"info\":\"INVALID_USER_KEY\"}",
                        MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> service.convertWgs84ToAmap(List.of(point(120.2156, 30.2525))))
                .isInstanceOf(AmapApiException.class)
                .hasMessageContaining("INVALID_USER_KEY");
        server.verify();
    }

    @Test
    void rejectsMismatchedResponseCount() {
        server.expect(request -> assertThat(request.getURI().getPath())
                        .isEqualTo("/v3/assistant/coordinate/convert"))
                .andRespond(withSuccess("{\"status\":\"1\",\"info\":\"OK\",\"locations\":\"120.22,30.25\"}",
                        MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> service.convertWgs84ToAmap(List.of(
                        point(120.2156, 30.2525),
                        point(120.1655, 30.2635))))
                .isInstanceOf(AmapApiException.class)
                .hasMessageContaining("数量与请求不一致");
        server.verify();
    }

    private static Point point(double lng, double lat) {
        Point point = new Point();
        point.setLng(lng);
        point.setLat(lat);
        return point;
    }
}
