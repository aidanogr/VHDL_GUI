package vdml.validation;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import vdml.io.JsonIO;
import vdml.model.Block;
import vdml.model.DiagramModel;
import vdml.model.Direction;
import vdml.model.EntityDef;
import vdml.model.Port;
import vdml.model.StandardLibrary;
import vdml.model.Wire;
import vdml.vhdl.VhdlEmitter;
import vdml.vhdl.VhdlTypeWidths;

public final class DesignValidator {
    public enum Severity {
        PASS, INFO, WARNING, ERROR
    }

    public record Finding(Severity severity, String check, String detail) {
    }

    public record Report(Instant createdAt, String topEntity, int blockCount, int wireCount,
                         List<Finding> findings) {
        public Report {
            findings = List.copyOf(findings);
        }

        public long count(Severity severity) {
            return findings.stream().filter(finding -> finding.severity() == severity).count();
        }

        public String summary() {
            return "Validation " + (count(Severity.ERROR) == 0 ? "passed" : "found errors")
                    + ": " + count(Severity.ERROR) + " error(s), "
                    + count(Severity.WARNING) + " warning(s)";
        }

        public String toText() {
            StringBuilder text = new StringBuilder();
            text.append("vdml design validation report\n")
                    .append("Generated: ").append(createdAt()).append('\n')
                    .append("Top-level entity: ").append(topEntity()).append('\n')
                    .append("Blocks: ").append(blockCount()).append(", wires: ").append(wireCount()).append('\n')
                    .append(summary()).append("\n\n");
            for (Finding finding : findings()) {
                text.append('[').append(finding.severity()).append("] ")
                        .append(finding.check()).append(": ")
                        .append(finding.detail()).append('\n');
            }
            return text.toString();
        }
    }

    private DesignValidator() {
    }

    public static Report validate(DiagramModel model, boolean runCompiler) {
        List<Finding> findings = new ArrayList<>();
        String integrityError = model.checkIntegrity();
        if (integrityError == null) {
            pass(findings, "Model integrity", "No broken block or wire references detected.");
        } else {
            error(findings, "Model integrity", integrityError);
        }
        inspectNamesAndPorts(model, findings);
        inspectConnections(model, findings);
        inspectJsonRoundTrip(model, findings);
        inspectGeneratedVhdl(model, findings);
        if (runCompiler) {
            inspectWithGhdl(model, findings);
        } else {
            info(findings, "GHDL analysis", "Skipped in live mode; use Validate now to run the compiler check.");
        }
        return new Report(Instant.now(), model.getTopEntity(), model.getBlocks().size(),
                model.getWires().size(), findings);
    }

    private static void inspectNamesAndPorts(DiagramModel model, List<Finding> findings) {
        int initialCount = findings.size();
        if (!DiagramModel.isIdentifier(model.getTopEntity())) {
            error(findings, "VHDL names", "Invalid top-level entity identifier: " + model.getTopEntity());
        }
        Set<String> topPortNames = new HashSet<>();
        for (Port port : model.getTopPorts()) {
            checkPort(port, "top-level port", topPortNames, findings);
        }
        Set<String> instanceNames = new HashSet<>();
        for (Block block : model.getBlocks()) {
            String label = block.getInstanceLabel();
            if (!DiagramModel.isIdentifier(label)) {
                error(findings, "VHDL names", "Invalid instance label on block " + block.getId() + ": " + label);
            } else if (!instanceNames.add(label.toLowerCase(Locale.ROOT))) {
                error(findings, "VHDL names", "Duplicate instance label: " + label);
            }
            EntityDef def = model.getEntityCatalog().get(block.getEntityName());
            if (def == null) {
                continue;
            }
            Set<String> portNames = new HashSet<>();
            for (Port port : def.ports()) {
                checkPort(port, "port on entity " + def.name(), portNames, findings);
            }
            Set<String> genericNames = new HashSet<>();
            for (String generic : def.generics()) {
                String name = EntityDef.genericName(generic);
                if (!DiagramModel.isIdentifier(name)) {
                    error(findings, "VHDL generics", "Invalid generic declaration on " + def.name() + ": " + generic);
                } else if (!genericNames.add(name.toLowerCase(Locale.ROOT))) {
                    error(findings, "VHDL generics", "Duplicate generic " + name + " on " + def.name());
                }
            }
            for (String assigned : block.getGenerics().keySet()) {
                if (!genericNames.contains(assigned.toLowerCase(Locale.ROOT))) {
                    warning(findings, "VHDL generics",
                            block.getInstanceLabel() + " assigns unknown generic " + assigned + ".");
                }
            }
        }
        if (findings.size() == initialCount) {
            pass(findings, "VHDL names and declarations", "Entity, instance, port, and generic names are consistent.");
        }
    }

