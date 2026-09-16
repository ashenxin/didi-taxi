package com.sx.map.model.dto;

/**
 * 指定单个途经地点的路线及其无途经点参考路线。
 */
public class WaypointRoutePlanResponse {

    private AmapPoiCandidate waypoint;
    private String waypointAccess;
    private RouteResponse route;
    private RouteResponse referenceRoute;
    private Long distanceDeltaMeters;
    private Long durationDeltaSeconds;

    public AmapPoiCandidate getWaypoint() {
        return waypoint;
    }

    public void setWaypoint(AmapPoiCandidate waypoint) {
        this.waypoint = waypoint;
    }

    public String getWaypointAccess() {
        return waypointAccess;
    }

    public void setWaypointAccess(String waypointAccess) {
        this.waypointAccess = waypointAccess;
    }

    public RouteResponse getRoute() {
        return route;
    }

    public void setRoute(RouteResponse route) {
        this.route = route;
    }

    public RouteResponse getReferenceRoute() {
        return referenceRoute;
    }

    public void setReferenceRoute(RouteResponse referenceRoute) {
        this.referenceRoute = referenceRoute;
    }

    public Long getDistanceDeltaMeters() {
        return distanceDeltaMeters;
    }

    public void setDistanceDeltaMeters(Long distanceDeltaMeters) {
        this.distanceDeltaMeters = distanceDeltaMeters;
    }

    public Long getDurationDeltaSeconds() {
        return durationDeltaSeconds;
    }

    public void setDurationDeltaSeconds(Long durationDeltaSeconds) {
        this.durationDeltaSeconds = durationDeltaSeconds;
    }
}
