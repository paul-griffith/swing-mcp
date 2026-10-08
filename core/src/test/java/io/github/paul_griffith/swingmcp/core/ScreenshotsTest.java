package io.github.paul_griffith.swingmcp.core;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Arrays;
import javax.imageio.ImageIO;
import javax.swing.JPanel;
import org.junit.jupiter.api.Test;

/**
 * Headless-safe unit tests for {@link Screenshots}. Off-screen painting of a sized (but never
 * shown) component into a {@link BufferedImage} works without a display, so these exercise the
 * sizing, caller-scale, region-crop, and PNG-encoding logic headlessly. On-screen capture of the
 * real demo UI is covered by the headed {@code IntrospectionIntegrationTest}.
 */
class ScreenshotsTest {

  private static JPanel sizedPanel() {
    JPanel panel = new JPanel();
    panel.setBackground(Color.WHITE);
    panel.setSize(100, 50);
    return panel;
  }

  @Test
  void captureMatchesComponentSizeAtUnitScale() {
    BufferedImage image = Screenshots.capture(sizedPanel());
    assertEquals(100, image.getWidth());
    assertEquals(50, image.getHeight());
  }

  @Test
  void callerScaleMultipliesPixelDimensions() {
    BufferedImage image = Screenshots.capture(sizedPanel(), 2.0);
    assertEquals(200, image.getWidth());
    assertEquals(100, image.getHeight());
  }

  @Test
  void regionCropsToTheRequestedRectangle() {
    BufferedImage image =
        Screenshots.capture(
            sizedPanel(), 1.0, new Rectangle(10, 10, 40, 20), Screenshots.DEFAULT_TIMEOUT);
    assertEquals(40, image.getWidth());
    assertEquals(20, image.getHeight());
  }

  @Test
  void toPngBytesEmitsPngSignature() {
    byte[] png = Screenshots.toPngBytes(new BufferedImage(4, 4, BufferedImage.TYPE_INT_ARGB));
    // PNG magic number: 89 50 4E 47 0D 0A 1A 0A
    byte[] signature = {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A};
    assertArrayEquals(signature, Arrays.copyOf(png, signature.length));
    assertTrue(png.length > signature.length);
  }

  @Test
  void pngBytesDecodeBackToTheSameDimensions() throws IOException {
    byte[] png = Screenshots.capturePng(sizedPanel(), 1.0, null, Screenshots.DEFAULT_TIMEOUT);
    BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(png));
    assertEquals(100, decoded.getWidth());
    assertEquals(50, decoded.getHeight());
  }

  @Test
  void zeroSizeComponentThrowsInsteadOfProducingABlankImage() {
    // Finding #4: an unrealized/zero-size component used to yield a useless 1x1 PNG.
    JPanel unsized = new JPanel(); // never sized or shown -> 0x0
    IllegalArgumentException e =
        org.junit.jupiter.api.Assertions.assertThrows(
            IllegalArgumentException.class, () -> Screenshots.capture(unsized));
    assertTrue(e.getMessage().contains("zero size"), "message should explain the cause: " + e);
  }

  @Test
  void captureWithMetadataReportsLogicalSizeAndEffectiveScale() {
    // Headless: no HiDPI graphics config, so the display scale is 1.0. A caller scale of 2 then
    // doubles the pixel size while the logical size stays the component's logical dimensions.
    Screenshots.Capture capture =
        Screenshots.captureWithMetadata(sizedPanel(), 2.0, null, Screenshots.DEFAULT_TIMEOUT);
    assertEquals(100, capture.logicalWidth());
    assertEquals(50, capture.logicalHeight());
    assertEquals(200, capture.pixelWidth());
    assertEquals(100, capture.pixelHeight());
    assertEquals(2.0, capture.effectiveScale(), 0.0001);
  }
}
