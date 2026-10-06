package vdml.ui;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.event.ActionEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import javax.swing.AbstractAction;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JTextField;
import javax.swing.JViewport;
import javax.swing.SwingUtilities;
import javax.swing.KeyStroke;

import vdml.model.Block;
import vdml.model.DiagramModel;
import vdml.model.Direction;
import vdml.model.EntityDef;
import vdml.model.Port;
import vdml.model.Wire;
import vdml.vhdl.VhdlTypeWidths;

public class DiagramCanvas extends JComponent {
    static final int BLOCK_W = Block.DEFAULT_WIDTH;
    static final int TITLE_H = 28;
    static final int ROW_H = 24;
    static final int PAD = 10;
    static final int PIN = 8;
    static final int HIT = 8;
    static final int GRID = 20;
    static final int LEFT_GUTTER = 1000;
    static final int RIGHT_GUTTER = 1000;
    static final int TOP_GUTTER = 600;
    static final int BOTTOM_GUTTER = 600;
    static final int TOP_IN_X = 170;
    static final int TOP_ROW_H = 36;
    static final int TOP_FIRST_Y = 60;
    private static final Color BG = new Color(0x22242a);
    private static final Color BLOCK_FILL = new Color(0x33363f);
    private static final Color TITLE_FILL = new Color(0x3f4350);
    private static final Color GRID_COLOR = new Color(0x2a2d33);
    private static final Color WIRE = new Color(0xffc24d);
    private static final Color SELECT = new Color(0x6d4aff);
    private static final Color WIRE_SELECTED = new Color(0x55ff55);
    private static final Color PIN_COLOR = new Color(0x9aa0b4);
    private static final Color TEXT = new Color(0xe6e8ef);
    private static final Color DIM = new Color(0x6b7083);

    /** A connectable pin; port is as seen by wires (top-level inputs act as drivers). */
    private record Pin(String blockId, Port port, Point point) {
        boolean isDriver() {
            return port.dir() == Direction.OUT;
        }

        boolean same(Pin other) {
            return other != null && blockId.equals(other.blockId) && port.name().equals(other.port.name());
        }
    }

    private final DiagramModel model;
    private Consumer<Block> blockActivated = b -> { };
    private Consumer<String> status = s -> { };
    private Consumer<Double> zoomChanged = z -> { };
    private double zoom = 1.0;
    private String selectedBlockId;
    private String selectedWireId;
    private final Set<String> selectedBlockIds = new LinkedHashSet<>();
    private final Set<String> selectedWireIds = new LinkedHashSet<>();
    private Pin pending;
    private boolean pendingHadConnection;
    private Pin hover;
    private Point mouse = new Point();

    private Map<String, Point> dragBlockStartPositions;
    private Point dragBlockStartMouse;
    private boolean dragMoved;
    private Point marqueeStart;
    private Point marqueeEnd;
    private boolean marqueeActive;
    private boolean marqueeMoved;
    private boolean marqueeAdditive;
    private Block resizeBlock;
    private Point resizeStartMouse;
    private int resizeStartWidth;
    private int resizeStartHeight;
    private int resizeStartStoredHeight;
    private boolean resizeMoved;
    private Wire routeDragWire;
    private Point routeDragStartMouse;
    private List<Point> routeDragStartPoints;
    private List<Point> routeDragPoints;
    private int routeDragSegment;
    private boolean routeDragMoved;
    private boolean spaceDown;
    private boolean panning;
    private Point panStartMouse;
    private Point panStartView;

    public DiagramCanvas(DiagramModel model) {
        this.model = model;
        setFocusable(true);
        setOpaque(true);
        model.addChangeListener(this::onModelChanged);
        installMouse();
        installMouseWheel();
        installKeys();
        updatePreferredSize();
    }

    public void setBlockActivatedListener(Consumer<Block> listener) {
        this.blockActivated = listener;
    }

    public void setStatusListener(Consumer<String> listener) {
        this.status = listener;
    }

    public void setZoomChangedListener(Consumer<Double> listener) {
        this.zoomChanged = listener;
    }

    public double getZoom() {
        return zoom;
    }

    public String getTopEntityName() {
        return model.getTopEntity();
    }

    public Dimension getExportSize() {
        Rectangle bounds = exportBounds();
        return new Dimension(bounds.width, bounds.height);
    }

    public BufferedImage renderExportImage(int pixelWidth, int pixelHeight) {
        if (pixelWidth <= 0 || pixelHeight <= 0) {
            throw new IllegalArgumentException("image dimensions must be positive");
        }
        Rectangle bounds = exportBounds();
        BufferedImage image = new BufferedImage(pixelWidth, pixelHeight, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setColor(BG);
        g.fillRect(0, 0, pixelWidth, pixelHeight);
        double scale = Math.min(pixelWidth / (double) bounds.width, pixelHeight / (double) bounds.height);
        double drawWidth = bounds.width * scale;
        double drawHeight = bounds.height * scale;
        g.translate((pixelWidth - drawWidth) / 2, (pixelHeight - drawHeight) / 2);
        g.scale(scale, scale);
        g.translate(-bounds.x, -bounds.y);
        paintDiagram(g, bounds.x, bounds.y, bounds.x + bounds.width, bounds.y + bounds.height, false);
        g.dispose();
        return image;
    }

    public void zoomIn() {
        zoomAt(zoom * 1.2, null);
    }

    public void zoomOut() {
        zoomAt(zoom / 1.2, null);
    }

    public void resetZoom() {
        zoomAt(1.0, null);
    }

    public void showOrigin() {
        JViewport viewport = (JViewport) SwingUtilities.getAncestorOfClass(JViewport.class, this);
        if (viewport != null) {
            viewport.setViewPosition(new Point((int) Math.round(LEFT_GUTTER * zoom),
                    (int) Math.round(TOP_GUTTER * zoom)));
        }
    }

    public void addBlock(String entityName) {
        int n = model.getBlocks().size();
        String error = model.addBlock(new Block(freeBlockId(), entityName, freeLabel(entityName),
                TOP_IN_X + GRID * 6 + (n % 6) * GRID * 2, GRID * 3 + (n % 6) * GRID * 2));
        if (error != null) {
            showError(error);
            return;
        }
        select(model.getBlocks().get(model.getBlocks().size() - 1).getId(), null);
    }

    public void deleteSelected() {
        String error = model.removeObjects(selectedBlockIds, selectedWireIds);
        if (error != null) {
            showError(error);
            return;
        }
        select(null, null);
    }

    public void cancelPending() {
        if (pending != null) {
            pending = null;
            status.accept(pendingHadConnection ? "Fan-out finished" : "Connection cancelled");
            pendingHadConnection = false;
            repaint();
        }
        if (marqueeActive) {
            marqueeActive = false;
            marqueeStart = null;
            marqueeEnd = null;
            marqueeMoved = false;
            marqueeAdditive = false;
            setCursor(java.awt.Cursor.getDefaultCursor());
            repaint();
        }
    }

    // ---- input ----

    private void installKeys() {
        bind("delete", KeyStroke.getKeyStroke(KeyEvent.VK_DELETE, 0), this::deleteSelected, WHEN_FOCUSED);
        bind("backspace", KeyStroke.getKeyStroke(KeyEvent.VK_BACK_SPACE, 0), this::deleteSelected, WHEN_FOCUSED);
        bind("escape", KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), this::cancelPending, WHEN_FOCUSED);
        bind("space-pan-pressed", KeyStroke.getKeyStroke(KeyEvent.VK_SPACE, 0, false),
                () -> spaceDown = true, WHEN_FOCUSED);
        bind("space-pan-released", KeyStroke.getKeyStroke(KeyEvent.VK_SPACE, 0, true),
                () -> spaceDown = false, WHEN_FOCUSED);
        bind("redo", KeyStroke.getKeyStroke(KeyEvent.VK_Z, InputEvent.CTRL_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK),
                model::redo, WHEN_IN_FOCUSED_WINDOW);
    }

