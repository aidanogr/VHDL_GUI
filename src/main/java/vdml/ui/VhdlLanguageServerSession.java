package vdml.ui;

import java.nio.file.Path;
import java.nio.file.Files;
import java.time.Duration;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.List;
import java.util.function.Consumer;
import java.util.prefs.Preferences;

import org.eclipse.lsp4j.ClientCapabilities;
import org.eclipse.lsp4j.DidChangeTextDocumentParams;
import org.eclipse.lsp4j.DidOpenTextDocumentParams;
import org.eclipse.lsp4j.InitializeParams;
import org.eclipse.lsp4j.PublishDiagnosticsParams;
import org.eclipse.lsp4j.TextDocumentContentChangeEvent;
import org.eclipse.lsp4j.TextDocumentItem;
import org.eclipse.lsp4j.VersionedTextDocumentIdentifier;
import org.eclipse.lsp4j.launch.LSPLauncher;
import org.eclipse.lsp4j.services.LanguageClient;
import org.eclipse.lsp4j.services.LanguageServer;

/** Owns one stdio VHDL language-server process for an architecture editor. */
final class VhdlLanguageServerSession implements AutoCloseable {
    private static final String EXECUTABLE_KEY = "vhdlLanguageServerExecutable";
    private static final Duration INITIALIZE_TIMEOUT = Duration.ofSeconds(15);
    private static final Duration SHUTDOWN_TIMEOUT = Duration.ofSeconds(10);

    private final String executable;
    private final Path workspaceRoot;
    private final String documentUri;
    private final Consumer<PublishDiagnosticsParams> diagnosticsHandler;
    private Process process;
    private LanguageServer server;
    private Path librariesConfigurationDirectory;
    private int documentVersion = 1;
    private boolean closed;

    static String configuredExecutable() {
        String configured = Preferences.userNodeForPackage(Main.class).get(EXECUTABLE_KEY, "").trim();
        return configured.isEmpty() ? findExecutableOnPath() : configured;
    }

    static void setConfiguredExecutable(String executable) {
        Preferences.userNodeForPackage(Main.class).put(EXECUTABLE_KEY, executable == null ? "" : executable.trim());
    }

    VhdlLanguageServerSession(String executable, Path workspaceRoot, Path documentPath,
                              Consumer<PublishDiagnosticsParams> diagnosticsHandler) {
        this.executable = executable;
        this.workspaceRoot = workspaceRoot.toAbsolutePath().normalize();
        this.documentUri = documentPath.toAbsolutePath().normalize().toUri().toString();
        this.diagnosticsHandler = diagnosticsHandler;
    }

