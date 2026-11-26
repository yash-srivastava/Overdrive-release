package com.overdrive.app.util

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class RotatingAnonymousIdTest {
    private val day = 24L * 60L * 60L * 1000L
    private val now = 200L * day

    private class Store {
        val sections = mutableMapOf<String, JSONObject>()
        var reads = 0
        var writes = 0

        fun read(name: String): JSONObject? {
            reads++
            return sections[name]
        }

        fun write(name: String, patch: JSONObject): Boolean {
            writes++
            assertEquals(2, patch.length())
            val target = sections.getOrPut(name) { JSONObject() }
            for (key in patch.keys()) target.put(key, patch.get(key))
            return true
        }
    }

    @Test
    fun identitiesStayIndependentWithDifferentRotationWindows() {
        val store = Store()
        val road = RotatingAnonymousId("roadSense", "deviceId", "deviceIdMintedMs", 30 * day,
            store::read, store::write)
        val analytics = RotatingAnonymousId("analytics", "installId", "installIdMintedMs", 180 * day,
            store::read, store::write)
        val r = road.current(now)
        val a = analytics.current(now)
        assertNotEquals(r, a)
        assertEquals(4, UUID.fromString(r).version())
        assertEquals(4, UUID.fromString(a).version())
        assertEquals(r, road.current(now + 30 * day - 1))
        assertEquals(a, analytics.current(now + 30 * day))
        assertNotEquals(r, road.current(now + 30 * day))
        assertEquals(a, analytics.current(now + 180 * day - 1))
        assertNotEquals(a, analytics.current(now + 180 * day))
        assertEquals(4, store.writes)
        assertTrue(!store.sections.getValue("roadSense").has("installId"))
        assertTrue(!store.sections.getValue("analytics").has("deviceId"))
    }

    @Test
    fun preloadedSectionAvoidsReloadAndWritesOnlyWhenExpired() {
        val store = Store()
        val id = RotatingAnonymousId("analytics", "installId", "installIdMintedMs", 180 * day,
            store::read, store::write)
        val data = JSONObject().put("installId", "existing").put("installIdMintedMs", now)
            .put("enabled", true)
        store.sections["analytics"] = data
        assertEquals("existing", id.current(now + day, data))
        assertEquals(0, store.writes)
        val fresh = id.current(now + 180 * day, data)
        assertEquals(0, store.reads)
        assertEquals(1, store.writes)
        assertEquals(fresh, data.getString("installId"))
        assertEquals(now + 180 * day, data.getLong("installIdMintedMs"))
        assertTrue(data.getBoolean("enabled"))
    }

    @Test
    fun emptyIdMintsEvenIfTimestampIsRecent() {
        val store = Store()
        store.sections["roadSense"] = JSONObject().put("deviceId", "").put("deviceIdMintedMs", now)
        val id = RotatingAnonymousId("roadSense", "deviceId", "deviceIdMintedMs", 30 * day,
            store::read, store::write)
        val minted = id.current(now)
        assertEquals(4, UUID.fromString(minted).version())
        assertEquals(minted, id.current(now))
        assertEquals(1, store.writes)
    }

    @Test
    fun readAndWriteExceptionsStillReturnFreshIds() {
        val id = RotatingAnonymousId("roadSense", "deviceId", "deviceIdMintedMs", 30 * day,
            { error("read failed") }, { _, _ -> error("write failed") })
        val first = id.current(now)
        val second = id.current(now)
        assertEquals(4, UUID.fromString(first).version())
        assertEquals(4, UUID.fromString(second).version())
        assertNotEquals(first, second)
    }

    @Test
    fun falseWriteResultStillReturnsFreshId() {
        val id = RotatingAnonymousId("analytics", "installId", "installIdMintedMs", 180 * day,
            { null }, { _, _ -> false })
        assertEquals(4, UUID.fromString(id.current(now)).version())
    }
}
