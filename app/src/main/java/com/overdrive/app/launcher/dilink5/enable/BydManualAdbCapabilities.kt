package com.overdrive.app.launcher.dilink5.enable

import android.content.ComponentName
import android.content.Context

/** Passive compatibility hints; each live client still validates its exact OEM protocol. */
data class BydManualAdbCapabilities(
    val accountFile: Boolean,
    val logTool: Boolean,
    val strategy: Boolean,
    val developerPage: Boolean,
    val developerToolsExport: Boolean,
) {
    val canEnable: Boolean get() = accountFile || logTool || strategy
    val available: Boolean get() = canEnable || developerPage || developerToolsExport

    companion object {
        const val DEVELOPER_TOOLS_PACKAGE = "com.byd.byddevelopmenttools"
        val NONE = BydManualAdbCapabilities(false, false, false, false, false)

        fun forContext(context: Context): BydManualAdbCapabilities {
            val pm = context.applicationContext.packageManager
            fun installed(packageName: String): Boolean = try {
                pm.getApplicationInfo(packageName, 0).enabled
            } catch (_: Throwable) {
                false
            }
            val account = try {
                val info = pm.getServiceInfo(
                    ComponentName(DiLink5AccountServiceClient.PACKAGE, DiLink5AccountServiceClient.SERVICE),
                    0,
                )
                info.enabled && info.exported && info.applicationInfo.enabled
            } catch (_: Throwable) {
                false
            }
            val strategy = try {
                val type = Class.forName("android.strategyservice.StrategyManager")
                val getter = type.getMethod("getStrategy", String::class.java, List::class.java)
                val setter = type.getMethod("setStrategy", String::class.java, getter.returnType)
                val manager = context.getSystemService(DiLink5StrategyServiceClient.SERVICE_NAME)
                type.isInstance(manager) && setter.parameterTypes.size == 2 &&
                    Map::class.java.isAssignableFrom(getter.returnType.getField("configs").type)
            } catch (_: Throwable) {
                false
            }
            val developerTools = installed(DEVELOPER_TOOLS_PACKAGE)
            val knownBydPackage = installed(DiLink5AccountServiceClient.PACKAGE) ||
                installed(DiLink5LogToolProtocol.LOG_TOOL_PACKAGE) ||
                DiLink5AdbWorkerProcess.candidates.any { installed(it.packageName) }
            return fromPassiveEvidence(account, strategy, developerTools, knownBydPackage)
        }

        internal fun fromPassiveEvidence(
            accountFile: Boolean,
            strategy: Boolean,
            developerTools: Boolean,
            knownBydPackageInstalled: Boolean,
        ): BydManualAdbCapabilities {
            val knownByd = accountFile || strategy || developerTools || knownBydPackageInstalled
            return BydManualAdbCapabilities(
                accountFile = accountFile,
                // Missing/disabled wake metadata cannot disprove an already running endpoint.
                // Only an explicit operation performs the bounded, exact ZMTP handshake.
                logTool = knownByd,
                strategy = strategy,
                developerPage = knownByd,
                developerToolsExport = developerTools,
            )
        }
    }
}
