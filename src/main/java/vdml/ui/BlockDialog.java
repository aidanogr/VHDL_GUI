package vdml.ui;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridBagLayout;
import java.awt.Window;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.swing.BorderFactory;
import javax.swing.AbstractCellEditor;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.TableCellEditor;

import vdml.model.Block;
import vdml.model.ArchitectureDef;
import vdml.model.DiagramModel;
import vdml.model.Direction;
import vdml.model.EntityDef;
import vdml.model.Port;
import vdml.model.StandardLibrary;
import vdml.model.Wire;

/** Properties of one placed block: label, generics, and what each port is wired to. */
public class BlockDialog extends JDialog {
    private final DiagramModel model;
    private final Block block;
    private final EntityDef def;
    private final Path projectVhdlDirectory;
    private final JTextField label = new JTextField(18);
    private final DefaultTableModel genericRows = new DefaultTableModel(
            new String[] {"Generic", "Type", "Default", "Value / top-level generic"}, 0) {
        @Override
        public boolean isCellEditable(int row, int column) {
            return column == 3;
        }
    };
    private final JTable genericTable = new JTable(genericRows);
    private final JComboBox<String> architectureSelector = new JComboBox<>();
    private final TopLevelGenericCellEditor topLevelGenericEditor = new TopLevelGenericCellEditor();

    public static void show(Window owner, DiagramModel model, Block block) {
        show(owner, model, block, null);
    }

    public static void show(Window owner, DiagramModel model, Block block, Path projectVhdlDirectory) {
        new BlockDialog(owner, model, block, projectVhdlDirectory).setVisible(true);
    }

    private BlockDialog(Window owner, DiagramModel model, Block block, Path projectVhdlDirectory) {
        super(owner, "Block " + block.getInstanceLabel(), ModalityType.APPLICATION_MODAL);
        this.model = model;
        this.block = block;
        this.projectVhdlDirectory = projectVhdlDirectory;
        this.def = model.getEntityCatalog().get(block.getEntityName());
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        UiUtil.styleTable(genericTable);
        genericTable.getColumnModel().getColumn(3).setCellEditor(topLevelGenericEditor);
        genericTable.setToolTipText("Choose a compatible top-level generic or enter a VHDL expression.");
        if (def != null) {
            for (var architecture : def.architectures()) {
                architectureSelector.addItem(architecture.name());
            }
        }
        if (block.getArchitectureName() != null) {
            architectureSelector.setSelectedItem(block.getArchitectureName());
        }
        architectureSelector.setEnabled(architectureSelector.getItemCount() > 0);

        label.setText(block.getInstanceLabel());
        JPanel top = new JPanel(new GridBagLayout());
        top.add(new JLabel("Entity"), UiUtil.cell(0, 0, false));
        top.add(new JLabel(block.getEntityName()), UiUtil.cell(1, 0, false));
        top.add(new JLabel("Instance label"), UiUtil.cell(0, 1, false));
        top.add(label, UiUtil.cell(1, 1, true));
        top.add(new JLabel("Architecture"), UiUtil.cell(0, 2, false));
        top.add(architectureSelector, UiUtil.cell(1, 2, true));
        JButton editArchitecture = new JButton("Edit arch…");
        editArchitecture.setEnabled(def != null && !StandardLibrary.contains(def.name())
                && def.sourceOverride() == null && architectureSelector.getItemCount() > 0);
        editArchitecture.setToolTipText(def != null && def.sourceOverride() != null
                ? "Edit the imported source file and re-import the entity." : null);
        editArchitecture.addActionListener(e -> editSelectedArchitecture());
        top.add(editArchitecture, UiUtil.cell(2, 2, false));
        JButton editEntity = new JButton("Edit entity…");
        editEntity.setEnabled(def != null && def.sourceOverride() == null);
        editEntity.addActionListener(e -> {
            EntityEditorDialog.show(this, model, def, projectVhdlDirectory);
            dispose();
        });
        top.add(editEntity, UiUtil.cell(2, 1, false));

        JPanel center = new JPanel();
        center.setLayout(new javax.swing.BoxLayout(center, javax.swing.BoxLayout.Y_AXIS));
        center.setBorder(BorderFactory.createEmptyBorder(0, 8, 0, 8));
        if (def != null && !def.generics().isEmpty()) {
            for (String formal : def.generics()) {
                String name = EntityDef.genericName(formal);
                genericRows.addRow(new Object[] {name, EntityDef.genericType(formal),
                        EntityDef.genericDefault(formal), block.getGenerics().getOrDefault(name, "")});
            }
            center.add(wrap("Generics (empty uses default; choose a top-level generic to pass it down)",
                    genericTable, 80));
        }
        if (def != null) {
            center.add(wrap("Inputs", portTable(def.inputs()), 130));
            center.add(wrap("Outputs", portTable(def.outputs()), 130));
        }

        JButton ok = new JButton("OK");
        JButton cancel = new JButton("Cancel");
        ok.addActionListener(e -> apply());
        cancel.addActionListener(e -> dispose());
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        buttons.add(ok);
        buttons.add(cancel);

        add(top, BorderLayout.NORTH);
        add(center, BorderLayout.CENTER);
        add(buttons, BorderLayout.SOUTH);
        setSize(580, 560);
        setLocationRelativeTo(owner);
        getRootPane().setDefaultButton(ok);
    }

