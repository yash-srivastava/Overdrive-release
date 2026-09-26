package com.overdrive.app.byd.dilink5;
import com.overdrive.app.util.ScratchPaths;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;

import com.overdrive.app.logging.DaemonLogger;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Loads the vehicle's {@code bydauto} classes at runtime on DiLink 5 by locating the system dex
 * container that defines them and attaching it to this process.
 */
public final class Dilink5SdkInjector {

    private static final String TAG = "Dilink5SdkInjector";
    private static final DaemonLogger logger = DaemonLogger.getInstance(TAG);

    private static final String CORE_PROBE_CLASS =
            "android.hardware.bydauto.BYDAutoEventValue";
    private static final String[] SYSTEM_DEX_ROOTS = {
            "/system/framework",
            "/system_ext/framework",
            "/product/framework",
            "/vendor/framework",
            "/odm/framework",
            "/system/app",
            "/system/priv-app",
            "/system_ext/app",
            "/system_ext/priv-app",
            "/product/app",
            "/product/priv-app",
            "/vendor/app",
            "/vendor/priv-app"
    };

    // This complete set is used only to prove injection can be skipped. After injection, optional
    // capabilities are allowed to remain absent and are handled independently by each device path.
    private static final String[] PROBE_CLASSES = new String[] {
            "android.hardware.bydauto.BYDAutoDeviceManager",
            "android.hardware.bydauto.speed.AbsBYDAutoSpeedListener",
            "android.hardware.bydauto.collectdata.AbsBYDAutoCollectDataListener",
            "android.hardware.bydauto.setting.AbsBYDAutoSettingListener",
            "android.hardware.bydauto.bodywork.AbsBYDAutoBodyworkListener",
            "android.hardware.bydauto.engine.AbsBYDAutoEngineListener",
            "android.hardware.bydauto.energy.AbsBYDAutoEnergyListener",
            "android.hardware.bydauto.doorlock.AbsBYDAutoDoorLockListener",
            "android.hardware.bydauto.statistic.BYDAutoStatisticDevice",
            "android.hardware.bydauto.statistic.AbsBYDAutoStatisticListener",
            "android.hardware.bydauto.charging.AbsBYDAutoChargingListener",
            "android.hardware.bydauto.tyre.AbsBYDAutoTyreListener",
            "android.hardware.bydauto.instrument.AbsBYDAutoInstrumentListener",
            "android.hardware.bydauto.safetybelt.AbsBYDAutoSafetyBeltListener",
            "android.hardware.bydauto.power.AbsBYDAutoPowerListener",
            "android.hardware.bydauto.radar.AbsBYDAutoRadarListener"
    };

    private static volatile Context appContext;
    private static final Map<ClassLoader, Object[]> pristineMap = new IdentityHashMap<>();
    private static final Map<ClassLoader, Object[]> injectedElements = new IdentityHashMap<>();

    private Dilink5SdkInjector() {}

    /**
     * Ensures that the BYD Auto SDK classes are available on this process's
     * application ClassLoader.
     *
     * @param context Android context (can be app context, activity context, or null for fallback path)
     * @return true if bydauto classes can now be resolved
     */
    public static synchronized boolean ensure(Context context) {
        if (!com.overdrive.app.camera.dilink5.DiLink5Platform.isSelected()) {
            return true;
        }
        if (context != null) {
            try {
                Context application = context.getApplicationContext();
                appContext = application != null ? application : context;
            } catch (Throwable ignored) {
                appContext = context;
            }
        }
        Context effectiveContext = context != null ? context : appContext;
        try {
            com.overdrive.app.shell.HiddenApiBypass.INSTANCE.bypass();
        } catch (Throwable ignored) {}
        ClassLoader selfLoader = Dilink5SdkInjector.class.getClassLoader();
        return injectIntoLoader(selfLoader, effectiveContext);
    }

    public static boolean isLoadable() {
        return coreLoadable(Dilink5SdkInjector.class.getClassLoader());
    }

