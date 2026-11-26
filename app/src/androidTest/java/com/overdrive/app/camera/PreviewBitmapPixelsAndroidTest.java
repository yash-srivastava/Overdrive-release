package com.overdrive.app.camera;

import android.graphics.Bitmap;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLSurface;
import android.opengl.GLES20;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.util.Random;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

@RunWith(AndroidJUnit4.class)
public class PreviewBitmapPixelsAndroidTest {
    @Test
    public void topFirstRawCopyAndRowFallbackMatchExpectedArgb() {
        Random random = new Random(723);
        for (int[] size : new int[][] {{1, 1}, {3, 5}, {16, 9}, {321, 241}, {1280, 960}}) {
            int width = size[0], height = size[1];
            int count = width * height;
            ByteBuffer rgba = ByteBuffer.allocateDirect(count * 4);
            int[] expected = new int[count];
            Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            try {
                // Different colors in each row and channel detect swaps, flips,
                // incorrect row stride and stale pixels after repeated copying.
                for (int pass = 0; pass < 2; pass++) {
                    rgba.clear();
                    for (int i = 0; i < count; i++) {
                        int r = random.nextInt(256);
                        int g = random.nextInt(256);
                        int b = random.nextInt(256);
                        rgba.put((byte) r).put((byte) g).put((byte) b).put((byte) 255);
                        expected[i] = 0xff000000 | (r << 16) | (g << 8) | b;
                    }
                    // Both paths consume the top-first frame without modifying its bytes.
                    ByteBuffer fallback = ByteBuffer.allocateDirect(count * 4);
                    rgba.flip();
                    fallback.put(rgba);
                    PreviewBitmapPixels.copyOpaqueRgba(rgba, bitmap);
                    rgba.clear();
                    fallback.clear();
                    assertEquals(fallback, rgba);
                    int[] actual = new int[count];
                    bitmap.getPixels(actual, 0, width, 0, 0, width, height);
                    assertArrayEquals(expected, actual);
                    bitmap.eraseColor(0);
                    PreviewBitmapPixels.copyRows(fallback, bitmap);
                    bitmap.getPixels(actual, 0, width, 0, 0, width, height);
                    assertArrayEquals(expected, actual);
                }
            } finally {
                bitmap.recycle();
            }
        }
    }

    @Test
    public void samplerVertexShaderProducesTopFirstReadbackWithLayoutFlips() {
        final int width = 3, height = 5;
        EGLDisplay display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
        assertTrue(display != EGL14.EGL_NO_DISPLAY);
        int[] version = new int[2];
        assertTrue(EGL14.eglInitialize(display, version, 0, version, 1));
        EGLContext context = EGL14.EGL_NO_CONTEXT;
        EGLSurface surface = EGL14.EGL_NO_SURFACE;
        try {
            EGLConfig[] configs = new EGLConfig[1];
            int[] count = new int[1];
            assertTrue(EGL14.eglChooseConfig(display, new int[] {
                    EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                    EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                    EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8,
                    EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
                    EGL14.EGL_NONE
            }, 0, configs, 0, 1, count, 0));
            assertTrue(count[0] > 0);
            context = EGL14.eglCreateContext(display, configs[0], EGL14.EGL_NO_CONTEXT,
                    new int[] {EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE}, 0);
            assertTrue(context != EGL14.EGL_NO_CONTEXT);
            surface = EGL14.eglCreatePbufferSurface(display, configs[0], new int[] {
                    EGL14.EGL_WIDTH, width, EGL14.EGL_HEIGHT, height, EGL14.EGL_NONE
            }, 0);
            assertTrue(surface != EGL14.EGL_NO_SURFACE);
            assertTrue(EGL14.eglMakeCurrent(display, surface, surface, context));

            // Use the production vertex shader with a synthetic 2D texture,
            // independent of the HAL, mosaic layout and camera hardware.
            int program = GlUtil.createProgram(HighResPreviewSampler.VERTEX_SHADER,
                    "precision highp float;\n"
                    + "varying vec2 vTexCoord; uniform sampler2D uTexture;\n"
                    + "void main() { gl_FragColor = texture2D(uTexture, vTexCoord); }\n");
            assertTrue(program != 0);
            GLES20.glUseProgram(program);
            GLES20.glDisable(GLES20.GL_CULL_FACE);
            GLES20.glDisable(GLES20.GL_DITHER);
            GLES20.glViewport(0, 0, width, height);

            byte[] pixels = new byte[width * height * 4];
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    int i = (y * width + x) * 4;
                    pixels[i] = (byte) (17 + x * 70);
                    pixels[i + 1] = (byte) (23 + y * 41);
                    pixels[i + 2] = (byte) (x + y * width);
                    pixels[i + 3] = (byte) 255;
                }
            }
            ByteBuffer upload = ByteBuffer.allocateDirect(pixels.length);
            upload.put(pixels).flip();
            int[] texture = new int[1];
            GLES20.glGenTextures(1, texture, 0);
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture[0]);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, width, height, 0,
                    GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, upload);
            GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "uTexture"), 0);
            FloatBuffer vertices = GlUtil.createFloatBuffer(new float[] {-1, -1, 1, -1, -1, 1, 1, 1});
            FloatBuffer texCoords = GlUtil.createFloatBuffer(new float[] {0, 1, 1, 1, 0, 0, 1, 0});
            int pos = GLES20.glGetAttribLocation(program, "aPosition");
            int uv = GLES20.glGetAttribLocation(program, "aTexCoord");
            GLES20.glEnableVertexAttribArray(pos);
            GLES20.glEnableVertexAttribArray(uv);
            GLES20.glVertexAttribPointer(pos, 2, GLES20.GL_FLOAT, false, 0, vertices);
            GLES20.glVertexAttribPointer(uv, 2, GLES20.GL_FLOAT, false, 0, texCoords);
            int matrix = GLES20.glGetUniformLocation(program, "uTexMatrix");
            int flip = GLES20.glGetUniformLocation(program, "uApplyInverseYFlip");
            ByteBuffer readback = ByteBuffer.allocateDirect(pixels.length);
            for (int flipY = 0; flipY <= 1; flipY++) {
                for (int flipX = 0; flipX <= 1; flipX++) {
                    GLES20.glUniform1f(flip, flipY);
                    GLES20.glUniformMatrix4fv(matrix, 1, false, new float[] {
                            flipX == 0 ? 1f : -1f, 0, 0, 0,
                            0, 1, 0, 0, 0, 0, 1, 0, flipX, 0, 0, 1
                    }, 0);
                    GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
                    readback.clear();
                    GLES20.glReadPixels(0, 0, width, height, GLES20.GL_RGBA,
                            GLES20.GL_UNSIGNED_BYTE, readback);
                    assertEquals(GLES20.GL_NO_ERROR, GLES20.glGetError());
                    byte[] expected = new byte[pixels.length];
                    for (int y = 0; y < height; y++) {
                        for (int x = 0; x < width; x++) {
                            int sy = flipY == 0 ? y : height - 1 - y;
                            int sx = flipX == 0 ? x : width - 1 - x;
                            System.arraycopy(pixels, (sy * width + sx) * 4,
                                    expected, (y * width + x) * 4, 4);
                        }
                    }
                    byte[] actual = new byte[pixels.length];
                    readback.rewind();
                    readback.get(actual);
                    assertArrayEquals(expected, actual);
                }
            }
            // The private EGL context owns the GL objects and frees them on teardown.
        } finally {
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
            if (surface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, surface);
            if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context);
            EGL14.eglTerminate(display);
        }
    }
}
