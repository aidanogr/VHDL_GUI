package vdml.model;

import java.awt.Point;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import vdml.model.commands.Command;

public class DiagramModel {
    public static final String DEFAULT_TOP_ENTITY = "top";
    /** Pseudo block id used by wires that start or end at a top-level port. */
    public static final String TOP_ID = "$top";

    private final Map<String, EntityDef> entityCatalog = new LinkedHashMap<>();
    private final List<Block> blocks = new ArrayList<>();
    private final List<Wire> wires = new ArrayList<>();
    private final List<Port> topPorts = new ArrayList<>();
    private final List<String> topGenerics = new ArrayList<>();
    private final List<String> libraries = new ArrayList<>(List.of("ieee"));
    private final List<String> libraryUses = new ArrayList<>(
            List.of("ieee.std_logic_1164.all", "ieee.numeric_std.all"));
    private final Map<String, Block> blocksById = new HashMap<>();
    private final List<Command> undoStack = new ArrayList<>();
    private final List<Command> redoStack = new ArrayList<>();
    private final List<ChangeListener> listeners = new ArrayList<>();
    private String topEntity = DEFAULT_TOP_ENTITY;

    public DiagramModel() {
        initDemoCatalogue();
    }

    public interface ChangeListener {
        void modelChanged();
    }

    // ---- listeners / accessors ----

    public void addChangeListener(ChangeListener listener) {
        if (listener != null) {
            listeners.add(listener);
        }
    }

    public void removeChangeListener(ChangeListener listener) {
        listeners.remove(listener);
    }

    public String getTopEntity() {
        return topEntity;
    }

    public void setTopEntity(String topEntity) {
        this.topEntity = topEntity == null ? DEFAULT_TOP_ENTITY : topEntity;
    }

    public List<Port> getTopPorts() {
        return Collections.unmodifiableList(topPorts);
    }

    /** Loader hook: replaces top-level ports without recording undo history. */
    public void setTopPorts(List<Port> ports) {
        topPorts.clear();
        if (ports != null) {
            topPorts.addAll(ports);
        }
    }

    public List<String> getTopGenerics() {
        return Collections.unmodifiableList(topGenerics);
    }

    /** Loader hook: replaces top-level generic declarations without undo history. */
    public void setTopGenerics(List<String> generics) {
        topGenerics.clear();
        if (generics != null) {
            topGenerics.addAll(generics);
        }
    }

    public List<String> getLibraries() {
        return Collections.unmodifiableList(libraries);
    }

    /** Loader hook. Passing null leaves the built-in defaults intact. */
    public void setLibraries(List<String> names) {
        if (names == null) {
            return;
        }
        libraries.clear();
        libraries.addAll(names);
    }

    public List<String> getLibraryUses() {
        return Collections.unmodifiableList(libraryUses);
    }

    /** Loader hook. Passing null leaves the built-in defaults intact. */
    public void setLibraryUses(List<String> clauses) {
        if (clauses == null) {
            return;
        }
        libraryUses.clear();
        libraryUses.addAll(clauses);
    }

    public Map<String, EntityDef> getEntityCatalog() {
        return Collections.unmodifiableMap(entityCatalog);
    }

    public void setEntityCatalog(Map<String, EntityDef> catalog) {
        replaceEntityCatalog(catalog);
    }

    public List<Block> getBlocks() {
        return Collections.unmodifiableList(blocks);
    }

    public List<Wire> getWires() {
        return Collections.unmodifiableList(wires);
    }

    public Block getBlock(String id) {
        return blocksById.get(id);
    }

    public boolean canUndo() {
        return !undoStack.isEmpty();
    }

    public boolean canRedo() {
        return !redoStack.isEmpty();
    }

    public static boolean isIdentifier(String s) {
        return s != null && s.matches("[A-Za-z][A-Za-z0-9_]*");
    }

    /**
     * Resolves a wire endpoint to the port as seen by the wire: a top-level input
     * acts as a driver (OUT) and a top-level output as a sink (IN).
     */
    public Port endpointPort(String blockId, String portName) {
        if (TOP_ID.equals(blockId)) {
            for (Port p : topPorts) {
                if (p.name().equals(portName)) {
                    return new Port(p.name(), p.dir() == Direction.IN ? Direction.OUT : Direction.IN, p.type());
                }
            }
            return null;
        }
        Block block = blocksById.get(blockId);
        EntityDef def = block == null ? null : entityCatalog.get(block.getEntityName());
        return def == null ? null : findPort(def, portName);
    }

    public String describeEndpoint(String blockId, String portName) {
        if (TOP_ID.equals(blockId)) {
            return "(top) " + portName;
        }
        Block block = blocksById.get(blockId);
        return (block == null ? blockId : block.getInstanceLabel()) + "." + portName;
    }

