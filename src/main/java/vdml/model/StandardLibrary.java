package vdml.model;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Built-in, synthesizable combinational VHDL components. */
public final class StandardLibrary {
    private static final Map<String, EntityDef> DEFINITIONS = createDefinitions();

    private StandardLibrary() {
    }

    public static Map<String, EntityDef> definitions() {
        return DEFINITIONS;
    }

    public static boolean contains(String entityName) {
        return entityName != null && DEFINITIONS.containsKey(entityName.toLowerCase(Locale.ROOT));
    }

    public static String architectureStatement(String entityName) {
        if (entityName == null) {
            return null;
        }
        return switch (entityName.toLowerCase(Locale.ROOT)) {
            case "and_gate" -> "y <= a and b;";
            case "or_gate" -> "y <= a or b;";
            case "xor_gate" -> "y <= a xor b;";
            case "not_gate" -> "y <= not a;";
            case "concat" -> "y <= a & b;";
            default -> null;
        };
    }

    private static Map<String, EntityDef> createDefinitions() {
        Map<String, EntityDef> definitions = new LinkedHashMap<>();
        for (String name : List.of("and_gate", "or_gate", "xor_gate")) {
            definitions.put(name, new EntityDef(name, List.of(
                    new Port("a", Direction.IN, "std_logic_vector(WIDTH-1 downto 0)"),
                    new Port("b", Direction.IN, "std_logic_vector(WIDTH-1 downto 0)"),
                    new Port("y", Direction.OUT, "std_logic_vector(WIDTH-1 downto 0)")
            ), List.of("WIDTH : positive := 8")));
        }
        definitions.put("not_gate", new EntityDef("not_gate", List.of(
                new Port("a", Direction.IN, "std_logic_vector(WIDTH-1 downto 0)"),
                new Port("y", Direction.OUT, "std_logic_vector(WIDTH-1 downto 0)")
        ), List.of("WIDTH : positive := 8")));
        definitions.put("concat", new EntityDef("concat", List.of(
                new Port("a", Direction.IN, "std_logic_vector(A_WIDTH-1 downto 0)"),
                new Port("b", Direction.IN, "std_logic_vector(B_WIDTH-1 downto 0)"),
                new Port("y", Direction.OUT, "std_logic_vector(A_WIDTH+B_WIDTH-1 downto 0)")
        ), List.of("A_WIDTH : positive := 8", "B_WIDTH : positive := 8")));
        return Collections.unmodifiableMap(definitions);
    }
}