    private void installMouseWheel() {
        addMouseWheelListener(e -> {
            zoomAt(zoom * Math.pow(1.12, -e.getPreciseWheelRotation()), e.getPoint());
            e.consume();
        });
    }

    private void zoomAt(double requested, Point componentAnchor) {
        double nextZoom = Math.max(0.3, Math.min(2.5, requested));
        if (Math.abs(nextZoom - zoom) < 0.001) {
            return;
        }

        JViewport viewport = (JViewport) SwingUtilities.getAncestorOfClass(JViewport.class, this);
        Point viewportAnchor = null;
        double worldX = 0;
        double worldY = 0;
        if (viewport != null) {
            Dimension extent = viewport.getExtentSize();
            viewportAnchor = componentAnchor == null
                    ? new Point(extent.width / 2, extent.height / 2)
                    : SwingUtilities.convertPoint(this, componentAnchor, viewport);
            Point view = viewport.getViewPosition();
            worldX = (view.x + viewportAnchor.x) / zoom - LEFT_GUTTER;
            worldY = (view.y + viewportAnchor.y) / zoom - TOP_GUTTER;
        }

        zoom = nextZoom;
        updatePreferredSize();
        zoomChanged.accept(zoom);
        if (viewport != null) {
            Point anchor = viewportAnchor;
            double anchorWorldX = worldX;
            double anchorWorldY = worldY;
            SwingUtilities.invokeLater(() -> {
                int x = (int) Math.round((anchorWorldX + LEFT_GUTTER) * zoom - anchor.x);
                int y = (int) Math.round((anchorWorldY + TOP_GUTTER) * zoom - anchor.y);
                viewport.setViewPosition(new Point(Math.max(0, x), Math.max(0, y)));
            });
        }
    }