    private static void checkPort(Port port, String owner, Set<String> names, List<Finding> findings) {
        if (!DiagramModel.isIdentifier(port.name())) {
            error(findings, "VHDL ports", "Invalid port name on " + owner + ": " + port.name());
        } else if (!names.add(port.name().toLowerCase(Locale.ROOT))) {
            error(findings, "VHDL ports", "Duplicate port " + port.name() + " on " + owner + ".");
        }
        if (port.dir() == null || port.type() == null || port.type().isBlank()) {
            error(findings, "VHDL ports", "Missing direction or type for " + owner + " port " + port.name());
        }
    }

    private static void inspectConnections(DiagramModel model, List<Finding> findings) {
        int initialCount = findings.size();
        Map<String, Wire> sinkDrivers = new HashMap<>();
        Map<String, String> netSignals = new HashMap<>();
        Set<String> wireIds = new HashSet<>();
        for (Wire wire : model.getWires()) {
            if (wire.id() == null || !wireIds.add(wire.id())) {
                error(findings, "Netlist", "Duplicate or missing wire identifier: " + wire.id());
            }
            if (!DiagramModel.isIdentifier(wire.signalName())) {
                error(findings, "Netlist", "Wire " + wire.id() + " has invalid signal name: " + wire.signalName());
            }
            if (wire.inlineExpression() != null
                    && (wire.inlineExpression().isBlank()
                            || wire.inlineExpression().contains("\n")
                            || wire.inlineExpression().contains("\r"))) {
                error(findings, "Netlist", "Wire " + wire.id() + " has an empty or multiline inline expression.");
            }
            String sinkKey = wire.toBlock() + "." + wire.toPort();
            Wire previous = sinkDrivers.putIfAbsent(sinkKey, wire);
            if (previous != null) {
                error(findings, "Netlist", "Input " + sinkKey + " is driven by both "
                        + previous.fromBlock() + "." + previous.fromPort() + " and "
                        + wire.fromBlock() + "." + wire.fromPort() + ".");
            }
            String sourceKey = wire.fromBlock() + "." + wire.fromPort();
            String previousSignal = netSignals.putIfAbsent(sourceKey, wire.signalName());
            if (previousSignal != null && !previousSignal.equalsIgnoreCase(wire.signalName())) {
                error(findings, "Netlist", "Fan-out from " + sourceKey + " uses inconsistent signal names.");
            }
            Port source = model.endpointPort(wire.fromBlock(), wire.fromPort());
            Port sink = model.endpointPort(wire.toBlock(), wire.toPort());
            if (wire.inlineExpression() == null && source != null && sink != null
                    && !samePortType(model, wire, source, sink)) {
                warning(findings, "Net type compatibility", "Wire " + wire.id() + " joins "
                        + source.type() + " to " + sink.type() + "; confirm the VHDL subtypes are compatible.");
            }
        }
        for (Block block : model.getBlocks()) {
            EntityDef def = model.getEntityCatalog().get(block.getEntityName());
            if (def == null) {
                continue;
            }
            for (Port port : def.ports()) {
                boolean connected = model.getWires().stream().anyMatch(wire ->
                        (wire.toBlock().equals(block.getId()) && wire.toPort().equals(port.name()))
                                || (wire.fromBlock().equals(block.getId()) && wire.fromPort().equals(port.name())));
                if (!connected && port.dir() == Direction.IN) {
                    warning(findings, "Unconnected inputs",
                            block.getInstanceLabel() + "." + port.name() + " is not connected.");
                }
            }
        }
        for (Port port : model.getTopPorts()) {
            if (port.dir() != Direction.OUT) {
                continue;
            }
            boolean driven = model.getWires().stream().anyMatch(wire ->
                    DiagramModel.TOP_ID.equals(wire.toBlock()) && wire.toPort().equals(port.name()));
            if (!driven) {
                warning(findings, "Top-level outputs", "Output " + port.name() + " is not driven.");
            }
        }
        if (findings.size() == initialCount) {
            pass(findings, "Netlist connectivity", "Wire endpoints, fan-out names, and port types are consistent.");
        }
    }

