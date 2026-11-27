package com.overdrive.app.byd;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.Test;

/**
 * R25: shell-identity fallback for the synchronous DI5 actuator bridge.
 *
 * Field evidence (on-car log): the daemon's synthetic-context
 * startForegroundService is rejected by AMS with "Unable to find app for
 * caller ... when starting service" (no ProcessRecord for the forged
 * IApplicationThread), while `am start-foreground-service` succeeds on the
 * same firmware because it calls IActivityManager.startService with a NULL
 * caller and shell attribution. The fallback must mirror exactly that call —
 * preserving the live ResultReceiver and Parcelable extras — and must be
 * unreachable outside the UID-2000 daemon. CarBodyManager.setPowerMode must
 * never be adopted anywhere (field-proven to write the unrelated vehicle
 * property 0x2140144A, not the IVI work mode).
 */
public class VehicleActuatorBridgeShellStartContractTest {

    private static final String BRIDGE =
            "app/src/main/java/com/overdrive/app/byd/VehicleActuatorBridge.java";

    @Test
    public void shellIdentityFallbackIsGatedAttributedAndFailSoft()
            throws Exception {
        String source = readRepositoryFile(BRIDGE);

        // The direct app-context start stays FIRST (it is the correct path on
        // firmware that accepts it); the shell-identity start is a fallback
        // only, inside the DI5-gated dispatch.
        int dispatch = source.indexOf(
                "private static android.os.Bundle dispatchDiLink5Request(");
        assertTrue(dispatch >= 0);
        int di5Gate = source.indexOf(
                "!com.overdrive.app.camera.dilink5.DiLink5Platform.isSelected()", dispatch);
        int directStart = source.indexOf("startContext.startForegroundService(intent)", dispatch);
        int fallbackCall = source.indexOf(
                "startDiLink5ServiceAsShell(startContext, intent, true, serviceMayOwnRequest)", dispatch);
        assertTrue(di5Gate > dispatch);
        assertTrue(directStart > di5Gate);
        assertTrue(fallbackCall > directStart);

        // Fallback body: UID-2000-only, NULL IApplicationThread caller,
        // com.android.shell attribution, requireForeground, fail-soft.
        int method = source.indexOf(
                "private static android.content.ComponentName startDiLink5ServiceAsShell(");
        int methodEnd = source.indexOf("static long capDeadlineToAccFreshness(", method);
        assertTrue(method > 0 && methodEnd > method);
        String body = source.substring(method, methodEnd);
        assertTrue(body.contains(
                "if (android.os.Process.myUid() != ANDROID_SHELL_UID) return null;"));
        assertTrue(body.contains("\"startService\".equals(candidate.getName())"));
        // Foreground actuator requests and ordinary projection requests preserve their
        // service type and the original Intent on both supported Android signatures.
        String args = "new Object[]{null, intent, resolvedType, requireForeground,";
        assertTrue(body.indexOf(args) >= 0 && body.lastIndexOf(args) > body.indexOf(args));
        assertTrue(source.contains("startDiLink5ServiceAsShell(context, intent, false, null)"));
        assertTrue(body.contains("\"com.android.shell\""));
        // Fail-soft: every failure path returns null so callers degrade
        // exactly as before the fallback existed.
        assertTrue(body.contains("catch (Throwable failed)"));
        assertFalse(body.contains("throw "));

        // The proven subprocess bridge for fire-and-forget launches remains.
        assertTrue(source.contains("am start-foreground-service -n "));
        // Never adopt the field-disproven IVI power setter.
        assertFalse(source.contains("setPowerMode"));
        assertFalse(source.contains("CarBodyManager"));
    }

    private static String readRepositoryFile(String relativePath)
            throws Exception {
        Path current = Paths.get(System.getProperty("user.dir"))
                .toAbsolutePath().normalize();
        while (current != null) {
            Path candidate = current.resolve(relativePath);
            if (Files.isRegularFile(candidate)) {
                return new String(
                        Files.readAllBytes(candidate),
                        StandardCharsets.UTF_8);
            }
            Path fromModule = current.resolve(
                    relativePath.replaceFirst("^app/", ""));
            if (Files.isRegularFile(fromModule)) {
                return new String(
                        Files.readAllBytes(fromModule),
                        StandardCharsets.UTF_8);
            }
            current = current.getParent();
        }
        throw new AssertionError(
                "Could not locate repository file: " + relativePath);
    }
}
