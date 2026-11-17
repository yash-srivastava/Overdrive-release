package com.overdrive.app.ui.fragment

import android.content.ActivityNotFoundException
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.text.format.DateUtils
import android.text.format.Formatter
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.overdrive.app.R
import com.overdrive.app.auth.PinSession
import com.overdrive.app.storage.ExternalStorageCleaner
import com.overdrive.app.storage.ExternalStorageCleaner.FactoryVideo
import com.overdrive.app.storage.ExternalStorageCleaner.FactoryVideoSnapshot
import com.overdrive.app.ui.RemoteMainActivity
import com.overdrive.app.ui.util.navigateDrillDown
import com.overdrive.app.ui.widget.AppToast
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** BYD's own dashcam clips on the microSD card. Read-only: lists, plays and copies, never modifies. */
class FactoryDvrFragment : Fragment(R.layout.fragment_factory_dvr) {

    private val scanIo: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "FactoryDvrScan").apply { isDaemon = true }
    }
    private val main = Handler(Looper.getMainLooper())

    private var snapshot: FactoryVideoSnapshot? = null
    private var scanning = false
    private var scanGeneration = 0
    private var pendingSave: String? = null

    private var summary: TextView? = null
    private var note: TextView? = null
    private var list: RecyclerView? = null
    private var stateBox: View? = null
    private var stateProgress: View? = null
    private var stateText: TextView? = null
    private var refresh: MaterialButton? = null
    private var appToast: AppToast? = null
    private val adapter = ClipAdapter()
    private val copyListener: (String, Boolean) -> Unit = { name, ok ->
        appToast?.show(
            getString(if (ok) R.string.factory_dvr_saved else R.string.factory_dvr_save_failed, name),
            if (ok) AppToast.Kind.SUCCESS else AppToast.Kind.ERROR
        )
    }

    private val saveCopy = registerForActivityResult(
        ActivityResultContracts.CreateDocument("video/*")
    ) { destination -> onSaveDestination(destination) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pendingSave = savedInstanceState?.getString(STATE_PENDING_SAVE)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        appToast = AppToast(view)
        summary = view.findViewById(R.id.tvFactoryDvrSummary)
        note = view.findViewById(R.id.tvFactoryDvrNote)
        stateBox = view.findViewById(R.id.factoryDvrState)
        stateProgress = view.findViewById(R.id.factoryDvrProgress)
        stateText = view.findViewById(R.id.tvFactoryDvrState)
        list = view.findViewById<RecyclerView>(R.id.rvFactoryDvr).also {
            it.layoutManager = LinearLayoutManager(view.context)
            it.adapter = adapter
        }
        refresh = view.findViewById<MaterialButton>(R.id.btnFactoryDvrRefresh).also {
            it.setOnClickListener { scan() }
        }
        copyResultListener = copyListener
        // Returning from the player keeps the list; only a new page instance scans.
        if (snapshot == null && !scanning) scan() else render()
    }

    override fun onDestroyView() {
        if (copyResultListener === copyListener) copyResultListener = null
        appToast?.cancel()
        appToast = null
        list?.adapter = null
        list = null
        summary = null
        note = null
        stateBox = null
        stateProgress = null
        stateText = null
        refresh = null
        super.onDestroyView()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_PENDING_SAVE, pendingSave)
    }

    override fun onDestroy() {
        scanGeneration++
        scanIo.shutdown()
        main.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun scan() {
        if (scanning) return
        val generation = ++scanGeneration
        scanning = true
        render()
        scanIo.execute {
            val result = runCatching { ExternalStorageCleaner.scanFactoryVideosReadOnly() }.getOrNull()
            main.post {
                if (generation != scanGeneration) return@post
                scanning = false
                snapshot = result
                render()
            }
        }
    }

    private fun render() {
        val ctx = context ?: return
        val summaryView = summary ?: return
        val noteView = note ?: return
        val listView = list ?: return
        val box = stateBox ?: return
        val stateView = stateText ?: return
        refresh?.isEnabled = !scanning

        val snap = snapshot
        val clips = if (scanning || snap == null) emptyList() else snap.videos
        adapter.submit(clips)
        listView.visibility = if (clips.isEmpty()) View.GONE else View.VISIBLE

        val folder = snap?.directory
        summaryView.text = when {
            scanning || snap == null || folder == null || clips.isEmpty() -> ""
            snap.totalFound > clips.size -> getString(
                R.string.factory_dvr_summary_newest, clips.size, snap.totalFound, folder)
            else -> ctx.resources.getQuantityString(
                R.plurals.factory_dvr_summary, clips.size, clips.size, folder)
        }
        summaryView.visibility = if (summaryView.text.isNullOrEmpty()) View.GONE else View.VISIBLE
        noteView.text = getString(R.string.factory_dvr_partial)
        noteView.visibility = if (!scanning && snap?.partial == true) View.VISIBLE else View.GONE

        val stateMessage = when {
            scanning -> getString(R.string.factory_dvr_scanning)
            snap == null -> getString(R.string.factory_dvr_not_found)
            snap.status == FactoryVideoSnapshot.Status.NO_CARD -> getString(R.string.factory_dvr_no_card)
            snap.status == FactoryVideoSnapshot.Status.NOT_FOUND -> getString(R.string.factory_dvr_not_found)
            snap.status == FactoryVideoSnapshot.Status.UNREADABLE ->
                getString(R.string.factory_dvr_unreadable, folder ?: "")
            clips.isEmpty() -> getString(R.string.factory_dvr_empty, folder ?: "")
            else -> null
        }
        stateView.text = stateMessage ?: ""
        stateProgress?.visibility = if (scanning) View.VISIBLE else View.GONE
        box.visibility = if (stateMessage != null) View.VISIBLE else View.GONE
    }

    private fun play(clip: FactoryVideo) {
        val clips = adapter.items
        val index = clips.indexOf(clip)
        if (index < 0) return
        // The player treats index+1 as "next", so hand it a window in oldest→newest order.
        val chronological = clips.asReversed()
        val at = chronological.size - 1 - index
        val from = (at - PLAYLIST_SPAN).coerceAtLeast(0)
        val to = (at + PLAYLIST_SPAN + 1).coerceAtMost(chronological.size)
        val window = chronological.subList(from, to)
        val ctx = requireContext()
        val args = Bundle().apply {
            putString(VideoPlayerFragment.ARG_VIDEO_PATH, clip.file.absolutePath)
            putString(VideoPlayerFragment.ARG_VIDEO_TITLE, title(ctx, clip))
            putBoolean(VideoPlayerFragment.ARG_EXTERNAL_SOURCE, true)
            putStringArray(VideoPlayerFragment.ARG_PLAYLIST_PATHS,
                window.map { it.file.absolutePath }.toTypedArray())
            putStringArray(VideoPlayerFragment.ARG_PLAYLIST_TITLES,
                window.map { title(ctx, it) }.toTypedArray())
            putInt(VideoPlayerFragment.ARG_PLAYLIST_INDEX, at - from)
        }
        findNavController().navigateDrillDown(R.id.videoPlayerFragment, args)
    }

    private fun requestSave(clip: FactoryVideo) {
        if (copyInFlight != null) {
            appToast?.show(getString(R.string.factory_dvr_save_busy), AppToast.Kind.WARNING)
            return
        }
        pendingSave = clip.file.absolutePath
        try {
            saveCopy.launch(clip.file.name)
        } catch (e: ActivityNotFoundException) {
            pendingSave = null
            appToast?.show(getString(R.string.factory_dvr_no_picker), AppToast.Kind.WARNING)
            return
        }
        // The picker is OverDrive's own detour, not the user leaving: keep the PIN session.
        if (activity !is RemoteMainActivity) PinSession.expectReturn(SAVE_RETURN_GRACE_MS)
    }

    private fun onSaveDestination(destination: Uri?) {
        val sourcePath = pendingSave
        pendingSave = null
        if (destination == null || sourcePath == null) return
        if (copyInFlight != null) {
            appToast?.show(getString(R.string.factory_dvr_save_busy), AppToast.Kind.WARNING)
            return
        }
        val source = File(sourcePath)
        val appContext = requireContext().applicationContext
        copyInFlight = source.name
        appToast?.show(getString(R.string.factory_dvr_saving, source.name))
        copyIo.execute {
            val ok = runCatching { copyOriginal(appContext, source, destination) }.isSuccess
            // Only the document the picker just created is removed; the source is never touched.
            if (!ok) runCatching {
                DocumentsContract.deleteDocument(appContext.contentResolver, destination)
            }
            Handler(Looper.getMainLooper()).post {
                copyInFlight = null
                copyResultListener?.invoke(source.name, ok)
            }
        }
    }

    private inner class ClipAdapter : RecyclerView.Adapter<ClipHolder>() {
        var items: List<FactoryVideo> = emptyList()
            private set

        fun submit(clips: List<FactoryVideo>) {
            if (clips === items) return
            items = clips
            @Suppress("NotifyDataSetChanged")
            notifyDataSetChanged()
        }

        override fun getItemCount(): Int = items.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ClipHolder =
            ClipHolder(LayoutInflater.from(parent.context)
                .inflate(R.layout.item_factory_dvr_clip, parent, false))

        override fun onBindViewHolder(holder: ClipHolder, position: Int) {
            val clip = items[position]
            val ctx = holder.itemView.context
            holder.time.text = formatTime(ctx, clip.modifiedMs)
            holder.meta.text =
                "${clip.relativePath} · ${Formatter.formatShortFileSize(ctx, clip.sizeBytes)}"
            holder.itemView.setOnClickListener { play(clip) }
            holder.save.setOnClickListener { requestSave(clip) }
        }
    }

    private class ClipHolder(view: View) : RecyclerView.ViewHolder(view) {
        val time: TextView = view.findViewById(R.id.tvFactoryClipTime)
        val meta: TextView = view.findViewById(R.id.tvFactoryClipMeta)
        val save: View = view.findViewById(R.id.btnFactoryClipSave)
    }

    companion object {
        private const val STATE_PENDING_SAVE = "factoryDvr.pendingSave"
        private const val PLAYLIST_SPAN = 60
        private const val SAVE_RETURN_GRACE_MS = 5 * 60_000L
        private const val COPY_BUFFER_BYTES = 256 * 1024

        // Process-wide so a copy outlives a rotation and still reports to the page now showing.
        private val copyIo: ExecutorService = Executors.newSingleThreadExecutor { r ->
            Thread(r, "FactoryDvrCopy").apply { isDaemon = true }
        }
        private var copyInFlight: String? = null
        private var copyResultListener: ((String, Boolean) -> Unit)? = null

        private fun formatTime(ctx: Context, ms: Long): String = DateUtils.formatDateTime(
            ctx, ms,
            DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_ABBREV_MONTH
        )

        private fun title(ctx: Context, clip: FactoryVideo): String =
            "${formatTime(ctx, clip.modifiedMs)} · ${clip.file.name}"

        private fun copyOriginal(context: Context, source: File, destination: Uri) {
            val attrs = Files.readAttributes(
                source.toPath(), BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
            if (!attrs.isRegularFile) throw IOException("Not a regular file")
            FileInputStream(source).use { input ->
                val target = context.contentResolver.openFileDescriptor(destination, "w")
                    ?: throw IOException("Cannot open the destination")
                ParcelFileDescriptor.AutoCloseOutputStream(target).use { output ->
                    input.copyTo(output, COPY_BUFFER_BYTES)
                    output.flush()
                    // A USB stick may be pulled right after "Saved"; pipes from cloud providers can't sync.
                    runCatching { output.fd.sync() }
                }
            }
        }
    }
}
