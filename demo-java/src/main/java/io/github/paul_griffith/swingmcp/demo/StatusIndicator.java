package io.github.paul_griffith.swingmcp.demo;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import javax.swing.JComponent;

/**
 * A custom-painted status light: a colored dot with no text, tooltip, or accessible name, so the
 * generic introspection rules have nothing to report for it. The test bed for extension describers
 * (the {@code demo-extension} module teaches swing-mcp to read its {@link #getState() state}).
 * Clicking it toggles between {@code online} and {@code offline}.
 */
public final class StatusIndicator extends JComponent {

  private String state = "online";

  public StatusIndicator() {
    Dimension size = new Dimension(16, 16);
    setPreferredSize(size);
    setMinimumSize(size);
    setMaximumSize(size);
    addMouseListener(
        new MouseAdapter() {
          @Override
          public void mouseClicked(MouseEvent e) {
            setState("online".equals(state) ? "offline" : "online");
          }
        });
  }

  /** The current state: {@code online} or {@code offline}. */
  public String getState() {
    return state;
  }

  /** Sets the state and repaints. Call on the Event Dispatch Thread. */
  public void setState(String state) {
    this.state = state;
    repaint();
  }

  @Override
  protected void paintComponent(Graphics g) {
    Graphics2D g2 = (Graphics2D) g.create();
    try {
      g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
      g2.setColor("online".equals(state) ? new Color(0x2e, 0xa0, 0x43) : Color.GRAY);
      g2.fillOval(2, 2, getWidth() - 4, getHeight() - 4);
    } finally {
      g2.dispose();
    }
  }
}
