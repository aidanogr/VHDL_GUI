package vdml.io;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import vdml.model.ArchitectureDef;
import vdml.model.DiagramModel;
import vdml.model.EntityDef;
import vdml.model.StandardLibrary;
import vdml.vhdl.VhdlEmitter;

/** Saves a vdml manifest alongside generated and linked VHDL sources. */
public final class VhdlProjectIO {
    public static final String PROJECT_EXTENSION = ".vdmlproj";
    private static final String SOURCE_DIRECTORY = "vhdl";

    private VhdlProjectIO() {
    }

    public static boolean isProjectFile(Path path) {
        return path != null && path.getFileName().toString().toLowerCase(Locale.ROOT)
                .endsWith(PROJECT_EXTENSION);
    }

    public static DiagramModel load(Path manifest) throws IOException {
        DiagramModel model = JsonIO.load(Files.readString(manifest, StandardCharsets.UTF_8));
        Path sourceDirectory = sourceDirectory(manifest);
        if (Files.isDirectory(sourceDirectory)) {
            for (EntityDef entity : new ArrayList<>(model.getEntityCatalog().values())) {
                if (StandardLibrary.contains(entity.name())) {
                    continue;
                }
                Path sourceFile = sourceDirectory.resolve(entity.name() + ".vhd");
                if (Files.isRegularFile(sourceFile)) {
                    String source = Files.readString(sourceFile, StandardCharsets.UTF_8);
                    if (entity.sourceOverride() != null) {
                        if (!entity.sourceOverride().equals(source)) {
                            importImportedEntitySource(model, entity, sourceFile);
                        }
                    } else {
                        importEntitySource(model, entity, source);
                    }
                }
            }
            Path topSource = sourceDirectory.resolve(model.getTopEntity() + ".vhd");
            if (Files.isRegularFile(topSource)) {
                importTopLevelSource(model, Files.readString(topSource, StandardCharsets.UTF_8));
            }
        }
        model.clearUndoHistory();
        return model;
    }

    public static void save(Path manifest, DiagramModel model) throws IOException {
        Path absoluteManifest = manifest.toAbsolutePath().normalize();
        Path parent = absoluteManifest.getParent();
        if (parent == null) {
            throw new IOException("Project manifest must have a parent directory.");
        }
        Files.createDirectories(parent);
        synchronizeVhdlFiles(absoluteManifest, model);
        writeAtomically(absoluteManifest, JsonIO.save(model));
    }

    public static void synchronizeVhdlFiles(Path manifest, DiagramModel model) throws IOException {
        Path directory = sourceDirectory(manifest);
        Files.createDirectories(directory);
        Map<String, String> sourceFiles = generatedFiles(model);
        for (Map.Entry<String, String> file : sourceFiles.entrySet()) {
            Path target = directory.resolve(file.getKey());
            if (!Files.isRegularFile(target)
                    || !Files.readString(target, StandardCharsets.UTF_8).equals(file.getValue())) {
                writeAtomically(target, file.getValue());
            }
        }
        removeStaleGeneratedFiles(directory, sourceFiles.keySet());
    }

    public static boolean importLinkedSource(Path manifest, DiagramModel model, Path sourceFile)
            throws IOException {
        if (!sourceFile.toAbsolutePath().normalize().getParent()
                .equals(sourceDirectory(manifest).toAbsolutePath().normalize())) {
            return false;
        }
        String fileName = sourceFile.getFileName().toString();
        String source = Files.readString(sourceFile, StandardCharsets.UTF_8);
        String existingGenerated = generatedFile(model, fileName);
        if (source.equals(existingGenerated)) {
            return false;
        }
        boolean imported = false;
        if (fileName.equalsIgnoreCase(model.getTopEntity() + ".vhd")) {
            imported = importTopLevelSource(model, source);
        } else {
            for (EntityDef entity : model.getEntityCatalog().values()) {
                if (entity.name().equalsIgnoreCase(removeExtension(fileName))
                        && !StandardLibrary.contains(entity.name())) {
                    imported = entity.sourceOverride() == null
                            ? importEntitySource(model, entity, source)
                            : importImportedEntitySource(model, entity, sourceFile);
                    break;
                }
            }
        }
        String generated = generatedFile(model, fileName);
        if (generated != null && !generated.equals(source)) {
            writeAtomically(sourceFile, generated);
        }
        return imported;
    }

    public static Path sourceDirectory(Path manifest) {
        return manifest.toAbsolutePath().normalize().getParent().resolve(SOURCE_DIRECTORY);
    }

    private static Map<String, String> generatedFiles(DiagramModel model) {
        Map<String, String> files = new LinkedHashMap<>();
        files.put("components_pkg.vhd", VhdlEmitter.emitComponentsPackage(model));
        files.putAll(VhdlEmitter.emitStandardLibraryFiles(model));
        files.putAll(VhdlEmitter.emitEntityArchitectureFiles(model));
        files.put(model.getTopEntity() + ".vhd", VhdlEmitter.emitStructuralArchitecture(model));
        return files;
    }

