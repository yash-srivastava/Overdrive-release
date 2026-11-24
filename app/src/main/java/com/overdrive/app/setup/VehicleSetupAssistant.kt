package com.overdrive.app.setup

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.CircularProgressIndicator
import com.overdrive.app.R
import com.overdrive.app.config.UnifiedConfigManager
import com.overdrive.app.config.VehicleModelSelection
import com.overdrive.app.camera.dilink5.DiLink5Platform
import com.overdrive.app.launcher.AdbDaemonLauncher
import com.overdrive.app.onboarding.OnboardingGate
import com.overdrive.app.onboarding.OnboardingState
import com.overdrive.app.ui.MainActivity
import com.overdrive.app.ui.dialog.ScratchRootDialog
import com.overdrive.app.ui.model.DaemonType
import com.overdrive.app.ui.vehicle.VehicleArt
import com.overdrive.app.ui.viewmodel.DaemonsViewModel
import com.overdrive.app.util.DaemonHttpClient
import com.overdrive.app.util.ScratchRoot
import org.json.JSONObject
import java.io.File
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Pick the car, review the plan, apply it. Applying only goes through paths the app already
 * uses (ScratchRoot switch, POST /api/models/selected, camera-mode POST + camera restart) and
 * every step re-checks live state, so a resumed run is idempotent. UI state is main-thread
 * only; steps run on [executor] and render only while their run is the live one.
 */
class VehicleSetupAssistant(private val activity: MainActivity) {

    private data class Model(val id: String, val name: String, val nominalKwh: Double?)

    private enum class Step { STORAGE, SERVICE, MODEL, CAMERA_MODE, VERIFY }

    private sealed class Outcome {
        object Done : Outcome()
        data class Skipped(val detail: String? = null) : Outcome()
        object RestartApp : Outcome()
        object Cancelled : Outcome()
        data class Failed(val message: String, val offerStart: Boolean = false) : Outcome()
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val generation = AtomicInteger()
    private var executor: ExecutorService? = null

    private var dialog: AlertDialog? = null
    private var chooserDialog: AlertDialog? = null
    private var gate: OnboardingGate? = null
    private var tvTitle: TextView? = null
    private var tvSubtitle: TextView? = null
    private var rvCars: RecyclerView? = null
    private var svRows: View? = null
    private var llRows: LinearLayout? = null
    private var btnSecondary: Button? = null
    private var btnTertiary: Button? = null
    private var btnPrimary: Button? = null

    private var fromOnboarding = false
    private var onFinished: ((Boolean) -> Unit)? = null
    private var models: List<Model> = emptyList()
    private var snapshot: VehicleSetupSnapshot? = null
    private var selectedModelId: String? = null
    /** "My car isn't listed": default settings, the configured model is left as it is. */
    private var notListed = false
    private var overrideMode: String? = null
    private var carAdapter: CarAdapter? = null
    private val stepRows = HashMap<Step, View>()

    val isActive: Boolean get() = dialog != null

    /** Opens the picker, or the pending setup: continued right after its storage restart, else offered. */
    fun start(fromOnboarding: Boolean, onFinished: ((Boolean) -> Unit)?): Boolean {
        if (dialog != null || activity.isFinishing || activity.isDestroyed) return false
        val pending = VehicleSetupJournal.load(activity)
        this.fromOnboarding = pending?.fromOnboarding ?: fromOnboarding
        selectedModelId = null
        notListed = false
        overrideMode = null
        snapshot = null
        if (!createDialog()) return false
        this.onFinished = onFinished
        gate = OnboardingGate(activity) { onAccOn() }.also { it.register() }
        when {
            pending == null -> showPick()
            pending.storage != VehicleSetupPlan.Storage.NONE && !ScratchRoot.usesLegacyDir() &&
                VehicleSetupJournal.storageAttemptedWithin(activity, AUTO_RESUME_WINDOW_MS) &&
                !VehicleSetupJournal.deferred(activity) ->
                showApply(pending)
            else -> showResume(pending)
        }
        return true
    }

    /** Activity teardown: stop work and drop the UI. A pending journal resumes on next start. */
    fun detach() {
        generation.incrementAndGet()
        onFinished = null
        teardown()
    }

    /**
     * Same edge the onboarding guide closes on; nothing is marked done or cleared. A running
     * storage switch is not interrupted and still ends in its restart; the relaunch then asks.
     */
    private fun onAccOn() {
        generation.incrementAndGet()
        if (VehicleSetupJournal.isPending(activity)) VehicleSetupJournal.markDeferred(activity)
        onFinished = null
        teardown()
    }

    private fun teardown() {
        stopWorker()
        gate?.unregister()
        gate = null
        try { chooserDialog?.dismiss() } catch (_: Throwable) {}
        chooserDialog = null
        try { dialog?.dismiss() } catch (_: Throwable) {}
        dialog = null
        stepRows.clear()
        carAdapter = null
    }

    // ---- dialog shell ------------------------------------------------------------------

