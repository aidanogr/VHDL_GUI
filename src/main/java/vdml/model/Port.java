package vdml.model;

public record Port(String name, Direction dir, String type) {
    public String baseType() {
        if (type == null) {
            return "";
        }
        String normalized = type.trim();
        int index = normalized.indexOf('(');
        if (index < 0) {
            return normalized;
        }
        return normalized.substring(0, index).trim();
    }

    public String widthLabel() {
        if (type == null) {
            return "";
        }
        String normalized = type.trim();
        int index = normalized.indexOf('(');
        if (index < 0) {
            return "";
        }
        return normalized.substring(index).trim();
    }
}
