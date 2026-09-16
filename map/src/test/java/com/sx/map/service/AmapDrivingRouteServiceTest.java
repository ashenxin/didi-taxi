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

    @Test
    void parsesFirstDrivingPathFromAmapResponse() {
        server.expect(requestTo("https://restapi.amap.com/v3/direction/driving"
                        + "?key=test-key&origin=120.1,30.1&destination=120.2,30.2"))
                .andRespond(withSuccess("""
                        {"status":"1","route":{"paths":[{"distance":"12345","duration":"1800"}]}}
                        """, MediaType.APPLICATION_JSON));

        RouteResponse response = service.drivingRoute(request());

        assertThat(response.getDistanceMeters()).isEqualTo(12345L);
        assertThat(response.getDurationSeconds()).isEqualTo(1800L);
        assertThat(response.getProvider()).isEqualTo("gaode");
        assertThat(response.getTraceId()).isNotBlank();
        server.verify();
    }

    @Test
    void sendsSingleWaypointToAmapWhenProvided() {
        server.expect(requestTo("https://restapi.amap.com/v3/direction/driving"
                        + "?key=test-key&origin=120.1,30.1&destination=120.2,30.2&waypoints=120.15,30.15"))
                .andRespond(withSuccess("""
                        {"status":"1","route":{"paths":[{
                          "distance":"15000",
                          "duration":"2100",
                          "steps":[
                            {"polyline":"120.1,30.1;120.15,30.15"},
                            {"polyline":"120.15,30.15;120.2,30.2"}
                          ]
                        }]}}
                        """, MediaType.APPLICATION_JSON));

        RouteRequest request = request();
        Point waypoint = new Point();
        waypoint.setLat(30.15);
        waypoint.setLng(120.15);
        request.setWaypoint(waypoint);

        RouteResponse response = service.drivingRoute(request);

        assertThat(response.getDistanceMeters()).isEqualTo(15000L);
        assertThat(response.getDurationSeconds()).isEqualTo(2100L);
        assertThat(response.getPolyline()).hasSize(3);
        assertThat(response.getPolyline().get(0).getLng()).isEqualTo(120.1);
        assertThat(response.getPolyline().get(1).getLng()).isEqualTo(120.15);
        assertThat(response.getPolyline().get(2).getLng()).isEqualTo(120.2);
        server.verify();
    }

    @Test
    void rejectsAmapBusinessFailureWithoutReturningFakeRoute() {
        server.expect(requestTo("https://restapi.amap.com/v3/direction/driving"
                        + "?key=test-key&origin=120.1,30.1&destination=120.2,30.2"))
                .andRespond(withSuccess("{\"status\":\"0\",\"info\":\"INVALID_USER_KEY\"}",
                        MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> service.drivingRoute(request()))
                .isInstanceOf(AmapApiException.class)
                .hasMessageContaining("INVALID_USER_KEY");
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

    private static RouteRequest request() {
        Point origin = new Point();
        origin.setLat(30.1);
        origin.setLng(120.1);
        Point dest = new Point();
        dest.setLat(30.2);
        dest.setLng(120.2);
        RouteRequest request = new RouteRequest();
        request.setOrigin(origin);
        request.setDest(dest);
        return request;
    }
}