    private static boolean sameType(String left, String right) {
        return left != null && right != null
                && left.replaceAll("\\s+", "").equalsIgnoreCase(right.replaceAll("\\s+", ""));
    }

    private static boolean samePortType(DiagramModel model, Wire wire, Port source, Port sink) {
        String sourceVector = VhdlTypeWidths.vectorTypeName(source.type());
        String sinkVector = VhdlTypeWidths.vectorTypeName(sink.type());
        if (sourceVector != null || sinkVector != null) {
            if (sourceVector == null || sinkVector == null || !sourceVector.equalsIgnoreCase(sinkVector)) {
                return false;
            }
            String sourceWidth = VhdlTypeWidths.widthExpression(model, wire.fromBlock(), wire.fromPort());
            String sinkWidth = VhdlTypeWidths.widthExpression(model, wire.toBlock(), wire.toPort());
            return sourceWidth == null || sinkWidth == null
                    ? sameType(source.type(), sink.type())
                    : sourceWidth.replaceAll("\\s+", "").equalsIgnoreCase(sinkWidth.replaceAll("\\s+", ""));
        }
        return sameType(source.type(), sink.type());
    }

    private static void inspectJsonRoundTrip(DiagramModel model, List<Finding> findings) {
        try {
            String saved = JsonIO.save(model);
            String savedAgain = JsonIO.save(JsonIO.load(saved));
            if (saved.equals(savedAgain)) {
                pass(findings, "JSON round-trip", "Save/load preserves the serialized design.");
            } else {
                error(findings, "JSON round-trip", "The serialized design changes after loading and saving again.");
            }
        } catch (RuntimeException ex) {
            error(findings, "JSON round-trip", "Could not round-trip the current model: " + ex.getMessage());
        }
    }