    /** Returns a description of the first structural problem found, or null. */
    public String checkIntegrity() {
        List<String> seenLibraries = new ArrayList<>();
        for (String library : libraries) {
            String error = validateLibrary(library);
            if (error != null) {
                return error;
            }
            if (containsIgnoreCase(seenLibraries, library)) {
                return "duplicate library: " + library;
            }
            seenLibraries.add(library);
        }
        List<String> seenUses = new ArrayList<>();
        for (String use : libraryUses) {
            String error = validateLibraryUse(use);
            if (error != null) {
                return error;
            }
            if (!containsIgnoreCase(libraries, libraryPrefix(use))) {
                return "package clause references undeclared library: " + libraryPrefix(use);
            }
            if (containsIgnoreCase(seenUses, use)) {
                return "duplicate package clause: " + use;
            }
            seenUses.add(use);
        }
        for (int i = 0; i < topGenerics.size(); i++) {
            String error = validateTopGeneric(topGenerics.get(i), i);
            if (error != null) {
                return error;
            }
        }
        for (Block block : blocks) {
            if (!entityCatalog.containsKey(block.getEntityName())) {
                return "block " + block.getId() + " uses unknown entity " + block.getEntityName();
            }
        }
        for (Wire wire : wires) {
            Port from = endpointPort(wire.fromBlock(), wire.fromPort());
            Port to = endpointPort(wire.toBlock(), wire.toPort());
            if (from == null || to == null) {
                return "wire " + wire.id() + " references an unknown block or port";
            }
            if (from.dir() != Direction.OUT || to.dir() != Direction.IN) {
                return "wire " + wire.id() + " does not run from an output to an input";
            }
        }
        return null;
    }

    /** True if a VHDL name would collide with a signal, top-level port or instance label. */
    public boolean isNameInUse(String name) {
        return nameInUse(name, null, null, null);
    }

    // ---- blocks ----

    public String addBlock(Block block) {
        if (block == null) {
            return "block is null";
        }
        String id = block.getId();
        if (id == null || id.isBlank() || TOP_ID.equals(id)) {
            return "invalid block id";
        }
        if (blocksById.containsKey(id)) {
            return "duplicate block id";
        }
        if (!entityCatalog.containsKey(block.getEntityName())) {
            return "unknown entity: " + block.getEntityName();
        }
        if (!isIdentifier(block.getInstanceLabel())) {
            return "invalid VHDL identifier: " + block.getInstanceLabel();
        }
        if (isNameInUse(block.getInstanceLabel())) {
            return "name already in use: " + block.getInstanceLabel();
        }
        Block copy = block.copy();
        blocks.add(copy);
        blocksById.put(id, copy);
        record(new Command() {
            @Override
            public void undo() {
                removeBlockInternal(id);
            }

            @Override
            public void redo() {
                blocks.add(copy);
                blocksById.put(id, copy);
            }
        });
        notifyListeners();
        return null;
    }

    public String removeBlock(String blockId) {
        Block existing = blocksById.get(blockId);
        if (existing == null) {
            return "block not found";
        }
        int index = blocks.indexOf(existing);
        List<Wire> wiresBefore = new ArrayList<>(wires);
        record(new Command() {
            @Override
            public void undo() {
                blocks.add(index, existing);
                blocksById.put(blockId, existing);
                wires.clear();
                wires.addAll(wiresBefore);
            }

            @Override
            public void redo() {
                removeBlockInternal(blockId);
            }
        });
        removeBlockInternal(blockId);
        notifyListeners();
        return null;
    }

    public void moveBlock(String blockId, int x, int y) {
        Block block = blocksById.get(blockId);
        if (block == null || (block.getX() == x && block.getY() == y)) {
            return;
        }
        int oldX = block.getX();
        int oldY = block.getY();
        record(new Command() {
            @Override
            public void undo() {
                block.moveTo(oldX, oldY);
            }

            @Override
            public void redo() {
                block.moveTo(x, y);
            }
        });
        block.moveTo(x, y);
        notifyListeners();
    }

    public void moveBlocks(Map<String, Point> positions) {
        moveBlocks(positions, Map.of());
    }

    public void moveBlocks(Map<String, Point> positions,
                           Map<String, List<Wire.Waypoint>> updatedWaypoints) {
        Map<String, Point> before = new LinkedHashMap<>();
        Map<String, Point> after = new LinkedHashMap<>();
        for (Map.Entry<String, Point> entry : positions.entrySet()) {
            Block block = blocksById.get(entry.getKey());
            Point position = entry.getValue();
            if (block != null && position != null
                    && (block.getX() != position.x || block.getY() != position.y)) {
                before.put(block.getId(), new Point(block.getX(), block.getY()));
                after.put(block.getId(), new Point(position));
            }
        }
        Map<String, Wire> wiresBefore = new LinkedHashMap<>();
        Map<String, Wire> wiresAfter = new LinkedHashMap<>();
        if (updatedWaypoints != null) {
            for (Wire wire : wires) {
                List<Wire.Waypoint> waypoints = updatedWaypoints.get(wire.id());
                if (waypoints != null && !waypoints.equals(wire.waypoints())) {
                    wiresBefore.put(wire.id(), wire);
                    wiresAfter.put(wire.id(), new Wire(wire.id(), wire.fromBlock(), wire.fromPort(),
                            wire.toBlock(), wire.toPort(), wire.signalName(), wire.bendX(), wire.bendY(),
                            wire.approachX(), waypoints, wire.inlineExpression(), wire.labelX(), wire.labelY()));
                }
            }
        }
        if (after.isEmpty() && wiresAfter.isEmpty()) {
            return;
        }
        record(new Command() {
            @Override
            public void undo() {
                applyBlockPositions(before);
                applyWireStates(wiresBefore);
            }

            @Override
            public void redo() {
                applyBlockPositions(after);
                applyWireStates(wiresAfter);
            }
        });
        applyBlockPositions(after);
        applyWireStates(wiresAfter);
        notifyListeners();
    }

