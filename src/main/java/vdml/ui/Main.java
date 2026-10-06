package vdml.ui;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.DefaultComboBoxModel;
import javax.swing.JButton;
import javax.swing.JCheckBoxMenuItem;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTextArea;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.filechooser.FileNameExtensionFilter;

import vdml.io.DiagramPdfExporter;
import vdml.io.JsonIO;
import vdml.model.DiagramModel;
import vdml.model.EntityDef;
import vdml.validation.DesignValidator;
import vdml.validation.DesignValidator.Report;
import vdml.vhdl.VhdlEmitter;

/**
 * Main window for the vdml editor. It shares {@link DiagramModel} between
 * {@link DiagramCanvas}, {@link TopLevelPanel}, and {@link TopLevelOptionsPanel},
 * and coordinates file I/O and validation through {@link JsonIO} and
 * {@link DesignValidator}.
 */
public class Main {
    private final DiagramModel model = new DiagramModel();
    private final DiagramCanvas canvas = new DiagramCanvas(model);
    private final JFrame frame = new JFrame("vdml");
    private JSplitPane workspaceSplit;
    private JPanel toolsPane;
    private JButton detachToolsButton;
    private JFrame detachedToolsFrame;
    private JLabel statusBar;
    private JLabel validationStatus;
    private boolean liveValidationEnabled = true;
    private long designRevision;
    private Report latestValidationReport;
    private boolean latestReportStale;
    private static final String HINT =
            "Fan-out: click one output, then multiple inputs; Esc finishes. Move: drag block. "
                    + "Resize: drag selected corner. "
                    + "Drag empty canvas to select multiple blocks and wires; Shift-drag adds to selection. "
                    + "Reroute: drag wire segments; right-click a wire to resize, add a breakpoint, or edit its signal. "
                    + "Pan any direction: middle-drag or Space+drag. "
                    + "Zoom: mouse wheel or canvas controls.";
    private File currentFile;

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> new Main().show());
    }

    private void show() {
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setJMenuBar(buildMenuBar());
        frame.getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
                .put(KeyStroke.getKeyStroke(KeyEvent.VK_E, InputEvent.CTRL_DOWN_MASK), "exportVhdl");
        frame.getRootPane().getActionMap().put("exportVhdl", new AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                exportVhdl();
            }
        });

        JComboBox<String> entities = new JComboBox<>();
        Runnable reloadEntities = () -> {
            Object selected = entities.getSelectedItem();
            entities.setModel(new DefaultComboBoxModel<>(model.getEntityCatalog().keySet().toArray(new String[0])));
            if (selected != null) {
                entities.setSelectedItem(selected);
            }
        };
        reloadEntities.run();
        model.addChangeListener(reloadEntities::run);

        JButton add = new JButton("Add block");
        add.addActionListener(e -> {
            String entity = (String) entities.getSelectedItem();
            if (entity != null) {
                canvas.addBlock(entity);
            }
        });
        JButton newEntity = new JButton("New entity…");
        newEntity.addActionListener(e -> EntityEditorDialog.show(frame, model, null));
        JButton editEntity = new JButton("Edit entity…");
        editEntity.addActionListener(e -> {
            EntityDef def = model.getEntityCatalog().get((String) entities.getSelectedItem());
            if (def != null) {
                EntityEditorDialog.show(frame, model, def);
            }
        });
        toolsPane = buildToolsPane(entities, add, newEntity, editEntity);

        statusBar = new JLabel(HINT);
        statusBar.setBorder(BorderFactory.createEmptyBorder(3, 8, 3, 8));
        validationStatus = new JLabel("Validation pending");
        validationStatus.setBorder(BorderFactory.createEmptyBorder(3, 8, 3, 8));
        canvas.setStatusListener(statusBar::setText);
        canvas.setToolTipText("Drag blocks to move; select a block and drag its bottom-right handle to resize. "
                + "Drag empty canvas to select multiple blocks and wires; Shift/Ctrl adds to selection. "
                + "Right-click a wire for Resize, breakpoint, or signal options. Drag interior wire segments to reroute "
                + "without disconnecting either end. "
                + "To fan out one output, click it once and then click multiple input pins; press Esc to finish. "
                + "Pan in any direction with the middle mouse button or Space+left-drag. "
                + "Use the mouse wheel to zoom around the pointer.");
        canvas.setBlockActivatedListener(block -> BlockDialog.show(frame, model, block));
        model.addChangeListener(this::modelChanged);
        refreshLiveValidation();

        JScrollPane topLevelScroll = new JScrollPane(new TopLevelPanel(model));
        topLevelScroll.setPreferredSize(new Dimension(320, 600));
        JScrollPane canvasScroll = new JScrollPane(canvas);
        JPanel canvasArea = new JPanel(new BorderLayout());
        JPanel zoomControls = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 2));
        JButton zoomOut = new JButton("-");
        zoomOut.setToolTipText("Zoom out");
        zoomOut.addActionListener(e -> canvas.zoomOut());
        JLabel zoomValue = new JLabel("100%");
        JButton resetZoom = new JButton("Reset");
        resetZoom.setToolTipText("Reset canvas zoom to 100%");
        resetZoom.addActionListener(e -> canvas.resetZoom());
        JButton zoomIn = new JButton("+");
        zoomIn.setToolTipText("Zoom in");
        zoomIn.addActionListener(e -> canvas.zoomIn());
        canvas.setZoomChangedListener(value -> zoomValue.setText(Math.round(value * 100) + "%"));
        zoomControls.add(new JLabel("Zoom"));
        zoomControls.add(zoomOut);
        zoomControls.add(zoomValue);
        zoomControls.add(resetZoom);
        zoomControls.add(zoomIn);
        canvasArea.add(zoomControls, BorderLayout.NORTH);
        canvasArea.add(canvasScroll, BorderLayout.CENTER);
        JSplitPane diagramSplit = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT,
                topLevelScroll, canvasArea);
        diagramSplit.setResizeWeight(0.0);
        workspaceSplit = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, diagramSplit, toolsPane);
        workspaceSplit.setResizeWeight(0.82);

        frame.add(workspaceSplit, BorderLayout.CENTER);
        JPanel statusPanel = new JPanel(new BorderLayout());
        statusPanel.add(statusBar, BorderLayout.CENTER);
        statusPanel.add(validationStatus, BorderLayout.EAST);
        frame.add(statusPanel, BorderLayout.SOUTH);
        frame.setSize(1480, 800);
        frame.setLocationRelativeTo(null);
        frame.setVisible(true);
        workspaceSplit.setDividerLocation(0.82);
        SwingUtilities.invokeLater(canvas::showOrigin);
    }

    private JPanel buildToolsPane(JComboBox<String> entities, JButton add,
                                  JButton newEntity, JButton editEntity) {
        JPanel pane = new JPanel(new BorderLayout(0, 8));
        pane.setPreferredSize(new Dimension(280, 600));
        pane.setBorder(BorderFactory.createTitledBorder("Top-level settings and design tools"));

        detachToolsButton = new JButton("Detach");
        detachToolsButton.setToolTipText("Move this panel into a separate, movable window");
        detachToolsButton.addActionListener(e -> toggleToolsPane());
        JPanel header = new JPanel(new BorderLayout());
        header.add(new JLabel("Design options"), BorderLayout.WEST);
        header.add(detachToolsButton, BorderLayout.EAST);

        JPanel controls = new JPanel();
        controls.setLayout(new BoxLayout(controls, BoxLayout.Y_AXIS));
        controls.setBorder(BorderFactory.createEmptyBorder(4, 6, 4, 6));
        entities.setMaximumSize(new Dimension(Integer.MAX_VALUE, entities.getPreferredSize().height));
        controls.add(new JLabel("Entity to place"));
        controls.add(Box.createVerticalStrut(4));
        controls.add(entities);
        controls.add(Box.createVerticalStrut(8));
        add.setAlignmentX(Component.LEFT_ALIGNMENT);
        newEntity.setAlignmentX(Component.LEFT_ALIGNMENT);
        editEntity.setAlignmentX(Component.LEFT_ALIGNMENT);
        controls.add(add);
        controls.add(Box.createVerticalStrut(4));
        controls.add(newEntity);
        controls.add(Box.createVerticalStrut(4));
        controls.add(editEntity);
        controls.add(Box.createVerticalStrut(16));
        controls.add(new javax.swing.JSeparator());
        controls.add(Box.createVerticalStrut(12));

        JButton emit = new JButton("Emit VHDL…");
        emit.setAlignmentX(Component.LEFT_ALIGNMENT);
        emit.addActionListener(e -> emitToStdout());
        JButton export = new JButton("Export VHDL files…");
        export.setAlignmentX(Component.LEFT_ALIGNMENT);
        export.setToolTipText("Shortcut: Ctrl+E");
        export.addActionListener(e -> exportVhdl());
        JButton exportPdf = new JButton("Export PDF…");
        exportPdf.setAlignmentX(Component.LEFT_ALIGNMENT);
        exportPdf.addActionListener(e -> exportPdf());
        controls.add(emit);
        controls.add(Box.createVerticalStrut(4));
        controls.add(export);
        controls.add(Box.createVerticalStrut(4));
        controls.add(exportPdf);
        controls.add(Box.createVerticalGlue());

        JPanel content = new JPanel();
        content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
        content.add(new TopLevelOptionsPanel(model));
        content.add(Box.createVerticalStrut(10));
        content.add(controls);

        pane.add(header, BorderLayout.NORTH);
        pane.add(new JScrollPane(content), BorderLayout.CENTER);
        return pane;
    }

    private void toggleToolsPane() {
        if (detachedToolsFrame != null) {
            dockToolsPane();
            return;
        }
        detachToolsPane();
    }

    private void detachToolsPane() {
        workspaceSplit.setRightComponent(null);
        detachedToolsFrame = new JFrame("vdml - Design tools");
        detachedToolsFrame.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        detachedToolsFrame.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                dockToolsPane();
            }
        });
        detachedToolsFrame.setContentPane(toolsPane);
        detachedToolsFrame.setSize(340, 560);
        detachedToolsFrame.setLocation(frame.getX() + frame.getWidth(), frame.getY() + 40);
        detachedToolsFrame.setVisible(true);
        workspaceSplit.setResizeWeight(1.0);
        detachToolsButton.setText("Dock");
        detachToolsButton.setToolTipText("Return this panel to the main window");
    }

    private void dockToolsPane() {
        if (detachedToolsFrame == null) {
            return;
        }
        JFrame oldFrame = detachedToolsFrame;
        detachedToolsFrame = null;
        oldFrame.setVisible(false);
        oldFrame.setContentPane(new JPanel());
        oldFrame.dispose();
        workspaceSplit.setRightComponent(toolsPane);
        workspaceSplit.setResizeWeight(0.82);
        workspaceSplit.setDividerLocation(0.82);
        detachToolsButton.setText("Detach");
        detachToolsButton.setToolTipText("Move this panel into a separate, movable window");
    }

    private JMenuBar buildMenuBar() {
        int ctrl = InputEvent.CTRL_DOWN_MASK;
        JMenu file = new JMenu("File");
        file.add(item("New", KeyEvent.VK_N, ctrl, this::newDiagram));
        file.add(item("Open…", KeyEvent.VK_O, ctrl, this::open));
        file.add(item("Save…", KeyEvent.VK_S, ctrl, this::save));
        file.addSeparator();
        file.add(item("Export PDF…", KeyEvent.VK_P, ctrl | InputEvent.SHIFT_DOWN_MASK, this::exportPdf));
        file.addSeparator();
        file.add(item("Exit", 0, 0, () -> System.exit(0)));

        JMenu edit = new JMenu("Edit");
        edit.add(item("Undo", KeyEvent.VK_Z, ctrl, model::undo));
        edit.add(item("Redo", KeyEvent.VK_Y, ctrl, model::redo));

        JMenu dev = new JMenu("Dev");
        JCheckBoxMenuItem liveValidation = new JCheckBoxMenuItem("Live validation", true);
        liveValidation.setToolTipText("Refresh model, JSON, and generated-VHDL checks after each design change");
        liveValidation.addActionListener(e -> {
            liveValidationEnabled = liveValidation.isSelected();
            if (liveValidationEnabled) {
                refreshLiveValidation();
            } else {
                validationStatus.setText("Live validation paused");
            }
        });
        dev.add(liveValidation);
        dev.addSeparator();
        dev.add(item("Validate now…", 0, 0, this::validateNow));
        dev.add(item("Show latest report…", 0, 0, this::showLatestReport));
        dev.add(item("Save latest report…", 0, 0, this::saveLatestReport));

        JMenuBar bar = new JMenuBar();
        bar.add(file);
        bar.add(edit);
        bar.add(dev);
        return bar;
    }

    private JMenuItem item(String text, int key, int mask, Runnable action) {
        JMenuItem item = new JMenuItem(text);
        if (key != 0) {
            item.setAccelerator(KeyStroke.getKeyStroke(key, mask));
        }
        item.addActionListener(e -> action.run());
        return item;
    }

    private void newDiagram() {
        model.loadFrom(new DiagramModel());
        currentFile = null;
        frame.setTitle("vdml");
    }

    private void open() {
        JFileChooser chooser = createDiagramFileChooser();
        if (chooser.showOpenDialog(frame) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        File file = chooser.getSelectedFile();
        try {
            String json = Files.readString(file.toPath(), StandardCharsets.UTF_8);
            model.loadFrom(JsonIO.load(json));
            currentFile = file;
            frame.setTitle("vdml - " + file.getName());
        } catch (IOException | RuntimeException ex) {
            error("Could not open " + file.getName() + ": " + ex.getMessage());
        }
    }

    private void save() {
        JFileChooser chooser = createDiagramFileChooser();
        if (currentFile != null) {
            chooser.setSelectedFile(currentFile);
        }
        if (chooser.showSaveDialog(frame) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        File file = chooser.getSelectedFile();
        if (!file.getName().contains(".")) {
            file = new File(file.getParentFile(), file.getName() + ".dgm.json");
        }
        try {
            Files.writeString(file.toPath(), JsonIO.save(model), StandardCharsets.UTF_8);
            currentFile = file;
            frame.setTitle("vdml - " + file.getName());
        } catch (IOException ex) {
            error("Could not save: " + ex.getMessage());
        }
    }

    private JFileChooser createDiagramFileChooser() {
        JFileChooser chooser = new JFileChooser(currentFile == null ? new File(".") : currentFile.getParentFile());
        chooser.setFileFilter(new FileNameExtensionFilter("Diagram (*.dgm.json, *.json)", "json"));
        return chooser;
    }

    private void emitToStdout() {
        showGeneratedVhdl(buildGeneratedVhdl());
    }

    private String buildGeneratedVhdl() {
        String pkg = VhdlEmitter.emitComponentsPackage(model);
        String arch = VhdlEmitter.emitStructuralArchitecture(model);
        System.out.println("-- components_pkg.vhd\n" + pkg);
        StringBuilder generated = new StringBuilder(pkg).append('\n');
        for (var file : VhdlEmitter.emitStandardLibraryFiles(model).entrySet()) {
            System.out.println("-- " + file.getKey() + "\n" + file.getValue());
            generated.append(file.getValue()).append('\n');
        }
        System.out.println("-- " + model.getTopEntity() + ".vhd\n" + arch);
        generated.append(arch);
        return generated.toString();
    }

    private void showGeneratedVhdl(String generated) {
        JTextArea area = new JTextArea(generated, 30, 90);
        area.setEditable(false);
        area.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        JScrollPane pane = new JScrollPane(area);
        pane.setPreferredSize(new Dimension(760, 520));
        JOptionPane.showMessageDialog(frame, pane, "Generated VHDL", JOptionPane.PLAIN_MESSAGE);
    }

    private void exportVhdl() {
        JFileChooser chooser = createVhdlExportChooser();
        if (chooser.showSaveDialog(frame) != JFileChooser.APPROVE_OPTION) {
            return;
        }

        Path dir = chooser.getSelectedFile().toPath();
        try {
            writeVhdlFiles(dir);
        } catch (IOException ex) {
            error("Could not write VHDL: " + ex.getMessage());
        }
    }

    private JFileChooser createVhdlExportChooser() {
        JFileChooser chooser = new JFileChooser(currentFile == null ? new File(".") : currentFile.getParentFile());
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        chooser.setDialogTitle("Choose output directory");
        return chooser;
    }

    private void writeVhdlFiles(Path dir) throws IOException {
        Files.writeString(dir.resolve("components_pkg.vhd"), VhdlEmitter.emitComponentsPackage(model),
                StandardCharsets.UTF_8);
        var standardFiles = VhdlEmitter.emitStandardLibraryFiles(model);
        for (var file : standardFiles.entrySet()) {
            Files.writeString(dir.resolve(file.getKey()), file.getValue(), StandardCharsets.UTF_8);
        }
        Files.writeString(dir.resolve(model.getTopEntity() + ".vhd"),
                VhdlEmitter.emitStructuralArchitecture(model), StandardCharsets.UTF_8);
        String standardSummary = standardFiles.isEmpty() ? "" : " plus "
                + standardFiles.size() + " standard library source file(s)";
        JOptionPane.showMessageDialog(frame, "Wrote components_pkg.vhd and " + model.getTopEntity()
                + ".vhd" + standardSummary + " to\n" + dir);
    }

    private void exportPdf() {
        JFileChooser chooser = createPdfExportChooser();
        if (chooser.showSaveDialog(frame) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        File file = ensurePdfExtension(chooser.getSelectedFile());
        try {
            DiagramPdfExporter.export(canvas, file.toPath());
            JOptionPane.showMessageDialog(frame, "Diagram exported to " + file.getName(),
                    "PDF export complete", JOptionPane.INFORMATION_MESSAGE);
        } catch (IOException ex) {
            error("Could not export PDF: " + ex.getMessage());
        }
    }

    private JFileChooser createPdfExportChooser() {
        JFileChooser chooser = new JFileChooser(currentFile == null ? new File(".") : currentFile.getParentFile());
        chooser.setDialogTitle("Export diagram to PDF");
        chooser.setFileFilter(new FileNameExtensionFilter("PDF document (*.pdf)", "pdf"));
        chooser.setSelectedFile(new File("diagram.pdf"));
        return chooser;
    }

    private File ensurePdfExtension(File file) {
        if (file.getName().toLowerCase(java.util.Locale.ROOT).endsWith(".pdf")) {
            return file;
        }
        return new File(file.getParentFile(), file.getName() + ".pdf");
    }

    private void modelChanged() {
        designRevision++;
        latestReportStale = latestValidationReport != null;
        if (liveValidationEnabled) {
            refreshLiveValidation();
        } else if (latestReportStale) {
            validationStatus.setText("Live validation paused (report is stale)");
        }
    }

    private void refreshLiveValidation() {
        latestValidationReport = DesignValidator.validate(model, false);
        latestReportStale = false;
        setValidationStatus("Live", latestValidationReport);
    }

    private void validateNow() {
        final DiagramModel snapshot;
        try {
            snapshot = JsonIO.load(JsonIO.save(model));
        } catch (RuntimeException ex) {
            latestValidationReport = DesignValidator.validate(model, false);
            latestReportStale = false;
            validationStatus.setToolTipText("Validation ran without a snapshot: " + ex.getMessage());
            showValidationReport(latestValidationReport, false);
            return;
        }
        long revision = designRevision;
        validationStatus.setText("Validation running…");
        new SwingWorker<Report, Void>() {
            @Override
            protected Report doInBackground() {
                return DesignValidator.validate(snapshot, true);
            }

            @Override
            protected void done() {
                try {
                    latestValidationReport = get();
                    latestReportStale = revision != designRevision;
                    setValidationStatus(latestReportStale ? "Stale" : "Validation", latestValidationReport);
                    if (latestReportStale) {
                        validationStatus.setToolTipText(
                                "The design changed while this report was being generated.");
                    }
                    showValidationReport(latestValidationReport, latestReportStale);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    error("Validation was interrupted.");
                } catch (java.util.concurrent.ExecutionException ex) {
                    Throwable cause = ex.getCause();
                    error("Validation failed: " + (cause == null ? ex.getMessage() : cause.getMessage()));
                }
            }
        }.execute();
    }

    private void showLatestReport() {
        if (latestValidationReport == null) {
            refreshLiveValidation();
        }
        showValidationReport(latestValidationReport, latestReportStale);
    }

    private void showValidationReport(Report report, boolean stale) {
        String text = report.toText();
        if (stale) {
            text += "\nNOTE: The design changed while this report was being generated. Run validation again for the current design.\n";
        }
        JTextArea area = new JTextArea(text, 28, 88);
        area.setEditable(false);
        area.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        JScrollPane pane = new JScrollPane(area);
        pane.setPreferredSize(new Dimension(780, 520));
        JOptionPane.showMessageDialog(frame, pane, "Design validation report",
                report.count(DesignValidator.Severity.ERROR) == 0
                        ? JOptionPane.INFORMATION_MESSAGE : JOptionPane.WARNING_MESSAGE);
    }

    private void setValidationStatus(String prefix, Report report) {
        validationStatus.setText(prefix + ": " + report.count(DesignValidator.Severity.ERROR)
                + " error(s), " + report.count(DesignValidator.Severity.WARNING) + " warning(s)");
        validationStatus.setToolTipText(report.summary());
    }

    private void saveLatestReport() {
        if (latestValidationReport == null) {
            refreshLiveValidation();
        }
        JFileChooser chooser = createReportSaveChooser();
        if (chooser.showSaveDialog(frame) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        File file = ensureTextReportExtension(chooser.getSelectedFile());
        String report = buildLatestReportText();
        try {
            Files.writeString(file.toPath(), report, StandardCharsets.UTF_8);
            statusBar.setText("Saved validation report: " + file.getName());
        } catch (IOException ex) {
            error("Could not save validation report: " + ex.getMessage());
        }
    }

    private JFileChooser createReportSaveChooser() {
        JFileChooser chooser = new JFileChooser(currentFile == null ? new File(".") : currentFile.getParentFile());
        chooser.setDialogTitle("Save validation report");
        chooser.setFileFilter(new FileNameExtensionFilter("Text report (*.txt)", "txt"));
        chooser.setSelectedFile(new File("vdml-validation-report.txt"));
        return chooser;
    }

    private File ensureTextReportExtension(File file) {
        if (file.getName().toLowerCase(java.util.Locale.ROOT).endsWith(".txt")) {
            return file;
        }
        return new File(file.getParentFile(), file.getName() + ".txt");
    }

    private String buildLatestReportText() {
        String report = latestValidationReport.toText();
        if (latestReportStale) {
            report += "\nNOTE: The design changed while this report was being generated. Run validation again for the current design.\n";
        }
        return report;
    }

    private void error(String message) {
        JOptionPane.showMessageDialog(frame, message, "Error", JOptionPane.ERROR_MESSAGE);
    }
}
