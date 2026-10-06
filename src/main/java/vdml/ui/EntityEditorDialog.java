package vdml.ui;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Window;
import java.util.ArrayList;
import java.util.List;

import javax.swing.BorderFactory;
import javax.swing.DefaultCellEditor;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.table.DefaultTableModel;

import vdml.model.DiagramModel;
import vdml.model.Direction;
import vdml.model.EntityDef;
import vdml.model.Port;

/** Creates or edits a catalogue entity: name, generics and ports. */
public class EntityEditorDialog extends JDialog {
    private final DiagramModel model;
    private final String originalName;
    private final JTextField nameField = new JTextField(20);
    private final DefaultTableModel generics = new DefaultTableModel(new String[] {"Generic declaration"}, 0);
    private final DefaultTableModel ports = new DefaultTableModel(new String[] {"Name", "Direction", "Type"}, 0);
    private final JTable genericTable = new JTable(generics);
    private final JTable portTable = new JTable(ports);

    /** @param existing the entity to edit, or null to create a new one */
    public static void show(Window owner, DiagramModel model, EntityDef existing) {
        new EntityEditorDialog(owner, model, existing).setVisible(true);
    }

    private EntityEditorDialog(Window owner, DiagramModel model, EntityDef existing) {
        super(owner, existing == null ? "New entity" : "Edit entity " + existing.name(), ModalityType.APPLICATION_MODAL);
        this.model = model;
        this.originalName = existing == null ? null : existing.name();
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);

        nameField.setText(existing == null ? "" : existing.name());
        if (existing != null) {
            existing.generics().forEach(g -> generics.addRow(new Object[] {g}));
            existing.ports().forEach(p -> ports.addRow(new Object[] {p.name(), p.dir(), p.type()}));
        }
        portTable.getColumnModel().getColumn(1).setCellEditor(new DefaultCellEditor(new JComboBox<>(Direction.values())));
        portTable.getColumnModel().getColumn(2).setCellEditor(new DefaultCellEditor(UiUtil.typeCombo("std_logic")));
        portTable.getColumnModel().getColumn(0).setPreferredWidth(100);
        portTable.getColumnModel().getColumn(1).setPreferredWidth(70);
        portTable.getColumnModel().getColumn(2).setPreferredWidth(220);

        JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT));
        top.add(new JLabel("Entity name"));
        top.add(nameField);

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
        String error = model.defineEntity(originalName, new EntityDef(name, portList, genericList));
        if (error != null) {
            UiUtil.warn(this, error);
            return;
        }
        dispose();
    }
}