    public String resizeBlock(String blockId, int width, int height) {
        Block block = blocksById.get(blockId);
        if (block == null) {
            return "block not found";
        }
        if (width < Block.MIN_WIDTH || height < Block.MIN_HEIGHT) {
            return "block size is below the minimum";
        }
        if (block.getWidth() == width && block.getHeight() == height) {
            return null;
        }
        int oldWidth = block.getWidth();
        int oldHeight = block.getHeight();
        record(new Command() {
            @Override
            public void undo() {
                block.setSize(oldWidth, oldHeight);
            }

            @Override
            public void redo() {
                block.setSize(width, height);
            }
        });
        block.setSize(width, height);
        notifyListeners();
        return null;
    }

    public String updateBlock(String blockId, String label, Map<String, String> newGenerics) {
        Block block = blocksById.get(blockId);
        if (block == null) {
            return "block not found";
        }
        if (!isIdentifier(label)) {
            return "invalid VHDL identifier: " + label;
        }
        if (nameInUse(label, block, null, null)) {
            return "name already in use: " + label;
        }
        String oldLabel = block.getInstanceLabel();
        Map<String, String> oldGenerics = new LinkedHashMap<>(block.getGenerics());
        Map<String, String> updated = new LinkedHashMap<>(newGenerics);
        applyBlockProps(block, label, updated);
        record(new Command() {
            @Override
            public void undo() {
                applyBlockProps(block, oldLabel, oldGenerics);
            }

            @Override
            public void redo() {
                applyBlockProps(block, label, updated);
            }
        });
        notifyListeners();
        return null;
    }

    // ---- wires ----

    public String addWire(Wire wire) {
        if (wire == null) {
            return "wire is null";
        }
        if (wire.id() == null || wire.id().isBlank()) {
            return "wire id is required";
        }
        if (wire.fromBlock() == null || wire.toBlock() == null) {
            return "wire endpoints are required";
        }
        Port from = endpointPort(wire.fromBlock(), wire.fromPort());
        Port to = endpointPort(wire.toBlock(), wire.toPort());
        if (from == null || to == null) {
            return "unknown block or port";
        }
        if (from.dir() != Direction.OUT || to.dir() != Direction.IN) {
            return "connections go output -> input only";
        }
        for (Wire existing : wires) {
            boolean sameTarget = existing.toBlock().equals(wire.toBlock()) && existing.toPort().equals(wire.toPort());
            if (sameTarget && existing.fromBlock().equals(wire.fromBlock())
                    && existing.fromPort().equals(wire.fromPort())) {
                return "duplicate connection";
            }
            if (sameTarget) {
                return "input pin already driven";
            }
        }

        // Wires fanning out from one pin share a single net; top-level inputs use their port name.
        String signalName = null;
        if (TOP_ID.equals(wire.fromBlock())) {
            signalName = wire.fromPort();
        } else {
            for (Wire existing : wires) {
                if (existing.fromBlock().equals(wire.fromBlock()) && existing.fromPort().equals(wire.fromPort())) {
                    signalName = existing.signalName();
                    break;
                }
            }
            if (signalName == null) {
                signalName = wire.signalName();
                if (signalName == null || signalName.isBlank()) {
                    signalName = "s_" + normalizeIdentifier(wire.fromPort()) + "_" + normalizeIdentifier(wire.toPort());
                }
                signalName = ensureUniqueSignalName(signalName);
            }
        }
        Wire created = new Wire(wire.id(), wire.fromBlock(), wire.fromPort(), wire.toBlock(), wire.toPort(),
                signalName, wire.bendX(), wire.bendY(), wire.approachX(), wire.waypoints(),
                wire.inlineExpression(), wire.labelX(), wire.labelY());
        wires.add(created);
        record(new Command() {
            @Override
            public void undo() {
                wires.remove(created);
            }

            @Override
            public void redo() {
                wires.add(created);
            }
        });
        notifyListeners();
        return null;
    }

    public String removeWire(String wireId) {
        Wire existing = findWire(wireId);
        if (existing == null) {
            return "wire not found";
        }
        int index = wires.indexOf(existing);
        record(new Command() {
            @Override
            public void undo() {
                wires.add(index, existing);
            }

            @Override
            public void redo() {
                wires.remove(existing);
            }
        });
        wires.remove(existing);
        notifyListeners();
        return null;
    }

