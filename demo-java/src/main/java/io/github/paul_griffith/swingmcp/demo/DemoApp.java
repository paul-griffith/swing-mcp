package io.github.paul_griffith.swingmcp.demo;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Window;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.table.DefaultTableModel;

/**
 * A small but real Swing application used as the test bed for the swing-mcp agent.
 *
 * <p>It intentionally exercises the component kinds the MVP tools target: a menu bar, a form panel
 * with text fields and a combo box, a {@link JTable}, and a button that opens a modal dialog. Every
 * interesting component is given a stable name via {@link java.awt.Component#setName(String)} so
 * introspection/driving tools can address it deterministically (the plan's component-addressing
 * rule).
 */
public final class DemoApp {

  private DemoApp() {}

  public static void main(String[] args) {
    SwingUtilities.invokeLater(DemoApp::createAndShow);
  }

  private static void createAndShow() {
    JFrame frame = createFrame();
    frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
    frame.setVisible(true);
  }

  /**
   * Builds the demo frame (packed and positioned, but not yet visible and with no close operation
   * set). Exposed so in-JVM introspection tests can show, snapshot, and dispose it without
   * terminating the test process. Must be called on the Event Dispatch Thread.
   *
   * @return the fully constructed, packed {@link JFrame}
   */
  public static JFrame createFrame() {
    JFrame frame = new JFrame("swing-mcp demo");
    frame.setName("mainFrame");
    frame.setJMenuBar(buildMenuBar(frame));

    JPanel north = new JPanel();
    north.setName("northPanel");
    north.setLayout(new BoxLayout(north, BoxLayout.PAGE_AXIS));
    north.add(buildFormPanel());
    north.add(Box.createVerticalStrut(8));
    north.add(buildFilterBar());

    JPanel content = new JPanel(new BorderLayout(12, 12));
    content.setName("contentPanel");
    content.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
    content.add(north, BorderLayout.NORTH);
    content.add(buildTable(), BorderLayout.CENTER);
    content.add(buildNotesArea(), BorderLayout.EAST);
    content.add(buildButtonBar(frame), BorderLayout.SOUTH);

    frame.setContentPane(content);
    frame.setPreferredSize(new Dimension(560, 420));
    frame.pack();
    frame.setLocationRelativeTo(null);
    return frame;
  }

  private static JMenuBar buildMenuBar(JFrame frame) {
    JMenuBar menuBar = new JMenuBar();
    menuBar.setName("menuBar");

    JMenu fileMenu = new JMenu("File");
    fileMenu.setName("fileMenu");

    JMenuItem openItem = new JMenuItem("Open...");
    openItem.setName("openMenuItem");
    openItem.addActionListener(
        e ->
            JOptionPane.showMessageDialog(
                frame, "Open selected", "Open", JOptionPane.INFORMATION_MESSAGE));

    JMenuItem exitItem = new JMenuItem("Exit");
    exitItem.setName("exitMenuItem");
    exitItem.addActionListener(e -> frame.dispose());

    fileMenu.add(openItem);
    fileMenu.addSeparator();
    fileMenu.add(exitItem);

    JMenu helpMenu = new JMenu("Help");
    helpMenu.setName("helpMenu");
    JMenuItem aboutItem = new JMenuItem("About");
    aboutItem.setName("aboutMenuItem");
    aboutItem.addActionListener(
        e ->
            JOptionPane.showMessageDialog(
                frame, "swing-mcp demo", "About", JOptionPane.INFORMATION_MESSAGE));
    helpMenu.add(aboutItem);

    menuBar.add(fileMenu);
    menuBar.add(helpMenu);
    return menuBar;
  }

  private static JPanel buildFormPanel() {
    JPanel form = new JPanel(new GridBagLayout());
    form.setName("formPanel");
    form.setBorder(BorderFactory.createTitledBorder("Person"));

    GridBagConstraints c = new GridBagConstraints();
    c.insets = new Insets(4, 4, 4, 4);
    c.anchor = GridBagConstraints.LINE_START;

    JTextField nameField = new JTextField(20);
    nameField.setName("nameField");
    JTextField emailField = new JTextField(20);
    emailField.setName("emailField");
    JComboBox<String> roleCombo = new JComboBox<>(new String[] {"Engineer", "Designer", "Manager"});
    roleCombo.setName("roleCombo");

    addLabeledRow(form, c, 0, "Name:", "nameLabel", nameField);
    addLabeledRow(form, c, 1, "Email:", "emailLabel", emailField);
    addLabeledRow(form, c, 2, "Role:", "roleLabel", roleCombo);
    return form;
  }

