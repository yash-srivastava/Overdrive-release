package com.overdrive.app.launcher.dilink5.enable

import com.overdrive.app.R
import com.overdrive.app.ui.fragment.settings.DiLink5AdbResultText
import com.overdrive.app.ui.fragment.settings.DiLink5AdbText
import java.nio.file.Files
import java.nio.file.Paths
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiLink5AdbResultTextTest {
    @Test
    fun passiveDiagnosticsKeepEveryCollectedValueVisible() {
        val details = listOf(
            DiLink5AdbEnableCoordinator.Detail.AdbStatusSnapshot(
                DiLink5AdbEnableCoordinator.AdbStatus.AUTHORIZATION_PENDING,
            ),
            DiLink5AdbEnableCoordinator.Detail.DeviceSnapshot("model-7", "build-9", 31),
            DiLink5AdbEnableCoordinator.Detail.PackageAvailability("package.one", true),
            DiLink5AdbEnableCoordinator.Detail.PackageAvailability("package.two", false),
        )

        val rendered = details.map(DiLink5AdbResultText::detail)

        assertTrue(rendered.all { it.resourceId != 0 })
        assertTrue(rendered.flatMap { values(it) }.containsAll(
            listOf("model-7", "build-9", 31, "package.one", "package.two"),
        ))
    }

    @Test
    fun timeoutAndLateOwnershipKeepWorkerPathAndCertaintyVisible() {
        val timeout = DiLink5AdbResultText.detail(
            DiLink5AdbEnableCoordinator.Detail.WorkerTimeout("package.timeout"),
        )
        val confirmed = DiLink5AdbResultText.detail(
            DiLink5AdbEnableCoordinator.Detail.FlagOwnership(
                "worker.confirmed",
                "/flag/path",
                DiLink5AdbEnableStateStore.MutationCertainty.CONFIRMED,
                DiLink5AdbEnableStateStore.OwnershipSource.LATE_RESULT,
            ),
        )
        val uncertain = DiLink5AdbResultText.detail(
            DiLink5AdbEnableCoordinator.Detail.FlagOwnership(
                "worker.uncertain",
                "/flag/path",
                DiLink5AdbEnableStateStore.MutationCertainty.MAY_HAVE_CHANGED,
                DiLink5AdbEnableStateStore.OwnershipSource.LATE_RESULT,
            ),
        )

        assertEquals(listOf("package.timeout"), values(timeout))
        assertEquals(listOf("worker.confirmed", "/flag/path"), values(confirmed))
        assertEquals(listOf("worker.uncertain", "/flag/path"), values(uncertain))
        assertTrue(confirmed.resourceId != uncertain.resourceId)
    }

    @Test
    fun branchAndRequestOutcomesHaveDistinctLocalizedMessages() {
        val details = mutableListOf<DiLink5AdbEnableCoordinator.Detail>(
            DiLink5AdbEnableCoordinator.Detail.LogToolUnavailable,
            DiLink5AdbEnableCoordinator.Detail.StrategyFallback,
        )
        DiLink5LogToolProtocol.Outcome.entries.forEach { outcome ->
            details += DiLink5AdbEnableCoordinator.Detail.PropertyRequest(outcome, false)
            details += DiLink5AdbEnableCoordinator.Detail.DeveloperActivityRequest(outcome)
        }
        details += DiLink5AdbEnableCoordinator.Detail.PropertyRequest(
            DiLink5LogToolProtocol.Outcome.UNAVAILABLE,
            true,
        )
        DiLink5StrategyServiceClient.Outcome.entries.forEach { outcome ->
            details += DiLink5AdbEnableCoordinator.Detail.StrategyChange(outcome)
        }
        DiLink5AdbEnableCoordinator.PageResult.entries.forEach { outcome ->
            details += DiLink5AdbEnableCoordinator.Detail.PageRequest(outcome)
        }

        assertTrue(details.map(DiLink5AdbResultText::detail).all { it.resourceId != 0 })
    }

    @Test
    fun ownershipSourceSelectsOrdinaryDisconnectAndLateMessages() {
        fun render(source: DiLink5AdbEnableStateStore.OwnershipSource) =
            DiLink5AdbResultText.detail(
                DiLink5AdbEnableCoordinator.Detail.FlagOwnership(
                    "worker",
                    "/flag/path",
                    DiLink5AdbEnableStateStore.MutationCertainty.CONFIRMED,
                    source,
                ),
            )

        assertEquals(
            R.string.settings_dilink5_adb_mutation_target,
            render(DiLink5AdbEnableStateStore.OwnershipSource.ORDINARY).resourceId,
        )
        assertEquals(
            R.string.settings_dilink5_adb_detail_ownership_retained,
            render(DiLink5AdbEnableStateStore.OwnershipSource.DISCONNECT_RETAINED).resourceId,
        )
        assertEquals(
            R.string.settings_dilink5_adb_detail_late_confirmed,
            render(DiLink5AdbEnableStateStore.OwnershipSource.LATE_RESULT).resourceId,
        )
    }

    @Test
    fun sentRejectionAndUnknownReplyKeepTheirPreciseMessages() {
        val rejected = DiLink5AdbResultText.detail(
            DiLink5AdbEnableCoordinator.Detail.PropertyRequest(
                DiLink5LogToolProtocol.Outcome.REJECTED,
                true,
            ),
        )
        val unknown = DiLink5AdbResultText.detail(
            DiLink5AdbEnableCoordinator.Detail.PropertyRequest(
                DiLink5LogToolProtocol.Outcome.UNKNOWN_REPLY,
                true,
            ),
        )

        assertEquals(R.string.settings_dilink5_adb_detail_property_rejected, rejected.resourceId)
        assertEquals(R.string.settings_dilink5_adb_detail_property_unknown, unknown.resourceId)
    }

    @Test
    fun aLostReplyDoesNotRenderAsAnObservedReadableTransition() {
        val rendered = DiLink5AdbResultText.detail(
            DiLink5AdbEnableCoordinator.Detail.WorkerAttempt(
                "worker",
                DiLink5AdbEnableCoordinator.WorkerOutcome.WRITE_REPLY_LOST,
            ),
        )

        assertEquals(
            R.string.settings_dilink5_adb_detail_worker_write_unconfirmed,
            (rendered.arguments[1] as DiLink5AdbText.Argument.Resource).resourceId,
        )
    }

    @Test
    fun uncertainTransitionCopyCoversEmptyAndArbitraryReadableBaselines() {
        listOf("", "7").forEach { priorValue ->
            val rendered = DiLink5AdbResultText.detail(
                DiLink5AdbEnableCoordinator.Detail.WorkerAttempt(
                    "worker-for-${priorValue.ifEmpty { "empty" }}",
                    DiLink5AdbEnableCoordinator.WorkerOutcome.UNCERTAIN_TRANSITION,
                ),
            )
            assertEquals(
                R.string.settings_dilink5_adb_result_flag_uncertain,
                (rendered.arguments[1] as DiLink5AdbText.Argument.Resource).resourceId,
            )
        }

        val resourcePattern = Regex(
            """<string name="settings_dilink5_adb_result_flag_uncertain">([^<]+)</string>""",
        )
        Files.list(Paths.get("src/main/res")).use { directories ->
            directories
                // Only the locales the APK ships; community-only catalogs fill in through the translation sheets.
                .filter { it.fileName.toString() in setOf("values", "values-ar", "values-b+sr+Latn", "values-ca", "values-cs", "values-de", "values-es", "values-fr", "values-he", "values-hi", "values-it", "values-ja", "values-ko", "values-nb", "values-nl", "values-pt-rBR", "values-ru", "values-sr", "values-th", "values-tr", "values-vi", "values-zh-rCN", "values-zh-rTW") }
                .map { it.resolve("strings.xml") }
                .filter(Files::isRegularFile)
                .forEach { catalog ->
                    val strings = String(Files.readAllBytes(catalog), Charsets.UTF_8)
                    val copy = resourcePattern.find(strings)?.groupValues?.get(1)
                    assertTrue("missing uncertain-transition copy in $catalog", !copy.isNullOrBlank())
                    assertFalse("zero-specific uncertain-transition copy in $catalog", copy!!.contains("0"))
                }
        }
    }

    @Test
    fun everyRestorationTargetAndLimitationHasAVisibleTypedMessage() {
        val details = DiLink5AdbEnableCoordinator.RestorationOutcome.entries.flatMap { outcome ->
            listOf(
                DiLink5AdbEnableCoordinator.Detail.FlagRestoration(
                    "worker.$outcome",
                    "/path/$outcome",
                    outcome,
                ),
                DiLink5AdbEnableCoordinator.Detail.StrategyRestoration(outcome),
            )
        } + listOf(
            DiLink5AdbEnableCoordinator.Detail.PropertyNotRestorable,
            DiLink5AdbEnableCoordinator.Detail.NoOwnedRestorableChange,
            DiLink5AdbEnableCoordinator.Detail.OwnershipNotDurable,
        )

        val rendered = details.map(DiLink5AdbResultText::detail)

        assertTrue(rendered.all { it.resourceId != 0 })
        DiLink5AdbEnableCoordinator.RestorationOutcome.entries.forEach { outcome ->
            assertTrue(rendered.flatMap { values(it) }.contains("worker.$outcome"))
            assertTrue(rendered.flatMap { values(it) }.contains("/path/$outcome"))
        }
    }

    @Test
    fun aLateOwnerAfterRestorationChangesTheDisplayedOutcomeToPartial() {
        val result = DiLink5AdbEnableCoordinator.Result(
            DiLink5AdbEnableCoordinator.Stage.RESTORED, false, emptyList(),
        )
        val late = DiLink5AdbEnableCoordinator.Detail.FlagOwnership(
            "late-worker", "/collect2/autotest",
            DiLink5AdbEnableStateStore.MutationCertainty.CONFIRMED,
            DiLink5AdbEnableStateStore.OwnershipSource.LATE_RESULT,
        )
        assertEquals(
            DiLink5AdbEnableCoordinator.Stage.PARTIAL_RESTORATION,
            DiLink5AdbResultText.displayStage(result, listOf(late)),
        )
        assertEquals(
            DiLink5AdbEnableCoordinator.Stage.RESTORED,
            DiLink5AdbResultText.displayStage(result, emptyList()),
        )
        assertEquals(
            R.string.settings_byd_adb_ownership_not_durable,
            DiLink5AdbResultText.detail(DiLink5AdbEnableCoordinator.Detail.OwnershipNotDurable).resourceId,
        )
    }

    private fun values(text: DiLink5AdbText): List<Any> = text.arguments.mapNotNull { argument ->
        (argument as? DiLink5AdbText.Argument.Value)?.value
    }
}