    private static boolean injectIntoLoader(ClassLoader loader, Context context) {
        if (loader == null) return false;
        boolean lostInjection = false;
        Object[] expectedElements = injectedElements.get(loader);
        if (expectedElements != null) {
            if (injectedElementsPresent(loader, expectedElements) && coreLoadable(loader)) {
                return true;
            }
            injectedElements.remove(loader);
            lostInjection = true;
        }

        if (!lostInjection && loadable(loader)) {
            return true;
        }

        List<String> dexPaths = findRuntimeDexPaths(context);
        if (dexPaths.isEmpty()) {
            boolean present = coreLoadable(loader);
            if (!present) {
                logger.warn("No system dex container defines the vehicle SDK");
            }
            return present;
        }

        try {
            Class<?> baseCls = Class.forName("dalvik.system.BaseDexClassLoader");
            Field pathListField = baseCls.getDeclaredField("pathList");
            pathListField.setAccessible(true);
            Object pathList = pathListField.get(loader);
            if (pathList == null) {
                return false;
            }

            Class<?> dexListCls = pathList.getClass();
            Field dexElementsField = dexListCls.getDeclaredField("dexElements");
            dexElementsField.setAccessible(true);
            Object[] oldElements = (Object[]) dexElementsField.get(pathList);

            Object[] baseElements;
            if (pristineMap.containsKey(loader)) {
                baseElements = pristineMap.get(loader);
            } else {
                pristineMap.put(loader, oldElements);
                baseElements = oldElements;
            }

            List<IOException> suppressed = new ArrayList<>();
            Object[] newElements = makeInMemoryElements(dexListCls, dexPaths, suppressed);
            if (newElements == null || newElements.length == 0) {
                File optDir = context != null ? new File(context.getCodeCacheDir(), "bydauto-inj") : new File(ScratchPaths.path("bydauto-inj"));
                optDir.mkdirs();
                List<File> files = new ArrayList<>();
                for (String p : dexPaths) {
                    files.add(new File(p));
                }
                newElements = makePathElements(dexListCls, files, optDir, suppressed, loader);
            }

            if (newElements == null || newElements.length == 0) {
                return false;
            }

            for (IOException ioe : suppressed) {
                logger.debug("Suppressed injection error: " + ioe.getMessage());
            }

            // Append newElements to baseElements
            Class<?> componentType = baseElements != null ? baseElements.getClass().getComponentType() : Object.class;
            int baseLen = baseElements != null ? baseElements.length : 0;
            Object[] combined = (Object[]) Array.newInstance(componentType, baseLen + newElements.length);
            if (baseElements != null && baseLen > 0) {
                System.arraycopy(baseElements, 0, combined, 0, baseLen);
            }
            System.arraycopy(newElements, 0, combined, baseLen, newElements.length);

            dexElementsField.set(pathList, combined);

            boolean ok = coreLoadable(loader);
            if (ok) injectedElements.put(loader, newElements);
            logger.info("Injected " + newElements.length + " dex elements into "
                    + loader.getClass().getSimpleName() + "; bydauto core loadable=" + ok);
            return ok;
        } catch (Throwable t) {
            logger.warn("Dilink5SdkInjector inject failed on " + loader.getClass().getSimpleName() + ": " + t.getMessage());
            return false;
        }
    }

    private static boolean loadable(ClassLoader loader) {
        if (loader == null) return false;
        for (String probe : PROBE_CLASSES) {
            try {
                Class.forName(probe, false, loader);
            } catch (Throwable t) {
                return false;
            }
        }
        return true;
    }

