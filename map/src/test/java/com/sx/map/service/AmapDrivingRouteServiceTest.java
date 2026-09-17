package com.sx.map.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sx.map.config.AmapProperties;
import com.sx.map.exception.AmapApiException;
import com.sx.map.model.dto.Point;
import com.sx.map.model.dto.RouteRequest;
import com.sx.map.model.dto.RouteResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class AmapDrivingRouteServiceTest {

    private AmapProperties properties;
    private MockRestServiceServer server;
    private AmapDrivingRouteService service;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        properties = new AmapProperties();
        properties.setKey("test-key");
        service = new AmapDrivingRouteService(builder.build(), properties, new ObjectMapper());
    }

    /**
     * 兼容入口仍返回高德第一条路线，但请求必须开启完整导航步骤和多路线推荐策略。
     */
    @Test
    void returnsFirstDrivingPathFromCompatibilityMethod() {
        server.expect(requestTo(baseUrl()))
                .andRespond(withSuccess("""
                        {"status":"1","route":{"paths":[{"distance":"12345","duration":"1800"}]}}
                        """, MediaType.APPLICATION_JSON));

        RouteResponse response = service.drivingRoute(request());

        assertThat(response.getDistanceMeters()).isEqualTo(12_345L);
        assertThat(response.getDurationSeconds()).isEqualTo(1_800L);
        assertThat(response.getProvider()).isEqualTo("gaode");
        assertThat(response.getTraceId()).isNotBlank();
        server.verify();
    }

    /**
     * 服务区必须按入口、出口顺序发送。高德返回的每一条path和导航动作都要保留给方向规则评估。
     */
    @Test
    void sendsOrderedWaypointsAndParsesEveryPathWithNavigationActions() {
        server.expect(requestTo(baseUrl() + "&waypoints=120.15,30.15;120.16,30.16"))
                .andRespond(withSuccess("""
                        {"status":"1","route":{"paths":[
                          {
                            "distance":"15000",
                            "duration":"2100",
                            "steps":[
                              {
                                "instruction":"沿G25向南行驶",
                                "action":"直行",
                                "assistant_action":"进入服务区",
                                "orientation":"南",
                                "road_name":"G25长深高速",
                                "polyline":"120.1,30.1;120.15,30.15"
                              },
                              {"polyline":"120.15,30.15;120.16,30.16;120.2,30.2"}
                            ]
                          },
                          {
                            "distance":"16000",
                            "duration":"2200",
                            "steps":[{"road":"长深高速","polyline":"120.1,30.1;120.2,30.2"}]
                          }
                        ]}}
                        """, MediaType.APPLICATION_JSON));
        RouteRequest request = request();
        request.setWaypoints(List.of(point(120.15, 30.15), point(120.16, 30.16)));

        List<AmapDrivingRouteService.DrivingRouteOption> options = service.drivingRoutes(request);

        assertThat(options).hasSize(2);
        assertThat(options.get(0).route().getPolyline())
                .extracting(Point::getLng)
                .containsExactly(120.1, 120.15, 120.16, 120.2);
        assertThat(options.get(0).steps()).hasSize(2);
        assertThat(options.get(0).steps().getFirst().action()).isEqualTo("直行");
        assertThat(options.get(0).steps().getFirst().assistantAction()).isEqualTo("进入服务区");
        assertThat(options.get(0).steps().getFirst().roadName()).isEqualTo("G25长深高速");
        assertThat(options.get(1).route().getDistanceMeters()).isEqualTo(16_000L);
        assertThat(options.get(1).steps().getFirst().roadName()).isEqualTo("长深高速");
        server.verify();
    }

    @Test
    void rejectsAmapBusinessFailureWithoutReturningFakeRoute() {
        server.expect(requestTo(baseUrl()))
                .andRespond(withSuccess("{\"status\":\"0\",\"info\":\"INVALID_USER_KEY\"}",
                        MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> service.drivingRoute(request()))
                .isInstanceOf(AmapApiException.class)
                .hasMessageContaining("INVALID_USER_KEY");
        server.verify();
    }

    @Test
    void rejectsPathWithMissingDistance() {
        server.expect(requestTo(baseUrl()))
                .andRespond(withSuccess("""
                        {"status":"1","route":{"paths":[{"duration":"1800"}]}}
                        """, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> service.drivingRoute(request()))
                .isInstanceOf(AmapApiException.class)
                .hasMessageContaining("缺少distance字段");
        server.verify();
    }

    @Test
    void missingKeyFailsBeforeExternalRequest() {
        properties.setKey(" ");

        assertThatThrownBy(() -> service.drivingRoute(request()))
                .isInstanceOf(AmapApiException.class)
                .hasMessageContaining("未配置高德 Key");
        server.verify();
    }

    private static String baseUrl() {
        return "https://restapi.amap.com/v3/direction/driving"
                + "?key=test-key&origin=120.1,30.1&destination=120.2,30.2"
                + "&strategy=10&extensions=all";
    }

    private static RouteRequest request() {
        RouteRequest request = new RouteRequest();
        request.setOrigin(point(120.1, 30.1));
        request.setDest(point(120.2, 30.2));
        return request;
    }

    private static Point point(double lng, double lat) {
        Point point = new Point();
        point.setLat(lat);
        point.setLng(lng);
        return point;
    }
}
