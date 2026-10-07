package vdml.ui;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.SwingWorker;
import javax.swing.SwingUtilities;
import javax.swing.Timer;

import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.DiagnosticSeverity;
import vdml.model.ArchitectureDef;
import vdml.model.DiagramModel;
import vdml.model.EntityDef;
import vdml.io.JsonIO;
import vdml.vhdl.VhdlEmitter;

final class ArchitectureEditorDialog extends JDialog {
    private static final Pattern GHDL_LOCATION = Pattern.compile("(?m)^.+?:(\\d+):\\d+:");

    private final DiagramModel model;
    private final EntityDef entity;
    private final ArchitectureDef original;
    private final boolean topLevel;
    private final Path projectVhdlDirectory;
    private final VhdlCodeArea generatedPreview = new VhdlCodeArea();
    private final VhdlCodeArea declarations = new VhdlCodeArea();
    private final VhdlCodeArea logic = new VhdlCodeArea();
    private final JLabel diagnostics = new JLabel("GHDL check pending");
    private final JLabel vimStatus = new JLabel("Vim: off");
    private final JLabel lspStatus = new JLabel("LSP: checking PATH…");
    private final Timer diagnosticsTimer;
    private ArchitectureDef result;
    private SwingWorker<SyntaxDiagnostic, Void> diagnosticWorker;
    private SwingWorker<Void, Void> lspStartupWorker;
    private volatile VhdlLanguageServerSession lspSession;
    private Path lspTempDirectory;
    private String lspDocumentSource = "";
    private List<Diagnostic> lspDiagnostics = List.of();
    private int lspDocumentVersion = 1;
    private volatile boolean lspReady;
    private boolean diagnosticPending;
    private int diagnosticRevision;

    static ArchitectureDef show(java.awt.Window owner, DiagramModel model, EntityDef entity,
                                ArchitectureDef architecture) {
        return show(owner, model, entity, architecture, null);
    }

    static ArchitectureDef show(java.awt.Window owner, DiagramModel model, EntityDef entity,
                                ArchitectureDef architecture, Path projectVhdlDirectory) {
        ArchitectureEditorDialog dialog = new ArchitectureEditorDialog(
                owner, model, entity, architecture, false, projectVhdlDirectory);
        dialog.setVisible(true);
        return dialog.result;
    }

    static void editTopLevel(java.awt.Window owner, DiagramModel model) {
        editTopLevel(owner, model, null);
    }

    static void editTopLevel(java.awt.Window owner, DiagramModel model, Path projectVhdlDirectory) {
        ArchitectureDef architecture = new ArchitectureDef("structural",
                model.getUserSignals(), model.getUserLogic());
        EntityDef entity = new EntityDef(model.getTopEntity(), model.getTopPorts(),
                model.getTopGenerics(), List.of(architecture));
        ArchitectureEditorDialog dialog =
                new ArchitectureEditorDialog(owner, model, entity, architecture, true, projectVhdlDirectory);
        dialog.setVisible(true);
        if (dialog.result != null) {
            model.updateTopLevelUserCode(dialog.result.declarations(), dialog.result.logic());
        }
    }

