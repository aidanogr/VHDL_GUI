package vdml.vhdl;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

/** Parses VHDL source with GHDL and exposes architecture-scoped syntax nodes. */
public final class GhdlVhdlParser {
    private final String executable;

    public GhdlVhdlParser() {
        this("ghdl");
    }

    public GhdlVhdlParser(String executable) {
        if (executable == null || executable.isBlank()) {
            throw new IllegalArgumentException("GHDL executable cannot be blank.");
        }
        this.executable = executable;
    }

    public void checkSyntax(Path sourceFile) throws IOException {
        Path source = Objects.requireNonNull(sourceFile, "VHDL source path")
                .toAbsolutePath().normalize();
        if (!Files.isRegularFile(source)) {
            throw new IOException("VHDL source file does not exist: " + source);
        }
        Path temporaryDirectory = Files.createTempDirectory("vdml-ghdl-syntax-");
        try {
            Path diagnosticsFile = temporaryDirectory.resolve("ghdl-diagnostics.txt");
            Process process;
            try {
                process = new ProcessBuilder(executable, "-s", "--std=08", source.toString())
                        .redirectErrorStream(true)
                        .redirectOutput(diagnosticsFile.toFile())
                        .start();
            } catch (IOException exception) {
                throw new IOException("Could not start GHDL VHDL parser '" + executable + "'.", exception);
            }
            try {
                if (!process.waitFor(30, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                    process.waitFor();
                    throw new IOException("GHDL timed out while checking VHDL syntax.");
                }
            } catch (InterruptedException exception) {
                process.destroyForcibly();
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted while GHDL was checking VHDL syntax.", exception);
            }
            if (process.exitValue() != 0) {
                String diagnostics = Files.exists(diagnosticsFile)
                        ? Files.readString(diagnosticsFile, StandardCharsets.UTF_8).trim()
                        : "";
                throw new IOException("GHDL rejected the VHDL source."
                        + (diagnostics.isEmpty() ? "" : System.lineSeparator() + diagnostics));
            }
        } finally {
            deleteTemporaryDirectory(temporaryDirectory);
        }
    }

    public ParsedDesign parse(List<Path> sourceFiles) throws IOException {
        if (sourceFiles == null || sourceFiles.isEmpty()) {
            throw new IllegalArgumentException("At least one VHDL source file is required.");
        }

        List<Path> sources = new ArrayList<>(sourceFiles.size());
        for (Path source : sourceFiles) {
            Path normalized = Objects.requireNonNull(source, "VHDL source path")
                    .toAbsolutePath().normalize();
            if (!Files.isRegularFile(normalized)) {
                throw new IOException("VHDL source file does not exist: " + normalized);
            }
            sources.add(normalized);
        }

        Path temporaryDirectory = Files.createTempDirectory("vdml-ghdl-ast-");
        try {
            Path xmlFile = temporaryDirectory.resolve("design.xml");
            Path errorFile = temporaryDirectory.resolve("ghdl-errors.txt");
            List<String> command = new ArrayList<>();
            command.add(executable);
            command.add("--file-to-xml");
            command.add("--std=08");
            command.add("--workdir=" + temporaryDirectory.toAbsolutePath());
            for (Path source : sources) {
                command.add(source.toString());
            }

            Process process;
            try {
                process = new ProcessBuilder(command)
                        .redirectOutput(xmlFile.toFile())
                        .redirectError(errorFile.toFile())
                        .start();
            } catch (IOException exception) {
                throw new IOException("Could not start GHDL VHDL parser '" + executable + "'.", exception);
            }

            int exitCode;
            try {
                if (!process.waitFor(30, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                    process.waitFor();
                    throw new IOException("GHDL timed out while parsing VHDL sources.");
                }
                exitCode = process.exitValue();
            } catch (InterruptedException exception) {
                process.destroyForcibly();
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted while GHDL was parsing VHDL sources.", exception);
            }
            if (exitCode != 0) {
                String diagnostics = Files.exists(errorFile)
                        ? Files.readString(errorFile, StandardCharsets.UTF_8).trim()
                        : "";
                throw new IOException("GHDL could not parse the VHDL sources."
                        + (diagnostics.isEmpty() ? "" : System.lineSeparator() + diagnostics));
            }
            if (!Files.isRegularFile(xmlFile)) {
                throw new IOException("GHDL completed without producing an AST.");
            }
            return readAst(xmlFile, sources);
        } finally {
            deleteTemporaryDirectory(temporaryDirectory);
        }
    }

    private static ParsedDesign readAst(Path xmlFile, List<Path> sources) throws IOException {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            Element root = factory.newDocumentBuilder().parse(xmlFile.toFile()).getDocumentElement();

            List<ParsedArchitecture> architectures = new ArrayList<>();
            collectArchitectures(root, sources, architectures);
            return new ParsedDesign(architectures);
        } catch (ParserConfigurationException | SAXException exception) {
            throw new IOException("Could not read GHDL's VHDL AST.", exception);
        }
    }

    private static void collectArchitectures(Element parent, List<Path> sources,
                                             List<ParsedArchitecture> architectures) {
        if ("architecture_body".equals(parent.getAttribute("kind"))
                && belongsToInput(parent.getAttribute("file"), sources)) {
            Element entityName = firstChild(parent, "entity_name");
            Element declarations = firstChild(parent, "declaration_chain");
            Element statements = firstChild(parent, "concurrent_statement_chain");
            architectures.add(new ParsedArchitecture(
                    identifier(entityName),
                    parent.getAttribute("identifier"),
                    parseLine(parent.getAttribute("line")),
                    nodes(declarations),
                    nodes(statements)));
            return;
        }

        for (Element child : childElements(parent)) {
            collectArchitectures(child, sources, architectures);
        }
    }

    private static List<ParsedNode> nodes(Element chain) {
        if (chain == null) {
            return List.of();
        }
        List<ParsedNode> result = new ArrayList<>();
        for (Element child : childElements(chain)) {
            if (child.hasAttribute("kind") && !child.hasAttribute("ref")) {
                result.add(node(child));
            }
        }
        return List.copyOf(result);
    }

    private static ParsedNode node(Element element) {
        Map<String, String> attributes = new LinkedHashMap<>();
        for (int i = 0; i < element.getAttributes().getLength(); i++) {
            Node attribute = element.getAttributes().item(i);
            String name = attribute.getNodeName();
            if (!name.equals("id") && !name.equals("file") && !name.equals("parent")) {
                attributes.put(name, attribute.getNodeValue());
            }
        }

        List<ParsedNode> children = new ArrayList<>();
        for (Element child : childElements(element)) {
            if (child.hasAttribute("kind") && !child.hasAttribute("ref")) {
                children.add(node(child));
            } else if (!child.hasAttribute("ref")) {
                for (Element nested : childElements(child)) {
                    if (nested.hasAttribute("kind") && !nested.hasAttribute("ref")) {
                        children.add(node(nested));
                    }
                }
            }
        }
        return new ParsedNode(element.getAttribute("kind"), attributes, children);
    }

    private static String identifier(Element element) {
        return element == null ? "" : element.getAttribute("identifier");
    }

    private static int parseLine(String line) {
        try {
            return Integer.parseInt(line);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("GHDL AST contained an invalid source line: " + line, exception);
        }
    }

    private static boolean belongsToInput(String fileName, List<Path> sources) {
        if (fileName.isBlank()) {
            return false;
        }
        Path astPath;
        try {
            astPath = Path.of(fileName).toAbsolutePath().normalize();
        } catch (RuntimeException exception) {
            return false;
        }
        for (Path source : sources) {
            if (astPath.equals(source) || astPath.toString().equalsIgnoreCase(source.toString())) {
                return true;
            }
        }
        return false;
    }

    private static Element firstChild(Element parent, String name) {
        if (parent == null) {
            return null;
        }
        for (Element child : childElements(parent)) {
            if (name.equals(child.getTagName())) {
                return child;
            }
        }
        return null;
    }

    private static List<Element> childElements(Element parent) {
        List<Element> elements = new ArrayList<>();
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child instanceof Element element) {
                elements.add(element);
            }
        }
        return elements;
    }

    private static void deleteTemporaryDirectory(Path directory) throws IOException {
        if (!Files.exists(directory)) {
            return;
        }
        try (var paths = Files.walk(directory)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    public record ParsedDesign(List<ParsedArchitecture> architectures) {
        public ParsedDesign {
            architectures = List.copyOf(architectures);
        }
    }

    public record ParsedArchitecture(String entityName, String name, int line,
                                     List<ParsedNode> declarations, List<ParsedNode> statements) {
        public ParsedArchitecture {
            declarations = List.copyOf(declarations);
            statements = List.copyOf(statements);
        }
    }

    public record ParsedNode(String kind, Map<String, String> attributes, List<ParsedNode> children) {
        public ParsedNode {
            attributes = Map.copyOf(attributes);
            children = List.copyOf(children);
        }
    }
}
