package com.overdrive.app.launcher.dilink5.enable

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.ResultReceiver
import com.overdrive.app.launcher.AdbShellExecutor
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** Owner-triggered coordinator. Construction and passive status reads perform no mutation. */
class DiLink5AdbEnableCoordinator(
    private val workers: WorkerGateway,
    private val logTool: LogToolGateway,
    private val strategy: StrategyGateway,
    private val state: StateGateway,
    private val pageLauncher: PageLauncher,
    private val passiveInfo: PassiveInfoProvider,
    private val modeAuthority: ModeAuthority,
    private val capabilities: () -> BydManualAdbCapabilities,
    private val detailObserver: (Detail) -> Unit = {},
) {
    enum class Stage {
        FLAG_CONFIRMED,
        FLAG_ACCEPTED,
        FLAG_ALREADY_ENABLED,
        FLAG_UNCERTAIN_TRANSITION,
        FLAG_REPLY_LOST,
        FLAG_EXISTS_ONLY,
        FLAG_CONTENT_ZERO,
        LOG_TOOL_CONFIRMED,
        LOG_TOOL_NOT_CONFIRMED,
        STRATEGY_CONFIRMED,
        STRATEGY_NOT_CONFIRMED,
        DIAGNOSTICS,
        CANCELLED,
        BUSY,
        UNAVAILABLE,
        RESTORED,
        PARTIAL_RESTORATION,
        PAGE_REQUEST_ACCEPTED,
        PAGE_REQUEST_UNAVAILABLE,
    }

    enum class AdbStatus { CONNECTED_AUTHORIZED, AUTHORIZATION_PENDING, DISCONNECTED, PORT_CLOSED }
    enum class PageResult { TARGETED_INTENT_ACCEPTED, GENERIC_INTENT_ACCEPTED, UNAVAILABLE }
    enum class WorkerOutcome {
        WRITE_AND_VALUE_CONFIRMED,
        WRITE_ACCEPTED,
        UNCERTAIN_TRANSITION,
        WRITE_REPLY_LOST,
        ALREADY_ENABLED,
        CONTENT_ZERO,
        EXISTS_VALUE_UNKNOWN,
        TRANSPORT_FAILED,
        WRITE_NOT_CONFIRMED,
    }
    enum class RestorationOutcome { CONFIRMED, NOT_CONFIRMED, UNSUPPORTED }

    sealed interface Detail {
        data class CandidateAvailability(val packageName: String, val installed: Boolean) : Detail
        data class WorkerTimeout(val packageName: String) : Detail
        data class WorkerAttempt(val packageName: String, val outcome: WorkerOutcome) : Detail
        data class FlagOwnership(
            val workerProcess: String,
            val path: String,
            val certainty: DiLink5AdbEnableStateStore.MutationCertainty,
            val source: DiLink5AdbEnableStateStore.OwnershipSource,
        ) : Detail
        data object LogToolUnavailable : Detail
        data class PropertyRequest(
            val outcome: DiLink5LogToolProtocol.Outcome,
            val requestSent: Boolean,
            val replyCorrelated: Boolean = false,
        ) : Detail
        data class DeveloperActivityRequest(val outcome: DiLink5LogToolProtocol.Outcome) : Detail
        data object StrategyFallback : Detail
        data class StrategyChange(val outcome: DiLink5StrategyServiceClient.Outcome) : Detail
        data class FlagRestoration(
            val workerProcess: String,
            val path: String,
            val outcome: RestorationOutcome,
        ) : Detail
        data class StrategyRestoration(val outcome: RestorationOutcome) : Detail
        data object PropertyNotRestorable : Detail
        data object NoOwnedRestorableChange : Detail
        data object OwnershipNotDurable : Detail
        data class PageRequest(val result: PageResult) : Detail
        data class AdbStatusSnapshot(val status: AdbStatus) : Detail
        data class DeviceSnapshot(val model: String, val build: String, val sdk: Int) : Detail
        data class PackageAvailability(val packageName: String, val installed: Boolean) : Detail
    }

    data class Result(
        val stage: Stage,
        val authenticatedAdb: Boolean,
        val details: List<Detail>,
        val mutationWorker: String? = null,
        val mutationPath: String? = null,
        val prerequisiteStage: Stage? = null,
        val ownershipDurable: Boolean = true,
    )

    data class WorkerResult(
        val workerProcess: String,
        val bound: Boolean,
        val connected: Boolean,
        val binderDied: Boolean,
        val prior: DiLink5AccountServiceClient.ReadResult,
        val writeResult: Int?,
        val exists: Boolean?,
        val after: DiLink5AccountServiceClient.ReadResult,
        val detail: String,
        val conditionalMatched: Boolean? = null,
        val writeDispatched: Boolean = false,
        val replyLostAfterDispatch: Boolean = false,
    )

    interface WorkerGateway {
        fun installed(candidate: DiLink5AdbWorkerProcess.Candidate): Boolean
        fun inspectMain(operationId: Long, modeGeneration: String, authority: () -> Boolean): WorkerResult? = null
        fun inspect(
            candidate: DiLink5AdbWorkerProcess.Candidate,
            operationId: Long,
            modeGeneration: String,
            authority: () -> Boolean,
        ): WorkerResult?
        fun write(
            candidate: DiLink5AdbWorkerProcess.Candidate,
            content: String,
            operationId: Long,
            modeGeneration: String,
            authority: () -> Boolean,
            onLateResult: (WorkerResult) -> Unit = {},
        ): WorkerResult?
        fun restore(
            candidate: DiLink5AdbWorkerProcess.Candidate,
            expectedCurrent: String,
            restoreValue: String,
            operationId: Long,
            modeGeneration: String,
            authority: () -> Boolean,
        ): WorkerResult?
    }

    interface LogToolGateway {
        fun probe(cancelled: () -> Boolean): DiLink5LogToolProtocol.Availability
        fun setAdbTcpPort(cancelled: () -> Boolean): DiLink5LogToolProtocol.RequestResult
        fun requestDeveloperActivity(cancelled: () -> Boolean): DiLink5LogToolProtocol.RequestResult
    }

    interface StrategyGateway {
        fun readVerifyEnable(): String?
        fun setVerifyDisabled(isCurrent: () -> Boolean): DiLink5StrategyServiceClient.Result
        fun restoreVerifyEnable(
            expectedCurrent: String,
            value: String,
            isCurrent: () -> Boolean,
        ): DiLink5StrategyServiceClient.Result
    }

    interface StateGateway {
        fun saveFlag(record: DiLink5AdbEnableStateStore.FlagMutation): Boolean
        fun flags(): List<DiLink5AdbEnableStateStore.FlagMutation>
        fun clearFlag(record: DiLink5AdbEnableStateStore.FlagMutation): Boolean
        fun saveStrategy(record: DiLink5AdbEnableStateStore.StrategyMutation): Boolean
        fun strategy(): DiLink5AdbEnableStateStore.StrategyMutation?
        fun clearStrategy(): Boolean
        fun markPropertyMayHaveChanged(changed: Boolean): Boolean
        fun propertyMayHaveChanged(): Boolean
        fun ownershipDurable(): Boolean
        fun <T> withOwnershipSnapshot(block: (DiLink5AdbEnableStateStore.OwnershipSnapshot) -> T): T
        fun observeFlagOwnership(
            observer: (DiLink5AdbEnableStateStore.FlagMutation) -> Unit,
        ): AutoCloseable
    }

    interface PageLauncher { fun launch(): PageResult }

    interface ModeAuthority {
        fun admit(): String?
        fun isCurrent(generation: String): Boolean
    }

    interface PassiveInfoProvider {
        fun adbStatus(): AdbStatus
        fun details(): List<Detail>
    }

    private val running = AtomicBoolean(false)
    private val generation = AtomicLong(0)
    private val stateObservation = state.observeFlagOwnership { record ->
        if (record.source == DiLink5AdbEnableStateStore.OwnershipSource.LATE_RESULT) {
            detailObserver(record.toOwnershipDetail())
        }
    }

    /** Returns only asynchronous ownership updates; current-operation details stay in the result. */
    fun lateDetails(): List<Detail> = state.withOwnershipSnapshot { snapshot ->
        snapshot.flags
            .filter { it.source == DiLink5AdbEnableStateStore.OwnershipSource.LATE_RESULT }
            .map { it.toOwnershipDetail() } +
            if (snapshot.durable) emptyList() else listOf(Detail.OwnershipNotDurable)
    }

    @JvmOverloads
    fun enable(expectedModeAuthority: String? = null): Result = singleFlight(expectedModeAuthority) { token, modeGeneration ->
        enableInternal(token, modeGeneration)
    }

    @JvmOverloads
    fun requestDeveloperPage(expectedModeAuthority: String? = null): Result =
        singleFlight(expectedModeAuthority) { token, modeGeneration ->
        val rails = capabilities()
        if (!rails.developerPage) {
            return@singleFlight Result(
                Stage.PAGE_REQUEST_UNAVAILABLE,
                isAuthenticatedAdb(),
                listOf(Detail.PageRequest(PageResult.UNAVAILABLE)),
            )
        }
        var flagExists = false
        for (candidate in DiLink5AdbWorkerProcess.candidates.takeIf { rails.accountFile }.orEmpty()) {
            if (!active(token, modeGeneration)) return@singleFlight cancelledResult()
            if (!workers.installed(candidate)) continue
            if (!active(token, modeGeneration)) return@singleFlight cancelledResult()
            val inspection = workers.inspect(candidate, nextOperationId(), modeGeneration) {
                active(token, modeGeneration)
            }
            if (!active(token, modeGeneration)) return@singleFlight cancelledResult()
            if (inspection?.exists == true) {
                flagExists = true
                break
            }
        }
        if (!flagExists && rails.accountFile) {
            if (!active(token, modeGeneration)) return@singleFlight cancelledResult()
            val mainInspection = workers.inspectMain(nextOperationId(), modeGeneration) {
                active(token, modeGeneration)
            }
            if (!active(token, modeGeneration)) return@singleFlight cancelledResult()
            flagExists = mainInspection?.exists == true
        }
        var enablement: Result? = null
        if (!flagExists) {
            enablement = enableInternal(token, modeGeneration)
            val canRequestPage = enablement.stage in setOf(
                Stage.FLAG_CONFIRMED,
                Stage.FLAG_ACCEPTED,
                Stage.FLAG_ALREADY_ENABLED,
                Stage.FLAG_UNCERTAIN_TRANSITION,
                Stage.FLAG_REPLY_LOST,
                Stage.FLAG_EXISTS_ONLY,
                Stage.FLAG_CONTENT_ZERO,
                Stage.LOG_TOOL_CONFIRMED,
                Stage.STRATEGY_CONFIRMED,
            ) || (enablement.stage == Stage.LOG_TOOL_NOT_CONFIRMED &&
                enablement.details.filterIsInstance<Detail.PropertyRequest>().any {
                    it.outcome == DiLink5LogToolProtocol.Outcome.UNKNOWN_REPLY &&
                        it.requestSent && it.replyCorrelated
                })
            if (!canRequestPage) return@singleFlight enablement
        }
        if (!active(token, modeGeneration)) return@singleFlight cancelledResult()
        val page = pageLauncher.launch()
        if (!active(token, modeGeneration)) return@singleFlight cancelledResult()
        Result(
            if (page == PageResult.UNAVAILABLE) Stage.PAGE_REQUEST_UNAVAILABLE else Stage.PAGE_REQUEST_ACCEPTED,
            isAuthenticatedAdb(),
            enablement?.details.orEmpty() + Detail.PageRequest(page),
            enablement?.mutationWorker,
            enablement?.mutationPath,
            enablement?.stage,
        )
    }

    fun diagnostics(): Result {
        val status = passiveInfo.adbStatus()
        return Result(
            Stage.DIAGNOSTICS,
            status == AdbStatus.CONNECTED_AUTHORIZED,
            listOf(Detail.AdbStatusSnapshot(status)) + passiveInfo.details(),
        )
    }

    @JvmOverloads
    fun restore(expectedModeAuthority: String? = null): Result = singleFlight(expectedModeAuthority) { token, modeGeneration ->
        val rails = capabilities()
        val details = mutableListOf<Detail>()
        var restored = false
        var partial = false
        state.flags().forEach { record ->
            if (!active(token, modeGeneration)) return@singleFlight cancelledResult()
            val candidate = DiLink5AdbWorkerProcess.candidates.firstOrNull {
                it.processName == record.workerProcess
            }
            val prior = record.priorValue
            val expected = record.expectedValue
            if (!rails.accountFile || candidate == null || !workers.installed(candidate) ||
                record.priorState == DiLink5AccountServiceClient.ReadState.UNAVAILABLE ||
                prior == null || expected == null
            ) {
                details += Detail.FlagRestoration(
                    record.workerProcess,
                    record.path,
                    RestorationOutcome.UNSUPPORTED,
                )
                partial = true
            } else {
                if (!active(token, modeGeneration)) return@singleFlight cancelledResult()
                val reply = workers.restore(
                    candidate,
                    expected,
                    prior,
                    nextOperationId(),
                    modeGeneration,
                    { active(token, modeGeneration) },
                )
                if (!active(token, modeGeneration)) return@singleFlight cancelledResult()
                val baselineObserved = reply != null &&
                    restoredFlagValueMatches(reply.after, prior) &&
                    ((reply.conditionalMatched == true && reply.writeResult == 1) ||
                        (reply.conditionalMatched == false && restoredFlagValueMatches(reply.prior, prior)))
                if (baselineObserved) {
                    val cleared = state.clearFlag(record)
                    restored = restored || cleared
                    partial = partial || !cleared
                    details += Detail.FlagRestoration(
                        record.workerProcess,
                        record.path,
                        RestorationOutcome.CONFIRMED,
                    )
                } else {
                    partial = true
                    details += Detail.FlagRestoration(
                        record.workerProcess,
                        record.path,
                        RestorationOutcome.NOT_CONFIRMED,
                    )
                }
            }
        }
        state.strategy()?.let { record ->
            if (!active(token, modeGeneration)) return@singleFlight cancelledResult()
            val expected = record.expectedValue
            if (!rails.strategy) {
                partial = true
                details += Detail.StrategyRestoration(RestorationOutcome.UNSUPPORTED)
            } else if (expected == null) {
                partial = true
                details += Detail.StrategyRestoration(RestorationOutcome.NOT_CONFIRMED)
            } else {
                val reply = strategy.restoreVerifyEnable(
                    expected,
                    record.priorValue,
                ) { active(token, modeGeneration) }
                if (!active(token, modeGeneration)) return@singleFlight cancelledResult()
                val baselineObserved = reply.outcome == DiLink5StrategyServiceClient.Outcome.CONFIRMED ||
                    (reply.priorVerifyEnable == record.priorValue &&
                        reply.readbackVerifyEnable == record.priorValue)
                if (baselineObserved) {
                    val cleared = state.clearStrategy()
                    restored = restored || cleared
                    partial = partial || !cleared
                    details += Detail.StrategyRestoration(RestorationOutcome.CONFIRMED)
                } else {
                    partial = true
                    details += Detail.StrategyRestoration(RestorationOutcome.NOT_CONFIRMED)
                }
            }
        }
        state.withOwnershipSnapshot { snapshot ->
            if (snapshot.propertyMayHaveChanged) {
                partial = true
                details += Detail.PropertyNotRestorable
            }
            if (snapshot.flags.isNotEmpty() || snapshot.strategy != null || !snapshot.durable) {
                partial = true
                snapshot.flags.forEach { details += it.toOwnershipDetail() }
            }
            if (!snapshot.durable) details += Detail.OwnershipNotDurable
            if (!restored && !partial) details += Detail.NoOwnedRestorableChange
            publishIfActive(token, modeGeneration) {
                Result(
                    if (partial) Stage.PARTIAL_RESTORATION else Stage.RESTORED,
                    isAuthenticatedAdb(),
                    details,
                    ownershipDurable = snapshot.durable,
                )
            }
        }
    }

    fun close() {
        cancelCurrent()
        stateObservation.close()
    }

    fun cancelCurrent() {
        generation.incrementAndGet()
    }

    private fun enableInternal(token: Long, modeGeneration: String): Result {
        val rails = capabilities()
        if (!rails.canEnable) return Result(Stage.UNAVAILABLE, isAuthenticatedAdb(), emptyList())
        val details = mutableListOf<Detail>()
        var flagResult: WorkerResult? = null
        for (candidate in DiLink5AdbWorkerProcess.candidates.takeIf { rails.accountFile }.orEmpty()) {
            if (!active(token, modeGeneration)) return cancelledResult()
            if (!workers.installed(candidate)) {
                details += Detail.CandidateAvailability(candidate.packageName, false)
                continue
            }
            if (!active(token, modeGeneration)) return cancelledResult()
            val result = workers.write(
                candidate,
                DiLink5AccountServiceClient.ENABLE_CONTENT,
                nextOperationId(),
                modeGeneration,
                { active(token, modeGeneration) },
                ::recordLateFlagOwnership,
            )
            val ownership = result?.let {
                recordFlagOwnership(
                    it,
                    if ((it.writeResult == 1 || it.replyLostAfterDispatch) &&
                        (it.binderDied || !it.connected)
                    ) {
                        DiLink5AdbEnableStateStore.OwnershipSource.DISCONNECT_RETAINED
                    } else {
                        DiLink5AdbEnableStateStore.OwnershipSource.ORDINARY
                    },
                )
            }
            if (!active(token, modeGeneration)) return cancelledResult()
            if (result == null) {
                details += Detail.WorkerTimeout(candidate.packageName)
                continue
            }
            details += Detail.WorkerAttempt(candidate.packageName, classifyWorkerOutcome(result))
            if (result.writeResult == 1 || result.replyLostAfterDispatch ||
                (result.bound && result.connected && !result.binderDied &&
                    result.after.state == DiLink5AccountServiceClient.ReadState.VALUE &&
                    result.after.value == DiLink5AccountServiceClient.ENABLE_CONTENT) ||
                result.exists == true
            ) {
                flagResult = result
                if (ownership?.source ==
                    DiLink5AdbEnableStateStore.OwnershipSource.DISCONNECT_RETAINED
                ) {
                    details += ownership.toOwnershipDetail()
                }
                break
            }
        }
        if (flagResult == null && rails.accountFile) {
            if (!active(token, modeGeneration)) return cancelledResult()
            workers.inspectMain(nextOperationId(), modeGeneration) {
                active(token, modeGeneration)
            }?.let { inspection ->
                details += Detail.WorkerAttempt(
                    inspection.workerProcess,
                    classifyWorkerOutcome(inspection),
                )
                if (inspection.exists == true) flagResult = inspection
            }
        }
        if (!active(token, modeGeneration)) return cancelledResult()
        if (flagResult != null) {
            val stage = classifyFlag(flagResult)
            // A lost transaction-1 reply is an uncertain mutation boundary. Preserve ownership
            // and stop; do not start a second enablement rail after a write may already have run.
            if (flagResult.replyLostAfterDispatch && flagResult.writeResult != 1) {
                return publishIfActive(token, modeGeneration) {
                    result(stage, details, flagResult)
                }
            }
            if (!active(token, modeGeneration)) return cancelledResult()
            val availability = if (rails.logTool) logTool.probe { !active(token, modeGeneration) }
            else DiLink5LogToolProtocol.Availability.UNAVAILABLE
            when (availability) {
                DiLink5LogToolProtocol.Availability.CANCELLED -> return cancelledResult()
                DiLink5LogToolProtocol.Availability.UNAVAILABLE ->
                    details += Detail.LogToolUnavailable
                DiLink5LogToolProtocol.Availability.AVAILABLE -> {
                    if (!active(token, modeGeneration)) return cancelledResult()
                    val property = logTool.setAdbTcpPort { !active(token, modeGeneration) }
                    if (property.mayHaveChanged()) state.markPropertyMayHaveChanged(true)
                    if (!active(token, modeGeneration) ||
                        property.outcome == DiLink5LogToolProtocol.Outcome.CANCELLED
                    ) {
                        return cancelledResult()
                    }
                    if (!active(token, modeGeneration)) return cancelledResult()
                    val page = logTool.requestDeveloperActivity { !active(token, modeGeneration) }
                    if (!active(token, modeGeneration) ||
                        page.outcome == DiLink5LogToolProtocol.Outcome.CANCELLED
                    ) {
                        return cancelledResult()
                    }
                    details += Detail.PropertyRequest(
                        property.outcome, property.requestSent, property.replyCorrelated,
                    )
                    details += Detail.DeveloperActivityRequest(page.outcome)
                    if (property.outcome == DiLink5LogToolProtocol.Outcome.CONFIRMED &&
                        page.outcome == DiLink5LogToolProtocol.Outcome.CONFIRMED
                    ) {
                        return publishIfActive(token, modeGeneration) {
                            result(Stage.LOG_TOOL_CONFIRMED, details, flagResult)
                        }
                    }
                }
            }
            return publishIfActive(token, modeGeneration) {
                result(stage, details, flagResult)
            }
        }

        if (!active(token, modeGeneration)) return cancelledResult()
        val property = if (rails.logTool) logTool.setAdbTcpPort { !active(token, modeGeneration) }
        else DiLink5LogToolProtocol.RequestResult(
            DiLink5LogToolProtocol.Outcome.UNAVAILABLE,
            detail = "OEM log-tool protocol is unavailable",
        )
        if (property.requestSent) state.markPropertyMayHaveChanged(true)
        if (!active(token, modeGeneration)) return cancelledResult()
        when {
            property.allowsDeveloperNavigation() -> {
                if (!active(token, modeGeneration)) return cancelledResult()
                val page = logTool.requestDeveloperActivity { !active(token, modeGeneration) }
                if (!active(token, modeGeneration) ||
                    page.outcome == DiLink5LogToolProtocol.Outcome.CANCELLED
                ) {
                    return cancelledResult()
                }
                details += Detail.PropertyRequest(
                    property.outcome, property.requestSent, property.replyCorrelated,
                )
                details += Detail.DeveloperActivityRequest(page.outcome)
                return Result(
                    if (property.outcome == DiLink5LogToolProtocol.Outcome.CONFIRMED &&
                        page.outcome == DiLink5LogToolProtocol.Outcome.CONFIRMED) Stage.LOG_TOOL_CONFIRMED
                    else Stage.LOG_TOOL_NOT_CONFIRMED,
                    isAuthenticatedAdb(),
                    details,
                )
            }
            property.outcome == DiLink5LogToolProtocol.Outcome.UNAVAILABLE -> {
                if (property.requestSent) {
                    details += Detail.PropertyRequest(property.outcome, true)
                    return Result(
                        Stage.LOG_TOOL_NOT_CONFIRMED,
                        isAuthenticatedAdb(),
                        details,
                    )
                }
                details += Detail.LogToolUnavailable
                details += Detail.StrategyFallback
            }
            property.outcome == DiLink5LogToolProtocol.Outcome.CANCELLED -> return cancelledResult()
            else -> {
                details += Detail.PropertyRequest(
                    property.outcome, property.requestSent, property.replyCorrelated,
                )
                return Result(Stage.LOG_TOOL_NOT_CONFIRMED, isAuthenticatedAdb(), details)
            }
        }

        if (!active(token, modeGeneration)) return cancelledResult()
        if (!rails.strategy) return Result(Stage.UNAVAILABLE, isAuthenticatedAdb(), details)
        val strategyResult = strategy.setVerifyDisabled {
            active(token, modeGeneration)
        }
        strategyResult.priorVerifyEnable?.let { prior ->
            if (prior != STRATEGY_DISABLED_VALUE &&
                strategyResult.outcome != DiLink5StrategyServiceClient.Outcome.UNAVAILABLE &&
                strategyResult.outcome != DiLink5StrategyServiceClient.Outcome.NOT_DISPATCHED &&
                strategyResult.outcome != DiLink5StrategyServiceClient.Outcome.CANCELLED
            ) {
                state.saveStrategy(DiLink5AdbEnableStateStore.StrategyMutation(
                    prior,
                    STRATEGY_DISABLED_VALUE,
                ))
            }
        }
        if (!active(token, modeGeneration)) return cancelledResult()
        details += Detail.StrategyChange(strategyResult.outcome)
        return Result(
            if (strategyResult.outcome == DiLink5StrategyServiceClient.Outcome.CONFIRMED) {
                Stage.STRATEGY_CONFIRMED
            } else {
                Stage.STRATEGY_NOT_CONFIRMED
            },
            isAuthenticatedAdb(),
            details,
        )
    }

    private fun restoredFlagValueMatches(
        read: DiLink5AccountServiceClient.ReadResult,
        expected: String,
    ): Boolean = if (expected.isEmpty()) {
        read.state == DiLink5AccountServiceClient.ReadState.EMPTY && read.value.orEmpty().isEmpty()
    } else {
        read.state == DiLink5AccountServiceClient.ReadState.VALUE && read.value == expected
    }

    private fun recordFlagOwnership(
        result: WorkerResult,
        source: DiLink5AdbEnableStateStore.OwnershipSource,
    ): DiLink5AdbEnableStateStore.FlagMutation? {
        if (result.prior.state == DiLink5AccountServiceClient.ReadState.VALUE &&
            result.prior.value == DiLink5AccountServiceClient.ENABLE_CONTENT
        ) return null
        val certainty = when {
            result.writeResult == 1 -> DiLink5AdbEnableStateStore.MutationCertainty.CONFIRMED
            result.replyLostAfterDispatch || isUncertainFlagTransition(result) ->
                DiLink5AdbEnableStateStore.MutationCertainty.MAY_HAVE_CHANGED
            else -> return null
        }
        return DiLink5AdbEnableStateStore.FlagMutation(
            workerProcess = result.workerProcess,
            path = DiLink5AccountServiceClient.FLAG_PATH,
            priorState = result.prior.state,
            priorValue = result.prior.value,
            expectedValue = DiLink5AccountServiceClient.ENABLE_CONTENT,
            certainty = certainty,
            source = source,
        ).also { state.saveFlag(it) }
    }

    private fun recordLateFlagOwnership(result: WorkerResult) {
        recordFlagOwnership(
            result,
            DiLink5AdbEnableStateStore.OwnershipSource.LATE_RESULT,
        )
    }

    private fun DiLink5AdbEnableStateStore.FlagMutation.toOwnershipDetail() =
        Detail.FlagOwnership(workerProcess, path, certainty, source)

    private fun isUncertainFlagTransition(result: WorkerResult): Boolean =
        result.writeResult != 1 &&
            result.prior.state != DiLink5AccountServiceClient.ReadState.UNAVAILABLE &&
            result.prior.value != null &&
            result.prior.value != DiLink5AccountServiceClient.ENABLE_CONTENT &&
            result.after.state == DiLink5AccountServiceClient.ReadState.VALUE &&
            result.after.value == DiLink5AccountServiceClient.ENABLE_CONTENT

    private fun DiLink5LogToolProtocol.RequestResult.mayHaveChanged(): Boolean =
        requestSent || outcome == DiLink5LogToolProtocol.Outcome.CONFIRMED ||
            outcome == DiLink5LogToolProtocol.Outcome.REJECTED ||
            outcome == DiLink5LogToolProtocol.Outcome.UNKNOWN_REPLY

    /** The reference follows a live reply with a page request, without proving the property write. */
    private fun DiLink5LogToolProtocol.RequestResult.allowsDeveloperNavigation(): Boolean =
        outcome == DiLink5LogToolProtocol.Outcome.CONFIRMED ||
            (outcome == DiLink5LogToolProtocol.Outcome.UNKNOWN_REPLY && requestSent && replyCorrelated)

    private fun classifyFlag(result: WorkerResult): Stage = when {
        result.writeResult == 1 && result.after.state == DiLink5AccountServiceClient.ReadState.VALUE &&
            result.after.value == DiLink5AccountServiceClient.ENABLE_CONTENT -> Stage.FLAG_CONFIRMED
        result.writeResult == 1 -> Stage.FLAG_ACCEPTED
        result.replyLostAfterDispatch -> Stage.FLAG_REPLY_LOST
        isUncertainFlagTransition(result) -> Stage.FLAG_UNCERTAIN_TRANSITION
        result.after.state == DiLink5AccountServiceClient.ReadState.VALUE &&
            result.after.value == DiLink5AccountServiceClient.ENABLE_CONTENT -> Stage.FLAG_ALREADY_ENABLED
        result.exists == true && result.after.state == DiLink5AccountServiceClient.ReadState.VALUE &&
            result.after.value == "0" -> Stage.FLAG_CONTENT_ZERO
        else -> Stage.FLAG_EXISTS_ONLY
    }

    private fun classifyWorkerOutcome(result: WorkerResult): WorkerOutcome = when {
        result.writeResult == 1 && result.after.state == DiLink5AccountServiceClient.ReadState.VALUE &&
            result.after.value == DiLink5AccountServiceClient.ENABLE_CONTENT ->
            WorkerOutcome.WRITE_AND_VALUE_CONFIRMED
        result.writeResult == 1 -> WorkerOutcome.WRITE_ACCEPTED
        result.replyLostAfterDispatch -> WorkerOutcome.WRITE_REPLY_LOST
        isUncertainFlagTransition(result) -> WorkerOutcome.UNCERTAIN_TRANSITION
        result.after.state == DiLink5AccountServiceClient.ReadState.VALUE &&
            result.after.value == DiLink5AccountServiceClient.ENABLE_CONTENT ->
            WorkerOutcome.ALREADY_ENABLED
        result.exists == true && result.after.state == DiLink5AccountServiceClient.ReadState.VALUE &&
            result.after.value == "0" -> WorkerOutcome.CONTENT_ZERO
        result.exists == true -> WorkerOutcome.EXISTS_VALUE_UNKNOWN
        !result.bound || !result.connected || result.binderDied -> WorkerOutcome.TRANSPORT_FAILED
        else -> WorkerOutcome.WRITE_NOT_CONFIRMED
    }

    private fun result(stage: Stage, details: List<Detail>, flag: WorkerResult): Result {
        val ownsFlag = state.flags().any {
            it.workerProcess == flag.workerProcess &&
                it.path == DiLink5AccountServiceClient.FLAG_PATH
        }
        return Result(
            stage,
            passiveInfo.adbStatus() == AdbStatus.CONNECTED_AUTHORIZED,
            details,
            if (ownsFlag) flag.workerProcess else null,
            if (ownsFlag) DiLink5AccountServiceClient.FLAG_PATH else null,
        )
    }

    private fun publishIfActive(
        token: Long,
        modeGeneration: String,
        result: () -> Result,
    ): Result = publishIfCurrent(
        current = { active(token, modeGeneration) },
        build = result,
        cancelled = ::cancelledResult,
    )

    private fun cancelledResult() = Result(
        Stage.CANCELLED,
        isAuthenticatedAdb(),
        emptyList(),
    )

    private fun singleFlight(expectedModeAuthority: String?, block: (Long, String) -> Result): Result {
        if (!running.compareAndSet(false, true)) {
            return Result(
                Stage.BUSY,
                isAuthenticatedAdb(),
                emptyList(),
            )
        }
        val token = generation.incrementAndGet()
        return try {
            val modeGeneration = expectedModeAuthority ?: modeAuthority.admit() ?: return Result(
                Stage.UNAVAILABLE,
                isAuthenticatedAdb(),
                emptyList(),
            )
            if (!active(token, modeGeneration)) return cancelledResult()
            val result = block(token, modeGeneration)
            state.withOwnershipSnapshot { snapshot ->
                val remaining = snapshot.flags.isNotEmpty() || snapshot.strategy != null ||
                    snapshot.propertyMayHaveChanged || !snapshot.durable
                val newlyPartial = result.stage == Stage.RESTORED && remaining
                val details = result.details.filterNot {
                    newlyPartial && it == Detail.NoOwnedRestorableChange
                } + (if (newlyPartial) snapshot.flags.map { it.toOwnershipDetail() } else emptyList()) +
                    (if (snapshot.durable) emptyList() else listOf(Detail.OwnershipNotDurable))
                publishIfCurrent(
                    current = { active(token, modeGeneration) },
                    build = {
                        result.copy(
                            stage = if (newlyPartial) Stage.PARTIAL_RESTORATION else result.stage,
                            details = details.distinct(),
                            ownershipDurable = result.ownershipDurable && snapshot.durable,
                        )
                    },
                    cancelled = {
                        cancelledResult().copy(
                            details = if (snapshot.durable) emptyList() else listOf(Detail.OwnershipNotDurable),
                            ownershipDurable = snapshot.durable,
                        )
                    },
                )
            }
        } finally {
            // Retire dispatch authority before another operation can become running.
            // Late replies still retain ownership through recordLateFlagOwnership.
            generation.compareAndSet(token, token + 1)
            running.set(false)
        }
    }

    private fun cancelled(token: Long): Boolean = generation.get() != token

    private fun active(token: Long, modeGeneration: String): Boolean =
        running.get() && !cancelled(token) && modeAuthority.isCurrent(modeGeneration)

    private fun isAuthenticatedAdb(): Boolean =
        passiveInfo.adbStatus() == AdbStatus.CONNECTED_AUTHORIZED

    companion object {
        private const val STRATEGY_DISABLED_VALUE = "0"
        internal const val CONFIGURED_DILINK5_AUTHORITY = "configured:dilink5"
        internal const val PENDING_DILINK5_AUTHORITY_PREFIX = "pending:dilink5:"
        private val OPERATION_IDS = AtomicLong(0)
        private fun nextOperationId(): Long = OPERATION_IDS.incrementAndGet()

        fun forContext(
            context: Context,
            detailObserver: (Detail) -> Unit = {},
        ): DiLink5AdbEnableCoordinator {
            val app = context.applicationContext
            val store = DiLink5AdbEnableStateStore.forContext(app)
            val protocol = DiLink5LogToolProtocol(app)
            val strategyClient = DiLink5StrategyServiceClient.forContext(app)
            return DiLink5AdbEnableCoordinator(
                AndroidWorkerGateway(app),
                object : LogToolGateway {
                    override fun probe(cancelled: () -> Boolean) =
                        protocol.probe(DiLink5LogToolProtocol.Cancellation(cancelled))
                    override fun setAdbTcpPort(cancelled: () -> Boolean) =
                        protocol.setAdbTcpPort(DiLink5LogToolProtocol.Cancellation(cancelled))
                    override fun requestDeveloperActivity(cancelled: () -> Boolean) =
                        protocol.requestDeveloperActivity(DiLink5LogToolProtocol.Cancellation(cancelled))
                },
                object : StrategyGateway {
                    override fun readVerifyEnable() = strategyClient.readVerifyEnable()
                    override fun setVerifyDisabled(isCurrent: () -> Boolean) =
                        strategyClient.setVerifyDisabled(isCurrent)
                    override fun restoreVerifyEnable(
                        expectedCurrent: String,
                        value: String,
                        isCurrent: () -> Boolean,
                    ) = strategyClient.restoreVerifyEnable(expectedCurrent, value, isCurrent)
                },
                object : StateGateway {
                    override fun saveFlag(record: DiLink5AdbEnableStateStore.FlagMutation) = store.saveFlagMutation(record)
                    override fun flags() = store.flagMutations()
                    override fun clearFlag(record: DiLink5AdbEnableStateStore.FlagMutation) = store.clearFlagMutation(record)
                    override fun saveStrategy(record: DiLink5AdbEnableStateStore.StrategyMutation) =
                        store.saveStrategyMutation(record)
                    override fun strategy() = store.strategyMutation()
                    override fun clearStrategy() = store.clearStrategyMutation()
                    override fun markPropertyMayHaveChanged(changed: Boolean) = store.markPropertyMayHaveChanged(changed)
                    override fun propertyMayHaveChanged() = store.propertyMayHaveChanged()
                    override fun ownershipDurable() = store.ownershipDurable()
                    override fun <T> withOwnershipSnapshot(
                        block: (DiLink5AdbEnableStateStore.OwnershipSnapshot) -> T,
                    ): T = store.withOwnershipSnapshot(block)
                    override fun observeFlagOwnership(
                        observer: (DiLink5AdbEnableStateStore.FlagMutation) -> Unit,
                    ) = store.observeFlagMutations(observer)
                },
                AndroidPageLauncher(app),
                AndroidPassiveInfoProvider(app),
                BydManualAdbModeAuthority(),
                { BydManualAdbCapabilities.forContext(app) },
                detailObserver,
            )
        }
    }

    private class AndroidWorkerGateway(private val context: Context) : WorkerGateway {
        override fun installed(candidate: DiLink5AdbWorkerProcess.Candidate): Boolean = try {
            context.packageManager.getApplicationInfo(candidate.packageName, 0)
            true
        } catch (_: Throwable) {
            false
        }

        override fun inspectMain(
            operationId: Long,
            modeGeneration: String,
            authority: () -> Boolean,
        ): WorkerResult? =
            dispatchInitialInspection(operationId) { candidate, id ->
                dispatch(
                    candidate,
                    DiLink5AdbWorkerService.OP_INSPECT,
                    null,
                    null,
                    id,
                    modeGeneration,
                    authority,
                )
            }

        override fun inspect(
            candidate: DiLink5AdbWorkerProcess.Candidate,
            operationId: Long,
            modeGeneration: String,
            authority: () -> Boolean,
        ) = dispatch(
            candidate, DiLink5AdbWorkerService.OP_INSPECT, null, null, operationId,
            modeGeneration, authority,
        )

        override fun write(
            candidate: DiLink5AdbWorkerProcess.Candidate,
            content: String,
            operationId: Long,
            modeGeneration: String,
            authority: () -> Boolean,
            onLateResult: (WorkerResult) -> Unit,
        ) = dispatch(
            candidate,
            DiLink5AdbWorkerService.OP_WRITE,
            content,
            null,
            operationId,
            modeGeneration,
            authority,
            onLateResult,
        )

        override fun restore(
            candidate: DiLink5AdbWorkerProcess.Candidate,
            expectedCurrent: String,
            restoreValue: String,
            operationId: Long,
            modeGeneration: String,
            authority: () -> Boolean,
        ) = dispatch(
            candidate,
            DiLink5AdbWorkerService.OP_RESTORE,
            restoreValue,
            expectedCurrent,
            operationId,
            modeGeneration,
            authority,
        )

        private fun dispatch(
            candidate: DiLink5AdbWorkerProcess.Candidate,
            operation: String,
            content: String?,
            expectedCurrent: String?,
            operationId: Long,
            modeGeneration: String? = null,
            authority: (() -> Boolean)? = null,
            onLateResult: (WorkerResult) -> Unit = {},
        ): WorkerResult? = DISPATCHER.dispatch(
            candidate.processName,
            operationId,
            onLateResult,
        ) { complete ->
            val receiver = object : ResultReceiver(Handler(context.mainLooper)) {
                override fun onReceiveResult(resultCode: Int, resultData: Bundle?) {
                    val returnedId = resultData?.getLong(DiLink5AdbWorkerService.EXTRA_OPERATION_ID, -1L)
                        ?: -1L
                    val value = if (resultCode == DiLink5AdbWorkerService.RESULT_OK) {
                        resultData?.toWorkerResult()
                    } else {
                        null
                    }
                    complete(returnedId, value)
                }
            }
            val intent = Intent(context, candidate.serviceClass)
                .setAction(DiLink5AdbWorkerService.ACTION_RUN)
                .putExtras(Bundle().apply {
                    putBinder(
                        DiLink5AdbWorkerService.EXTRA_OPERATION_AUTHORITY,
                        authority?.let(DiLink5AdbWorkerAuthority::create),
                    )
                })
                .putExtra(DiLink5AdbWorkerService.EXTRA_OPERATION_ID, operationId)
                .putExtra(DiLink5AdbWorkerService.EXTRA_OPERATION, operation)
                .putExtra(DiLink5AdbWorkerService.EXTRA_CONTENT, content)
                .putExtra(DiLink5AdbWorkerService.EXTRA_EXPECTED_CURRENT, expectedCurrent)
                .putExtra(DiLink5AdbWorkerService.EXTRA_MODE_GENERATION, modeGeneration)
                .putExtra(DiLink5AdbWorkerService.EXTRA_RESULT_RECEIVER, receiver)
            try {
                context.startService(intent)
                true
            } catch (_: Throwable) {
                false
            }
        }

        private fun DiLink5AccountServiceClient.Result.toWorkerResult(
            workerProcess: String,
        ) = WorkerResult(
            workerProcess,
            bound,
            connected,
            binderDied,
            prior,
            writeResult,
            exists,
            after,
            detail,
            conditionalMatched,
            writeDispatched,
            replyLostAfterDispatch,
        )

        private fun Bundle.toWorkerResult(): WorkerResult {
            fun readResult(stateKey: String, valueKey: String): DiLink5AccountServiceClient.ReadResult {
                val state = try {
                    DiLink5AccountServiceClient.ReadState.valueOf(
                        getString(stateKey) ?: DiLink5AccountServiceClient.ReadState.UNAVAILABLE.name,
                    )
                } catch (_: IllegalArgumentException) {
                    DiLink5AccountServiceClient.ReadState.UNAVAILABLE
                }
                return DiLink5AccountServiceClient.ReadResult(state, getString(valueKey))
            }
            return WorkerResult(
                getString(DiLink5AdbWorkerService.KEY_WORKER).orEmpty(),
                getBoolean(DiLink5AdbWorkerService.KEY_BOUND),
                getBoolean(DiLink5AdbWorkerService.KEY_CONNECTED),
                getBoolean(DiLink5AdbWorkerService.KEY_BINDER_DIED),
                readResult(DiLink5AdbWorkerService.KEY_PRIOR_STATE, DiLink5AdbWorkerService.KEY_PRIOR_VALUE),
                if (getBoolean(DiLink5AdbWorkerService.KEY_WRITE_PRESENT)) getInt(DiLink5AdbWorkerService.KEY_WRITE_RESULT) else null,
                if (getBoolean(DiLink5AdbWorkerService.KEY_EXISTS_PRESENT)) getBoolean(DiLink5AdbWorkerService.KEY_EXISTS) else null,
                readResult(DiLink5AdbWorkerService.KEY_AFTER_STATE, DiLink5AdbWorkerService.KEY_AFTER_VALUE),
                getString(DiLink5AdbWorkerService.KEY_DETAIL).orEmpty(),
                if (getBoolean(DiLink5AdbWorkerService.KEY_CONDITIONAL_PRESENT)) {
                    getBoolean(DiLink5AdbWorkerService.KEY_CONDITIONAL_MATCHED)
                } else {
                    null
                },
                getBoolean(DiLink5AdbWorkerService.KEY_WRITE_DISPATCHED),
                getBoolean(DiLink5AdbWorkerService.KEY_REPLY_LOST_AFTER_DISPATCH),
            )
        }

        companion object {
            private const val WORKER_TIMEOUT_MS = 12_000L
            private val DISPATCHER = DiLink5WorkerResultDispatcher<WorkerResult>(WORKER_TIMEOUT_MS)
        }
    }

    private class AndroidPageLauncher(private val context: Context) : PageLauncher {
        override fun launch(): PageResult {
            val uri = Uri.parse("tel:" + Uri.encode("*#91532547#*"))
            val targeted = Intent(Intent.ACTION_DIAL, uri)
                .setPackage("com.byd.bluetoothcall")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                context.startActivity(targeted)
                return PageResult.TARGETED_INTENT_ACCEPTED
            } catch (_: Throwable) {
            }
            return try {
                context.startActivity(Intent(Intent.ACTION_DIAL, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                PageResult.GENERIC_INTENT_ACCEPTED
            } catch (_: Throwable) {
                PageResult.UNAVAILABLE
            }
        }
    }

    private class AndroidPassiveInfoProvider(private val context: Context) : PassiveInfoProvider {
        override fun adbStatus(): AdbStatus = when {
            AdbShellExecutor.isConnected() -> AdbStatus.CONNECTED_AUTHORIZED
            AdbShellExecutor.isAuthPending() -> AdbStatus.AUTHORIZATION_PENDING
            AdbShellExecutor.wasDiLink5PortClosed() -> AdbStatus.PORT_CLOSED
            else -> AdbStatus.DISCONNECTED
        }

        override fun details(): List<Detail> {
            val packages = DiLink5AdbWorkerProcess.candidates.map { candidate ->
                val installed = try {
                    context.packageManager.getApplicationInfo(candidate.packageName, 0)
                    true
                } catch (_: Throwable) {
                    false
                }
                Detail.PackageAvailability(candidate.packageName, installed)
            }
            return listOf(
                Detail.DeviceSnapshot(
                    android.os.Build.MODEL,
                    android.os.Build.DISPLAY,
                    android.os.Build.VERSION.SDK_INT,
                ),
            ) + packages
        }
    }
}

internal fun <T> publishIfCurrent(
    current: () -> Boolean,
    build: () -> T,
    cancelled: () -> T,
): T {
    if (!current()) return cancelled()
    val result = build()
    return if (current()) result else cancelled()
}

internal fun <T> dispatchInitialInspection(
    operationId: Long,
    dispatch: (DiLink5AdbWorkerProcess.Candidate, Long) -> T,
): T = dispatch(DiLink5AdbWorkerProcess.initialInspectionCandidate, operationId)

internal fun initialEnablementAuthority(
    committedMarkerPresent: Boolean,
    activeGeneration: String?,
    configuredWithoutMarker: Boolean,
    pendingGeneration: String?,
): String? = when {
    activeGeneration != null -> activeGeneration
    pendingGeneration != null -> pendingInitialEnablementAuthority(pendingGeneration)
    committedMarkerPresent -> null
    configuredWithoutMarker -> DiLink5AdbEnableCoordinator.CONFIGURED_DILINK5_AUTHORITY
    else -> null
}

internal fun pendingInitialEnablementAuthority(generation: String): String =
    DiLink5AdbEnableCoordinator.PENDING_DILINK5_AUTHORITY_PREFIX + generation

internal fun pendingGenerationFromAuthority(authority: String): String? = authority
    .takeIf { it.startsWith(DiLink5AdbEnableCoordinator.PENDING_DILINK5_AUTHORITY_PREFIX) }
    ?.removePrefix(DiLink5AdbEnableCoordinator.PENDING_DILINK5_AUTHORITY_PREFIX)
    ?.takeIf { it.isNotEmpty() }
