package com.overdrive.app.surveillance;

/**
 * Pointer topology validation independent of Android. A missing/out-of-order frame must
 * cancel a gesture, rather than fabricate a new DOWN for the remaining fingers.
 */
public final class ClusterTouchSequence {
    public static final int MAX_POINTERS = 10;
    private int activeIds;

    public boolean accept(int action, int index, int[] ids) {
        if (ids == null || ids.length < 1 || ids.length > MAX_POINTERS
                || index < 0 || index >= ids.length) return false;
        int frameIds = 0;
        for (int id : ids) {
            if (id < 0 || id > 31 || (frameIds & (1 << id)) != 0) return false;
            frameIds |= 1 << id;
        }
        final int changed = 1 << ids[index];
        switch (action) {
            case 0: // DOWN
                if (activeIds != 0 || ids.length != 1 || index != 0) return false;
                activeIds = frameIds;
                return true;
            case 2: // MOVE
                return activeIds != 0 && activeIds == frameIds && index == 0;
            case 5: // POINTER_DOWN
                if (activeIds == 0 || (activeIds & changed) != 0
                        || frameIds != (activeIds | changed)) return false;
                activeIds = frameIds;
                return true;
            case 6: // POINTER_UP (the lifted finger is still present in this frame)
                if (ids.length < 2 || activeIds != frameIds) return false;
                activeIds &= ~changed;
                return true;
            case 1: // UP
                if (ids.length != 1 || activeIds != frameIds || index != 0) return false;
                activeIds = 0;
                return true;
            case 3: // CANCEL
                if (activeIds == 0 || activeIds != frameIds) return false;
                activeIds = 0;
                return true;
            default:
                return false;
        }
    }

    public void reset() { activeIds = 0; }
}