    private static void inspectGeneratedVhdl(DiagramModel model, List<Finding> findings) {
        final String packageVhdl;
        final String architecture;
        try {
            packageVhdl = VhdlEmitter.emitComponentsPackage(model);
            architecture = VhdlEmitter.emitStructuralArchitecture(model);
        } catch (RuntimeException ex) {
            error(findings, "VHDL generation", "Generator failed: " + ex.getMessage());
            return;
        }

        boolean allChecksPassed = true;
        if (!matches(packageVhdl, "^\\s*package\\s+components_pkg\\s+is\\b")) {
            error(findings, "Generated package", "The component package declaration is missing.");
            allChecksPassed = false;
        }
        if (!matches(architecture, "(?im)^entity\\s+" + Pattern.quote(model.getTopEntity()) + "\\s+is\\b")) {
            error(findings, "Generated entity", "The top-level entity declaration does not match the model.");
            allChecksPassed = false;
        }
        if (!matches(architecture, "(?im)^architecture\\s+structural\\s+of\\s+"
                + Pattern.quote(model.getTopEntity()) + "\\s+is\\b")) {
            error(findings, "Generated architecture", "The structural architecture does not match the model.");
            allChecksPassed = false;
        }
        if (!architecture.toLowerCase(Locale.ROOT).contains("use work.components_pkg.all;")) {
            error(findings, "Generated architecture", "The architecture does not import components_pkg.");
            allChecksPassed = false;
        }
        for (Map.Entry<String, String> standardFile : VhdlEmitter.emitStandardLibraryFiles(model).entrySet()) {
            String entityName = standardFile.getKey().replaceFirst("(?i)\\.vhd$", "");
            String expectedStatement = StandardLibrary.architectureStatement(entityName);
            if (!matches(standardFile.getValue(), "(?im)^entity\\s+" + Pattern.quote(entityName) + "\\s+is\\b")
                    || expectedStatement == null
                    || !standardFile.getValue().toLowerCase(Locale.ROOT)
                            .contains(expectedStatement.toLowerCase(Locale.ROOT))) {
                error(findings, "Generated standard library",
                        "Generated source for " + entityName + " is missing its entity or implementation.");
                allChecksPassed = false;
            }
        }
        String entitySection = entitySection(architecture, model.getTopEntity());
        if (entitySection == null) {
            error(findings, "Generated entity", "Could not isolate the generated top-level entity declaration.");
            allChecksPassed = false;
        } else {
            for (Port port : model.getTopPorts()) {
                String direction = port.dir() == Direction.IN ? "in" : "out";
                if (!containsDeclaration(entitySection, port.name() + ":" + direction + " " + port.type())) {
                    error(findings, "Top-level port declarations",
                            "Generated entity declaration is missing " + port.name() + " : " + direction
                                    + " " + port.type() + ".");
                    allChecksPassed = false;
                }
            }
            for (String generic : model.getTopGenerics()) {
                if (!containsDeclaration(entitySection, generic)) {
                    error(findings, "Top-level generic declarations",
                            "Generated entity declaration is missing generic " + generic + ".");
                    allChecksPassed = false;
                }
            }
        }

        for (Block block : model.getBlocks()) {
            EntityDef def = model.getEntityCatalog().get(block.getEntityName());
            if (def == null) {
                continue;
            }
            String declaration = componentSection(packageVhdl, def.name());
            if (declaration == null) {
                error(findings, "Component declarations", "No package component declaration for " + def.name() + ".");
                allChecksPassed = false;
            } else {
                for (Port port : def.ports()) {
                    String direction = port.dir() == Direction.IN ? "in" : "out";
                    if (!containsDeclaration(declaration, port.name() + ":" + direction + " " + port.type())) {
                        error(findings, "Component declarations", "Declaration for " + def.name()
                                + " is missing port " + port.name() + " : " + direction + " " + port.type() + ".");
                        allChecksPassed = false;
                    }
                }
                for (String generic : def.generics()) {
                    if (!containsDeclaration(declaration, generic)) {
                        error(findings, "Component declarations",
                                "Declaration for " + def.name() + " is missing generic " + generic + ".");
                        allChecksPassed = false;
                    }
                }
            }
            String section = instanceSection(architecture, block);
            if (section == null) {
                error(findings, "Component instances",
                        "Generated architecture is missing instance " + block.getInstanceLabel() + ".");
                allChecksPassed = false;
                continue;
            }
            Map<String, String> connected = connectedPorts(model, block);
            for (Map.Entry<String, String> association : connected.entrySet()) {
                if (!containsAssociation(section, association.getKey(), association.getValue())) {
                    error(findings, "Port-map consistency", block.getInstanceLabel() + "."
                            + association.getKey() + " should map to " + association.getValue() + ".");
                    allChecksPassed = false;
                }
            }
            for (Map.Entry<String, String> generic : block.getGenerics().entrySet()) {
                if (!matches(section, "(?i)\\b" + Pattern.quote(generic.getKey())
                        + "\\s*=>\\s*" + Pattern.quote(generic.getValue()))) {
                    error(findings, "Generic-map consistency", block.getInstanceLabel() + "."
                            + generic.getKey() + " should map to " + generic.getValue() + ".");
                    allChecksPassed = false;
                }
            }
            if (connected.isEmpty() && !def.ports().isEmpty()) {
                error(findings, "Port-map consistency", "Instance " + block.getInstanceLabel()
                        + " has no connected ports; the emitter cannot produce a valid empty port map.");
                allChecksPassed = false;
            }
        }

        for (Wire wire : model.getWires()) {
            if (!DiagramModel.TOP_ID.equals(wire.fromBlock())
                    && !matches(architecture, "(?im)^\\s*signal\\s+" + Pattern.quote(wire.signalName()) + "\\s*:")) {
                error(findings, "Signal declarations",
                        "Generated architecture has no declaration for signal " + wire.signalName() + ".");
                allChecksPassed = false;
            }
            if (DiagramModel.TOP_ID.equals(wire.toBlock())
                    && !matches(architecture, "(?im)^\\s*" + Pattern.quote(wire.toPort())
                            + "\\s*<=\\s*" + Pattern.quote(wire.connectionExpression()) + "\\s*;")) {
                error(findings, "Top-level assignments",
                        "Generated architecture does not assign " + wire.connectionExpression()
                                + " to " + wire.toPort() + ".");
                allChecksPassed = false;
            }
        }
        if (allChecksPassed) {
            pass(findings, "Generated VHDL consistency",
                    "Package, entity, component instances, signal declarations, and wire mappings match the model.");
        }
    }

