package com.overdrive.app.launcher.dilink5.enable

import java.nio.file.Files
import java.nio.file.Paths
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiLink5AdbWorkerManifestTest {
    @Test
    fun workersArePrivateAndUseExactCandidateProcesses() {
        val manifest = source("src/main/AndroidManifest.xml")
        val declarations = listOf(
            "DiLink5LauncherMapWorkerService" to "com.byd.launchermap",
            "DiLink5NaviAutoWorkerService" to "com.byd.naviauto",
            "DiLink5AutoMapWorkerService" to "com.byd.automap",
            "DiLink5BtetAutoMapWorkerService" to "com.btet.automap",
            "DiLink5AccountWorkerService" to "com.byd.diLinkAccount",
        )
        declarations.forEach { (service, process) ->
            val start = manifest.indexOf(service)
            val end = manifest.indexOf("/>", start)
            assertTrue("missing $service", start >= 0)
            assertTrue(end > start)
            val declaration = manifest.substring(start, end)
            assertTrue(declaration.contains("android:exported=\"false\""))
            assertTrue(declaration.contains("android:process=\"$process\""))
            assertFalse(declaration.contains("android:isolatedProcess=\"true\""))
        }
    }

    @Test
    fun minimalWorkerResolvesSharedRootThenReturnsBeforeMainStackInitialization() {
        val application = source("src/main/java/com/overdrive/app/OverdriveApplication.kt")
        val root = application.indexOf("ScratchRoot.bootstrap(this)")
        val guard = application.indexOf("DiLink5AdbWorkerProcess.isCurrentProcess()")
        val firstMainInitialization = application.indexOf("AuthManager.useAppPrivateDir")
        assertTrue(root >= 0)
        assertTrue(guard > root)
        assertTrue(firstMainInitialization > guard)
        assertTrue(application.substring(guard, firstMainInitialization).contains("return"))
        assertTrue(application.contains("packageName == android.app.Application.getProcessName()"))
        val coordinator = source(
            "src/main/java/com/overdrive/app/launcher/dilink5/enable/DiLink5AdbEnableCoordinator.kt",
        )
        assertTrue(coordinator.contains("CONFIGURED_DILINK5_AUTHORITY"))
        assertTrue(coordinator.contains("BydManualAdbModeAuthority()"))
        val authority = source(
            "src/main/java/com/overdrive/app/launcher/dilink5/enable/BydManualAdbModeAuthority.kt",
        )
        assertTrue(authority.contains("currentManualAdbModeSnapshot()"))
        val worker = source(
            "src/main/java/com/overdrive/app/launcher/dilink5/enable/DiLink5AdbWorkerService.kt",
        )
        assertTrue(worker.contains("isConfiguredDiLink5WithoutCommittedMode()"))
    }

    @Test
    fun everyManualControlIsReachableAndLocalized() {
        val layout = source("src/main/res/layout/fragment_settings_adb.xml")
        val fragment = source("src/main/java/com/overdrive/app/ui/fragment/settings/SettingsAdbFragment.kt")
        listOf(
            "btnDiLink5AdbEnable",
            "btnDiLink5AdbPage",
            "btnDiLink5AdbDiagnostics",
            "btnDiLink5AdbRestore",
            "btnDiLink5AdbExport",
        ).forEach { id ->
            assertTrue(layout.contains("@+id/$id"))
            assertTrue(fragment.contains("R.id.$id"))
        }
        val pageBinding = fragment.substring(
            fragment.indexOf("R.id.btnDiLink5AdbPage"),
            fragment.indexOf("R.id.btnDiLink5AdbDiagnostics"),
        )
        assertTrue(pageBinding.contains("confirmDeveloperPageRequest()"))
        val pageConfirmation = fragment.substring(
            fragment.indexOf("private fun confirmDeveloperPageRequest()"),
            fragment.indexOf("private fun confirmAdbRestoration()"),
        )
        assertTrue(pageConfirmation.contains("settings_dilink5_adb_enable_confirm_body"))
        assertTrue(pageConfirmation.contains("client.requestDeveloperPage(admitted)"))
        val requiredManualStrings = listOf(
            "settings_dilink5_adb_enable_confirm_body",
            "settings_dilink5_adb_restore_confirm_body",
            "settings_dilink5_adb_result_flag_uncertain",
            "settings_dilink5_adb_result_cancelled",
            "settings_dilink5_adb_result_partial_restoration",
            "settings_dilink5_adb_result_page_accepted",
            "settings_dilink5_adb_mutation_target",
            "settings_dilink5_adb_detail_worker_timeout",
            "settings_dilink5_adb_detail_late_confirmed",
            "settings_dilink5_adb_detail_late_uncertain",
            "settings_dilink5_adb_detail_device",
            "settings_dilink5_adb_detail_log_unavailable",
            "settings_dilink5_adb_detail_flag_restored",
            "settings_dilink5_adb_detail_flag_restore_unconfirmed",
            "settings_dilink5_adb_detail_flag_restore_unsupported",
            "settings_dilink5_adb_export_cleanup_failed",
            "settings_byd_adb_ownership_not_durable",
        )
        val adbUiStrings = source("src/main/res/values/settings_adb.xml")
        val requiredAdbUiStrings = Regex("""<string name="([^"]+)"""")
            .findAll(adbUiStrings).map { it.groupValues[1] }.toList()
        assertTrue(requiredAdbUiStrings.isNotEmpty())
        Regex("""@string/(settings_adb_\w+|settings_section_adb)""")
            .findAll(layout).forEach { reference ->
                assertTrue(reference.value, reference.groupValues[1] in requiredAdbUiStrings)
            }
        Files.list(Paths.get("src/main/res")).use { directories ->
            directories
                // Only the locales the APK ships; community-only catalogs fill in through the translation sheets.
                .filter { it.fileName.toString() in setOf("values", "values-ar", "values-b+sr+Latn", "values-ca", "values-cs", "values-de", "values-es", "values-fr", "values-he", "values-hi", "values-it", "values-ja", "values-ko", "values-nb", "values-nl", "values-pt-rBR", "values-ru", "values-sr", "values-th", "values-tr", "values-vi", "values-zh-rCN", "values-zh-rTW") }
                .map { it.resolve("strings.xml") }
                .filter(Files::isRegularFile)
                .forEach { catalog ->
                    val strings = String(Files.readAllBytes(catalog), Charsets.UTF_8)
                    requiredManualStrings.forEach { key ->
                        assertTrue(catalog.toString(), strings.contains("name=\"$key\""))
                    }
                    val adbCatalog = catalog.parent.resolve("settings_adb.xml")
                    assertTrue(adbCatalog.toString(), Files.isRegularFile(adbCatalog))
                    val localizedAdbUi = String(Files.readAllBytes(adbCatalog), Charsets.UTF_8)
                    requiredAdbUiStrings.forEach { key ->
                        assertTrue(adbCatalog.toString(), localizedAdbUi.contains("name=\"$key\""))
                    }
                    if (catalog.parent.fileName.toString() != "values") {
                        assertTrue(catalog.toString(), strings.contains("5555"))
                        assertFalse(catalog.toString(), strings.contains("Manual ADB enablement"))
                        assertFalse(catalog.toString(), strings.contains("Only readable values changed by this app can be restored."))
                    }
                }
        }
        assertFalse(fragment.contains("result.stage.name"))
        assertTrue(fragment.contains("result?.details.orEmpty() + lateDetails"))
        assertTrue(fragment.contains("DiLink5AdbResultText.detail(detail)"))
        assertFalse(fragment.contains("append(result.details"))
    }

    @Test
    fun localRecoveryAndTlsDialogControlsHaveSeparateGroups() {
        val document = javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(Paths.get("src/main/res/layout/fragment_settings_adb.xml").toFile())
        val nodes = document.getElementsByTagName("*")
        val recoveryIds = setOf(
            "swAdbAutomaticRestore", "swDiLink5AdbDialogAutoConfirm",
            "btnAdbRecover", "btnAdbCancelRecovery",
        )
        val found = mutableSetOf<String>()
        for (index in 0 until nodes.length) {
            val element = nodes.item(index) as org.w3c.dom.Element
            val id = element.getAttribute("android:id").removePrefix("@+id/")
            if (id !in recoveryIds) continue
            found += id
            var parent = element.parentNode
            var withinTlsGroup = false
            val requiredGroup = if (id == "swDiLink5AdbDialogAutoConfirm") {
                "groupDiLink5AdbTlsAdvanced"
            } else {
                "groupAdbRecovery"
            }
            while (parent is org.w3c.dom.Element) {
                if (parent.getAttribute("android:id") == "@+id/$requiredGroup") {
                    withinTlsGroup = true
                }
                parent = parent.parentNode
            }
            assertTrue("$id is outside its recovery section", withinTlsGroup)
        }
        assertTrue(found == recoveryIds)
        val fragment = source("src/main/java/com/overdrive/app/ui/fragment/settings/SettingsAdbFragment.kt")
        assertTrue(fragment.contains(
            "groupDiLink5AdbTlsAdvanced.visibility = if (tlsAvailable) View.VISIBLE else View.GONE",
        ))
        assertFalse(fragment.contains(
            "groupAdbRecovery.visibility = if (tlsAvailable)",
        ))
        assertTrue(fragment.contains("modeAvailable || recoveryOwned"))
    }

    private fun source(path: String) = String(Files.readAllBytes(Paths.get(path)), Charsets.UTF_8)
}
