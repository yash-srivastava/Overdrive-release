package com.overdrive.app.widgets

import com.overdrive.app.server.Messages
import com.overdrive.app.config.UnifiedConfigManager
import com.overdrive.app.logging.DaemonLogger
import com.overdrive.app.util.ScratchRoot
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.SecureRandom

/**
 * User-made glass widget docs, written only by the daemon. The overlay re-reads the file when
 * `glassWidgets.customVersion` moves; `glassWidgets.custom` lists the car ids on screen, in order.
 */
object CustomWidgetStore {

    data class Entry(val id: String, val doc: JSONObject, val updatedMs: Long, val source: String, val communityId: String?)

    class Full(message: String) : Exception(message)
    class TooLarge(message: String) : Exception(message)
    /** The file or config write failed; whatever already landed stays, and repeating the call is safe. */
    class Unavailable(message: String) : Exception(message)

    const val MAX_WIDGETS = 32
    const val MAX_BYTES = 16 * 1024
    const val SOURCE_LOCAL = "local"
    const val SOURCE_COMMUNITY = "community"
    /** Car-side id prefix, as used in `glassWidgets.custom` and `glassWidgets.layout`. */
    const val CAR_PREFIX = "custom:"

    private const val SECTION = "glassWidgets"
    private const val KEY_SHOWN = "custom"
    private const val KEY_VERSION = "customVersion"
    private const val KEY_LAYOUT = "layout"
    private const val MAX_COMMUNITY_ID = 100
    private const val MAX_SET_ASIDE = 3
    private const val STALE_TMP_MS = 5 * 60_000L
    private const val ID_CHARS = "abcdefghijklmnopqrstuvwxyz0123456789"
    private val BUSY: String get() = Messages.get("errors.widgets_settings_busy")
    private val NOT_REFRESHED: String get() = Messages.get("errors.widgets_saved_not_refreshed")
    private val ID = Regex("[a-z0-9-]{8,40}")
    private val logger = DaemonLogger.getInstance("CustomWidgetStore")
    private val lock = Any()
    private val random by lazy { SecureRandom() }
    private var swept = false

    /** [corrupt]: the file isn't JSON and is set aside on the next write; [unreadable]: it couldn't be read at all. */
    private class Snapshot(
        val version: Int,
        val entries: MutableList<Entry>,
        val corrupt: Boolean = false,
        val unreadable: Boolean = false,
    )

    private fun file() = File(ScratchRoot.path("overdrive_custom_widgets.json"))

    fun isValidId(id: String?): Boolean = id != null && ID.matches(id)

    /** A fresh random id for a new entry. */
    fun newId(): String = String(CharArray(16) { ID_CHARS[random.nextInt(ID_CHARS.length)] })

    fun list(): List<Entry> = synchronized(lock) { load().entries.toList() }

    fun get(id: String): Entry? = synchronized(lock) { load().entries.firstOrNull { it.id == id } }

    /** Creates or replaces [id] with exactly this origin; [canonicalDoc] comes from `CustomDoc.canonical`. */
    fun put(id: String, canonicalDoc: String, source: String = SOURCE_LOCAL, communityId: String? = null): Entry =
        upsert(id, canonicalDoc) { source to communityId }

    /** Like [put], but an existing entry keeps its source and communityId. */
    fun edit(id: String, canonicalDoc: String): Entry =
        upsert(id, canonicalDoc) { prev -> (prev?.source ?: SOURCE_LOCAL) to prev?.communityId }

    /** Removes the entry and its car references (shown list, layout spot); true when anything was removed. */
    fun delete(id: String): Boolean {
        if (!isValidId(id)) return false
        val carId = CAR_PREFIX + id
        synchronized(lock) {
            val store = loadForChange()
            val present = store.entries.any { it.id == id }
            val cached = cachedSection()
            val referenced = listed(cached?.optJSONArray(KEY_SHOWN), carId) ||
                cached?.optJSONObject(KEY_LAYOUT)?.has(carId) == true
            if (!present && !referenced) return false
            // the car lets go of it before its doc goes, so a failure in between never leaves it shown without one
            val version = nextVersion(store)
            publish(version) { section, patch ->
                val shown = section.optJSONArray(KEY_SHOWN)
                if (listed(shown, carId)) patch.put(KEY_SHOWN, shownList(shown, carId, false))
                val layout = section.optJSONObject(KEY_LAYOUT)
                if (layout != null && layout.has(carId)) {
                    layout.remove(carId)
                    patch.put(KEY_LAYOUT, layout)
                }
            }
            if (present) {
                store.entries.removeAll { it.id == id }
                write(store, version, Messages.get("errors.widgets_remove_failed"))
            }
            return true
        }
    }

