package com.overdrive.app.surveillance;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ClusterProjectionControllerDiLink5Test {

    @Test
    public void diLink5OpcodeGateIncludesLifecycleOnly() {
        for (int op : new int[] {0, 16, 18}) {
            assertTrue(
                    ClusterProjectionController
                            .isAllowedDiLink5ContainerOpcode(op));
        }
        for (int op : new int[] {-1, 1, 12, 13, 17, 29, 30, 31, 35, 53}) {
            assertFalse(
                    ClusterProjectionController
                            .isAllowedDiLink5ContainerOpcode(op));
        }
    }

    @Test
    public void inactiveModeCleanupAuthorityCannotEnableProjection() {
        assertTrue(ClusterProjectionController
                .isAllowedDiLink5CleanupOpcode(0));
        assertTrue(ClusterProjectionController
                .isAllowedDiLink5CleanupOpcode(18));
        assertFalse(ClusterProjectionController
                .isAllowedDiLink5CleanupOpcode(16));
        for (int op : new int[] {12, 13, 53}) {
            assertFalse(ClusterProjectionController.isAllowedDiLink5CleanupOpcode(op));
        }
    }

    @Test
    public void nativeDiLink5ResultRejectsOnlyNegativeValues() {
        assertTrue(
                ClusterProjectionController
                        .isAcceptedDiLink5ContainerResult(0));
        assertTrue(
                ClusterProjectionController
                        .isAcceptedDiLink5ContainerResult(1));
        assertFalse(
                ClusterProjectionController
                        .isAcceptedDiLink5ContainerResult(-1));
    }

    @Test
    public void serviceCallParserReadsTheReturnedParcelWord() {
        assertEquals(
                Integer.valueOf(1),
                ClusterProjectionController.parseDiLink5ServiceCallResult(
                        "Result: Parcel(00000000 00000001 '........')"));
        assertEquals(
                Integer.valueOf(-1),
                ClusterProjectionController.parseDiLink5ServiceCallResult(
                        "Result: Parcel(00000000 ffffffff '........')"));
        assertNull(
                ClusterProjectionController.parseDiLink5ServiceCallResult(
                        "Result: Parcel(00000000 '....')"));
        assertNull(
                ClusterProjectionController.parseDiLink5ServiceCallResult(
                        "Result: Parcel(fffffffe 00000001 '........')"));
        assertEquals(
                Integer.valueOf(0),
                ClusterProjectionController.parseDiLink5ServiceCallStatus(
                        "Result: Parcel(00000000 '....')"));
        assertEquals(
                Integer.valueOf(-2),
                ClusterProjectionController.parseDiLink5ServiceCallStatus(
                        "Result: Parcel(fffffffe 00000001 '........')"));
        assertNull(
                ClusterProjectionController.parseDiLink5ServiceCallResult(
                        "Service AutoContainer does not exist"));
    }

    @Test
    public void legacyServiceCallRequiresAConfirmedBinderSuccess() {
        assertTrue(
                ClusterProjectionController
                        .isAcceptedLegacyServiceCallResult(
                                0,
                                false,
                                "Result: Parcel(00000000 '....')"));
        assertTrue(
                ClusterProjectionController
                        .isAcceptedLegacyServiceCallResult(
                                0,
                                false,
                                "Result: Parcel(00000000 00000001 '........')"));
        assertFalse(
                ClusterProjectionController
                        .isAcceptedLegacyServiceCallResult(
                                0,
                                false,
                                "Result: Parcel(00000000 ffffffff '........')"));
        assertFalse(
                ClusterProjectionController
                        .isAcceptedLegacyServiceCallResult(
                                0,
                                false,
                                "Result: Parcel(fffffffe 00000001 '........')"));
        assertFalse(
                ClusterProjectionController
                        .isAcceptedLegacyServiceCallResult(
                                1,
                                false,
                                "Result: Parcel(00000000 '....')"));
        assertFalse(
                ClusterProjectionController
                        .isAcceptedLegacyServiceCallResult(
                                0,
                                true,
                                "Result: Parcel(00000000 '....')"));
        assertFalse(
                ClusterProjectionController
                        .isAcceptedLegacyServiceCallResult(
                                0,
                                false,
                                "service call returned no parcel"));
    }

    @Test
    public void onlyExplicitMissingServiceOutputAllowsAliasFallback() {
        assertTrue(
                ClusterProjectionController
                        .isDiLink5ServiceUnavailableOutput(
                                "Service AutoContainer does not exist",
                                "AutoContainer"));
        assertTrue(
                ClusterProjectionController
                        .isDiLink5ServiceUnavailableOutput(
                                "Service auto_container: not found",
                                "auto_container"));
        assertFalse(
                ClusterProjectionController
                        .isDiLink5ServiceUnavailableOutput(
                                "binder transaction failed with exit 1",
                                "AutoContainer"));
        assertFalse(
                ClusterProjectionController
                        .isDiLink5ServiceUnavailableOutput(
                                "Result: Parcel()",
                                "AutoContainer"));
    }

    @Test
    public void onlyProjectionEnableMayBootstrapTheContainerService() {
        assertTrue(ClusterProjectionController
                .shouldBootstrapDiLink5ContainerService(
                        16,
                        ClusterProjectionController.DiLink5CommandResult
                                .UNAVAILABLE));
        assertFalse(ClusterProjectionController
                .shouldBootstrapDiLink5ContainerService(
                        18,
                        ClusterProjectionController.DiLink5CommandResult
                                .UNAVAILABLE));
        assertFalse(ClusterProjectionController
                .shouldBootstrapDiLink5ContainerService(
                        0,
                        ClusterProjectionController.DiLink5CommandResult
                                .UNAVAILABLE));
        assertFalse(ClusterProjectionController
                .shouldBootstrapDiLink5ContainerService(
                        16,
                        ClusterProjectionController.DiLink5CommandResult
                                .REJECTED));
    }

    @Test
    public void xdjaBootstrapRequiresAnExactInstalledPackageResult() {
        assertTrue(ClusterProjectionController.isPackagePathResult(
                0, "package:/system/priv-app/XdjaContainerService/base.apk\n"));
        assertFalse(ClusterProjectionController.isPackagePathResult(
                1, "package:/system/priv-app/XdjaContainerService/base.apk\n"));
        assertFalse(ClusterProjectionController.isPackagePathResult(
                0, "Unknown package: com.xdja.containerservice"));
    }

    @Test
    public void serviceBootstrapRejectsShellLevelFailures() {
        assertTrue(ClusterProjectionController.isSuccessfulServiceStartResult(
                0,
                false,
                "Starting service: Intent { cmp=com.xdja.containerservice/"
                        + ".AutoDisplayService }"));
        assertFalse(ClusterProjectionController.isSuccessfulServiceStartResult(
                0, true, ""));
        assertFalse(ClusterProjectionController.isSuccessfulServiceStartResult(
                0, false, "Error: Not allowed to start service"));
        assertFalse(ClusterProjectionController.isSuccessfulServiceStartResult(
                1, false, "Starting service"));
    }

    @Test
    public void legacyRecoveryResultSeparatesRetryableAndSameBootFailures() {
        assertTrue(
                ClusterProjectionController.LegacyBootRecoveryResult
                        .RECOVERED.isRecovered());
        assertFalse(
                ClusterProjectionController.LegacyBootRecoveryResult
                        .RETRYABLE_FAILURE.isRecovered());
        assertFalse(
                ClusterProjectionController.LegacyBootRecoveryResult
                        .SAME_BOOT_INDETERMINATE.isRecovered());
        assertTrue(
                ClusterProjectionController.LegacyBootRecoveryResult
                        .SAME_BOOT_INDETERMINATE
                        .isSameBootIndeterminate());
        assertFalse(
                ClusterProjectionController.LegacyBootRecoveryResult
                        .RETRYABLE_FAILURE
                        .isSameBootIndeterminate());
    }

    @Test
    public void missingLegacyServiceClassifiesAsUnavailableNotRejected() {
        // "Service X does not exist" means nothing was dispatched. Mapping it
        // to REJECTED made AutoContainer-less firmware indistinguishable from
        // a live service refusing the opcode, which permanently wedged boot
        // recovery (2026-09-23 field log).
        assertEquals(
                ClusterProjectionController.LegacyCommandResult.UNAVAILABLE,
                ClusterProjectionController.classifyLegacyServiceCallResult(
                        1, false, "Service AutoContainer does not exist"));
        assertEquals(
                ClusterProjectionController.LegacyCommandResult.UNAVAILABLE,
                ClusterProjectionController.classifyLegacyServiceCallResult(
                        1, false, "Service AutoContainer: not found"));
        // A live service that refuses stays REJECTED (Binder exception word /
        // negative native verdict); a tool that exited with an error FAILED
        // (nothing pending); only a still-running call is INDETERMINATE.
        assertEquals(
                ClusterProjectionController.LegacyCommandResult.REJECTED,
                ClusterProjectionController.classifyLegacyServiceCallResult(
                        0, false,
                        "Result: Parcel(fffffffe 00000001 '........')"));
        assertEquals(
                ClusterProjectionController.LegacyCommandResult.REJECTED,
                ClusterProjectionController.classifyLegacyServiceCallResult(
                        0, false,
                        "Result: Parcel(00000000 ffffffff '........')"));
        assertEquals(
                ClusterProjectionController.LegacyCommandResult.FAILED,
                ClusterProjectionController.classifyLegacyServiceCallResult(
                        1, false, "binder transaction failed"));
        assertEquals(
                ClusterProjectionController.LegacyCommandResult.INDETERMINATE,
                ClusterProjectionController.classifyLegacyServiceCallResult(
                        0, true, "Result: Parcel(00000000 '....')"));
        assertEquals(
                ClusterProjectionController.LegacyCommandResult.ACCEPTED,
                ClusterProjectionController.classifyLegacyServiceCallResult(
                        0, false, "Result: Parcel(00000000 '....')"));
    }

    @Test
    public void terminalRecoveryVerdictsEndTheLadderAndReleaseAdmission() {
        // Terminal = no retry can change the outcome.
        assertTrue(ClusterProjectionController.LegacyBootRecoveryResult
                .RECOVERED.isTerminal());
        assertTrue(ClusterProjectionController.LegacyBootRecoveryResult
                .UNSUPPORTED.isTerminal());
        assertFalse(ClusterProjectionController.LegacyBootRecoveryResult
                .RETRYABLE_FAILURE.isTerminal());
        assertFalse(ClusterProjectionController.LegacyBootRecoveryResult
                .SAME_BOOT_INDETERMINATE.isTerminal());

        assertTrue(ClusterProjectionController.LegacyBootRecoveryResult
                .UNSUPPORTED.isUnsupported());
        assertFalse(ClusterProjectionController.LegacyBootRecoveryResult
                .UNSUPPORTED.isRecovered());
        assertFalse(ClusterProjectionController.LegacyBootRecoveryResult
                .UNSUPPORTED.isSameBootIndeterminate());
        assertFalse(ClusterProjectionController.LegacyBootRecoveryResult
                .RECOVERED.isUnsupported());

        // After a FINISHED restore pass, only an unresolved same-boot command
        // keeps projection admission blocked; every other verdict releases it
        // (the durable marker, when retained, re-arms recovery at next boot).
        assertTrue(ClusterProjectionController.LegacyBootRecoveryResult
                .SAME_BOOT_INDETERMINATE.blocksAdmissionAfterRestore());
        assertFalse(ClusterProjectionController.LegacyBootRecoveryResult
                .RECOVERED.blocksAdmissionAfterRestore());
        assertFalse(ClusterProjectionController.LegacyBootRecoveryResult
                .UNSUPPORTED.blocksAdmissionAfterRestore());
        assertFalse(ClusterProjectionController.LegacyBootRecoveryResult
                .RETRYABLE_FAILURE.blocksAdmissionAfterRestore());
    }
}