    private fun createDialog(): Boolean {
        val root = LayoutInflater.from(activity).inflate(R.layout.dialog_vehicle_setup, null)
        tvTitle = root.findViewById(R.id.tvVehicleSetupTitle)
        tvSubtitle = root.findViewById(R.id.tvVehicleSetupSubtitle)
        rvCars = root.findViewById(R.id.rvVehicleSetupCars)
        svRows = root.findViewById(R.id.svVehicleSetupRows)
        llRows = root.findViewById(R.id.llVehicleSetupRows)
        btnSecondary = root.findViewById(R.id.btnVehicleSetupSecondary)
        btnTertiary = root.findViewById(R.id.btnVehicleSetupTertiary)
        btnPrimary = root.findViewById(R.id.btnVehicleSetupPrimary)

        val metrics = activity.resources.displayMetrics
        val landscape = metrics.widthPixels > metrics.heightPixels
        val contentHeight = (metrics.heightPixels * if (landscape) 0.5f else 0.55f).toInt()
        root.findViewById<View>(R.id.flVehicleSetupContent)?.layoutParams?.height = contentHeight
        val built = MaterialAlertDialogBuilder(activity, R.style.Theme_Overdrive_M3_Dialog)
            .setView(root)
            .setCancelable(false)
            .create()
        // A stray touch outside must never count as Skip; Back and the buttons stay explicit.
        built.setCanceledOnTouchOutside(false)
        built.setOnCancelListener { cancelFromUser() }
        try {
            built.show()
        } catch (t: Throwable) {
            Log.w(TAG, "assistant dialog failed to show", t)
            return false
        }
        val width = (metrics.widthPixels * if (landscape) 0.86f else 0.94f).toInt()
        built.window?.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT)
        dialog = built
        return true
    }

    private fun setButtons(
        secondary: Int?, onSecondary: (() -> Unit)?,
        primary: Int?, onPrimary: (() -> Unit)?,
        tertiary: Int? = null, onTertiary: (() -> Unit)? = null,
    ) {
        fun bind(button: Button?, text: Int?, action: (() -> Unit)?) {
            button ?: return
            if (text == null) {
                button.visibility = View.GONE
                button.setOnClickListener(null)
            } else {
                button.visibility = View.VISIBLE
                button.isEnabled = true
                button.setText(text)
                button.setOnClickListener { action?.invoke() }
            }
        }
        bind(btnSecondary, secondary, onSecondary)
        bind(btnPrimary, primary, onPrimary)
        bind(btnTertiary, tertiary, onTertiary)
    }

    private fun showRows() {
        rvCars?.visibility = View.GONE
        svRows?.visibility = View.VISIBLE
        llRows?.removeAllViews()
    }

    // ---- pick ----------------------------------------------------------------------------

    private fun showPick() {
        dialog?.setCancelable(true)
        tvTitle?.setText(R.string.vehicle_setup_pick_title)
        tvSubtitle?.text = activity.getString(R.string.vehicle_setup_pick_checking)
        rvCars?.visibility = View.VISIBLE
        svRows?.visibility = View.GONE
        setButtons(
            secondary = R.string.action_cancel, onSecondary = { cancelFromUser() },
            primary = R.string.vehicle_setup_next, onPrimary = { showReview() },
        )
        updateNextEnabled()

        val rv = rvCars ?: return
        val widthDp = activity.resources.displayMetrics.widthPixels /
            activity.resources.displayMetrics.density
        rv.layoutManager = GridLayoutManager(activity, if (widthDp >= 900) 4 else if (widthDp >= 560) 3 else 2)
        val adapter = CarAdapter()
        carAdapter = adapter
        rv.adapter = adapter

        val appContext = activity.applicationContext
        val token = generation.incrementAndGet()
        worker().execute {
            val loaded = loadModels(appContext)
            val probe = try { VehicleSetupProbe.collect() } catch (_: Throwable) { null }
            post(token) {
                models = loaded
                snapshot = probe
                if (selectedModelId == null && !notListed) {
                    selectedModelId = (probe?.modelId ?: probe?.detection?.modelHint)
                        ?.let { id -> loaded.firstOrNull { it.id == id }?.id }
                }
                tvSubtitle?.text = probe?.let { detectedLine(it) }
                    ?: activity.getString(R.string.vehicle_setup_pick_subtitle)
                adapter.notifyDataSetChanged()
                updateNextEnabled()
            }
        }
    }

    private fun detectedLine(s: VehicleSetupSnapshot): String {
        val platform = activity.getString(
            when (s.detection.platform) {
                VehicleSetupDetector.Platform.DILINK100 -> R.string.vehicle_setup_platform_dilink100
                VehicleSetupDetector.Platform.DILINK5 -> R.string.vehicle_setup_platform_dilink5
                VehicleSetupDetector.Platform.LEGACY -> R.string.vehicle_setup_platform_legacy
                VehicleSetupDetector.Platform.UNKNOWN -> R.string.vehicle_setup_platform_unknown
            }
        )
        return activity.getString(R.string.vehicle_setup_detected, platform) + " · " +
            activity.getString(R.string.vehicle_setup_pick_subtitle)
    }

    private fun updateNextEnabled() {
        btnPrimary?.isEnabled = (selectedModelId != null || notListed) && snapshot != null
    }

