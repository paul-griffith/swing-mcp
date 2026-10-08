package io.github.paul_griffith.swingmcp.core;

import java.awt.Component;
import java.awt.Graphics2D;
import java.awt.GraphicsConfiguration;
import java.awt.Rectangle;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import javax.imageio.ImageIO;

/**
 * Renders Swing components to PNG images for the {@code screenshot} tool.
 *
 * <p>Capture uses {@link Component#printAll(java.awt.Graphics)} into an off-screen {@link
 * BufferedImage} rather than {@link java.awt.Robot} screen capture. {@code printAll} (unlike {@code
 * paint}) temporarily disables double buffering, so the output is correct even for occluded or
 * background windows, and it needs no OS screen-recording permission (which {@code Robot} does on
 * macOS). It cannot capture native window decorations (title bars drawn by the OS) — only the
 * Java-painted surface.
 *
 * <p>HiDPI is handled by painting at the display scale derived from the component's {@link
 * GraphicsConfiguration#getDefaultTransform()}, so a Retina window renders at its true pixel
 * resolution. A caller-supplied scale factor multiplies that, and an optional region crops the
 * capture to a sub-rectangle (in the component's logical coordinates).
 *
 * <p>Rendering runs on the Event Dispatch Thread via {@link EdtOps}; the entry points are callable
 * from any thread.
 */
public final class Screenshots {

  /**
   * Default EDT timeout for a capture. Deliberately longer than {@link EdtOps#DEFAULT_TIMEOUT}:
   * rendering a large or complex window via {@code printAll} can legitimately take longer than a
   * bean-property read or a component-tree walk.
   */
  public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(10);

  private Screenshots() {}

  /** Captures the whole component at its native display scale with the default timeout. */
  public static BufferedImage capture(Component component) {
    return capture(component, 1.0, null, DEFAULT_TIMEOUT);
  }

  /** Captures the whole component with an extra caller scale factor. */
  public static BufferedImage capture(Component component, double callerScale) {
    return capture(component, callerScale, null, DEFAULT_TIMEOUT);
  }

  /**
   * A rendered image plus the metadata needed to relate it to the logical (device-independent)
   * coordinates that {@link Introspection} reports for component/window bounds.
   *
   * <p>Component and window {@code bounds} in the tree are in <b>logical pixels</b>; a screenshot
   * is rendered in <b>device pixels</b>. Multiplying a logical coordinate by {@link
   * #effectiveScale()} maps it onto the screenshot, so an LLM can locate a component within the
   * captured image.
   *
   * @param image the rendered image (dimensions are the device-pixel size)
   * @param logicalWidth the captured area's width in logical pixels
   * @param logicalHeight the captured area's height in logical pixels
   * @param displayScale the display's HiDPI scale factor that was applied (1.0 on a non-HiDPI
   *     display)
   */
  public record Capture(
      BufferedImage image, int logicalWidth, int logicalHeight, double displayScale) {

    /** The rendered image's width in device pixels. */
    public int pixelWidth() {
      return image.getWidth();
    }

    /** The rendered image's height in device pixels. */
    public int pixelHeight() {
      return image.getHeight();
    }

    /**
     * Device pixels per logical pixel for this capture ({@code pixelWidth / logicalWidth}). Equals
     * the display scale times any caller scale before downscaling; callers that downscale the image
     * afterwards should recompute this from the final image (see the {@code screenshot} tool).
     */
    public double effectiveScale() {
      return logicalWidth > 0 ? (double) image.getWidth() / logicalWidth : displayScale;
    }
  }

  /**
   * Renders {@code component} and returns the image together with its logical size and the display
   * scale, so a caller can report how logical (tree) bounds map onto the screenshot's device
   * pixels. Runs on the EDT via {@link EdtOps}.
   *
   * @param component the component (often a {@link java.awt.Window}) to render
   * @param callerScale extra scale factor applied on top of the display's HiDPI scale (1.0 = none)
   * @param region sub-rectangle to capture, in logical coordinates, or {@code null} for the whole
   *     component
   * @param timeout maximum time to wait for the EDT
   * @throws EdtOps.EdtTimeoutException if the EDT does not answer within {@code timeout}
   */
  public static Capture captureWithMetadata(
      Component component, double callerScale, Rectangle region, Duration timeout) {
    return EdtOps.call(() -> renderWithMetadataOnEdt(component, callerScale, region), timeout);
  }

