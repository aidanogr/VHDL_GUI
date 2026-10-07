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
import java.util.ArrayList;
import java.util.List;
import java.util.prefs.BackingStoreException;
import java.util.prefs.Preferences;

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
import javax.swing.UnsupportedLookAndFeelException;
import javax.swing.UIManager;
import javax.swing.filechooser.FileNameExtensionFilter;

import vdml.io.DiagramPdfExporter;
import vdml.io.JsonIO;
import vdml.io.VhdlProjectFileLink;
import vdml.io.VhdlEntityIO;
import vdml.io.VhdlProjectIO;
import vdml.model.DiagramModel;
import vdml.model.EntityDef;
import vdml.model.StandardLibrary;
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
    private static final int MAX_RECENT_FILES = 10;
    private static final String RECENT_FILE_COUNT_KEY = "recentFileCount";
    private static final String RECENT_FILE_KEY_PREFIX = "recentFile.";

    private final DiagramModel model = new DiagramModel();
    private final DiagramCanvas canvas = new DiagramCanvas(model);
    private final JFrame frame = new JFrame("vdml");
    private final List<File> recentFiles = new ArrayList<>();
    private JSplitPane workspaceSplit;
    private JMenu recentFilesMenu;
    private JPanel toolsPane;
    private JButton detachToolsButton;
    private JFrame detachedToolsFrame;
    private JLabel statusBar;
    private JLabel validationStatus;
    private boolean liveValidationEnabled = true;
    private long designRevision;
    private Report latestValidationReport;
    private boolean latestReportStale;
    private String recentFilesLoadError;
    private VhdlProjectFileLink projectFileLink;
    private static final String HINT =
            "Fan-out: click one output, then multiple inputs; Esc finishes. Move: drag block. "
                    + "Resize: drag selected corner. "
                    + "Drag empty canvas to select multiple blocks and wires; Shift-drag adds to selection. "
                    + "Reroute: drag wire segments; right-click a wire to resize, add a breakpoint, or edit its signal. "
                    + "Pan any direction: middle-drag or Space+drag. "
                    + "Zoom: mouse wheel or canvas controls.";
    private File currentFile;

    public static void main(String[] args) {
        installSystemLookAndFeel();
        SwingUtilities.invokeLater(() -> new Main().show());
    }

    private static void installSystemLookAndFeel() {
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (ClassNotFoundException | InstantiationException | IllegalAccessException
                | UnsupportedLookAndFeelException ex) {
            System.err.println("Could not install the system look and feel: " + ex.getMessage());
        }
    }

    private void show() {
        loadRecentFiles();
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                closeProjectFileLink();
            }
        });
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
        newEntity.addActionListener(e -> EntityEditorDialog.show(frame, model, null, projectVhdlDirectory()));
        JButton editEntity = new JButton("Edit entity…");
        editEntity.addActionListener(e -> {
            EntityDef def = model.getEntityCatalog().get((String) entities.getSelectedItem());
            if (def != null) {
                if (def.sourceOverride() != null) {
                    error("This component is backed by imported VHDL. Edit its source file and re-import it.");
                    return;
                }
                EntityEditorDialog.show(frame, model, def, projectVhdlDirectory());
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
        canvas.setBlockActivatedListener(block ->
                BlockDialog.show(frame, model, block, projectVhdlDirectory()));
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
        if (recentFilesLoadError != null) {
            error("Could not load recent files: " + recentFilesLoadError);
            recentFilesLoadError = null;
        }
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
        recentFilesMenu = new JMenu("Open Recent");
        refreshRecentFilesMenu();
        file.add(recentFilesMenu);
        file.add(item("Save…", KeyEvent.VK_S, ctrl, this::save));
        file.addSeparator();
        file.add(item("Import VHDL entity as component…", 0, 0, this::importEntityAsComponent));
        file.add(item("Import VHDL directory as components…", 0, 0, this::importVhdlDirectory));
        file.add(item("Import VHDL interface as top level…", 0, 0, this::importTopLevelInterface));
        file.add(item("Export entity…", 0, 0, this::exportEntity));
        file.addSeparator();
        file.add(item("Export PDF…", KeyEvent.VK_P, ctrl | InputEvent.SHIFT_DOWN_MASK, this::exportPdf));
        file.add(item("Export VHDL files…", KeyEvent.VK_E, ctrl, this::exportVhdl));
        file.addSeparator();
        file.add(item("Exit", 0, 0, () -> System.exit(0)));

        JMenu edit = new JMenu("Edit");
        edit.add(item("Undo", KeyEvent.VK_Z, ctrl, model::undo));
        edit.add(item("Redo", KeyEvent.VK_Y, ctrl, model::redo));
        edit.addSeparator();
        edit.add(item("Top-level architecture code…", 0, 0,
                () -> ArchitectureEditorDialog.editTopLevel(frame, model, projectVhdlDirectory())));

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
        dev.addSeparator();
        dev.add(item("Configure VHDL language server…", 0, 0, this::configureVhdlLanguageServer));

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
        closeProjectFileLink();
        currentFile = null;
        model.loadFrom(new DiagramModel());
        frame.setTitle("vdml");
    }

    private void open() {
        JFileChooser chooser = createDiagramFileChooser();
        if (chooser.showOpenDialog(frame) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        openFile(chooser.getSelectedFile());
    }

    private void openFile(File selectedFile) {
        File file = selectedFile.getAbsoluteFile();
        DiagramModel loaded;
        try {
            loaded = VhdlProjectIO.isProjectFile(file.toPath())
                    ? VhdlProjectIO.load(file.toPath())
                    : JsonIO.load(Files.readString(file.toPath(), StandardCharsets.UTF_8));
        } catch (IOException | RuntimeException ex) {
            error("Could not open " + file.getName() + ": " + ex.getMessage());
            return;
        }
        closeProjectFileLink();
        currentFile = null;
        model.loadFrom(loaded);
        currentFile = file;
        frame.setTitle("vdml - " + file.getName());
        rememberRecentFile(file);
        if (VhdlProjectIO.isProjectFile(file.toPath())) {
            try {
                VhdlProjectIO.save(file.toPath(), model);
            } catch (IOException ex) {
                error("Project opened, but linked VHDL files could not be synchronized: " + ex.getMessage());
                return;
            }
            try {
                watchProjectFiles();
            } catch (IOException ex) {
                error("Project opened, but linked VHDL changes could not be watched: " + ex.getMessage());
            }
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
        String lowerName = file.getName().toLowerCase(java.util.Locale.ROOT);
        if (!lowerName.endsWith(VhdlProjectIO.PROJECT_EXTENSION) && selectedProjectFilter(chooser)) {
            file = new File(file.getParentFile(), file.getName() + VhdlProjectIO.PROJECT_EXTENSION);
        } else if (!lowerName.endsWith(VhdlProjectIO.PROJECT_EXTENSION)
                && !selectedProjectFilter(chooser) && !lowerName.endsWith(".json")) {
            String extension = ".dgm.json";
            file = new File(file.getParentFile(), file.getName() + extension);
        }
        file = file.getAbsoluteFile();
        try {
            boolean projectFile = VhdlProjectIO.isProjectFile(file.toPath());
            if (projectFile) {
                VhdlProjectIO.save(file.toPath(), model);
            } else {
                Files.writeString(file.toPath(), JsonIO.save(model), StandardCharsets.UTF_8);
            }
            if (!projectFile || currentFile == null || !sameFile(currentFile, file)) {
                closeProjectFileLink();
            }
            currentFile = file;
            if (projectFile && projectFileLink == null) {
                try {
                    watchProjectFiles();
                } catch (IOException ex) {
                    error("Project saved, but linked VHDL changes could not be watched: " + ex.getMessage());
                }
            }
            frame.setTitle("vdml - " + file.getName());
            rememberRecentFile(file);
        } catch (IOException ex) {
            error("Could not save: " + ex.getMessage());
        }
    }

    private void loadRecentFiles() {
        try {
            Preferences preferences = Preferences.userNodeForPackage(Main.class);
            int count = Math.min(MAX_RECENT_FILES, Math.max(0, preferences.getInt(RECENT_FILE_COUNT_KEY, 0)));
            for (int i = count - 1; i >= 0; i--) {
                String path = preferences.get(RECENT_FILE_KEY_PREFIX + i, null);
                if (path != null && !path.isBlank()) {
                    addRecentFileInMemory(new File(path));
                }
            }
        } catch (SecurityException ex) {
            recentFilesLoadError = ex.getMessage();
        }
    }

    private void rememberRecentFile(File file) {
        addRecentFileInMemory(file);
        persistRecentFiles();
        refreshRecentFilesMenu();
    }

    private void addRecentFileInMemory(File file) {
        File normalized = file.toPath().toAbsolutePath().normalize().toFile();
        recentFiles.removeIf(recent -> sameFile(recent, normalized));
        recentFiles.add(0, normalized);
        while (recentFiles.size() > MAX_RECENT_FILES) {
            recentFiles.remove(recentFiles.size() - 1);
        }
    }

    private boolean sameFile(File first, File second) {
        return first.toPath().toAbsolutePath().normalize()
                .equals(second.toPath().toAbsolutePath().normalize());
    }

    private void persistRecentFiles() {
        try {
            Preferences preferences = Preferences.userNodeForPackage(Main.class);
            preferences.putInt(RECENT_FILE_COUNT_KEY, recentFiles.size());
            for (int i = 0; i < MAX_RECENT_FILES; i++) {
                String key = RECENT_FILE_KEY_PREFIX + i;
                if (i < recentFiles.size()) {
                    preferences.put(key, recentFiles.get(i).getAbsolutePath());
                } else {
                    preferences.remove(key);
                }
            }
            preferences.flush();
        } catch (BackingStoreException | SecurityException ex) {
            error("Could not save recent files: " + ex.getMessage());
        }
    }

    private void refreshRecentFilesMenu() {
        if (recentFilesMenu == null) {
            return;
        }
        recentFilesMenu.removeAll();
        if (recentFiles.isEmpty()) {
            JMenuItem empty = new JMenuItem("No recent files");
            empty.setEnabled(false);
            recentFilesMenu.add(empty);
            return;
        }
        for (File file : recentFiles) {
            JMenuItem recent = new JMenuItem(recentFileLabel(file));
            recent.setToolTipText(file.getAbsolutePath());
            recent.addActionListener(e -> openFile(file));
            recentFilesMenu.add(recent);
        }
    }

    private String recentFileLabel(File file) {
        int duplicateNames = 0;
        for (File recent : recentFiles) {
            if (recent.getName().equals(file.getName())) {
                duplicateNames++;
            }
        }
        if (duplicateNames > 1) {
            return file.getName() + " — " + file.getParent();
        }
        return file.getName();
    }

    private JFileChooser createDiagramFileChooser() {
        JFileChooser chooser = new JFileChooser(currentFile == null ? new File(".") : currentFile.getParentFile());
        chooser.setFileFilter(new FileNameExtensionFilter("Diagram (*.dgm.json, *.json)", "json"));
        chooser.addChoosableFileFilter(new FileNameExtensionFilter(
                "vdml project (*.vdmlproj)", "vdmlproj"));
        return chooser;
    }

    private Path projectVhdlDirectory() {
        if (currentFile == null || !VhdlProjectIO.isProjectFile(currentFile.toPath())) {
            return null;
        }
        return VhdlProjectIO.sourceDirectory(currentFile.toPath());
    }

    private void configureVhdlLanguageServer() {
        String current = VhdlLanguageServerSession.configuredExecutable();
        String entered = JOptionPane.showInputDialog(frame,
                "Enter the VHDL language-server executable (for example, vhdl_ls).\n"
                        + "Leave blank to auto-detect vhdl_ls on PATH; GHDL remains available as fallback.",
                current);
        if (entered == null) {
            return;
        }
        try {
            VhdlLanguageServerSession.setConfiguredExecutable(entered);
        } catch (SecurityException ex) {
            error("Could not save language-server settings: " + ex.getMessage());
        }
    }

    private boolean selectedProjectFilter(JFileChooser chooser) {
        if (!(chooser.getFileFilter() instanceof FileNameExtensionFilter filter)) {
            return false;
        }
        for (String extension : filter.getExtensions()) {
            if (extension.equalsIgnoreCase("vdmlproj")) {
                return true;
            }
        }
        return false;
    }

    private void watchProjectFiles() throws IOException {
        projectFileLink = new VhdlProjectFileLink(currentFile.toPath(), model, this::error);
    }

    private void closeProjectFileLink() {
        if (projectFileLink == null) {
            return;
        }
        projectFileLink.close();
        projectFileLink = null;
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
        for (var file : VhdlEmitter.emitEntityArchitectureFiles(model).entrySet()) {
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

    private void importEntityAsComponent() {
        JFileChooser chooser = createVhdlImportChooser();
        if (chooser.showOpenDialog(frame) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        try {
            EntityDef imported = VhdlEntityIO.readEntity(chooser.getSelectedFile().toPath());
            EntityDef existing = findEntityIgnoreCase(imported.name());
            String oldName = null;
            if (existing != null) {
                int replace = JOptionPane.showConfirmDialog(frame,
                        "Replace catalogue entity " + existing.name() + " with the imported interface?",
                        "Replace entity", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
                if (replace != JOptionPane.YES_OPTION) {
                    return;
                }
                oldName = existing.name();
            }
            String problem = model.defineEntity(oldName, imported);
            if (problem != null) {
                error("Could not import entity: " + problem);
                return;
            }
            statusBar.setText("Imported " + imported.name() + " as a reusable component");
        } catch (IOException | RuntimeException ex) {
            error("Could not import VHDL entity: " + ex.getMessage());
        }
    }

    private void importTopLevelInterface() {
        JFileChooser chooser = createVhdlImportChooser();
        if (chooser.showOpenDialog(frame) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        try {
            EntityDef imported = VhdlEntityIO.readEntity(chooser.getSelectedFile().toPath());
            boolean topWiresExist = model.getWires().stream()
                    .anyMatch(wire -> wire.fromBlock().equals(DiagramModel.TOP_ID)
                            || wire.toBlock().equals(DiagramModel.TOP_ID));
            if (topWiresExist && !model.getTopPorts().equals(imported.ports())) {
                int replace = JOptionPane.showConfirmDialog(frame,
                        "Changing the top-level interface may remove wires connected to ports that no longer match. "
                                + "Continue?",
                        "Replace top-level interface", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
                if (replace != JOptionPane.YES_OPTION) {
                    return;
                }
            }
            String problem = model.importTopInterface(imported);
            if (problem != null) {
                error("Could not import top-level interface: " + problem);
                return;
            }
            statusBar.setText("Imported top-level interface " + imported.name());
        } catch (IOException | RuntimeException ex) {
            error("Could not import VHDL interface: " + ex.getMessage());
        }
    }

    private void importVhdlDirectory() {
        File start = currentFile == null ? new File(".") : currentFile.getAbsoluteFile().getParentFile();
        JFileChooser chooser = new JFileChooser(start);
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        chooser.setDialogTitle("Choose a VHDL export directory");
        if (chooser.showOpenDialog(frame) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        try {
            List<EntityDef> imported = VhdlEntityIO.readEntities(chooser.getSelectedFile().toPath()).stream()
                    .filter(entity -> !StandardLibrary.contains(entity.name()))
                    .toList();
            if (imported.isEmpty()) {
                error("The selected directory contains no importable VHDL entities.");
                return;
            }
            EntityDef topNameConflict = imported.stream()
                    .filter(entity -> entity.name().equalsIgnoreCase(model.getTopEntity()))
                    .findFirst().orElse(null);
            if (topNameConflict != null) {
                error("Cannot import " + topNameConflict.name()
                        + " as a component because it is the current top-level entity.");
                return;
            }
            List<String> replacements = imported.stream()
                    .map(entity -> findEntityIgnoreCase(entity.name()))
                    .filter(java.util.Objects::nonNull)
                    .map(EntityDef::name)
                    .toList();
            if (!replacements.isEmpty()) {
                int replace = JOptionPane.showConfirmDialog(frame,
                        "Replace these catalogue entities?\n" + String.join(", ", replacements),
                        "Replace imported entities", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
                if (replace != JOptionPane.YES_OPTION) {
                    return;
                }
            }

            int applied = 0;
            for (EntityDef entity : imported) {
                EntityDef existing = findEntityIgnoreCase(entity.name());
                String problem = model.defineEntity(existing == null ? null : existing.name(), entity);
                if (problem != null) {
                    while (applied-- > 0) {
                        model.undo();
                    }
                    error("Could not import VHDL directory: " + problem);
                    return;
                }
                applied++;
            }
            statusBar.setText("Imported " + imported.size() + " VHDL entities as components");
        } catch (IOException | RuntimeException ex) {
            error("Could not import VHDL directory: " + ex.getMessage());
        }
    }

    private void exportEntity() {
        String topChoice = "Top-level: " + model.getTopEntity();
        List<String> choices = new ArrayList<>();
        choices.add(topChoice);
        model.getEntityCatalog().values().stream()
                .filter(entity -> !StandardLibrary.contains(entity.name()))
                .map(EntityDef::name)
                .forEach(choices::add);
        Object selected = JOptionPane.showInputDialog(frame, "Choose the top-level entity or a custom component:",
                "Export entity", JOptionPane.PLAIN_MESSAGE, null,
                choices.toArray(), topChoice);
        if (!(selected instanceof String selection)) {
            return;
        }
        String entityName;
        String source;
        if (selection.equals(topChoice)) {
            entityName = model.getTopEntity();
            source = VhdlEmitter.emitStructuralArchitecture(model);
        } else {
            EntityDef entity = model.getEntityCatalog().get(selection);
            if (entity == null) {
                error("The selected entity is no longer in the catalogue.");
                return;
            }
            entityName = entity.name();
            source = VhdlEmitter.emitEntityArchitectureFile(model, entity);
        }
        JFileChooser chooser = new JFileChooser(currentFile == null
                ? new File(".") : currentFile.getAbsoluteFile().getParentFile());
        chooser.setDialogTitle("Export entity " + entityName);
        chooser.setFileFilter(new FileNameExtensionFilter("VHDL source (*.vhd)", "vhd"));
        chooser.setSelectedFile(new File(entityName + ".vhd"));
        if (chooser.showSaveDialog(frame) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        File output = ensureVhdlExtension(chooser.getSelectedFile());
        if (output.exists()) {
            int replace = JOptionPane.showConfirmDialog(frame,
                    "Replace " + output.getName() + "?", "Confirm export",
                    JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
            if (replace != JOptionPane.YES_OPTION) {
                return;
            }
        }
        try {
            Files.writeString(output.toPath(), source, StandardCharsets.UTF_8);
            statusBar.setText("Exported " + entityName + " to " + output.getName());
        } catch (IOException ex) {
            error("Could not export entity: " + ex.getMessage());
        }
    }

    private JFileChooser createVhdlImportChooser() {
        File start = currentFile == null ? new File(".") : currentFile.getAbsoluteFile().getParentFile();
        JFileChooser chooser = new JFileChooser(start);
        chooser.setDialogTitle("Import VHDL entity");
        chooser.setFileFilter(new FileNameExtensionFilter("VHDL source (*.vhd, *.vhdl)", "vhd", "vhdl"));
        return chooser;
    }

    private EntityDef findEntityIgnoreCase(String name) {
        return model.getEntityCatalog().values().stream()
                .filter(entity -> entity.name().equalsIgnoreCase(name))
                .findFirst().orElse(null);
    }

    private File ensureVhdlExtension(File file) {
        String name = file.getName().toLowerCase(java.util.Locale.ROOT);
        if (name.endsWith(".vhd") || name.endsWith(".vhdl")) {
            return file;
        }
        return new File(file.getParentFile(), file.getName() + ".vhd");
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
        var entityFiles = VhdlEmitter.emitEntityArchitectureFiles(model);
        for (var file : entityFiles.entrySet()) {
            Files.writeString(dir.resolve(file.getKey()), file.getValue(), StandardCharsets.UTF_8);
        }
        Files.writeString(dir.resolve(model.getTopEntity() + ".vhd"),
                VhdlEmitter.emitStructuralArchitecture(model), StandardCharsets.UTF_8);
        int sourceFileCount = standardFiles.size() + entityFiles.size();
        String sourceSummary = sourceFileCount == 0 ? "" : " plus "
                + sourceFileCount + " component source file(s)";
        JOptionPane.showMessageDialog(frame, "Wrote components_pkg.vhd and " + model.getTopEntity()
                + ".vhd" + sourceSummary + " to\n" + dir);
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
        synchronizeLinkedProject();
    }

    private void synchronizeLinkedProject() {
        if (currentFile == null || !VhdlProjectIO.isProjectFile(currentFile.toPath())) {
            return;
        }
        try {
            VhdlProjectIO.save(currentFile.toPath(), model);
            validationStatus.setToolTipText(null);
        } catch (IOException ex) {
            validationStatus.setText("Project sync failed");
            validationStatus.setToolTipText(ex.getMessage());
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
