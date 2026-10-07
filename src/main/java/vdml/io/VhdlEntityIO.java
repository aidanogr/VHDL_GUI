package vdml.io;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import vdml.model.ArchitectureDef;
import vdml.model.Direction;
import vdml.model.EntityDef;
import vdml.model.Port;

/** Imports a single VHDL entity as a reusable catalogue component. */
public final class VhdlEntityIO {
    private VhdlEntityIO() {
    }

    public static EntityDef readEntity(Path sourceFile) throws IOException {
        Path sourcePath = sourceFile.toAbsolutePath().normalize();
        String source = Files.readString(sourcePath, StandardCharsets.UTF_8);
        return parseEntity(source, sourcePath.toString());
    }

    public static List<EntityDef> readEntities(Path sourceDirectory) throws IOException {
        Path directory = sourceDirectory.toAbsolutePath().normalize();
        if (!Files.isDirectory(directory)) {
            throw new IOException("VHDL source directory does not exist: " + directory);
        }
        List<Path> files;
        try (var children = Files.list(directory)) {
            files = children.filter(Files::isRegularFile)
                    .filter(path -> {
                        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
                        return name.endsWith(".vhd") || name.endsWith(".vhdl");
                    })
                    .sorted((left, right) -> left.getFileName().toString()
                            .compareToIgnoreCase(right.getFileName().toString()))
                    .toList();
        }

        List<EntityDef> entities = new ArrayList<>();
        for (Path file : files) {
            String source = Files.readString(file, StandardCharsets.UTF_8);
            int count = findEntityDeclarations(tokenize(source)).size();
            if (count == 0) {
                if (file.getFileName().toString().equalsIgnoreCase("components_pkg.vhd")) {
                    continue;
                }
                throw new IOException("VHDL packages without an entity are not imported: " + file);
            }
            if (count != 1) {
                throw new IOException("Expected at most one entity declaration per VHDL file: " + file);
            }
            entities.add(parseEntity(source, file.toString()));
        }
        List<String> names = new ArrayList<>();
        for (EntityDef entity : entities) {
            if (names.stream().anyMatch(name -> name.equalsIgnoreCase(entity.name()))) {
                throw new IOException("VHDL source directory contains duplicate entity " + entity.name() + ".");
            }
            names.add(entity.name());
        }
        return List.copyOf(entities);
    }

    public static EntityDef parseEntity(String source, String sourceDescription) throws IOException {
        if (source == null || source.isBlank()) {
            throw new IOException("VHDL source is empty: " + sourceDescription);
        }
        List<Token> tokens = tokenize(source);
        List<Integer> declarations = findEntityDeclarations(tokens);
        if (declarations.size() != 1) {
            throw new IOException("Expected exactly one entity declaration in " + sourceDescription
                    + ", found " + declarations.size() + ".");
        }

        int entityIndex = declarations.get(0);
        String entityName = tokens.get(entityIndex + 1).text();
        if (!isBasicIdentifier(entityName)) {
            throw new IOException("Unsupported VHDL entity identifier in " + sourceDescription + ": "
                    + entityName);
        }
        int entityEnd = findEntityEnd(tokens, entityIndex + 3, entityName, sourceDescription);
        List<String> generics = new ArrayList<>();
        List<Port> ports = new ArrayList<>();
        int genericClauses = 0;
        int portClauses = 0;
        for (int i = entityIndex + 3; i + 1 < entityEnd; i++) {
            if (!tokens.get(i).is("generic") && !tokens.get(i).is("port")) {
                continue;
            }
            if (!tokens.get(i + 1).is("(")) {
                continue;
            }
            int close = matchingParen(tokens, i + 1, entityEnd, sourceDescription);
            if (tokens.get(i).is("generic")) {
                generics.addAll(parseGenerics(source, tokens, i + 2, close, sourceDescription));
                genericClauses++;
            } else {
                ports.addAll(parsePorts(source, tokens, i + 2, close, sourceDescription));
                portClauses++;
            }
            i = close;
        }
        if (genericClauses > 1 || portClauses > 1) {
            throw new IOException("VHDL entity contains duplicate generic or port clauses: " + entityName);
        }

        List<ArchitectureDef> architectures = parseArchitectures(tokens, entityName);
        return new EntityDef(entityName, ports, generics, architectures, source);
    }

