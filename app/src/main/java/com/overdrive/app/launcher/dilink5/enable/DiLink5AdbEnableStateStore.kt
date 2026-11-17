package com.overdrive.app.launcher.dilink5.enable

import android.content.Context
import java.util.concurrent.CopyOnWriteArrayList

/** Process-wide app-private ownership records for only mutations this app actually attempted. */
class DiLink5AdbEnableStateStore private constructor(context: Context) {
    enum class MutationCertainty { CONFIRMED, MAY_HAVE_CHANGED }
    enum class OwnershipSource { ORDINARY, DISCONNECT_RETAINED, LATE_RESULT }

    data class FlagMutation(
        val workerProcess: String,
        val path: String,
        val priorState: DiLink5AccountServiceClient.ReadState,
        val priorValue: String?,
        val expectedValue: String? = DiLink5AccountServiceClient.ENABLE_CONTENT,
        val certainty: MutationCertainty = MutationCertainty.CONFIRMED,
        val source: OwnershipSource = OwnershipSource.ORDINARY,
    )

    data class StrategyMutation(
        val priorValue: String,
        val expectedValue: String?,
    )

    data class OwnershipSnapshot(
        val flags: List<FlagMutation>,
        val strategy: StrategyMutation?,
        val propertyMayHaveChanged: Boolean,
        val durable: Boolean,
    )

    private val preferences = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val flags = DiLink5AdbOwnershipRecords(loadFlagMutations(), ::writeFlagMutations)
    private val ownershipObservers = CopyOnWriteArrayList<(FlagMutation) -> Unit>()
    private var strategyRecord = loadStrategyMutation()
    private var strategyDurable = true
    private var propertyChanged = preferences.getBoolean(KEY_PROPERTY_MAY_HAVE_CHANGED, false)
    private var propertyDurable = true

    @Synchronized
    fun saveFlagMutation(record: FlagMutation): Boolean {
        val saved = flags.save(record)
        if (saved.added) ownershipObservers.forEach { it(record) }
        return saved.durable
    }

    fun observeFlagMutations(observer: (FlagMutation) -> Unit): AutoCloseable {
        ownershipObservers += observer
        return AutoCloseable { ownershipObservers -= observer }
    }

    @Synchronized
    fun flagMutations(): List<FlagMutation> = flags.snapshot()

    @Synchronized
    fun clearFlagMutation(record: FlagMutation): Boolean = flags.clear(record)

    @Synchronized
    fun saveStrategyMutation(record: StrategyMutation): Boolean {
        val retained = strategyRecord ?: record.also { strategyRecord = it }
        if (retained !== record && strategyDurable) return true
        strategyDurable = persist {
            preferences.edit()
                .putBoolean(KEY_STRATEGY_PRIOR_PRESENT, true)
                .putString(KEY_STRATEGY_PRIOR, retained.priorValue)
                .putString(KEY_STRATEGY_EXPECTED, retained.expectedValue)
                .commit()
        }
        return strategyDurable
    }

    @Synchronized
    fun strategyMutation(): StrategyMutation? = strategyRecord

    private fun loadStrategyMutation(): StrategyMutation? {
        if (!preferences.getBoolean(KEY_STRATEGY_PRIOR_PRESENT, false)) return null
        val prior = preferences.getString(KEY_STRATEGY_PRIOR, null) ?: return null
        val expected = preferences.getString(KEY_STRATEGY_EXPECTED, null)
        return StrategyMutation(prior, expected)
    }

    @Synchronized
    fun clearStrategyMutation(): Boolean {
        strategyDurable = persist {
            preferences.edit()
                .remove(KEY_STRATEGY_PRIOR_PRESENT)
                .remove(KEY_STRATEGY_PRIOR)
                .remove(KEY_STRATEGY_EXPECTED)
                .commit()
        }
        if (strategyDurable) strategyRecord = null
        return strategyDurable
    }

    @Synchronized
    fun markPropertyMayHaveChanged(changed: Boolean): Boolean {
        if (changed) propertyChanged = true
        propertyDurable = persist {
            preferences.edit().putBoolean(KEY_PROPERTY_MAY_HAVE_CHANGED, changed).commit()
        }
        if (propertyDurable) propertyChanged = changed
        return propertyDurable
    }