    public String removeObjects(Set<String> blockIds, Set<String> wireIds) {
        Set<String> blocksToRemove = blockIds == null ? Set.of() : new LinkedHashSet<>(blockIds);
        Set<String> wiresToRemove = wireIds == null ? Set.of() : new LinkedHashSet<>(wireIds);
        for (String blockId : blocksToRemove) {
            if (!blocksById.containsKey(blockId)) {
                return "block not found: " + blockId;
            }
        }
        for (String wireId : wiresToRemove) {
            if (findWire(wireId) == null) {
                return "wire not found: " + wireId;
            }
        }
        if (blocksToRemove.isEmpty() && wiresToRemove.isEmpty()) {
            return null;
        }
        List<Block> blocksBefore = new ArrayList<>(blocks);
        List<Wire> wiresBefore = new ArrayList<>(wires);
        List<Block> blocksAfter = blocksBefore.stream()
                .filter(block -> !blocksToRemove.contains(block.getId())).toList();
        List<Wire> wiresAfter = wiresBefore.stream()
                .filter(wire -> !wiresToRemove.contains(wire.id())
                        && !blocksToRemove.contains(wire.fromBlock())
                        && !blocksToRemove.contains(wire.toBlock()))
                .toList();
        record(new Command() {
            @Override
            public void undo() {
                applyBlocksAndWires(blocksBefore, wiresBefore);
            }

            @Override
            public void redo() {
                applyBlocksAndWires(blocksAfter, wiresAfter);
            }
        });
        applyBlocksAndWires(blocksAfter, wiresAfter);
        notifyListeners();
        return null;
    }

    /** Moves a wire's visual elbow only; its endpoints and signal remain unchanged. */
    public String moveWireElbow(String wireId, int x, int y) {
        Wire existing = findWire(wireId);
        if (existing == null) {
            return "wire not found";
        }
        return updateWireRoute(existing, existing.withBend(x, y));
    }

    /** Moves the departure, shared route, and port-approach runs without changing endpoints. */
    public String moveWireRoute(String wireId, int departureX, int routeY, int approachX) {
        Wire existing = findWire(wireId);
        if (existing == null) {
            return "wire not found";
        }
        return updateWireRoute(existing, existing.withRoute(departureX, routeY, approachX));
    }

    public String updateWireWaypoints(String wireId, List<Wire.Waypoint> waypoints) {
        Wire existing = findWire(wireId);
        if (existing == null) {
            return "wire not found";
        }
        return updateWireRoute(existing, existing.withWaypoints(waypoints));
    }

    public String updateWireLabelPosition(String wireId, int x, int y) {
        Wire existing = findWire(wireId);
        if (existing == null) {
            return "wire not found";
        }
        return updateWireRoute(existing, existing.withLabelPosition(x, y));
    }

    public String updateWireInlineExpression(String wireId, String expression) {
        Wire existing = findWire(wireId);
        if (existing == null) {
            return "wire not found";
        }
        String normalized = expression == null || expression.isBlank() ? null : expression.trim();
        if (normalized != null && (normalized.contains("\n") || normalized.contains("\r"))) {
            return "inline expressions must fit on one line";
        }
        return updateWireRoute(existing, existing.withInlineExpression(normalized));
    }

    private String updateWireRoute(Wire existing, Wire moved) {
        if (existing.equals(moved)) {
            return null;
        }
        int index = wires.indexOf(existing);
        wires.set(index, moved);
        record(new Command() {
            @Override
            public void undo() {
                wires.set(index, existing);
            }

            @Override
            public void redo() {
                wires.set(index, moved);
            }
        });
        notifyListeners();
        return null;
    }

    public String renameWire(String wireId, String newName) {
        Wire target = findWire(wireId);
        if (target == null) {
            return "wire not found";
        }
        if (TOP_ID.equals(target.fromBlock())) {
            return "this net is named after its top-level input port; rename the port instead";
        }
        if (!isIdentifier(newName)) {
            return "invalid VHDL identifier: " + newName;
        }
        if (nameInUse(newName, null, null, target)) {
            return "name already in use: " + newName;
        }
        List<Wire> oldNet = new ArrayList<>();
        List<Wire> newNet = new ArrayList<>();
        for (Wire w : wires) {
            if (sameNet(w, target)) {
                oldNet.add(w);
                newNet.add(new Wire(w.id(), w.fromBlock(), w.fromPort(), w.toBlock(), w.toPort(),
                        newName, w.bendX(), w.bendY(), w.approachX(), w.waypoints(),
                        w.inlineExpression(), w.labelX(), w.labelY()));
            }
        }
        swapWires(oldNet, newNet);
        record(new Command() {
            @Override
            public void undo() {
                swapWires(newNet, oldNet);
            }

            @Override
            public void redo() {
                swapWires(oldNet, newNet);
            }
        });
        notifyListeners();
        return null;
    }

    // ---- top-level entity ----

    public String setTopEntityName(String name) {
        if (!isIdentifier(name)) {
            return "invalid VHDL identifier: " + name;
        }
        if (name.equals(topEntity)) {
            return null;
        }
        if (entityCatalog.keySet().stream().anyMatch(name::equalsIgnoreCase)) {
            return "name already used by a catalogue entity: " + name;
        }
        Snapshot before = capture();
        topEntity = name;
        commit(before);
        return null;
    }

