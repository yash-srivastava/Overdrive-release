#!/usr/bin/env python3
"""Static contract check for CopiedFrameRing's camera-copy completion barrier."""
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[3]
SOURCE = ROOT / 'app/src/main/java/com/overdrive/app/camera/CopiedFrameRing.java'
text = SOURCE.read_text(encoding='utf-8')
if 'import android.opengl.GLES30;' not in text:
    raise AssertionError('GLES30 import is required for the fence implementation')

copy_match = re.search(r'public int copyFrom\(int oesTextureId\) \{(.*?)\n    /\*\*\n     \* Wait until the just-submitted', text, re.S)
if not copy_match:
    raise AssertionError('copyFrom implementation not found')
copy_body = copy_match.group(1)
if 'awaitCopyCompletion();' not in copy_body:
    raise AssertionError('copyFrom must establish the completion barrier')
copy_code = re.sub(r'/\*.*?\*/|//[^\n]*', '', copy_body, flags=re.S)
if 'GLES20.glFinish()' in copy_code:
    raise AssertionError('copyFrom must not unconditionally drain the GL queue')
if copy_body.index('awaitCopyCompletion();') > copy_body.index('slotGenerations[slot]++'):
    raise AssertionError('slot must not be published before the GPU copy completes')

helper_match = re.search(r'private static void awaitCopyCompletion\(\) \{(.*?)\n    \}', text, re.S)
if not helper_match:
    raise AssertionError('awaitCopyCompletion helper not found')
helper = helper_match.group(1)
required = [
    'GLES30.glFenceSync(',
    'GLES30.glClientWaitSync(',
    'GLES30.GL_SYNC_FLUSH_COMMANDS_BIT',
    'GLES30.GL_ALREADY_SIGNALED',
    'GLES30.GL_CONDITION_SATISFIED',
    'GLES30.glDeleteSync(fence)',
    'GLES20.glFinish();',
]
for token in required:
    if token not in helper:
        raise AssertionError('completion helper missing required safety contract: ' + token)
if '50_000_000L' not in helper:
    raise AssertionError('fence wait must be bounded before hard-finish fallback')

print('PASS: fence-scoped camera-copy wait; slot publication follows completion; hard fallback retained')
