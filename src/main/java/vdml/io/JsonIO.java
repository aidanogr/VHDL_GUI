package vdml.io;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import vdml.model.Block;
import vdml.model.ArchitectureDef;
import vdml.model.DiagramModel;
import vdml.model.Direction;
import vdml.model.EntityDef;
import vdml.model.Port;
import vdml.model.Wire;

public final class JsonIO {
    private JsonIO() {
    }

    public static String save(DiagramModel model) {
        return toJson(model);
    }

    public static DiagramModel load(String json) {
        return fromJson(json);
    }

    public static String toJson(DiagramModel model) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("version", 1);
        root.put("topEntity", model.getTopEntity());
        root.put("userSignals", model.getUserSignals());
        root.put("userLogic", model.getUserLogic());

        List<Map<String, Object>> topPorts = new ArrayList<>();
        for (Port port : model.getTopPorts()) {
            Map<String, Object> portEntry = new LinkedHashMap<>();
            portEntry.put("name", port.name());
            portEntry.put("direction", port.dir().name());
            portEntry.put("type", port.type());
            topPorts.add(portEntry);
        }
        root.put("topPorts", topPorts);
        root.put("topGenerics", model.getTopGenerics());
        root.put("libraries", model.getLibraries());
        root.put("libraryUses", model.getLibraryUses());

        Map<String, String> renamedBlockIds = buildUniqueBlockIds(model.getBlocks());
        Map<String, String> renamedSignalNames = buildUniqueSignalNames(model.getWires());

        List<Map<String, Object>> blocks = new ArrayList<>();
        for (Block block : model.getBlocks()) {
            String resolvedId = renamedBlockIds.getOrDefault(block.getId(), block.getId());
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", resolvedId);
            item.put("entityName", block.getEntityName());
            item.put("instanceLabel", block.getInstanceLabel());
            item.put("generics", block.getGenerics());
            item.put("x", block.getX());
            item.put("y", block.getY());
            item.put("width", block.getWidth());
            item.put("height", block.getHeight());
            if (block.getArchitectureName() != null) {
                item.put("architecture", block.getArchitectureName());
            }
            blocks.add(item);
        }
        root.put("blocks", blocks);

        List<Map<String, Object>> wires = new ArrayList<>();
        for (Wire wire : model.getWires()) {
            String fromBlock = renamedBlockIds.getOrDefault(wire.fromBlock(), wire.fromBlock());
            String toBlock = renamedBlockIds.getOrDefault(wire.toBlock(), wire.toBlock());
            String signalName = renamedSignalNames.getOrDefault(wire.id(), wire.signalName());
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", wire.id());
            item.put("fromBlock", fromBlock);
            item.put("fromPort", wire.fromPort());
            item.put("toBlock", toBlock);
            item.put("toPort", wire.toPort());
            item.put("signalName", signalName);
            item.put("bendX", wire.bendX());
            item.put("bendY", wire.bendY());
            item.put("approachX", wire.approachX());
            if (wire.labelX() != null && wire.labelY() != null) {
                item.put("labelX", wire.labelX());
                item.put("labelY", wire.labelY());
            }
            if (wire.inlineExpression() != null) {
                item.put("inlineExpression", wire.inlineExpression());
            }
            if (wire.waypoints() != null) {
                List<Map<String, Object>> waypoints = new ArrayList<>();
                for (Wire.Waypoint point : wire.waypoints()) {
                    Map<String, Object> waypoint = new LinkedHashMap<>();
                    waypoint.put("x", point.x());
                    waypoint.put("y", point.y());
                    waypoints.add(waypoint);
                }
                item.put("waypoints", waypoints);
            }
            wires.add(item);
        }
        root.put("wires", wires);