    private inner class CarAdapter : RecyclerView.Adapter<CarAdapter.Holder>() {
        inner class Holder(view: View) : RecyclerView.ViewHolder(view) {
            val card: MaterialCardView = view.findViewById(R.id.cardVehicleSetupCar)
            val image: ImageView = view.findViewById(R.id.ivVehicleSetupCar)
            val name: TextView = view.findViewById(R.id.tvVehicleSetupCarName)
            val badge: TextView = view.findViewById(R.id.tvVehicleSetupCarBadge)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
            Holder(LayoutInflater.from(parent.context)
                .inflate(R.layout.item_vehicle_setup_car, parent, false))

        // The last tile is "My car isn't listed".
        override fun getItemCount(): Int = models.size + 1

        override fun getItemViewType(position: Int): Int =
            if (position == models.size) VIEW_NOT_LISTED else VIEW_MODEL

        override fun onBindViewHolder(holder: Holder, position: Int) {
            if (position == models.size) {
                holder.image.setImageResource(R.drawable.vehicle_fallback)
                holder.name.setText(R.string.vehicle_setup_not_listed)
                holder.badge.setText(R.string.vehicle_setup_not_listed_badge)
                holder.badge.visibility = View.VISIBLE
                holder.card.isChecked = notListed
                holder.card.setOnClickListener {
                    if (!notListed) overrideMode = null
                    notListed = true
                    selectedModelId = null
                    notifyDataSetChanged()
                    updateNextEnabled()
                }
                return
            }
            val model = models[position]
            holder.image.setImageResource(VehicleArt.drawableFor(model.id))
            holder.name.text = model.name
            val hint = snapshot?.detection?.modelHint
            holder.badge.visibility = if (hint == model.id) View.VISIBLE else View.INVISIBLE
            holder.card.isChecked = !notListed && model.id == selectedModelId
            holder.card.setOnClickListener {
                // A camera choice made for one car must not carry over to another.
                if (notListed || selectedModelId != model.id) overrideMode = null
                notListed = false
                selectedModelId = model.id
                notifyDataSetChanged()
                updateNextEnabled()
            }
        }
    }

    // ---- review --------------------------------------------------------------------------

    private fun currentPlan(): VehicleSetupPlan? {
        val s = snapshot ?: return null
        if (selectedModelId == null && !notListed) return null
        return VehicleSetupPlan.build(
            if (notListed) null else selectedModelId, s.detection, s.di5CameraLibrary,
            s.configuredCameraMode ?: s.appActiveCameraMode, s.modelId,
            s.alternateStorage, s.storageSplit, s.serviceReachable, overrideMode,
        )
    }

    private fun showReview() {
        val s = snapshot ?: return
        val plan = currentPlan() ?: return
        val model = if (notListed) null else models.firstOrNull { it.id == selectedModelId } ?: return
        dialog?.setCancelable(true)
        tvTitle?.setText(R.string.vehicle_setup_review_title)
        tvSubtitle?.setText(R.string.vehicle_setup_review_subtitle)
        showRows()
        val rows = llRows ?: return

        if (model == null) {
            // Never cleared here: any model change resets battery health.
            val saved = s.modelId?.let { id -> models.firstOrNull { it.id == id }?.name ?: id }
            addRow(rows, R.drawable.ic_directions_car,
                activity.getString(R.string.vehicle_setup_not_listed),
                if (saved != null) activity.getString(R.string.vehicle_setup_row_not_listed_kept, saved)
                else activity.getString(R.string.vehicle_setup_row_not_listed))
        } else {
            addRow(rows, R.drawable.ic_directions_car, model.name,
                if (!plan.changeModel) activity.getString(R.string.vehicle_setup_row_unchanged)
                else model.nominalKwh?.let {
                    activity.getString(R.string.vehicle_setup_row_kwh,
                        String.format(Locale.getDefault(), "%.1f", it))
                })
        }

        val running = s.runningCameraMode
        val restarts = plan.changeCameraMode ||
            (plan.changeModel && plan.cameraMode != VehicleSetupPlan.MODE_DEFAULT) ||
            (running != null && running != plan.cameraMode)
        val reason = activity.getString(when (plan.modeSource) {
            VehicleSetupPlan.ModeSource.OVERRIDE -> R.string.vehicle_setup_row_camera_chosen
            VehicleSetupPlan.ModeSource.UNSUPPORTED -> R.string.vehicle_setup_row_camera_unsupported
            VehicleSetupPlan.ModeSource.DETECTED ->
                if (plan.changeCameraMode) R.string.vehicle_setup_row_camera_detected
                else R.string.vehicle_setup_row_unchanged
            VehicleSetupPlan.ModeSource.MODEL -> R.string.vehicle_setup_row_camera_model
            VehicleSetupPlan.ModeSource.CURRENT ->
                if (plan.changeCameraMode) R.string.vehicle_setup_row_camera_current
                else R.string.vehicle_setup_row_unchanged
            VehicleSetupPlan.ModeSource.DEFAULT ->
                if (plan.changeCameraMode) R.string.vehicle_setup_row_camera_default
                else R.string.vehicle_setup_row_unchanged
        })
        addRow(rows, R.drawable.ic_camera_probe,
            activity.getString(R.string.vehicle_setup_row_camera, modeLabel(plan.cameraMode)),
            if (restarts) reason + " · " + activity.getString(R.string.vehicle_setup_row_camera_restart)
            else reason,
            R.string.vehicle_setup_change) { pickCameraMode(plan.cameraMode) }

        if (plan.cameraMode == VehicleSetupDetector.MODE_DILINK5 &&
            model?.id == VehicleSetupPlan.SHARK_MODEL_ID) {
            val saved = s.dilink5Mapping
            val keepsSaved = plan.storage == VehicleSetupPlan.Storage.NONE && !saved.isNullOrEmpty()
            addRow(rows, R.drawable.ic_camera_probe,
                activity.getString(R.string.vehicle_setup_row_order),
                if (keepsSaved) activity.getString(R.string.vehicle_setup_row_order_kept, saved)
                else activity.getString(R.string.vehicle_setup_row_order_auto))
        }
        when {
            plan.storage == VehicleSetupPlan.Storage.CHECK -> addRow(rows,
                R.drawable.ic_recording_storage,
                activity.getString(R.string.diagnostics_section_daemon_storage),
                activity.getString(R.string.vehicle_setup_row_storage_check))
            plan.storage == VehicleSetupPlan.Storage.REPAIR -> addRow(rows,
                R.drawable.ic_recording_storage,
                activity.getString(R.string.diagnostics_section_daemon_storage),
                activity.getString(R.string.vehicle_setup_row_storage_repair))
            s.storageSplit -> addRow(rows, R.drawable.ic_warning,
                activity.getString(R.string.diagnostics_section_daemon_storage),
                activity.getString(R.string.vehicle_setup_row_storage_split))
        }

        setButtons(
            secondary = R.string.action_cancel, onSecondary = { cancelFromUser() },
            primary = R.string.vehicle_setup_apply, onPrimary = { acceptPlan(plan, model) },
            tertiary = R.string.vehicle_setup_back, onTertiary = { showPick() },
        )
    }

    private fun pickCameraMode(current: String) {
        if (chooserDialog?.isShowing == true) return
        // dilink5 cannot run without the DiLink 5 camera library, so it is not offered there.
        val withDi5 = snapshot?.di5CameraLibrary == true
        val modes = listOfNotNull(
            VehicleSetupPlan.MODE_DEFAULT, "dilink4",
            VehicleSetupDetector.MODE_DILINK5.takeIf { withDi5 }, VehicleSetupDetector.MODE_CAMERA2,
            com.overdrive.app.camera.AvmSurfaceTexturePlatform.MODE,
        )
        chooserDialog = MaterialAlertDialogBuilder(activity, R.style.Theme_Overdrive_M3_Dialog)
            .setTitle(R.string.vehicle_setup_camera_choice_title)
            .setSingleChoiceItems(modes.map { modeLabel(it) }.toTypedArray(),
                modes.indexOf(current).coerceAtLeast(0)) { choice, which ->
                overrideMode = modes[which]
                choice.dismiss()
                if (dialog != null) showReview()
            }
            .setNegativeButton(R.string.action_cancel, null)
            .setOnDismissListener { dismissed -> if (chooserDialog === dismissed) chooserDialog = null }
            .show()
    }

    private fun acceptPlan(plan: VehicleSetupPlan, model: Model?) {
        val journal = VehicleSetupJournal(model?.id, plan.cameraMode, plan.storage, fromOnboarding)
        if (!journal.save(activity)) {
            Log.w(TAG, "journal commit failed; applying without resume support")
        }
        showApply(journal)
    }

    // ---- resume ----------------------------------------------------------------------------

    private fun showResume(journal: VehicleSetupJournal) {
        dialog?.setCancelable(false)
        if (models.isEmpty()) models = loadModels(activity)
        val name = journal.modelId?.let { id -> models.firstOrNull { it.id == id }?.name ?: id }
            ?: activity.getString(R.string.vehicle_setup_not_listed)
        tvTitle?.setText(R.string.vehicle_setup_resume_title)
        tvSubtitle?.text = activity.getString(R.string.vehicle_setup_resume_subtitle,
            name, modeLabel(journal.cameraMode))
        showRows()
        setButtons(
            secondary = R.string.vehicle_setup_discard, onSecondary = { cancelFromUser() },
            primary = R.string.vehicle_setup_continue, onPrimary = {
                VehicleSetupJournal.clearDeferred(activity)
                showApply(journal)
            },
        )
    }

    // ---- apply ---------------------------------------------------------------------------

    private fun stepsFor(journal: VehicleSetupJournal): List<Step> = buildList {
        if (journal.storage != VehicleSetupPlan.Storage.NONE) add(Step.STORAGE)
        add(Step.SERVICE)
        if (journal.modelId != null) add(Step.MODEL)
        add(Step.CAMERA_MODE)
        add(Step.VERIFY)
    }

    private fun showApply(journal: VehicleSetupJournal) {
        dialog?.setCancelable(false)
        tvTitle?.setText(R.string.vehicle_setup_apply_title)
        tvSubtitle?.setText(R.string.vehicle_setup_apply_subtitle)
        showRows()
        val rows = llRows ?: return
        stepRows.clear()
        if (models.isEmpty()) models = loadModels(activity)
        val modelName = journal.modelId?.let { id -> models.firstOrNull { it.id == id }?.name ?: id }
        for (step in stepsFor(journal)) {
            val title = when (step) {
                Step.STORAGE -> activity.getString(
                    if (journal.storage == VehicleSetupPlan.Storage.CHECK)
                        R.string.vehicle_setup_step_storage_check
                    else R.string.vehicle_setup_step_storage)
                Step.SERVICE -> activity.getString(R.string.vehicle_setup_step_service)
                Step.MODEL -> activity.getString(R.string.vehicle_setup_step_model, modelName)
                Step.CAMERA_MODE -> activity.getString(
                    R.string.vehicle_setup_step_camera, modeLabel(journal.cameraMode))
                Step.VERIFY -> activity.getString(R.string.vehicle_setup_step_verify)
            }
            stepRows[step] = addRow(rows, 0, title, null)
        }
        runSteps(journal, stepsFor(journal).first())
    }

    private fun runSteps(journal: VehicleSetupJournal, from: Step) {
        setButtons(secondary = R.string.action_cancel, onSecondary = { cancelFromUser() },
            primary = null, onPrimary = null)
        val steps = stepsFor(journal)
        val pending = steps.subList(steps.indexOf(from).coerceAtLeast(0), steps.size)
        for (step in pending) markRow(step, RowState.PENDING, null)
        val appContext = activity.applicationContext
        val token = generation.incrementAndGet()
        worker().execute {
            for (step in pending) {
                if (token != generation.get()) return@execute
                post(token) {
                    markRow(step, RowState.RUNNING, null)
                    btnSecondary?.isEnabled = step != Step.STORAGE
                }
                val outcome = try {
                    runStep(step, journal, appContext, token)
                } catch (_: InterruptedException) {
                    Outcome.Cancelled
                } catch (t: Throwable) {
                    Log.w(TAG, "step $step crashed", t)
                    Outcome.Failed(t.message ?: t.javaClass.simpleName)
                }
                when (outcome) {
                    Outcome.Done -> post(token) {
                        btnSecondary?.isEnabled = true
                        markRow(step, RowState.DONE, null)
                    }
                    is Outcome.Skipped -> post(token) {
                        btnSecondary?.isEnabled = true
                        markRow(step, RowState.SKIPPED, outcome.detail)
                    }
                    // The switch already committed: restarting is the only consistent end state,
                    // even when this run was cancelled or its Activity recreated meanwhile.
                    Outcome.RestartApp -> {
                        mainHandler.post {
                            try {
                                ScratchRootDialog.restartApp(activity)
                            } catch (t: Throwable) {
                                Log.w(TAG, "restart after storage switch failed", t)
                                Runtime.getRuntime().exit(0)
                            }
                        }
                        return@execute
                    }
                    Outcome.Cancelled -> return@execute
                    is Outcome.Failed -> {
                        post(token) {
                            btnSecondary?.isEnabled = true
                            markRow(step, RowState.FAILED, outcome.message)
                            showFailure(journal, step, outcome.offerStart)
                        }
                        return@execute
                    }
                }
            }
            post(token) { showDone(journal) }
        }
    }

    private fun runStep(step: Step, journal: VehicleSetupJournal, appContext: Context, token: Int): Outcome =
        when (step) {
            Step.STORAGE -> runStorage(journal, appContext, token)
            Step.SERVICE -> waitForService(token)
            Step.MODEL -> applyModel(journal, appContext, token)
            Step.CAMERA_MODE -> applyCameraMode(journal, appContext, token)
            Step.VERIFY -> verify(journal, appContext)
        }

    private fun runStorage(journal: VehicleSetupJournal, appContext: Context, token: Int): Outcome {
        if (!ScratchRoot.usesLegacyDir()) return switchedOrSet(appContext)
        // A switch that succeeded but left this process on legacy must not loop restarts.
        if (VehicleSetupJournal.storageAttempted(appContext)) {
            return Outcome.Failed(activity.getString(R.string.vehicle_setup_fail_storage,
                activity.getString(R.string.vehicle_setup_fail_storage_kept)))
        }
        val launcher = AdbDaemonLauncher(appContext)
        try {
            // Both CHECK and REPAIR switch only when legacy cannot run the camera service, so a
            // working root (or the owner's toggle on one) is never overridden.
            when (legacyRootUsable(launcher)) {
                true -> return Outcome.Skipped(activity.getString(
                    if (journal.storage == VehicleSetupPlan.Storage.REPAIR)
                        R.string.vehicle_setup_storage_repair_skipped
                    else R.string.vehicle_setup_storage_not_needed))
                null -> return Outcome.Failed(
                    activity.getString(R.string.vehicle_setup_fail_storage_check))
                false -> Unit
            }
            // Pairs with stopWorker(): flag first, then the token, so a cancel either lands
            // before the switch starts or never interrupts it. Process-wide: a recreated
            // Activity's resumed run waits for the old switch instead of starting a second.
            val waitUntil = System.currentTimeMillis() + SWITCH_WAIT_MS
            while (!STORAGE_IN_FLIGHT.compareAndSet(false, true)) {
                if (token != generation.get()) return Outcome.Cancelled
                if (!ScratchRoot.usesLegacyDir()) return switchedOrSet(appContext)
                if (System.currentTimeMillis() > waitUntil) {
                    return Outcome.Failed(activity.getString(R.string.vehicle_setup_fail_storage,
                        activity.getString(R.string.vehicle_setup_storage_busy)))
                }
                Thread.sleep(500L)
            }
            try {
                if (token != generation.get()) return Outcome.Cancelled
                if (!ScratchRoot.usesLegacyDir()) return switchedOrSet(appContext)
                Thread.interrupted()
                val failure = try {
                    ScratchRootDialog.performSwitch(appContext, launcher, true)
                } catch (t: Throwable) {
                    t.message ?: t.javaClass.simpleName
                }
                if (failure != null) {
                    return Outcome.Failed(activity.getString(R.string.vehicle_setup_fail_storage, failure))
                }
                VehicleSetupJournal.markStorageAttempted(appContext)
                return Outcome.RestartApp
            } finally {
                STORAGE_IN_FLIGHT.set(false)
            }
        } finally {
            try { launcher.releasePerInstanceResources() } catch (_: Throwable) {}
        }
    }

    private fun switchedOrSet(appContext: Context): Outcome = Outcome.Skipped(
        if (VehicleSetupJournal.storageAttempted(appContext))
            activity.getString(R.string.vehicle_setup_storage_switched) else null)

    /**
     * Legacy launches always put bmmcamera.jar on the camera classpath and app_process aborts
     * without it (Shark 6), so a writable root alone is not enough. Null when ADB could not answer.
     */
    private fun legacyRootUsable(launcher: AdbDaemonLauncher): Boolean? {
        if (!File(BMM_CAMERA_JAR).isFile) return false
        val out = runShell(launcher, LEGACY_PROBE_COMMAND, SHELL_TIMEOUT_S) ?: return null
        return when {
            out.contains(PROBE_OK) -> true
            out.contains(PROBE_LOCKED) -> false
            else -> null
        }
    }

    private fun waitForService(token: Int): Outcome {
        val deadline = System.currentTimeMillis() + SERVICE_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            if (token != generation.get()) return Outcome.Cancelled
            if (httpCode("/status") == 200) return Outcome.Done
            Thread.sleep(POLL_MS)
        }
        return Outcome.Failed(activity.getString(R.string.vehicle_setup_fail_service), offerStart = true)
    }