    public String addTopPort(Port port) {
        String error = validatePort(port);
        if (error != null) {
            return error;
        }
        if (nameInUse(port.name(), null, null, null)) {
            return "name already in use: " + port.name();
        }
        Snapshot before = capture();
        topPorts.add(port);
        commit(before);
        return null;
    }

    public String removeTopPort(String name) {
        Port existing = findTopPort(name);
        if (existing == null) {
            return "port not found";
        }
        Snapshot before = capture();
        topPorts.remove(existing);
        wires.removeIf(w -> touchesTopPort(w, name));
        commit(before);
        return null;
    }

    public String updateTopPort(String oldName, Port updated) {
        Port old = findTopPort(oldName);
        if (old == null) {
            return "port not found";
        }
        String error = validatePort(updated);
        if (error != null) {
            return error;
        }
        if (nameInUse(updated.name(), null, oldName, null)) {
            return "name already in use: " + updated.name();
        }
        Snapshot before = capture();
        topPorts.set(topPorts.indexOf(old), updated);
        List<Wire> rewritten = new ArrayList<>();
        for (Wire w : wires) {
            boolean src = TOP_ID.equals(w.fromBlock()) && w.fromPort().equals(oldName);
            boolean dst = TOP_ID.equals(w.toBlock()) && w.toPort().equals(oldName);
            if (!src && !dst) {
                rewritten.add(w);
            } else if (old.dir() == updated.dir()) {
                rewritten.add(new Wire(w.id(), w.fromBlock(), src ? updated.name() : w.fromPort(), w.toBlock(),
                        dst ? updated.name() : w.toPort(), src ? updated.name() : w.signalName(),
                        w.bendX(), w.bendY(), w.approachX(), w.waypoints(), w.inlineExpression(),
                        w.labelX(), w.labelY()));
            }
        }
        wires.clear();
        wires.addAll(rewritten);
        commit(before);
        return null;
    }

    public String addTopGeneric(String declaration) {
        String error = validateTopGeneric(declaration, -1);
        if (error != null) {
            return error;
        }
        Snapshot before = capture();
        topGenerics.add(declaration.trim());
        commit(before);
        return null;
    }

    public String updateTopGeneric(int index, String declaration) {
        if (index < 0 || index >= topGenerics.size()) {
            return "generic not found";
        }
        String error = validateTopGeneric(declaration, index);
        if (error != null) {
            return error;
        }
        String normalized = declaration.trim();
        if (topGenerics.get(index).equals(normalized)) {
            return null;
        }
        Snapshot before = capture();
        topGenerics.set(index, normalized);
        commit(before);
        return null;
    }

    public String removeTopGeneric(int index) {
        if (index < 0 || index >= topGenerics.size()) {
            return "generic not found";
        }
        Snapshot before = capture();
        topGenerics.remove(index);
        commit(before);
        return null;
    }

    public String addLibrary(String name) {
        String normalized = name == null ? "" : name.trim();
        String error = validateLibrary(normalized);
        if (error != null) {
            return error;
        }
        if (containsIgnoreCase(libraries, normalized)) {
            return "library already listed: " + normalized;
        }
        Snapshot before = capture();
        libraries.add(normalized);
        commit(before);
        return null;
    }

    public String updateLibrary(int index, String name) {
        if (index >= 0 && index < libraries.size()) {
            String normalized = name == null ? "" : name.trim();
            if (!libraries.get(index).equalsIgnoreCase(normalized) && libraryIsUsed(libraries.get(index))) {
                return "package clauses still use library " + libraries.get(index);
            }
        }
        return updateContextItem(libraries, index, name, false);
    }

    public String removeLibrary(int index) {
        if (index >= 0 && index < libraries.size() && libraryIsUsed(libraries.get(index))) {
            return "package clauses still use library " + libraries.get(index);
        }
        return removeContextItem(libraries, index, "library");
    }

    public String addLibraryUse(String selectedName) {
        String normalized = selectedName == null ? "" : selectedName.trim();
        String error = validateLibraryUse(normalized);
        if (error != null) {
            return error;
        }
        if (!containsIgnoreCase(libraries, libraryPrefix(normalized))) {
            return "add library " + libraryPrefix(normalized) + " before adding this package clause";
        }
        if (containsIgnoreCase(libraryUses, normalized)) {
            return "package clause already listed: " + normalized;
        }
        Snapshot before = capture();
        libraryUses.add(normalized);
        commit(before);
        return null;
    }

    public String updateLibraryUse(int index, String selectedName) {
        return updateContextItem(libraryUses, index, selectedName, true);
    }

    public String removeLibraryUse(int index) {
        return removeContextItem(libraryUses, index, "package clause");
    }

    // ---- entity catalogue ----

