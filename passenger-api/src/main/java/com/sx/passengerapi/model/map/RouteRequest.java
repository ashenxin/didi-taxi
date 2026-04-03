package com.sx.passengerapi.model.map;

public class RouteRequest {
    private Point origin;
    private Point dest;

    public Point getOrigin() {
        return origin;
    }

    public void setOrigin(Point origin) {
        this.origin = origin;
    }

    public Point getDest() {
        return dest;
    }

    public void setDest(Point dest) {
        this.dest = dest;
    }
}