    private static boolean coreLoadable(ClassLoader loader) {
        if (loader == null) return false;
        try {
            Class.forName(CORE_PROBE_CLASS, false, loader);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean injectedElementsPresent(ClassLoader loader, Object[] expected) {
        if (loader == null || expected == null || expected.length == 0) return false;
        try {
            Class<?> baseCls = Class.forName("dalvik.system.BaseDexClassLoader");
            Field pathListField = baseCls.getDeclaredField("pathList");
            pathListField.setAccessible(true);
            Object pathList = pathListField.get(loader);
            Field dexElementsField = pathList.getClass().getDeclaredField("dexElements");
            dexElementsField.setAccessible(true);
            Object[] current = (Object[]) dexElementsField.get(pathList);
            for (Object wanted : expected) {
                boolean found = false;
                for (Object element : current) {
                    if (element == wanted) {
                        found = true;
                        break;
                    }
                }
                if (!found) return false;
            }
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private static List<String> findRuntimeDexPaths(Context context) {
        for (String root : SYSTEM_DEX_ROOTS) {
            Set<String> candidates = new LinkedHashSet<>();
            collectDexCandidates(
                    new File(root), root.endsWith("/framework") ? 1 : 3, candidates);
            List<String> providers = coreProviders(candidates);
            if (!providers.isEmpty()) return providers;
        }

        if (context != null) {
            try {
                PackageManager pm = context.getPackageManager();
                if (pm != null) {
                    for (ApplicationInfo ai : pm.getInstalledApplications(0)) {
                        if (ai == null || (ai.flags & ApplicationInfo.FLAG_SYSTEM) == 0) continue;
                        Set<String> appPaths = new LinkedHashSet<>();
                        addDexCandidate(appPaths, ai.sourceDir);
                        if (ai.splitSourceDirs != null) {
                            for (String split : ai.splitSourceDirs) {
                                addDexCandidate(appPaths, split);
                            }
                        }
                        if (!coreProviders(appPaths).isEmpty()) {
                            return new ArrayList<>(appPaths);
                        }
                    }
                }
            } catch (Exception ignored) {}
        }
        return Collections.emptyList();
    }

    private static List<String> coreProviders(Set<String> candidates) {
        List<String> sorted = new ArrayList<>(candidates);
        Collections.sort(sorted);
        List<String> providers = new ArrayList<>();
        for (String candidate : sorted) {
            if (definesCoreProbe(candidate)) providers.add(candidate);
        }
        return providers;
    }

    private static void addDexCandidate(Set<String> candidates, String path) {
        if (path == null) return;
        File file = new File(path);
        if (file.isFile() && isDexContainer(file.getName())) {
            candidates.add(file.getAbsolutePath());
        }
    }

    private static void collectDexCandidates(
            File path, int remainingDepth, Set<String> candidates) {
        if (path == null || !path.exists() || !path.canRead()) return;
        if (path.isFile()) {
            if (isDexContainer(path.getName())) candidates.add(path.getAbsolutePath());
            return;
        }
        if (remainingDepth <= 0) return;
        File[] children = path.listFiles();
        if (children == null) return;
        for (File child : children) {
            collectDexCandidates(child, remainingDepth - 1, candidates);
        }
    }

    static boolean isDexContainer(String name) {
        if (name == null) return false;
        String lower = name.toLowerCase(java.util.Locale.ROOT);
        return lower.endsWith(".apk") || lower.endsWith(".jar");
    }

    private static boolean definesCoreProbe(String path) {
        Object dexFile = null;
        try {
            Class<?> dexFileClass = Class.forName("dalvik.system.DexFile");
            dexFile = dexFileClass.getConstructor(String.class).newInstance(path);
            Object value = dexFileClass.getMethod("entries").invoke(dexFile);
            if (!(value instanceof Enumeration)) return false;
            Enumeration<?> entries = (Enumeration<?>) value;
            while (entries.hasMoreElements()) {
                if (CORE_PROBE_CLASS.equals(String.valueOf(entries.nextElement()))) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
        } finally {
            if (dexFile != null) {
                try {
                    dexFile.getClass().getMethod("close").invoke(dexFile);
                } catch (Throwable ignored) {}
            }
        }
        return false;
    }

    private static Object[] makeInMemoryElements(Class<?> dexListCls, List<String> apkPaths, List<IOException> suppressed) {
        try {
            Method m = dexListCls.getDeclaredMethod("makeInMemoryDexElements", ByteBuffer[].class, List.class);
            m.setAccessible(true);

            List<ByteBuffer> buffers = new ArrayList<>();
            for (String apkPath : apkPaths) {
                try (ZipFile zip = new ZipFile(apkPath)) {
                    Enumeration<? extends ZipEntry> entries = zip.entries();
                    while (entries.hasMoreElements()) {
                        ZipEntry entry = entries.nextElement();
                        if (entry.getName().matches("classes\\d*\\.dex")) {
                            try (InputStream is = zip.getInputStream(entry)) {
                                byte[] bytes = readAllBytes(is);
                                buffers.add(ByteBuffer.wrap(bytes));
                            }
                        }
                    }
                } catch (IOException e) {
                    suppressed.add(new IOException("Error reading " + apkPath + ": " + e.getMessage(), e));
                }
            }

            if (buffers.isEmpty()) {
                return null;
            }

            return (Object[]) m.invoke(null, buffers.toArray(new ByteBuffer[0]), suppressed);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Object[] makePathElements(Class<?> dexListCls, List<File> files, File optDir,
                                             List<IOException> suppressed, ClassLoader loader) {
        try {
            Method m = dexListCls.getDeclaredMethod("makePathElements", List.class, File.class, List.class);
            m.setAccessible(true);
            return (Object[]) m.invoke(null, files, optDir, suppressed);
        } catch (Throwable ignored) {}

        try {
            Method m = dexListCls.getDeclaredMethod("makeDexElements", List.class, File.class, List.class, ClassLoader.class);
            m.setAccessible(true);
            return (Object[]) m.invoke(null, files, optDir, suppressed, loader);
        } catch (Throwable ignored) {}

        return null;
    }

    private static byte[] readAllBytes(InputStream is) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] data = new byte[8192];
        int nRead;
        while ((nRead = is.read(data, 0, data.length)) != -1) {
            buffer.write(data, 0, nRead);
        }
        return buffer.toByteArray();
    }
}