    /** Creates a catalogue entity (oldName == null) or replaces/renames an existing one. */
    public String defineEntity(String oldName, EntityDef def) {
        if (StandardLibrary.contains(oldName)) {
            return "standard library entities cannot be edited";
        }
        if (!isIdentifier(def.name())) {
            return "invalid VHDL identifier: " + def.name();
        }
        if (def.name().equalsIgnoreCase(topEntity)) {
            return "name matches the top-level entity";
        }
        for (String existing : entityCatalog.keySet()) {
            if (existing.equalsIgnoreCase(def.name()) && !existing.equals(oldName)) {
                return "entity already exists: " + existing;
            }
        }
        if (oldName != null && !entityCatalog.containsKey(oldName)) {
            return "entity not found: " + oldName;
        }
        List<String> seen = new ArrayList<>();
        for (Port p : def.ports()) {
            String error = validatePort(p);
            if (error != null) {
                return error;
            }
            if (seen.contains(p.name().toLowerCase())) {
                return "duplicate port name: " + p.name();
            }
            seen.add(p.name().toLowerCase());
        }
        for (String generic : def.generics()) {
            if (!isIdentifier(EntityDef.genericName(generic))) {
                return "invalid generic declaration: " + generic;
            }
        }

        Snapshot before = capture();
        Map<String, EntityDef> rebuilt = new LinkedHashMap<>();
        if (oldName == null) {
            rebuilt.putAll(entityCatalog);
            rebuilt.put(def.name(), def);
        } else {
            for (Map.Entry<String, EntityDef> e : entityCatalog.entrySet()) {
                if (e.getKey().equals(oldName)) {
                    rebuilt.put(def.name(), def);
                } else {
                    rebuilt.put(e.getKey(), e.getValue());
                }
            }
            for (Block b : blocks) {
                if (b.getEntityName().equals(oldName)) {
                    b.setEntityName(def.name());
                }
            }
        }
        entityCatalog.clear();
        entityCatalog.putAll(rebuilt);
        wires.removeIf(w -> {
            Port from = endpointPort(w.fromBlock(), w.fromPort());
            Port to = endpointPort(w.toBlock(), w.toPort());
            return from == null || to == null || from.dir() != Direction.OUT || to.dir() != Direction.IN;
        });
        commit(before);
        return null;
    }

    public String removeEntity(String name) {
        if (StandardLibrary.contains(name)) {
            return "standard library entities cannot be removed";
        }
        if (!entityCatalog.containsKey(name)) {
            return "entity not found: " + name;
        }
        for (Block b : blocks) {
            if (b.getEntityName().equals(name)) {
                return "entity is used by block " + b.getInstanceLabel();
            }
        }
        Snapshot before = capture();
        entityCatalog.remove(name);
        commit(before);
        return null;
    }

    // ---- history / state ----

    public void undo() {
        if (undoStack.isEmpty()) {
            return;
        }
        Command command = undoStack.remove(undoStack.size() - 1);
        command.undo();
        redoStack.add(command);
        notifyListeners();
    }

    public void redo() {
        if (redoStack.isEmpty()) {
            return;
        }
        Command command = redoStack.remove(redoStack.size() - 1);
        command.redo();
        undoStack.add(command);
        notifyListeners();
    }

    public void clearUndoHistory() {
        undoStack.clear();
        redoStack.clear();
    }

    public void replaceState(List<Block> newBlocks, List<Wire> newWires, Map<String, EntityDef> catalog) {
        blocks.clear();
        wires.clear();
        blocksById.clear();
        if (newBlocks != null) {
            for (Block block : newBlocks) {
                if (block != null) {
                    Block copy = block.copy();
                    blocks.add(copy);
                    blocksById.put(copy.getId(), copy);
                }
            }
        }
        if (newWires != null) {
            wires.addAll(newWires);
        }
        if (catalog != null) {
            replaceEntityCatalog(catalog);
        }
        clearUndoHistory();
        notifyListeners();
    }

    public void loadFrom(DiagramModel other) {
        topEntity = other.topEntity;
        setTopPorts(new ArrayList<>(other.topPorts));
        setTopGenerics(new ArrayList<>(other.topGenerics));
        setLibraries(new ArrayList<>(other.libraries));
        setLibraryUses(new ArrayList<>(other.libraryUses));
        replaceState(other.blocks, other.wires, other.entityCatalog);
    }

    // ---- internals ----

    /** State touched by top-level and catalogue edits; blocks are kept by identity. */
    private record Snapshot(String top, List<Port> topPorts, List<String> topGenerics,
                            List<String> libraries, List<String> libraryUses, List<Wire> wires,
                            Map<String, EntityDef> catalog, Map<Block, String> entityNames) {
    }

    private Snapshot capture() {
        Map<Block, String> names = new LinkedHashMap<>();
        for (Block b : blocks) {
            names.put(b, b.getEntityName());
        }
        return new Snapshot(topEntity, new ArrayList<>(topPorts), new ArrayList<>(topGenerics),
                new ArrayList<>(libraries), new ArrayList<>(libraryUses), new ArrayList<>(wires),
                new LinkedHashMap<>(entityCatalog), names);
    }

    private void restore(Snapshot s) {
        topEntity = s.top();
        topPorts.clear();
        topPorts.addAll(s.topPorts());
        topGenerics.clear();
        topGenerics.addAll(s.topGenerics());
        libraries.clear();
        libraries.addAll(s.libraries());
        libraryUses.clear();
        libraryUses.addAll(s.libraryUses());
        wires.clear();
        wires.addAll(s.wires());
        entityCatalog.clear();
        entityCatalog.putAll(s.catalog());
        s.entityNames().forEach(Block::setEntityName);
    }

