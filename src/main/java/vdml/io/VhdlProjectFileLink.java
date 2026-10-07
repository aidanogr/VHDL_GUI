package vdml.io;

import java.io.IOException;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.Files;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.function.Consumer;

import javax.swing.SwingUtilities;

import vdml.model.DiagramModel;

/** Watches a project's VHDL directory and imports edits to its marked user-code regions. */
public final class VhdlProjectFileLink implements AutoCloseable {
    private final Path manifest;
    private final DiagramModel model;
    private final Consumer<String> errorHandler;
    private final WatchService watchService;
    private final Thread watcherThread;
    private volatile boolean closed;

    public VhdlProjectFileLink(Path manifest, DiagramModel model, Consumer<String> errorHandler)
            throws IOException {
        this.manifest = manifest.toAbsolutePath().normalize();
        this.model = model;
        this.errorHandler = errorHandler;
        Path sourceDirectory = VhdlProjectIO.sourceDirectory(this.manifest);
        watchService = FileSystems.getDefault().newWatchService();
        sourceDirectory.register(watchService,
                StandardWatchEventKinds.ENTRY_CREATE,
                StandardWatchEventKinds.ENTRY_MODIFY,
                StandardWatchEventKinds.ENTRY_DELETE);
        watcherThread = new Thread(this::watchForChanges, "vdml-vhdl-project-watcher");
        watcherThread.setDaemon(true);
        watcherThread.start();
    }

    @Override
    public void close() {
        closed = true;
        try {
            watchService.close();
        } catch (IOException ex) {
            reportError("Could not close the VHDL project watcher: " + ex.getMessage());
        }
        watcherThread.interrupt();
    }

    private void watchForChanges() {
        while (!closed) {
            WatchKey key;
            try {
                key = watchService.take();
            } catch (ClosedWatchServiceException ex) {
                return;
            } catch (InterruptedException ex) {
                if (!closed) {
                    Thread.currentThread().interrupt();
                    reportError("The VHDL project watcher was interrupted.");
                }
                return;
            }

            Set<Path> changedFiles = collectChangedFiles(key);
            if (changedFiles.isEmpty()) {
                continue;
            }
            try {
                Thread.sleep(150);
            } catch (InterruptedException ex) {
                if (closed) {
                    return;
                }
                Thread.currentThread().interrupt();
                reportError("The VHDL project watcher was interrupted.");
                return;
            }
            for (Path changedFile : changedFiles) {
                importChangedFile(changedFile);
            }
        }
    }

    private Set<Path> collectChangedFiles(WatchKey key) {
        Set<Path> changedFiles = new LinkedHashSet<>();
        for (WatchEvent<?> event : key.pollEvents()) {
            if (event.kind() == StandardWatchEventKinds.OVERFLOW
                    || !(event.context() instanceof Path relativePath)
                    || !relativePath.getFileName().toString().toLowerCase(java.util.Locale.ROOT).endsWith(".vhd")) {
                continue;
            }
            changedFiles.add(VhdlProjectIO.sourceDirectory(manifest).resolve(relativePath));
        }
        if (!key.reset()) {
            reportError("The VHDL source directory is no longer available to watch.");
        }
        return changedFiles;
    }

    private void importChangedFile(Path sourceFile) {
        SwingUtilities.invokeLater(() -> {
            if (closed) {
                return;
            }
            try {
                if (!Files.isRegularFile(sourceFile)) {
                    VhdlProjectIO.synchronizeVhdlFiles(manifest, model);
                    return;
                }
                VhdlProjectIO.importLinkedSource(manifest, model, sourceFile);
            } catch (IOException | RuntimeException ex) {
                errorHandler.accept("Could not import " + sourceFile.getFileName() + ": " + ex.getMessage());
            }
        });
    }

    private void reportError(String message) {
        SwingUtilities.invokeLater(() -> {
            if (!closed) {
                errorHandler.accept(message);
            }
        });
    }
}
