package com.overdrive.app.ui.fragment.settings

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import com.overdrive.app.BuildConfig
import com.overdrive.app.R
import com.overdrive.app.ui.MainActivity
import com.overdrive.app.ui.about.VehicleVersionInfo
import com.overdrive.app.ui.about.VehicleVersionInfoProvider
import com.overdrive.app.updater.AppUpdater
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Settings → About pane.
 *
 * Renders identity (brand, version, build), MIT license + GitHub source
 * deep-links, and the "Check for updates" action. Version is pulled from
 * [AppUpdater.getDisplayVersion], which prefers the installed GitHub release
 * label (the world-readable version file written by every install, read
 * cross-UID), falling back to the BuildConfig identity
 * (UPDATE_CHANNEL + "-v" + VERSION_NAME, e.g. "alpha-v26.0") — the build's
 * true running identity, NOT a persisted file. So it's always correct for the
 * build actually installed, regardless of how it was installed (in-app update,
 * Android Studio, sideload, channel switch). This row answers "what am I
 * running?"; the "Check for updates" action answers "is something newer?".
 */
class SettingsAboutFragment : Fragment() {

    /** Off-thread loader for GitHub avatar fetches. Single thread is
     *  enough — at most ~10 contributors at a time, and a queue
     *  serializes their HTTPS calls without flooding the head unit. */
    private var avatarExecutor: ExecutorService? = null
    /** Kept separate so slow contributor-avatar downloads never delay local vehicle identity. */
    private var vehicleInfoExecutor: ExecutorService? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var vehicleVersionInfo: VehicleVersionInfo? = null
    private var vinRevealed = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_settings_about, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Show the INSTALLED version — i.e. the build actually running on this
        // device. We deliberately do NOT overwrite this with the latest version
        // published on GitHub: this row answers "what am I running?", and
        // showing the latest-available would make a user on an older build look
        // current and is simply mislabeled. "Is an update available?" is what
        // the Check for Updates action below answers.
        val versionView = view.findViewById<TextView>(R.id.tvAboutVersion)
        // BuildConfig identity only — no async overwrite. getDisplayVersion's
        // persisted GitHub label is written ONLY by the in-app updater's own
        // install path, so a sideloaded/ADB-installed build (every build
        // pushed tonight) left it stale: this row would show the correct
        // version for a moment, then flip to whatever was last installed
        // THROUGH the updater. Defeats the row's whole stated purpose above
        // ("what am I running?"). getInstalledVersion() is pure in-memory
        // BuildConfig, always correct for the binary actually executing.
        versionView.text = AppUpdater.getInstalledVersion()
        view.findViewById<TextView>(R.id.tvAboutBuild).text = BuildConfig.APPLICATION_ID
        setupVehicleInformation(view)

        setupChannelToggle(view)

        view.findViewById<View>(R.id.cardCheckUpdate).setOnClickListener {
            (activity as? MainActivity)?.invokeCheckForUpdates()
        }

        view.findViewById<View>(R.id.cardBackupExport).setOnClickListener {
            startBackupExport()
        }

        view.findViewById<View>(R.id.cardBackupImport).setOnClickListener {
            startBackupImport()
        }

        view.findViewById<View>(R.id.cardLicense).setOnClickListener {
            openExternal(getString(R.string.settings_about_license_url))
        }

        view.findViewById<View>(R.id.cardSource).setOnClickListener {
            openExternal(getString(R.string.settings_about_source_url))
        }

        // Tiered support actions — free → social → monetary.
        view.findViewById<View>(R.id.cardStar).setOnClickListener {
            openExternal(getString(R.string.settings_about_star_url))
        }

        view.findViewById<View>(R.id.cardShare).setOnClickListener {
            shareOverdrive()
        }

        view.findViewById<View>(R.id.cardSupport).setOnClickListener {
            openExternal(getString(R.string.settings_about_support_kofi_url))
        }

