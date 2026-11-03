package com.overdrive.app.util;

public final class ScratchRoot {
    public static String path(String name) {
        return System.getProperty("java.io.tmpdir") + "/link-manager-dummy-no-override/" + name;
    }
}