    private fun applyModel(journal: VehicleSetupJournal, appContext: Context, token: Int): Outcome {
        val targetId = journal.modelId ?: return Outcome.Skipped()
        // Any model POST resets battery health, so an unchanged car is never re-sent.
        if (savedModelId() == targetId) return Outcome.Skipped()
        val body = JSONObject().put("modelId", targetId)
        loadModels(appContext).firstOrNull { it.id == targetId }?.nominalKwh
            ?.let { body.put("nominalKwh", it) }
        val deadline = System.currentTimeMillis() + MODEL_DEADLINE_MS
        var attempt = 0
        var reason: String? = null
        while (true) {
            if (token != generation.get()) return Outcome.Cancelled
            val reply = postJsonReply("/api/models/selected", body.toString(), MODEL_READ_TIMEOUT_MS)
            // A reply lost to a timeout may still have committed; a saved car is never re-sent.
            if (reply.json?.optBoolean("ok", false) == true || savedModelId() == targetId) {
                // DI5 order and camera2/DI4 profiles read the model at camera start; the legacy
                // walk-through that follows a default-mode setup restarts on its own.
                if (journal.cameraMode != VehicleSetupPlan.MODE_DEFAULT) {
                    VehicleSetupJournal.setRestartOwed(appContext, true)
                }
                return Outcome.Done
            }
            reason = reply.reason
            val pause = minOf((attempt + 1) * 2_000L, 10_000L)
            if (++attempt >= MODEL_ATTEMPTS || System.currentTimeMillis() + pause >= deadline) break
            Thread.sleep(pause)
        }
        return Outcome.Failed(
            reason?.let { activity.getString(R.string.vehicle_setup_fail_model_reason, it) }
                ?: activity.getString(R.string.vehicle_setup_fail_model))
    }

