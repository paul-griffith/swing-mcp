package io.github.paul_griffith.swingmcp.core;

import java.awt.Rectangle;

/**
 * Logical (device-independent) rectangle in AWT coordinates.
 *
 * <p>Used both for a component's parent-relative bounds and for on-screen bounds, and for a
 * window's on-screen bounds. Values are in logical pixels — the same units AWT reports from {@link
 * java.awt.Component#getBounds()} — so they are unaffected by HiDPI scaling (that scaling is
 * applied only when rendering screenshots).
 */
public record Bounds(int x, int y, int width, int height) {

  /** Adapts an AWT {@link Rectangle}; returns {@code null} for a {@code null} rectangle. */
  static Bounds of(Rectangle r) {
    return r == null ? null : new Bounds(r.x, r.y, r.width, r.height);
  }
}
