package app.service;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;

/** Printable ID card (85.6 x 54 mm at 300 dpi) carrying the signed QR code. */
public final class CardImage {
    private CardImage() { }

    public static BufferedImage render(String header, String name, String line2, String line3, byte[] photo, String payload) {
        int w = 1011, h = 638;
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, w, h);
        g.setColor(new Color(0x8F1D22));
        g.fillRect(0, 0, w, 90);
        g.setColor(Color.WHITE);
        g.setFont(new Font("SansSerif", Font.BOLD, 38));
        g.drawString(header, 34, 60);
        // photo
        g.setColor(new Color(0xE6DADA));
        g.fillRect(34, 130, 250, 320);
        try {
            if (photo != null && photo.length > 0) {
                BufferedImage p = ImageIO.read(new ByteArrayInputStream(photo));
                if (p != null) g.drawImage(p, 34, 130, 250, 320, null);
            }
        } catch (IOException ignored) { }
        g.setColor(Color.BLACK);
        g.setFont(new Font("SansSerif", Font.BOLD, 40));
        g.drawString(fit(name, 22), 34, 505);
        g.setFont(new Font("SansSerif", Font.PLAIN, 32));
        g.drawString(line2, 34, 550);
        g.drawString(line3, 34, 595);
        BufferedImage qr = QrService.image(payload, 360);
        g.drawImage(qr, 610, 150, 360, 360, null);
        g.setFont(new Font("SansSerif", Font.PLAIN, 22));
        g.setColor(Color.DARK_GRAY);
        g.drawString("Signed card – do not lend", 640, 545);
        g.dispose();
        return img;
    }

    private static String fit(String s, int max) { return s.length() <= max ? s : s.substring(0, max - 1) + "…"; }

    public static void save(BufferedImage img, File f) throws IOException { ImageIO.write(img, "png", f); }
}