    /** Appends [id] to the car's shown list or takes it off; an already shown widget keeps its place. */
    fun setShown(id: String, shown: Boolean) {
        synchronized(lock) {
            require(isValidId(id)) { "invalid id" }
            val store = loadForChange()
            if (shown && store.entries.none { it.id == id }) throw NoSuchElementException("no widget $id")
            publish(nextVersion(store)) { section, patch ->
                patch.put(KEY_SHOWN, shownList(section.optJSONArray(KEY_SHOWN), CAR_PREFIX + id, shown))
            }
        }
    }

    /** Store ids on the car's shown list, in order. */
    fun shownIds(): List<String> {
        val arr = cachedSection()?.optJSONArray(KEY_SHOWN) ?: return emptyList()
        val out = ArrayList<String>()
        for (i in 0 until arr.length()) {
            val v = arr.opt(i) as? String ?: continue
            if (!v.startsWith(CAR_PREFIX)) continue
            val id = v.substring(CAR_PREFIX.length)
            if (isValidId(id) && id !in out) out.add(id)
        }
        return out
    }

    /** Links an entry to its community copy (null unlinks); an unknown id is a no-op. */
    fun setCommunityId(id: String, communityId: String?) {
        synchronized(lock) {
            require(communityId == null || communityId.length in 1..MAX_COMMUNITY_ID) { "invalid communityId" }
            val store = loadForChange()
            val at = store.entries.indexOfFirst { it.id == id }
            if (at < 0 || store.entries[at].communityId == communityId) return
            store.entries[at] = store.entries[at].copy(communityId = communityId)
            val version = nextVersion(store)
            write(store, version)
            publish(version)
        }
    }

    private fun upsert(id: String, canonicalDoc: String, origin: (Entry?) -> Pair<String, String?>): Entry = synchronized(lock) {
        require(isValidId(id)) { "invalid id" }
        val doc = try {
            JSONObject(canonicalDoc)
        } catch (e: JSONException) {
            throw IllegalArgumentException("doc is not a JSON object")
        }
        // measured as the car parses it: re-serialised from the file, where Android escapes every '/'
        if (doc.toString().toByteArray(Charsets.UTF_8).size > MAX_BYTES) {
            throw TooLarge(Messages.get("errors.widgets_too_big_kb", (MAX_BYTES / 1024).toString()))
        }
        val store = loadForChange()
        val at = store.entries.indexOfFirst { it.id == id }
        val prev = store.entries.getOrNull(at)
        if (prev == null && store.entries.size >= MAX_WIDGETS) {
            throw Full(Messages.get("errors.widgets_full", MAX_WIDGETS.toString()))
        }
        val (source, communityId) = origin(prev)
        require(source == SOURCE_LOCAL || source == SOURCE_COMMUNITY) { "invalid source" }
        require(communityId == null || communityId.length in 1..MAX_COMMUNITY_ID) { "invalid communityId" }
        val version = nextVersion(store)
        // an identical save (a retry after a failed refresh) only refreshes the car
        val unchanged = prev != null && prev.source == source && prev.communityId == communityId &&
            prev.doc.toString() == doc.toString()
        val entry = if (unchanged) prev!! else Entry(id, doc, System.currentTimeMillis(), source, communityId)
        if (!unchanged) {
            if (prev != null) store.entries[at] = entry else store.entries.add(entry)
            write(store, version)
        }
        publish(version, NOT_REFRESHED)
        entry
    }

    private fun listed(arr: JSONArray?, carId: String): Boolean {
        if (arr == null) return false
        for (i in 0 until arr.length()) if (arr.opt(i) == carId) return true
        return false
    }

    /** [current] with [carId] kept once in place (or appended) when [shown], dropped otherwise; other ids untouched. */
    private fun shownList(current: JSONArray?, carId: String, shown: Boolean): JSONArray {
        val src = current ?: JSONArray()
        val next = JSONArray()
        var placed = false
        for (i in 0 until src.length()) {
            val v = src.opt(i)
            if (v == carId) {
                if (shown && !placed) next.put(carId)
                placed = true
            } else {
                next.put(v)
            }
        }
        if (shown && !placed) next.put(carId)
        return next
    }

    /** Read-only: the loaded config is the shared cache. */
    private fun cachedSection(): JSONObject? = try {
        UnifiedConfigManager.loadConfig().optJSONObject(SECTION)
    } catch (e: Exception) {
        null
    }

    private fun nextVersion(store: Snapshot): Int =
        maxOf(store.version, cachedSection()?.optInt(KEY_VERSION, 0) ?: 0) + 1

