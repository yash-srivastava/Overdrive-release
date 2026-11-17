package com.overdrive.app.launcher.dilink5.enable

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class DiLink5StrategyServiceClientTest {
    @Test
    fun onlyStringVerifyEnableChangesOnTheReturnedObject() {
        val raw = Any()
        val configs = mutableMapOf<Any?, Any?>(
            "verifyEnable" to "1",
            "obdVerifyEnable" to "7",
            "unrelated" to 99,
        )
        var submitted: DiLink5StrategyServiceClient.StrategySnapshot? = null
        val access = object : DiLink5StrategyServiceClient.StrategyAccess {
            override fun get() = DiLink5StrategyServiceClient.StrategySnapshot(raw, configs)
            override fun set(snapshot: DiLink5StrategyServiceClient.StrategySnapshot): Boolean {
                submitted = snapshot
                return true
            }
        }

        val result = DiLink5StrategyServiceClient(access).setVerifyDisabled()

        assertEquals(DiLink5StrategyServiceClient.Outcome.CONFIRMED, result.outcome)
        assertEquals("1", result.priorVerifyEnable)
        assertEquals("7", result.obdVerifyEnable)
        assertEquals("0", configs["verifyEnable"])
        assertEquals("7", configs["obdVerifyEnable"])
        assertEquals(99, configs["unrelated"])
        assertSame(raw, submitted!!.raw)
    }

    @Test
    fun connectivityAndReadbackRemainSeparate() {
        val configs = mutableMapOf<Any?, Any?>("verifyEnable" to "1", "obdVerifyEnable" to "1")
        val access = object : DiLink5StrategyServiceClient.StrategyAccess {
            var reads = 0
            override fun get(): DiLink5StrategyServiceClient.StrategySnapshot {
                reads++
                val returned = if (reads == 1) configs else mutableMapOf<Any?, Any?>("verifyEnable" to "1")
                return DiLink5StrategyServiceClient.StrategySnapshot(Any(), returned)
            }
            override fun set(snapshot: DiLink5StrategyServiceClient.StrategySnapshot) = true
        }

        val result = DiLink5StrategyServiceClient(access).setVerifyDisabled()

        assertEquals(DiLink5StrategyServiceClient.Outcome.CONNECTED_NOT_CONFIRMED, result.outcome)
        assertEquals("1", result.readbackVerifyEnable)
    }

    @Test
    fun currentVerifyEnableReadDoesNotSubmitAChange() {
        var setCalls = 0
        val access = object : DiLink5StrategyServiceClient.StrategyAccess {
            override fun get() = DiLink5StrategyServiceClient.StrategySnapshot(
                Any(),
                mutableMapOf("verifyEnable" to "0", "obdVerifyEnable" to "1"),
            )
            override fun set(snapshot: DiLink5StrategyServiceClient.StrategySnapshot): Boolean {
                setCalls++
                return true
            }
        }

        assertEquals("0", DiLink5StrategyServiceClient(access).readVerifyEnable())
        assertEquals(0, setCalls)
    }

    @Test
    fun restorationMutatesOnlyMatchingVerifyEnableOnReturnedObject() {
        val raw = Any()
        val configs = mutableMapOf<Any?, Any?>(
            "verifyEnable" to "0",
            "obdVerifyEnable" to "7",
            "unrelated" to 99,
        )
        var submitted: DiLink5StrategyServiceClient.StrategySnapshot? = null
        val access = object : DiLink5StrategyServiceClient.StrategyAccess {
            override fun get() = DiLink5StrategyServiceClient.StrategySnapshot(raw, configs)
            override fun set(snapshot: DiLink5StrategyServiceClient.StrategySnapshot): Boolean {
                submitted = snapshot
                return true
            }
        }

        val result = DiLink5StrategyServiceClient(access).restoreVerifyEnable("0", "1")

        assertEquals(DiLink5StrategyServiceClient.Outcome.CONFIRMED, result.outcome)
        assertEquals("1", configs["verifyEnable"])
        assertEquals("7", configs["obdVerifyEnable"])
        assertEquals(99, configs["unrelated"])
        assertSame(raw, submitted!!.raw)
    }

    @Test
    fun restorationRejectsInterleavedStrategyDriftWithoutMutation() {
        val initial = mutableMapOf<Any?, Any?>("verifyEnable" to "0", "obdVerifyEnable" to "1")
        val drifted = mutableMapOf<Any?, Any?>("verifyEnable" to "external", "obdVerifyEnable" to "1")
        val snapshots = ArrayDeque<MutableMap<Any?, Any?>>().apply {
            add(initial)
            add(drifted)
        }
        var setCalls = 0
        val access = object : DiLink5StrategyServiceClient.StrategyAccess {
            override fun get() = DiLink5StrategyServiceClient.StrategySnapshot(
                Any(),
                snapshots.removeFirst(),
            )
            override fun set(snapshot: DiLink5StrategyServiceClient.StrategySnapshot): Boolean {
                setCalls++
                return true
            }
        }
        val client = DiLink5StrategyServiceClient(access)

        assertEquals("0", client.readVerifyEnable())
        val result = client.restoreVerifyEnable("0", "1")

        assertEquals(DiLink5StrategyServiceClient.Outcome.CONNECTED_NOT_CONFIRMED, result.outcome)
        assertEquals(0, setCalls)
        assertEquals("external", drifted["verifyEnable"])
    }

    @Test
    fun restorationRejectsUnreadableCurrentValueWithoutMutation() {
        val configs = mutableMapOf<Any?, Any?>("verifyEnable" to 0, "obdVerifyEnable" to "1")
        var setCalls = 0
        val access = object : DiLink5StrategyServiceClient.StrategyAccess {
            override fun get() = DiLink5StrategyServiceClient.StrategySnapshot(Any(), configs)
            override fun set(snapshot: DiLink5StrategyServiceClient.StrategySnapshot): Boolean {
                setCalls++
                return true
            }
        }

        val result = DiLink5StrategyServiceClient(access).restoreVerifyEnable("0", "1")

        assertEquals(DiLink5StrategyServiceClient.Outcome.CONNECTED_NOT_CONFIRMED, result.outcome)
        assertEquals(0, setCalls)
        assertEquals(0, configs["verifyEnable"])
    }

    @Test
    fun preInvocationFailureIsNotOwnedAndRestoresLocalSnapshot() {
        val configs = mutableMapOf<Any?, Any?>(
            "verifyEnable" to "1",
            "obdVerifyEnable" to "7",
        )
        val access = object : DiLink5StrategyServiceClient.StrategyAccess {
            override fun get() = DiLink5StrategyServiceClient.StrategySnapshot(Any(), configs)
            override fun set(snapshot: DiLink5StrategyServiceClient.StrategySnapshot) = false
            override fun setIfCurrent(
                snapshot: DiLink5StrategyServiceClient.StrategySnapshot,
                isCurrent: () -> Boolean,
            ) = DiLink5StrategyServiceClient.DispatchResult.NOT_DISPATCHED
        }

        val result = DiLink5StrategyServiceClient(access).setVerifyDisabled()

        assertEquals(DiLink5StrategyServiceClient.Outcome.NOT_DISPATCHED, result.outcome)
        assertEquals("1", configs["verifyEnable"])
    }

    @Test
    fun finalAuthorityRevocationPreventsStrategyDispatch() {
        val configs = mutableMapOf<Any?, Any?>(
            "verifyEnable" to "1",
            "obdVerifyEnable" to "7",
        )
        var current = true
        var setCalls = 0
        val access = object : DiLink5StrategyServiceClient.StrategyAccess {
            override fun get() = DiLink5StrategyServiceClient.StrategySnapshot(Any(), configs)
            override fun set(snapshot: DiLink5StrategyServiceClient.StrategySnapshot): Boolean {
                setCalls++
                return true
            }
            override fun setIfCurrent(
                snapshot: DiLink5StrategyServiceClient.StrategySnapshot,
                isCurrent: () -> Boolean,
            ): DiLink5StrategyServiceClient.DispatchResult {
                current = false
                return if (isCurrent()) {
                    DiLink5StrategyServiceClient.DispatchResult.ACCEPTED
                } else {
                    DiLink5StrategyServiceClient.DispatchResult.CANCELLED
                }
            }
        }

        val result = DiLink5StrategyServiceClient(access).setVerifyDisabled { current }

        assertEquals(DiLink5StrategyServiceClient.Outcome.CANCELLED, result.outcome)
        assertEquals(0, setCalls)
        assertEquals("1", configs["verifyEnable"])
    }

    @Test
    fun unreadableVerifyEnableFailsClosedWithoutStrategySet() {
        val configs = listOf(
            mutableMapOf<Any?, Any?>("obdVerifyEnable" to "1"),
            mutableMapOf<Any?, Any?>("verifyEnable" to null, "obdVerifyEnable" to "1"),
            mutableMapOf<Any?, Any?>("verifyEnable" to 1, "obdVerifyEnable" to "1"),
        )

        configs.forEach { values ->
            val before = values.toMap()
            var setCalls = 0
            val access = object : DiLink5StrategyServiceClient.StrategyAccess {
                override fun get() = DiLink5StrategyServiceClient.StrategySnapshot(Any(), values)
                override fun set(snapshot: DiLink5StrategyServiceClient.StrategySnapshot): Boolean {
                    setCalls++
                    return true
                }
            }

            val result = DiLink5StrategyServiceClient(access).setVerifyDisabled()

            assertEquals(DiLink5StrategyServiceClient.Outcome.CONNECTED_NOT_CONFIRMED, result.outcome)
            assertEquals(0, setCalls)
            assertEquals(before, values)
        }
    }
}