    private void bind(String name, KeyStroke stroke, Runnable action, int condition) {
        getInputMap(condition).put(stroke, name);
        getActionMap().put(name, new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                action.run();
            }
        });
    }

    private void installMouse() {
        MouseAdapter adapter = new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                requestFocusInWindow();
                if (e.isPopupTrigger()) {
                    popup(e);
                } else if (e.getButton() == MouseEvent.BUTTON2
                        || (e.getButton() == MouseEvent.BUTTON1 && spaceDown)) {
                    startPan(e.getPoint());
                } else if (e.getButton() == MouseEvent.BUTTON1) {
                    handlePress(e, logicalPoint(e.getPoint()));
                }
            }

            @Override
            public void mouseDragged(MouseEvent e) {
                Point p = logicalPoint(e.getPoint());
                mouse = p;
                if (panning) {
                    updatePan(e.getPoint());
                } else if (marqueeActive) {
                    marqueeEnd = p;
                    marqueeMoved = !p.equals(marqueeStart);
                } else if (resizeBlock != null) {
                    int width = Math.max(Block.MIN_WIDTH,
                            snap(resizeStartWidth + p.x - resizeStartMouse.x));
                    int height = Math.max(minimumBlockHeight(resizeBlock),
                            snap(resizeStartHeight + p.y - resizeStartMouse.y));
                    resizeBlock.setSize(width, height);
                    resizeMoved = width != resizeStartWidth || height != resizeStartHeight;
                    updatePreferredSize();
                } else if (dragBlockStartPositions != null) {
                    int dx = p.x - dragBlockStartMouse.x;
                    int dy = p.y - dragBlockStartMouse.y;
                    for (Map.Entry<String, Point> entry : dragBlockStartPositions.entrySet()) {
                        Block block = model.getBlock(entry.getKey());
                        if (block != null) {
                            Point start = entry.getValue();
                            block.moveTo(Math.max(0, snap(start.x + dx)), Math.max(0, snap(start.y + dy)));
                        }
                    }
                    dragMoved = dx != 0 || dy != 0;
                    updatePreferredSize();
                } else if (routeDragWire != null) {
                    routeDragPoints = movedSegment(routeDragStartPoints, routeDragSegment,
                            p.x - routeDragStartMouse.x, p.y - routeDragStartMouse.y);
                    routeDragMoved = true;
                }
                repaint();
            }

            @Override
            public void mouseMoved(MouseEvent e) {
                mouse = logicalPoint(e.getPoint());
                Pin now = pinAt(mouse);
                boolean changed = now == null ? hover != null : !now.same(hover);
                hover = now;
                Block block = blockAt(mouse);
                boolean overResize = block != null && isResizeHandle(block, mouse);
                Wire wire = now == null && !overResize ? wireAt(mouse) : null;
                if (wire != null) {
                    block = null;
                }
                int draggableSegment = wire == null ? -1 : draggableWireSegmentAt(wire, mouse);
                boolean overWire = wire != null;
                boolean overBlock = now == null && block != null;
                int cursor = marqueeActive ? java.awt.Cursor.CROSSHAIR_CURSOR
                        : overResize ? java.awt.Cursor.SE_RESIZE_CURSOR
                        : overWire ? draggableSegment >= 0 ? wireSegmentCursor(wire, draggableSegment)
                                : java.awt.Cursor.HAND_CURSOR
                        : overBlock ? java.awt.Cursor.MOVE_CURSOR
                        : now != null ? java.awt.Cursor.HAND_CURSOR : java.awt.Cursor.DEFAULT_CURSOR;
                setCursor(java.awt.Cursor.getPredefinedCursor(cursor));
                if (changed || pending != null) {
                    repaint();
                }
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                if (e.isPopupTrigger()) {
                    popup(e);
                }
                finishDrag();
                finishPan();
            }
        };
        addMouseListener(adapter);
        addMouseMotionListener(adapter);
    }

    private void finishDrag() {
        if (marqueeActive) {
            finishMarquee();
        } else if (resizeBlock != null) {
            Block block = resizeBlock;
            int width = block.getWidth();
            int height = block.getHeight();
            resizeBlock = null;
            block.setSize(resizeStartWidth, resizeStartStoredHeight);
            if (resizeMoved) {
                String error = model.resizeBlock(block.getId(), width, height);
                if (error != null) {
                    showError(error);
                }
            }
            resizeStartMouse = null;
            resizeMoved = false;
        } else if (dragBlockStartPositions == null) {
            finishWireDrag();
        } else {
            Map<String, Point> destinations = new LinkedHashMap<>();
            for (Map.Entry<String, Point> entry : dragBlockStartPositions.entrySet()) {
                Block block = model.getBlock(entry.getKey());
                if (block != null) {
                    destinations.put(entry.getKey(), new Point(block.getX(), block.getY()));
                    block.moveTo(entry.getValue().x, entry.getValue().y);
                }
            }
            dragBlockStartPositions = null;
            dragBlockStartMouse = null;
            if (dragMoved) {
                model.moveBlocks(destinations);
            }
            dragMoved = false;
        }
        repaint();
    }

    private void finishMarquee() {
        boolean moved = marqueeMoved;
        boolean additive = marqueeAdditive;
        Rectangle bounds = marqueeBounds();
        marqueeActive = false;
        marqueeStart = null;
        marqueeEnd = null;
        marqueeMoved = false;
        marqueeAdditive = false;
        setCursor(java.awt.Cursor.getDefaultCursor());
        if (!additive) {
            selectedBlockIds.clear();
            selectedWireIds.clear();
        }
        if (moved && bounds != null && (bounds.width > 2 || bounds.height > 2)) {
            for (Block block : model.getBlocks()) {
                Rectangle blockBounds = new Rectangle(block.getX(), block.getY(),
                        block.getWidth(), blockHeight(block));
                if (bounds.intersects(blockBounds)) {
                    selectedBlockIds.add(block.getId());
                }
            }
            for (Wire wire : model.getWires()) {
                int[][] points = route(wire);
                if (points == null) {
                    continue;
                }
                for (int i = 0; i < points.length - 1; i++) {
                    if (bounds.intersectsLine(points[i][0], points[i][1],
                            points[i + 1][0], points[i + 1][1])) {
                        selectedWireIds.add(wire.id());
                        break;
                    }
                }
            }
        }
        updateSelectionPrimaries();
        status.accept("Selected " + selectedBlockIds.size() + " block(s) and "
                + selectedWireIds.size() + " wire(s).");
    }

    private Rectangle marqueeBounds() {
        if (marqueeStart == null || marqueeEnd == null) {
            return null;
        }
        return new Rectangle(Math.min(marqueeStart.x, marqueeEnd.x),
                Math.min(marqueeStart.y, marqueeEnd.y),
                Math.abs(marqueeEnd.x - marqueeStart.x),
                Math.abs(marqueeEnd.y - marqueeStart.y));
    }

    private void startPan(Point p) {
        JViewport viewport = (JViewport) SwingUtilities.getAncestorOfClass(JViewport.class, this);
        if (viewport == null) {
            return;
        }
        panning = true;
        panStartMouse = SwingUtilities.convertPoint(this, p, viewport);
        panStartView = viewport.getViewPosition();
        setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.MOVE_CURSOR));
    }

    private void updatePan(Point p) {
        JViewport viewport = (JViewport) SwingUtilities.getAncestorOfClass(JViewport.class, this);
        if (viewport == null || panStartMouse == null || panStartView == null) {
            return;
        }
        Point viewportPoint = SwingUtilities.convertPoint(this, p, viewport);
        int x = Math.max(0, panStartView.x + panStartMouse.x - viewportPoint.x);
        int y = Math.max(0, panStartView.y + panStartMouse.y - viewportPoint.y);
        viewport.setViewPosition(new Point(x, y));
    }

    private void finishPan() {
        if (panning) {
            panning = false;
            panStartMouse = null;
            panStartView = null;
            setCursor(java.awt.Cursor.getDefaultCursor());
        }
    }

    private void finishWireDrag() {
        if (routeDragWire == null) {
            return;
        }
        Wire wire = routeDragWire;
        List<Point> points = routeDragPoints;
        boolean moved = routeDragMoved;
        routeDragWire = null;
        routeDragStartMouse = null;
        routeDragStartPoints = null;
        routeDragPoints = null;
        routeDragMoved = false;
        if (moved) {
            String error = model.updateWireWaypoints(wire.id(), interiorWaypoints(points));
            if (error != null) {
                showError(error);
            }
        }
    }

    private void handlePress(MouseEvent e, Point p) {
        mouse = p;
        Pin pin = pinAt(p);

        if (pending != null) {
            if (pin != null && !pin.isDriver()) {
                Pin source = pending;
                String error = model.addWire(new Wire(freeWireId(), source.blockId(), source.port().name(),
                        pin.blockId(), pin.port().name(), null));
                if (error != null) {
                    status.accept("Not connected: " + error);
                    showError(error);
                } else {
                    pendingHadConnection = true;
                    status.accept("Connected " + describe(source) + " -> " + describe(pin)
                            + ". Click more inputs to fan out, or press Esc to finish.");
                }
            } else if (pin != null) {
                pending = pin;
                pendingHadConnection = false;
                status.accept("Source changed to " + describe(pin) + ". Now click an input pin.");
            } else {
                pending = null;
                status.accept(pendingHadConnection ? "Fan-out finished" : "Connection cancelled");
                pendingHadConnection = false;
            }
            repaint();
            return;
        }

        if (pin != null) {
            if (pin.isDriver()) {
                pending = pin;
                pendingHadConnection = false;
                select(null, null);
                status.accept("Connecting from " + describe(pin)
                        + ". Click one or more input pins (Esc finishes fan-out).");
            } else {
                status.accept("Start a connection from an output pin, not an input.");
            }
            repaint();
            return;
        }

        Block blockUnderPointer = blockAt(p);
        if (blockUnderPointer != null && isResizeHandle(blockUnderPointer, p)) {
            resizeBlock = blockUnderPointer;
            resizeStartMouse = p;
            resizeStartWidth = blockUnderPointer.getWidth();
            resizeStartHeight = blockHeight(blockUnderPointer);
            resizeStartStoredHeight = blockUnderPointer.getHeight();
            resizeMoved = false;
            status.accept("Drag the corner handle to resize this block.");
            return;
        }

        Wire wire = wireAt(p);
        if (wire != null) {
            if (e.isShiftDown() || e.isControlDown()) {
                toggleWireSelection(wire.id());
                return;
            }
            if (!selectedWireIds.contains(wire.id())) {
                select(null, wire.id());
            } else if (selectedWireId == null) {
                selectedWireId = wire.id();
            }
            List<Point> points = routePoints(wire);
            int segment = draggableWireSegmentAt(wire, p);
            if (canDragSegment(points, segment)) {
                routeDragWire = wire;
                routeDragStartPoints = copyPoints(points);
                routeDragPoints = copyPoints(points);
                routeDragSegment = segment;
                routeDragStartMouse = p;
                routeDragMoved = false;
                status.accept("Drag this wire segment to reroute it. Collinear breakpoints merge automatically.");
            } else {
                status.accept("Use the wire context menu to add a breakpoint or edit this signal.");
            }
            if (e.getClickCount() >= 2) {
                renameWire(wire);
            }
            return;
        }

        Block block = blockUnderPointer;
        if (block != null) {
            if (e.isShiftDown() || e.isControlDown()) {
                toggleBlockSelection(block.getId());
                return;
            }
            if (!selectedBlockIds.contains(block.getId())) {
                select(block.getId(), null);
            } else if (selectedBlockId == null) {
                selectedBlockId = block.getId();
            }
            if (e.getClickCount() >= 2) {
                blockActivated.accept(block);
                return;
            }
            dragBlockStartPositions = new LinkedHashMap<>();
            for (String blockId : selectedBlockIds) {
                Block selectedItem = model.getBlock(blockId);
                if (selectedItem != null) {
                    dragBlockStartPositions.put(blockId,
                            new Point(selectedItem.getX(), selectedItem.getY()));
                }
            }
            dragBlockStartMouse = p;
            dragMoved = false;
            return;
        }

        marqueeStart = p;
        marqueeEnd = p;
        marqueeActive = true;
        marqueeMoved = false;
        marqueeAdditive = e.isShiftDown() || e.isControlDown();
        setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.CROSSHAIR_CURSOR));
    }

    private void toggleBlockSelection(String blockId) {
        if (!selectedBlockIds.remove(blockId)) {
            selectedBlockIds.add(blockId);
        }
        updateSelectionPrimaries();
        repaint();
    }

    private void toggleWireSelection(String wireId) {
        if (!selectedWireIds.remove(wireId)) {
            selectedWireIds.add(wireId);
        }
        updateSelectionPrimaries();
        repaint();
    }

    private void popup(MouseEvent e) {
        Point rawPoint = e.getPoint();
        Point p = logicalPoint(rawPoint);
        mouse = p;
        boolean overPin = pinAt(p) != null;
        Wire wire = overPin ? null : wireAt(p);
        Block block = overPin || wire != null ? null : blockAt(p);
        JPopupMenu menu = new JPopupMenu();
        if (block != null) {
            select(block.getId(), null);
            menu.add(item("Properties…", () -> blockActivated.accept(block)));
            menu.add(item("Delete block", this::deleteSelected));
        } else if (wire != null) {
            select(null, wire.id());
            menu.add(item("Resize…", () -> resizeWire(wire)));
            if (wire.inlineExpression() != null) {
                menu.add(item("Clear inline expression", () -> updateInlineExpression(wire, null)));
            }
            int segment = wireSegmentAt(wire, p);
            if (segment >= 0) {
                menu.add(item("Add breakpoint here", () -> addBreakpoint(wire, segment, p)));
                menu.addSeparator();
            }
            menu.add(item("Rename signal…", () -> renameWire(wire)));
            menu.add(item("Delete wire", this::deleteSelected));
        } else {
            return;
        }
        menu.show(this, rawPoint.x, rawPoint.y);
    }

    private JMenuItem item(String text, Runnable action) {
        JMenuItem item = new JMenuItem(text);
        item.addActionListener(e -> action.run());
        return item;
    }

    private void resizeWire(Wire wire) {
        Port source = model.endpointPort(wire.fromBlock(), wire.fromPort());
        Port destination = model.endpointPort(wire.toBlock(), wire.toPort());
        if (source == null || destination == null) {
            showError("Cannot resize this wire because an endpoint is missing.");
            return;
        }
        if (!VhdlTypeWidths.isVectorType(source.type()) || !VhdlTypeWidths.isVectorType(destination.type())) {
            showError("Resize is available for std_logic_vector, signed, or unsigned ports. "
                    + "Scalar std_logic wires do not need resizing.");
            return;
        }

        String sourceWidth = VhdlTypeWidths.widthExpression(model, wire.fromBlock(), wire.fromPort());
        String destinationWidth = VhdlTypeWidths.widthExpression(model, wire.toBlock(), wire.toPort());
        String resizeResultType = VhdlTypeWidths.vectorTypeName(destination.type());
        JTextField targetWidth = new JTextField(destinationWidth == null ? "" : destinationWidth, 18);
        JComboBox<String> fill = new JComboBox<>(new String[] {"0 (zero fill)", "1 (one fill)"});
        String[] fillValues = {"0", "1"};
        String signalToken = Wire.SIGNAL_TOKEN;
        String fillOnePrefix = resizeResultType + "(not (resize(unsigned(not (" + signalToken + ")), ";
        String fillZeroPrefix = resizeResultType + "(resize(unsigned(" + signalToken + "), ";
        String existingExpression = wire.inlineExpression();
        if (existingExpression != null && existingExpression.startsWith(fillOnePrefix)
                && existingExpression.endsWith(")))")) {
            targetWidth.setText(existingExpression.substring(fillOnePrefix.length(),
                    existingExpression.length() - 3));
            fill.setSelectedIndex(1);
        } else if (existingExpression != null && existingExpression.startsWith(fillZeroPrefix)
                && existingExpression.endsWith("))")) {
            targetWidth.setText(existingExpression.substring(fillZeroPrefix.length(),
                    existingExpression.length() - 2));
        }
        JPanel fields = new JPanel(new java.awt.GridLayout(0, 2, 8, 6));
        fields.add(new JLabel("Connection"));
        fields.add(new JLabel(model.describeEndpoint(wire.fromBlock(), wire.fromPort()) + " → "
                + model.describeEndpoint(wire.toBlock(), wire.toPort())));
        fields.add(new JLabel("Source width"));
        fields.add(new JLabel(sourceWidth == null ? "Could not infer" : sourceWidth));
        fields.add(new JLabel("Target width"));
        fields.add(targetWidth);
        fields.add(new JLabel("Pad with"));
        fields.add(fill);
        fields.add(new JLabel("Note"));
        fields.add(new JLabel("Wider sources are truncated to the target width."));

        int answer = JOptionPane.showConfirmDialog(this, fields, "Resize connection",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (answer != JOptionPane.OK_OPTION) {
            return;
        }
        String width = targetWidth.getText().trim();
        if (width.isEmpty()) {
            showError("Enter a target width or use the inferred destination width.");
            return;
        }
        String pad = fillValues[fill.getSelectedIndex()];
        String expression = pad.equals("0")
                ? resizeResultType + "(resize(unsigned(" + signalToken + "), " + width + "))"
                : resizeResultType + "(not (resize(unsigned(not (" + signalToken + ")), " + width + ")))";
        updateInlineExpression(wire, expression);
    }

    private void updateInlineExpression(Wire wire, String expression) {
        String error = model.updateWireInlineExpression(wire.id(), expression);
        if (error != null) {
            showError(error);
        } else {
            status.accept(expression == null
                    ? "Inline expression cleared; wire uses its original signal."
                    : "Wire resized with inline expression: "
                            + model.getWires().stream().filter(candidate -> candidate.id().equals(wire.id()))
                                    .findFirst().map(Wire::connectionExpression).orElse(expression));
        }
    }

    private void addBreakpoint(Wire wire, int segment, Point location) {
        List<Point> points = routePoints(wire);
        if (segment < 0 || segment >= points.size() - 1) {
            return;
        }
        Point a = points.get(segment);
        Point b = points.get(segment + 1);
        int length = Math.abs(b.x - a.x) + Math.abs(b.y - a.y);
        int halfSpan = Math.min(GRID, length / 4);
        if (halfSpan < 1) {
            status.accept("That wire segment is too short to add a breakpoint.");
            return;
        }

        List<Point> updated = new ArrayList<>(points.subList(0, segment + 1));
        if (a.y == b.y) {
            int low = Math.min(a.x, b.x);
            int center = Math.max(low + halfSpan, Math.min(Math.max(a.x, b.x) - halfSpan, location.x));
            int left = center - halfSpan;
            int right = center + halfSpan;
            if (a.x < b.x) {
                updated.add(new Point(left, a.y));
                updated.add(new Point(left, a.y + GRID));
                updated.add(new Point(right, a.y + GRID));
                updated.add(new Point(right, a.y));
            } else {
                updated.add(new Point(right, a.y));
                updated.add(new Point(right, a.y + GRID));
                updated.add(new Point(left, a.y + GRID));
                updated.add(new Point(left, a.y));
            }
        } else {
            int low = Math.min(a.y, b.y);
            int center = Math.max(low + halfSpan, Math.min(Math.max(a.y, b.y) - halfSpan, location.y));
            int top = center - halfSpan;
            int bottom = center + halfSpan;
            if (a.y < b.y) {
                updated.add(new Point(a.x, top));
                updated.add(new Point(a.x + GRID, top));
                updated.add(new Point(a.x + GRID, bottom));
                updated.add(new Point(a.x, bottom));
            } else {
                updated.add(new Point(a.x, bottom));
                updated.add(new Point(a.x + GRID, bottom));
                updated.add(new Point(a.x + GRID, top));
                updated.add(new Point(a.x, top));
            }
        }
        updated.addAll(points.subList(segment + 1, points.size()));
        List<Point> normalized = normalizeRoute(updated);
        String error = model.updateWireWaypoints(wire.id(), interiorWaypoints(normalized));
        if (error != null) {
            showError(error);
        } else {
            status.accept("Breakpoint added. Drag wire segments to adjust it; redundant bends merge automatically.");
        }
    }

    private void renameWire(Wire wire) {
        if (DiagramModel.TOP_ID.equals(wire.fromBlock())) {
            showError("This net is named after the top-level input '" + wire.fromPort()
                    + "'. Rename the port in the left panel instead.");
            return;
        }
        Object answer = JOptionPane.showInputDialog(this, "Signal name:", "Rename signal",
                JOptionPane.PLAIN_MESSAGE, null, null, wire.signalName());
        if (answer != null) {
            String error = model.renameWire(wire.id(), answer.toString().trim());
            if (error != null) {
                showError(error);
            }
        }
    }

    private void select(String blockId, String wireId) {
        selectedBlockId = blockId;
        selectedWireId = wireId;
        selectedBlockIds.clear();
        selectedWireIds.clear();
        if (blockId != null) {
            selectedBlockIds.add(blockId);
        }
        if (wireId != null) {
            selectedWireIds.add(wireId);
        }
        repaint();
    }

    private void updateSelectionPrimaries() {
        if (selectedBlockId == null || !selectedBlockIds.contains(selectedBlockId)) {
            selectedBlockId = selectedBlockIds.stream().reduce((first, second) -> second).orElse(null);
        }
        if (selectedWireId == null || !selectedWireIds.contains(selectedWireId)) {
            selectedWireId = selectedWireIds.stream().reduce((first, second) -> second).orElse(null);
        }
    }

    private void onModelChanged() {
        selectedBlockIds.removeIf(id -> model.getBlock(id) == null);
        selectedWireIds.removeIf(id -> model.getWires().stream().noneMatch(wire -> wire.id().equals(id)));
        updateSelectionPrimaries();
        if (pending != null && model.endpointPort(pending.blockId(), pending.port().name()) == null) {
            pending = null;
            pendingHadConnection = false;
        }
        updatePreferredSize();
        repaint();
    }

    private void updatePreferredSize() {
        int w = 1000;
        int h = Math.max(600, TOP_FIRST_Y + Math.max(inputCount(), outputCount()) * TOP_ROW_H + 100);
        for (Block b : model.getBlocks()) {
            w = Math.max(w, b.getX() + b.getWidth() + 320);
            h = Math.max(h, b.getY() + blockHeight(b) + 200);
        }
        Dimension d = new Dimension((int) Math.ceil((w + LEFT_GUTTER + RIGHT_GUTTER) * zoom),
                (int) Math.ceil((h + TOP_GUTTER + BOTTOM_GUTTER) * zoom));
        if (!d.equals(getPreferredSize())) {
            setPreferredSize(d);
            revalidate();
        }
    }

    private Point logicalPoint(Point componentPoint) {
        return new Point((int) Math.round(componentPoint.x / zoom - LEFT_GUTTER),
                (int) Math.round(componentPoint.y / zoom - TOP_GUTTER));
    }

    private Rectangle exportBounds() {
        FontMetrics portMetrics = getFontMetrics(new Font(Font.SANS_SERIF, Font.PLAIN, 11));
        FontMetrics headingMetrics = getFontMetrics(new Font(Font.SANS_SERIF, Font.BOLD, 11));
        FontMetrics signalMetrics = getFontMetrics(new Font(Font.MONOSPACED, Font.PLAIN, 11));
        int minX = TOP_IN_X - 120;
        int minY = 0;
        int maxX = Math.max(1000, topOutX() - 20 + headingMetrics.stringWidth("OUTPUTS"));
        int maxY = TOP_FIRST_Y + Math.max(inputCount(), outputCount()) * TOP_ROW_H + 24;

        String inputHeading = "INPUTS  (" + model.getTopEntity() + ")";
        maxX = Math.max(maxX, TOP_IN_X - 120 + headingMetrics.stringWidth(inputHeading));
        for (Block block : model.getBlocks()) {
            minX = Math.min(minX, block.getX());
            minY = Math.min(minY, block.getY());
            maxX = Math.max(maxX, block.getX() + block.getWidth());
            maxY = Math.max(maxY, block.getY() + blockHeight(block));
        }

        for (Pin pin : allPins()) {
            String label;
            int x;
            if (DiagramModel.TOP_ID.equals(pin.blockId())) {
                label = pin.port().name() + pin.port().widthLabel();
                x = pin.isDriver() ? pin.point().x - 8 - portMetrics.stringWidth(label) : pin.point().x + 8;
            } else if (pin.port().dir() == Direction.IN) {
                label = pin.port().name() + pin.port().widthLabel();
                x = pin.point().x + 8;
            } else {
                label = pin.port().widthLabel()
                        + (pin.port().widthLabel().isEmpty() ? "" : " ") + pin.port().name();
                x = pin.point().x - 8 - portMetrics.stringWidth(label);
            }
            int baseline = pin.point().y + portMetrics.getAscent() / 2 - 1;
            minX = Math.min(minX, x);
            maxX = Math.max(maxX, x + portMetrics.stringWidth(label));
            minY = Math.min(minY, baseline - portMetrics.getAscent());
            maxY = Math.max(maxY, baseline + portMetrics.getDescent());
        }

        for (Wire wire : model.getWires()) {
            int[][] points = route(wire);
            if (points == null) {
                continue;
            }
            for (int[] point : points) {
                minX = Math.min(minX, point[0]);
                minY = Math.min(minY, point[1]);
                maxX = Math.max(maxX, point[0]);
                maxY = Math.max(maxY, point[1]);
            }
            Point label = wireLabelPosition(points);
            int labelX = label.x + 6;
            int labelBaseline = label.y - 5;
            minY = Math.min(minY, labelBaseline - signalMetrics.getAscent());
            maxY = Math.max(maxY, labelBaseline + signalMetrics.getDescent());
            maxX = Math.max(maxX, labelX + signalMetrics.stringWidth(wire.signalName()));
        }

        int padding = 24;
        return new Rectangle(minX - padding, minY - padding,
                Math.max(1, maxX - minX + padding * 2),
                Math.max(1, maxY - minY + padding * 2));
    }

    private static int snap(int v) {
        return Math.round(v / (float) GRID) * GRID;
    }

    private String freeBlockId() {
        int n = model.getBlocks().size() + 1;
        while (model.getBlock("b" + n) != null) {
            n++;
        }
        return "b" + n;
    }

    private String freeWireId() {
        int n = model.getWires().size() + 1;
        while (true) {
            String id = "w" + n;
            if (model.getWires().stream().noneMatch(w -> w.id().equals(id))) {
                return id;
            }
            n++;
        }
    }

    private String freeLabel(String entityName) {
        String base = "u_" + entityName;
        String label = base;
        int n = 2;
        while (model.isNameInUse(label)) {
            label = base + "_" + n++;
        }
        return label;
    }

    private void showError(String message) {
        JOptionPane.showMessageDialog(this, message, "Cannot complete action", JOptionPane.WARNING_MESSAGE);
    }

    private String describe(Pin pin) {
        return model.describeEndpoint(pin.blockId(), pin.port().name());
    }

    // ---- geometry ----

    private EntityDef def(Block b) {
        return model.getEntityCatalog().get(b.getEntityName());
    }

    private int blockHeight(Block b) {
        EntityDef d = def(b);
        int rows = d == null ? 0 : Math.max(d.inputs().size(), d.outputs().size());
        return Math.max(minimumBlockHeight(b), b.getHeight());
    }

    private int minimumBlockHeight(Block b) {
        EntityDef d = def(b);
        int rows = d == null ? 0 : Math.max(d.inputs().size(), d.outputs().size());
        return Math.max(Block.MIN_HEIGHT, TITLE_H + rows * ROW_H + PAD);
    }

    private int inputCount() {
        return (int) model.getTopPorts().stream().filter(p -> p.dir() == Direction.IN).count();
    }

    private int outputCount() {
        return (int) model.getTopPorts().stream().filter(p -> p.dir() == Direction.OUT).count();
    }

    private int topOutX() {
        int right = 0;
        for (Block b : model.getBlocks()) {
            right = Math.max(right, b.getX() + b.getWidth());
        }
        return Math.max(right + 140, 760);
    }

    private List<Pin> allPins() {
        List<Pin> pins = new ArrayList<>();
        for (Block b : model.getBlocks()) {
            EntityDef d = def(b);
            if (d == null) {
                continue;
            }
            for (Port port : d.ports()) {
                List<Port> side = port.dir() == Direction.IN ? d.inputs() : d.outputs();
                int y = b.getY() + TITLE_H + side.indexOf(port) * ROW_H + ROW_H / 2;
                int x = port.dir() == Direction.IN ? b.getX() : b.getX() + b.getWidth();
                pins.add(new Pin(b.getId(), port, new Point(x, y)));
            }
        }
        int in = 0;
        int out = 0;
        int outX = topOutX();
        for (Port port : model.getTopPorts()) {
            if (port.dir() == Direction.IN) {
                Port asDriver = new Port(port.name(), Direction.OUT, port.type());
                pins.add(new Pin(DiagramModel.TOP_ID, asDriver, new Point(TOP_IN_X, TOP_FIRST_Y + in++ * TOP_ROW_H)));
            } else {
                Port asSink = new Port(port.name(), Direction.IN, port.type());
                pins.add(new Pin(DiagramModel.TOP_ID, asSink, new Point(outX, TOP_FIRST_Y + out++ * TOP_ROW_H)));
            }
        }
        return pins;
    }

    private Point pinPoint(String blockId, String portName) {
        for (Pin pin : allPins()) {
            if (pin.blockId().equals(blockId) && pin.port().name().equals(portName)) {
                return pin.point();
            }
        }
        return null;
    }

    private Pin pinAt(Point p) {
        Pin best = null;
        double hitDistance = HIT / zoom;
        double bestDistance = hitDistance + 1;
        for (Pin pin : allPins()) {
            double d = pin.point().distance(p);
            if (d <= hitDistance && d < bestDistance) {
                best = pin;
                bestDistance = d;
            }
        }
        return best;
    }

    private Block blockAt(Point p) {
        List<Block> blocks = model.getBlocks();
        for (int i = blocks.size() - 1; i >= 0; i--) {
            Block b = blocks.get(i);
            if (p.x >= b.getX() && p.x <= b.getX() + b.getWidth()
                    && p.y >= b.getY() && p.y <= b.getY() + blockHeight(b)) {
                return b;
            }
        }
        return null;
    }

    private Block selectedBlock() {
        return selectedBlockId == null ? null : model.getBlock(selectedBlockId);
    }

    private boolean isResizeHandle(Block block, Point p) {
        if (block == null || !selectedBlockIds.contains(block.getId())) {
            return false;
        }
        int x = block.getX() + block.getWidth() - 14;
        int y = block.getY() + blockHeight(block) - 14;
        return p.x >= x && p.x <= x + 14 && p.y >= y && p.y <= y + 14;
    }

    private int[][] route(Wire w) {
        List<Point> points = routePoints(w);
        if (points == null) {
            return null;
        }
        int[][] result = new int[points.size()][2];
        for (int i = 0; i < points.size(); i++) {
            result[i][0] = points.get(i).x;
            result[i][1] = points.get(i).y;
        }
        return result;
    }

    private List<Point> routePoints(Wire w) {
        Point a = pinPoint(w.fromBlock(), w.fromPort());
        Point b = pinPoint(w.toBlock(), w.toPort());
        if (a == null || b == null) {
            return null;
        }
        if (routeDragWire != null && routeDragWire.id().equals(w.id()) && routeDragPoints != null) {
            return routeDragPoints;
        }
        List<Point> points = new ArrayList<>();
        points.add(a);
        if (w.waypoints() != null) {
            if (w.waypoints().isEmpty()) {
                if (a.y != b.y) {
                    int midX = a.x + (b.x - a.x) / 2;
                    points.add(new Point(midX, a.y));
                    points.add(new Point(midX, b.y));
                }
                points.add(b);
                return normalizeRoute(points);
            }
            for (Wire.Waypoint waypoint : w.waypoints()) {
                points.add(new Point(waypoint.x(), waypoint.y()));
            }
            if (points.size() > 1 && points.get(1).y != a.y) {
                points.add(1, new Point(points.get(1).x, a.y));
            }
            if (points.size() > 1 && points.get(points.size() - 1).y != b.y) {
                Point last = points.get(points.size() - 1);
                points.add(new Point(last.x, b.y));
            }
            points.add(b);
            return normalizeRoute(points);
        }
        int mx = w.bendX() == null ? a.x + (b.x - a.x) / 3 : w.bendX();
        int my = w.bendY() == null ? (a.y + b.y) / 2 : w.bendY();
        int approachX = w.approachX() == null
                ? w.bendX() == null ? a.x + 2 * (b.x - a.x) / 3 : mx + (b.x - mx) / 2
                : w.approachX();
        points.add(new Point(mx, a.y));
        points.add(new Point(mx, my));
        points.add(new Point(approachX, my));
        points.add(new Point(approachX, b.y));
        points.add(b);
        return normalizeRoute(points);
    }

    private static List<Point> normalizeRoute(List<Point> points) {
        List<Point> normalized = new ArrayList<>();
        for (Point point : points) {
            if (!normalized.isEmpty() && normalized.get(normalized.size() - 1).equals(point)) {
                continue;
            }
            while (normalized.size() >= 2
                    && collinearForward(normalized.get(normalized.size() - 2),
                            normalized.get(normalized.size() - 1), point)) {
                normalized.remove(normalized.size() - 1);
            }
            normalized.add(new Point(point));
        }
        return normalized;
    }

    private static boolean collinearForward(Point a, Point b, Point c) {
        if (a.x == b.x && b.x == c.x) {
            return (long) (b.y - a.y) * (c.y - b.y) >= 0;
        }
        if (a.y == b.y && b.y == c.y) {
            return (long) (b.x - a.x) * (c.x - b.x) >= 0;
        }
        return false;
    }

    private static List<Point> copyPoints(List<Point> points) {
        List<Point> copy = new ArrayList<>(points.size());
        for (Point point : points) {
            copy.add(new Point(point));
        }
        return copy;
    }

    private static boolean canDragSegment(List<Point> points, int segment) {
        if (segment <= 0 || segment >= points.size() - 2) {
            return false;
        }
        Point a = points.get(segment);
        Point b = points.get(segment + 1);
        return !a.equals(b);
    }

    private List<Point> movedSegment(List<Point> original, int segment, int dx, int dy) {
        List<Point> moved = copyPoints(original);
        Point a = moved.get(segment);
        Point b = moved.get(segment + 1);
        if (a.y == b.y) {
            int y = Math.max(0, snap(a.y + dy));
            a.y = y;
            b.y = y;
        } else {
            int x = Math.max(0, snap(a.x + dx));
            a.x = x;
            b.x = x;
        }
        return normalizeRoute(moved);
    }

    private static List<Wire.Waypoint> interiorWaypoints(List<Point> points) {
        List<Wire.Waypoint> waypoints = new ArrayList<>(Math.max(0, points.size() - 2));
        for (int i = 1; i < points.size() - 1; i++) {
            waypoints.add(new Wire.Waypoint(points.get(i).x, points.get(i).y));
        }
        return waypoints;
    }

    private static Point wireLabelPosition(int[][] points) {
        long totalLength = 0;
        for (int i = 0; i < points.length - 1; i++) {
            totalLength += Math.abs(points[i + 1][0] - points[i][0])
                    + Math.abs(points[i + 1][1] - points[i][1]);
        }
        long halfway = totalLength / 2;
        long traversed = 0;
        for (int i = 0; i < points.length - 1; i++) {
            int dx = points[i + 1][0] - points[i][0];
            int dy = points[i + 1][1] - points[i][1];
            int length = Math.abs(dx) + Math.abs(dy);
            if (traversed + length >= halfway) {
                long offset = halfway - traversed;
                int x = points[i][0] + (length == 0 ? 0 : (int) (dx * offset / length));
                int y = points[i][1] + (length == 0 ? 0 : (int) (dy * offset / length));
                return new Point(x, y);
            }
            traversed += length;
        }
        return new Point(points[0][0], points[0][1]);
    }

    private Wire wireAt(Point p) {
        List<Wire> wires = model.getWires();
        for (int i = wires.size() - 1; i >= 0; i--) {
            if (wireSegmentAt(wires.get(i), p) >= 0) {
                return wires.get(i);
            }
        }
        return null;
    }

    private int wireSegmentAt(Wire wire, Point p) {
        int[][] points = route(wire);
        if (points == null) {
            return -1;
        }
        int nearestSegment = -1;
        double nearestDistance = 7 / zoom;
        for (int i = 0; i < points.length - 1; i++) {
            double distance = distanceToSegment(p, points[i], points[i + 1]);
            if (distance <= nearestDistance) {
                nearestDistance = distance;
                nearestSegment = i;
            }
        }
        return nearestSegment;
    }

    private int draggableWireSegmentAt(Wire wire, Point p) {
        List<Point> route = routePoints(wire);
        if (route == null) {
            return -1;
        }
        int nearestSegment = -1;
        double nearestDistance = 7 / zoom;
        for (int i = 0; i < route.size() - 1; i++) {
            if (!canDragSegment(route, i)) {
                continue;
            }
            Point a = route.get(i);
            Point b = route.get(i + 1);
            double distance = distanceToSegment(p, new int[] {a.x, a.y}, new int[] {b.x, b.y});
            if (distance <= nearestDistance) {
                nearestDistance = distance;
                nearestSegment = i;
            }
        }
        return nearestSegment;
    }

    private int wireSegmentCursor(Wire wire, int segment) {
        int[][] points = route(wire);
        if (points == null || segment < 0) {
            return java.awt.Cursor.DEFAULT_CURSOR;
        }
        return points[segment][1] == points[segment + 1][1]
                ? java.awt.Cursor.N_RESIZE_CURSOR : java.awt.Cursor.E_RESIZE_CURSOR;
    }

    private static double distanceToSegment(Point p, int[] a, int[] b) {
        double dx = b[0] - a[0];
        double dy = b[1] - a[1];
        double len2 = dx * dx + dy * dy;
        double t = len2 == 0 ? 0 : ((p.x - a[0]) * dx + (p.y - a[1]) * dy) / len2;
        t = Math.max(0, Math.min(1, t));
        return Math.hypot(p.x - (a[0] + t * dx), p.y - (a[1] + t * dy));
    }

    // ---- painting ----

    @Override
    protected void paintComponent(Graphics g0) {
        Graphics2D g = (Graphics2D) g0.create();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setColor(BG);
        g.fillRect(0, 0, getWidth(), getHeight());
        g.scale(zoom, zoom);
        g.translate(LEFT_GUTTER, TOP_GUTTER);
        int right = (int) Math.ceil(getWidth() / zoom) - LEFT_GUTTER;
        int bottom = (int) Math.ceil(getHeight() / zoom) - TOP_GUTTER;
        paintDiagram(g, -LEFT_GUTTER, -TOP_GUTTER, right, bottom, true);
        g.dispose();
    }

    private void paintDiagram(Graphics2D g, int left, int top, int right, int bottom, boolean showSelection) {
        g.setColor(BG);
        g.fillRect(left, top, right - left, bottom - top);
        g.setColor(GRID_COLOR);
        for (int x = left; x < right; x += GRID) {
            g.drawLine(x, top, x, bottom);
        }
        for (int y = top; y < bottom; y += GRID) {
            g.drawLine(left, y, right, y);
        }

        paintTopBoundary(g, bottom);
        for (Block b : model.getBlocks()) {
            paintBlock(g, b, showSelection);
        }
        for (Wire w : model.getWires()) {
            paintWire(g, w, showSelection);
        }
        List<Pin> pins = allPins();
        paintTopPins(g, pins);
        if (showSelection) {
            paintPinHighlights(g, pins);
        }

        if (showSelection && pending != null) {
            Point a = pending.point();
            Pin target = pinAt(mouse);
            Point end = target != null && !target.isDriver() ? target.point() : mouse;
            g.setColor(WIRE);
            g.setStroke(new BasicStroke(1.5f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f,
                    new float[] {6f, 4f}, 0f));
            g.drawLine(a.x, a.y, end.x, end.y);
        }
        if (showSelection && marqueeActive) {
            Rectangle bounds = marqueeBounds();
            if (bounds != null) {
                g.setColor(new Color(0x6d, 0x4a, 0xff, 48));
                g.fillRect(bounds.x, bounds.y, bounds.width, bounds.height);
                g.setColor(SELECT);
                g.setStroke(new BasicStroke(1.5f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER,
                        10f, new float[] {5f, 4f}, 0f));
                g.drawRect(bounds.x, bounds.y, bounds.width, bounds.height);
            }
        }
    }

    private void paintTopBoundary(Graphics2D g, int bottom) {
        int outX = topOutX();
        g.setStroke(new BasicStroke(1f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f, new float[] {4f, 6f}, 0f));
        g.setColor(DIM);
        g.drawLine(TOP_IN_X, 20, TOP_IN_X, bottom);
        g.drawLine(outX, 20, outX, bottom);
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 11));
        g.drawString("INPUTS  (" + model.getTopEntity() + ")", TOP_IN_X - 120, 18);
        g.drawString("OUTPUTS", outX - 20, 18);
    }

    private void paintTopPins(Graphics2D g, List<Pin> pins) {
        g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 11));
        FontMetrics fm = g.getFontMetrics();
        for (Pin pin : pins) {
            if (!DiagramModel.TOP_ID.equals(pin.blockId())) {
                continue;
            }
            Point c = pin.point();
            g.setColor(PIN_COLOR);
            g.fillRect(c.x - PIN / 2, c.y - PIN / 2, PIN, PIN);
            g.setColor(TEXT);
            Port port = pin.port();
            String label = port.name() + port.widthLabel();
            int baseline = c.y + fm.getAscent() / 2 - 1;
            if (pin.isDriver()) {
                g.drawString(label, c.x - 8 - fm.stringWidth(label), baseline);
            } else {
                g.drawString(label, c.x + 8, baseline);
            }
        }
    }

    private void paintPinHighlights(Graphics2D g, List<Pin> pins) {
        g.setStroke(new BasicStroke(2f));
        if (pending != null) {
            for (Pin pin : pins) {
                boolean driven = !pin.isDriver() && model.getWires().stream()
                        .anyMatch(w -> w.toBlock().equals(pin.blockId()) && w.toPort().equals(pin.port().name()));
                if (!pin.isDriver() && !driven) {
                    g.setColor(new Color(0x55ff55));
                    g.drawOval(pin.point().x - 7, pin.point().y - 7, 14, 14);
                }
            }
            g.setColor(SELECT);
            g.drawOval(pending.point().x - 8, pending.point().y - 8, 16, 16);
        }
        if (hover != null) {
            g.setColor(Color.WHITE);
            g.drawRect(hover.point().x - PIN / 2 - 2, hover.point().y - PIN / 2 - 2, PIN + 4, PIN + 4);
        }
    }

    private void paintBlock(Graphics2D g, Block b, boolean showSelection) {
        int x = b.getX();
        int y = b.getY();
        int width = b.getWidth();
        int h = blockHeight(b);
        boolean selected = showSelection && selectedBlockIds.contains(b.getId());
        g.setColor(BLOCK_FILL);
        g.fillRect(x, y, width, h);
        g.setColor(TITLE_FILL);
        g.fillRect(x, y, width, TITLE_H);
        g.setStroke(new BasicStroke(selected ? 2.5f : 1f));
        g.setColor(selected ? SELECT : PIN_COLOR);
        g.drawRect(x, y, width, h);

        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 12));
        g.setColor(TEXT);
        g.setClip(x + 4, y, width - 8, TITLE_H);
        g.drawString(b.getInstanceLabel() + " : " + b.getEntityName(), x + 8, y + 18);
        g.setClip(null);
        if (selected) {
            int handleX = x + width - 14;
            int handleY = y + h - 14;
            g.setColor(SELECT);
            g.fillRect(handleX, handleY, 14, 14);
            g.setColor(Color.WHITE);
            g.drawLine(handleX + 4, handleY + 11, handleX + 11, handleY + 4);
            g.drawLine(handleX + 8, handleY + 11, handleX + 11, handleY + 8);
        }

        EntityDef d = def(b);
        if (d == null) {
            g.setColor(Color.RED);
            g.drawString("unknown entity", x + 8, y + TITLE_H + 16);
            return;
        }
        g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 11));
        FontMetrics fm = g.getFontMetrics();
        for (Pin pin : allPins()) {
            if (!pin.blockId().equals(b.getId())) {
                continue;
            }
            Point c = pin.point();
            Port port = pin.port();
            g.setColor(PIN_COLOR);
            g.fillRect(c.x - PIN / 2, c.y - PIN / 2, PIN, PIN);
            g.setColor(TEXT);
            int baseline = c.y + fm.getAscent() / 2 - 1;
            if (port.dir() == Direction.IN) {
                g.drawString(port.name() + port.widthLabel(), c.x + 8, baseline);
            } else {
                String label = port.widthLabel() + (port.widthLabel().isEmpty() ? "" : " ") + port.name();
                g.drawString(label, c.x - 8 - fm.stringWidth(label), baseline);
            }
        }
    }

    private void paintWire(Graphics2D g, Wire w, boolean showSelection) {
        int[][] r = route(w);
        if (r == null) {
            return;
        }
        boolean selected = showSelection && selectedWireIds.contains(w.id());
        g.setStroke(new BasicStroke(selected ? 3f : 1.5f));
        g.setColor(selected ? WIRE_SELECTED : WIRE);
        for (int i = 0; i < r.length - 1; i++) {
            g.drawLine(r[i][0], r[i][1], r[i + 1][0], r[i + 1][1]);
        }
        g.fillOval(r[r.length - 1][0] - 3, r[r.length - 1][1] - 3, 6, 6);
        if (selected) {
            g.setColor(SELECT);
            g.setColor(Color.WHITE);
            for (int i = 1; i < r.length - 1; i++) {
                g.setColor(SELECT);
                g.fillRect(r[i][0] - 5, r[i][1] - 5, 10, 10);
                g.setColor(Color.WHITE);
                g.drawRect(r[i][0] - 5, r[i][1] - 5, 10, 10);
            }
        }
        g.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 11));
        Point label = wireLabelPosition(r);
        g.drawString(w.signalName(), label.x + 6, label.y - 5);
    }
}
