package com.overdrive.app.surveillance;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.Test;

/**
 * The AI readback FBO is completely overwritten by a fullscreen quad. A
 * pre-draw glClear is therefore redundant GPU work on every inference sample.
 */
public class GpuDownscalerFullFrameClearContractTest {

    private static String readSource() throws Exception {
        Path root = Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize();
        Path source = root.resolve(
                "app/src/main/java/com/overdrive/app/surveillance/GpuDownscaler.java");
        if (!Files.isRegularFile(source) && root.getParent() != null) {
            source = root.getParent().resolve(
                    "app/src/main/java/com/overdrive/app/surveillance/GpuDownscaler.java");
        }
        return new String(Files.readAllBytes(source), java.nio.charset.StandardCharsets.UTF_8);
    }

    private static String methodBody(String source, String signature) {
        int start = source.indexOf(signature);
        if (start < 0) throw new AssertionError("Missing method: " + signature);
        int open = source.indexOf('{', start);
        if (open < 0) throw new AssertionError("Missing method body: " + signature);
        int depth = 0;
        for (int i = open; i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == '{') depth++;
            else if (c == '}' && --depth == 0) return source.substring(open + 1, i);
        }
        throw new AssertionError("Unclosed method body: " + signature);
    }

    @Test
    public void directAiReadbackDoesNotClearAFullCoverageRenderTarget() throws Exception {
        String source = readSource();
        String method = methodBody(source, "public byte[] readPixelsDirect(int cameraTextureId)");
        assertTrue(method.contains("GLES20.glViewport(0, 0, WIDTH, HEIGHT)"));
        assertTrue(method.contains("GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)"));
        assertFalse("fullscreen AI readback must not clear its target before drawing",
                method.contains("glClear("));
    }

    @Test
    public void directReadbackShaderWritesEveryFragment() throws Exception {
        String source = readSource();
        int shaderStart = source.indexOf("private static String buildFragmentShader(");
        assertTrue("shared readback fragment shader must exist", shaderStart >= 0);
        String shader = source.substring(shaderStart);
        int shaderEnd = shader.indexOf("\n    }\n}", shaderStart);
        assertTrue("shader builder must have a bounded body", shaderEnd >= 0);
        shader = shader.substring(0, shaderEnd);
        assertTrue(shader.contains("\"    gl_FragColor = src;\\n\""));
        assertFalse("shader must not discard fragments", shader.contains("discard"));
    }
}
