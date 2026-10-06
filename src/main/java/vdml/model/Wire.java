package vdml.model;

import java.util.List;

public record Wire(String id, String fromBlock, String fromPort,
                  String toBlock, String toPort, String signalName,
                  Integer bendX, Integer bendY, Integer approachX, List<Waypoint> waypoints) {
    public record Waypoint(int x, int y) {
    }

    public Wire {
        waypoints = waypoints == null ? null : List.copyOf(waypoints);
    }

    public Wire(String id, String fromBlock, String fromPort, String toBlock, String toPort,
                String signalName, Integer bendX, Integer bendY, Integer approachX) {
        this(id, fromBlock, fromPort, toBlock, toPort, signalName, bendX, bendY, approachX, null);
    }

    public Wire(String id, String fromBlock, String fromPort, String toBlock, String toPort,
                String signalName, Integer bendX, Integer bendY) {
        this(id, fromBlock, fromPort, toBlock, toPort, signalName, bendX, bendY, null, null);
    }

    public Wire(String id, String fromBlock, String fromPort,
                String toBlock, String toPort, String signalName) {
        this(id, fromBlock, fromPort, toBlock, toPort, signalName, null, null, null, null);
    }

    public Wire withBend(int x, int y) {
        return withRoute(x, y, approachX);
    }

    public Wire withRoute(int departureX, int routeY, int approachX) {
        return new Wire(id, fromBlock, fromPort, toBlock, toPort, signalName,
                departureX, routeY, approachX, waypoints);
    }

    public Wire withWaypoints(List<Waypoint> points) {
        return new Wire(id, fromBlock, fromPort, toBlock, toPort, signalName,
                bendX, bendY, approachX, points);
    }
}
