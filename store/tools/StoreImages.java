import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.CubicCurve2D;
import java.awt.geom.Ellipse2D;
import java.awt.geom.GeneralPath;
import java.awt.geom.Line2D;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import javax.imageio.ImageIO;

// Builds store/play-icon-512.png (512x512, opaque, full-bleed) for Cycle Route Logger.
// Based on gps_camera/store/tools/StoreImages.java.
// (Comments are ASCII-only so that javac can read this file with any default charset.)
//
// The artwork uses the same coordinate space as res/drawable/ic_launcher_foreground.xml:
// a 108x108 viewport whose safe zone is the circle of radius 33 around (54,54).
//
// Run from the app folder with the JDK bundled with Android Studio:
//
//   & "C:\Program Files\Android\Android Studio\jbr\bin\java.exe" store\tools\StoreImages.java
//       -> store/play-icon-512.png
public final class StoreImages {

    static final Color BG = new Color(0x0E, 0x3B, 0x43);
    static final Color WHITE = new Color(0xFF, 0xFF, 0xFF);
    static final Color TRAIL = new Color(0xFF, 0xB3, 0x00);
    static final Color START = new Color(0x66, 0xBB, 0x6A);
    static final Color END = new Color(0xFF, 0x70, 0x43);

    public static void main(String[] args) throws IOException {
        File dst = new File("store", "play-icon-512.png");
        ImageIO.write(render(512), "png", dst);
        System.out.println("wrote " + dst.getPath());
    }

    // Renders the centre 72x72 of the 108 viewport, matching the adaptive icon after masking.
    static BufferedImage render(int size) {
        BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        g.setColor(BG);
        g.fill(new Rectangle2D.Double(0, 0, size, size));
        double scale = size / 72.0;
        g.translate(-18 * scale, -18 * scale);
        g.scale(scale, scale);
        drawArtwork(g);
        g.dispose();
        return image;
    }

    // Same shapes as res/drawable/ic_launcher_foreground.xml, in viewport units.
    static void drawArtwork(Graphics2D g) {
        // Trail: M33,74 C41,69 47,81 57,77 S71,69 78,71  (S mirrors the previous control point)
        g.setColor(TRAIL);
        g.setStroke(new BasicStroke(3f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.draw(new CubicCurve2D.Double(33, 74, 41, 69, 47, 81, 57, 77));
        g.draw(new CubicCurve2D.Double(57, 77, 67, 73, 71, 69, 78, 71));
        g.setColor(START);
        g.fill(new Ellipse2D.Double(30, 71, 6, 6));
        g.setColor(END);
        g.fill(new Ellipse2D.Double(75, 68, 6, 6));
        // Wheels.
        g.setColor(WHITE);
        g.setStroke(new BasicStroke(3.2f));
        g.draw(new Ellipse2D.Double(30, 48, 20, 20));
        g.draw(new Ellipse2D.Double(58, 48, 20, 20));
        // Frame.
        g.setStroke(new BasicStroke(3f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        GeneralPath frame = new GeneralPath();
        frame.moveTo(40, 58);
        frame.lineTo(52, 58);
        frame.lineTo(48, 44);
        frame.closePath();
        frame.moveTo(48, 44);
        frame.lineTo(64, 45);
        frame.lineTo(52, 58);
        frame.moveTo(64, 45);
        frame.lineTo(68, 58);
        frame.moveTo(64, 45);
        frame.lineTo(62, 40);
        frame.moveTo(59, 40);
        frame.lineTo(66, 40);
        g.draw(frame);
        // Saddle.
        g.setStroke(new BasicStroke(3.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.draw(new Line2D.Double(44, 42.5, 51, 42.5));
    }
}
