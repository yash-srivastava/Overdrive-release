package com.overdrive.app.safekeep.util;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.google.zxing.BinaryBitmap;
import com.google.zxing.RGBLuminanceSource;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.qrcode.QRCodeReader;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.Arrays;

public class QrPngTest {

    @Test
    public void asciiPayloadProducesReadablePngAndRoundTrips() throws Exception {
        String text = "OverDrive QR test: 12345";
        byte[] png = QrPng.render(text, 8);
        byte[] signature = {(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10};
        assertArrayEquals(signature, Arrays.copyOf(png, signature.length));

        Pixels image = readImage(png);
        assertEquals(text, decode(image));
    }

    @Test
    public void utf8PayloadRoundTripsWithoutCharacterLoss() throws Exception {
        String text = "Gr\u00fc\u00dfe \u2014 \u65e5\u672c\u8a9e \u2014 \u0645\u0631\u062d\u0628\u0627 \uD83D\uDE97";
        assertEquals(text, decode(readImage(QrPng.render(text, 8))));
    }

    @Test
    public void moduleScaleIsClampedAndDimensionsGrowProportionally() throws Exception {
        String text = "Scale test";
        int minWidth = readImage(QrPng.render(text, 2)).width;
        int maxWidth = readImage(QrPng.render(text, 16)).width;

        assertEquals(minWidth, readImage(QrPng.render(text, 0)).width);
        assertEquals(minWidth, readImage(QrPng.render(text, -1)).width);
        assertEquals(minWidth * 4, readImage(QrPng.render(text, 8)).width);
        assertEquals(minWidth * 8, maxWidth);
        assertEquals(maxWidth, readImage(QrPng.render(text, 100)).width);
    }

    private static final class Pixels {
        final int width;
        final int height;
        final int[] argb;

        Pixels(int width, int height, int[] argb) {
            this.width = width;
            this.height = height;
            this.argb = argb;
        }
    }

    // Reflection: unit tests compile against Android's java.base, which has no ImageIO.
    private static Pixels readImage(byte[] png) throws Exception {
        Object image = Class.forName("javax.imageio.ImageIO").getMethod("read", InputStream.class)
                .invoke(null, new ByteArrayInputStream(png));
        assertNotNull("PNG must be readable by the JVM image decoder", image);
        Class<?> buffered = Class.forName("java.awt.image.BufferedImage");
        int width = (Integer) buffered.getMethod("getWidth").invoke(image);
        int height = (Integer) buffered.getMethod("getHeight").invoke(image);
        assertTrue(width > 0);
        assertEquals(width, height);
        int[] argb = (int[]) buffered.getMethod("getRGB", int.class, int.class, int.class, int.class,
                int[].class, int.class, int.class).invoke(image, 0, 0, width, height, null, 0, width);
        return new Pixels(width, height, argb);
    }

    private static String decode(Pixels image) throws Exception {
        RGBLuminanceSource source = new RGBLuminanceSource(image.width, image.height, image.argb);
        return new QRCodeReader().decode(new BinaryBitmap(new HybridBinarizer(source))).getText();
    }
}