    private static String generatedFile(DiagramModel model, String fileName) {
        for (Map.Entry<String, String> file : generatedFiles(model).entrySet()) {
            if (file.getKey().equalsIgnoreCase(fileName)) {
                return file.getValue();
            }
        }
        return null;
    }

    private static boolean importEntitySource(DiagramModel model, EntityDef entity, String source)
            throws IOException {
        if (entity.sourceOverride() != null) {
            return false;
        }
        List<ArchitectureDef> updated = new ArrayList<>();
        boolean changed = false;
        for (ArchitectureDef architecture : entity.architectures()) {
            String declarations = extractRegion(source,
                    "-- [[ VDML USER DECLARATIONS:" + architecture.name() + " ]]",
                    "  -- [[ END VDML USER DECLARATIONS ]]");
            String logic = extractRegion(source,
                    "-- [[ VDML USER LOGIC:" + architecture.name() + " ]]",
                    "  -- [[ END VDML USER LOGIC ]]");
            ArchitectureDef revised = new ArchitectureDef(architecture.name(), declarations, logic);
            updated.add(revised);
            changed |= !architecture.equals(revised);
        }
        if (!changed) {
            return false;
        }
        String error = model.defineEntity(entity.name(),
                new EntityDef(entity.name(), entity.ports(), entity.generics(), updated));
        if (error != null) {
            throw new IOException("Could not apply linked VHDL for " + entity.name() + ": " + error);
        }
        return true;
    }

    private static boolean importImportedEntitySource(DiagramModel model, EntityDef previous, Path sourceFile)
            throws IOException {
        EntityDef updated = VhdlEntityIO.readEntity(sourceFile);
        if (!updated.name().equalsIgnoreCase(previous.name())) {
            throw new IOException("Imported source entity name changed from " + previous.name()
                    + " to " + updated.name() + "; the source was retained.");
        }
        if (updated.equals(previous)) {
            return false;
        }
        String error = model.defineEntity(previous.name(), updated);
        if (error != null) {
            throw new IOException("Could not apply imported VHDL for " + previous.name() + ": " + error);
        }
        return true;
    }

    private static boolean importTopLevelSource(DiagramModel model, String source) throws IOException {
        String signals = extractRegion(source, "-- [[ USER SIGNALS ]]", "  -- [[ END USER SIGNALS ]]");
        String logic = extractRegion(source, "-- [[ USER LOGIC ]]", "  -- [[ END USER LOGIC ]]");
        if (signals.equals(model.getUserSignals()) && logic.equals(model.getUserLogic())) {
            return false;
        }
        model.updateTopLevelUserCode(signals, logic);
        return true;
    }

    private static String extractRegion(String source, String startMarker, String endMarker) throws IOException {
        int markerStart = source.indexOf(startMarker);
        if (markerStart < 0) {
            throw new IOException("Linked VHDL is missing generated region " + startMarker + ".");
        }
        int contentStart = source.indexOf('\n', markerStart + startMarker.length());
        int contentEnd = source.indexOf(endMarker, contentStart + 1);
        if (contentStart < 0 || contentEnd < 0) {
            throw new IOException("Linked VHDL has an incomplete generated region " + startMarker + ".");
        }
        contentStart++;
        if (contentStart < contentEnd && source.charAt(contentStart) == '\r') {
            contentStart++;
        }
        if (contentEnd > contentStart && source.charAt(contentEnd - 1) == '\n') {
            contentEnd--;
            if (contentEnd > contentStart && source.charAt(contentEnd - 1) == '\r') {
                contentEnd--;
            }
        }
        return source.substring(contentStart, contentEnd);
    }

    private static void removeStaleGeneratedFiles(Path directory, Set<String> currentFiles) throws IOException {
        Set<String> expected = new LinkedHashSet<>(currentFiles);
        try (DirectoryStream<Path> files = Files.newDirectoryStream(directory, "*.vhd")) {
            for (Path file : files) {
                if (expected.contains(file.getFileName().toString()) || !isGeneratedFile(file)) {
                    continue;
                }
                Files.delete(file);
            }
        }
    }

    private static boolean isGeneratedFile(Path file) throws IOException {
        try (var reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String firstLine = reader.readLine();
            return firstLine != null && (firstLine.startsWith("-- Generated by vdml")
                    || firstLine.startsWith("-- Generated entity interface"));
        }
    }

    private static String removeExtension(String name) {
        int extension = name.lastIndexOf('.');
        return extension < 0 ? name : name.substring(0, extension);
    }

    private static void writeAtomically(Path file, String content) throws IOException {
        Path temporary = Files.createTempFile(file.getParent(), file.getFileName().toString(), ".tmp");
        try {
            Files.writeString(temporary, content, StandardCharsets.UTF_8);
            try {
                Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ex) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}
