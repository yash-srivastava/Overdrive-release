package com.overdrive.app.byd.dilink5;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import com.overdrive.app.util.ScratchPaths;

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
import java.util.Enumeration;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Loads the real OEM {@code bydauto} classes at runtime on DiLink 5.0 (Android 11 Automotive / SA8155P)
 * by injecting the already-installed {@code com.byd.data.collect} APK into Overdrive's own
 * ClassLoader, instead of requiring a bundled jar in the build.
 */
public final class Dilink5SdkInjector {

    private static final String TAG = "Dilink5SdkInjector";
    private static final DaemonLogger logger = DaemonLogger.getInstance(TAG);

    private static final String OEM_PKG = "com.byd.data.collect";
    private static final String DEFAULT_OEM_APK_PATH = "/system/app/BydDataCollect/BydDataCollect.apk";

    private static final String[] PROBE_CLASSES = new String[] {
            "android.hardware.bydauto.statistic.BYDAutoStatisticDevice",
            "android.hardware.bydauto.charging.AbsBYDAutoChargingListener",
            "android.hardware.bydauto.tyre.AbsBYDAutoTyreListener",
            "android.hardware.bydauto.instrument.AbsBYDAutoInstrumentListener",
            "android.hardware.bydauto.collectdata.AbsBYDAutoCollectDataListener"
    };

    private static volatile boolean permanentlyUnavailable = false;
    private static final Map<ClassLoader, Object[]> pristineMap = new IdentityHashMap<>();

    private Dilink5SdkInjector() {}

    /**
     * Ensures that the BYD Auto SDK classes are available on all relevant ClassLoaders
     * (either already present or after injecting com.byd.data.collect dex).
     *
     * @param context Android context (can be app context, activity context, or null for fallback path)
     * @return true if bydauto classes can now be resolved
     */
    public static synchronized boolean ensure(Context context) {
        List<ClassLoader> loaders = new ArrayList<>();
        ClassLoader selfLoader = Dilink5SdkInjector.class.getClassLoader();
        if (selfLoader != null) loaders.add(selfLoader);
        if (context != null && context.getClassLoader() != null && !loaders.contains(context.getClassLoader())) {
            loaders.add(context.getClassLoader());
        }
        ClassLoader ccl = Thread.currentThread().getContextClassLoader();
        if (ccl != null && !loaders.contains(ccl)) loaders.add(ccl);
        ClassLoader sys = ClassLoader.getSystemClassLoader();
        if (sys != null && !loaders.contains(sys)) loaders.add(sys);

        boolean anySuccess = false;
        for (ClassLoader l : loaders) {
            if (injectIntoLoader(l, context)) {
                anySuccess = true;
            }
        }
        return anySuccess || isLoadable();
    }

    public static boolean isLoadable() {
        return loadable(Dilink5SdkInjector.class.getClassLoader());
    }

    private static boolean injectIntoLoader(ClassLoader loader, Context context) {
        if (loader == null) return false;
        if (loadable(loader)) return true;
        if (permanentlyUnavailable) return false;

        List<String> apkPaths = getOemApkPaths(context);
        if (apkPaths.isEmpty()) {
            logger.warn(OEM_PKG + " not found and fallback path does not exist");
            permanentlyUnavailable = true;
            return false;
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
            Object[] newElements = makeInMemoryElements(dexListCls, apkPaths, suppressed);
            if (newElements == null || newElements.length == 0) {
                File optDir = context != null ? new File(context.getCodeCacheDir(), "bydauto-inj") : new File(ScratchPaths.path("bydauto-inj"));
                optDir.mkdirs();
                List<File> files = new ArrayList<>();
                for (String p : apkPaths) {
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

            boolean ok = loadable(loader);
            logger.info("Injected " + newElements.length + " dex elements into " + loader.getClass().getSimpleName() + "; bydauto loadable=" + ok);
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

    private static List<String> getOemApkPaths(Context context) {
        List<String> paths = new ArrayList<>();
        if (context != null) {
            try {
                PackageManager pm = context.getPackageManager();
                if (pm != null) {
                    ApplicationInfo ai = pm.getApplicationInfo(OEM_PKG, 0);
                    if (ai != null) {
                        if (ai.sourceDir != null && new File(ai.sourceDir).exists()) {
                            paths.add(ai.sourceDir);
                        }
                        if (ai.splitSourceDirs != null) {
                            for (String split : ai.splitSourceDirs) {
                                if (split != null && new File(split).exists() && !paths.contains(split)) {
                                    paths.add(split);
                                }
                            }
                        }
                    }
                }
            } catch (Exception ignored) {}
        }
        if (paths.isEmpty()) {
            File def = new File(DEFAULT_OEM_APK_PATH);
            if (def.exists()) {
                paths.add(def.getAbsolutePath());
            }
        }
        return paths;
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
