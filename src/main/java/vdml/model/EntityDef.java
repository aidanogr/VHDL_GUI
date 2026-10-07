package vdml.model;

import java.util.List;
import java.util.stream.Collectors;

public record EntityDef(String name, List<Port> ports, List<String> generics,
                        List<ArchitectureDef> architectures, String sourceOverride) {
    public EntityDef {
        ports = ports == null ? List.of() : List.copyOf(ports);
        generics = generics == null ? List.of() : List.copyOf(generics);
        architectures = architectures == null ? List.of() : List.copyOf(architectures);
    }

    public EntityDef(String name, List<Port> ports, List<String> generics,
                     List<ArchitectureDef> architectures) {
        this(name, ports, generics, architectures, null);
    }

    public EntityDef(String name, List<Port> ports, List<String> generics) {
        this(name, ports, generics, List.of(new ArchitectureDef("rtl", "", "")));
    }

    public List<Port> inputs() {
        return ports == null ? List.of() : ports.stream()
                .filter(port -> port.dir() == Direction.IN)
                .collect(Collectors.toList());
    }

    public List<Port> outputs() {
        return ports == null ? List.of() : ports.stream()
                .filter(port -> port.dir() == Direction.OUT)
                .collect(Collectors.toList());
    }

    /** "WIDTH : integer := 8" -> "WIDTH". */
    public static String genericName(String formal) {
        return formal.split(":", 2)[0].trim();
    }

    /** "WIDTH : integer := 8" -> "integer". */
    public static String genericType(String formal) {
        int colon = formal.indexOf(':');
        if (colon < 0) {
            return "";
        }
        String rest = formal.substring(colon + 1);
        int assign = rest.indexOf(":=");
        return (assign < 0 ? rest : rest.substring(0, assign)).trim();
    }

    /** "WIDTH : integer := 8" -> "8" (empty when there is no default). */
    public static String genericDefault(String formal) {
        int assign = formal.indexOf(":=");
        return assign < 0 ? "" : formal.substring(assign + 2).trim();
    }
}
