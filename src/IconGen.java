import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;

/**
 * Build-time utility: render an app icon (256x256 PNG) by rendering a small
 * board with a few pieces, against the panel background. Not used at runtime.
 *
 * Invoked from the Makefile's appimage target.
 *
 * Usage: java -cp build IconGen <output-path>
 */
public class IconGen {
    public static void main(String[] args) throws Exception {
        String path = args.length > 0 ? args[0] : "icon.png";

        int size = 256;
        BoardPanel p = new BoardPanel();
        p.setSize(size, size);
        p.setBoard(Board.initial());
        p.setShowLabels(false);

        BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        p.paint(g);
        g.dispose();
        File out = new File(path);
        if (out.getParentFile() != null) out.getParentFile().mkdirs();
        ImageIO.write(img, "png", out);
        System.out.println("Wrote " + path);
    }
}
