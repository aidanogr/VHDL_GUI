package vdml.ui;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Window;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import javax.swing.BorderFactory;
import javax.swing.DefaultCellEditor;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.table.DefaultTableModel;

import vdml.model.DiagramModel;
import vdml.model.ArchitectureDef;
import vdml.model.Direction;
import vdml.model.EntityDef;
import vdml.model.Port;

/** Creates or edits a catalogue entity: name, generics and ports. */
public class EntityEditorDialog extends JDialog {
    private final DiagramModel model;
    private final Path projectVhdlDirectory;
    private final String originalName;
    private final JTextField nameField = new JTextField(20);
    private final DefaultTableModel generics = new DefaultTableModel(new String[] {"Generic declaration"}, 0);
    private final DefaultTableModel ports = new DefaultTableModel(new String[] {"Name", "Direction", "Type"}, 0);
    private final JTable genericTable = new JTable(generics);
    private final JTable portTable = new JTable(ports);
    private final List<ArchitectureDef> architectures = new ArrayList<>();
    private final JComboBox<String> architectureSelector = new JComboBox<>();

    /** @param existing the entity to edit, or null to create a new one */
    public static void show(Window owner, DiagramModel model, EntityDef existing) {
        show(owner, model, existing, null);
    }

    public static void show(Window owner, DiagramModel model, EntityDef existing, Path projectVhdlDirectory) {
        new EntityEditorDialog(owner, model, existing, projectVhdlDirectory).setVisible(true);
    }

    private EntityEditorDialog(Window owner, DiagramModel model, EntityDef existing, Path projectVhdlDirectory) {
        super(owner, existing == null ? "New entity" : "Edit entity " + existing.name(), ModalityType.APPLICATION_MODAL);
        this.model = model;
        this.projectVhdlDirectory = projectVhdlDirectory;
        this.originalName = existing == null ? null : existing.name();
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);

        nameField.setText(existing == null ? "" : existing.name());
        if (existing != null) {
            existing.generics().forEach(g -> generics.addRow(new Object[] {g}));
            existing.ports().forEach(p -> ports.addRow(new Object[] {p.name(), p.dir(), p.type()}));
            architectures.addAll(existing.architectures());
        }
        if (architectures.isEmpty()) {
            architectures.add(new ArchitectureDef("rtl", "", ""));
        }
        for (ArchitectureDef architecture : architectures) {
            architectureSelector.addItem(architecture.name());
        }
        UiUtil.styleTable(genericTable);
        UiUtil.styleTable(portTable);
        portTable.getColumnModel().getColumn(1).setCellEditor(new DefaultCellEditor(new JComboBox<>(Direction.values())));
        portTable.getColumnModel().getColumn(2).setCellEditor(new DefaultCellEditor(UiUtil.typeCombo("std_logic")));
        portTable.getColumnModel().getColumn(0).setPreferredWidth(100);
        portTable.getColumnModel().getColumn(1).setPreferredWidth(70);
        portTable.getColumnModel().getColumn(2).setPreferredWidth(220);

        JPanel top = new JPanel(new BorderLayout(0, 8));
        JPanel nameRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        nameRow.add(new JLabel("Entity name"));
        nameRow.add(nameField);
        JPanel architectureRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        architectureRow.add(new JLabel("Architecture"));
        architectureRow.add(architectureSelector);
        JButton editArchitecture = new JButton("Edit architecture…");
        editArchitecture.addActionListener(e -> editSelectedArchitecture());
        architectureRow.add(editArchitecture);
        JButton createArchitecture = new JButton("Create new architecture");
        createArchitecture.addActionListener(e -> createArchitecture());
        architectureRow.add(createArchitecture);
        top.add(nameRow, BorderLayout.NORTH);
        top.add(architectureRow, BorderLayout.SOUTH);

        JPanel center = new JPanel(new BorderLayout(0, 8));
        center.add(tablePanel("Generics (e.g. WIDTH : integer := 8)", genericTable, generics,
                new Object[] {"WIDTH : integer := 8"}, 100), BorderLayout.NORTH);
        center.add(tablePanel("Ports", portTable, ports, new Object[] {"port", Direction.IN, "std_logic"}, 240),
                BorderLayout.CENTER);
        center.setBorder(BorderFactory.createEmptyBorder(0, 8, 0, 8));

        JButton ok = new JButton(originalName == null ? "Create" : "Save");
        JButton cancel = new JButton("Cancel");
        ok.addActionListener(e -> save());
        cancel.addActionListener(e -> dispose());
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        buttons.add(ok);
        buttons.add(cancel);

