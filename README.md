# vdml

A Swing editor for constructing structural VHDL designs as block diagrams.

## Build and run

Requires Java 17 or newer and Maven.

```powershell
mvn compile
java -cp target\classes vdml.ui.Main
```

## Using the editor

- The left **Top-level entity** panel edits the entity name and its input/output ports.
  The right **Top-level options** section edits generic declarations, such as
  `WIDTH : integer := 8`, and manages library names and package-use clauses.
  The standard IEEE entries are included by default. These options stay with the
  right-side tools when detached.
  Top-level input pins are drawn at the left edge of the canvas; top-level output
  pins are drawn at the right edge.
- Choose a catalogue entity and click **Add block**. Double-click a block to see
  its input/output connection tables and edit its instance label or generic values.
- Click an output pin, then an input pin to connect them. Top-level inputs act as
  drivers and top-level outputs as sinks. A dashed preview is shown while a source
  is pending; Escape or clicking empty space cancels. Input-first attempts,
  duplicate connections, and already-driven input pins are rejected.
- Keep clicking input pins after the first connection to fan one output out to
  multiple sinks. The connections share one signal; press Escape or click empty
  space when finished.
- Drag an interior horizontal or vertical wire segment to adjust the route.
  Right-click a wire and choose **Add breakpoint here** to insert an orthogonal
  detour; repeat on any segment to add more. Collinear or redundant breakpoints
  merge automatically as routes are edited. Endpoints and signals stay unchanged;
  route positions are saved and can be undone/redone.
- Add or edit catalogue entities using **New entity…** and **Edit entity…**.
- The right **Design tools** pane contains the block catalogue and VHDL actions.
  Click **Detach** to move it into a separate window (which can be moved to
  another monitor); click **Dock** or close that window to return it.
- Drag blocks to move them. Select a block, then drag its bottom-right corner
  handle to resize it; size is saved with the diagram and can be undone/redone.
- Pan with the middle mouse button, or hold Space and drag with the left button.
  The canvas has extra space on all four sides, so you can pan horizontally and
  vertically as well as inspect long input/output labels. Scrollbars are also
  available for navigating the canvas.
- Zoom with the **+** and **-** canvas controls or Ctrl+mouse wheel. **Reset**
  returns to 100%; wheel zoom keeps the point under the cursor in view.
- Select a wire or block and press Delete to remove it. Ctrl+Z/Ctrl+Y undo and
  redo model changes.
- Save/Open use `.dgm.json` sidecars. **Export VHDL files…** writes
  `components_pkg.vhd` and the top-level entity/architecture file.
- **Export PDF…** in the File menu or Design tools pane saves a fitted,
  single-page PDF of the diagram.
- The **Dev** menu runs design validation. Live checks refresh after edits and
  compare model connectivity, JSON round-tripping, and emitted VHDL mappings.
  **Validate now…** also runs GHDL analysis when `ghdl` is available on `PATH`;
  view or save the detailed report from the same menu.