        populateThanks(view)
    }

    /**
     * Re-resolve the displayed version every time the pane is shown.
     *
     * The version row is painted once in onViewCreated and would otherwise
     * stay frozen at whatever VERSION_FILE held when the view was created.
     * VERSION_FILE (/data/local/tmp/overdrive_version) is advanced by ANY
     * install path — including a daemon/web/Telegram-triggered update that
     * lands while this fragment is on the back stack — so a stale snapshot
     * here disagrees with the live read the "Check for updates" toast does
     * (the "About shows v27.2 but check says v27.4" report). Re-reading on
     * resume keeps About in lock-step with the toast / status surfaces.
     *
     * Same off-thread + attach-guard discipline as the first paint:
     * getDisplayVersion does a /data/local/tmp file read, which must not run
     * on the looper.
     */
    override fun onResume() {
        super.onResume()
        val v = view ?: return
        val versionView = v.findViewById<TextView>(R.id.tvAboutVersion) ?: return
        (avatarExecutor ?: Executors.newSingleThreadExecutor().also { avatarExecutor = it })
            .execute {
                val resolved = AppUpdater.getDisplayVersion(requireContext().applicationContext)
                mainHandler.post { if (isAdded && view?.parent != null) versionView.text = resolved }
            }
        refreshVehicleInformation(v)
    }

    /**
     * Bind the VIN reveal action once. The identifier is never persisted in UI state: every new
     * view starts masked, including rotation/recreation, and teardown clears the fragment copy.
     */
    private fun setupVehicleInformation(root: View) {
        root.findViewById<View>(R.id.btnAboutVehicleVinVisibility).setOnClickListener {
            val info = vehicleVersionInfo ?: return@setOnClickListener
            if (info.vin == null) return@setOnClickListener
            vinRevealed = !vinRevealed
            bindVehicleInformation(root, info)
        }
    }

    /**
     * Resolve Android/BYD version properties and ask the daemon for its existing VIN snapshot.
     * Both operations run off the looper. The VIN request uses device-local loopback IPC, not an
     * HTTP endpoint, so the identifier is not added to the authenticated tunnel/web API surface.
     */
    private fun refreshVehicleInformation(root: View) {
        val executor = vehicleInfoExecutor
            ?: Executors.newSingleThreadExecutor().also { vehicleInfoExecutor = it }
        executor.execute {
            val response = com.overdrive.app.server.DaemonIpcClient.send(
                JSONObject().put("command", "GET_VEHICLE_IDENTITY"),
                2_500
            )
            val rawVin = response
                ?.takeIf { it.optBoolean("success", false) }
                ?.optString("vin", "")
                ?.takeIf { it.isNotBlank() }
            val info = VehicleVersionInfoProvider.read(rawVin)
            mainHandler.post {
                if (!isAdded || view !== root || root.parent == null) return@post
                vehicleVersionInfo = info
                vinRevealed = false
                bindVehicleInformation(root, info)
            }
        }
    }

    private fun bindVehicleInformation(root: View, info: VehicleVersionInfo) {
        val unavailable = getString(R.string.settings_about_vehicle_unavailable)
        fun setValue(id: Int, value: String?) {
            root.findViewById<TextView>(id).text = value ?: unavailable
        }

        setValue(R.id.tvAboutVehicleFirmware, info.firmware)
        setValue(R.id.tvAboutVehicleDsp, info.dsp)
        setValue(R.id.tvAboutVehicleMcu, info.mcu)
        setValue(R.id.tvAboutVehicleAndroid, info.android)
        setValue(R.id.tvAboutVehicleSecurityPatch, info.securityPatch)
        setValue(R.id.tvAboutVehicleHeadUnit, info.headUnit)

        val vinView = root.findViewById<TextView>(R.id.tvAboutVehicleVin)
        val reveal = root.findViewById<TextView>(R.id.btnAboutVehicleVinVisibility)
        val vin = info.vin
        if (vin == null) {
            vinView.text = unavailable
            vinView.contentDescription = unavailable
            reveal.visibility = View.GONE
            return
        }

        reveal.visibility = View.VISIBLE
        if (vinRevealed) {
            vinView.text = vin
            vinView.contentDescription = vin
            reveal.setText(R.string.settings_about_vehicle_vin_hide)
            reveal.contentDescription = getString(R.string.settings_about_vehicle_vin_hide_a11y)
        } else {
            vinView.text = VehicleVersionInfoProvider.maskVin(vin)
            vinView.contentDescription =
                getString(R.string.settings_about_vehicle_vin_hidden_a11y)
            reveal.setText(R.string.settings_about_vehicle_vin_show)
            reveal.contentDescription = getString(R.string.settings_about_vehicle_vin_show_a11y)
        }
    }

    /**
     * Wire the Alpha / Braveheart channel toggle.
     *
     * Channel state lives in UnifiedConfigManager's "updates" section (cross-
     * process — the daemon honors it too). Reads call forceReload first (the
     * web/daemon may have changed it) and run OFF the UI thread; writes go
     * through setUpdateChannel on [avatarExecutor] — updateSection is a full
     * JSON rewrite + potential IPC round-trip, which would ANR the looper.
     * The UI flips optimistically and reverts on a failed write.
     */
    private fun setupChannelToggle(view: View) {
        val group = view.findViewById<com.google.android.material.button.MaterialButtonToggleGroup>(
            R.id.toggleUpdateChannel)
        val desc = view.findViewById<TextView>(R.id.tvChannelDesc)
        val executor = avatarExecutor
            ?: Executors.newSingleThreadExecutor().also { avatarExecutor = it }

        // Guard so the programmatic check() during seeding doesn't fire the
        // listener and trigger a redundant (or racing) write.
        var suppressListener = true

        fun applyDesc(channel: String) {
            desc.setText(
                if (channel == AppUpdater.CHANNEL_BRAVEHEART)
                    R.string.settings_update_channel_braveheart_desc
                else R.string.settings_update_channel_alpha_desc
            )
        }

        // Seed from current config off-thread, then bind on the UI thread.
        executor.execute {
            com.overdrive.app.config.UnifiedConfigManager.forceReload()
            val current = com.overdrive.app.config.UnifiedConfigManager.getUpdateChannel()
            mainHandler.post {
                if (!isAdded || view.parent == null) return@post
                suppressListener = true
                group.check(
                    if (current == AppUpdater.CHANNEL_BRAVEHEART) R.id.btnChannelBraveheart
                    else R.id.btnChannelAlpha
                )
                applyDesc(current)
                suppressListener = false
            }
        }

        group.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked || suppressListener) return@addOnButtonCheckedListener
            val channel = if (checkedId == R.id.btnChannelBraveheart)
                AppUpdater.CHANNEL_BRAVEHEART else AppUpdater.CHANNEL_ALPHA
            applyDesc(channel)
            executor.execute {
                val ok = com.overdrive.app.config.UnifiedConfigManager.setUpdateChannel(channel)
                if (ok && channel == AppUpdater.CHANNEL_BRAVEHEART) {
                    // Braveheart = beta: warn about instability + how to report.
                    mainHandler.post {
                        if (!isAdded) return@post
                        Toast.makeText(requireContext(),
                            R.string.settings_update_channel_braveheart_toast, Toast.LENGTH_LONG).show()
                    }
                }
                if (!ok) {
                    mainHandler.post {
                        if (!isAdded) return@post
                        Toast.makeText(requireContext(),
                            R.string.settings_update_channel_save_failed, Toast.LENGTH_SHORT).show()
                        // Revert to the persisted value.
                        com.overdrive.app.config.UnifiedConfigManager.forceReload()
                        val reverted = com.overdrive.app.config.UnifiedConfigManager.getUpdateChannel()
                        suppressListener = true
                        group.check(
                            if (reverted == AppUpdater.CHANNEL_BRAVEHEART) R.id.btnChannelBraveheart
                            else R.id.btnChannelAlpha
                        )
                        applyDesc(reverted)
                        suppressListener = false
                    }
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        avatarExecutor?.shutdownNow()
        avatarExecutor = null
        vehicleInfoExecutor?.shutdownNow()
        vehicleInfoExecutor = null
        // Don't retain the export bundle (device-id + encrypted credentials) in
        // memory after teardown if the user navigated away before the SAF
        // save-location picker returned. appContext is just the app context
        // (not sensitive) but clear it too so a torn-down fragment holds nothing.
        pendingExportText = null
        appContext = null
        vehicleVersionInfo = null
        vinRevealed = false
    }

    // ==================== Backup & Restore ====================
    //
    // Same core as the web + Telegram surfaces: the daemon (UID 2000) owns the
    // EXPORT_CONFIG / REPLACE_CONFIG IPC commands and does the atomic write. The
    // app only moves bytes between the daemon and a user-picked file via SAF.
    // Liveness gate: if the daemon doesn't answer, we tell the user to start it
    // (the "camera daemon must be running" popup the design calls for).

    /** Cached bundle text awaiting a SAF create-document destination. */
    private var pendingExportText: String? = null

    /**
     * Application context captured for background backup I/O. Survives the
     * fragment teardown, so a SAF read/write or IPC call that finishes after
     * onDestroyView never touches a destroyed fragment via requireContext()
     * (the executor-shutdown race). Lazily initialised on first backup action.
     */
    private var appContext: android.content.Context? = null

    private fun ioContext(): android.content.Context? {
        if (appContext == null && isAdded) appContext = requireContext().applicationContext
        return appContext
    }

    /**
     * Submit backup I/O. Reuses avatarExecutor but tolerates the destroy race:
     * if the executor was shut down in onDestroyView between this call and the
     * submit, swallow the RejectedExecutionException (the work is moot — the
     * fragment is gone) instead of crashing.
     */
    private fun submitIo(task: Runnable) {
        val exec = avatarExecutor ?: Executors.newSingleThreadExecutor().also { avatarExecutor = it }
        try {
            exec.execute(task)
        } catch (e: java.util.concurrent.RejectedExecutionException) {
            // Fragment torn down mid-action; nothing to do.
        }
    }

    /** Tap → ask whether to include trip history, then build the bundle. */
    private fun startBackupExport() {
        if (!isAdded) return   // getString / dialog need an attached context
        com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.settings_backup_export_title)
            .setMessage(R.string.settings_backup_trips_prompt)
            .setNeutralButton(android.R.string.cancel, null)
            // "Settings only" is the safe default (left/negative); including
            // trip history (with its location data) is the explicit opt-in.
            .setNegativeButton(R.string.settings_backup_settings_only) { _, _ -> runBackupExport(false) }
            .setPositiveButton(R.string.settings_backup_with_trips) { _, _ -> runBackupExport(true) }
            .show()
    }

    /** Tap → restore. Use SAF open-document when a real picker exists; else
     *  show an in-app list of backup files found in the public Overdrive
     *  folders (the BYD unit's only "picker" is an image gallery). */
    private fun startBackupImport() {
        if (!isAdded) return
        if (safAvailable(Intent.ACTION_OPEN_DOCUMENT)) {
            try {
                val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "*/*"
                    putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/json", "text/plain", "*/*"))
                }
                startActivityForResult(intent, REQ_IMPORT_PICK)
                return
            } catch (e: Exception) {
                // Fall through to the in-app picker.
            }
        }
        showInAppBackupPicker()
    }

    /**
     * In-app file chooser used when no SAF picker is installed. Scans the
     * public Overdrive backups folder plus Download for *.json bundles and
     * lets the user pick one; reads it via direct file I/O. If nothing is
     * found we tell the user where to drop a file.
     */
    private fun showInAppBackupPicker() {
        if (!isAdded) return
        submitIo {
            val files = scanForBackupFiles()
            mainHandler.post {
                if (!isAdded) return@post
                if (files.isEmpty()) {
                    com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
                        .setTitle(R.string.settings_backup_import_title)
                        .setMessage(getString(R.string.settings_backup_no_files,
                            publicBackupDir().absolutePath))
                        .setPositiveButton(android.R.string.ok, null)
                        .show()
                    return@post
                }
                // Label each as "<filename>  ·  <date>" for disambiguation.
                val labels = files.map { f ->
                    f.name + "\n" + android.text.format.DateFormat.getDateFormat(requireContext())
                        .format(java.util.Date(f.lastModified())) + " " +
                        android.text.format.DateFormat.getTimeFormat(requireContext())
                            .format(java.util.Date(f.lastModified()))
                }.toTypedArray()
                com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
                    .setTitle(R.string.settings_backup_pick_file)
                    .setItems(labels) { _, which ->
                        readBackupFile(files[which])
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
        }
    }

    /**
     * Find candidate backup files: *.json (newest first) under the Overdrive
     * backups dir and the public Download dir. De-duplicated by absolute path.
     */
    private fun scanForBackupFiles(): List<File> {
        val found = LinkedHashMap<String, File>()
        val dirs = listOf(
            publicBackupDir(),
            File(android.os.Environment.getExternalStorageDirectory(), "Overdrive"),
            android.os.Environment.getExternalStoragePublicDirectory(
                android.os.Environment.DIRECTORY_DOWNLOADS)
        )
        for (dir in dirs) {
            val kids = try { dir.listFiles() } catch (e: Exception) { null } ?: continue
            for (f in kids) {
                if (f.isFile && f.name.endsWith(".json", ignoreCase = true) &&
                    f.length() in 1..MAX_IMPORT_CHARS.toLong()) {
                    found[f.absolutePath] = f
                }
            }
        }
        return found.values.sortedByDescending { it.lastModified() }
    }

    /** Read a picked backup file directly off disk, then validate + confirm. */
    private fun readBackupFile(file: File) {
        submitIo {
            var tooLarge = false
            val text = try {
                if (file.length() > MAX_IMPORT_CHARS.toLong()) { tooLarge = true; null }
                else file.readText(Charsets.UTF_8)
            } catch (e: Exception) { null }
            mainHandler.post {
                if (!isAdded) return@post
                if (tooLarge) { toast(getString(R.string.settings_backup_invalid_file)); return@post }
                if (text.isNullOrBlank()) { toast(getString(R.string.settings_backup_read_failed)); return@post }
                val parsed = try { JSONObject(text) } catch (e: Exception) { null }
                if (parsed == null || parsed.optJSONObject("manifest") == null) {
                    toast(getString(R.string.settings_backup_invalid_file)); return@post
                }
                confirmAndRestore(text)
            }
        }
    }

    /** Ask the daemon to build the bundle, then save it — via SAF when a real
     *  document picker exists, else by writing straight to the public Overdrive
     *  backups folder (the BYD head unit ships no DocumentsUI, so SAF
     *  CREATE_DOCUMENT resolves to nothing and would throw). */
    private fun runBackupExport(includeTrips: Boolean) {
        if (!isAdded) return
        toast(getString(R.string.settings_backup_exporting))
        submitIo {
            val req = JSONObject().put("command", "EXPORT_CONFIG").put("includeTrips", includeTrips)
            val resp = com.overdrive.app.server.DaemonIpcClient.send(req, 15_000)
            // The bundle build is the slow part; do the file write on this same
            // IO thread (no SAF round-trip in the direct path).
            val bundle = resp?.optJSONObject("bundle")
            val ok = resp != null && resp.optBoolean("success", false) && bundle != null
            if (ok && !safAvailable(Intent.ACTION_CREATE_DOCUMENT)) {
                // Direct path: write to /sdcard/Overdrive/backups/<name>.json.
                val name = suggestedBackupName(bundle)
                val savedPath = writeBundleToPublicDir(bundle!!.toString(2), name)
                mainHandler.post {
                    if (!isAdded) return@post
                    if (savedPath != null) {
                        toast(getString(R.string.settings_backup_saved_to, savedPath))
                    } else {
                        toast(getString(R.string.settings_backup_save_failed))
                    }
                }
                return@submitIo
            }
            mainHandler.post {
                if (!isAdded) return@post
                if (resp == null) {
                    toast(getString(R.string.settings_backup_daemon_down))
                    return@post
                }
                if (!ok) {
                    toast(getString(R.string.settings_backup_export_failed))
                    return@post
                }
                // SAF path (some hosts do have a document UI): pick a destination.
                pendingExportText = bundle!!.toString(2)
                try {
                    val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        type = "application/json"
                        putExtra(Intent.EXTRA_TITLE, suggestedBackupName(bundle))
                    }
                    startActivityForResult(intent, REQ_EXPORT_SAVE)
                } catch (e: Exception) {
                    // Last-ditch: SAF claimed to resolve but the launch threw —
                    // fall back to the public-dir write rather than just failing.
                    val text = pendingExportText
                    pendingExportText = null
                    submitIo {
                        val savedPath = if (text != null) writeBundleToPublicDir(text, suggestedBackupName(bundle)) else null
                        mainHandler.post {
                            if (!isAdded) return@post
                            if (savedPath != null) toast(getString(R.string.settings_backup_saved_to, savedPath))
                            else toast(getString(R.string.settings_backup_export_failed))
                        }
                    }
                }
            }
        }
    }

    /**
     * True when a real activity handles [action] for our JSON document MIME —
     * i.e. a usable Storage Access Framework picker is installed. On the BYD
     * head unit DocumentsUI is absent: CREATE_DOCUMENT resolves to nothing and
     * OPEN_DOCUMENT/GET_CONTENT resolve only to BYD's image-only picker
     * (com.byd.auto_photo), which can't pick or save a .json. In that case we
     * use direct file I/O instead. Resolution is cheap (PackageManager lookup),
     * so it's fine to call on the UI thread.
     */
    private fun safAvailable(action: String): Boolean {
        val ctx = context ?: appContext ?: return false
        return try {
            val intent = Intent(action).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "application/json"
            }
            val ri = ctx.packageManager.resolveActivity(intent, 0) ?: return false
            // A resolver that points at BYD's photo picker is NOT a usable
            // JSON document picker — treat it as "no SAF".
            val pkg = ri.activityInfo?.packageName ?: return false
            pkg != "com.byd.auto_photo"
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Public directory where backups are written when SAF is unavailable:
     * {@code /sdcard/Overdrive/backups}. Same external root the app already
     * uses for recordings/trips, so it's user-reachable from the BYD file
     * manager. Created on demand.
     */
    private fun publicBackupDir(): File {
        val base = File(android.os.Environment.getExternalStorageDirectory(), "Overdrive")
        val dir = File(base, "backups")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    /**
     * Write [text] to [name] under {@link #publicBackupDir}, returning the
     * absolute path on success or null on failure. Runs on an IO thread.
     */
    private fun writeBundleToPublicDir(text: String, name: String): String? {
        return try {
            val dir = publicBackupDir()
            if (!dir.exists()) return null
            val safeName = name.replace(Regex("[^A-Za-z0-9._-]"), "_")
                .ifEmpty { "overdrive-backup.json" }
            val out = File(dir, safeName)
            FileOutputStream(out).use { it.write(text.toByteArray(Charsets.UTF_8)) }
            out.absolutePath
        } catch (e: Exception) {
            null
        }
    }

    private fun suggestedBackupName(bundle: JSONObject?): String {
        return try {
            val m = bundle?.optJSONObject("manifest")
            val ver = (m?.optString("appVersion") ?: "").replace(Regex("[^A-Za-z0-9._-]"), "")
            val model = (m?.optString("deviceModel") ?: "").replace(Regex("[^A-Za-z0-9._-]"), "")
            val parts = listOfNotNull("overdrive-backup",
                model.ifEmpty { null }, ver.ifEmpty { null })
            parts.joinToString("-") + ".json"
        } catch (e: Exception) {
            getString(R.string.settings_backup_default_filename)
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != android.app.Activity.RESULT_OK) {
            if (requestCode == REQ_EXPORT_SAVE) pendingExportText = null
            return
        }
        val uri: Uri = data?.data ?: run {
            if (requestCode == REQ_EXPORT_SAVE) pendingExportText = null
            return
        }
        when (requestCode) {
            REQ_EXPORT_SAVE -> writeExportToUri(uri)
            REQ_IMPORT_PICK -> readAndRestoreFromUri(uri)
        }
    }

    private fun writeExportToUri(uri: Uri) {
        val text = pendingExportText
        pendingExportText = null
        if (text == null) { toast(getString(R.string.settings_backup_export_failed)); return }
        val ctx = ioContext()
        submitIo {
            val ok = try {
                ctx?.contentResolver?.openOutputStream(uri)?.use { os ->
                    os.write(text.toByteArray(Charsets.UTF_8)); true
                } ?: false
            } catch (e: Exception) { false }
            mainHandler.post {
                if (!isAdded) return@post
                toast(getString(if (ok) R.string.settings_backup_saved
                                else R.string.settings_backup_save_failed))
            }
        }
    }

    private fun readAndRestoreFromUri(uri: Uri) {
        val ctx = ioContext()
        submitIo {
            // Cap the read so a huge/garbage file can't OOM the head unit. A real
            // bundle is a few KB; 16 MB matches the web import body cap. Read
            // bounded and treat overflow as an invalid file. tooLarge is tracked
            // separately so the user gets a precise message.
            var tooLarge = false
            val text = try {
                ctx?.contentResolver?.openInputStream(uri)?.use { ins ->
                    val reader = BufferedReader(InputStreamReader(ins))
                    val sb = StringBuilder()
                    val buf = CharArray(8192)
                    var total = 0
                    while (true) {
                        val n = reader.read(buf)
                        if (n < 0) break
                        total += n
                        if (total > MAX_IMPORT_CHARS) { tooLarge = true; break }
                        sb.append(buf, 0, n)
                    }
                    sb.toString()
                }
            } catch (e: Exception) { null }
            mainHandler.post {
                if (!isAdded) return@post
                if (tooLarge) { toast(getString(R.string.settings_backup_invalid_file)); return@post }
                if (text.isNullOrBlank()) { toast(getString(R.string.settings_backup_read_failed)); return@post }
                // Validate it parses + looks like our bundle before prompting.
                val parsed = try { JSONObject(text) } catch (e: Exception) { null }
                if (parsed == null || parsed.optJSONObject("manifest") == null) {
                    toast(getString(R.string.settings_backup_invalid_file)); return@post
                }
                confirmAndRestore(text)
            }
        }
    }

    private fun confirmAndRestore(bundleText: String) {
        if (!isAdded) return   // requireContext()/getString need an attached context
        com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.settings_backup_restore_confirm_title)
            .setMessage(R.string.settings_backup_restore_confirm_msg)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.settings_backup_restore_confirm_btn) { _, _ ->
                doRestore(bundleText)
            }
            .show()
    }

    private fun doRestore(bundleText: String) {
        // Runs synchronously from the confirm-dialog callback. getString() needs
        // an attached context, so bail if the fragment detached between showing
        // the dialog and the user confirming (back button / backgrounded).
        if (!isAdded) return
        toast(getString(R.string.settings_backup_restoring))
        submitIo {
            val bundle = try { JSONObject(bundleText) } catch (e: Exception) { null }
            val resp = if (bundle == null) null else {
                val req = JSONObject().put("command", "REPLACE_CONFIG").put("bundle", bundle)
                com.overdrive.app.server.DaemonIpcClient.send(req, 20_000)
            }
            mainHandler.post {
                if (!isAdded) return@post
                when {
                    resp == null -> toast(getString(R.string.settings_backup_daemon_down))
                    resp.optBoolean("success", false) -> {
                        val msg = resp.optString("message", getString(R.string.settings_backup_restored))
                        toast(msg.ifEmpty { getString(R.string.settings_backup_restored) })
                        // Restored settings are on disk, but already-running
                        // pipelines (camera/surveillance/recording) read most of
                        // their config at start — a restart guarantees every
                        // restored value is actually applied. Surface this as a
                        // dialog so the user sees it (toasts can stack/expire).
                        showRestartPrompt(resp.optJSONArray("warnings"))
                    }
                    else -> {
                        val msg = resp.optString("message", getString(R.string.settings_backup_restore_failed))
                        toast(msg.ifEmpty { getString(R.string.settings_backup_restore_failed) })
                    }
                }
            }
        }
    }

    /**
     * Post-restore advisory: tell the user to restart the camera daemon so the
     * restored settings take full effect, and append any warnings the daemon
     * returned (e.g. firmware-change credential loss). Shown as a dialog so it
     * isn't missed.
     */
    private fun showRestartPrompt(warnings: org.json.JSONArray?) {
        if (!isAdded) return
        val sb = StringBuilder(getString(R.string.settings_backup_restart_hint))
        if (warnings != null && warnings.length() > 0) {
            sb.append("\n")
            for (i in 0 until warnings.length()) {
                val w = warnings.optString(i, "")
                if (w.isNotEmpty()) sb.append("\n• ").append(w)
            }
        }
        com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.settings_backup_restored)
            .setMessage(sb.toString())
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun toast(msg: String) {
        if (isAdded) Toast.makeText(requireContext(), msg, Toast.LENGTH_LONG).show()
    }

    companion object {
        private const val REQ_EXPORT_SAVE = 4801
        private const val REQ_IMPORT_PICK = 4802
        // Bound the SAF import read so a malformed/huge file can't OOM the head
        // unit. 16 MB worth of chars; a real bundle is a few KB.
        private const val MAX_IMPORT_CHARS = 16 * 1024 * 1024
    }

    private fun populateThanks(root: View) {
        val contribContainer = root.findViewById<LinearLayout>(R.id.containerContributors)
        val supportContainer = root.findViewById<LinearLayout>(R.id.containerSupporters)

        val json = try {
            val raw = requireContext().assets.open("web/local/credits.json").use { input ->
                BufferedReader(InputStreamReader(input)).readText()
            }
            JSONObject(raw)
        } catch (e: Exception) {
            // Render empty-state on both lists so the user sees the localized
            // "List populates as people pitch in." copy instead of a blank card.
            renderRows(contribContainer, null, withGithub = true)
            renderRows(supportContainer, null, withGithub = false)
            return
        }

        renderRows(contribContainer, json.optJSONArray("contributors"), withGithub = true)
        val supporters = json.optJSONArray("supporters")
        renderRows(supportContainer, supporters, withGithub = false)

        // Only surface the tier/×N explainer when the data actually uses them.
        val hasTiers = (0 until (supporters?.length() ?: 0)).any { i ->
            val o = supporters?.optJSONObject(i) ?: return@any false
            tierLabel(o.optString("tier").trim().lowercase()) != null || o.optInt("count", 0) > 1
        }
        root.findViewById<TextView>(R.id.textSupportersLegend)?.visibility =
            if (hasTiers) View.VISIBLE else View.GONE
    }

    private fun renderRows(container: LinearLayout, arr: org.json.JSONArray?, withGithub: Boolean) {
        container.removeAllViews()
        if (arr == null || arr.length() == 0) {
            val empty = TextView(container.context).apply {
                text = getString(R.string.settings_about_thanks_empty)
                setTextColor(resolveAttr(android.R.attr.textColorSecondary))
                textSize = 13f
            }
            container.addView(empty)
            return
        }

        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val name = obj.optString("name").trim()
            if (name.isEmpty()) continue
            val github = if (withGithub) obj.optString("github").trim().ifEmpty { null } else null
            val tier = obj.optString("tier").trim().lowercase()
            // Only a count above 1 earns a badge; "x1" reads as "only once".
            val count = obj.optInt("count", 0).let { if (it > 1) it else 0 }
            container.addView(buildRow(container, name, github, tier, count))
        }
    }

    private fun buildRow(
        parent: LinearLayout,
        name: String,
        github: String?,
        tier: String = "",
        count: Int = 0
    ): View {
        val ctx = parent.context
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val padV = dp(10)
            setPadding(0, padV, 0, padV)
            isClickable = github != null
            isFocusable = github != null
            if (github != null) {
                val ta = ctx.obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackground))
                background = ta.getDrawable(0)
                ta.recycle()
                setOnClickListener { openExternal("https://github.com/$github") }
            }
        }

        // Avatar: start with the initial-circle fallback, then async-load
        // the GitHub profile picture (matches about.html behavior). The
        // CDN URL `https://github.com/<user>.png?size=72` 302-redirects to
        // the user's avatar; if it fails we keep the initial circle.
        // Every avatar stays 36dp; the medal is a ring plus a labelled pip.
        val avatarDp = 36
        val ringColor = medalColor(tier)
        val avatarSize = dp(avatarDp)
        val avatar = ImageView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(avatarSize, avatarSize)
            setImageDrawable(initialCircle(name, avatarDp, ringColor))
        }
        row.addView(avatar)
        if (github != null) {
            loadGithubAvatar(github, avatar)
        }

        row.addView(TextView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(14)
            }
            this.text = name
            setTextColor(resolveAttr(android.R.attr.textColorPrimary))
            textSize = 15f
        })

        // Medal name — the text is what makes the colour legible to everyone,
        // so the ring and the label always ship together.
        val tierName = tierLabel(tier)
        if (tierName != null && ringColor != null) {
            row.addView(TextView(ctx).apply {
                this.text = tierName.uppercase()
                setTextColor(resolveAttr(android.R.attr.textColorSecondary))
                textSize = 11f
                letterSpacing = 0.04f
                compoundDrawablePadding = dp(5)
                setCompoundDrawablesRelative(
                    medalPip(tier, ringColor, dp(11)), null, null, null
                )
            })
        }

        if (count > 0) {
            // Neutral chip — the medal owns the colour on this row, so the
            // count stays ink-on-surface rather than competing with it.
            val chipInk = resolveAttr(android.R.attr.textColorSecondary)
            row.addView(TextView(ctx).apply {
                this.text = "×$count"
                setTextColor(chipInk)
                textSize = 11f
                typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
                val padH = dp(7); val padV = dp(3)
                setPadding(padH, padV, padH, padV)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginStart = dp(10) }
                contentDescription = getString(R.string.settings_about_support_count, count)
                background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                    cornerRadius = dp(999).toFloat()
                    setColor(Color.argb(30, Color.red(chipInk), Color.green(chipInk), Color.blue(chipInk)))
                }
            })
        }

        if (github != null) {
            val chev = ImageView(ctx).apply {
                val sz = dp(18)
                layoutParams = LinearLayout.LayoutParams(sz, sz)
                setImageResource(R.drawable.ic_chevron_right)
                imageTintList = android.content.res.ColorStateList.valueOf(resolveAttr(android.R.attr.textColorSecondary))
            }
            row.addView(chev)
        }
        return row
    }

    /**
     * Asynchronously load a GitHub avatar into [target]. Uses a tiny
     * disk cache under the app's cacheDir keyed by username so repeated
     * About-page visits don't re-fetch.
     *
     * On failure (no network, 404, parse error, fragment torn down) we
     * leave the initial-circle in place — same graceful behavior as
     * about.html's `error` handler.
     */
    private fun loadGithubAvatar(github: String, target: ImageView) {
        val ctx = context ?: return
        val safeName = github.replace(Regex("[^A-Za-z0-9_.-]"), "_")
        val cacheFile = File(ctx.cacheDir, "gh_avatar_$safeName.png")

        // Cached hit — decode synchronously off the main thread (cheap)
        // but still post the bitmap binding to the UI thread.
        val executor = avatarExecutor
            ?: Executors.newSingleThreadExecutor().also { avatarExecutor = it }

        executor.execute {
            try {
                val bmp = if (cacheFile.exists() && cacheFile.length() > 0L) {
                    BitmapFactory.decodeFile(cacheFile.absolutePath)
                } else {
                    fetchAvatarBitmap(github, cacheFile)
                } ?: return@execute

                mainHandler.post {
                    if (!isAdded || view == null) return@post
                    target.setImageDrawable(circularBitmap(bmp))
                }
            } catch (_: Throwable) {
                // Stay with initial circle.
            }
        }
    }

    /**
     * Fetch the avatar bitmap via HTTPS, follow the GitHub redirect,
     * persist to [cacheFile], and return the decoded bitmap. Any
     * exception means "no avatar available" and the caller falls
     * through to the initial circle.
     */
    private fun fetchAvatarBitmap(github: String, cacheFile: File): Bitmap? {
        val urlStr = "https://github.com/" +
            java.net.URLEncoder.encode(github, "UTF-8") +
            ".png?size=72"
        val conn = URL(urlStr).openConnection() as HttpURLConnection
        conn.connectTimeout = 5_000
        conn.readTimeout = 5_000
        conn.instanceFollowRedirects = true
        conn.requestMethod = "GET"
        try {
            if (conn.responseCode !in 200..299) return null
            conn.inputStream.use { input ->
                FileOutputStream(cacheFile).use { output ->
                    val buf = ByteArray(8192)
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        output.write(buf, 0, n)
                    }
                }
            }
            return BitmapFactory.decodeFile(cacheFile.absolutePath)
        } finally {
            conn.disconnect()
        }
    }

    /**
     * Wrap a square bitmap in a circle-shaped drawable so the avatar
     * matches the initial-circle visual (no square photos in a list of
     * round chips).
     */
    private fun circularBitmap(src: Bitmap): Drawable {
        val sz = dp(36)
        return object : Drawable() {
            private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = BitmapShader(src, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
                isFilterBitmap = true
            }
            private val matrix = android.graphics.Matrix()

            override fun onBoundsChange(bounds: android.graphics.Rect) {
                val scale = maxOf(
                    bounds.width().toFloat() / src.width,
                    bounds.height().toFloat() / src.height
                )
                matrix.reset()
                matrix.setScale(scale, scale)
                matrix.postTranslate(
                    bounds.left + (bounds.width() - src.width * scale) / 2f,
                    bounds.top + (bounds.height() - src.height * scale) / 2f
                )
                paint.shader.setLocalMatrix(matrix)
            }

            override fun draw(canvas: Canvas) {
                val cx = bounds.exactCenterX()
                val cy = bounds.exactCenterY()
                val r = minOf(bounds.width(), bounds.height()) / 2f
                canvas.drawCircle(cx, cy, r, paint)
            }
            override fun setAlpha(alpha: Int) { paint.alpha = alpha }
            override fun setColorFilter(cf: android.graphics.ColorFilter?) {
                paint.colorFilter = cf
            }
            @Deprecated("Deprecated in API 29")
            override fun getOpacity(): Int = android.graphics.PixelFormat.TRANSLUCENT
            override fun getIntrinsicWidth(): Int = sz
            override fun getIntrinsicHeight(): Int = sz
        }
    }

    /**
     * Monogram avatar. [sizeDp] must match the ImageView's layout size —
     * the glyph is scaled off it, so a hardcoded size would leave a large
     * tier avatar with a small letter. [ringColor] draws the tier ring.
     */
    private fun initialCircle(name: String, sizeDp: Int = 36, ringColor: Int? = null): Drawable {
        val initial = name.firstOrNull { it.isLetterOrDigit() }?.uppercaseChar() ?: '?'
        val palette = intArrayOf(
            0xFF6366F1.toInt(), 0xFFEC4899.toInt(), 0xFF14B8A6.toInt(),
            0xFFF59E0B.toInt(), 0xFF8B5CF6.toInt(), 0xFF06B6D4.toInt(),
            0xFFEF4444.toInt(), 0xFF22C55E.toInt()
        )
        val color = palette[Math.floorMod(name.hashCode(), palette.size)]
        val sz = dp(sizeDp)
        val ringPx = if (ringColor == null) 0f else if (sizeDp >= 56) dp(2).toFloat() else dp(2).toFloat() * 0.75f

        return object : Drawable() {
            private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                this.color = color
                style = Paint.Style.FILL
            }
            private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                this.color = ringColor ?: Color.TRANSPARENT
                style = Paint.Style.STROKE
                strokeWidth = ringPx
            }
            private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                this.color = Color.WHITE
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                textAlign = Paint.Align.CENTER
                textSize = sz * 0.45f
            }

            override fun draw(canvas: Canvas) {
                val cx = bounds.exactCenterX()
                val cy = bounds.exactCenterY()
                // Inset by the ring so the stroke sits inside the bounds
                // instead of being clipped at the edge.
                val r = minOf(bounds.width(), bounds.height()) / 2f - ringPx / 2f
                canvas.drawCircle(cx, cy, r, bgPaint)
                if (ringColor != null) canvas.drawCircle(cx, cy, r, ringPaint)
                val baseline = cy - (textPaint.descent() + textPaint.ascent()) / 2f
                canvas.drawText(initial.toString(), cx, baseline, textPaint)
            }
            override fun setAlpha(alpha: Int) { bgPaint.alpha = alpha; textPaint.alpha = alpha }
            override fun setColorFilter(cf: android.graphics.ColorFilter?) {
                bgPaint.colorFilter = cf; textPaint.colorFilter = cf
            }
            @Deprecated("Deprecated in API 29")
            override fun getOpacity(): Int = android.graphics.PixelFormat.TRANSLUCENT
            override fun getIntrinsicWidth(): Int = sz
            override fun getIntrinsicHeight(): Int = sz
        }
    }

    /**
     * Medal colours, mirroring about.html. NOT the classic metal hexes —
     * #D4AF37/#C0C0C0/#CD7F32 put gold and bronze below the normal-vision
     * separation floor and collapse under deuteranopia. These are
     * lightness-separated and have their own darker light-mode steps, because
     * the dark-mode gold and silver only reach ~2-3:1 on a light surface.
     */
    private fun isNightMode(): Boolean =
        (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES

    private fun medalColor(tier: String): Int? {
        val night = isNightMode()
        return when (tier) {
            "diamond" -> if (night) 0xFF7FE0F5.toInt() else 0xFF0E8FB0.toInt()
            "platinum" -> if (night) 0xFFD8E2EA.toInt() else 0xFF5C6A78.toInt()
            "gold" -> if (night) 0xFFF0C043.toInt() else 0xFFE0A81F.toInt()
            "silver" -> if (night) 0xFFAFBECC.toInt() else 0xFF8494A3.toInt()
            "bronze" -> if (night) 0xFFB4602C.toInt() else 0xFF9A4A22.toInt()
            else -> null
        }
    }

    /**
     * Pip for [tier]. Diamond is a rotated square and platinum a hollow ring —
     * no hue pair separates two pale cool tiers under deuteranopia (cyan↔violet
     * measures ΔE 1.0), so the top two carry shape as the primary channel.
     */
    private fun medalPip(tier: String, color: Int, size: Int): Drawable = when (tier) {
        "diamond" -> android.graphics.drawable.RotateDrawable().apply {
            drawable = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                cornerRadius = size * 0.18f
                setColor(color)
                setSize(size, size)
            }
            fromDegrees = 45f
            toDegrees = 45f
            setBounds(0, 0, size, size)
        }
        "platinum" -> android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.OVAL
            setColor(Color.TRANSPARENT)
            setStroke((size * 0.28f).toInt(), color)
            setSize(size, size)
            setBounds(0, 0, size, size)
        }
        else -> android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.OVAL
            setColor(color)
            setSize(size, size)
            setBounds(0, 0, size, size)
        }
    }

    private fun tierLabel(tier: String): String? = when (tier) {
        "diamond" -> getString(R.string.settings_about_tier_diamond)
        "platinum" -> getString(R.string.settings_about_tier_platinum)
        "gold" -> getString(R.string.settings_about_tier_gold)
        "silver" -> getString(R.string.settings_about_tier_silver)
        "bronze" -> getString(R.string.settings_about_tier_bronze)
        else -> null
    }

    private fun resolveAttr(attr: Int): Int {
        val tv = TypedValue()
        requireContext().theme.resolveAttribute(attr, tv, true)
        return tv.data
    }

    private fun dp(v: Int): Int =
        (v * resources.displayMetrics.density).toInt()

    /** Fire an Android share-chooser with a prefilled message + repo link. */
    private fun shareOverdrive() {
        val ctx = context ?: return
        try {
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, getString(R.string.settings_about_support_share_message))
            }
            val chooser = Intent.createChooser(
                send,
                getString(R.string.settings_about_support_share_chooser)
            ).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
            ctx.startActivity(chooser)
        } catch (e: Exception) {
            Toast.makeText(ctx, getString(R.string.settings_about_support_share_message), Toast.LENGTH_LONG).show()
        }
    }

    /**
     * Open a URL in an external browser.
     *
     * The BYD head unit's launcher doesn't always advertise a default
     * browser. We try one direct handler resolution; if no app handles
     * https:// we skip straight to copying the URL to the clipboard +
     * showing a Toast.
     *
     * We deliberately do NOT use `Intent.createChooser` as a middle
     * step — on hosts with no browser installed Android still pops a
     * "No apps can perform this action" bottom sheet instead of falling
     * through to our handler, which is worse UX than a clean Toast.
     */
    private fun openExternal(url: String) {
        val ctx = context ?: return
        if (url.isBlank()) {
            Toast.makeText(ctx, R.string.settings_about_open_link_failed, Toast.LENGTH_SHORT).show()
            return
        }
        val uri = Uri.parse(url)

        val direct = Intent(Intent.ACTION_VIEW, uri).apply {
            addCategory(Intent.CATEGORY_BROWSABLE)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (direct.resolveActivity(ctx.packageManager) != null) {
            try {
                ctx.startActivity(direct)
                return
            } catch (_: Exception) { /* fall through to clipboard */ }
        }

        // No browser available — copy + toast so the URL is at least
        // recoverable.
        try {
            val clip = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                as? android.content.ClipboardManager
            clip?.setPrimaryClip(android.content.ClipData.newPlainText("url", url))
            Toast.makeText(
                ctx,
                getString(R.string.settings_about_open_link_copied, url),
                Toast.LENGTH_LONG
            ).show()
        } catch (_: Exception) {
            Toast.makeText(ctx, url, Toast.LENGTH_LONG).show()
        }
    }
}
