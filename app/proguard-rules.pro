# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.

# Keep line numbers for debugging
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# ==================== BYD SDK Stubs (reflection) ====================
# These are compile-time stubs - real classes come from system at runtime
-keep class android.hardware.bydauto.** { *; }
-keep class android.hardware.BmmCamera** { *; }
# IBYDAutoListener is the base marker interface that every AbsBYDAuto*Listener
# implements. It lives directly in android.hardware (NOT android.hardware.bydauto),
# so the rule above does NOT cover it. The HAL's registerListener(...) signatures
# and its typed-listener dispatch match against this interface; if ProGuard renamed
# or stripped it, our AbsBYDAuto*Listener subclasses (charging/instrument/engine —
# the typed callbacks that deliver live charging power etc.) could fail to register
# or never receive callbacks. Keep it intact.
-keep class android.hardware.IBYDAutoListener { *; }
# IBYDAutoDevice is in the same boat: it lives directly in android.hardware, so the
# android.hardware.bydauto.** rule above does NOT cover it. It is the declared parameter
# type of BYDAutoDeviceManager.enableDevice/disableDevice/addDevice, which we resolve
# reflectively via Class.forName("android.hardware.IBYDAutoDevice") — a rename or strip
# would make that lookup miss and silently disable device ACTIVATION (and with it, on
# trims that need enabling, the whole charging-power surface). Release-build only defect,
# so it would not show up in debug testing.
-keep class android.hardware.IBYDAutoDevice { *; }
# IBYDAutoEvent closes the last hole in this family. It also lives directly in
# android.hardware, and it appears in the DESCRIPTOR of kept callbacks —
# IBYDAutoDevice.onPostEvent(IBYDAutoEvent) and AbsBYDAuto*Listener.onDataChanged(IBYDAutoEvent).
# R8 will not delete a class referenced from a kept member, but it will freely RENAME it. The day
# app code overrides one of those callbacks (the natural next step for the generic event channel),
# the override would compile against the renamed type, stop overriding the platform method, and the
# HAL would dispatch to the platform's no-op base — callback silently never fires, in release only.
-keep class android.hardware.IBYDAutoEvent { *; }
# Belt-and-suspenders: never rename a method that OVERRIDES a kept BYD HAL
# listener method (e.g. onExternalChargingPowerChanged). Overrides normally keep
# their name because the superclass is kept, but make it explicit for the
# typed-listener subclasses we construct in BydDeviceHelper.
-keepclassmembers class * extends android.hardware.bydauto.instrument.AbsBYDAutoInstrumentListener { *; }
-keepclassmembers class * extends android.hardware.bydauto.charging.AbsBYDAutoChargingListener { *; }
-keepclassmembers class * extends android.hardware.bydauto.energy.AbsBYDAutoEnergyListener { *; }

# ==================== Daemon Entry Points (app_process) ====================
# ONLY keep class names and main() - everything else gets obfuscated
# This hides internal method names like whitelistViaBruteForce -> a()

-keep class com.overdrive.app.daemon.CameraDaemon {
    public static void main(java.lang.String[]);
}
-keep class com.overdrive.app.daemon.SentryDaemon {
    public static void main(java.lang.String[]);
}
-keep class com.overdrive.app.daemon.AccSentryDaemon {
    public static void main(java.lang.String[]);
}
-keep class com.overdrive.app.daemon.TelegramBotDaemon {
    public static void main(java.lang.String[]);
}
-keep class com.overdrive.app.daemon.GlobalProxyDaemon {
    public static void main(java.lang.String[]);
}
-keep class com.overdrive.app.byd.BydEventDaemon {
    public static void main(java.lang.String[]);
}
-keep class com.overdrive.app.byd.BydModeCommand {
    public static void main(java.lang.String[]);
}
-keep class com.overdrive.app.launcher.ZrokRuntimeProbe {
    public static void main(java.lang.String[]);
}

# Keep listener classes that extend BYD SDK (method names must match parent)
-keep class com.overdrive.app.daemon.AccSentryDaemon$AccListener {
    <methods>;
}

# ==================== Static Watchdog Builders (cross-process callers) ====================
# These companion-object static helpers are called from daemon processes
# (AccSentryDaemon, TelegramBotDaemon) to build the SAME shell-watchdog
# script the UI deploys. Without `-keep` here, R8 may rename
# `buildTelegramWatchdogScript` etc., the runtime call from
# AccSentryDaemon.launchTelegramDaemon would NoSuchMethodError, and the
# code falls back to the bare-nohup unsupervised launch — silently
# regressing the H2 watchdog work.
-keep class com.overdrive.app.launcher.DaemonLauncher$Companion {
    public *;
}
-keep class com.overdrive.app.launcher.ZrokLauncher$Companion {
    public *;
}