    private fun savedModelId(): String? {
        val vehicle = try { UnifiedConfigManager.loadConfig().optJSONObject("vehicle") } catch (_: Throwable) { null }
        val rawId = vehicle?.optString("modelId", "")?.takeIf { it.isNotEmpty() }
        val rawSource = vehicle?.optString("modelSource", "")?.takeIf { it.isNotEmpty() }
        return VehicleModelSelection.resolvedModelId(rawId, rawSource)
    }

    private fun applyCameraMode(journal: VehicleSetupJournal, appContext: Context, token: Int): Outcome {
        val target = journal.cameraMode
        val camera = try { UnifiedConfigManager.loadConfig().optJSONObject("camera") } catch (_: Throwable) { null }
        val persisted = DiLink5Platform.normalizeConfiguredMode(
            camera?.optString("cameraMode", "")?.takeIf { it.isNotEmpty() })
        val before = activeMode()
        val owed = VehicleSetupJournal.restartOwed(appContext)
        if (before == target && (persisted == null || persisted == target) && !owed) {
            return Outcome.Skipped()
        }
        if (persisted != target) {
            if (token != generation.get()) return Outcome.Cancelled
            val reply = postJson("/api/surveillance/config", JSONObject().put("cameraMode", target).toString())
            if (reply == null || !reply.optBoolean("success", true)) {
                return Outcome.Failed(activity.getString(R.string.vehicle_setup_fail_camera_save))
            }
        }
        VehicleSetupJournal.setRestartOwed(appContext, true)
        val launcher = AdbDaemonLauncher(appContext)
        try {
            var pidsBefore: Set<String>? = null
            for (attempt in 0 until PS_ATTEMPTS) {
                pidsBefore = cameraDaemonPids(launcher)
                if (pidsBefore != null || token != generation.get()) break
                Thread.sleep(1_000L)
            }
            if (token != generation.get()) return Outcome.Cancelled
            // Without a mode flip to watch, only pids prove a restart; never restart blind.
            if (pidsBefore == null && (before == null || before == target)) {
                return Outcome.Failed(activity.getString(R.string.vehicle_setup_fail_restart_unverifiable))
            }
            mainHandler.post {
                if (token == generation.get() && !activity.isFinishing && !activity.isDestroyed) {
                    activity.restartCameraDaemonForOnboarding()
                }
            }
            val deadline = System.currentTimeMillis() + MODE_SWITCH_TIMEOUT_MS
            while (System.currentTimeMillis() < deadline) {
                Thread.sleep(MODE_POLL_MS)
                if (token != generation.get()) return Outcome.Cancelled
                if (activeMode() != target) continue
                // vehicleMode only changes at boot, so a flip proves a fresh daemon; otherwise
                // every camera-daemon pid seen before the request must be gone.
                val restarted = (before != null && before != target) || run {
                    val now = cameraDaemonPids(launcher)
                    pidsBefore != null && now != null && now.isNotEmpty() && now.none { it in pidsBefore }
                }
                if (restarted) {
                    VehicleSetupJournal.setRestartOwed(appContext, false)
                    return Outcome.Done
                }
            }
            return Outcome.Failed(activity.getString(
                if (before == target) R.string.vehicle_setup_fail_restart
                else R.string.vehicle_setup_fail_camera_switch))
        } finally {
            try { launcher.releasePerInstanceResources() } catch (_: Throwable) {}
        }
    }