    private static List<Integer> findEntityDeclarations(List<Token> tokens) {
        List<Integer> declarations = new ArrayList<>();
        for (int i = 0; i + 2 < tokens.size(); i++) {
            if (tokens.get(i).is("entity") && tokens.get(i + 2).is("is")) {
                declarations.add(i);
            }
        }
        return declarations;
    }

    private static List<String> parseGenerics(String source, List<Token> tokens, int start, int end,
                                              String sourceDescription) throws IOException {
        List<String> generics = new ArrayList<>();
        for (int[] segment : declarationSegments(tokens, start, end)) {
            int colon = topLevelToken(tokens, segment[0], segment[1], ":");
            if (colon < 0) {
                throw new IOException("Unsupported generic declaration in " + sourceDescription + ": "
                        + tokenText(source, tokens, segment[0], segment[1]));
            }
            int nameStart = segment[0];
            if (tokens.get(nameStart).is("constant")) {
                nameStart++;
            }
            List<String> names = declarationNames(tokens, nameStart, colon, sourceDescription);
            int assignment = topLevelToken(tokens, colon + 1, segment[1], ":=");
            String type = clean(source, tokens, colon + 1, assignment < 0 ? segment[1] : assignment);
            if (type.isBlank()) {
                throw new IOException("Generic declaration has no type in " + sourceDescription + ".");
            }
            String defaultValue = assignment < 0 ? ""
                    : clean(source, tokens, assignment + 1, segment[1]);
            for (String name : names) {
                generics.add(name + " : " + type
                        + (defaultValue.isBlank() ? "" : " := " + defaultValue));
            }
        }
        return generics;
    }

    private static List<Port> parsePorts(String source, List<Token> tokens, int start, int end,
                                         String sourceDescription) throws IOException {
        List<Port> ports = new ArrayList<>();
        for (int[] segment : declarationSegments(tokens, start, end)) {
            int colon = topLevelToken(tokens, segment[0], segment[1], ":");
            if (colon < 0) {
                throw new IOException("Unsupported port declaration in " + sourceDescription + ": "
                        + tokenText(source, tokens, segment[0], segment[1]));
            }
            int nameStart = segment[0];
            if (tokens.get(nameStart).is("signal")) {
                nameStart++;
            }
            List<String> names = declarationNames(tokens, nameStart, colon, sourceDescription);
            int modeIndex = colon + 1;
            Direction direction = Direction.IN;
            if (modeIndex < segment[1] && tokens.get(modeIndex).is("signal")) {
                modeIndex++;
            }
            if (modeIndex < segment[1]
                    && (tokens.get(modeIndex).is("in") || tokens.get(modeIndex).is("out"))) {
                direction = tokens.get(modeIndex).is("in") ? Direction.IN : Direction.OUT;
                modeIndex++;
            } else if (modeIndex < segment[1]) {
                throw new IOException("Port mode '" + tokens.get(modeIndex).text()
                        + "' is not supported by the diagram editor (only in/out ports are supported).");
            }
            int assignment = topLevelToken(tokens, modeIndex, segment[1], ":=");
            if (assignment >= 0) {
                throw new IOException("Ports with default values cannot yet be imported as diagram pins.");
            }
            String type = clean(source, tokens, modeIndex, segment[1]);
            if (type.isBlank()) {
                throw new IOException("Port declaration has no type in " + sourceDescription + ".");
            }
            for (String name : names) {
                ports.add(new Port(name, direction, type));
            }
        }
        return ports;
    }