# ==================== Daemon Support Classes ====================
# These are used by daemons but don't need full preservation

# Safe - Pure Java AES decryption (replaced native NativeSecrets)
-keep class com.overdrive.app.daemon.proxy.Safe {
    public static java.lang.String s(java.lang.String);
}

# S - Short alias for string decryption (used throughout daemon code)
-keep class com.overdrive.app.daemon.proxy.S {
    public static java.lang.String d(java.lang.String);
}

# Enc - holds decrypted constants accessed via reflection from
# DaemonBootstrap.verifySafeWorking() (Class.forName + getDeclaredField).
# Without this, R8 renames APP_PACKAGE to a single-letter name and the
# bootstrap aborts before the daemon ever loads.
-keep class com.overdrive.app.daemon.proxy.Enc {
    public static java.lang.String APP_PACKAGE;
    *;
}

# CameraDaemon.getAppContext() — called reflectively by ScreenDeterrent.resolveContext()
# (surveillance package can't compile-time depend on daemon package). The
# main()-only -keep above doesn't preserve this, so R8 renames it to a()
# and ScreenDeterrent silently falls through to DaemonBootstrap.
# getGpuPipeline() — called reflectively by ClusterProjectionController.notifyPipelineReady/
# notifyPipelineClosed (same surveillance→daemon no-compile-time-dep reason). Without this
# keep, R8 renames getGpuPipeline() to a short name (mapping.txt: getGpuPipeline -> j), the
# reflective getMethod("getGpuPipeline") throws NoSuchMethodException (swallowed at
# logger.debug), and onClusterProjectionReady/Closed NEVER fire — so the warm cluster
# blind-spot SurfaceControl layer is never re-tagged onto the recreated fission display's
# new layerStack → it composites onto the destroyed old stack → BS card renders BLACK.
-keepclassmembers class com.overdrive.app.daemon.CameraDaemon {
    public static android.content.Context getAppContext();
    public static com.overdrive.app.surveillance.GpuSurveillancePipeline getGpuPipeline();
}

# GpuSurveillancePipeline.onClusterProjectionReady()/onClusterProjectionClosed() — the
# SECOND reflective hop from ClusterProjectionController (pipe.getClass().getMethod(...)).
# Same dead-reflection failure if R8 renames them; keep both so the cluster show/hide
# re-tag + render-gate callbacks survive R8.
-keepclassmembers class com.overdrive.app.surveillance.GpuSurveillancePipeline {
    public void onClusterProjectionReady();
    public void onClusterProjectionClosed();
}

# DaemonBootstrap.getContext() — fallback path for the same reflection above.
-keepclassmembers class com.overdrive.app.daemon.DaemonBootstrap {
    public static android.content.Context getContext();
}

# Messages.get(String, String) and Messages.get(String) — called
# reflectively by SrtWriter.lookupCatalog() so subtitle generation can
# pull localized strings without a compile-time dependency from
# surveillance → server.
-keepclassmembers class com.overdrive.app.server.Messages {
    public static java.lang.String get(java.lang.String, java.lang.String);
    public static java.lang.String get(java.lang.String);
}

# LocaleManager.get() — called reflectively by SrtWriter.resolveCurrentLocale()
-keepclassmembers class com.overdrive.app.server.LocaleManager {
    public static java.lang.String get();
}

# Keep class names for daemon subpackages (for Class.forName if used internally)
# but allow method/field renaming
-keepnames class com.overdrive.app.daemon.** { }
-keepnames class com.overdrive.app.byd.** { }
-keepnames class com.overdrive.app.camera.** { }
-keepnames class com.overdrive.app.server.** { }
-keepnames class com.overdrive.app.encoding.** { }
-keepnames class com.overdrive.app.stream.** { }
-keepnames class com.overdrive.app.monitor.** { }

# ==================== Logging (keep class names + DaemonLogConfig for runtime control) ====================
-keepnames class com.overdrive.app.logging.** { }
-keep class com.overdrive.app.logging.DaemonLogConfig { *; }

# ==================== Native Methods (all classes) ====================
# JNI lookup mangles BOTH the class and method name into the C symbol
# (Java_com_overdrive_app_od_Od_nativeAuthorize). The generic
# -keepclasseswithmembernames rule below keeps native METHOD names but NOT
# the CLASS name, so R8 is free to rename `Od` -> `a`, and the runtime
# System.loadLibrary lookup then misses the .so export and NoSuchMethodErrors.
# Keep the class name (and its native methods) explicitly first.
-keep class com.overdrive.app.od.Od {
    native <methods>;
}
# JNI method names must match native function signatures exactly
-keepclasseswithmembernames class * {
    native <methods>;
}