    void start(String initialText) throws Exception {
        List<String> command = new ArrayList<>();
        command.add(executable);
        configureGhdlStandardLibraries(command);
        process = new ProcessBuilder(command)
                .directory(workspaceRoot.toFile())
                .start();
        Process startedProcess = process;
        Thread errorReader = new Thread(() -> drainErrorStream(startedProcess),
                "vdml-vhdl-lsp-stderr");
        errorReader.setDaemon(true);
        errorReader.start();

        LanguageClient client = new LanguageClient() {
            @Override
            public void telemetryEvent(Object object) {
            }

            @Override
            public void publishDiagnostics(PublishDiagnosticsParams params) {
                if (documentUri.equals(params.getUri())) {
                    diagnosticsHandler.accept(params);
                }
            }

            @Override
            public void showMessage(org.eclipse.lsp4j.MessageParams params) {
            }

            @Override
            public CompletableFuture<org.eclipse.lsp4j.MessageActionItem> showMessageRequest(
                    org.eclipse.lsp4j.ShowMessageRequestParams params) {
                return CompletableFuture.completedFuture(null);
            }

            @Override
            public void logMessage(org.eclipse.lsp4j.MessageParams params) {
            }
        };
        var launcher = LSPLauncher.createClientLauncher(client, process.getInputStream(), process.getOutputStream());
        launcher.startListening();
        server = launcher.getRemoteProxy();

        InitializeParams initialize = new InitializeParams();
        initialize.setRootPath(workspaceRoot.toString());
        initialize.setRootUri(workspaceRoot.toUri().toString());
        initialize.setCapabilities(new ClientCapabilities());
        try {
            server.initialize(initialize).get(INITIALIZE_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (ExecutionException | TimeoutException ex) {
            if (!process.isAlive()) {
                throw new IOException("VHDL language server exited during initialization (exit code "
                        + process.exitValue() + "). Check its standard-library configuration.", ex);
            }
            throw ex;
        }
        server.initialized();
        server.getTextDocumentService().didOpen(new DidOpenTextDocumentParams(
                new TextDocumentItem(documentUri, "vhdl", documentVersion, initialText)));
    }

    void updateDocument(String text) {
        if (closed || server == null) {
            return;
        }
        documentVersion++;
        VersionedTextDocumentIdentifier document =
                new VersionedTextDocumentIdentifier(documentUri, documentVersion);
        server.getTextDocumentService().didChange(new DidChangeTextDocumentParams(document,
                List.of(new TextDocumentContentChangeEvent(text))));
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        Process runningProcess = process;
        LanguageServer runningServer = server;
        Thread shutdownThread = new Thread(() -> stopServer(runningServer, runningProcess),
                "vdml-vhdl-lsp-shutdown");
        shutdownThread.setDaemon(true);
        shutdownThread.start();
    }

    private void stopServer(LanguageServer runningServer, Process runningProcess) {
        if (runningServer != null && runningProcess != null && runningProcess.isAlive()) {
            try {
                runningServer.shutdown().get(SHUTDOWN_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
                runningServer.exit();
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            } catch (ExecutionException | TimeoutException | RuntimeException ex) {
                if (runningProcess.isAlive()) {
                    System.err.println("Could not shut down VHDL language server cleanly: " + ex.getMessage());
                }
            }
        }
        try {
            if (runningProcess != null) {
                runningProcess.destroy();
                if (!runningProcess.waitFor(SHUTDOWN_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                    runningProcess.destroyForcibly();
                }
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            if (runningProcess != null) {
                runningProcess.destroyForcibly();
            }
        } finally {
            removeLibrariesConfiguration();
        }
    }

    private void drainErrorStream(Process startedProcess) {
        try (InputStream errorStream = startedProcess.getErrorStream()) {
            errorStream.transferTo(java.io.OutputStream.nullOutputStream());
        } catch (IOException ex) {
            if (startedProcess.isAlive()) {
                System.err.println("Could not read VHDL language-server output: " + ex.getMessage());
            }
        }
    }

    private static String findExecutableOnPath() {
        String pathValue = System.getenv("PATH");
        if (pathValue == null || pathValue.isBlank()) {
            return "";
        }
        List<String> executableNames = new ArrayList<>();
        if (System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win")) {
            executableNames.add("vhdl_ls.exe");
        }
        executableNames.add("vhdl_ls");

        for (String directory : pathValue.split(java.util.regex.Pattern.quote(java.io.File.pathSeparator))) {
            String normalizedDirectory = directory.trim();
            if (normalizedDirectory.length() >= 2 && normalizedDirectory.startsWith("\"")
                    && normalizedDirectory.endsWith("\"")) {
                normalizedDirectory = normalizedDirectory.substring(1, normalizedDirectory.length() - 1);
            }
            if (normalizedDirectory.isEmpty()) {
                continue;
            }
            for (String executableName : executableNames) {
                Path candidate = Path.of(normalizedDirectory, executableName);
                if (Files.isRegularFile(candidate) && Files.isExecutable(candidate)) {
                    return candidate.toAbsolutePath().normalize().toString();
                }
            }
        }
        return "";
    }

    private void configureGhdlStandardLibraries(List<String> command) throws IOException {
        Path ghdlSourceDirectory = findGhdlSourceDirectory();
        if (ghdlSourceDirectory == null) {
            return;
        }
        Path standardSources = ghdlSourceDirectory.resolve("std").resolve("v08");
        Path ieeeSources = ghdlSourceDirectory.resolve("ieee2008");
        if (!Files.isDirectory(standardSources) || !Files.isDirectory(ieeeSources)) {
            return;
        }
        librariesConfigurationDirectory = Files.createTempDirectory("vdml-vhdl-libraries-");
        String configuration = "[libraries]\n"
                + "std.files = [\"" + tomlGlob(standardSources) + "\"]\n"
                + "ieee.files = [\"" + tomlGlob(ieeeSources) + "\"]\n";
        Files.writeString(librariesConfigurationDirectory.resolve("vhdl_ls.toml"), configuration);
        command.add("--libraries");
        command.add(librariesConfigurationDirectory.toString());
    }

    private Path findGhdlSourceDirectory() {
        String pathValue = System.getenv("PATH");
        if (pathValue == null || pathValue.isBlank()) {
            return null;
        }
        for (String directory : pathValue.split(java.util.regex.Pattern.quote(java.io.File.pathSeparator))) {
            String normalizedDirectory = directory.trim();
            if (normalizedDirectory.length() >= 2 && normalizedDirectory.startsWith("\"")
                    && normalizedDirectory.endsWith("\"")) {
                normalizedDirectory = normalizedDirectory.substring(1, normalizedDirectory.length() - 1);
            }
            if (normalizedDirectory.isEmpty()) {
                continue;
            }
            for (String executableName : List.of("ghdl.exe", "ghdl")) {
                Path executablePath = Path.of(normalizedDirectory, executableName);
                if (!Files.isRegularFile(executablePath)) {
                    continue;
                }
                Path installationRoot = executablePath.toAbsolutePath().normalize().getParent().getParent();
                Path sourceDirectory = installationRoot.resolve("lib").resolve("ghdl").resolve("src");
                if (Files.isDirectory(sourceDirectory)) {
                    return sourceDirectory;
                }
            }
        }
        return null;
    }

    private String tomlGlob(Path directory) {
        return (directory.toAbsolutePath().normalize().toString() + java.io.File.separator + "*.vhdl")
                .replace('\\', '/').replace("\"", "\\\"");
    }

    private void removeLibrariesConfiguration() {
        if (librariesConfigurationDirectory == null) {
            return;
        }
        try {
            Files.deleteIfExists(librariesConfigurationDirectory.resolve("vhdl_ls.toml"));
            Files.deleteIfExists(librariesConfigurationDirectory);
        } catch (IOException ex) {
            System.err.println("Could not remove temporary VHDL library configuration: " + ex.getMessage());
        }
        librariesConfigurationDirectory = null;
    }
}