    private fun verify(journal: VehicleSetupJournal, appContext: Context): Outcome =
        if (httpCode("/status") == 200 && activeMode() == journal.cameraMode &&
            !VehicleSetupJournal.restartOwed(appContext)) Outcome.Done
        else Outcome.Failed(activity.getString(R.string.vehicle_setup_fail_camera_switch))

    private fun showFailure(journal: VehicleSetupJournal, step: Step, offerStart: Boolean) {
        setButtons(
            secondary = R.string.dialog_close, onSecondary = { cancelFromUser() },
            primary = R.string.vehicle_setup_retry, onPrimary = {
                runSteps(journal, if (step == Step.VERIFY) Step.CAMERA_MODE else step)
            },
            tertiary = if (offerStart) R.string.vehicle_setup_start_service else null,
            onTertiary = {
                // Explicit owner request: this clears the stop sentinel and parked marker.
                ViewModelProvider(activity)[DaemonsViewModel::class.java]
                    .startDaemon(DaemonType.CAMERA_DAEMON, true)
                runSteps(journal, step)
            },
        )
    }

    private fun showDone(journal: VehicleSetupJournal) {
        // Everything applied: nothing left to resume, even if the Activity is recreated now.
        recordOutcome(true, journal)
        tvTitle?.setText(R.string.vehicle_setup_done_title)
        tvSubtitle?.setText(R.string.vehicle_setup_done_subtitle)
        setButtons(secondary = null, onSecondary = null,
            primary = R.string.vehicle_setup_done, onPrimary = { finish(true, journal) })
    }