# ==================== Dadb (ADB client) ====================
-keep class dadb.** { *; }
-dontwarn dadb.**

# ==================== ZXing (QR codes) ====================
-keep class com.google.zxing.** { *; }

# ==================== Eclipse Paho MQTT ====================
# Paho uses java.util.logging internally and loads logging resource bundles
# by class name via reflection. ProGuard strips these, causing
# MissingResourceException at connect time.
-keep class org.eclipse.paho.client.mqttv3.** { *; }
-keep class org.eclipse.paho.client.mqttv3.logging.** { *; }
-dontwarn org.eclipse.paho.client.mqttv3.**
# v5 client used by BydCloudMqttSubscriber for BYD's EMQ broker — same
# reflection-based logging-bundle loader as v3. The package roots differ
# from v3 (mqttv5 sits directly under org.eclipse.paho, no .client. prefix
# in the package after that), so the v3 rules above don't cover it.
-keep class org.eclipse.paho.mqttv5.** { *; }
-keep class org.eclipse.paho.mqttv5.client.logging.** { *; }
-dontwarn org.eclipse.paho.mqttv5.**

# ==================== RTMP client ====================
-keep class com.pedro.** { *; }
-dontwarn com.pedro.**

# ==================== TensorFlow Lite ====================
# CPU-only (XNNPACK). The GPU delegate dependency was removed because on
# Adreno 610 (unified-memory SoC) concurrent OpenCL inference and the H.265
# encoder share one DDR bus, producing visible eglSwap stalls during
# recording. See YoloDetector.kt class doc.
-keep class org.tensorflow.lite.** { *; }
-keep interface org.tensorflow.lite.** { *; }
-keepclassmembers class org.tensorflow.lite.** { *; }
-keep class org.tensorflow.lite.support.** { *; }
-keep class com.google.auto.value.** { *; }
-dontwarn com.google.auto.value.**

# AI detection classes - keep class names but allow method obfuscation
# Detection data class needs field names for any serialization
-keep class com.overdrive.app.ai.Detection { *; }
-keepnames class com.overdrive.app.ai.** { }

# ==================== Kotlin & AndroidX ====================
-dontwarn kotlin.**
-keep class kotlin.Metadata { *; }
# AndroidX - only keep what's needed, not everything
-keep class androidx.core.content.FileProvider { *; }
-keep class androidx.work.** { *; }
-keep class androidx.navigation.** { *; }
-keepnames class androidx.** { }
-dontwarn androidx.**

# ==================== App Components (declared in AndroidManifest) ====================
# R8 auto-keeps these, but explicit rules for safety
-keep class com.overdrive.app.OverdriveApplication { *; }
-keep class com.overdrive.app.ui.MainActivity { *; }
-keep class com.overdrive.app.ui.LocationStarterActivity { *; }
-keep class com.overdrive.app.BlockerActivity { *; }
-keep class com.overdrive.app.receiver.BootReceiver { *; }
-keep class com.overdrive.app.receiver.LocationBootReceiver { *; }
-keep class com.overdrive.app.services.LocationSidecarService { *; }
# RoadSense IMU sidecar — launched by the daemon via a STRING-LITERAL `am
# start-foreground-service -n .../RoadSenseImuSidecarService` (R8 can't see that
# reference), so its class name must not be renamed/stripped. Same rationale as
# LocationSidecarService above. The daemon→app bridge (CameraDaemon.getRoadSense,
# RoadSenseController, RoadSenseApiHandler) is reached by typed calls so R8 tracks
# those automatically; only the am-launched component needs an explicit keep.
-keep class com.overdrive.app.roadsense.sidecar.RoadSenseImuSidecarService { *; }
-keep class com.overdrive.app.roadsense.overlay.RoadSenseOverlayService { *; }

# ==================== App Packages (allow obfuscation) ====================
# Keep class names for debugging but obfuscate methods/fields
-keepnames class com.overdrive.app.auth.** { }
-keepnames class com.overdrive.app.bridge.** { }
-keepnames class com.overdrive.app.byd.** { }
-keepnames class com.overdrive.app.client.** { }
-keepnames class com.overdrive.app.config.** { }
-keepnames class com.overdrive.app.launcher.** { }
-keepnames class com.overdrive.app.manager.** { }
-keepnames class com.overdrive.app.od.** { }
# power.** was the only first-party package missing here. Nothing reflects into
# it today (StealthPanel etc. are reached by direct call from the kept daemon
# entry points), so this changes nothing now — it closes the trap where a future
# reflective hop into the package would fail silently in release builds only.
-keepnames class com.overdrive.app.power.** { }
-keepnames class com.overdrive.app.proximity.** { }
-keepnames class com.overdrive.app.recording.** { }
-keepnames class com.overdrive.app.service.** { }
-keepnames class com.overdrive.app.shell.** { }
-keepnames class com.overdrive.app.storage.** { }
-keepnames class com.overdrive.app.streaming.** { }
-keepnames class com.overdrive.app.surveillance.** { }
-keepnames class com.overdrive.app.telemetry.** { }
# TelemetrySnapshot fields accessed by overlay renderer — keep from renaming
-keepclassmembers class com.overdrive.app.telemetry.TelemetrySnapshot { public *; }
-keepnames class com.overdrive.app.abrp.** { }
-keepnames class com.overdrive.app.telegram.** { }
-keepnames class com.overdrive.app.ui.** { }
-keepnames class com.overdrive.app.util.** { }
-keepnames class com.overdrive.app.webrtc.** { }

