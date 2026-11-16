package com.overdrive.app.surveillance;

import java.nio.ByteBuffer;

/** Minimal, bounds-checked AVC SPS reader used for diagnostics only. */
final class AvcSpsInfo {
    final int profileIdc;
    final int levelIdc;

    private AvcSpsInfo(int profileIdc, int levelIdc) {
        this.profileIdc = profileIdc;
        this.levelIdc = levelIdc;
    }

    static AvcSpsInfo parse(ByteBuffer csd) {
        if (csd == null) return null;
        ByteBuffer data = csd.duplicate();
        int start = data.position();
        int end = data.limit();
        for (int i = start; i < end; i++) {
            int nal = -1;
            if (i + 3 < end && data.get(i) == 0 && data.get(i + 1) == 0
                    && data.get(i + 2) == 0 && data.get(i + 3) == 1) {
                nal = i + 4;
            } else if (i + 2 < end && data.get(i) == 0 && data.get(i + 1) == 0
                    && data.get(i + 2) == 1) {
                nal = i + 3;
            }
            if (nal >= 0) {
                AvcSpsInfo found = parseNal(data, nal, end);
                if (found != null) return found;
                i = nal - 1;
            }
        }
        // Android codecs may expose the SPS NAL directly without an Annex-B
        // start code. Do not modify the caller's ByteBuffer position.
        return parseNal(data, start, end);
    }

    private static AvcSpsInfo parseNal(ByteBuffer data, int nal, int end) {
        if (nal < 0 || nal + 3 >= end) return null;
        int nalType = data.get(nal) & 0x1f;
        if (nalType != 7) return null;
        return new AvcSpsInfo(data.get(nal + 1) & 0xff, data.get(nal + 3) & 0xff);
    }

    String profileName() {
        switch (profileIdc) {
            case 66: return "Baseline";
            case 77: return "Main";
            case 88: return "Extended";
            case 100: return "High";
            case 110: return "High 10";
            case 122: return "High 4:2:2";
            case 244: return "High 4:4:4";
            default: return "profile_idc=" + profileIdc;
        }
    }

    String levelName() {
        return (levelIdc / 10) + "." + (levelIdc % 10);
    }
}
