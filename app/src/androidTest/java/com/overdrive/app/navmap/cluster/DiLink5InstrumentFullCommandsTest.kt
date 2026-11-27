package com.overdrive.app.navmap.cluster

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.overdrive.app.navmap.cluster.DiLink5InstrumentFullSession.Owner
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DiLink5InstrumentFullCommandsTest {
    private val owner = Owner(42, "boot-id")

    @Test
    fun temporaryFullUsesOnlyDirectMapTypeOnOpenAndClose() {
        assertCommands(
            owner,
            message(31, "subtype" to 3, "mode" to 4),
            message(31, "subtype" to 15, "mapType" to 1, "state" to 6),
            message(35, "subtype" to 2, "mapType" to 1, "naviState" to 0),
            message(31, "subtype" to 14, "mapType" to 1),
        )
        assertCommands(
            owner.copy(closing = true),
            message(31, "subtype" to 15, "mapType" to 1, "state" to 1),
            message(35, "subtype" to 2, "mapType" to 1, "naviState" to 1),
            message(31, "subtype" to 14, "mapType" to 0),
            message(31, "subtype" to 3, "mode" to 1),
        )
    }

    @Test
    fun recoveryOfLegacyOwnerAbandonsPreviouslyRequestedFocus() {
        assertCommands(
            owner.copy(closing = true, legacyMapFocus = true),
            message(31, "subtype" to 15, "mapType" to 1, "state" to 1),
            message(35, "subtype" to 2, "mapType" to 1, "naviState" to 1),
            message(34, "mapType" to 1),
            message(31, "subtype" to 14, "mapType" to 0),
            message(31, "subtype" to 3, "mode" to 1),
        )
    }

    @Suppress("DEPRECATION")
    private fun assertCommands(owner: Owner, vararg expected: Map<String, Int>) {
        val commands = DiLink5InstrumentFullSession.commands(owner)
        assertEquals(owner.expectedCommandCount, commands.size)
        assertEquals(expected.toList(), commands.map { bundle -> bundle.keySet().associateWith { bundle.get(it) } })
    }

    private fun message(type: Int, vararg data: Pair<String, Int>): Map<String, Int> =
        mapOf("msgType" to 3, "DataType" to type, *data)
}