    private ArchitectureEditorDialog(java.awt.Window owner, DiagramModel model, EntityDef entity,
                                     ArchitectureDef architecture, boolean topLevel,
                                     Path projectVhdlDirectory) {
        super(owner, topLevel ? "Top-level architecture code"
                : "Architecture " + entity.name() + "(" + architecture.name() + ")",
                ModalityType.APPLICATION_MODAL);
        this.model = model;
        this.entity = entity;
        this.original = architecture;
        this.topLevel = topLevel;
        this.projectVhdlDirectory = projectVhdlDirectory;
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        generatedPreview.setEditable(false);
        declarations.setText(architecture.declarations());
        logic.setText(architecture.logic());

        diagnosticsTimer = new Timer(700, event -> runSyntaxCheck());
        diagnosticsTimer.setRepeats(false);
        declarations.getDocument().addDocumentListener(documentListener());
        logic.getDocument().addDocumentListener(documentListener());
        declarations.setModeListener(vimStatus::setText);
        logic.setModeListener(vimStatus::setText);

        JPanel content = new JPanel(new BorderLayout(0, 8));
        content.setBorder(BorderFactory.createEmptyBorder(12, 12, 8, 12));
        content.add(buildEditorPanels(), BorderLayout.CENTER);

        JCheckBox vimMode = new JCheckBox("Vim keybindings");
        vimMode.addActionListener(event -> {
            declarations.setVimMode(vimMode.isSelected());
            logic.setVimMode(vimMode.isSelected());
        });
        JPanel footer = new JPanel(new BorderLayout(8, 0));
        footer.setBorder(BorderFactory.createEmptyBorder(4, 12, 10, 12));
        JPanel footerStatus = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        footerStatus.add(vimMode);
        footerStatus.add(vimStatus);
        footerStatus.add(diagnostics);
        footerStatus.add(lspStatus);
        footer.add(footerStatus, BorderLayout.CENTER);

        JButton save = new JButton("Save");
        JButton cancel = new JButton("Cancel");
        save.addActionListener(event -> save());
        cancel.addActionListener(event -> dispose());
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        buttons.add(save);
        buttons.add(cancel);
        footer.add(buttons, BorderLayout.EAST);

        add(content, BorderLayout.CENTER);
        add(footer, BorderLayout.SOUTH);
        setPreferredSize(new Dimension(940, 700));
        pack();
        setLocationRelativeTo(owner);
        getRootPane().setDefaultButton(save);
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosed(WindowEvent event) {
                closeLanguageServer();
            }
        });
        diagnosticsTimer.start();
        startLanguageServer(sourceFor(withArchitecture(currentArchitecture())));
    }

    private javax.swing.event.DocumentListener documentListener() {
        return new javax.swing.event.DocumentListener() {
            @Override
            public void insertUpdate(javax.swing.event.DocumentEvent event) {
                refreshGeneratedPreview();
                diagnosticsTimer.restart();
            }

            @Override
            public void removeUpdate(javax.swing.event.DocumentEvent event) {
                refreshGeneratedPreview();
                diagnosticsTimer.restart();
            }

            @Override
            public void changedUpdate(javax.swing.event.DocumentEvent event) {
                refreshGeneratedPreview();
                diagnosticsTimer.restart();
            }
        };
    }

    private JSplitPane buildEditorPanels() {
        JPanel generatedPanel = codePanel(
                "Generated entity declaration and structural VHDL (read-only)", generatedPreview);
        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, generatedPanel, buildCodePanels());
        split.setResizeWeight(0.42);
        split.setBorder(BorderFactory.createEmptyBorder());
        split.setDividerLocation(0.42);
        refreshGeneratedPreview();
        return split;
    }

    private JSplitPane buildCodePanels() {
        JPanel declarationPanel = codePanel("Architecture declarations", declarations);
        JPanel logicPanel = codePanel("Concurrent architecture logic", logic);
        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, declarationPanel, logicPanel);
        split.setResizeWeight(0.35);
        split.setBorder(BorderFactory.createEmptyBorder());
        return split;
    }

    private JPanel codePanel(String title, VhdlCodeArea code) {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBorder(BorderFactory.createTitledBorder(title));
        panel.add(new JScrollPane(code), BorderLayout.CENTER);
        return panel;
    }

    private void refreshGeneratedPreview() {
        generatedPreview.setText(currentSource());
        generatedPreview.setCaretPosition(0);
    }

    private void runSyntaxCheck() {
        if (diagnosticWorker != null && !diagnosticWorker.isDone()) {
            diagnosticPending = true;
            return;
        }
        int revision = ++diagnosticRevision;
        ArchitectureDef candidate = currentArchitecture();
        EntityDef draft = withArchitecture(candidate);
        updateLanguageServerDocument(sourceFor(draft));
        diagnosticWorker = new SwingWorker<>() {
            @Override
            protected SyntaxDiagnostic doInBackground() throws Exception {
                return checkWithGhdl(draft);
            }

            @Override
            protected void done() {
                if (revision == diagnosticRevision) {
                    applyDiagnosticResult(this);
                }
                diagnosticWorker = null;
                if (diagnosticPending) {
                    diagnosticPending = false;
                    diagnosticsTimer.restart();
                }
            }
        };
        diagnosticWorker.execute();
    }

    private void applyDiagnosticResult(SwingWorker<SyntaxDiagnostic, Void> worker) {
        try {
            SyntaxDiagnostic diagnostic = worker.get();
            diagnostics.setText(diagnostic.message());
            diagnostics.setToolTipText(diagnostic.output());
            declarations.setErrorLine(0);
            logic.setErrorLine(0);
            if (diagnostic.line() > 0) {
                int declarationStart = markerContentLine(diagnostic.source(), declarationMarker());
                int logicStart = markerContentLine(diagnostic.source(), logicMarker());
                int declarationLines = lineCount(currentArchitecture().declarations());
                int logicLines = lineCount(currentArchitecture().logic());
                if (diagnostic.line() >= declarationStart
                        && diagnostic.line() < declarationStart + declarationLines) {
                    declarations.setErrorLine(diagnostic.line() - declarationStart + 1);
                } else if (diagnostic.line() >= logicStart && diagnostic.line() < logicStart + logicLines) {
                    logic.setErrorLine(diagnostic.line() - logicStart + 1);
                }
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            diagnostics.setText("GHDL check interrupted");
            diagnostics.setToolTipText(ex.getMessage());
        } catch (ExecutionException ex) {
            diagnostics.setText("GHDL check failed");
            diagnostics.setToolTipText(ex.getCause() == null ? ex.getMessage() : ex.getCause().getMessage());
        }
        applyLanguageServerHighlight();
    }

    private void startLanguageServer(String initialSource) {
        String executable = VhdlLanguageServerSession.configuredExecutable();
        if (executable.isBlank()) {
            lspStatus.setText("LSP: not found on PATH; using GHDL");
            return;
        }
        lspDocumentSource = initialSource;
        lspStatus.setText("LSP: starting…");
        lspStartupWorker = new SwingWorker<>() {
            @Override
            protected Void doInBackground() throws Exception {
                if (isCancelled()) {
                    return null;
                }
                Path workspaceRoot;
                Path documentPath;
                if (projectVhdlDirectory != null) {
                    workspaceRoot = projectVhdlDirectory.getParent();
                    documentPath = projectVhdlDirectory.resolve(entity.name() + ".vhd");
                } else {
                    lspTempDirectory = Files.createTempDirectory("vdml-lsp-workspace-");
                    workspaceRoot = lspTempDirectory;
                    documentPath = workspaceRoot.resolve(entity.name() + ".vhd");
                    Files.writeString(documentPath, initialSource, StandardCharsets.UTF_8);
                }
                VhdlLanguageServerSession session = new VhdlLanguageServerSession(
                        executable, workspaceRoot, documentPath, ArchitectureEditorDialog.this::receiveLspDiagnostics);
                lspSession = session;
                if (isCancelled()) {
                    session.close();
                    return null;
                }
                session.start(initialSource);
                return null;
            }

            @Override
            protected void done() {
                try {
                    get();
                    lspReady = true;
                    lspStatus.setText("LSP: connected");
                    updateLanguageServerDocument(currentSource());
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    failLanguageServer("LSP startup interrupted");
                } catch (ExecutionException ex) {
                    Throwable cause = ex.getCause();
                    failLanguageServer(cause == null ? ex.getMessage() : cause.getMessage());
                } catch (java.util.concurrent.CancellationException ex) {
                    closeLanguageServer();
                }
            }
        };
        lspStartupWorker.execute();
    }

    private void receiveLspDiagnostics(org.eclipse.lsp4j.PublishDiagnosticsParams params) {
        SwingUtilities.invokeLater(() -> {
            if (!isDisplayable()) {
                return;
            }
            if (params.getVersion() != null && params.getVersion() < lspDocumentVersion) {
                return;
            }
            lspDiagnostics = List.copyOf(params.getDiagnostics());
            updateLspStatus();
            applyLanguageServerHighlight();
        });
    }

    private void updateLspStatus() {
        if (lspDiagnostics.isEmpty()) {
            lspStatus.setText("LSP: no issues");
            lspStatus.setToolTipText(null);
            return;
        }
        int errors = 0;
        int warnings = 0;
        StringBuilder details = new StringBuilder();
        for (Diagnostic diagnostic : lspDiagnostics) {
            if (diagnostic.getSeverity() == DiagnosticSeverity.Error) {
                errors++;
            } else if (diagnostic.getSeverity() == DiagnosticSeverity.Warning) {
                warnings++;
            }
            if (details.length() > 0) {
                details.append('\n');
            }
            details.append("Line ").append(diagnostic.getRange().getStart().getLine() + 1)
                    .append(": ").append(diagnostic.getMessage());
        }
        lspStatus.setText("LSP: " + errors + " errors, " + warnings + " warnings");
        lspStatus.setToolTipText("<html>" + details.toString().replace("&", "&amp;")
                .replace("<", "&lt;").replace(">", "&gt;").replace("\n", "<br>") + "</html>");
    }

    private void applyLanguageServerHighlight() {
        if (lspDiagnostics.isEmpty()) {
            return;
        }
        for (Diagnostic diagnostic : lspDiagnostics) {
            int line = diagnostic.getRange().getStart().getLine() + 1;
            int declarationStart = markerContentLine(lspDocumentSource, declarationMarker());
            int logicStart = markerContentLine(lspDocumentSource, logicMarker());
            int declarationLines = lineCount(currentArchitecture().declarations());
            int logicLines = lineCount(currentArchitecture().logic());
            if (line >= declarationStart && line < declarationStart + declarationLines) {
                declarations.setErrorLine(line - declarationStart + 1);
                return;
            }
            if (line >= logicStart && line < logicStart + logicLines) {
                logic.setErrorLine(line - logicStart + 1);
                return;
            }
        }
    }

    private String currentSource() {
        return sourceFor(withArchitecture(currentArchitecture()));
    }

    private void updateLanguageServerDocument(String source) {
        lspDocumentSource = source;
        if (!lspReady || lspSession == null) {
            return;
        }
        lspDocumentVersion++;
        lspSession.updateDocument(source);
    }

    private void failLanguageServer(String message) {
        lspReady = false;
        closeLanguageServer();
        lspStatus.setText("LSP unavailable; using GHDL");
        lspStatus.setToolTipText(message);
    }

    private void closeLanguageServer() {
        lspReady = false;
        if (lspStartupWorker != null && !lspStartupWorker.isDone()) {
            lspStartupWorker.cancel(true);
        }
        if (lspSession != null) {
            lspSession.close();
            lspSession = null;
        }
        if (lspTempDirectory != null) {
            try {
                Files.deleteIfExists(lspTempDirectory.resolve(entity.name() + ".vhd"));
                Files.deleteIfExists(lspTempDirectory);
            } catch (IOException ex) {
                System.err.println("Could not remove temporary VHDL LSP workspace: " + ex.getMessage());
            }
            lspTempDirectory = null;
        }
    }

    private SyntaxDiagnostic checkWithGhdl(EntityDef draft) throws Exception {
        String source = sourceFor(draft);
        Path directory = Files.createTempDirectory("vdml-vhdl-check-");
        Path sourceFile = directory.resolve(draft.name() + ".vhd");
        try {
            Files.writeString(sourceFile, source, StandardCharsets.UTF_8);
            Process process;
            try {
                process = new ProcessBuilder("ghdl", "-s", "--std=08", sourceFile.getFileName().toString())
                        .directory(directory.toFile())
                        .redirectErrorStream(true)
                        .start();
            } catch (IOException ex) {
                return new SyntaxDiagnostic("GHDL unavailable", ex.getMessage(), 0, source);
            }
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            int exitCode = process.waitFor();
            if (exitCode == 0) {
                return new SyntaxDiagnostic("GHDL syntax check passed", output, 0, source);
            }
            Matcher location = GHDL_LOCATION.matcher(output);
            int line = location.find() ? Integer.parseInt(location.group(1)) : 0;
            String summary = output.isEmpty() ? "GHDL rejected the architecture." : output.lines().findFirst().orElse(output);
            return new SyntaxDiagnostic(summary, output, line, source);
        } finally {
            Files.deleteIfExists(sourceFile);
            Files.deleteIfExists(directory.resolve("work-obj08.cf"));
            Files.deleteIfExists(directory);
        }
    }

    private String sourceFor(EntityDef draft) {
        if (!topLevel) {
            return VhdlEmitter.emitEntityArchitectureFile(model, draft);
        }
        DiagramModel draftModel = JsonIO.load(JsonIO.save(model));
        ArchitectureDef architecture = draft.architectures().get(0);
        draftModel.updateTopLevelUserCode(architecture.declarations(), architecture.logic());
        return VhdlEmitter.emitStructuralArchitecture(draftModel);
    }

    private EntityDef withArchitecture(ArchitectureDef updated) {
        List<ArchitectureDef> values = new ArrayList<>(entity.architectures());
        int index = values.indexOf(original);
        if (index < 0) {
            values.add(updated);
        } else {
            values.set(index, updated);
        }
        return new EntityDef(entity.name(), entity.ports(), entity.generics(), values);
    }

    private ArchitectureDef currentArchitecture() {
        return new ArchitectureDef(original.name(), declarations.getText(), logic.getText());
    }

    private String declarationMarker() {
        return topLevel ? "-- [[ USER SIGNALS ]]"
                : "-- [[ VDML USER DECLARATIONS:" + original.name() + " ]]";
    }

    private String logicMarker() {
        return topLevel ? "-- [[ USER LOGIC ]]"
                : "-- [[ VDML USER LOGIC:" + original.name() + " ]]";
    }

    private int markerContentLine(String source, String marker) {
        int markerStart = source.indexOf(marker);
        if (markerStart < 0) {
            return -1;
        }
        int line = 1;
        for (int i = 0; i < markerStart; i++) {
            if (source.charAt(i) == '\n') {
                line++;
            }
        }
        return line + 1;
    }

    private int lineCount(String text) {
        return text.isEmpty() ? 0 : text.split("\\R", -1).length;
    }

    private void save() {
        diagnosticsTimer.stop();
        result = currentArchitecture();
        dispose();
    }

    private record SyntaxDiagnostic(String message, String output, int line, String source) {
    }
}
