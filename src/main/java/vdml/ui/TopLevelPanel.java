package vdml.ui;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.table.DefaultTableModel;

import vdml.model.DiagramModel;
import vdml.model.Direction;
import vdml.model.Port;

/** Left panel for the top-level entity name and ports. */
public class TopLevelPanel extends JPanel {
    private final DiagramModel model;
    private final JTextField nameField = new JTextField();
    private final PortTable inputs = new PortTable(Direction.IN);
    private final PortTable outputs = new PortTable(Direction.OUT);

    public TopLevelPanel(DiagramModel model) {
        super(new BorderLayout());
        this.model = model;
        setPreferredSize(new Dimension(300, 500));
        setBorder(BorderFactory.createTitledBorder("Top-level entity"));

        JPanel nameRow = new JPanel(new BorderLayout(4, 0));
        nameRow.add(new JLabel("Name"), BorderLayout.WEST);
        nameRow.add(nameField, BorderLayout.CENTER);
        JButton rename = new JButton("Set");
        rename.addActionListener(e -> applyName());
        nameField.addActionListener(e -> applyName());
        nameRow.add(rename, BorderLayout.EAST);
        nameRow.setBorder(BorderFactory.createEmptyBorder(2, 2, 6, 2));

        JPanel tables = new JPanel();
        tables.setLayout(new javax.swing.BoxLayout(tables, javax.swing.BoxLayout.Y_AXIS));
        tables.add(inputs);
        tables.add(javax.swing.Box.createVerticalStrut(8));
        tables.add(outputs);

        add(nameRow, BorderLayout.NORTH);
        add(tables, BorderLayout.CENTER);
        add(new JLabel("<html><small>Top-level inputs appear as pins on the left of the canvas, "
                + "outputs on the right.</small></html>"), BorderLayout.SOUTH);

        model.addChangeListener(this::refresh);
        refresh();
    }

    private void applyName() {
        String error = model.setTopEntityName(nameField.getText().trim());
        if (error != null) {
            UiUtil.warn(this, error);
            nameField.setText(model.getTopEntity());
        }
    }

    private void refresh() {
        if (!nameField.getText().equals(model.getTopEntity()) && !nameField.hasFocus()) {
            nameField.setText(model.getTopEntity());
        }
        inputs.reload();
        outputs.reload();
    }

    private class PortTable extends JPanel {
        private final Direction dir;
        private final DefaultTableModel tableModel = new DefaultTableModel(new String[] {"Name", "Type"}, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }
        };
        private final JTable table = new JTable(tableModel);

        PortTable(Direction dir) {
            super(new BorderLayout(0, 2));
            this.dir = dir;
            setBorder(BorderFactory.createTitledBorder(dir == Direction.IN ? "Inputs" : "Outputs"));
            table.setFillsViewportHeight(true);
            table.getColumnModel().getColumn(0).setPreferredWidth(80);
            table.getColumnModel().getColumn(1).setPreferredWidth(170);
            table.addMouseListener(new MouseAdapter() {
                @Override
                public void mouseClicked(MouseEvent e) {
                    if (e.getClickCount() == 2) {
                        edit();
                    }
                }
            });
            add(new JScrollPane(table), BorderLayout.CENTER);

            JButton add = new JButton("Add");
            JButton edit = new JButton("Edit");
            JButton remove = new JButton("Remove");
            add.addActionListener(e -> addPort());
            edit.addActionListener(e -> edit());
            remove.addActionListener(e -> remove());
            JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
            buttons.add(add);
            buttons.add(edit);
            buttons.add(remove);
            add(buttons, BorderLayout.SOUTH);
        }

        void reload() {
            String selected = selectedName();
            tableModel.setRowCount(0);
            for (Port port : model.getTopPorts()) {
                if (port.dir() == dir) {
                    tableModel.addRow(new Object[] {port.name(), port.type()});
                }
            }
            for (int i = 0; i < tableModel.getRowCount(); i++) {
                if (tableModel.getValueAt(i, 0).equals(selected)) {
                    table.setRowSelectionInterval(i, i);
                }
            }
        }

        private String selectedName() {
            int row = table.getSelectedRow();
            return row < 0 || row >= tableModel.getRowCount() ? null : (String) tableModel.getValueAt(row, 0);
        }

        private void addPort() {
            UiUtil.promptPort(TopLevelPanel.this, "Add top-level " + (dir == Direction.IN ? "input" : "output"),
                    new Port("", dir, "std_logic"), model::addTopPort);
        }

        private void edit() {
            String name = selectedName();
            if (name == null) {
                return;
            }
            Port current = model.getTopPorts().stream().filter(port -> port.name().equals(name)).findFirst().orElse(null);
            if (current != null) {
                UiUtil.promptPort(TopLevelPanel.this, "Edit port " + name, current,
                        port -> model.updateTopPort(name, port));
            }
        }

        private void remove() {
            String name = selectedName();
            if (name != null) {
                String error = model.removeTopPort(name);
                if (error != null) {
                    UiUtil.warn(TopLevelPanel.this, error);
                }
            }
        }
    }
}