    private static Map<String, String> connectedPorts(DiagramModel model, Block block) {
        Map<String, String> connected = new HashMap<>();
        for (Wire wire : model.getWires()) {
            if (wire.toBlock().equals(block.getId())) {
                connected.put(wire.toPort(), wire.connectionExpression());
            }
            if (wire.fromBlock().equals(block.getId())) {
                connected.put(wire.fromPort(), wire.signalName());
            }
        }
        return connected;
    }

    private static String instanceSection(String architecture, Block block) {
        Pattern startPattern = Pattern.compile("(?im)^  " + Pattern.quote(block.getInstanceLabel())
                + "\\s*:\\s*" + Pattern.quote(block.getEntityName()) + "\\b");
        Matcher startMatcher = startPattern.matcher(architecture);
        if (!startMatcher.find()) {
            return null;
        }
        int end = architecture.length();
        Matcher nextInstance = Pattern.compile("(?im)^  [A-Za-z][A-Za-z0-9_]*\\s*:")
                .matcher(architecture);
        if (nextInstance.find(startMatcher.end())) {
            end = nextInstance.start();
        }
        return architecture.substring(startMatcher.start(), end);
    }

    private static String componentSection(String packageVhdl, String entityName) {
        Matcher start = Pattern.compile("(?im)^\\s*component\\s+" + Pattern.quote(entityName) + "\\s+is\\b")
                .matcher(packageVhdl);
        if (!start.find()) {
            return null;
        }
        int end = packageVhdl.toLowerCase(Locale.ROOT).indexOf("end component;", start.end());
        return end < 0 ? null : packageVhdl.substring(start.start(), end);
    }

    private static String entitySection(String architecture, String entityName) {
        Matcher start = Pattern.compile("(?im)^entity\\s+" + Pattern.quote(entityName) + "\\s+is\\b")
                .matcher(architecture);
        if (!start.find()) {
            return null;
        }
        int end = architecture.toLowerCase(Locale.ROOT).indexOf("end entity", start.end());
        return end < 0 ? null : architecture.substring(start.start(), end);
    }

    private static boolean containsDeclaration(String section, String expected) {
        return section.replaceAll("\\s+", "").toLowerCase(Locale.ROOT)
                .contains(expected.replaceAll("\\s+", "").toLowerCase(Locale.ROOT));
    }

    private static boolean containsAssociation(String section, String port, String expression) {
        return containsDeclaration(section, port + "=>" + expression);
    }