    private JTable portTable(List<Port> list) {
        DefaultTableModel tableModel = new DefaultTableModel(new String[] {"Port", "Type", "Connected"}, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }
        };
        for (Port port : list) {
            tableModel.addRow(new Object[] {port.name(), port.type(), connections(port)});
        }
        JTable table = new JTable(tableModel);
        UiUtil.styleTable(table);
        table.getColumnModel().getColumn(0).setPreferredWidth(70);
        table.getColumnModel().getColumn(1).setPreferredWidth(190);
        table.getColumnModel().getColumn(2).setPreferredWidth(200);
        return table;
    }

    private String connections(Port port) {
        List<String> parts = new java.util.ArrayList<>();
        for (Wire w : model.getWires()) {
            if (port.dir() == Direction.IN && w.toBlock().equals(block.getId()) && w.toPort().equals(port.name())) {
                parts.add("<- " + model.describeEndpoint(w.fromBlock(), w.fromPort()) + "  [" + w.signalName() + "]");
            } else if (port.dir() == Direction.OUT && w.fromBlock().equals(block.getId())
                    && w.fromPort().equals(port.name())) {
                parts.add("-> " + model.describeEndpoint(w.toBlock(), w.toPort()));
            }
        }
        return parts.isEmpty() ? "-" : String.join(", ", parts);
    }

    private void editSelectedArchitecture() {
        EntityDef currentDefinition = model.getEntityCatalog().get(block.getEntityName());
        if (currentDefinition == null || StandardLibrary.contains(currentDefinition.name())) {
            UiUtil.warn(this, "This entity does not have an editable architecture.");
            return;
        }
        String selectedName = (String) architectureSelector.getSelectedItem();
        ArchitectureDef selected = null;
        for (ArchitectureDef architecture : currentDefinition.architectures()) {
            if (architecture.name().equalsIgnoreCase(selectedName)) {
                selected = architecture;
                break;
            }
        }
        if (selected == null) {
            UiUtil.warn(this, "Select an architecture first.");
            return;
        }
        ArchitectureDef updated = ArchitectureEditorDialog.show(
                this, model, currentDefinition, selected, projectVhdlDirectory);
        if (updated == null) {
            return;
        }
        List<ArchitectureDef> architectures = new java.util.ArrayList<>(currentDefinition.architectures());
        architectures.set(architectures.indexOf(selected), updated);
        String error = model.defineEntity(currentDefinition.name(), new EntityDef(
                currentDefinition.name(), currentDefinition.ports(), currentDefinition.generics(), architectures));
        if (error != null) {
            UiUtil.warn(this, error);
        }
    }

    private JPanel wrap(String title, JTable table, int height) {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBorder(BorderFactory.createTitledBorder(title));
        JScrollPane scroll = new JScrollPane(table);
        scroll.setPreferredSize(new Dimension(520, height));
        panel.add(scroll, BorderLayout.CENTER);
        return panel;
    }

    private class TopLevelGenericCellEditor extends AbstractCellEditor implements TableCellEditor {
        private final JComboBox<String> choices = new JComboBox<>();

        TopLevelGenericCellEditor() {
            choices.setEditable(true);
        }

        @Override
        public Component getTableCellEditorComponent(JTable table, Object value,
                                                      boolean isSelected, int row, int column) {
            String current = value == null ? "" : value.toString();
            String genericType = String.valueOf(genericRows.getValueAt(row, 1)).trim();
            choices.removeAllItems();
            choices.addItem("");
            for (String declaration : model.getTopGenerics()) {
                if (!genericType.isEmpty()
                        && sameType(genericType, EntityDef.genericType(declaration))) {
                    choices.addItem(EntityDef.genericName(declaration));
                }
            }
            if (!containsChoice(current)) {
                choices.addItem(current);
            }
            choices.setSelectedItem(current);
            return choices;
        }

        @Override
        public Object getCellEditorValue() {
            Object selected = choices.getEditor().getItem();
            return selected == null ? "" : selected.toString();
        }

        private boolean containsChoice(String value) {
            for (int i = 0; i < choices.getItemCount(); i++) {
                if (choices.getItemAt(i).equals(value)) {
                    return true;
                }
            }
            return false;
        }

        private boolean sameType(String left, String right) {
            return left.replaceAll("\\s+", "").equalsIgnoreCase(right.replaceAll("\\s+", ""));
        }
    }

    private void apply() {
        UiUtil.commitEdits(genericTable);
        Map<String, String> values = new LinkedHashMap<>();
        for (int i = 0; i < genericRows.getRowCount(); i++) {
            String value = String.valueOf(genericRows.getValueAt(i, 3)).trim();
            if (!value.isEmpty()) {
                values.put((String) genericRows.getValueAt(i, 0), value);
            }
        }
        String error = model.updateBlock(block.getId(), label.getText().trim(), values,
                (String) architectureSelector.getSelectedItem());
        if (error != null) {
            UiUtil.warn(this, error);
            return;
        }
        dispose();
    }
}
