package vdml.model;

import java.util.List;

public record Wire(String id, String fromBlock, String fromPort,
                  String toBlock, String toPort, String signalName,
                  Integer bendX, Integer bendY, Integer approachX, List<Waypoint> waypoints,
                  String inlineExpression) {
    public static final String SIGNAL_TOKEN = "__VDML_SIGNAL__";

    public record Waypoint(int x, int y) {
    }

    public Wire {
        waypoints = waypoints == null ? null : List.copyOf(waypoints);
    }

    public Wire(String id, String fromBlock, String fromPort, String toBlock, String toPort,
                String signalName, Integer bendX, Integer bendY, Integer approachX) {
        this(id, fromBlock, fromPort, toBlock, toPort, signalName, bendX, bendY, approachX, null, null);
    }

    public Wire(String id, String fromBlock, String fromPort, String toBlock, String toPort,
                String signalName, Integer bendX, Integer bendY, Integer approachX, List<Waypoint> waypoints) {
        this(id, fromBlock, fromPort, toBlock, toPort, signalName,
                bendX, bendY, approachX, waypoints, null);
    }

    public Wire(String id, String fromBlock, String fromPort, String toBlock, String toPort,
                String signalName, Integer bendX, Integer bendY) {
        this(id, fromBlock, fromPort, toBlock, toPort, signalName, bendX, bendY, null, null, null);
    }

    public Wire(String id, String fromBlock, String fromPort,
                String toBlock, String toPort, String signalName) {
        this(id, fromBlock, fromPort, toBlock, toPort, signalName, null, null, null, null, null);
    }

    public Wire withBend(int x, int y) {
        return withRoute(x, y, approachX);
    }

    public Wire withRoute(int departureX, int routeY, int approachX) {
        return new Wire(id, fromBlock, fromPort, toBlock, toPort, signalName,
                departureX, routeY, approachX, waypoints, inlineExpression);
    }

    public Wire withWaypoints(List<Waypoint> points) {
        return new Wire(id, fromBlock, fromPort, toBlock, toPort, signalName,
                bendX, bendY, approachX, points, inlineExpression);
    }

    public Wire withInlineExpression(String expression) {
        return new Wire(id, fromBlock, fromPort, toBlock, toPort, signalName,
                bendX, bendY, approachX, waypoints, expression);
    }

    public String connectionExpression() {
        return inlineExpression == null || inlineExpression.isBlank()
                ? signalName : inlineExpression.replace(SIGNAL_TOKEN, signalName);
    }
}