    /** Writes [edit]'s keys and the bumped version against a fresh read of the section, under the config lock. */
    private fun publish(version: Int, failure: String = BUSY, edit: ((section: JSONObject, patch: JSONObject) -> Unit)? = null) {
        val ok = try {
            UnifiedConfigManager.runUnderConfigLock {
                val fresh = UnifiedConfigManager.forceReload().optJSONObject(SECTION)
                val section = JSONObject(fresh?.toString() ?: "{}")
                val patch = JSONObject()
                edit?.invoke(section, patch)
                patch.put(KEY_VERSION, maxOf(version, section.optInt(KEY_VERSION, 0) + 1))
                UnifiedConfigManager.updateSection(SECTION, patch)
            }
        } catch (e: Exception) {
            logger.warn("glassWidgets update failed: ${e.message}")
            false
        }
        if (!ok) throw Unavailable(failure)
    }

    /** Once per process: drops tmp files an interrupted write left behind. */
    private fun sweepOnce() {
        if (swept) return
        swept = true
        try {
            val f = file()
            val cutoff = System.currentTimeMillis() - STALE_TMP_MS
            f.parentFile?.listFiles { _, name -> name.startsWith(f.name + ".tmp.") }
                ?.forEach { if (it.lastModified() < cutoff) it.delete() }
        } catch (e: Exception) {
            logger.warn("tmp sweep failed: ${e.message}")
        }
    }

    private fun load(): Snapshot {
        sweepOnce()
        val f = file()
        if (!f.exists()) return Snapshot(0, ArrayList())
        val text = try {
            f.readText()
        } catch (e: Exception) {
            logger.warn("widget store unreadable: ${e.message}")
            return Snapshot(0, ArrayList(), unreadable = true)
        }
        val root = try {
            JSONObject(text)
        } catch (e: JSONException) {
            logger.warn("widget store is not JSON: ${e.message}")
            return Snapshot(0, ArrayList(), corrupt = true)
        }
        val arr = root.optJSONArray("widgets") ?: JSONArray()
        val entries = ArrayList<Entry>()
        for (i in 0 until arr.length()) {
            val e = entry(arr.optJSONObject(i)) ?: continue
            if (entries.none { it.id == e.id }) entries.add(e)
        }
        return Snapshot(root.optInt("version", 0), entries)
    }

    /** A change never starts from a file that couldn't be read: it would drop what's in it. */
    private fun loadForChange(): Snapshot = load().also {
        if (it.unreadable) throw Unavailable(Messages.get("errors.widgets_read_failed"))
    }

    private fun entry(o: JSONObject?): Entry? {
        if (o == null) return null
        val id = o.optString("id")
        val doc = o.optJSONObject("doc") ?: return null
        if (!isValidId(id)) return null
        val source = if (o.optString("source") == SOURCE_COMMUNITY) SOURCE_COMMUNITY else SOURCE_LOCAL
        val communityId = if (o.isNull("communityId")) null else o.optString("communityId").takeIf { it.isNotEmpty() }
        return Entry(id, doc, o.optLong("updatedMs", 0L), source, communityId)
    }

    /** tmp + fsync + rename: a reader sees the old file or the new one, never a torn write. */
    private fun write(store: Snapshot, version: Int, failure: String = Messages.get("errors.widgets_save_failed")) {
        val arr = JSONArray()
        for (e in store.entries) {
            arr.put(JSONObject().put("id", e.id).put("doc", e.doc).put("updatedMs", e.updatedMs)
                .put("source", e.source).put("communityId", e.communityId ?: JSONObject.NULL))
        }
        val text = JSONObject().put("version", version).put("widgets", arr).toString()
        val f = file()
        f.parentFile?.mkdirs()
        val tmp = File(f.parentFile, f.name + ".tmp." + android.os.Process.myPid())
        try {
            FileOutputStream(tmp, false).use { o ->
                o.write(text.toByteArray(Charsets.UTF_8))
                o.flush()
                o.fd.sync()
            }
            // the overlay runs as the app's uid and only reads it
            tmp.setReadable(true, false)
            if (store.corrupt) setAside(f)
            if (!tmp.renameTo(f)) throw IOException("rename failed")
        } catch (e: Exception) {
            logger.warn("widget store write failed: ${e.message}")
            throw Unavailable(failure)
        } finally {
            tmp.delete()
        }
    }

    /** Keeps a damaged store as `.bad.<ms>` (the newest [MAX_SET_ASIDE]) instead of overwriting it. */
    private fun setAside(f: File) {
        if (f.exists() && !f.renameTo(File("${f.path}.bad.${System.currentTimeMillis()}"))) {
            throw IOException("couldn't set the damaged store aside")
        }
        val kept = f.parentFile?.listFiles { _, name -> name.startsWith(f.name + ".bad.") } ?: return
        kept.sortedByDescending { it.name.substringAfterLast('.').toLongOrNull() ?: 0L }
            .drop(MAX_SET_ASIDE)
            .forEach { it.delete() }
    }
}
