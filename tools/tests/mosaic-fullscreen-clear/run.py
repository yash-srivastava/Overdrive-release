#!/usr/bin/env python3
"""Static contract check for the full-screen mosaic render path."""
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[3]
source = ROOT / "app/src/main/java/com/overdrive/app/surveillance/GpuMosaicRecorder.java"
text = source.read_text(encoding="utf-8")

# The main recorder draw must replace the entire target without a preceding clear.
match = re.search(
    r"public void drawFrame\(int cameraTextureId, int windshieldTextureId,.*?\n    }\n",
    text,
    re.S,
)
if not match:
    raise AssertionError("main drawFrame overload not found")
draw = match.group(0)
if "GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)" in draw:
    raise AssertionError("redundant full-target clear remains in drawFrame")
if "GLES20.glEnable(GLES20.GL_SCISSOR_TEST)" in text or "GLES20.glScissor(" in text:
    raise AssertionError("scissor state could prevent full-target replacement")
if "GLES20.glColorMask(" in text:
    raise AssertionError("partial color mask could prevent full-target replacement")
if "GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)" not in draw:
    raise AssertionError("full-screen triangle-strip draw is required")

# Verify the fullscreen quad spans the complete NDC target.
quad = re.search(r"VERTEX_COORDS\s*=\s*\{(.*?)\};", text, re.S)
if not quad:
    raise AssertionError("fullscreen vertex coordinates not found")
values = [float(v[:-1]) for v in re.findall(r"-?\d+(?:\.\d+)?f", quad.group(1))]
if len(values) != 8 or min(values) != -1.0 or max(values) != 1.0:
    raise AssertionError("quad must span the full NDC range [-1, 1]")

# The generated fragment shader must not discard fragments, and must write
# the output both on the windshield early-return path and the common path.
shader = text[text.index("private static String buildFragmentShader"):]
if "discard;" in shader:
    raise AssertionError("shader discard would make the no-clear assumption unsafe")
if '"                gl_FragColor = texture2D(uWindshieldTex, wuv);\\n"' not in shader:
    raise AssertionError("windshield early-return path must write a fragment")
if '"    gl_FragColor = src;\\n"' not in shader:
    raise AssertionError("common shader path must write a fragment")

print("PASS: full-screen mosaic replaces every target pixel; redundant clear removed")