    @Synchronized
    fun propertyMayHaveChanged(): Boolean = propertyChanged

    @Synchronized
    fun ownershipDurable(): Boolean = flags.isDurable() && strategyDurable && propertyDurable

    /** Late owners cannot be added between this snapshot and the result built from it. */
    @Synchronized
    fun <T> withOwnershipSnapshot(block: (OwnershipSnapshot) -> T): T = block(
        OwnershipSnapshot(flags.snapshot(), strategyRecord, propertyChanged, ownershipDurable()),
    )

    private fun persist(write: () -> Boolean): Boolean = try {
        write()
    } catch (_: Throwable) {
        false
    }

    private fun loadFlagMutations(): List<FlagMutation> {
        val count = preferences.getInt(KEY_FLAG_COUNT, -1)
        if (count >= 0) {
            return (0 until count).mapNotNull(::readIndexedFlagMutation)
        }
        return legacyFlagMutation()?.let(::listOf).orEmpty()
    }

    private fun legacyFlagMutation(): FlagMutation? {
        val worker = preferences.getString(KEY_FLAG_WORKER, null) ?: return null
        val path = preferences.getString(KEY_FLAG_PATH, null) ?: return null
        val state = readState(preferences.getString(KEY_FLAG_PRIOR_STATE, null)) ?: return null
        return FlagMutation(
            workerProcess = worker,
            path = path,
            priorState = state,
            priorValue = preferences.getString(KEY_FLAG_PRIOR_VALUE, null),
            expectedValue = null,
            certainty = MutationCertainty.CONFIRMED,
            source = OwnershipSource.ORDINARY,
        )
    }

    private fun readIndexedFlagMutation(index: Int): FlagMutation? {
        val worker = preferences.getString(flagKey(index, SUFFIX_WORKER), null) ?: return null
        val path = preferences.getString(flagKey(index, SUFFIX_PATH), null) ?: return null
        val state = readState(preferences.getString(flagKey(index, SUFFIX_PRIOR_STATE), null)) ?: return null
        return FlagMutation(
            workerProcess = worker,
            path = path,
            priorState = state,
            priorValue = preferences.getString(flagKey(index, SUFFIX_PRIOR_VALUE), null),
            expectedValue = preferences.getString(flagKey(index, SUFFIX_EXPECTED_VALUE), null),
            certainty = readCertainty(preferences.getString(flagKey(index, SUFFIX_CERTAINTY), null)),
            source = readSource(
                preferences.getString(flagKey(index, SUFFIX_SOURCE), null),
                preferences.getBoolean(flagKey(index, SUFFIX_LATE), false),
            ),
        )
    }

    private fun readState(value: String?): DiLink5AccountServiceClient.ReadState? = try {
        value?.let(DiLink5AccountServiceClient.ReadState::valueOf)
    } catch (_: IllegalArgumentException) {
        null
    }

    private fun readCertainty(value: String?): MutationCertainty = try {
        value?.let(MutationCertainty::valueOf) ?: MutationCertainty.CONFIRMED
    } catch (_: IllegalArgumentException) {
        MutationCertainty.CONFIRMED
    }

    private fun readSource(value: String?, legacyLate: Boolean): OwnershipSource = try {
        value?.let(OwnershipSource::valueOf)
            ?: if (legacyLate) OwnershipSource.LATE_RESULT else OwnershipSource.ORDINARY
    } catch (_: IllegalArgumentException) {
        OwnershipSource.ORDINARY
    }