  /**
   * Captures at a target <i>total</i> scale relative to logical (component-tree) pixels, rather
   * than as a multiplier on top of the display's HiDPI scale.
   *
   * <p>This is the semantics a caller means by "scale": {@code targetScale = 3} yields an image 3×
   * the component's logical size on every display, instead of 3× the display scale (6× on a Retina
   * panel, which is where 6700-pixel-wide screenshots came from). The HiDPI factor is divided out
   * before capture, so the render still happens at full device resolution when the target scale
   * calls for it.
   *
   * @param component the component (often a {@link java.awt.Window}) to render
   * @param targetScale the desired image-px-per-logical-px, or {@code null} for the display's own
   *     scale (native resolution)
   * @param region sub-rectangle to capture, in logical coordinates, or {@code null} for all of it
   * @param timeout maximum time to wait for the EDT
   * @throws EdtOps.EdtTimeoutException if the EDT does not answer within {@code timeout}
   */
  public static Capture captureAtScale(
      Component component, Double targetScale, Rectangle region, Duration timeout) {
    return EdtOps.call(
        () -> {
          double callerScale = (targetScale == null) ? 1.0 : targetScale / displayScale(component);
          return renderWithMetadataOnEdt(component, callerScale, region);
        },
        timeout);
  }

  /**
   * Renders {@code component} to a {@link BufferedImage}.
   *
   * @param component the component (often a {@link java.awt.Window}) to render
   * @param callerScale extra scale factor applied on top of the display's HiDPI scale (1.0 = none)
   * @param region sub-rectangle to capture, in the component's logical coordinates, or {@code null}
   *     for the whole component
   * @param timeout maximum time to wait for the EDT
   * @return the rendered image (pixel dimensions reflect display scale × {@code callerScale})
   * @throws EdtOps.EdtTimeoutException if the EDT does not answer within {@code timeout}
   */
  public static BufferedImage capture(
      Component component, double callerScale, Rectangle region, Duration timeout) {
    return EdtOps.call(() -> renderOnEdt(component, callerScale, region), timeout);
  }

  /**
   * Convenience: {@link #capture(Component, double, Rectangle, Duration)} then {@link #toPngBytes}.
   */
  public static byte[] capturePng(
      Component component, double callerScale, Rectangle region, Duration timeout) {
    return toPngBytes(capture(component, callerScale, region, timeout));
  }

  /** Encodes an image as PNG bytes suitable for an MCP {@code ImageContent} payload. */
  public static byte[] toPngBytes(BufferedImage image) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    try {
      ImageIO.write(image, "png", out);
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to encode screenshot as PNG", e);
    }
    return out.toByteArray();
  }

  private static BufferedImage renderOnEdt(
      Component component, double callerScale, Rectangle region) {
    return renderWithMetadataOnEdt(component, callerScale, region).image();
  }

  private static Capture renderWithMetadataOnEdt(
      Component component, double callerScale, Rectangle region) {
    Rectangle area =
        (region != null)
            ? region
            : new Rectangle(0, 0, component.getWidth(), component.getHeight());

    if (area.width <= 0 || area.height <= 0) {
      throw new IllegalArgumentException(
          "component has zero size ("
              + area.width
              + "x"
              + area.height
              + ") — is the window shown/packed? Show/pack it (setVisible(true)/pack()) or capture"
              + " a laid-out component before screenshotting");
    }

    double displayScale = displayScale(component);
    double sx = displayScale * callerScale;
    double sy = displayScale * callerScale;

    int pixelWidth = Math.max(1, (int) Math.round(area.width * sx));
    int pixelHeight = Math.max(1, (int) Math.round(area.height * sy));

    BufferedImage image = new BufferedImage(pixelWidth, pixelHeight, BufferedImage.TYPE_INT_ARGB);
    Graphics2D g = image.createGraphics();
    try {
      g.scale(sx, sy);
      // Shift so the requested region's top-left maps to the image origin.
      g.translate(-area.x, -area.y);
      component.printAll(g);
    } finally {
      g.dispose();
    }
    return new Capture(image, area.width, area.height, displayScale);
  }

  /** The display's HiDPI scale from the component's graphics config, or 1.0 when unavailable. */
  private static double displayScale(Component component) {
    GraphicsConfiguration gc = component.getGraphicsConfiguration();
    if (gc == null) {
      return 1.0;
    }
    AffineTransform tx = gc.getDefaultTransform();
    return tx != null ? tx.getScaleX() : 1.0;
  }
}