    private static List<String> declarationNames(List<Token> tokens, int start, int end,
                                                 String sourceDescription) throws IOException {
        List<String> names = new ArrayList<>();
        boolean expectName = true;
        for (int i = start; i < end; i++) {
            Token token = tokens.get(i);
            if (expectName) {
                if (!isBasicIdentifier(token.text())) {
                    throw new IOException("Invalid interface name in " + sourceDescription + ": "
                            + token.text());
                }
                names.add(token.text());
            } else if (!token.is(",")) {
                throw new IOException("Expected ',' in interface declaration in " + sourceDescription + ".");
            }
            expectName = !expectName;
        }
        if (names.isEmpty() || expectName) {
            throw new IOException("Incomplete interface name list in " + sourceDescription + ".");
        }
        return names;
    }

    private static List<int[]> declarationSegments(List<Token> tokens, int start, int end)
            throws IOException {
        List<int[]> segments = new ArrayList<>();
        int segmentStart = start;
        int depth = 0;
        for (int i = start; i < end; i++) {
            Token token = tokens.get(i);
            if (token.is("(")) {
                depth++;
            } else if (token.is(")")) {
                depth--;
            } else if (token.is(";") && depth == 0) {
                if (segmentStart < i) {
                    segments.add(new int[] {segmentStart, i});
                }
                segmentStart = i + 1;
            }
            if (depth < 0) {
                throw new IOException("Unbalanced interface declaration parentheses.");
            }
        }
        if (depth != 0) {
            throw new IOException("Unbalanced interface declaration parentheses.");
        }
        if (segmentStart < end) {
            segments.add(new int[] {segmentStart, end});
        }
        return segments;
    }

    private static int topLevelToken(List<Token> tokens, int start, int end, String value) {
        int depth = 0;
        for (int i = start; i < end; i++) {
            Token token = tokens.get(i);
            if (depth == 0 && token.is(value)) {
                return i;
            }
            if (token.is("(")) {
                depth++;
            } else if (token.is(")")) {
                depth--;
            }
        }
        return -1;
    }

    private static int findEntityEnd(List<Token> tokens, int start, String entityName,
                                     String sourceDescription) throws IOException {
        for (int i = start; i < tokens.size(); i++) {
            if (!tokens.get(i).is("end")) {
                continue;
            }
            if (i + 1 < tokens.size() && tokens.get(i + 1).is("entity")) {
                return i;
            }
            if (i + 2 < tokens.size() && tokens.get(i + 1).is(entityName)
                    && tokens.get(i + 2).is(";")) {
                return i;
            }
        }
        throw new IOException("Could not find the end of entity " + entityName + " in " + sourceDescription);
    }

    private static int matchingParen(List<Token> tokens, int open, int end, String sourceDescription)
            throws IOException {
        int depth = 0;
        for (int i = open; i < end; i++) {
            if (tokens.get(i).is("(")) {
                depth++;
            } else if (tokens.get(i).is(")") && --depth == 0) {
                return i;
            }
        }
        throw new IOException("Unclosed interface clause in " + sourceDescription);
    }

    private static List<ArchitectureDef> parseArchitectures(List<Token> tokens, String entityName) {
        List<ArchitectureDef> architectures = new ArrayList<>();
        for (int i = 0; i + 4 < tokens.size(); i++) {
            if (tokens.get(i).is("architecture") && tokens.get(i + 2).is("of")
                    && tokens.get(i + 3).is(entityName) && tokens.get(i + 4).is("is")) {
                String name = tokens.get(i + 1).text();
                if (architectures.stream().noneMatch(architecture ->
                        architecture.name().equalsIgnoreCase(name))) {
                    architectures.add(new ArchitectureDef(name, "", ""));
                }
            }
        }
        return architectures;
    }