    private fun writeFlagMutations(records: List<FlagMutation>): Boolean {
        val previousCount = preferences.getInt(KEY_FLAG_COUNT, 0).coerceAtLeast(records.size)
        val editor = preferences.edit()
            .putInt(KEY_FLAG_COUNT, records.size)
            .remove(KEY_FLAG_WORKER)
            .remove(KEY_FLAG_PATH)
            .remove(KEY_FLAG_PRIOR_STATE)
            .remove(KEY_FLAG_PRIOR_VALUE)
        for (index in 0 until previousCount) {
            editor.remove(flagKey(index, SUFFIX_WORKER))
                .remove(flagKey(index, SUFFIX_PATH))
                .remove(flagKey(index, SUFFIX_PRIOR_STATE))
                .remove(flagKey(index, SUFFIX_PRIOR_VALUE))
                .remove(flagKey(index, SUFFIX_EXPECTED_VALUE))
                .remove(flagKey(index, SUFFIX_CERTAINTY))
                .remove(flagKey(index, SUFFIX_SOURCE))
                .remove(flagKey(index, SUFFIX_LATE))
        }
        records.forEachIndexed { index, record ->
            editor.putString(flagKey(index, SUFFIX_WORKER), record.workerProcess)
                .putString(flagKey(index, SUFFIX_PATH), record.path)
                .putString(flagKey(index, SUFFIX_PRIOR_STATE), record.priorState.name)
                .putString(flagKey(index, SUFFIX_PRIOR_VALUE), record.priorValue)
                .putString(flagKey(index, SUFFIX_EXPECTED_VALUE), record.expectedValue)
                .putString(flagKey(index, SUFFIX_CERTAINTY), record.certainty.name)
                .putString(flagKey(index, SUFFIX_SOURCE), record.source.name)
                .remove(flagKey(index, SUFFIX_LATE))
        }
        return editor.commit()
    }

    companion object {
        private const val PREFS = "dilink5_manual_adb_enablement"
        private const val KEY_FLAG_COUNT = "flag_count"
        private const val KEY_FLAG_WORKER = "flag_worker"
        private const val KEY_FLAG_PATH = "flag_path"
        private const val KEY_FLAG_PRIOR_STATE = "flag_prior_state"
        private const val KEY_FLAG_PRIOR_VALUE = "flag_prior_value"
        private const val KEY_STRATEGY_PRIOR_PRESENT = "strategy_prior_present"
        private const val KEY_STRATEGY_PRIOR = "strategy_prior"
        private const val KEY_STRATEGY_EXPECTED = "strategy_expected"
        private const val KEY_PROPERTY_MAY_HAVE_CHANGED = "property_may_have_changed"
        private const val SUFFIX_WORKER = "worker"
        private const val SUFFIX_PATH = "path"
        private const val SUFFIX_PRIOR_STATE = "prior_state"
        private const val SUFFIX_PRIOR_VALUE = "prior_value"
        private const val SUFFIX_EXPECTED_VALUE = "expected_value"
        private const val SUFFIX_CERTAINTY = "certainty"
        private const val SUFFIX_SOURCE = "source"
        private const val SUFFIX_LATE = "late"

        @Volatile
        private var instance: DiLink5AdbEnableStateStore? = null

        fun forContext(context: Context): DiLink5AdbEnableStateStore =
            instance ?: synchronized(this) {
                instance ?: DiLink5AdbEnableStateStore(context.applicationContext).also { instance = it }
            }

        private fun flagKey(index: Int, suffix: String) = "flag.$index.$suffix"
    }
}

/** Serializes read-modify-persist operations so late and current callbacks cannot lose an owner. */
internal class DiLink5AdbOwnershipRecords(
    initial: List<DiLink5AdbEnableStateStore.FlagMutation>,
    private val persist: (List<DiLink5AdbEnableStateStore.FlagMutation>) -> Boolean,
) {
    data class SaveResult(val added: Boolean, val durable: Boolean)

    private var records = initial.toList()
    private var durable = true

    @Synchronized
    fun save(record: DiLink5AdbEnableStateStore.FlagMutation): SaveResult {
        val added = records.none { it.workerProcess == record.workerProcess && it.path == record.path }
        if (added) records = records + record
        if (added || !durable) durable = persistSafely(records)
        return SaveResult(added, durable)
    }

    @Synchronized
    fun snapshot(): List<DiLink5AdbEnableStateStore.FlagMutation> = records.toList()

    @Synchronized
    fun isDurable(): Boolean = durable

    @Synchronized
    fun clear(record: DiLink5AdbEnableStateStore.FlagMutation): Boolean {
        val updated = records.filterNot {
            it.workerProcess == record.workerProcess && it.path == record.path
        }
        if (updated.size == records.size && durable) return true
        durable = persistSafely(updated)
        if (durable) records = updated
        return durable
    }

    private fun persistSafely(snapshot: List<DiLink5AdbEnableStateStore.FlagMutation>): Boolean = try {
        persist(snapshot)
    } catch (_: Throwable) {
        false
    }
}