    // ---- finish --------------------------------------------------------------------------

    /** A switch already running is not interrupted and still restarts the app, with nothing to resume. */
    private fun cancelFromUser() {
        finish(false, null)
    }

    private fun recordOutcome(success: Boolean, journal: VehicleSetupJournal?) {
        VehicleSetupJournal.clear(activity)
        val state = OnboardingState.get(activity)
        if (success || fromOnboarding) state.vehicleStepDone = true
        // The guided camera walk-through only fits the legacy camera system.
        if (success && journal != null && journal.cameraMode != VehicleSetupPlan.MODE_DEFAULT) {
            state.cameraStep = OnboardingState.CameraStep.SAVED_OK
        }
    }

    private fun finish(success: Boolean, journal: VehicleSetupJournal?) {
        generation.incrementAndGet()
        recordOutcome(success, journal)
        val callback = onFinished
        onFinished = null
        teardown()
        callback?.invoke(success)
    }

    // ---- rows ----------------------------------------------------------------------------

    private enum class RowState { PENDING, RUNNING, DONE, SKIPPED, FAILED }

    private fun addRow(
        parent: LinearLayout, iconRes: Int, title: String, detail: String?,
        actionText: Int? = null, action: (() -> Unit)? = null,
    ): View {
        val row = LayoutInflater.from(activity).inflate(R.layout.item_vehicle_setup_row, parent, false)
        val icon = row.findViewById<ImageView>(R.id.ivVehicleSetupRowIcon)
        if (iconRes != 0) icon.setImageResource(iconRes)
        row.findViewById<TextView>(R.id.tvVehicleSetupRowTitle).text = title
        row.findViewById<TextView>(R.id.tvVehicleSetupRowDetail).apply {
            text = detail
            visibility = if (detail.isNullOrEmpty()) View.GONE else View.VISIBLE
        }
        row.findViewById<Button>(R.id.btnVehicleSetupRowAction).apply {
            if (actionText != null && action != null) {
                visibility = View.VISIBLE
                setText(actionText)
                setOnClickListener { action() }
            }
        }
        parent.addView(row)
        return row
    }

    private fun markRow(step: Step, state: RowState, detail: String?) {
        val row = stepRows[step] ?: return
        val icon = row.findViewById<ImageView>(R.id.ivVehicleSetupRowIcon)
        val progress = row.findViewById<CircularProgressIndicator>(R.id.pbVehicleSetupRow)
        progress.visibility = if (state == RowState.RUNNING) View.VISIBLE else View.GONE
        icon.visibility = if (state == RowState.RUNNING) View.GONE else View.VISIBLE
        icon.setImageResource(
            when (state) {
                RowState.DONE, RowState.SKIPPED -> R.drawable.ic_check_circle
                RowState.FAILED -> R.drawable.ic_error
                else -> R.drawable.status_dot_neutral
            }
        )
        val text = detail ?: if (state == RowState.SKIPPED)
            activity.getString(R.string.vehicle_setup_row_unchanged) else null
        row.findViewById<TextView>(R.id.tvVehicleSetupRowDetail).apply {
            this.text = text
            visibility = if (text.isNullOrEmpty()) View.GONE else View.VISIBLE
        }
    }

    // ---- helpers -------------------------------------------------------------------------

    private fun modeLabel(mode: String?): String = activity.getString(
        when (mode) {
            "dilink4" -> R.string.vehicle_setup_mode_dilink4
            VehicleSetupDetector.MODE_DILINK5 -> R.string.vehicle_setup_mode_dilink5
            VehicleSetupDetector.MODE_CAMERA2 -> R.string.vehicle_setup_mode_camera2
            com.overdrive.app.camera.AvmSurfaceTexturePlatform.MODE -> R.string.camera_mode_option_surfacetexture
            else -> R.string.vehicle_setup_mode_default
        }
    )

    private fun worker(): ExecutorService =
        executor ?: Executors.newSingleThreadExecutor().also { executor = it }

    /** Callers bump [generation] first; a running storage switch is left to finish. */
    private fun stopWorker() {
        val running = executor ?: return
        executor = null
        if (STORAGE_IN_FLIGHT.get()) running.shutdown() else running.shutdownNow()
    }

    private fun post(token: Int, block: () -> Unit) {
        mainHandler.post {
            if (token == generation.get() && dialog != null) block()
        }
    }

    private fun httpCode(path: String): Int = try {
        val conn = DaemonHttpClient.open(path, "GET", 1500, 2000)
        try { conn.responseCode } finally { conn.disconnect() }
    } catch (_: Throwable) {
        -1
    }

