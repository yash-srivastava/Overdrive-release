package com.overdrive.app.launcher.adb

import com.overdrive.app.config.UnifiedConfigManager
import org.json.JSONObject

/** Durable recovery preferences, cooldowns, ownership, and retry verdicts. */
interface AdbRecoveryStateStore {
    fun load(): DiLink5AdbRecoveryStateStore.Snapshot
    fun setAutomaticRestore(enabled: Boolean): Boolean
    fun setDialogAutoConfirm(enabled: Boolean, networkId: String?): Boolean
    fun recordWrite(networkId: String, atMs: Long, generation: Long, priorValue: Int): Boolean
    fun recordRetry(networkId: String, verdict: String, exhausted: Boolean): Boolean
    fun clearExhaustion(): Boolean
    fun transferOwnership(fromGeneration: Long, toGeneration: Long): Boolean
    fun clearOwnership(generation: Long): Boolean
}

class DiLink5AdbRecoveryStateStore internal constructor(
    private val readSection: () -> JSONObject = {
        sectionFromDurableRoot(UnifiedConfigManager.readDurableConfigStrict())
    },
    private val writeSection: (JSONObject) -> Boolean = {
        UnifiedConfigManager.updateSection(SECTION, it)
    },
) : AdbRecoveryStateStore {
    data class Snapshot(
        val automaticRestore: Boolean,
        val dialogAutoConfirm: Boolean,
        val dialogAutoConfirmNetwork: String?,
        val networkWriteTimes: Map<String, Long>,
        val exhaustedNetwork: String?,
        val retryVerdict: String?,
        val settingOwnerGeneration: Long?,
        val settingOwnerNetwork: String?,
        val priorSettingValue: Int?,
        val ownershipJournalInvalid: Boolean = false,
    ) {
        val hasSettingOwnershipJournal: Boolean
            get() = ownershipJournalInvalid || settingOwnerGeneration != null ||
                settingOwnerNetwork != null || priorSettingValue != null
    }

    override fun load(): Snapshot = synchronized(lock) {
        val section = readSection()
        val times = linkedMapOf<String, Long>()
        section.optJSONObject("networkWriteTimes")?.let { values ->
            val keys = values.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                times[key] = values.optLong(key, 0L)
            }
        }
        val rawGeneration = section.opt("settingOwnerGeneration")
        val rawNetwork = section.opt("settingOwnerNetwork")
        val rawPrior = section.opt("priorSettingValue")
        val parsedGeneration = integerValue(rawGeneration)
        val ownerGeneration = parsedGeneration?.takeIf { it >= 0L }
        val ownerNetwork = (rawNetwork as? String)?.takeIf { it.isNotBlank() }
        val prior = integerValue(rawPrior)?.takeIf {
            it in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()
        }?.toInt()
        val cleared = (absent(rawGeneration) || parsedGeneration == -1L) &&
            (absent(rawNetwork) || (rawNetwork is String && rawNetwork.isBlank())) &&
            absent(rawPrior)
        val complete = ownerGeneration != null && ownerNetwork != null && prior != null
        Snapshot(
            automaticRestore = section.optBoolean("automaticRestore", false),
            dialogAutoConfirm = section.optBoolean("dialogAutoConfirm", false),
            dialogAutoConfirmNetwork = section.optString("dialogAutoConfirmNetwork", "")
                .ifBlank { null },
            networkWriteTimes = times,
            exhaustedNetwork = section.optString("exhaustedNetwork", "").ifBlank { null },
            retryVerdict = section.optString("retryVerdict", "").ifBlank { null },
            settingOwnerGeneration = ownerGeneration,
            settingOwnerNetwork = ownerNetwork,
            priorSettingValue = prior,
            ownershipJournalInvalid = !cleared && !complete,
        )
    }

    override fun setAutomaticRestore(enabled: Boolean): Boolean = update(JSONObject().put("automaticRestore", enabled))
    override fun setDialogAutoConfirm(enabled: Boolean, networkId: String?): Boolean {
        if (enabled && networkId.isNullOrBlank()) return false
        return update(JSONObject()
            .put("dialogAutoConfirm", enabled)
            .put("dialogAutoConfirmNetwork", if (enabled) networkId else ""))
    }

    override fun recordWrite(
        networkId: String,
        atMs: Long,
        generation: Long,
        priorValue: Int,
    ): Boolean = synchronized(lock) {
        if (networkId.isBlank() || generation < 0L) return@synchronized false
        val snapshot = load()
        if (snapshot.ownershipJournalInvalid ||
            (snapshot.hasSettingOwnershipJournal &&
                (snapshot.settingOwnerGeneration != generation ||
                    snapshot.settingOwnerNetwork != networkId))
        ) return@synchronized false
        val preservedPriorValue = priorValueForWrite(snapshot, networkId, generation, priorValue)
        val times = JSONObject()
        snapshot.networkWriteTimes.forEach { (network, time) -> times.put(network, time) }
        times.put(networkId, atMs)
        update(JSONObject()
            .put("networkWriteTimes", times)
            .put("settingOwnerGeneration", generation)
            .put("settingOwnerNetwork", networkId)
            .put("priorSettingValue", preservedPriorValue))
    }

    override fun recordRetry(networkId: String, verdict: String, exhausted: Boolean): Boolean = synchronized(lock) {
        val current = load()
        update(JSONObject()
            .put("retryVerdict", verdict)
            .put(
                "exhaustedNetwork",
                if (exhausted) networkId else current.exhaustedNetwork.orEmpty(),
            ))
    }

    override fun clearExhaustion(): Boolean = update(JSONObject()
        .put("retryVerdict", "")
        .put("exhaustedNetwork", ""))

    override fun transferOwnership(fromGeneration: Long, toGeneration: Long): Boolean = synchronized(lock) {
        val current = load()
        if (toGeneration < 0L || current.ownershipJournalInvalid ||
            current.settingOwnerGeneration != fromGeneration
        ) return@synchronized false
        update(JSONObject().put("settingOwnerGeneration", toGeneration))
    }

    override fun clearOwnership(generation: Long): Boolean = synchronized(lock) {
        val current = load()
        if (current.ownershipJournalInvalid || current.settingOwnerGeneration != generation) {
            return@synchronized false
        }
        update(JSONObject()
            .put("settingOwnerGeneration", -1L)
            .put("settingOwnerNetwork", "")
            .put("priorSettingValue", JSONObject.NULL))
    }

    private fun update(values: JSONObject): Boolean = synchronized(lock) {
        // A cached/default root after an I/O failure cannot authorize a durable mutation.
        readSection()
        writeSection(values)
    }

    companion object {
        const val SECTION = "adbRecovery"
        private val lock = Any()

        internal fun sectionFromDurableRoot(root: JSONObject): JSONObject {
            if (!root.has(SECTION)) return JSONObject()
            return root.get(SECTION) as? JSONObject
                ?: throw IllegalStateException("Durable ADB recovery section is not an object")
        }

        private fun absent(value: Any?): Boolean = value == null || value === JSONObject.NULL

        private fun integerValue(value: Any?): Long? = when (value) {
            is Byte -> value.toLong()
            is Short -> value.toLong()
            is Int -> value.toLong()
            is Long -> value
            else -> null
        }

        internal fun priorValueForWrite(
            snapshot: Snapshot,
            networkId: String,
            generation: Long,
            proposedPriorValue: Int,
        ): Int = if (snapshot.settingOwnerGeneration == generation &&
            snapshot.settingOwnerNetwork == networkId
        ) {
            snapshot.priorSettingValue ?: proposedPriorValue
        } else {
            proposedPriorValue
        }
    }
}