        Map<String, Object> entityCatalog = new LinkedHashMap<>();
        for (Map.Entry<String, EntityDef> entry : model.getEntityCatalog().entrySet()) {
            Map<String, Object> entity = new LinkedHashMap<>();
            entity.put("name", entry.getValue().name());
            entity.put("generics", entry.getValue().generics() == null ? List.of() : entry.getValue().generics());
            List<Map<String, Object>> portList = new ArrayList<>();
            for (Port port : entry.getValue().ports()) {
                Map<String, Object> portEntry = new LinkedHashMap<>();
                portEntry.put("name", port.name());
                portEntry.put("direction", port.dir().name());
                portEntry.put("type", port.type());
                portList.add(portEntry);
            }
            entity.put("ports", portList);
            List<Map<String, Object>> architectures = new ArrayList<>();
            for (ArchitectureDef architecture : entry.getValue().architectures()) {
                Map<String, Object> architectureEntry = new LinkedHashMap<>();
                architectureEntry.put("name", architecture.name());
                architectureEntry.put("declarations", architecture.declarations());
                architectureEntry.put("logic", architecture.logic());
                architectures.add(architectureEntry);
            }
            entity.put("architectures", architectures);
            if (entry.getValue().sourceOverride() != null) {
                entity.put("sourceOverride", entry.getValue().sourceOverride());
            }
            entityCatalog.put(entry.getKey(), entity);
        }
        root.put("entityCatalog", entityCatalog);
        return toJsonString(root);
    }

    public static DiagramModel fromJson(String json) {
        if (json == null || json.isBlank()) {
            throw new IllegalArgumentException("JSON input cannot be blank");
        }
        Object parsed = JsonParser.parse(json);
        if (!(parsed instanceof Map<?, ?> root)) {
            throw new IllegalArgumentException("Top-level JSON document must be an object");
        }
        Object versionObject = root.get("version");
        if (!(versionObject instanceof Number version) || version.intValue() != 1) {
            throw new IllegalArgumentException("Unsupported or unknown version");
        }

        DiagramModel model = new DiagramModel();
        model.setTopEntity(asString(root.get("topEntity"), DiagramModel.DEFAULT_TOP_ENTITY));
        model.updateTopLevelUserCode(
                asString(root.get("userSignals"), ""), asString(root.get("userLogic"), ""));
        if (root.get("libraries") instanceof List<?> libraryEntries) {
            model.setLibraries(toStringList(libraryEntries));
        }
        if (root.get("libraryUses") instanceof List<?> useEntries) {
            model.setLibraryUses(toStringList(useEntries));
        }

        Map<String, EntityDef> catalog = new LinkedHashMap<>();
        Object catalogObject = root.get("entityCatalog");
        if (catalogObject instanceof Map<?, ?> rawCatalog) {
            for (Map.Entry<?, ?> entry : rawCatalog.entrySet()) {
                Object value = entry.getValue();
                if (value instanceof Map<?, ?> entityMap) {
                    String name = asString(entityMap.get("name"), String.valueOf(entry.getKey()));
                    List<String> generics = new ArrayList<>();
                    Object genericEntries = entityMap.get("generics");
                    if (genericEntries instanceof List<?> list) {
                        for (Object generic : list) {
                            generics.add(String.valueOf(generic));
                        }
                    }
                    List<Port> ports = new ArrayList<>();
                    Object portsValue = entityMap.get("ports");
                    if (portsValue instanceof List<?> list) {
                        for (Object portValue : list) {
                            if (portValue instanceof Map<?, ?> portMap) {
                                String portName = asString(portMap.get("name"), "");
                                String direction = asString(portMap.get("direction"), "IN");
                                String type = asString(portMap.get("type"), "std_logic");
                                ports.add(new Port(portName, Direction.valueOf(direction.toUpperCase()), type));
                            }
                        }
                    }
                    List<ArchitectureDef> architectures = null;
                    Object architectureValues = entityMap.get("architectures");
                    if (architectureValues instanceof List<?> architectureList) {
                        architectures = new ArrayList<>();
                        for (Object architectureValue : architectureList) {
                            if (architectureValue instanceof Map<?, ?> architectureMap) {
                                architectures.add(new ArchitectureDef(
                                        asString(architectureMap.get("name"), ""),
                                        asString(architectureMap.get("declarations"), ""),
                                        asString(architectureMap.get("logic"), "")));
                            }
                        }
                    }
                    String sourceOverride = entityMap.get("sourceOverride") instanceof String source
                            ? source : null;
                    catalog.put(name, architectures == null
                            ? new EntityDef(name, ports, generics, List.of(new ArchitectureDef("rtl", "", "")),
                                    sourceOverride)
                            : new EntityDef(name, ports, generics, architectures, sourceOverride));
                }
            }
        }
        model.setEntityCatalog(catalog);

        List<Block> blocks = new ArrayList<>();
        Object blocksValue = root.get("blocks");
        if (blocksValue instanceof List<?> rawBlocks) {
            for (Object item : rawBlocks) {
                if (item instanceof Map<?, ?> map) {
                    String id = asString(map.get("id"), "b" + blocks.size());
                    String entityName = asString(map.get("entityName"), "");
                    String instanceLabel = asString(map.get("instanceLabel"), entityName);
                    Map<String, String> generics = new LinkedHashMap<>();
                    Object genericMap = map.get("generics");
                    if (genericMap instanceof Map<?, ?> rawGenerics) {
                        for (Map.Entry<?, ?> genericEntry : rawGenerics.entrySet()) {
                            generics.put(String.valueOf(genericEntry.getKey()), String.valueOf(genericEntry.getValue()));
                        }
                    }
                    int x = asInt(map.get("x"), 0);
                    int y = asInt(map.get("y"), 0);
                    int width = asInt(map.get("width"), Block.DEFAULT_WIDTH);
                    int height = asInt(map.get("height"), 0);
                    Block block = new Block(id, entityName, instanceLabel, generics, x, y, width, height);
                    block.setArchitectureName(asNullableString(map.get("architecture")));
                    blocks.add(block);
                }
            }
        }

        List<Wire> wires = new ArrayList<>();
        Object wireValue = root.get("wires");
        if (wireValue instanceof List<?> rawWires) {
            for (Object item : rawWires) {
                if (item instanceof Map<?, ?> map) {
                    String id = asString(map.get("id"), "w" + wires.size());
                    String fromBlock = asString(map.get("fromBlock"), "");
                    String fromPort = asString(map.get("fromPort"), "");
                    String toBlock = asString(map.get("toBlock"), "");
                    String toPort = asString(map.get("toPort"), "");
                    String signalName = asString(map.get("signalName"), "");
                    List<Wire.Waypoint> waypoints = null;
                    if (map.get("waypoints") instanceof List<?> rawWaypoints) {
                        waypoints = new ArrayList<>();
                        for (Object waypointValue : rawWaypoints) {
                            if (waypointValue instanceof Map<?, ?> waypoint) {
                                waypoints.add(new Wire.Waypoint(
                                        asInt(waypoint.get("x"), 0), asInt(waypoint.get("y"), 0)));
                            }
                        }
                    }
                    wires.add(new Wire(id, fromBlock, fromPort, toBlock, toPort, signalName,
                            asOptionalInt(map.get("bendX")), asOptionalInt(map.get("bendY")),
                            asOptionalInt(map.get("approachX")), waypoints,
                            asNullableString(map.get("inlineExpression")),
                            asOptionalInt(map.get("labelX")), asOptionalInt(map.get("labelY"))));
                }
            }
        }

        model.replaceState(blocks, wires, catalog);
        List<Port> topPorts = new ArrayList<>();
        if (root.get("topPorts") instanceof List<?> rawTop) {
            for (Object portValue : rawTop) {
                if (portValue instanceof Map<?, ?> portMap) {
                    topPorts.add(new Port(asString(portMap.get("name"), ""),
                            Direction.valueOf(asString(portMap.get("direction"), "IN").toUpperCase()),
                            asString(portMap.get("type"), "std_logic")));
                }
            }
        }
        model.setTopPorts(topPorts);
        List<String> topGenerics = new ArrayList<>();
        if (root.get("topGenerics") instanceof List<?> rawGenerics) {
            for (Object generic : rawGenerics) {
                topGenerics.add(String.valueOf(generic));
            }
        }
        model.setTopGenerics(topGenerics);
        String problem = model.checkIntegrity();
        if (problem != null) {
            throw new IllegalArgumentException("Invalid diagram: " + problem);
        }
        return model;
    }

    private static Map<String, String> buildUniqueBlockIds(List<Block> blocks) {
        Map<String, String> renamed = new LinkedHashMap<>();
        java.util.Set<String> used = new java.util.HashSet<>();
        for (Block block : blocks) {
            String id = block.getId();
            String candidate = id;
            int suffix = 2;
            while (!used.add(candidate)) {
                candidate = id + "_" + suffix;
                suffix++;
            }
            renamed.put(block.getId(), candidate);
        }
        return renamed;
    }

    private static Map<String, String> buildUniqueSignalNames(List<Wire> wires) {
        Map<String, String> renamed = new LinkedHashMap<>();
        Map<String, String> netByName = new LinkedHashMap<>();
        Map<String, String> assignedByNet = new LinkedHashMap<>();
        for (Wire wire : wires) {
            String net = wire.fromBlock() + "." + wire.fromPort();
            String assigned = assignedByNet.get(net);
            if (assigned == null) {
                String base = wire.signalName();
                assigned = base;
                int suffix = 2;
                while (netByName.containsKey(assigned)) {
                    assigned = base + "_" + suffix;
                    suffix++;
                }
                netByName.put(assigned, net);
                assignedByNet.put(net, assigned);
            }
            renamed.put(wire.id(), assigned);
        }
        return renamed;
    }

    private static String toJsonString(Map<String, Object> root) {
        StringBuilder builder = new StringBuilder();
        appendJsonValue(builder, root, 0);
        return builder.toString();
    }

    private static void appendJsonValue(StringBuilder builder, Object value, int indent) {
        if (value instanceof Map<?, ?> map) {
            builder.append("{\n");
            int index = 0;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                indent(builder, indent + 2);
                builder.append('"').append(escape(String.valueOf(entry.getKey()))).append("\": ");
                appendJsonValue(builder, entry.getValue(), indent + 2);
                if (index < map.size() - 1) {
                    builder.append(',');
                }
                builder.append('\n');
                index++;
            }
            indent(builder, indent);
            builder.append('}');
        } else if (value instanceof List<?> list) {
            if (list.isEmpty()) {
                builder.append("[]");
                return;
            }
            builder.append("[\n");
            for (int i = 0; i < list.size(); i++) {
                Object item = list.get(i);
                indent(builder, indent + 2);
                appendJsonValue(builder, item, indent + 2);
                if (i < list.size() - 1) {
                    builder.append(',');
                }
                builder.append('\n');
            }
            indent(builder, indent);
            builder.append(']');
        } else if (value instanceof String) {
            builder.append('"').append(escape((String) value)).append('"');
        } else if (value instanceof Number || value instanceof Boolean) {
            builder.append(String.valueOf(value));
        } else if (value == null) {
            builder.append("null");
        } else {
            builder.append('"').append(escape(String.valueOf(value))).append('"');
        }
    }

    private static void indent(StringBuilder builder, int spaces) {
        for (int i = 0; i < spaces; i++) {
            builder.append(' ');
        }
    }

    private static String escape(String input) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < input.length(); i++) {
            char ch = input.charAt(i);
            switch (ch) {
                case '\\' -> builder.append("\\\\");
                case '"' -> builder.append("\\\"");
                case '\n' -> builder.append("\\n");
                case '\r' -> builder.append("\\r");
                case '\t' -> builder.append("\\t");
                default -> builder.append(ch);
            }
        }
        return builder.toString();
    }

    private static String asString(Object value, String fallback) {
        return value == null ? fallback : String.valueOf(value);
    }

    private static String asNullableString(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static int asInt(Object value, int fallback) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        return fallback;
    }

    private static List<String> toStringList(List<?> entries) {
        List<String> result = new ArrayList<>();
        for (Object entry : entries) {
            result.add(String.valueOf(entry));
        }
        return result;
    }

    private static Integer asOptionalInt(Object value) {
        return value instanceof Number number ? number.intValue() : null;
    }

    private static final class JsonParser {
        private final String input;
        private int index;

        private JsonParser(String input) {
            this.input = input;
        }

        static Object parse(String text) {
            JsonParser parser = new JsonParser(text);
            Object value = parser.parseValue();
            parser.skipWhitespace();
            if (parser.index != parser.input.length()) {
                throw new IllegalArgumentException("Unexpected trailing characters");
            }
            return value;
        }

        private Object parseValue() {
            skipWhitespace();
            if (index >= input.length()) {
                throw new IllegalArgumentException("Unexpected end of input");
            }
            char ch = input.charAt(index);
            return switch (ch) {
                case '{' -> parseObject();
                case '[' -> parseArray();
                case '"' -> parseString();
                case 't' -> parseLiteral("true", Boolean.TRUE);
                case 'f' -> parseLiteral("false", Boolean.FALSE);
                case 'n' -> parseLiteral("null", null);
                default -> parseNumber();
            };
        }

        private Map<String, Object> parseObject() {
            expect('{');
            Map<String, Object> object = new LinkedHashMap<>();
            skipWhitespace();
            if (peek('}')) {
                index++;
                return object;
            }
            while (true) {
                skipWhitespace();
                String key = parseString();
                skipWhitespace();
                expect(':');
                Object value = parseValue();
                object.put(key, value);
                skipWhitespace();
                if (peek('}')) {
                    index++;
                    return object;
                }
                expect(',');
            }
        }

        private List<Object> parseArray() {
            expect('[');
            List<Object> array = new ArrayList<>();
            skipWhitespace();
            if (peek(']')) {
                index++;
                return array;
            }
            while (true) {
                array.add(parseValue());
                skipWhitespace();
                if (peek(']')) {
                    index++;
                    return array;
                }
                expect(',');
            }
        }

        private String parseString() {
            expect('"');
            StringBuilder builder = new StringBuilder();
            while (index < input.length()) {
                char ch = input.charAt(index++);
                if (ch == '"') {
                    return builder.toString();
                }
                if (ch == '\\') {
                    if (index >= input.length()) {
                        throw new IllegalArgumentException("Unterminated escape sequence");
                    }
                    char escape = input.charAt(index++);
                    builder.append(switch (escape) {
                        case '"' -> '"';
                        case '\\' -> '\\';
                        case '/' -> '/';
                        case 'b' -> '\b';
                        case 'f' -> '\f';
                        case 'n' -> '\n';
                        case 'r' -> '\r';
                        case 't' -> '\t';
                        default -> escape;
                    });
                } else {
                    builder.append(ch);
                }
            }
            throw new IllegalArgumentException("Unterminated string literal");
        }

        private Object parseNumber() {
            int start = index;
            if (peek('-')) {
                index++;
            }
            while (index < input.length() && Character.isDigit(input.charAt(index))) {
                index++;
            }
            if (index < input.length() && input.charAt(index) == '.') {
                index++;
                while (index < input.length() && Character.isDigit(input.charAt(index))) {
                    index++;
                }
            }
            String text = input.substring(start, index);
            if (text.contains(".")) {
                return Double.parseDouble(text);
            }
            return Long.parseLong(text);
        }

        private Object parseLiteral(String literal, Object value) {
            if (!input.startsWith(literal, index)) {
                throw new IllegalArgumentException("Expected literal '" + literal + "'");
            }
            index += literal.length();
            return value;
        }

        private void skipWhitespace() {
            while (index < input.length() && Character.isWhitespace(input.charAt(index))) {
                index++;
            }
        }

        private void expect(char ch) {
            skipWhitespace();
            if (index >= input.length() || input.charAt(index) != ch) {
                throw new IllegalArgumentException("Expected '" + ch + "' at index " + index);
            }
            index++;
        }

        private boolean peek(char ch) {
            skipWhitespace();
            return index < input.length() && input.charAt(index) == ch;
        }
    }
}