    private void commit(Snapshot before) {
        Snapshot after = capture();
        record(new Command() {
            @Override
            public void undo() {
                restore(before);
            }

            @Override
            public void redo() {
                restore(after);
            }
        });
        notifyListeners();
    }

    private void record(Command command) {
        undoStack.add(command);
        redoStack.clear();
    }

    private void notifyListeners() {
        for (ChangeListener listener : new ArrayList<>(listeners)) {
            listener.modelChanged();
        }
    }

    private void removeBlockInternal(String blockId) {
        blocksById.remove(blockId);
        blocks.removeIf(b -> b.getId().equals(blockId));
        wires.removeIf(w -> w.fromBlock().equals(blockId) || w.toBlock().equals(blockId));
    }

    private void applyBlockPositions(Map<String, Point> positions) {
        for (Map.Entry<String, Point> entry : positions.entrySet()) {
            Block block = blocksById.get(entry.getKey());
            Point position = entry.getValue();
            if (block != null) {
                block.moveTo(position.x, position.y);
            }
        }
    }

    private void applyWireStates(Map<String, Wire> states) {
        if (states.isEmpty()) {
            return;
        }
        for (int i = 0; i < wires.size(); i++) {
            Wire replacement = states.get(wires.get(i).id());
            if (replacement != null) {
                wires.set(i, replacement);
            }
        }
    }

    private void applyBlocksAndWires(List<Block> updatedBlocks, List<Wire> updatedWires) {
        blocks.clear();
        blocks.addAll(updatedBlocks);
        blocksById.clear();
        for (Block block : blocks) {
            blocksById.put(block.getId(), block);
        }
        wires.clear();
        wires.addAll(updatedWires);
    }

    private void applyBlockProps(Block block, String label, Map<String, String> generics) {
        block.setInstanceLabel(label);
        block.getGenerics().clear();
        block.getGenerics().putAll(generics);
    }

    private void swapWires(List<Wire> from, List<Wire> to) {
        for (int i = 0; i < from.size(); i++) {
            int idx = wires.indexOf(from.get(i));
            if (idx >= 0) {
                wires.set(idx, to.get(i));
            }
        }
    }

    private static boolean sameNet(Wire a, Wire b) {
        return a.fromBlock().equals(b.fromBlock()) && a.fromPort().equals(b.fromPort());
    }

    private static boolean touchesTopPort(Wire w, String name) {
        return (TOP_ID.equals(w.fromBlock()) && w.fromPort().equals(name))
                || (TOP_ID.equals(w.toBlock()) && w.toPort().equals(name));
    }

    private String validatePort(Port port) {
        if (!isIdentifier(port.name())) {
            return "invalid VHDL identifier: " + port.name();
        }
        if (port.type() == null || port.type().isBlank()) {
            return "port " + port.name() + " needs a type";
        }
        return null;
    }

    private String updateContextItem(List<String> items, int index, String value, boolean useClause) {
        if (index < 0 || index >= items.size()) {
            return useClause ? "package clause not found" : "library not found";
        }
        String normalized = value == null ? "" : value.trim();
        String error = useClause ? validateLibraryUse(normalized) : validateLibrary(normalized);
        if (error != null) {
            return error;
        }
        if (useClause && !containsIgnoreCase(libraries, libraryPrefix(normalized))) {
            return "add library " + libraryPrefix(normalized) + " before adding this package clause";
        }
        for (int i = 0; i < items.size(); i++) {
            if (i != index && items.get(i).equalsIgnoreCase(normalized)) {
                return useClause ? "package clause already listed: " + normalized
                        : "library already listed: " + normalized;
            }
        }
        if (items.get(index).equals(normalized)) {
            return null;
        }
        Snapshot before = capture();
        items.set(index, normalized);
        commit(before);
        return null;
    }

    private String removeContextItem(List<String> items, int index, String kind) {
        if (index < 0 || index >= items.size()) {
            return kind + " not found";
        }
        Snapshot before = capture();
        items.remove(index);
        commit(before);
        return null;
    }

    private String validateLibrary(String name) {
        return isIdentifier(name) ? null : "library name must be a VHDL identifier";
    }

    private String validateLibraryUse(String clause) {
        if (clause.matches("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")) {
            return null;
        }
        return "package clause must be a selected name, for example ieee.std_logic_1164.all";
    }

    private String libraryPrefix(String selectedName) {
        int dot = selectedName.indexOf('.');
        return dot < 0 ? selectedName : selectedName.substring(0, dot);
    }

    private boolean libraryIsUsed(String library) {
        return libraryUses.stream().anyMatch(use -> library.equalsIgnoreCase(libraryPrefix(use)));
    }

    private boolean containsIgnoreCase(List<String> items, String value) {
        return items.stream().anyMatch(item -> item.equalsIgnoreCase(value));
    }

