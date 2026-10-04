package com.overdrive.app.navmap

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.color.MaterialColors
import com.overdrive.app.R
import com.overdrive.app.navmap.nav.NavRoute

/**
 * Adapter for the route-options bottom sheet: one row per candidate route
 * returned by `ValhallaRouteClient.routesWithAlternates`. Each row shows a
 * route label ("Route 1", "Route 2", …), an optional "Fastest" hint on the
 * route with the lowest duration, the distance, and the ETA.
 *
 * <p>This is a small, fixed-size list (≤3 rows) rebuilt wholesale whenever a
 * new destination is chosen, so a plain [RecyclerView.Adapter] over an
 * in-memory list is the right tool — no DiffUtil churn needed. Selection is
 * tracked here ([selectedIndex]); tapping a row notifies the host via
 * [onRouteSelected] AND repaints the previously- and newly-selected rows so
 * the leading glyph / label / check reflect the active choice.
 *
 * <p>All colors are resolved from theme attrs ([MaterialColors.getColor]) so
 * the sheet is correct in both day and night.
 */
class NavRouteOptionAdapter(
    private val onRouteSelected: (Int) -> Unit
) : RecyclerView.Adapter<NavRouteOptionAdapter.RouteViewHolder>() {

    private val routes = ArrayList<NavRoute>()

    /**
     * Per-route hazard info parallel to [routes]: severity counts + whether the
     * route corridor has any RoadSense coverage at all. `mapped=false` means the
     * area is un-surveyed (show "Not mapped yet"), distinct from mapped-but-clear.
     */
    data class RouteHazardInfo(
        val severe: Int, val moderate: Int, val minor: Int, val mapped: Boolean
    )
    private var hazardCounts: List<RouteHazardInfo> = emptyList()

    /** Index of the route currently highlighted as selected (default 0). */
    var selectedIndex: Int = 0
        private set

    /** Index of the fastest route (lowest duration), for the "Fastest" hint. */
    private var fastestIndex: Int = 0

    /** Replace the route set and reset the selection to the primary route. */
    fun setRoutes(newRoutes: List<NavRoute>) {
        routes.clear()
        routes.addAll(newRoutes)
        hazardCounts = emptyList() // counts arrive async via setHazardCounts
        selectedIndex = 0
        fastestIndex = if (routes.isEmpty()) 0 else
            routes.indices.minByOrNull { routes[it].totalDurationSeconds } ?: 0
        notifyDataSetChanged()
    }

    /**
     * Supply hazards-along-route counts (severe, moderate, minor) per route,
     * parallel to the current route list. Computed off-thread by the host after
     * the routes are shown; this just repaints the pills.
     */
    fun setHazardCounts(counts: List<RouteHazardInfo>) {
        hazardCounts = counts
        notifyDataSetChanged()
    }

    /** Programmatically select [index] (e.g. from a map line tap) and repaint. */
    fun selectIndex(index: Int) {
        if (index < 0 || index >= routes.size || index == selectedIndex) return
        val prev = selectedIndex
        selectedIndex = index
        notifyItemChanged(prev)
        notifyItemChanged(index)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RouteViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_nav_route_option, parent, false)
        return RouteViewHolder(view)
    }

    override fun onBindViewHolder(holder: RouteViewHolder, position: Int) {
        holder.bind(routes[position], position)
    }

    override fun getItemCount(): Int = routes.size

    inner class RouteViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val ivIcon: ImageView = itemView.findViewById(R.id.ivRouteIcon)
        private val tvLabel: TextView = itemView.findViewById(R.id.tvRouteLabel)
        private val tvHint: TextView = itemView.findViewById(R.id.tvRouteHint)
        private val tvDistance: TextView = itemView.findViewById(R.id.tvRouteDistance)
        private val tvEta: TextView = itemView.findViewById(R.id.tvRouteEta)
        private val ivCheck: ImageView = itemView.findViewById(R.id.ivRouteCheck)
        private val pillRow: View = itemView.findViewById(R.id.routeHazardPills)
        private val pillSevere: TextView = itemView.findViewById(R.id.pillSevere)
        private val pillModerate: TextView = itemView.findViewById(R.id.pillModerate)
        private val pillMinor: TextView = itemView.findViewById(R.id.pillMinor)
        private val pillStatus: TextView = itemView.findViewById(R.id.pillStatus)

        fun bind(route: NavRoute, position: Int) {
            val ctx = itemView.context
            tvLabel.text = ctx.getString(R.string.roadsense_map_route_option, position + 1)
            tvDistance.text = formatDistance(route.totalDistanceMeters)
            tvEta.text = formatEta(ctx, route.totalDurationSeconds)

            // Hazards-along-route status. Three states:
            //  - has hazards → severity count pills
            //  - mapped + 0 hazards → a muted "Clear" pill
            //  - not mapped (no RoadSense coverage in this corridor) → "Not mapped yet"
            val info = hazardCounts.getOrNull(position)
            when {
                info == null -> pillRow.visibility = View.GONE // counts still loading
                (info.severe + info.moderate + info.minor) > 0 -> {
                    pillRow.visibility = View.VISIBLE
                    bindPill(pillSevere, info.severe, R.string.roadsense_map_pill_severe)
                    bindPill(pillModerate, info.moderate, R.string.roadsense_map_pill_moderate)
                    bindPill(pillMinor, info.minor, R.string.roadsense_map_pill_minor)
                    pillStatus.visibility = View.GONE
                }
                else -> {
                    // No counts → show a single status pill (clear vs not-mapped).
                    pillRow.visibility = View.VISIBLE
                    pillSevere.visibility = View.GONE
                    pillModerate.visibility = View.GONE
                    pillMinor.visibility = View.GONE
                    pillStatus.visibility = View.VISIBLE
                    pillStatus.setText(
                        if (info.mapped) R.string.roadsense_map_route_clear
                        else R.string.roadsense_map_route_unmapped
                    )
                }
            }

            tvHint.visibility = if (position == fastestIndex) View.VISIBLE else View.GONE
            if (position == fastestIndex) {
                tvHint.text = ctx.getString(R.string.roadsense_map_fastest)
            }

            val selected = position == selectedIndex
            ivCheck.visibility = if (selected) View.VISIBLE else View.INVISIBLE

            // Selected row reads "active": primary-tinted glyph + label; the
            // others stay muted. Theme attrs so both day/night are correct.
            val primary = MaterialColors.getColor(itemView, androidx.appcompat.R.attr.colorPrimary)
            val onSurface = MaterialColors.getColor(itemView, com.google.android.material.R.attr.colorOnSurface)
            val onSurfaceVariant = MaterialColors.getColor(
                itemView, com.google.android.material.R.attr.colorOnSurfaceVariant
            )
            ivIcon.setColorFilter(if (selected) primary else onSurfaceVariant)
            tvLabel.setTextColor(if (selected) primary else onSurface)

            itemView.setOnClickListener {
                val pos = bindingAdapterPosition
                if (pos == RecyclerView.NO_POSITION) return@setOnClickListener
                if (pos != selectedIndex) {
                    val prev = selectedIndex
                    selectedIndex = pos
                    notifyItemChanged(prev)
                    notifyItemChanged(pos)
                }
                onRouteSelected(pos)
            }
        }

        /** Show a hazard count pill with "<n> <label>", or hide it when count is 0. */
        private fun bindPill(pill: TextView, count: Int, labelRes: Int) {
            if (count <= 0) { pill.visibility = View.GONE; return }
            pill.visibility = View.VISIBLE
            pill.text = pill.context.getString(labelRes, count)
        }
    }

    // Distance/ETA formatting mirrors RoadSenseMapActivity.formatDistance/formatEta
    // so the sheet rows read identically to the maneuver banner — both go through
    // the shared MapNetworking.formatDistance, which honours the Trips km/miles unit.
    private fun formatDistance(m: Double): String =
        com.overdrive.app.navmap.nav.MapNetworking.formatDistance(m)

    private fun formatEta(context: android.content.Context, seconds: Double): String {
        val mins = (seconds / 60.0).toInt()
        val hour = context.getString(R.string.unit_hour_compact)
        val unit = context.getString(R.string.unit_minute)
        return if (mins >= 60) "${mins / 60} $hour ${mins % 60} $unit" else "$mins $unit"
    }
}
