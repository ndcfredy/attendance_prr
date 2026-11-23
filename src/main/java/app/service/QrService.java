package app.service;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.EncodeHintType;
import com.google.zxing.LuminanceSource;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.NotFoundException;
import com.google.zxing.Result;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import javafx.embed.swing.SwingFXUtils;
import javafx.scene.image.Image;

import java.awt.image.BufferedImage;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/** QR generation and reading (ZXing). */
public final class QrService {
    private QrService() { }

    public static BufferedImage image(String text, int size) {
        try {
            Map<EncodeHintType, Object> hints = new EnumMap<>(EncodeHintType.class);
            hints.put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M);
            hints.put(EncodeHintType.MARGIN, 1);
            BitMatrix m = new QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, hints);
            BufferedImage img = new BufferedImage(m.getWidth(), m.getHeight(), BufferedImage.TYPE_INT_RGB);
            for (int x = 0; x < m.getWidth(); x++)
                for (int y = 0; y < m.getHeight(); y++) img.setRGB(x, y, m.get(x, y) ? 0x000000 : 0xFFFFFF);
            return img;
        } catch (Exception e) {
            throw new IllegalStateException("QR generation failed: " + e.getMessage(), e);
        }
    }

    public static Image fxImage(String text, int size) {
        return SwingFXUtils.toFXImage(image(text, size), null);
    }

    /** Decodes a QR code from a camera frame; null when none is visible. */
    public static String decode(BufferedImage frame) {
        int w = frame.getWidth(), h = frame.getHeight();
        int[] px = frame.getRGB(0, 0, w, h, null, 0, w);
        byte[] lum = new byte[w * h];
        for (int i = 0; i < px.length; i++) {
            int p = px[i];
            lum[i] = (byte) (((p >> 16 & 0xFF) * 299 + (p >> 8 & 0xFF) * 587 + (p & 0xFF) * 114) / 1000);
        }
        LuminanceSource src = new LuminanceSource(w, h) {
            @Override public byte[] getRow(int y, byte[] row) {
                byte[] r = row == null || row.length < w ? new byte[w] : row;
                System.arraycopy(lum, y * w, r, 0, w);
                return r;
            }
            @Override public byte[] getMatrix() { return lum; }
        };
        MultiFormatReader reader = new MultiFormatReader();
        Map<DecodeHintType, Object> hints = new EnumMap<>(DecodeHintType.class);
        hints.put(DecodeHintType.POSSIBLE_FORMATS, List.of(BarcodeFormat.QR_CODE));
        hints.put(DecodeHintType.TRY_HARDER, Boolean.TRUE);
        try {
            Result r = reader.decode(new BinaryBitmap(new HybridBinarizer(src)), hints);
            return r.getText();
        } catch (NotFoundException e) {
            return null;
        }
    }
}