  private static void addLabeledRow(
      JPanel form,
      GridBagConstraints c,
      int row,
      String labelText,
      String labelName,
      java.awt.Component field) {
    JLabel label = new JLabel(labelText);
    label.setName(labelName);
    c.gridx = 0;
    c.gridy = row;
    c.weightx = 0;
    c.fill = GridBagConstraints.NONE;
    form.add(label, c);

    c.gridx = 1;
    c.weightx = 1;
    c.fill = GridBagConstraints.HORIZONTAL;
    form.add(field, c);
  }

  /**
   * A "filter" bar reproducing two patterns common in real-world Swing UIs: a named
   * <em>container</em> that wraps an unnamed text field (so text tools must auto-descend), and an
   * overlay "clear" button painted at a field's edge (so a centered click misses it).
   */
  private static JPanel buildFilterBar() {
    JPanel bar = new JPanel(new GridBagLayout());
    bar.setName("filterBar");
    bar.setBorder(BorderFactory.createTitledBorder("Filter"));

    GridBagConstraints c = new GridBagConstraints();
    c.insets = new Insets(4, 4, 4, 4);
    c.fill = GridBagConstraints.HORIZONTAL;
    c.weightx = 1;
    c.gridx = 0;

    // Named composite: the *panel* carries the "filterField" handle, wrapping an unnamed field —
    // the name lands on a wrapping JPanel, not on the input itself.
    JPanel filterField = new JPanel(new BorderLayout(4, 0));
    filterField.setName("filterField");
    filterField.add(new JLabel("Filter:"), BorderLayout.WEST);
    filterField.add(new JTextField(16), BorderLayout.CENTER); // deliberately unnamed
    c.gridy = 0;
    bar.add(filterField, c);

    // Overlay-clear field: a "✕" hit-region at the right edge, cleared only by a click there.
    ClearableField clearable = new ClearableField();
    clearable.setName("clearableField");
    c.gridy = 1;
    bar.add(clearable, c);
    return bar;
  }

  /**
   * A text field that paints an overlay "✕" clear affordance at its right edge and clears itself
   * only when clicked <em>within that zone</em> — a plain centered synthetic click deliberately
   * misses, as with overlay "clear" buttons in third-party search fields.
   */
  static final class ClearableField extends JTextField {
    private static final int CLEAR_ZONE = 22;

