package vdml.ui;

import java.awt.Component;
import java.awt.Dimension;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.util.function.Function;

import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JTable;
import javax.swing.JTextField;

import vdml.model.Direction;
import vdml.model.Port;

final class UiUtil {
    static final String[] TYPES = {
        "std_logic", "std_logic_vector(7 downto 0)", "std_logic_vector(15 downto 0)",
        "unsigned(7 downto 0)", "signed(7 downto 0)", "integer", "boolean"
    };

    private UiUtil() {
    }

    static JComboBox<String> typeCombo(String initial) {
        JComboBox<String> combo = new JComboBox<>(TYPES);
        combo.setEditable(true);
        combo.setSelectedItem(initial);
        return combo;
    }

    static void commitEdits(JTable table) {
        if (table.isEditing()) {
            table.getCellEditor().stopCellEditing();
        }
    }

    static void styleTable(JTable table) {
        table.setFillsViewportHeight(true);
        table.setRowHeight(26);
        table.setShowVerticalLines(false);
        table.setIntercellSpacing(new Dimension(8, 2));
        table.getTableHeader().setReorderingAllowed(false);
    }

    static void warn(Component parent, String message) {
        JOptionPane.showMessageDialog(parent, message, "Cannot complete action", JOptionPane.WARNING_MESSAGE);
    }

    static GridBagConstraints cell(int x, int y, boolean fill) {
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = x;
        c.gridy = y;
        c.insets = new Insets(3, 4, 3, 4);
        c.anchor = GridBagConstraints.WEST;
        if (fill) {
            c.fill = GridBagConstraints.HORIZONTAL;
            c.weightx = 1;
        }
        return c;
    }

    /**
     * Asks for a port definition and re-prompts, keeping the entered values, until
     * {@code apply} accepts it (returns null) or the user cancels (returns false).
     */
    static boolean promptPort(Component parent, String title, Port initial, Function<Port, String> apply) {
        JTextField name = new JTextField(initial == null ? "" : initial.name(), 16);
        JComboBox<Direction> dir = new JComboBox<>(Direction.values());
        dir.setSelectedItem(initial == null ? Direction.IN : initial.dir());
        JComboBox<String> type = typeCombo(initial == null ? "std_logic" : initial.type());

        JPanel panel = new JPanel(new GridBagLayout());
        panel.add(new JLabel("Name"), cell(0, 0, false));
        panel.add(name, cell(1, 0, true));
        panel.add(new JLabel("Direction"), cell(0, 1, false));
        panel.add(dir, cell(1, 1, true));
        panel.add(new JLabel("Type"), cell(0, 2, false));
        panel.add(type, cell(1, 2, true));

        while (true) {
            int result = JOptionPane.showConfirmDialog(parent, panel, title, JOptionPane.OK_CANCEL_OPTION,
                    JOptionPane.PLAIN_MESSAGE);
            if (result != JOptionPane.OK_OPTION) {
                return false;
            }
            Port port = new Port(name.getText().trim(), (Direction) dir.getSelectedItem(),
                    String.valueOf(type.getSelectedItem()).trim());
            String error = apply.apply(port);
            if (error == null) {
                return true;
            }
            warn(parent, error);
        }
    }
}