    private static boolean matches(String text, String expression) {
        return Pattern.compile(expression, Pattern.CASE_INSENSITIVE | Pattern.MULTILINE)
                .matcher(text).find();
    }

    private static void inspectWithGhdl(DiagramModel model, List<Finding> findings) {
        Path temporaryDirectory = null;
        try {
            temporaryDirectory = Files.createTempDirectory("vdml-validation-");
            Path packageFile = temporaryDirectory.resolve("components_pkg.vhd");
            Path architectureFile = temporaryDirectory.resolve("design.vhd");
            Files.writeString(packageFile, VhdlEmitter.emitComponentsPackage(model), StandardCharsets.UTF_8);
            Files.writeString(architectureFile, VhdlEmitter.emitStructuralArchitecture(model), StandardCharsets.UTF_8);
            List<String> files = new ArrayList<>();
            files.add("components_pkg.vhd");
            for (Map.Entry<String, String> standardFile : VhdlEmitter.emitStandardLibraryFiles(model).entrySet()) {
                Files.writeString(temporaryDirectory.resolve(standardFile.getKey()),
                        standardFile.getValue(), StandardCharsets.UTF_8);
                files.add(standardFile.getKey());
            }
            files.add("design.vhd");
            Path log = temporaryDirectory.resolve("ghdl.log");
            for (String file : files) {
                ProcessBuilder builder = new ProcessBuilder("ghdl", "-a", "--std=08",
                        "--workdir=" + temporaryDirectory.toAbsolutePath(), file)
                        .directory(temporaryDirectory.toFile())
                        .redirectErrorStream(true)
                        .redirectOutput(log.toFile());
                Process process;
                try {
                    process = builder.start();
                } catch (IOException ex) {
                    info(findings, "GHDL analysis",
                            "GHDL could not be started; external syntax analysis was skipped: " + ex.getMessage());
                    return;
                }
                if (!process.waitFor(15, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                    process.waitFor();
                    error(findings, "GHDL analysis", "GHDL timed out while analyzing " + file + ".");
                    appendLog(findings, log);
                    return;
                }
                if (process.exitValue() != 0) {
                    error(findings, "GHDL analysis", "GHDL rejected " + file + ".");
                    appendLog(findings, log);
                    return;
                }
            }
            pass(findings, "GHDL analysis",
                    "GHDL analyzed the generated package, standard library components, and structural design.");
        } catch (IOException ex) {
            error(findings, "GHDL analysis", "Could not prepare compiler inputs: " + ex.getMessage());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            error(findings, "GHDL analysis", "Compiler analysis was interrupted.");
        } finally {
            if (temporaryDirectory != null) {
                deleteTemporaryDirectory(temporaryDirectory);
            }
        }
    }

    private static void appendLog(List<Finding> findings, Path log) {
        try {
            String output = Files.readString(log, StandardCharsets.UTF_8).trim();
            if (!output.isEmpty()) {
                findings.add(new Finding(Severity.INFO, "GHDL output", output));
            }
        } catch (IOException ex) {
            warning(findings, "GHDL output", "Could not read compiler output: " + ex.getMessage());
        }
    }

    private static void deleteTemporaryDirectory(Path directory) {
        try (Stream<Path> paths = Files.walk(directory)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        } catch (IOException ex) {
            System.err.println("Could not remove validation temporary files from " + directory + ": " + ex.getMessage());
        }
    }

    private static void pass(List<Finding> findings, String check, String detail) {
        findings.add(new Finding(Severity.PASS, check, detail));
    }

    private static void info(List<Finding> findings, String check, String detail) {
        findings.add(new Finding(Severity.INFO, check, detail));
    }

    private static void warning(List<Finding> findings, String check, String detail) {
        findings.add(new Finding(Severity.WARNING, check, detail));
    }

    private static void error(List<Finding> findings, String check, String detail) {
        findings.add(new Finding(Severity.ERROR, check, detail));
    }
}