        add(top, BorderLayout.NORTH);
        add(center, BorderLayout.CENTER);
        add(buttons, BorderLayout.SOUTH);
        setSize(560, 520);
        setLocationRelativeTo(owner);
        getRootPane().setDefaultButton(ok);
    }

    private JPanel tablePanel(String title, JTable table, DefaultTableModel tableModel, Object[] newRow, int height) {
        JPanel panel = new JPanel(new BorderLayout(0, 2));
        panel.setBorder(BorderFactory.createTitledBorder(title));
        JScrollPane scroll = new JScrollPane(table);
        scroll.setPreferredSize(new Dimension(500, height));
        panel.add(scroll, BorderLayout.CENTER);
        JButton add = new JButton("Add");
        JButton remove = new JButton("Remove");
        add.addActionListener(e -> {
            UiUtil.commitEdits(table);
            tableModel.addRow(newRow.clone());
            table.setRowSelectionInterval(tableModel.getRowCount() - 1, tableModel.getRowCount() - 1);
        });
        remove.addActionListener(e -> {
            UiUtil.commitEdits(table);
            int row = table.getSelectedRow();
            if (row >= 0) {
                tableModel.removeRow(row);
            }
        });
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        buttons.add(add);
        buttons.add(remove);
        panel.add(buttons, BorderLayout.SOUTH);
        return panel;
    }

    private void save() {
        EntityDef updated = draftEntity();
        String error = model.defineEntity(originalName, updated);
        if (error != null) {
            UiUtil.warn(this, error);
            return;
        }
        dispose();
    }

    private EntityDef draftEntity() {
        UiUtil.commitEdits(genericTable);
        UiUtil.commitEdits(portTable);
        List<String> genericList = new ArrayList<>();
        for (int i = 0; i < generics.getRowCount(); i++) {
            String text = String.valueOf(generics.getValueAt(i, 0)).trim();
            if (!text.isEmpty()) {
                genericList.add(text);
            }
        }
        List<Port> portList = new ArrayList<>();
        for (int i = 0; i < ports.getRowCount(); i++) {
            portList.add(new Port(String.valueOf(ports.getValueAt(i, 0)).trim(),
                    (Direction) ports.getValueAt(i, 1), String.valueOf(ports.getValueAt(i, 2)).trim()));
        }
        String name = nameField.getText().trim();
        return new EntityDef(name, portList, genericList, new ArrayList<>(architectures));
    }

    private void editSelectedArchitecture() {
        ArchitectureDef selected = selectedArchitecture();
        if (selected == null) {
            UiUtil.warn(this, "Select an architecture first.");
            return;
        }
        ArchitectureDef updated = ArchitectureEditorDialog.show(
                this, model, draftEntity(), selected, projectVhdlDirectory);
        if (updated != null) {
            replaceArchitecture(selected, updated);
        }
    }

    private void createArchitecture() {
        String name = JOptionPane.showInputDialog(this, "Architecture name:", "rtl");
        if (name == null) {
            return;
        }
        name = name.trim();
        if (!DiagramModel.isIdentifier(name)) {
            UiUtil.warn(this, "Invalid VHDL architecture identifier: " + name);
            return;
        }
        for (ArchitectureDef architecture : architectures) {
            if (architecture.name().equalsIgnoreCase(name)) {
                UiUtil.warn(this, "Architecture already exists: " + name);
                return;
            }
        }
        ArchitectureDef created = new ArchitectureDef(name, "", "");
        architectures.add(created);
        architectureSelector.addItem(name);
        architectureSelector.setSelectedItem(name);
        ArchitectureDef updated = ArchitectureEditorDialog.show(
                this, model, draftEntity(), created, projectVhdlDirectory);
        if (updated != null) {
            replaceArchitecture(created, updated);
        }
    }

    private ArchitectureDef selectedArchitecture() {
        String selectedName = (String) architectureSelector.getSelectedItem();
        for (ArchitectureDef architecture : architectures) {
            if (architecture.name().equals(selectedName)) {
                return architecture;
            }
        }
        return null;
    }

    private void replaceArchitecture(ArchitectureDef previous, ArchitectureDef updated) {
        int index = architectures.indexOf(previous);
        if (index < 0) {
            return;
        }
        architectures.set(index, updated);
        if (!previous.name().equals(updated.name())) {
            architectureSelector.removeItem(previous.name());
            architectureSelector.insertItemAt(updated.name(), index);
            architectureSelector.setSelectedItem(updated.name());
        }
    }
}