    private static String clean(String source, List<Token> tokens, int start, int end) {
        return start >= end ? "" : stripComments(source.substring(
                tokens.get(start).start(), tokens.get(end - 1).end())).trim();
    }

    private static String tokenText(String source, List<Token> tokens, int start, int end) {
        return start >= end ? "" : source.substring(tokens.get(start).start(), tokens.get(end - 1).end()).trim();
    }

    private static String stripComments(String source) {
        StringBuilder result = new StringBuilder(source.length());
        boolean inString = false;
        for (int i = 0; i < source.length(); i++) {
            char current = source.charAt(i);
            if (inString) {
                result.append(current);
                if (current == '"' && i + 1 < source.length() && source.charAt(i + 1) == '"') {
                    result.append(source.charAt(++i));
                } else if (current == '"') {
                    inString = false;
                }
            } else if (current == '"') {
                inString = true;
                result.append(current);
            } else if (current == '-' && i + 1 < source.length() && source.charAt(i + 1) == '-') {
                while (i < source.length() && source.charAt(i) != '\n') {
                    i++;
                }
                result.append(' ');
                if (i < source.length()) {
                    result.append('\n');
                }
            } else {
                result.append(current);
            }
        }
        return result.toString();
    }

    private static List<Token> tokenize(String source) {
        List<Token> tokens = new ArrayList<>();
        for (int i = 0; i < source.length();) {
            char current = source.charAt(i);
            if (Character.isWhitespace(current)) {
                i++;
                continue;
            }
            if (current == '-' && i + 1 < source.length() && source.charAt(i + 1) == '-') {
                while (i < source.length() && source.charAt(i) != '\n') {
                    i++;
                }
                continue;
            }
            int start = i;
            if (current == '"') {
                i++;
                while (i < source.length()) {
                    if (source.charAt(i++) == '"') {
                        if (i < source.length() && source.charAt(i) == '"') {
                            i++;
                        } else {
                            break;
                        }
                    }
                }
            } else if (Character.isLetter(current) || current == '_') {
                i++;
                while (i < source.length()
                        && (Character.isLetterOrDigit(source.charAt(i)) || source.charAt(i) == '_')) {
                    i++;
                }
            } else if (current == '\'' && i + 2 < source.length() && source.charAt(i + 2) == '\'') {
                i += 3;
            } else if (current == ':' && i + 1 < source.length() && source.charAt(i + 1) == '=') {
                i += 2;
            } else {
                i++;
            }
            tokens.add(new Token(source.substring(start, i), start, i));
        }
        return tokens;
    }

    private static boolean isBasicIdentifier(String value) {
        return value != null && value.matches("[A-Za-z][A-Za-z0-9_]*")
                && !VHDL_RESERVED.contains(value.toLowerCase(Locale.ROOT));
    }

    private static final java.util.Set<String> VHDL_RESERVED = java.util.Set.of(
            "abs", "access", "after", "alias", "all", "and", "architecture", "array", "assert",
            "attribute", "begin", "block", "body", "buffer", "bus", "case", "component",
            "configuration", "constant", "context", "else", "elsif", "end", "entity", "exit",
            "file", "for", "function", "generate", "generic", "group", "guarded", "if", "impure",
            "in", "inertial", "inout", "is", "label", "library", "linkage", "literal", "loop",
            "map", "mod", "nand", "new", "next", "nor", "not", "null", "of", "on", "open",
            "or", "others", "out", "package", "parameter", "port", "postponed", "procedure",
            "process", "pure", "range", "record", "register", "reject", "rem", " report",
            "report", "return", "rol", "ror", "select", "severity", "shared", "signal", "sla", "sll",
            "sra", "srl", "subtype", "then", "to", "transport", "type", "unaffected", "units",
            "until", "use", "variable", "wait", "when", "while", "with", "xnor", "xor");

    private record Token(String text, int start, int end) {
        boolean is(String expected) {
            return text.equalsIgnoreCase(expected);
        }
    }
}