    private String validateTopGeneric(String declaration, int exceptIndex) {
        if (declaration == null || declaration.isBlank() || declaration.contains(";")
                || declaration.contains("\n") || declaration.contains("\r")) {
            return "enter one generic declaration, for example WIDTH : integer := 8";
        }
        String name = EntityDef.genericName(declaration);
        String type = EntityDef.genericType(declaration);
        if (!isIdentifier(name) || type.isBlank()) {
            return "generic must include a valid name and type, for example WIDTH : integer := 8";
        }
        for (Port port : topPorts) {
            if (port.name().equalsIgnoreCase(name)) {
                return "generic name conflicts with top-level port: " + port.name();
            }
        }
        for (Block block : blocks) {
            if (block.getInstanceLabel().equalsIgnoreCase(name)) {
                return "generic name conflicts with block instance: " + block.getInstanceLabel();
            }
        }
        for (Wire wire : wires) {
            if (!TOP_ID.equals(wire.fromBlock()) && wire.signalName().equalsIgnoreCase(name)) {
                return "generic name conflicts with signal: " + wire.signalName();
            }
        }
        for (int i = 0; i < topGenerics.size(); i++) {
            if (i != exceptIndex && EntityDef.genericName(topGenerics.get(i)).equalsIgnoreCase(name)) {
                return "duplicate top-level generic: " + name;
            }
        }
        return null;
    }

    private Port findTopPort(String name) {
        for (Port p : topPorts) {
            if (p.name().equals(name)) {
                return p;
            }
        }
        return null;
    }

    private Port findPort(EntityDef entityDef, String portName) {
        for (Port port : entityDef.ports()) {
            if (port.name().equals(portName)) {
                return port;
            }
        }
        return null;
    }

    private Wire findWire(String wireId) {
        for (Wire wire : wires) {
            if (wire.id().equals(wireId)) {
                return wire;
            }
        }
        return null;
    }

    /** VHDL is case-insensitive, so all name comparisons ignore case. */
    private boolean nameInUse(String name, Block exceptBlock, String exceptTopPort, Wire exceptNetOf) {
        for (Block b : blocks) {
            if (b != exceptBlock && name.equalsIgnoreCase(b.getInstanceLabel())) {
                return true;
            }
        }
        for (Port p : topPorts) {
            if ((exceptTopPort == null || !p.name().equalsIgnoreCase(exceptTopPort))
                    && name.equalsIgnoreCase(p.name())) {
                return true;
            }
        }
        for (String generic : topGenerics) {
            if (name.equalsIgnoreCase(EntityDef.genericName(generic))) {
                return true;
            }
        }
        for (Wire w : wires) {
            if (TOP_ID.equals(w.fromBlock()) || (exceptNetOf != null && sameNet(w, exceptNetOf))) {
                continue;
            }
            if (name.equalsIgnoreCase(w.signalName())) {
                return true;
            }
        }
        return false;
    }

    private String ensureUniqueSignalName(String signalName) {
        String candidate = signalName;
        int suffix = 2;
        while (isNameInUse(candidate)) {
            candidate = signalName + "_" + suffix;
            suffix++;
        }
        return candidate;
    }

    private String normalizeIdentifier(String value) {
        if (value == null || value.isBlank()) {
            return "wire";
        }
        String cleaned = value.replaceAll("[^A-Za-z0-9_]", "_");
        return Character.isDigit(cleaned.charAt(0)) ? "n_" + cleaned : cleaned;
    }

    private void initDemoCatalogue() {
        entityCatalog.put("reg8", new EntityDef("reg8", List.of(
                new Port("clk", Direction.IN, "std_logic"),
                new Port("rst", Direction.IN, "std_logic"),
                new Port("ld", Direction.IN, "std_logic"),
                new Port("d", Direction.IN, "std_logic_vector(7 downto 0)"),
                new Port("q", Direction.OUT, "std_logic_vector(7 downto 0)")
        ), List.of()));
        entityCatalog.put("alu8", new EntityDef("alu8", List.of(
                new Port("a", Direction.IN, "std_logic_vector(7 downto 0)"),
                new Port("b", Direction.IN, "std_logic_vector(7 downto 0)"),
                new Port("op", Direction.IN, "std_logic_vector(1 downto 0)"),
                new Port("sum", Direction.OUT, "std_logic_vector(7 downto 0)"),
                new Port("cout", Direction.OUT, "std_logic"),
                new Port("zero", Direction.OUT, "std_logic")
        ), List.of("WIDTH : integer := 8")));
        entityCatalog.put("mux2", new EntityDef("mux2", List.of(
                new Port("sel", Direction.IN, "std_logic"),
                new Port("a", Direction.IN, "std_logic_vector(7 downto 0)"),
                new Port("b", Direction.IN, "std_logic_vector(7 downto 0)"),
                new Port("y", Direction.OUT, "std_logic_vector(7 downto 0)")
        ), List.of()));
        entityCatalog.putAll(StandardLibrary.definitions());
    }

    private void replaceEntityCatalog(Map<String, EntityDef> catalog) {
        entityCatalog.clear();
        if (catalog != null) {
            entityCatalog.putAll(catalog);
        }
        for (EntityDef standardEntity : StandardLibrary.definitions().values()) {
            entityCatalog.keySet().removeIf(name -> name.equalsIgnoreCase(standardEntity.name()));
            entityCatalog.put(standardEntity.name(), standardEntity);
        }
    }
}
