package com.overdrive.app.launcher.layout;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/** Immutable, display-independent saved layout. Geometry uses ten-thousandths of the panel. */
public final class LayoutPreset {
    public static final int SCALE = 10_000;
    public static final int MAX_SLOTS = 4;
    public static final String MAP_PACKAGE = "com.overdrive.app";
    private static final Pattern ID = Pattern.compile("[A-Za-z0-9_-]{1,64}");
    private static final Pattern PACKAGE = Pattern.compile(
            "[A-Za-z_][A-Za-z0-9_]*(?:\\.[A-Za-z_][A-Za-z0-9_]*)+");

    public final String id;
    public final String name;
    public final List<Slot> slots;

    public LayoutPreset(String id, String name, List<Slot> slots) {
        if (id == null || !ID.matcher(id).matches()) throw new IllegalArgumentException("Invalid layout ID");
        if (name == null || name.trim().isEmpty() || name.length() > 80)
            throw new IllegalArgumentException("Layout name must contain 1–80 characters");
        if (slots == null || slots.size() < 1 || slots.size() > MAX_SLOTS)
            throw new IllegalArgumentException("A layout needs 1–4 apps");
        Set<String> ids = new HashSet<>();
        Set<String> apps = new HashSet<>();
        List<Slot> copy = new ArrayList<>(slots);
        for (int i = 0; i < copy.size(); i++) {
            Slot slot = Objects.requireNonNull(copy.get(i), "slot");
            if (!ids.add(slot.id) || !apps.add(slot.packageName))
                throw new IllegalArgumentException("An app or slot cannot appear twice in one layout");
            for (int j = 0; j < i; j++) {
                if (slot.overlaps(copy.get(j))) throw new IllegalArgumentException("App slots cannot overlap");
            }
        }
        this.id = id;
        this.name = name.trim();
        this.slots = Collections.unmodifiableList(copy);
    }

    public static boolean validPackage(String value) {
        return value != null && value.length() <= 255 && PACKAGE.matcher(value).matches();
    }

    public static final class Slot {
        public final String id;
        public final String packageName;
        public final int x, y, width, height, dpi;

        public Slot(String id, String packageName, int x, int y, int width, int height, int dpi) {
            if (id == null || !ID.matcher(id).matches()) throw new IllegalArgumentException("Invalid slot ID");
            if (!validPackage(packageName)) throw new IllegalArgumentException("Invalid app package");
            if (x < 0 || y < 0 || width < 500 || height < 500
                    || x > SCALE - width || y > SCALE - height)
                throw new IllegalArgumentException("Slots must fit inside the cluster and be at least 5% per axis");
            if (dpi != 0 && (dpi < 96 || dpi > 480))
                throw new IllegalArgumentException("Slot DPI must be 0 or 96–480");
            this.id = id;
            this.packageName = packageName;
            this.x = x;
            this.y = y;
            this.width = width;
            this.height = height;
            this.dpi = dpi;
        }

        public int[] pixelBounds(int panelWidth, int panelHeight) {
            if (panelWidth <= 0 || panelHeight <= 0) throw new IllegalArgumentException("Invalid panel size");
            int left = (int) ((long) x * panelWidth / SCALE);
            int top = (int) ((long) y * panelHeight / SCALE);
            int right = (int) ((long) (x + width) * panelWidth / SCALE);
            int bottom = (int) ((long) (y + height) * panelHeight / SCALE);
            if (right - left < 64 || bottom - top < 64)
                throw new IllegalArgumentException("App slots must be at least 64 pixels per axis");
            return new int[] {left, top, right, bottom};
        }

        private boolean overlaps(Slot other) {
            return x < other.x + other.width && x + width > other.x
                    && y < other.y + other.height && y + height > other.y;
        }
    }
}
