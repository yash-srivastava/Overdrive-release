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

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.util.Arrays;

import javax.imageio.ImageIO;

public class QrPngTest {

    @Test
    public void asciiPayloadProducesReadablePngAndRoundTrips() throws Exception {
        String text = "OverDrive QR test: 12345";
        byte[] png = QrPng.render(text, 8);
        byte[] signature = {(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10};
        assertArrayEquals(signature, Arrays.copyOf(png, signature.length));

        BufferedImage image = readImage(png);
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
        int minWidth = readImage(QrPng.render(text, 2)).getWidth();
        int maxWidth = readImage(QrPng.render(text, 16)).getWidth();

        assertEquals(minWidth, readImage(QrPng.render(text, 0)).getWidth());
        assertEquals(minWidth, readImage(QrPng.render(text, -1)).getWidth());
        assertEquals(minWidth * 4, readImage(QrPng.render(text, 8)).getWidth());
        assertEquals(minWidth * 8, maxWidth);
        assertEquals(maxWidth, readImage(QrPng.render(text, 100)).getWidth());
    }

    private static BufferedImage readImage(byte[] png) throws Exception {
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(png));
        assertNotNull("PNG must be readable by the JVM image decoder", image);
        assertTrue(image.getWidth() > 0);
        assertEquals(image.getWidth(), image.getHeight());
        return image;
    }

    private static String decode(BufferedImage image) throws Exception {
        int width = image.getWidth();
        int height = image.getHeight();
        int[] pixels = image.getRGB(0, 0, width, height, null, 0, width);
        RGBLuminanceSource source = new RGBLuminanceSource(width, height, pixels);
        return new QRCodeReader().decode(new BinaryBitmap(new HybridBinarizer(source))).getText();
    }
}
