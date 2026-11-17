package com.overdrive.app.launcher.dilink5.enable

import com.overdrive.app.camera.dilink5.DiLink5Platform

/** Manual OEM tools do not select a camera backend or grant automatic recovery authority. */
internal class BydManualAdbModeAuthority(
    private val snapshot: () -> Scope? = ::currentScope,
) : DiLink5AdbEnableCoordinator.ModeAuthority {
    data class Scope(
        val configuredMode: String,
        val activeMode: String? = null,
        val activeGeneration: String? = null,
        val pendingMode: String? = null,
        val pendingGeneration: String? = null,
    ) {
        fun authority(): String? {
            if (configuredMode !in MODES ||
                !validMarker(activeMode, activeGeneration) ||
                !validMarker(pendingMode, pendingGeneration)
            ) return null
            return PREFIX + listOf(
                configuredMode, activeMode, activeGeneration, pendingMode, pendingGeneration,
            ).joinToString("|") { field -> field?.let { "${it.length}:$it" } ?: "-" }
        }

        val permitsTlsRecovery: Boolean
            get() = activeMode == "dilink5" && !activeGeneration.isNullOrEmpty()

        private fun validMarker(mode: String?, generation: String?): Boolean =
            if (mode == null) generation == null
            else mode in MODES && !generation.isNullOrEmpty()
    }

    override fun admit(): String? = snapshot()?.authority()

    override fun isCurrent(generation: String): Boolean =
        generation.startsWith(PREFIX) && snapshot()?.authority() == generation

    companion object {
        private const val PREFIX = "manual:byd:v1:"
        private val MODES = setOf("default", "dilink4", "dilink5", "camera2")

        fun currentScope(): Scope? {
            val current = DiLink5Platform.currentManualAdbModeSnapshot() ?: return null
            return Scope(
                configuredMode = current.configuredMode,
                activeMode = current.active?.mode,
                activeGeneration = current.active?.generation,
                pendingMode = current.pending?.mode,
                pendingGeneration = current.pending?.generation,
            )
        }

        fun isManualAuthority(value: String?): Boolean = value?.startsWith(PREFIX) == true

        fun isCurrent(value: String?): Boolean =
            value != null && BydManualAdbModeAuthority().isCurrent(value)

        fun committedDiLink5RecoveryAvailable(): Boolean {
            val active = DiLink5Platform.currentActiveModeSnapshot() ?: return false
            return active.mode == "dilink5"
        }
    }
}
