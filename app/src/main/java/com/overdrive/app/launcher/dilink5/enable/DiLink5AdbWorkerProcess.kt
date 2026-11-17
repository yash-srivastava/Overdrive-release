package com.overdrive.app.launcher.dilink5.enable

import android.app.Application

/** Exact process boundary for the private account-service workers. */
object DiLink5AdbWorkerProcess {
    data class Candidate(
        val packageName: String,
        val processName: String,
        val serviceClass: Class<out DiLink5AdbWorkerService>,
    )

    val candidates: List<Candidate> = listOf(
        Candidate("com.byd.launchermap", "com.byd.launchermap", DiLink5LauncherMapWorkerService::class.java),
        Candidate("com.byd.naviauto", "com.byd.naviauto", DiLink5NaviAutoWorkerService::class.java),
        Candidate("com.byd.automap", "com.byd.automap", DiLink5AutoMapWorkerService::class.java),
        Candidate("com.btet.automap", "com.btet.automap", DiLink5BtetAutoMapWorkerService::class.java),
        Candidate("com.byd.diLinkAccount", "com.byd.diLinkAccount", DiLink5AccountWorkerService::class.java),
    )

    /** Inspection always uses the first private worker, independent of OEM package presence. */
    val initialInspectionCandidate: Candidate
        get() = candidates.first()

    fun isWorkerProcess(processName: String?): Boolean =
        processName != null && candidates.any { it.processName == processName }

    fun isCurrentProcess(): Boolean = isWorkerProcess(Application.getProcessName())

    fun candidateForService(serviceClass: Class<*>): Candidate? =
        candidates.firstOrNull { it.serviceClass == serviceClass }
}
