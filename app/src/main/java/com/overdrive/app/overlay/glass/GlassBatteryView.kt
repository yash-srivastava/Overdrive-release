package com.overdrive.app.overlay.glass

import com.overdrive.app.R
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import kotlin.math.roundToInt

/** Battery & range — a glass battery cell of glowing liquid; plug-in hybrids get a second, gold fuel cell. */
@SuppressLint("ViewConstructor")
class GlassBatteryView(context: Context, env: GlassEnv, ticker: GlassTicker) : GlassWidgetView(context, env, ticker, 272f, 184f) {

    /**
     * [rangeKm] and [fuelPct] are null when the car didn't report them (the widget then keeps the last
     * level and never claims 0); [calibratedKm] is OverDrive's learned range (a hybrid's combined figure),
     * null until it has learned enough trips.
     */
    data class State(val socPct: Int, val rangeKm: Int?, val phev: Boolean, val fuelPct: Int?, val calibratedKm: Int? = null)

    private var state = State(0, null, false, null)
    private var shownCalibrated = false
    private var hasKm = false
    // a hybrid's fuel cell appears once the car has reported fuel: an empty tank for "no reading" would read as empty
    private var fuelCell = false
    private val soc = Spring.liquid()
    private val fuel = Spring.liquid()
    private val km = Spring.number()
    private var cell: CellPainter? = null
    private var shownKm = -1
    private var colorPct = -1
    private var fuelColorPct = -1
    private val text = GlassText(context) { env.theme }
    private val agitation = FloatArray(2)
    private var bound = false

    /** Unchanged data is a no-op; [force] re-applies it (theme change). */
    fun bind(s: State, animate: Boolean, force: Boolean = false) {
        if (!force && bound && s == state) return
        bound = true
        // a new unit formats afresh
        if (force) shownKm = Int.MIN_VALUE
        val withFuel = s.phev && (fuelCell || s.fuelPct != null)
        val newFuelCell = withFuel && !fuelCell
        val layoutChanged = withFuel != fuelCell || cell == null
        fuelCell = withFuel
        // a hybrid's figure is the combined range, a BEV's the electric one: never count between them
        val powertrainChanged = state.phev != s.phev
        state = s
        if (layoutChanged) { cell = null; layersChanged() }
        val instant = !animate || env.reduceMotion
        val calibrated = s.calibratedKm
        soc.set(s.socPct / 100f, instant)
        // a fuel cell that has just appeared shows its level, not a tank filling from empty
        s.fuelPct?.let { fuel.set(it / 100f, instant || newFuelCell) }
        val headline = calibrated ?: s.rangeKm
        // the first figure, and a switch between the car's and OverDrive's, jump: counting between two quantities would mislead
        if (headline != null) {
            km.set(headline.toFloat(), instant || !hasKm || powertrainChanged || shownCalibrated != (calibrated != null))
            shownCalibrated = calibrated != null
            hasKm = true
        } else if (hasKm && shownCalibrated != (calibrated != null)) {
            // the figure on show is the other source's: it can't stand under this label
            hasKm = false
        }
        text.labelText = if (calibrated != null) gs(R.string.glass_range_calibrated) else if (s.phev) gs(R.string.glass_range_total) else gs(R.string.glass_range)
        val t = env.theme
        // the cell shows the level; the status line gives the exact charge (a hybrid's names it, beside the fuel cell)
        val pct = "${s.socPct}%"
        when {
            s.phev && s.fuelPct != null && s.fuelPct <= 12 -> { text.statusText = gs(R.string.glass_refuel_soon); text.statusColor = t.warn }
            !s.phev && s.socPct <= 10 -> { text.statusText = gs(R.string.glass_soc_very_low, pct); text.statusColor = t.danger }
            !s.phev && s.socPct <= 20 -> { text.statusText = gs(R.string.glass_soc_charge_soon, pct); text.statusColor = t.warn }
            // with OverDrive's figure as the headline, the car's own sits beside the charge for comparison
            !s.phev && calibrated != null && s.rangeKm != null -> {
                text.statusText = gs(R.string.glass_soc_car_range, pct, GlassUnits.dist(s.rangeKm.toFloat(), env.miles).roundToInt(), GlassUnits.distUnit(env.miles))
                text.statusColor = 0
            }
            else -> { text.statusText = if (s.phev) gs(R.string.glass_battery_pct, pct) else pct; text.statusColor = 0 }
        }
        updateValue()
        requestFrames()
        invalidate()
    }

    private fun painter(): CellPainter {
        cell?.let { return it }
        val capH = minOf(154f, baseH - 30f)
        val k = capH / 154f
        val y = (baseH - capH) / 2f
        return (if (fuelCell) CellPainter(env.theme, 18f, y, 102f * k, capH, true)
        else CellPainter(env.theme, 26f, y, 62f * k, capH, false)).also { cell = it; colorPct = -1; fuelColorPct = -1 }
    }

    // no range reported yet shows a dash, never a made-up 0
    private fun updateValue() {
        if (!hasKm) { if (shownKm != -2) { shownKm = -2; text.setValue("–", GlassUnits.distUnit(env.miles)) }; return }
        val v = GlassUnits.dist(km.value, env.miles).roundToInt().coerceAtLeast(0)
        if (v == shownKm) return
        shownKm = v
        text.setValue(v.toString(), GlassUnits.distUnit(env.miles))
    }

    override fun animate(nowMs: Long, dt: Float): Boolean {
        val a = soc.step(dt); val b = fuel.step(dt); val c = km.step(dt)
        updateValue()
        return a || b || c
    }

    override fun onThemeChanged() { cell = null; if (bound) bind(state, false, force = true) }

    override fun drawBack(c: Canvas) = painter().drawBack(c)
    override fun drawFront(c: Canvas) = painter().drawFront(c)

    override fun drawLive(c: Canvas, nowMs: Long) {
        val p = painter()
        val sp = (soc.value * 100f).roundToInt().coerceIn(0, 100)
        if (sp != colorPct) { colorPct = sp; p.setColor(0, socColor(env.theme, sp.toFloat())) }
        p.setLevel(0, soc.value)
        agitation[0] = soc.velocity
        if (fuelCell) {
            val fp = (fuel.value * 100f).roundToInt().coerceIn(0, 100)
            if (fp != fuelColorPct) { fuelColorPct = fp; p.setColor(1, fuelColor(env.theme, fp.toFloat())) }
            p.setLevel(1, fuel.value)
            agitation[1] = fuel.velocity
        }
        p.drawLive(c, agitation)
        val textX = p.cells.last().x + p.cells.last().rx * 2f + 22f
        text.draw(c, textX, 0f, shellH)
    }

    companion object {
        private val SOC_X = floatArrayOf(0f, 10f, 18f, 26f, 34f, 100f)
        private val FUEL_X = floatArrayOf(0f, 8f, 16f, 100f)

        /** Mint holds down to ~34 %, then a short amber band, coral at 10 % and below. */
        fun socColor(t: GlassTheme, soc: Float) = GlassColor.rampHue(SOC_X,
            intArrayOf(t.glowDanger, t.glowDanger, t.glowWarn, t.glowWarn, t.glowAccent, t.glowAccent), soc)

        fun fuelColor(t: GlassTheme, pct: Float) = GlassColor.rampHue(FUEL_X, intArrayOf(t.glowDanger, t.glowDanger, t.fuel, t.fuel), pct)
    }
}
