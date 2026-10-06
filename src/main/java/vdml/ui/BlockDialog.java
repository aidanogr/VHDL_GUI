package vdml.ui;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridBagLayout;
import java.awt.Window;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.table.DefaultTableModel;

import vdml.model.Block;
import vdml.model.DiagramModel;
import vdml.model.Direction;
import vdml.model.EntityDef;
import vdml.model.Port;
import vdml.model.Wire;

/** Properties of one placed block: label, generics, and what each port is wired to. */
public class BlockDialog extends JDialog {
    private final DiagramModel model;
    private final Block block;
    private final EntityDef def;
    private final JTextField label = new JTextField(18);
    private final DefaultTableModel genericRows = new DefaultTableModel(
            new String[] {"Generic", "Type", "Default", "Value"}, 0) {
        @Override
        public boolean isCellEditable(int row, int column) {
            return column == 3;
        }
    };
    private final JTable genericTable = new JTable(genericRows);

    public static void show(Window owner, DiagramModel model, Block block) {
        new BlockDialog(owner, model, block).setVisible(true);
    }

    private BlockDialog(Window owner, DiagramModel model, Block block) {
        super(owner, "Block " + block.getInstanceLabel(), ModalityType.APPLICATION_MODAL);
        this.model = model;
        this.block = block;
        this.def = model.getEntityCatalog().get(block.getEntityName());
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);

        label.setText(block.getInstanceLabel());
        JPanel top = new JPanel(new GridBagLayout());
        top.add(new JLabel("Entity"), UiUtil.cell(0, 0, false));
        top.add(new JLabel(block.getEntityName()), UiUtil.cell(1, 0, false));
        top.add(new JLabel("Instance label"), UiUtil.cell(0, 1, false));
        top.add(label, UiUtil.cell(1, 1, true));
        JButton editEntity = new JButton("Edit entity…");
        editEntity.setEnabled(def != null);
        editEntity.addActionListener(e -> {
            EntityEditorDialog.show(this, model, def);
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
            center.add(wrap("Generics (leave Value empty to use the default)", genericTable, 80));
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

    private JPanel wrap(String title, JTable table, int height) {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBorder(BorderFactory.createTitledBorder(title));
        JScrollPane scroll = new JScrollPane(table);
        scroll.setPreferredSize(new Dimension(520, height));
        panel.add(scroll, BorderLayout.CENTER);
        return panel;
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
        String error = model.updateBlock(block.getId(), label.getText().trim(), values);
        if (error != null) {
            UiUtil.warn(this, error);
            return;
        }
        dispose();
    }
}
