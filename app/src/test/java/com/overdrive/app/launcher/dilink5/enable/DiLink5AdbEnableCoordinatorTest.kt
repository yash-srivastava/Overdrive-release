package com.overdrive.app.launcher.dilink5.enable

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.Socket
import java.net.SocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DiLink5AdbEnableCoordinatorTest {
    @Test
    fun manualApisRetainJavaNoArgumentEntryPoints() {
        listOf("enable", "requestDeveloperPage", "restore").forEach { name ->
            assertEquals(0, DiLink5AdbEnableCoordinator::class.java.getDeclaredMethod(name).parameterCount)
        }
    }

    @Test
    fun configuredInitialEnablementWorksOnlyWithoutACommittedMarker() {
        assertEquals(
            DiLink5AdbEnableCoordinator.CONFIGURED_DILINK5_AUTHORITY,
            initialEnablementAuthority(false, null, true, null),
        )
        assertEquals("generation", initialEnablementAuthority(true, "generation", true, "staged"))
        assertEquals(
            pendingInitialEnablementAuthority("staged"),
            initialEnablementAuthority(true, null, false, "staged"),
        )
        assertEquals("staged", pendingGenerationFromAuthority(
            pendingInitialEnablementAuthority("staged")))
        assertEquals(null, pendingGenerationFromAuthority("pending:dilink5:other".removeSuffix("other")))
        assertEquals(null, initialEnablementAuthority(true, null, true, null))
        assertEquals(null, initialEnablementAuthority(false, null, false, null))
    }

    @Test
    fun finalFlagPublicationRechecksAuthorityAfterResultConstruction() {
        var current = true
        val published = publishIfCurrent(
            current = { current },
            build = {
                current = false
                "stale"
            },
            cancelled = { "cancelled" },
        )
        assertEquals("cancelled", published)
    }

    @Test
    fun confirmedFlagStopsCandidateTraversalAndRecordsExactRestorationOwner() {
        val worker = FakeWorkers().apply {
            installed += firstCandidate.processName
            writes += workerResult(write = 1, exists = true, prior = "0", after = "1")
        }
        val state = FakeState()
        val strategy = FakeStrategy()
        val coordinator = coordinator(worker, FakeLog(), strategy, state)

        val result = coordinator.enable()

        assertEquals(DiLink5AdbEnableCoordinator.Stage.FLAG_CONFIRMED, result.stage)
        assertEquals(firstCandidate.processName, result.mutationWorker)
        assertEquals("/collect2/autotest", result.mutationPath)
        assertEquals(firstCandidate.processName, state.flags.single().workerProcess)
        assertEquals("0", state.flags.single().priorValue)
        assertEquals(1, worker.writeCalls)
        assertEquals(0, strategy.setCalls)
        assertFalse(result.authenticatedAdb)
    }

    @Test
    fun definiteRejectedContentZeroStopsOnTheObservedFlagBranch() {
        val worker = FakeWorkers().apply {
            installed += firstCandidate.processName
            writes += workerResult(write = 0, exists = true, prior = null, after = "0")
        }
        val state = FakeState()
        val strategy = FakeStrategy()
        val log = FakeLog()

        val result = coordinator(worker, log, strategy, state).enable()

        assertEquals(DiLink5AdbEnableCoordinator.Stage.FLAG_CONTENT_ZERO, result.stage)
        assertTrue(result.details.contains(
            DiLink5AdbEnableCoordinator.Detail.WorkerAttempt(
                firstCandidate.packageName,
                DiLink5AdbEnableCoordinator.WorkerOutcome.CONTENT_ZERO,
            ),
        ))
        assertEquals(1, log.calls)
        assertEquals(0, strategy.setCalls)
        assertEquals(null, result.mutationWorker)
        assertEquals(null, result.mutationPath)
        assertTrue(state.flags.isEmpty())
    }

    @Test
    fun finalPrivateWorkerExistenceCheckRunsWhenOemPackageIsAbsent() {
        val workers = FakeWorkers().apply {
            mainInspections += workerResult(
                write = null,
                exists = true,
                prior = null,
                after = null,
            ).copy(workerProcess = DiLink5AdbWorkerProcess.initialInspectionCandidate.processName)
        }
        val strategy = FakeStrategy()
        val log = FakeLog()

        val result = coordinator(workers, log, strategy, FakeState()).enable()

        assertEquals(DiLink5AdbEnableCoordinator.Stage.FLAG_EXISTS_ONLY, result.stage)
        assertTrue(workers.installed.isEmpty())
        assertEquals(1, workers.inspectMainCalls)
        assertEquals(1, log.calls)
        assertEquals(0, strategy.setCalls)
        assertEquals(null, result.mutationWorker)
        assertEquals(null, result.mutationPath)
    }

    @Test
    fun openPageKeepsDecodedExistenceAfterDisconnect() {
        val workers = FakeWorkers().apply {
            installed += firstCandidate.processName
            inspections += workerResult(
                write = null,
                exists = true,
                prior = null,
                after = null,
            ).copy(connected = false, binderDied = true)
        }
        val page = FakePage().apply {
            result = DiLink5AdbEnableCoordinator.PageResult.TARGETED_INTENT_ACCEPTED
        }
        val strategy = FakeStrategy()

        val result = coordinator(
            workers,
            FakeLog(),
            strategy,
            FakeState(),
            page,
        ).requestDeveloperPage()

        assertEquals(DiLink5AdbEnableCoordinator.Stage.PAGE_REQUEST_ACCEPTED, result.stage)
        assertEquals(0, workers.writeCalls)
        assertEquals(0, strategy.setCalls)
        assertEquals(1, page.calls)
    }

    @Test
    fun decodedExistenceSurvivesBinderDeathDuringLaterReadback() {
        val worker = FakeWorkers().apply {
            installed += firstCandidate.processName
            writes += workerResult(write = 0, exists = true, prior = "0", after = null)
                .copy(connected = false, binderDied = true)
        }
        val strategy = FakeStrategy()
        val log = FakeLog()

        val result = coordinator(worker, log, strategy, FakeState()).enable()

        assertEquals(DiLink5AdbEnableCoordinator.Stage.FLAG_EXISTS_ONLY, result.stage)
        assertEquals(1, worker.writeCalls)
        assertEquals(1, log.calls)
        assertEquals(0, strategy.setCalls)
    }

    @Test
    fun definiteRejectedReplyWithoutFlagEvidenceContinuesCandidateTraversal() {
        val workers = FakeWorkers().apply {
            installed += DiLink5AdbWorkerProcess.candidates.take(2).map { it.processName }
            writes += workerResult(write = 0, exists = false, prior = "0", after = "0")
            writes += workerResult(write = 1, exists = true, prior = "0", after = "1")
                .copy(workerProcess = DiLink5AdbWorkerProcess.candidates[1].processName)
        }

        val result = coordinator(workers, FakeLog(), FakeStrategy(), FakeState()).enable()

        assertEquals(DiLink5AdbEnableCoordinator.Stage.FLAG_CONFIRMED, result.stage)
        assertEquals(DiLink5AdbWorkerProcess.candidates[1].processName, result.mutationWorker)
        assertEquals(2, workers.writeCalls)
    }

    @Test
    fun lostWriteReplyRetainsPartialOwnershipAndStartsNoSecondMutationRail() {
        val worker = FakeWorkers().apply {
            installed += firstCandidate.processName
            writes += workerResult(
                write = null,
                exists = null,
                prior = null,
                after = null,
                replyLostAfterDispatch = true,
            )
        }
        val log = FakeLog().apply { availability = DiLink5LogToolProtocol.Availability.AVAILABLE }
        val strategy = FakeStrategy()
        val state = FakeState()

        val result = coordinator(worker, log, strategy, state).enable()

        assertEquals(DiLink5AdbEnableCoordinator.Stage.FLAG_REPLY_LOST, result.stage)
        assertEquals(0, log.calls)
        assertEquals(0, strategy.setCalls)
        assertEquals(
            DiLink5AdbEnableStateStore.MutationCertainty.MAY_HAVE_CHANGED,
            state.flags.single().certainty,
        )
        assertEquals(DiLink5AccountServiceClient.ReadState.UNAVAILABLE, state.flags.single().priorState)
    }

    @Test
    fun readableTransitionAfterUnconfirmedWriteRetainsUncertainRestorationOwner() {
        val worker = FakeWorkers().apply {
            installed += firstCandidate.processName
            writes += workerResult(write = 0, exists = true, prior = "0", after = "1")
        }
        val state = FakeState()

        val result = coordinator(worker, FakeLog(), FakeStrategy(), state).enable()

        assertEquals(DiLink5AdbEnableCoordinator.Stage.FLAG_UNCERTAIN_TRANSITION, result.stage)
        assertEquals(firstCandidate.processName, result.mutationWorker)
        assertEquals("0", state.flags.single().priorValue)
        assertEquals(
            DiLink5AdbEnableStateStore.MutationCertainty.MAY_HAVE_CHANGED,
            state.flags.single().certainty,
        )
        assertTrue(result.details.any {
            it == DiLink5AdbEnableCoordinator.Detail.WorkerAttempt(
                firstCandidate.packageName,
                DiLink5AdbEnableCoordinator.WorkerOutcome.UNCERTAIN_TRANSITION,
            )
        })
    }

    @Test
    fun emptyAndNonzeroTransitionsAfterUnconfirmedWritesRetainExactPriorContent() {
        listOf("", "legacy").forEach { prior ->
            val worker = FakeWorkers().apply {
                installed += firstCandidate.processName
                writes += workerResult(write = 0, exists = true, prior = prior, after = "1")
            }
            val state = FakeState()

            val result = coordinator(worker, FakeLog(), FakeStrategy(), state).enable()

            assertEquals(DiLink5AdbEnableCoordinator.Stage.FLAG_UNCERTAIN_TRANSITION, result.stage)
            assertEquals(prior, state.flags.single().priorValue)
            assertEquals(
                DiLink5AdbEnableStateStore.MutationCertainty.MAY_HAVE_CHANGED,
                state.flags.single().certainty,
            )
        }
    }

    @Test
    fun readableExistingContentOneIsReportedWithoutClaimingAWrite() {
        val worker = FakeWorkers().apply {
            installed += firstCandidate.processName
            writes += workerResult(write = 0, exists = true, prior = "1", after = "1")
        }
        val state = FakeState()

        val result = coordinator(worker, FakeLog(), FakeStrategy(), state).enable()

        assertEquals(DiLink5AdbEnableCoordinator.Stage.FLAG_ALREADY_ENABLED, result.stage)
        assertTrue(result.details.any {
            it == DiLink5AdbEnableCoordinator.Detail.WorkerAttempt(
                firstCandidate.packageName,
                DiLink5AdbEnableCoordinator.WorkerOutcome.ALREADY_ENABLED,
            )
        })
        assertTrue(state.flags.isEmpty())
    }

    @Test
    fun acceptedIdempotentFlagWriteDoesNotClaimRestorationOwnership() {
        val worker = FakeWorkers().apply {
            installed += firstCandidate.processName
            writes += workerResult(write = 1, exists = true, prior = "1", after = "1")
        }
        val state = FakeState()

        val result = coordinator(worker, FakeLog(), FakeStrategy(), state).enable()

        assertEquals(DiLink5AdbEnableCoordinator.Stage.FLAG_CONFIRMED, result.stage)
        assertTrue(state.flags.isEmpty())
        assertTrue(result.details.none { it is DiLink5AdbEnableCoordinator.Detail.FlagOwnership })
    }

    @Test
    fun preDispatchStrategyFailureCreatesNoRestorationOwner() {
        val strategy = object : DiLink5AdbEnableCoordinator.StrategyGateway {
            override fun readVerifyEnable(): String? = "1"
            override fun setVerifyDisabled(isCurrent: () -> Boolean) =
                DiLink5StrategyServiceClient.Result(
                    DiLink5StrategyServiceClient.Outcome.NOT_DISPATCHED,
                    "1",
                    "1",
                    null,
                    "method unavailable",
                )
            override fun restoreVerifyEnable(
                expectedCurrent: String,
                value: String,
                isCurrent: () -> Boolean,
            ) = error("not called")
        }
        val state = FakeState()

        val result = coordinator(FakeWorkers(), FakeLog(), strategy, state).enable()

        assertEquals(DiLink5AdbEnableCoordinator.Stage.STRATEGY_NOT_CONFIRMED, result.stage)
        assertEquals(null, state.strategy)
    }

    @Test
    fun alreadyDisabledStrategyDoesNotClaimRestorationOwnership() {
        val strategy = FakeStrategy().apply { prior = "0" }
        val state = FakeState()

        val result = coordinator(FakeWorkers(), FakeLog(), strategy, state).enable()

        assertEquals(DiLink5AdbEnableCoordinator.Stage.STRATEGY_CONFIRMED, result.stage)
        assertEquals(null, state.strategy)
    }

    @Test
    fun deathAfterConfirmedWriteStopsTraversalAndRetainsOwnership() {
        val worker = FakeWorkers().apply {
            installed += DiLink5AdbWorkerProcess.candidates.take(2).map { it.processName }
            writes += workerResult(write = 1, exists = null, prior = "0", after = null)
                .copy(connected = false, binderDied = true)
            writes += workerResult(write = 1, exists = true, prior = "0", after = "1")
                .copy(workerProcess = DiLink5AdbWorkerProcess.candidates[1].processName)
        }
        val state = FakeState()

        val result = coordinator(worker, FakeLog(), FakeStrategy(), state).enable()

        assertEquals(DiLink5AdbEnableCoordinator.Stage.FLAG_ACCEPTED, result.stage)
        assertEquals(firstCandidate.processName, result.mutationWorker)
        assertEquals(firstCandidate.processName, state.flags.single().workerProcess)
        assertEquals("0", state.flags.single().priorValue)
        assertEquals(1, worker.writeCalls)
        assertTrue(result.details.any {
            it == DiLink5AdbEnableCoordinator.Detail.FlagOwnership(
                firstCandidate.processName,
                DiLink5AccountServiceClient.FLAG_PATH,
                DiLink5AdbEnableStateStore.MutationCertainty.CONFIRMED,
                DiLink5AdbEnableStateStore.OwnershipSource.DISCONNECT_RETAINED,
            )
        })
        assertEquals(1, result.details.count {
            it is DiLink5AdbEnableCoordinator.Detail.FlagOwnership
        })
        assertTrue(coordinator(worker, FakeLog(), FakeStrategy(), state).lateDetails().isEmpty())
    }

    @Test
    fun deathBeforeWriteReplyContinuesToNextCandidate() {
        val worker = FakeWorkers().apply {
            installed += DiLink5AdbWorkerProcess.candidates.take(2).map { it.processName }
            writes += workerResult(write = null, exists = null, prior = "0", after = null)
                .copy(connected = false, binderDied = true)
            writes += workerResult(write = 1, exists = true, prior = "0", after = "1")
                .copy(workerProcess = DiLink5AdbWorkerProcess.candidates[1].processName)
        }

        val result = coordinator(worker, FakeLog(), FakeStrategy(), FakeState()).enable()

        assertEquals(DiLink5AdbWorkerProcess.candidates[1].processName, result.mutationWorker)
        assertEquals(2, worker.writeCalls)
    }

    @Test
    fun logToolSuccessBranchesRemainDistinctFromAuthenticatedAdb() {
        val noFlagLog = FakeLog().apply {
            setResult = DiLink5LogToolProtocol.RequestResult(
                DiLink5LogToolProtocol.Outcome.CONFIRMED, "ok", "confirmed",
            )
        }
        val result = coordinator(FakeWorkers(), noFlagLog, FakeStrategy(), FakeState()).enable()
        assertEquals(DiLink5AdbEnableCoordinator.Stage.LOG_TOOL_CONFIRMED, result.stage)
        assertFalse(result.authenticatedAdb)

        val worker = FakeWorkers().apply {
            installed += firstCandidate.processName
            writes += workerResult(write = 1, exists = true, prior = "0", after = "1")
        }
        val partialLog = FakeLog().apply {
            availability = DiLink5LogToolProtocol.Availability.AVAILABLE
            setResult = DiLink5LogToolProtocol.RequestResult(
                DiLink5LogToolProtocol.Outcome.CONFIRMED, "ok", "confirmed",
            )
            pageResult = DiLink5LogToolProtocol.RequestResult(
                DiLink5LogToolProtocol.Outcome.REJECTED, "no", "rejected",
            )
        }
        assertEquals(
            DiLink5AdbEnableCoordinator.Stage.FLAG_CONFIRMED,
            coordinator(worker, partialLog, FakeStrategy(), FakeState()).enable().stage,
        )
    }

    @Test
    fun strategyRunsOnlyWhenFlagFailsAndLogEndpointIsDown() {
        val strategy = FakeStrategy()
        val result = coordinator(FakeWorkers(), FakeLog(), strategy, FakeState()).enable()
        assertEquals(DiLink5AdbEnableCoordinator.Stage.STRATEGY_CONFIRMED, result.stage)
        assertEquals(1, strategy.setCalls)

        val unknownLog = FakeLog().apply {
            setResult = DiLink5LogToolProtocol.RequestResult(
                DiLink5LogToolProtocol.Outcome.UNKNOWN_REPLY,
                "{}",
                "unsupported reply",
            )
        }
        val secondStrategy = FakeStrategy()
        val unknownResult = coordinator(
            FakeWorkers(),
            unknownLog,
            secondStrategy,
            FakeState(),
            adbStatus = DiLink5AdbEnableCoordinator.AdbStatus.CONNECTED_AUTHORIZED,
        ).enable()
        assertEquals(DiLink5AdbEnableCoordinator.Stage.LOG_TOOL_NOT_CONFIRMED, unknownResult.stage)
        assertTrue(unknownResult.authenticatedAdb)
        assertEquals(0, secondStrategy.setCalls)
    }

    @Test
    fun sentButUnconfirmedPropertyRetainsOwnershipAndNeverFallsBack() {
        val log = FakeLog().apply {
            setResult = DiLink5LogToolProtocol.RequestResult(
                DiLink5LogToolProtocol.Outcome.UNAVAILABLE,
                null,
                "reply unavailable",
                requestSent = true,
            )
        }
        val strategy = FakeStrategy()
        val state = FakeState()

        val result = coordinator(FakeWorkers(), log, strategy, state).enable()

        assertEquals(DiLink5AdbEnableCoordinator.Stage.LOG_TOOL_NOT_CONFIRMED, result.stage)
        assertTrue(state.propertyChanged)
        assertEquals(0, strategy.setCalls)
    }

    @Test(timeout = 5_000)
    fun aRealCorrelatedUnknownPropertyReplyRequestsNavigationWithoutClaimingSuccess() {
        val log = WireLogGateway()
        val strategy = FakeStrategy()
        val state = FakeState()
        val capabilities = BydManualAdbCapabilities.fromPassiveEvidence(false, false, false, true)

        val result = coordinator(FakeWorkers(), log, strategy, state, capabilities = capabilities).enable()

        assertEquals(listOf("SET_PROPERTY", "OPEN_ACTIVITY"), log.actions)
        assertEquals(DiLink5AdbEnableCoordinator.Stage.LOG_TOOL_NOT_CONFIRMED, result.stage)
        assertFalse(result.authenticatedAdb)
        assertTrue(state.propertyChanged)
        assertEquals(0, strategy.setCalls)
        assertTrue(result.details.contains(DiLink5AdbEnableCoordinator.Detail.PropertyRequest(
            DiLink5LogToolProtocol.Outcome.UNKNOWN_REPLY,
            requestSent = true,
            replyCorrelated = true,
        )))
        assertTrue(result.details.contains(DiLink5AdbEnableCoordinator.Detail.DeveloperActivityRequest(
            DiLink5LogToolProtocol.Outcome.UNKNOWN_REPLY,
        )))
    }

    @Test(timeout = 5_000)
    fun aRealCorrelatedUnknownReplyKeepsTheSeparatePagePrerequisiteUnconfirmed() {
        val log = WireLogGateway()
        val strategy = FakeStrategy()
        val page = FakePage().apply {
            result = DiLink5AdbEnableCoordinator.PageResult.TARGETED_INTENT_ACCEPTED
        }
        val capabilities = BydManualAdbCapabilities.fromPassiveEvidence(false, false, false, true)

        val result = coordinator(FakeWorkers(), log, strategy, FakeState(), page,
            capabilities = capabilities).requestDeveloperPage()

        assertEquals(listOf("SET_PROPERTY", "OPEN_ACTIVITY"), log.actions)
        assertEquals(1, page.calls)
        assertEquals(DiLink5AdbEnableCoordinator.Stage.PAGE_REQUEST_ACCEPTED, result.stage)
        assertEquals(DiLink5AdbEnableCoordinator.Stage.LOG_TOOL_NOT_CONFIRMED, result.prerequisiteStage)
        assertFalse(result.authenticatedAdb)
        assertEquals(0, strategy.setCalls)
    }

    @Test(timeout = 5_000)
    fun aRealUnrelatedMalformedOrAbsentReplyCannotRequestNavigationOrReplayAMutation() {
        val replies: List<(JSONObject) -> String?> = listOf(
            { "{\"msgId\":\"unrelated\"}" },
            { "not-json" },
            { "{}" },
            { null },
            { "{\"msgId\":\"${it.getString("msgId")}\"} trailing" },
        )
        replies.forEach { reply ->
            val log = WireLogGateway(reply)
            val strategy = FakeStrategy()
            val page = FakePage()
            val state = FakeState()
            val capabilities = BydManualAdbCapabilities.fromPassiveEvidence(false, false, false, true)

            val result = coordinator(FakeWorkers(), log, strategy, state, page,
                capabilities = capabilities).requestDeveloperPage()

            assertEquals(listOf("SET_PROPERTY"), log.actions)
            assertEquals(0, page.calls)
            assertEquals(0, strategy.setCalls)
            assertTrue(state.propertyChanged)
            assertFalse(result.authenticatedAdb)
            assertEquals(DiLink5AdbEnableCoordinator.Stage.LOG_TOOL_NOT_CONFIRMED, result.stage)
        }
    }

    @Test
    fun cancellationAfterPropertyTransmissionRetainsRestorationOwnership() {
        val log = FakeLog()
        val state = FakeState()
        lateinit var subject: DiLink5AdbEnableCoordinator
        log.setHook = {
            subject.cancelCurrent()
            DiLink5LogToolProtocol.RequestResult(
                DiLink5LogToolProtocol.Outcome.CANCELLED,
                null,
                "cancelled after transmission",
                requestSent = true,
            )
        }
        subject = coordinator(FakeWorkers(), log, FakeStrategy(), state)

        val result = subject.enable()

        assertEquals(DiLink5AdbEnableCoordinator.Stage.CANCELLED, result.stage)
        assertTrue(state.propertyChanged)
    }

    @Test
    fun passiveDiagnosticsDispatchNoWorkerLogStrategyOrPageAction() {
        val workers = FakeWorkers()
        val log = FakeLog()
        val strategy = FakeStrategy()
        val page = FakePage()
        val result = coordinator(
            workers,
            log,
            strategy,
            FakeState(),
            page,
            DiLink5AdbEnableCoordinator.AdbStatus.CONNECTED_AUTHORIZED,
        ).diagnostics()

        assertEquals(DiLink5AdbEnableCoordinator.Stage.DIAGNOSTICS, result.stage)
        assertTrue(result.authenticatedAdb)
        assertEquals(0, workers.writeCalls)
        assertEquals(0, workers.inspectCalls)
        assertEquals(0, workers.restoreCalls)
        assertEquals(0, log.calls)
        assertEquals(0, strategy.setCalls)
        assertEquals(0, page.calls)
        assertTrue(result.details.contains(
            DiLink5AdbEnableCoordinator.Detail.AdbStatusSnapshot(
                DiLink5AdbEnableCoordinator.AdbStatus.CONNECTED_AUTHORIZED,
            ),
        ))
    }

    @Test
    fun repeatedClickIsSingleFlightAndCancellationDiscardsResult() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val workers = FakeWorkers().apply {
            installed += firstCandidate.processName
            writeHook = {
                entered.countDown()
                release.await(2, TimeUnit.SECONDS)
                workerResult(write = 1, exists = true, prior = "0", after = "1")
            }
        }
        val log = FakeLog()
        val coordinator = coordinator(workers, log, FakeStrategy(), FakeState())
        val first = AtomicReference<DiLink5AdbEnableCoordinator.Result>()
        val thread = Thread { first.set(coordinator.enable()) }
        thread.start()
        assertTrue(entered.await(1, TimeUnit.SECONDS))

        assertEquals(DiLink5AdbEnableCoordinator.Stage.BUSY, coordinator.enable().stage)
        coordinator.cancelCurrent()
        assertFalse(requireNotNull(workers.lastWriteAuthority).invoke())
        release.countDown()
        thread.join(2_000)

        assertNotNull(first.get())
        assertEquals(DiLink5AdbEnableCoordinator.Stage.CANCELLED, first.get().stage)
        assertEquals(0, log.calls)
    }

    @Test
    fun completedEnableRevokesAnUndispatchedWorkerBeforeAnyNextOperation() {
        val workers = FakeWorkers().apply {
            installed += firstCandidate.processName
            writeHook = { null }
        }
        val subject = coordinator(workers, FakeLog(), FakeStrategy(), FakeState())

        assertEquals(DiLink5AdbEnableCoordinator.Stage.STRATEGY_CONFIRMED, subject.enable().stage)
        val completedAuthority = requireNotNull(workers.lastWriteAuthority)
        assertFalse(completedAuthority())

        var writeCalls = 0
        val channel = object : DiLink5AccountServiceClient.ServiceChannel {
            override val isAlive = true
            override fun linkToDeath(onDeath: () -> Unit) = Unit
            override fun unlinkToDeath() = Unit
            override fun write(request: DiLink5AccountServiceClient.ParcelRequest): Int {
                writeCalls++
                return 1
            }
            override fun read(request: DiLink5AccountServiceClient.ParcelRequest) =
                DiLink5AccountServiceClient.ReadResult(DiLink5AccountServiceClient.ReadState.VALUE, "0")
            override fun exists(request: DiLink5AccountServiceClient.ParcelRequest) = false
        }
        val binding = object : DiLink5AccountServiceClient.BindingGateway {
            override fun bind(callback: DiLink5AccountServiceClient.BindingCallback): Boolean {
                callback.onConnected(channel)
                return true
            }
            override fun unbind(callback: DiLink5AccountServiceClient.BindingCallback) = Unit
        }

        val reply = DiLink5AccountServiceClient(binding, 10).write("1", completedAuthority)

        assertEquals(0, writeCalls)
        assertFalse(reply.writeDispatched)
        assertFalse(reply.replyLostAfterDispatch)
    }

    @Test
    fun nextOperationCannotReviveTheCompletedWorkersDispatchAuthority() {
        val workers = FakeWorkers().apply {
            installed += firstCandidate.processName
            writeHook = { null }
        }
        val subject = coordinator(workers, FakeLog(), FakeStrategy(), FakeState())
        subject.enable()
        val completedAuthority = requireNotNull(workers.lastWriteAuthority)
        workers.writeHook = {
            assertFalse(completedAuthority())
            assertTrue(requireNotNull(workers.lastWriteAuthority)())
            workerResult(write = 1, exists = true, prior = "0", after = "1")
        }

        assertEquals(DiLink5AdbEnableCoordinator.Stage.FLAG_CONFIRMED, subject.enable().stage)
        assertFalse(completedAuthority())
        assertFalse(requireNotNull(workers.lastWriteAuthority)())
    }

    @Test
    fun timeoutThenLateConfirmedWriteRecordsRestorationOwnershipOnly() {
        val worker = FakeWorkers().apply { installed += firstCandidate.processName }
        val state = FakeState()
        var lateCompletion: ((DiLink5AdbEnableCoordinator.WorkerResult) -> Unit)? = null
        worker.writeHook = { complete ->
            lateCompletion = complete
            null
        }
        val observed = mutableListOf<DiLink5AdbEnableCoordinator.Detail>()
        val coordinator = coordinator(
            worker,
            FakeLog(),
            FakeStrategy(),
            state,
            detailObserver = observed::add,
        )
        val result = coordinator.enable()

        assertEquals(DiLink5AdbEnableCoordinator.Stage.STRATEGY_CONFIRMED, result.stage)
        assertFalse(requireNotNull(worker.lastWriteAuthority)())
        assertTrue(result.details.contains(
            DiLink5AdbEnableCoordinator.Detail.WorkerTimeout(firstCandidate.packageName),
        ))
        assertTrue(result.details.contains(DiLink5AdbEnableCoordinator.Detail.StrategyFallback))
        assertTrue(state.flags.isEmpty())

        val expected = DiLink5AdbEnableCoordinator.Detail.FlagOwnership(
            firstCandidate.processName,
            DiLink5AccountServiceClient.FLAG_PATH,
            DiLink5AdbEnableStateStore.MutationCertainty.CONFIRMED,
            DiLink5AdbEnableStateStore.OwnershipSource.LATE_RESULT,
        )
        val restoreWorkers = FakeWorkers().apply {
            installed += firstCandidate.processName
            restorations += workerResult(write = 1, exists = true, prior = "1", after = "0")
                .copy(conditionalMatched = true)
        }
        val replacementObserved = mutableListOf<DiLink5AdbEnableCoordinator.Detail>()
        coordinator.close()
        val recreated = coordinator(
            restoreWorkers,
            FakeLog(),
            FakeStrategy(),
            state,
            detailObserver = replacementObserved::add,
        )

        lateCompletion!!(workerResult(write = 1, exists = true, prior = "0", after = "1"))

        assertEquals("0", state.flags.single().priorValue)
        assertTrue(observed.isEmpty())
        assertEquals(listOf(expected), replacementObserved)
        assertEquals(listOf(expected), recreated.lateDetails())
        assertEquals(DiLink5AdbEnableCoordinator.Stage.STRATEGY_CONFIRMED, result.stage)

        assertEquals(DiLink5AdbEnableCoordinator.Stage.RESTORED, recreated.restore().stage)
        assertTrue(state.flags.isEmpty())
        assertTrue(recreated.lateDetails().isEmpty())
    }

    @Test
    fun timeoutThenLateUnconfirmedTransitionRecordsUncertainRestorationOwnershipOnly() {
        val worker = FakeWorkers().apply { installed += firstCandidate.processName }
        val state = FakeState()
        var lateCompletion: ((DiLink5AdbEnableCoordinator.WorkerResult) -> Unit)? = null
        worker.writeHook = { complete ->
            lateCompletion = complete
            null
        }
        val observed = mutableListOf<DiLink5AdbEnableCoordinator.Detail>()
        val coordinator = coordinator(
            worker,
            FakeLog(),
            FakeStrategy(),
            state,
            detailObserver = observed::add,
        )
        val result = coordinator.enable()

        assertEquals(DiLink5AdbEnableCoordinator.Stage.STRATEGY_CONFIRMED, result.stage)
        assertTrue(result.details.contains(
            DiLink5AdbEnableCoordinator.Detail.WorkerTimeout(firstCandidate.packageName),
        ))
        assertTrue(result.details.contains(DiLink5AdbEnableCoordinator.Detail.StrategyFallback))
        assertTrue(state.flags.isEmpty())

        lateCompletion!!(workerResult(write = 0, exists = true, prior = "0", after = "1"))

        val expected = DiLink5AdbEnableCoordinator.Detail.FlagOwnership(
            firstCandidate.processName,
            DiLink5AccountServiceClient.FLAG_PATH,
            DiLink5AdbEnableStateStore.MutationCertainty.MAY_HAVE_CHANGED,
            DiLink5AdbEnableStateStore.OwnershipSource.LATE_RESULT,
        )
        assertEquals("0", state.flags.single().priorValue)
        assertEquals(
            DiLink5AdbEnableStateStore.MutationCertainty.MAY_HAVE_CHANGED,
            state.flags.single().certainty,
        )
        assertEquals(listOf(expected), observed)
        assertEquals(listOf(expected), coordinator.lateDetails())
        assertEquals(DiLink5AdbEnableCoordinator.Stage.STRATEGY_CONFIRMED, result.stage)
    }

    @Test
    fun lateEmptyAndNonzeroTransitionsRetainExactUncertainRestorationOwnership() {
        listOf("", "legacy").forEach { prior ->
            val worker = FakeWorkers().apply { installed += firstCandidate.processName }
            val state = FakeState()
            var lateCompletion: ((DiLink5AdbEnableCoordinator.WorkerResult) -> Unit)? = null
            worker.writeHook = { complete ->
                lateCompletion = complete
                null
            }
            val coordinator = coordinator(worker, FakeLog(), FakeStrategy(), state)

            val result = coordinator.enable()
            lateCompletion!!(workerResult(write = 0, exists = true, prior = prior, after = "1"))

            assertEquals(DiLink5AdbEnableCoordinator.Stage.STRATEGY_CONFIRMED, result.stage)
            assertEquals(prior, state.flags.single().priorValue)
            assertEquals(
                DiLink5AdbEnableStateStore.MutationCertainty.MAY_HAVE_CHANGED,
                state.flags.single().certainty,
            )
            assertEquals(
                DiLink5AdbEnableStateStore.OwnershipSource.LATE_RESULT,
                state.flags.single().source,
            )
            coordinator.close()
        }
    }

    @Test
    fun missingCurrentModeRejectsAllActiveEntryPointsWithoutDispatch() {
        val mode = FakeMode().apply { revoke() }
        val workers = FakeWorkers().apply {
            installed += firstCandidate.processName
            writes += workerResult(write = 1, exists = true, prior = "0", after = "1")
        }
        val log = FakeLog()
        val strategy = FakeStrategy()
        val page = FakePage()
        val state = FakeState().apply {
            flags += DiLink5AdbEnableStateStore.FlagMutation(
                firstCandidate.processName,
                DiLink5AccountServiceClient.FLAG_PATH,
                DiLink5AccountServiceClient.ReadState.VALUE,
                "0",
            )
            this.strategy = "1"
        }
        val subject = coordinator(workers, log, strategy, state, page, mode = mode)

        assertEquals(DiLink5AdbEnableCoordinator.Stage.UNAVAILABLE, subject.enable().stage)
        assertEquals(DiLink5AdbEnableCoordinator.Stage.UNAVAILABLE, subject.requestDeveloperPage().stage)
        assertEquals(DiLink5AdbEnableCoordinator.Stage.UNAVAILABLE, subject.restore().stage)
        assertEquals(0, workers.inspectCalls)
        assertEquals(0, workers.writeCalls)
        assertEquals(0, workers.restoreCalls)
        assertEquals(0, log.calls)
        assertEquals(0, strategy.setCalls)
        assertEquals(0, strategy.restoreCalls)
        assertEquals(0, page.calls)
    }

    @Test
    fun modeRevocationBeforeWorkerDispatchStopsEveryActiveEffect() {
        val mode = FakeMode()
        val workers = FakeWorkers().apply {
            installed += firstCandidate.processName
            installedHook = { mode.revoke() }
        }
        val log = FakeLog()
        val strategy = FakeStrategy()

        val result = coordinator(workers, log, strategy, FakeState(), mode = mode).enable()

        assertEquals(DiLink5AdbEnableCoordinator.Stage.CANCELLED, result.stage)
        assertEquals(0, workers.writeCalls)
        assertEquals(0, log.calls)
        assertEquals(0, strategy.setCalls)
    }

    @Test
    fun modeRevocationDuringWorkerCallKeepsOwnershipAndStopsLaterStages() {
        val mode = FakeMode()
        val workers = FakeWorkers().apply {
            installed += firstCandidate.processName
            writeHook = {
                mode.revoke()
                workerResult(write = 1, exists = true, prior = "0", after = "1")
            }
        }
        val state = FakeState()
        val log = FakeLog()
        val strategy = FakeStrategy()

        val result = coordinator(workers, log, strategy, state, mode = mode).enable()

        assertEquals(DiLink5AdbEnableCoordinator.Stage.CANCELLED, result.stage)
        assertEquals("0", state.flags.single().priorValue)
        assertEquals(0, log.calls)
        assertEquals(0, strategy.setCalls)
    }

    @Test
    fun cancellationAfterPageInspectionPreventsDialDispatch() {
        val workers = FakeWorkers().apply {
            installed += firstCandidate.processName
            inspections += workerResult(write = null, exists = true, prior = null, after = null)
        }
        val page = FakePage()
        lateinit var subject: DiLink5AdbEnableCoordinator
        workers.inspectHook = { subject.cancelCurrent() }
        subject = coordinator(workers, FakeLog(), FakeStrategy(), FakeState(), page)

        assertEquals(DiLink5AdbEnableCoordinator.Stage.CANCELLED, subject.requestDeveloperPage().stage)
        assertEquals(0, page.calls)
    }

    @Test
    fun cancellationAfterFlagRestorePreventsStrategyRestore() {
        val workers = FakeWorkers().apply {
            installed += firstCandidate.processName
        }
        val state = FakeState().apply {
            flags += DiLink5AdbEnableStateStore.FlagMutation(
                firstCandidate.processName,
                DiLink5AccountServiceClient.FLAG_PATH,
                DiLink5AccountServiceClient.ReadState.VALUE,
                "0",
            )
            strategy = "1"
        }
        val strategy = FakeStrategy()
        lateinit var subject: DiLink5AdbEnableCoordinator
        workers.restoreHook = {
            subject.cancelCurrent()
            workerResult(write = 1, exists = true, prior = "1", after = "0")
                .copy(conditionalMatched = true)
        }
        subject = coordinator(workers, FakeLog(), strategy, state)

        assertEquals(DiLink5AdbEnableCoordinator.Stage.CANCELLED, subject.restore().stage)
        assertEquals(0, strategy.restoreCalls)
        assertEquals(1, state.flags.size)
    }

    @Test
    fun pageRequestChecksExistenceAndReportsIntentAcceptanceWithoutClaimingVisibility() {
        val workers = FakeWorkers().apply {
            installed += firstCandidate.processName
            inspections += workerResult(write = null, exists = true, prior = null, after = null)
        }
        val page = FakePage().apply { result = DiLink5AdbEnableCoordinator.PageResult.TARGETED_INTENT_ACCEPTED }

        val result = coordinator(workers, FakeLog(), FakeStrategy(), FakeState(), page).requestDeveloperPage()

        assertEquals(DiLink5AdbEnableCoordinator.Stage.PAGE_REQUEST_ACCEPTED, result.stage)
        assertEquals(1, workers.inspectCalls)
        assertEquals(0, workers.writeCalls)
        assertEquals(
            DiLink5AdbEnableCoordinator.Detail.PageRequest(
                DiLink5AdbEnableCoordinator.PageResult.TARGETED_INTENT_ACCEPTED,
            ),
            result.details.single(),
        )
    }

    @Test
    fun pageRequestWithMissingFlagRetainsEnablementAndPageOutcomes() {
        val workers = FakeWorkers().apply {
            installed += firstCandidate.processName
            inspections += workerResult(write = null, exists = false, prior = null, after = null)
            writes += workerResult(write = 1, exists = true, prior = "0", after = "1")
        }
        val page = FakePage().apply {
            result = DiLink5AdbEnableCoordinator.PageResult.TARGETED_INTENT_ACCEPTED
        }

        val result = coordinator(workers, FakeLog(), FakeStrategy(), FakeState(), page)
            .requestDeveloperPage()

        assertEquals(DiLink5AdbEnableCoordinator.Stage.PAGE_REQUEST_ACCEPTED, result.stage)
        assertEquals(DiLink5AdbEnableCoordinator.Stage.FLAG_CONFIRMED, result.prerequisiteStage)
        assertEquals(firstCandidate.processName, result.mutationWorker)
        assertEquals(DiLink5AccountServiceClient.FLAG_PATH, result.mutationPath)
        assertTrue(result.details.any { it is DiLink5AdbEnableCoordinator.Detail.WorkerAttempt })
        assertTrue(result.details.any { it is DiLink5AdbEnableCoordinator.Detail.PageRequest })
    }

    @Test
    fun repeatedAttemptsPreserveEarliestFlagAndStrategyBaselines() {
        val workers = FakeWorkers().apply {
            installed += firstCandidate.processName
            writes += workerResult(write = 1, exists = true, prior = "0", after = "1")
            writes += workerResult(write = 1, exists = true, prior = "1", after = "1")
        }
        val state = FakeState()
        val coordinator = coordinator(workers, FakeLog(), FakeStrategy(), state)

        coordinator.enable()
        coordinator.enable()

        assertEquals(1, state.flags.size)
        assertEquals("0", state.flags.single().priorValue)

        val strategy = FakeStrategy()
        val strategyState = FakeState()
        val strategyCoordinator = coordinator(FakeWorkers(), FakeLog(), strategy, strategyState)
        strategy.prior = "1"
        strategyCoordinator.enable()
        strategy.prior = "0"
        strategyCoordinator.enable()
        assertEquals("1", strategyState.strategy)
    }

    @Test
    fun changedCandidatesKeepIndependentRestorationOwners() {
        val first = DiLink5AdbWorkerProcess.candidates[0]
        val second = DiLink5AdbWorkerProcess.candidates[1]
        val workers = FakeWorkers().apply {
            installed += first.processName
            writes += workerResult(write = 1, exists = true, prior = "0", after = "1")
        }
        val state = FakeState()
        val coordinator = coordinator(workers, FakeLog(), FakeStrategy(), state)
        coordinator.enable()

        workers.installed.clear()
        workers.installed += second.processName
        workers.writes += workerResult(write = 1, exists = true, prior = "other", after = "1")
            .copy(workerProcess = second.processName)
        coordinator.enable()

        assertEquals(listOf(first.processName, second.processName), state.flags.map { it.workerProcess })
        assertEquals(listOf("0", "other"), state.flags.map { it.priorValue })
    }

    @Test
    fun flagRestorationRejectsExternalDriftWithoutWriting() {
        val workers = FakeWorkers().apply {
            installed += firstCandidate.processName
            restorations += workerResult(
                write = null,
                exists = true,
                prior = "external",
                after = "external",
            ).copy(conditionalMatched = false)
        }
        val owner = DiLink5AdbEnableStateStore.FlagMutation(
            firstCandidate.processName,
            DiLink5AccountServiceClient.FLAG_PATH,
            DiLink5AccountServiceClient.ReadState.VALUE,
            "0",
            "1",
        )
        val state = FakeState().apply { flags += owner }

        val result = coordinator(workers, FakeLog(), FakeStrategy(), state).restore()

        assertEquals(DiLink5AdbEnableCoordinator.Stage.PARTIAL_RESTORATION, result.stage)
        assertEquals(0, workers.inspectCalls)
        assertEquals(0, workers.writeCalls)
        assertEquals(1, workers.restoreCalls)
        assertEquals(listOf("1" to "0"), workers.restoreRequests)
        assertEquals(listOf(owner), state.flags)
    }

    @Test
    fun emptyBaselineRestorationCanBeConfirmedAndClearsOwnership() {
        val workers = FakeWorkers().apply {
            installed += firstCandidate.processName
            restorations += workerResult(
                write = 1,
                exists = true,
                prior = "1",
                after = "",
            ).copy(conditionalMatched = true, writeDispatched = true)
        }
        val state = FakeState().apply {
            flags += DiLink5AdbEnableStateStore.FlagMutation(
                firstCandidate.processName,
                DiLink5AccountServiceClient.FLAG_PATH,
                DiLink5AccountServiceClient.ReadState.EMPTY,
                "",
                "1",
            )
        }

        val result = coordinator(workers, FakeLog(), FakeStrategy(), state).restore()

        assertEquals(DiLink5AdbEnableCoordinator.Stage.RESTORED, result.stage)
        assertTrue(state.flags.isEmpty())
        assertEquals(listOf("1" to ""), workers.restoreRequests)
    }

    @Test
    fun strategyRestorationRejectsExternalDriftWithoutWriting() {
        val strategy = FakeStrategy().apply { current = "external" }
        val state = FakeState().apply {
            this.strategy = "1"
            strategyExpected = "0"
        }

        val result = coordinator(FakeWorkers(), FakeLog(), strategy, state).restore()

        assertEquals(DiLink5AdbEnableCoordinator.Stage.PARTIAL_RESTORATION, result.stage)
        assertEquals(0, strategy.readCalls)
        assertEquals(1, strategy.restoreCalls)
        assertEquals(listOf("0" to "1"), strategy.restoreRequests)
        assertEquals("1", state.strategy)
    }

    @Test
    fun restorationUsesEveryRecordedCandidateWithoutOverwritingOwnership() {
        val first = DiLink5AdbWorkerProcess.candidates[0]
        val second = DiLink5AdbWorkerProcess.candidates[1]
        val workers = FakeWorkers().apply {
            installed += listOf(first.processName, second.processName)
            restorations += workerResult(write = 1, exists = true, prior = "1", after = "0")
                .copy(conditionalMatched = true)
            restorations += workerResult(write = 1, exists = true, prior = "1", after = "legacy")
                .copy(workerProcess = second.processName, conditionalMatched = true)
        }
        val state = FakeState().apply {
            flags += DiLink5AdbEnableStateStore.FlagMutation(
                first.processName,
                DiLink5AccountServiceClient.FLAG_PATH,
                DiLink5AccountServiceClient.ReadState.VALUE,
                "0",
            )
            flags += DiLink5AdbEnableStateStore.FlagMutation(
                second.processName,
                DiLink5AccountServiceClient.FLAG_PATH,
                DiLink5AccountServiceClient.ReadState.VALUE,
                "legacy",
            )
        }

        val result = coordinator(workers, FakeLog(), FakeStrategy(), state).restore()

        assertEquals(DiLink5AdbEnableCoordinator.Stage.RESTORED, result.stage)
        assertTrue(state.flags.isEmpty())
        assertEquals(0, workers.writeCalls)
        assertEquals(2, workers.restoreCalls)
        assertEquals(
            listOf(
                DiLink5AdbEnableCoordinator.Detail.FlagRestoration(
                    first.processName,
                    DiLink5AccountServiceClient.FLAG_PATH,
                    DiLink5AdbEnableCoordinator.RestorationOutcome.CONFIRMED,
                ),
                DiLink5AdbEnableCoordinator.Detail.FlagRestoration(
                    second.processName,
                    DiLink5AccountServiceClient.FLAG_PATH,
                    DiLink5AdbEnableCoordinator.RestorationOutcome.CONFIRMED,
                ),
            ),
            result.details,
        )
    }

    @Test
    fun restorationUsesRecordedWorkerAndKeepsUnreadablePropertyVisible() {
        val workers = FakeWorkers().apply {
            installed += firstCandidate.processName
            restorations += workerResult(write = 1, exists = true, prior = "1", after = "0")
                .copy(conditionalMatched = true)
        }
        val state = FakeState().apply {
            flags += DiLink5AdbEnableStateStore.FlagMutation(
                firstCandidate.processName,
                DiLink5AccountServiceClient.FLAG_PATH,
                DiLink5AccountServiceClient.ReadState.VALUE,
                "0",
            )
            propertyChanged = true
        }

        val result = coordinator(workers, FakeLog(), FakeStrategy(), state).restore()

        assertEquals(DiLink5AdbEnableCoordinator.Stage.PARTIAL_RESTORATION, result.stage)
        assertTrue(state.flags.isEmpty())
        assertTrue(result.details.contains(DiLink5AdbEnableCoordinator.Detail.PropertyNotRestorable))
    }

    @Test
    fun lateFlagMutationDuringStrategyRestorePreventsRestoredPublication() {
        val workers = FakeWorkers().apply { installed += firstCandidate.processName }
        var lateCompletion: ((DiLink5AdbEnableCoordinator.WorkerResult) -> Unit)? = null
        workers.writeHook = { complete -> lateCompletion = complete; null }
        val state = FakeState()
        val strategy = FakeStrategy()
        val client = coordinator(workers, FakeLog(), strategy, state)
        assertEquals(DiLink5AdbEnableCoordinator.Stage.STRATEGY_CONFIRMED, client.enable().stage)
        assertTrue(state.flags.isEmpty())
        strategy.restoreHook = { _, value ->
            lateCompletion!!(workerResult(write = 1, exists = true, prior = "0", after = "1"))
            DiLink5StrategyServiceClient.Result(
                DiLink5StrategyServiceClient.Outcome.CONFIRMED, "0", "1", value, "restored",
            )
        }

        val result = client.restore()

        assertEquals(DiLink5AdbEnableCoordinator.Stage.PARTIAL_RESTORATION, result.stage)
        assertEquals(DiLink5AdbEnableStateStore.OwnershipSource.LATE_RESULT, state.flags.single().source)
        assertTrue(result.details.any { it is DiLink5AdbEnableCoordinator.Detail.FlagOwnership })
        assertEquals(null, state.strategy)
        assertEquals(0, workers.restoreCalls)
    }

    @Test
    fun lateOwnerDuringResultConstructionIsRecheckedAtFinalRestorationPublication() {
        val workers = FakeWorkers().apply { installed += firstCandidate.processName }
        var lateCompletion: ((DiLink5AdbEnableCoordinator.WorkerResult) -> Unit)? = null
        workers.writeHook = { complete -> lateCompletion = complete; null }
        val state = FakeState()
        var deliverDuringResult = false
        val client = coordinator(
            workers, FakeLog(), FakeStrategy(), state,
            adbStatusHook = {
                if (deliverDuringResult) {
                    deliverDuringResult = false
                    lateCompletion!!(workerResult(write = 1, exists = true, prior = "0", after = "1"))
                }
            },
        )
        client.enable()
        deliverDuringResult = true

        val result = client.restore()

        assertEquals(DiLink5AdbEnableCoordinator.Stage.PARTIAL_RESTORATION, result.stage)
        assertEquals(1, state.flags.size)
        assertTrue(result.details.any { it is DiLink5AdbEnableCoordinator.Detail.FlagOwnership })
        assertFalse(result.details.contains(DiLink5AdbEnableCoordinator.Detail.NoOwnedRestorableChange))
    }

    @Test
    fun failedFlagOwnershipSaveKeepsPhysicalOutcomeButWarnsItIsNotDurable() {
        val workers = FakeWorkers().apply {
            installed += firstCandidate.processName
            writes += workerResult(write = 1, exists = true, prior = "0", after = "1")
        }
        val state = FakeState().apply { persistSucceeds = false }

        val result = coordinator(workers, FakeLog(), FakeStrategy(), state).enable()

        assertEquals(DiLink5AdbEnableCoordinator.Stage.FLAG_CONFIRMED, result.stage)
        assertFalse(result.ownershipDurable)
        assertTrue(result.details.contains(DiLink5AdbEnableCoordinator.Detail.OwnershipNotDurable))
        assertEquals("0", state.flags.single().priorValue)
    }

    @Test
    fun failedStrategyAndPropertyJournalsExposeTheirDurabilitySeparatelyFromAuthorization() {
        val strategyState = FakeState().apply { persistSucceeds = false }
        val strategyResult = coordinator(FakeWorkers(), FakeLog(), FakeStrategy(), strategyState).enable()
        assertEquals(DiLink5AdbEnableCoordinator.Stage.STRATEGY_CONFIRMED, strategyResult.stage)
        assertFalse(strategyResult.ownershipDurable)
        assertEquals("1", strategyState.strategy)
        assertFalse(strategyResult.authenticatedAdb)

        val propertyState = FakeState().apply { persistSucceeds = false }
        val log = FakeLog().apply {
            setResult = DiLink5LogToolProtocol.RequestResult(
                DiLink5LogToolProtocol.Outcome.UNKNOWN_REPLY, detail = "sent", requestSent = true,
            )
        }
        val propertyResult = coordinator(FakeWorkers(), log, FakeStrategy(), propertyState).enable()
        assertEquals(DiLink5AdbEnableCoordinator.Stage.LOG_TOOL_NOT_CONFIRMED, propertyResult.stage)
        assertFalse(propertyResult.ownershipDurable)
        assertTrue(propertyState.propertyChanged)
        assertFalse(propertyResult.authenticatedAdb)
    }

    @Test
    fun failedJournalClearIsPartialAndRetryCanClearAlreadyRestoredBaselineWithoutReplay() {
        val workers = FakeWorkers().apply {
            installed += firstCandidate.processName
            restorations += workerResult(write = 1, exists = true, prior = "1", after = "0")
                .copy(conditionalMatched = true)
            restorations += workerResult(write = null, exists = true, prior = "0", after = "0")
                .copy(conditionalMatched = false)
        }
        val state = FakeState().apply {
            flags += DiLink5AdbEnableStateStore.FlagMutation(
                firstCandidate.processName, DiLink5AccountServiceClient.FLAG_PATH,
                DiLink5AccountServiceClient.ReadState.VALUE, "0",
            )
            persistSucceeds = false
        }
        val client = coordinator(workers, FakeLog(), FakeStrategy(), state)
        val first = client.restore()
        assertEquals(DiLink5AdbEnableCoordinator.Stage.PARTIAL_RESTORATION, first.stage)
        assertFalse(first.ownershipDurable)
        assertEquals(1, state.flags.size)

        state.persistSucceeds = true
        val retry = client.restore()
        assertEquals(DiLink5AdbEnableCoordinator.Stage.RESTORED, retry.stage)
        assertTrue(retry.ownershipDurable)
        assertTrue(state.flags.isEmpty())
        assertEquals(0, workers.writeCalls)
    }

    @Test
    fun compatibleDefaultDiLink4AndDiLink5UseManualStrategyWithoutCameraAdmission() {
        listOf("default", "dilink4", "dilink5", "camera2").forEach { mode ->
            val workers = FakeWorkers().apply { installed += firstCandidate.processName }
            val strategy = FakeStrategy()
            val authority = BydManualAdbModeAuthority { BydManualAdbModeAuthority.Scope(mode) }
            val result = coordinator(
                workers, FakeLog(), strategy, FakeState(),
                mode = authority,
                capabilities = BydManualAdbCapabilities(false, false, true, true, false),
            ).enable()
            assertEquals(mode, DiLink5AdbEnableCoordinator.Stage.STRATEGY_CONFIRMED, result.stage)
            assertEquals(0, workers.writeCalls)
            assertEquals(0, workers.inspectMainCalls)
            assertEquals(1, strategy.setCalls)
        }
    }

    @Test
    fun unavailableRailsNeverDispatchEvenWhenCandidatePackagesExist() {
        val workers = FakeWorkers().apply { installed += firstCandidate.processName }
        val log = FakeLog()
        val strategy = FakeStrategy()
        val client = coordinator(workers, log, strategy, FakeState(),
            capabilities = BydManualAdbCapabilities.NONE)

        assertEquals(DiLink5AdbEnableCoordinator.Stage.UNAVAILABLE, client.enable().stage)
        assertEquals(DiLink5AdbEnableCoordinator.Stage.PAGE_REQUEST_UNAVAILABLE, client.requestDeveloperPage().stage)
        assertEquals(0, workers.writeCalls)
        assertEquals(0, workers.inspectMainCalls)
        assertEquals(0, log.calls)
        assertEquals(0, strategy.setCalls)
    }

    @Test
    fun modeSelectionBeforeQueuedExecutionCancelsTheOriginallyApprovedManualRequest() {
        var scope = BydManualAdbModeAuthority.Scope("default")
        val authority = BydManualAdbModeAuthority { scope }
        val approved = authority.admit()!!
        scope = scope.copy(
            configuredMode = "dilink4", pendingMode = "dilink4", pendingGeneration = "pending-4",
        )
        val workers = FakeWorkers().apply { installed += firstCandidate.processName }
        val log = FakeLog()
        val strategy = FakeStrategy()
        val client = coordinator(workers, log, strategy, FakeState(), mode = authority)

        assertEquals(DiLink5AdbEnableCoordinator.Stage.CANCELLED, client.enable(approved).stage)
        assertEquals(DiLink5AdbEnableCoordinator.Stage.CANCELLED, client.requestDeveloperPage(approved).stage)
        assertEquals(DiLink5AdbEnableCoordinator.Stage.CANCELLED, client.restore(approved).stage)
        assertEquals(0, workers.writeCalls)
        assertEquals(0, workers.inspectMainCalls)
        assertEquals(0, log.calls)
        assertEquals(0, strategy.setCalls)
    }

    @Test
    fun logOnlyFirmwareSkipsAccountAbiAndDoesNotFallbackAfterAnUncertainSentRequest() {
        val workers = FakeWorkers().apply { installed += firstCandidate.processName }
        val log = FakeLog().apply {
            setResult = DiLink5LogToolProtocol.RequestResult(
                DiLink5LogToolProtocol.Outcome.UNKNOWN_REPLY, detail = "sent", requestSent = true,
            )
        }
        val strategy = FakeStrategy()
        val result = coordinator(workers, log, strategy, FakeState(),
            capabilities = BydManualAdbCapabilities(false, true, false, true, false)).enable()

        assertEquals(DiLink5AdbEnableCoordinator.Stage.LOG_TOOL_NOT_CONFIRMED, result.stage)
        assertEquals(0, workers.writeCalls)
        assertEquals(0, workers.inspectMainCalls)
        assertEquals(1, log.calls)
        assertEquals(0, strategy.setCalls)
    }

    @Test
    fun unavailableRestorationAbiPreservesOwnedChangesAndReportsPartial() {
        val state = FakeState().apply {
            flags += DiLink5AdbEnableStateStore.FlagMutation(
                firstCandidate.processName, DiLink5AccountServiceClient.FLAG_PATH,
                DiLink5AccountServiceClient.ReadState.VALUE, "0",
            )
            strategy = "1"
            strategyExpected = "0"
        }
        val workers = FakeWorkers().apply { installed += firstCandidate.processName }
        val strategy = FakeStrategy()

        val result = coordinator(workers, FakeLog(), strategy, state,
            capabilities = BydManualAdbCapabilities.NONE).restore()

        assertEquals(DiLink5AdbEnableCoordinator.Stage.PARTIAL_RESTORATION, result.stage)
        assertEquals(0, workers.restoreCalls)
        assertEquals(0, strategy.restoreCalls)
        assertEquals(1, state.flags.size)
        assertEquals("1", state.strategy)
    }

    private fun coordinator(
        workers: FakeWorkers,
        log: DiLink5AdbEnableCoordinator.LogToolGateway,
        strategy: DiLink5AdbEnableCoordinator.StrategyGateway,
        state: FakeState,
        page: FakePage = FakePage(),
        adbStatus: DiLink5AdbEnableCoordinator.AdbStatus = DiLink5AdbEnableCoordinator.AdbStatus.DISCONNECTED,
        mode: DiLink5AdbEnableCoordinator.ModeAuthority = FakeMode(),
        detailObserver: (DiLink5AdbEnableCoordinator.Detail) -> Unit = {},
        capabilities: BydManualAdbCapabilities = BydManualAdbCapabilities(true, true, true, true, true),
        adbStatusHook: (() -> Unit)? = null,
    ) = DiLink5AdbEnableCoordinator(
        workers,
        log,
        strategy,
        state,
        page,
        object : DiLink5AdbEnableCoordinator.PassiveInfoProvider {
            override fun adbStatus(): DiLink5AdbEnableCoordinator.AdbStatus {
                adbStatusHook?.invoke()
                return adbStatus
            }
            override fun details() = listOf(
                DiLink5AdbEnableCoordinator.Detail.DeviceSnapshot("model", "build", 31),
            )
        },
        mode,
        { capabilities },
        detailObserver,
    )

    private class FakeWorkers : DiLink5AdbEnableCoordinator.WorkerGateway {
        val installed = mutableSetOf<String>()
        val writes = ArrayDeque<DiLink5AdbEnableCoordinator.WorkerResult>()
        val mainInspections = ArrayDeque<DiLink5AdbEnableCoordinator.WorkerResult>()
        val inspections = ArrayDeque<DiLink5AdbEnableCoordinator.WorkerResult>()
        val restorations = ArrayDeque<DiLink5AdbEnableCoordinator.WorkerResult>()
        val restoreRequests = mutableListOf<Pair<String, String>>()
        var writeCalls = 0
        var lastWriteAuthority: (() -> Boolean)? = null
        var inspectCalls = 0
        var inspectMainCalls = 0
        var restoreCalls = 0
        var installedHook: (() -> Unit)? = null
        var inspectHook: (() -> Unit)? = null
        var writeHook: (((DiLink5AdbEnableCoordinator.WorkerResult) -> Unit) -> DiLink5AdbEnableCoordinator.WorkerResult?)? = null
        var restoreHook: (() -> DiLink5AdbEnableCoordinator.WorkerResult?)? = null

        override fun installed(candidate: DiLink5AdbWorkerProcess.Candidate): Boolean {
            installedHook?.invoke()
            return candidate.processName in installed
        }

        override fun inspectMain(
            operationId: Long,
            modeGeneration: String,
            authority: () -> Boolean,
        ): DiLink5AdbEnableCoordinator.WorkerResult? {
            inspectMainCalls++
            return mainInspections.removeFirstOrNull()
        }

        override fun inspect(
            candidate: DiLink5AdbWorkerProcess.Candidate,
            operationId: Long,
            modeGeneration: String,
            authority: () -> Boolean,
        ): DiLink5AdbEnableCoordinator.WorkerResult? {
            inspectCalls++
            inspectHook?.invoke()
            return inspections.removeFirstOrNull()
        }

        override fun write(
            candidate: DiLink5AdbWorkerProcess.Candidate,
            content: String,
            operationId: Long,
            modeGeneration: String,
            authority: () -> Boolean,
            onLateResult: (DiLink5AdbEnableCoordinator.WorkerResult) -> Unit,
        ): DiLink5AdbEnableCoordinator.WorkerResult? {
            writeCalls++
            lastWriteAuthority = authority
            return writeHook?.invoke(onLateResult) ?: writes.removeFirstOrNull()
        }

        override fun restore(
            candidate: DiLink5AdbWorkerProcess.Candidate,
            expectedCurrent: String,
            restoreValue: String,
            operationId: Long,
            modeGeneration: String,
            authority: () -> Boolean,
        ): DiLink5AdbEnableCoordinator.WorkerResult? {
            restoreCalls++
            restoreRequests += expectedCurrent to restoreValue
            return restoreHook?.invoke() ?: restorations.removeFirstOrNull()
        }
    }

    /** Exercises the production handshake, request framing, correlation and coordinator gates. */
    private class WireLogGateway(
        private val reply: (JSONObject) -> String? = {
            JSONObject().put("msgId", it.getString("msgId")).put("error", "unverified").toString()
        },
    ) : DiLink5AdbEnableCoordinator.LogToolGateway {
        val actions = mutableListOf<String>()
        private var nowMs = 0L
        private val protocol = DiLink5LogToolProtocol(
            null,
            socketFactory = { wireSocket() },
            clockMs = { nowMs },
            sleeper = { delay -> nowMs += delay },
            wakeSender = { throw IllegalStateException("wake receiver is unavailable") },
        )

        override fun probe(cancelled: () -> Boolean) =
            protocol.probe(DiLink5LogToolProtocol.Cancellation(cancelled))

        override fun setAdbTcpPort(cancelled: () -> Boolean) =
            protocol.setAdbTcpPort(DiLink5LogToolProtocol.Cancellation(cancelled))

        override fun requestDeveloperActivity(cancelled: () -> Boolean) =
            protocol.requestDeveloperActivity(DiLink5LogToolProtocol.Cancellation(cancelled))

        private fun wireSocket(): Socket {
            val written = ByteArrayOutputStream()
            val greeting = ByteArray(64).apply {
                this[0] = 0xff.toByte()
                this[9] = 0x7f
                this[10] = 3
            }
            val handshake = ByteArrayInputStream(
                greeting + protocol.encodeFrame(4, byteArrayOf(5) + "READY".toByteArray()),
            )
            var framedReply: ByteArrayInputStream? = null
            val input = object : InputStream() {
                private fun source(): ByteArrayInputStream {
                    if (handshake.available() > 0) return handshake
                    return framedReply ?: run {
                        val request = JSONObject("{" + written.toString("UTF-8").substringAfter('{'))
                        actions += request.getString("action")
                        val payload = reply(request)
                        ByteArrayInputStream(
                            if (payload == null) ByteArray(0)
                            else protocol.encodeFrame(1, ByteArray(0)) +
                                protocol.encodeFrame(0, payload.toByteArray(Charsets.UTF_8)),
                        ).also { framedReply = it }
                    }
                }

                override fun read(): Int = source().read()
                override fun read(bytes: ByteArray, offset: Int, length: Int): Int =
                    source().read(bytes, offset, length)
            }
            return object : Socket() {
                override fun connect(endpoint: SocketAddress?, timeout: Int) = Unit
                override fun getInputStream() = input
                override fun getOutputStream() = written
                override fun close() = Unit
            }
        }
    }

    private class FakeLog : DiLink5AdbEnableCoordinator.LogToolGateway {
        var calls = 0
        var availability = DiLink5LogToolProtocol.Availability.UNAVAILABLE
        var setResult = DiLink5LogToolProtocol.RequestResult(
            DiLink5LogToolProtocol.Outcome.UNAVAILABLE,
            null,
            "down",
        )
        var pageResult = DiLink5LogToolProtocol.RequestResult(
            DiLink5LogToolProtocol.Outcome.CONFIRMED,
            null,
            "confirmed",
        )
        var setHook: (() -> DiLink5LogToolProtocol.RequestResult)? = null
        override fun probe(cancelled: () -> Boolean): DiLink5LogToolProtocol.Availability {
            calls++
            return availability
        }
        override fun setAdbTcpPort(cancelled: () -> Boolean): DiLink5LogToolProtocol.RequestResult {
            calls++
            return setHook?.invoke() ?: setResult
        }
        override fun requestDeveloperActivity(cancelled: () -> Boolean): DiLink5LogToolProtocol.RequestResult {
            calls++
            return pageResult
        }
    }

    private class FakeStrategy : DiLink5AdbEnableCoordinator.StrategyGateway {
        var readCalls = 0
        var setCalls = 0
        var restoreCalls = 0
        var prior = "1"
        var current: String? = "0"
        val restoreRequests = mutableListOf<Pair<String, String>>()
        var restoreHook: ((String, String) -> DiLink5StrategyServiceClient.Result)? = null
        override fun readVerifyEnable(): String? {
            readCalls++
            return current
        }
        override fun setVerifyDisabled(isCurrent: () -> Boolean): DiLink5StrategyServiceClient.Result {
            if (!isCurrent()) return DiLink5StrategyServiceClient.Result(
                DiLink5StrategyServiceClient.Outcome.CANCELLED,
                prior,
                "1",
                current,
                "cancelled",
            )
            setCalls++
            current = "0"
            return DiLink5StrategyServiceClient.Result(
                DiLink5StrategyServiceClient.Outcome.CONFIRMED,
                prior,
                "1",
                "0",
                "confirmed",
            )
        }
        override fun restoreVerifyEnable(
            expectedCurrent: String,
            value: String,
            isCurrent: () -> Boolean,
        ): DiLink5StrategyServiceClient.Result {
            if (!isCurrent()) return DiLink5StrategyServiceClient.Result(
                DiLink5StrategyServiceClient.Outcome.CANCELLED,
                current,
                "1",
                current,
                "cancelled",
            )
            restoreCalls++
            restoreRequests += expectedCurrent to value
            restoreHook?.let { return it(expectedCurrent, value) }
            if (current != expectedCurrent) {
                return DiLink5StrategyServiceClient.Result(
                    DiLink5StrategyServiceClient.Outcome.CONNECTED_NOT_CONFIRMED,
                    current,
                    "1",
                    current,
                    "current value did not match",
                )
            }
            current = value
            return DiLink5StrategyServiceClient.Result(
                DiLink5StrategyServiceClient.Outcome.CONFIRMED,
                expectedCurrent,
                "1",
                value,
                "restored",
            )
        }
    }

    private class FakeState : DiLink5AdbEnableCoordinator.StateGateway {
        val flags = mutableListOf<DiLink5AdbEnableStateStore.FlagMutation>()
        private val observers = java.util.concurrent.CopyOnWriteArrayList<
            (DiLink5AdbEnableStateStore.FlagMutation) -> Unit
        >()
        var strategy: String? = null
        var strategyExpected: String? = null
        var propertyChanged = false
        var persistSucceeds = true
        private var durable = true
        @Synchronized
        override fun saveFlag(record: DiLink5AdbEnableStateStore.FlagMutation): Boolean {
            val added = synchronized(flags) {
                if (flags.any { it.workerProcess == record.workerProcess && it.path == record.path }) {
                    false
                } else {
                    flags += record
                    true
                }
            }
            durable = persistSucceeds
            if (added) observers.forEach { it(record) }
            return durable
        }
        @Synchronized
        override fun flags() = flags.toList()
        @Synchronized
        override fun clearFlag(record: DiLink5AdbEnableStateStore.FlagMutation): Boolean {
            durable = persistSucceeds
            if (durable) {
                flags.removeAll { it.workerProcess == record.workerProcess && it.path == record.path }
            }
            return durable
        }
        @Synchronized
        override fun saveStrategy(record: DiLink5AdbEnableStateStore.StrategyMutation): Boolean {
            if (strategy == null) {
                strategy = record.priorValue
                strategyExpected = record.expectedValue
            }
            durable = persistSucceeds
            return durable
        }
        override fun strategy() = strategy?.let { prior ->
            DiLink5AdbEnableStateStore.StrategyMutation(prior, strategyExpected)
        }
        @Synchronized
        override fun clearStrategy(): Boolean {
            durable = persistSucceeds
            if (durable) {
                strategy = null
                strategyExpected = null
            }
            return durable
        }
        @Synchronized
        override fun markPropertyMayHaveChanged(changed: Boolean): Boolean {
            durable = persistSucceeds
            if (changed || durable) propertyChanged = changed
            return durable
        }
        override fun propertyMayHaveChanged() = propertyChanged
        override fun ownershipDurable() = durable
        @Synchronized
        override fun <T> withOwnershipSnapshot(
            block: (DiLink5AdbEnableStateStore.OwnershipSnapshot) -> T,
        ): T = block(
            DiLink5AdbEnableStateStore.OwnershipSnapshot(flags.toList(), strategy(), propertyChanged, durable),
        )
        override fun observeFlagOwnership(
            observer: (DiLink5AdbEnableStateStore.FlagMutation) -> Unit,
        ): AutoCloseable {
            observers += observer
            return AutoCloseable { observers -= observer }
        }
    }

    private class FakePage : DiLink5AdbEnableCoordinator.PageLauncher {
        var calls = 0
        var result = DiLink5AdbEnableCoordinator.PageResult.UNAVAILABLE
        override fun launch(): DiLink5AdbEnableCoordinator.PageResult {
            calls++
            return result
        }
    }

    private class FakeMode : DiLink5AdbEnableCoordinator.ModeAuthority {
        var generation: String? = "generation-1"
        override fun admit(): String? = generation
        override fun isCurrent(generation: String): Boolean = this.generation == generation
        fun revoke() { generation = null }
    }

    companion object {
        private val firstCandidate get() = DiLink5AdbWorkerProcess.candidates.first()

        private fun workerResult(
            write: Int?,
            exists: Boolean?,
            prior: String?,
            after: String?,
            replyLostAfterDispatch: Boolean = false,
        ) = DiLink5AdbEnableCoordinator.WorkerResult(
            firstCandidate.processName,
            true,
            true,
            false,
            read(prior),
            write,
            exists,
            read(after),
            "complete",
            writeDispatched = write != null || replyLostAfterDispatch,
            replyLostAfterDispatch = replyLostAfterDispatch,
        )

        private fun read(value: String?) = when (value) {
            null -> DiLink5AccountServiceClient.ReadResult(DiLink5AccountServiceClient.ReadState.UNAVAILABLE)
            "" -> DiLink5AccountServiceClient.ReadResult(DiLink5AccountServiceClient.ReadState.EMPTY, "")
            else -> DiLink5AccountServiceClient.ReadResult(DiLink5AccountServiceClient.ReadState.VALUE, value)
        }
    }
}