    ClearableField() {
      super(16);
      addMouseListener(
          new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
              if (e.getX() >= getWidth() - CLEAR_ZONE) {
                setText("");
              }
            }
          });
    }

    @Override
    protected void paintComponent(Graphics g) {
      super.paintComponent(g);
      if (!getText().isEmpty()) {
        g.drawString("✕", getWidth() - CLEAR_ZONE + 6, getHeight() / 2 + 5);
      }
    }
  }

  /**
   * A deliberately long document: the test bed for {@code get_text} paging. It is far longer than
   * the free-text cap every snapshot field carries, so reading it back in full is only possible
   * through get_text.
   */
  private static final String LONG_NOTES = buildNotesText();

  private static String buildNotesText() {
    StringBuilder sb = new StringBuilder();
    for (int i = 1; i <= 40; i++) {
      sb.append("Note line ").append(i).append(" of 40 — demo notes text.\n");
    }
    return sb.toString();
  }

  private static JScrollPane buildNotesArea() {
    JTextArea notes = new JTextArea(LONG_NOTES);
    notes.setName("notesArea");
    notes.setLineWrap(true);
    notes.setWrapStyleWord(true);
    notes.setColumns(16);
    JScrollPane scrollPane = new JScrollPane(notes);
    scrollPane.setName("notesAreaScrollPane");
    return scrollPane;
  }

  private static JScrollPane buildTable() {
    DefaultTableModel model = new DefaultTableModel(new Object[] {"Name", "Email", "Role"}, 0);
    model.addRow(new Object[] {"Ada Lovelace", "ada@example.com", "Engineer"});
    model.addRow(new Object[] {"Grace Hopper", "grace@example.com", "Manager"});
    model.addRow(new Object[] {"Alan Turing", "alan@example.com", "Engineer"});

    JTable table = new JTable(model);
    table.setName("peopleTable");
    JScrollPane scrollPane = new JScrollPane(table);
    scrollPane.setName("peopleTableScrollPane");
    return scrollPane;
  }

  private static JPanel buildButtonBar(JFrame frame) {
    JPanel bar = new JPanel();
    bar.setName("buttonBar");
    bar.setLayout(new BoxLayout(bar, BoxLayout.LINE_AXIS));
    bar.add(Box.createHorizontalGlue());

    // A button that pops a JPopupMenu (context-menu style), so tools can be tested against
    // lightweight popups — which are not top-level windows.
    JPopupMenu rowPopupMenu = new JPopupMenu();
    rowPopupMenu.setName("rowPopupMenu");
    JMenuItem duplicateRowItem = new JMenuItem("Duplicate Row");
    duplicateRowItem.setName("duplicateRowItem");
    rowPopupMenu.add(duplicateRowItem);
    JMenuItem deleteRowItem = new JMenuItem("Delete Row");
    deleteRowItem.setName("deleteRowItem");
    rowPopupMenu.add(deleteRowItem);

    JButton openPopupButton = new JButton("Row Actions");
    openPopupButton.setName("openPopupButton");
    openPopupButton.addActionListener(
        e -> rowPopupMenu.show(openPopupButton, 0, openPopupButton.getHeight()));
    bar.add(openPopupButton);
    bar.add(Box.createHorizontalStrut(8));

    // A slow async toggle: each click starts a 600 ms Timer that adds the "slowTaskLabel" to the
    // bar if absent, or removes it if present — the test bed for wait_for's appears/disappears.
    JLabel slowTaskLabel = new JLabel("Task complete");
    slowTaskLabel.setName("slowTaskLabel");
    JButton slowToggleButton = new JButton("Slow Toggle");
    slowToggleButton.setName("slowToggleButton");
    slowToggleButton.addActionListener(
        e -> {
          Timer timer =
              new Timer(
                  600,
                  ev -> {
                    if (slowTaskLabel.getParent() == null) {
                      bar.add(slowTaskLabel, 0);
                    } else {
                      bar.remove(slowTaskLabel);
                    }
                    bar.revalidate();
                    bar.repaint();
                  });
          timer.setRepeats(false);
          timer.start();
        });
    bar.add(slowToggleButton);
    bar.add(Box.createHorizontalStrut(8));

    // A button that opens a modeless dialog (contrast the modal Details... dialog).
    JButton openNotesButton = new JButton("Notes...");
    openNotesButton.setName("openNotesButton");
    openNotesButton.addActionListener(e -> showNotesDialog(frame));
    bar.add(openNotesButton);
    bar.add(Box.createHorizontalStrut(8));

    JButton openDialogButton = new JButton("Details...");
    openDialogButton.setName("openDialogButton");
    openDialogButton.addActionListener(e -> showDetailsDialog(frame));
    bar.add(openDialogButton);
    return bar;
  }

  /** Shows the modeless "Notes" dialog: closable via its button or the Escape key. */
  private static void showNotesDialog(Window owner) {
    JDialog dialog = new JDialog(owner, "Notes", JDialog.ModalityType.MODELESS);
    dialog.setName("notesDialog");

    JPanel panel = new JPanel();
    panel.setName("notesPanel");
    panel.setLayout(new BoxLayout(panel, BoxLayout.PAGE_AXIS));
    panel.setBorder(BorderFactory.createEmptyBorder(16, 16, 16, 16));

    JLabel message = new JLabel("This is a modeless dialog.");
    message.setName("notesMessageLabel");
    panel.add(message);
    panel.add(Box.createVerticalStrut(12));

    JButton closeButton = new JButton("Close");
    closeButton.setName("notesCloseButton");
    closeButton.addActionListener(e -> dialog.dispose());
    panel.add(closeButton);

    // Escape dismisses the dialog (a window-scoped key binding on the root pane).
    dialog
        .getRootPane()
        .registerKeyboardAction(
            e -> dialog.dispose(),
            KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0),
            JComponent.WHEN_IN_FOCUSED_WINDOW);

    dialog.setContentPane(panel);
    dialog.pack();
    dialog.setLocationRelativeTo(owner);
    dialog.setVisible(true);
  }

  private static void showDetailsDialog(Window owner) {
    JDialog dialog = new JDialog(owner, "Details", JDialog.ModalityType.APPLICATION_MODAL);
    dialog.setName("detailsDialog");

    JPanel panel = new JPanel();
    panel.setName("detailsPanel");
    panel.setLayout(new BoxLayout(panel, BoxLayout.PAGE_AXIS));
    panel.setBorder(BorderFactory.createEmptyBorder(16, 16, 16, 16));

    JLabel message = new JLabel("This is a modal dialog.");
    message.setName("detailsMessageLabel");
    panel.add(message);
    panel.add(Box.createVerticalStrut(12));

    JButton closeButton = new JButton("Close");
    closeButton.setName("detailsCloseButton");
    closeButton.addActionListener(e -> dialog.dispose());
    panel.add(closeButton);

    dialog.setContentPane(panel);
    dialog.pack();
    dialog.setLocationRelativeTo(owner);
    dialog.setVisible(true);
  }
}
