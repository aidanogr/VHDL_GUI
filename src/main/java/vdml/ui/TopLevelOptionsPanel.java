package vdml.ui;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.util.List;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.table.DefaultTableModel;

import vdml.model.DiagramModel;

/** Top-level generics and library imports, placed with the right-side tools. */
public class TopLevelOptionsPanel extends JPanel {
    private final DiagramModel model;
    private final GenericTable generics = new GenericTable();
    private final LibrarySection libraries = new LibrarySection();

    public TopLevelOptionsPanel(DiagramModel model) {
        super();
        this.model = model;
        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        setBorder(BorderFactory.createTitledBorder("Top-level options"));
        add(generics);
        add(Box.createVerticalStrut(8));
        add(libraries);
        model.addChangeListener(this::refresh);
        refresh();
    }

    private void refresh() {
        generics.reload();
        libraries.reload();
    }

    private class GenericTable extends JPanel {
        private final DefaultTableModel tableModel = new DefaultTableModel(new String[] {"Generic declaration"}, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }
        };
        private final JTable table = new JTable(tableModel);

        GenericTable() {
            super(new BorderLayout(0, 2));
            setBorder(BorderFactory.createTitledBorder("Generics"));
            UiUtil.styleTable(table);
            table.setPreferredScrollableViewportSize(new Dimension(240, 66));
            add(new JScrollPane(table), BorderLayout.CENTER);

            JButton add = new JButton("Add");
            JButton edit = new JButton("Edit");
            JButton remove = new JButton("Remove");
            add.addActionListener(e -> prompt(-1, ""));
            edit.addActionListener(e -> {
                int row = table.getSelectedRow();
                if (row >= 0) {
                    prompt(row, model.getTopGenerics().get(row));
                }
            });
            remove.addActionListener(e -> {
                int row = table.getSelectedRow();
                if (row >= 0) {
                    String error = model.removeTopGeneric(row);
                    if (error != null) {
                        UiUtil.warn(TopLevelOptionsPanel.this, error);
                    }
                }
            });
            JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
            buttons.add(add);
            buttons.add(edit);
            buttons.add(remove);
            add(buttons, BorderLayout.SOUTH);
        }

        void reload() {
            int selected = table.getSelectedRow();
            tableModel.setRowCount(0);
            for (String generic : model.getTopGenerics()) {
                tableModel.addRow(new Object[] {generic});
            }
            restoreSelection(selected);
        }

        private void restoreSelection(int selected) {
            if (selected >= 0 && selected < tableModel.getRowCount()) {
                table.setRowSelectionInterval(selected, selected);
            }
        }

        private void prompt(int index, String initial) {
            String value = initial;
            while (true) {
                String answer = (String) JOptionPane.showInputDialog(TopLevelOptionsPanel.this,
                        "Generic declaration (for example WIDTH : integer := 8):",
                        index < 0 ? "Add top-level generic" : "Edit top-level generic",
                        JOptionPane.PLAIN_MESSAGE, null, null, value);
                if (answer == null) {
                    return;
                }
                String error = index < 0 ? model.addTopGeneric(answer) : model.updateTopGeneric(index, answer);
                if (error == null) {
                    return;
                }
                UiUtil.warn(TopLevelOptionsPanel.this, error);
                value = answer;
            }
        }
    }

    private class LibrarySection extends JPanel {
        private final ContextList names = new ContextList(false);
        private final ContextList uses = new ContextList(true);

        LibrarySection() {
            super();
            setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
            setBorder(BorderFactory.createTitledBorder("Libraries"));
            add(names);
            add(Box.createVerticalStrut(6));
            add(uses);
        }

        void reload() {
            names.reload();
            uses.reload();
        }
    }

    private class ContextList extends JPanel {
        private final boolean useClauses;
        private final DefaultTableModel tableModel = new DefaultTableModel(new String[] {"Name"}, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }
        };
        private final JTable table = new JTable(tableModel);

        ContextList(boolean useClauses) {
            super(new BorderLayout(0, 2));
            this.useClauses = useClauses;
            setBorder(BorderFactory.createTitledBorder(useClauses ? "Package use clauses" : "Library names"));
            UiUtil.styleTable(table);
            table.setPreferredScrollableViewportSize(new Dimension(240, 66));
            add(new JScrollPane(table), BorderLayout.CENTER);

            JButton add = new JButton("Add");
            JButton edit = new JButton("Edit");
            JButton remove = new JButton("Remove");
            add.addActionListener(e -> prompt(-1, ""));
            edit.addActionListener(e -> {
                int row = table.getSelectedRow();
                if (row >= 0) {
                    prompt(row, itemList().get(row));
                }
            });
            remove.addActionListener(e -> {
                int row = table.getSelectedRow();
                if (row >= 0) {
                    String error = useClauses ? model.removeLibraryUse(row) : model.removeLibrary(row);
                    if (error != null) {
                        UiUtil.warn(TopLevelOptionsPanel.this, error);
                    }
                }
            });
            JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
            buttons.add(add);
            buttons.add(edit);
            buttons.add(remove);
            add(buttons, BorderLayout.SOUTH);
        }

        void reload() {
            int selected = table.getSelectedRow();
            tableModel.setRowCount(0);
            for (String item : itemList()) {
                tableModel.addRow(new Object[] {item});
            }
            if (selected >= 0 && selected < tableModel.getRowCount()) {
                table.setRowSelectionInterval(selected, selected);
            }
        }

        private List<String> itemList() {
            return useClauses ? model.getLibraryUses() : model.getLibraries();
        }

        private void prompt(int index, String initial) {
            String value = initial;
            while (true) {
                String description = useClauses
                        ? "Selected package name (example: ieee.std_logic_1164.all):"
                        : "Library name (example: work):";
                String answer = (String) JOptionPane.showInputDialog(TopLevelOptionsPanel.this, description,
                        index < 0 ? "Add " + (useClauses ? "package clause" : "library")
                                : "Edit " + (useClauses ? "package clause" : "library"),
                        JOptionPane.PLAIN_MESSAGE, null, null, value);
                if (answer == null) {
                    return;
                }
                String error = useClauses
                        ? index < 0 ? model.addLibraryUse(answer) : model.updateLibraryUse(index, answer)
                        : index < 0 ? model.addLibrary(answer) : model.updateLibrary(index, answer);
                if (error == null) {
                    return;
                }
                UiUtil.warn(TopLevelOptionsPanel.this, error);
                value = answer;
            }
        }
    }
}