    private fun activeMode(): String? = try {
        val conn = DaemonHttpClient.open(STATUS_PATH, "GET", 1500, 2000)
        try {
            if (conn.responseCode != 200) null
            else DiLink5Platform.normalizeConfiguredMode(
                JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
                    .optString("vehicleMode", ""))
        } finally {
            conn.disconnect()
        }
    } catch (_: Throwable) {
        null
    }

    /** POST that trusts only a 2xx JSON body; null on any transport or parse failure. */
    private fun postJson(path: String, body: String): JSONObject? =
        postJsonReply(path, body, 8000).json

    private class Reply(val json: JSONObject?, val reason: String?)

    /** [postJson] that also says why it failed (HTTP code, daemon error, transport error). */
    private fun postJsonReply(path: String, body: String, readTimeoutMs: Int): Reply = try {
        val conn = DaemonHttpClient.open(path, "POST", 3000, readTimeoutMs)
        try {
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.outputStream.use { it.write(body.toByteArray()) }
            val code = conn.responseCode
            if (code !in 200..299) Reply(null, "HTTP $code")
            else JSONObject(conn.inputStream.bufferedReader().use { it.readText() }).let {
                Reply(it, it.optString("error", "").ifEmpty { null })
            }
        } finally {
            conn.disconnect()
        }
    } catch (t: Throwable) {
        Reply(null, t.message ?: t.javaClass.simpleName)
    }

    /** Live camera-daemon pids, matched like DaemonLauncher.processAliveInSnapshot; null on ADB failure. */
    private fun cameraDaemonPids(launcher: AdbDaemonLauncher): Set<String>? {
        val out = runShell(launcher, "ps -A -o PID,S,ARGS", PS_TIMEOUT_S) ?: return null
        return out.lineSequence().mapNotNull { line ->
            val parts = line.trim().split(WHITESPACE, limit = 3)
            if (parts.size < 3 || parts[1].startsWith("Z") || !parts[0].all(Char::isDigit)) null
            else if (parts[2].contains(CAMERA_DAEMON_PROCESS)) parts[0] else null
        }.toSet()
    }

    /** Blocking shell command over the app's ADB lane; stdout, or null on error or timeout. */
    private fun runShell(launcher: AdbDaemonLauncher, command: String, timeoutS: Long): String? {
        val latch = CountDownLatch(1)
        val out = StringBuffer()
        var failed = false
        launcher.executeShellCommand(command, object : AdbDaemonLauncher.LaunchCallback {
            override fun onLog(message: String) { out.append(message).append('\n') }
            override fun onLaunched() { latch.countDown() }
            override fun onError(error: String) { failed = true; latch.countDown() }
        })
        if (!latch.await(timeoutS, TimeUnit.SECONDS) || failed) return null
        return out.toString()
    }

    private fun loadModels(context: Context): List<Model> = try {
        val json = context.assets.open(MANIFEST_ASSET).bufferedReader().use { it.readText() }
        val array = JSONObject(json).optJSONArray("models")
        val list = ArrayList<Model>()
        if (array != null) {
            for (i in 0 until array.length()) {
                val entry = array.optJSONObject(i) ?: continue
                val id = entry.optString("id", "")
                if (id.isEmpty()) continue
                val kwh = entry.optDouble("nominalKwh", Double.NaN)
                val name = if (id == "seagull") context.getString(R.string.vehicle_model_seagull)
                    else entry.optString("name", id)
                list.add(Model(id, name, kwh.takeIf { it.isFinite() && it > 0.0 }))
            }
        }
        list
    } catch (_: Throwable) {
        emptyList()
    }

    private companion object {
        const val TAG = "VehicleSetupAssistant"
        const val MANIFEST_ASSET = "web/shared/models/manifest.json"
        const val VIEW_MODEL = 0
        const val VIEW_NOT_LISTED = 1
        const val STATUS_PATH = "/api/vehicle/cluster-mirror-status"
        const val CAMERA_DAEMON_PROCESS = "byd_cam_daemon"
        // Same file DaemonLauncher.cameraClasspathPrefix() tests.
        const val BMM_CAMERA_JAR = "/system/framework/bmmcamera.jar"
        const val PROBE_OK = "OD_LEGACY_OK"
        const val PROBE_LOCKED = "OD_LEGACY_LOCKED"
        const val LEGACY_PROBE_COMMAND = "f=/data/local/tmp/.od_setup_probe_\$\$; " +
            "if (echo x > \"\$f\") 2>/dev/null; then rm -f \"\$f\"; echo $PROBE_OK; " +
            "else echo $PROBE_LOCKED; fi"
        val WHITESPACE = Regex("\\s+")
        const val POLL_MS = 2_000L
        const val MODE_POLL_MS = 3_000L
        const val SHELL_TIMEOUT_S = 25L
        const val PS_TIMEOUT_S = 10L
        const val PS_ATTEMPTS = 3
        const val SWITCH_WAIT_MS = 90_000L
        // Boot start runs 45 s after launch; a mode restart re-arms that same timer.
        const val SERVICE_TIMEOUT_MS = 150_000L
        const val MODE_SWITCH_TIMEOUT_MS = 120_000L
        // The daemon resets and re-seeds battery health under the config lock before it answers.
        const val MODEL_READ_TIMEOUT_MS = 30_000
        const val MODEL_DEADLINE_MS = 120_000L
        const val MODEL_ATTEMPTS = 8
        // Only the relaunch the storage switch itself caused continues without a tap.
        const val AUTO_RESUME_WINDOW_MS = 120_000L
        // Process-wide: an interrupt mid-switch could leave the app and daemons on different roots.
        val STORAGE_IN_FLIGHT = AtomicBoolean(false)
    }
}