# ==================== Serialization & Parcelable ====================
-keepclassmembers class * implements android.os.Parcelable {
    public static final android.os.Parcelable$Creator *;
}
-keepclassmembers class * implements java.io.Serializable {
    static final long serialVersionUID;
    private static final java.io.ObjectStreamField[] serialPersistentFields;
    private void writeObject(java.io.ObjectOutputStream);
    private void readObject(java.io.ObjectInputStream);
    java.lang.Object writeReplace();
    java.lang.Object readResolve();
}

# ==================== H2 Database & JDBC Fixes (COMPLETE) ====================

# 1. Ignore Desktop UI (AWT/Swing)
-dontwarn java.awt.**
-dontwarn java.beans.**

# 2. Ignore Java Management Extensions (JMX)
-dontwarn java.lang.management.**
-dontwarn javax.management.**

# 3. Ignore Servlets (Web Server features) - MISSING IN PREVIOUS
-dontwarn javax.servlet.**
-dontwarn jakarta.servlet.**

# 4. Ignore OSGi (Module System) - MISSING IN PREVIOUS
-dontwarn org.osgi.**

# 5. Ignore Advanced SQL/Transaction APIs (XA/JDBCType)
-dontwarn java.sql.**
-dontwarn javax.sql.**
-dontwarn javax.transaction.**
-dontwarn javax.naming.**
-dontwarn javax.security.**

# 6. Ignore GIS/Geometry Support (JTS)
-dontwarn org.locationtech.jts.**

# 7. Ignore Lucene (Full Text Search)
-dontwarn org.apache.lucene.**

# 8. Ignore other missing standard Java extensions
-dontwarn javax.xml.stream.**
-dontwarn javax.xml.transform.**
-dontwarn javax.tools.**
-dontwarn javax.script.**

# 9. Keep H2 functional
-keep class org.h2.** { *; }

# ==================== Log Stripping (Release Builds) ====================
#
# CONTROLLED BY: com.overdrive.app.logging.DaemonLogConfig + buildType
#
# Stripping is split across TWO buildType-conditional files so the
# `braveheart` channel can ship the FULL log surface:
#   - proguard-rules-strip-logs.pro     → DaemonLogger/LogManager FILE logging.
#       Included by `release` when no DaemonLogConfig flag is set (auto-detect
#       in build.gradle.kts); EXCLUDED by `braveheart`.
#   - proguard-rules-strip-console.pro  → android.util.Log (logcat) + stdout/err.
#       Included by `release`; EXCLUDED by `braveheart` so logcat AND the daemon
#       stdout that feeds /data/local/tmp/<daemon>.log are preserved.
#
# This file (proguard-rules.pro) is applied to ALL buildTypes, so it must NOT
# contain any log-stripping rules — only keep-rules + class-structure rules.
# ====================================================================

# Keep DaemonLogConfig (R8 needs it for runtime checks when logging is enabled)
-keep class com.overdrive.app.logging.DaemonLogConfig { *; }

# Keep DaemonLogger class structure
-keep class com.overdrive.app.logging.DaemonLogger { *; }
-keep class com.overdrive.app.logging.DaemonLogger$Config { *; }
-keep class com.overdrive.app.logging.DaemonLogger$Level { *; }

# Strip Kotlin null checks (minor optimization — always safe to strip)
-assumenosideeffects class kotlin.jvm.internal.Intrinsics {
    public static void checkParameterIsNotNull(...);
    public static void checkNotNullParameter(...);
    public static void checkNotNullExpressionValue(...);
}

# ==================== General Safety ====================
-dontwarn sun.misc.Unsafe
-dontwarn java.nio.file.**
-dontwarn java.util.spi.ToolProvider

# ==================== DiLink 5.0 (SA8155P) JNI Bridge ====================
-keep class com.overdrive.app.camera.dilink5.** { *; }
-keepclassmembers class com.overdrive.app.camera.dilink5.** { *; }

