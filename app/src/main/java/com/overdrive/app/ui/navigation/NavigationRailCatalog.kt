package com.overdrive.app.ui.navigation

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import com.overdrive.app.R

data class NavigationRailOption(
    val key: String,
    @StringRes val labelRes: Int,
    @DrawableRes val iconRes: Int,
)

/**
 * Stable user-facing navigation keys. Resource and destination ids are not
 * persisted because they can change between builds.
 */
object NavigationRailCatalog {
    const val ASSISTANT = "assistant"
    const val LIVE = "live"
    const val RECORDINGS = "recordings"
    const val PARKING = "parking"
    const val VEHICLE = "vehicle"
    const val PROXIMITY = "proximity"
    const val SEAT_POSITIONS = "seat_positions"
    const val PROJECTION = "projection"
    const val TRIPS = "trips"
    const val CHARGING = "charging"
    const val AUTOMATIONS = "automations"
    const val KEY_MAPPING = "key_mapping"
    const val INTEGRATIONS = "integrations"
    const val ROAD_SENSE = "road_sense"
    const val MAP = "map"
    const val WIDGETS = "widgets"
    const val NOTIFICATIONS = "notifications"
    const val NETWORK = "network"
    const val DIAGNOSTICS = "diagnostics"

    val customizableOptions: List<NavigationRailOption> = listOf(
        NavigationRailOption(ASSISTANT, R.string.rail_assistant, R.drawable.ic_smart_toy),
        NavigationRailOption(LIVE, R.string.rail_live, R.drawable.ic_live),
        NavigationRailOption(RECORDINGS, R.string.rail_recordings, R.drawable.ic_recording),
        // Parking Intelligence. A new catalog key is shown by default even on
        // installs that already customised the rail (NavigationVisibilityPolicy).
        NavigationRailOption(PARKING, R.string.rail_parking, R.drawable.ic_parking),
        NavigationRailOption(VEHICLE, R.string.rail_vehicle, R.drawable.ic_vehicle_control),
        NavigationRailOption(PROXIMITY, R.string.nav_page_remote_access, R.drawable.ic_overlay_mode_proximity),
        NavigationRailOption(SEAT_POSITIONS, R.string.rail_seat_positions, R.drawable.ic_seat_positions),
        NavigationRailOption(PROJECTION, R.string.rail_projection, R.drawable.ic_projection),
        NavigationRailOption(TRIPS, R.string.rail_trips, R.drawable.ic_trips),
        NavigationRailOption(CHARGING, R.string.rail_charging, R.drawable.ic_charging),
        NavigationRailOption(AUTOMATIONS, R.string.rail_automations, R.drawable.ic_automations),
        NavigationRailOption(KEY_MAPPING, R.string.rail_key_mapping, R.drawable.ic_key_mapping),
        NavigationRailOption(INTEGRATIONS, R.string.rail_integrations, R.drawable.ic_integrations),
        NavigationRailOption(ROAD_SENSE, R.string.rail_roadsense, R.drawable.ic_roadsense),
        NavigationRailOption(MAP, R.string.rail_hazard_map, R.drawable.ic_roadsense_map),
        NavigationRailOption(WIDGETS, R.string.rail_widgets, R.drawable.ic_widgets),
        NavigationRailOption(NOTIFICATIONS, R.string.rail_notifications, R.drawable.ic_notifications),
        NavigationRailOption(NETWORK, R.string.rail_network, R.drawable.ic_hotspot),
        NavigationRailOption(DIAGNOSTICS, R.string.rail_diagnostics, R.drawable.ic_diagnostics),
    )

    val customizableKeys: Set<String> = customizableOptions.mapTo(linkedSetOf()) { it.key }
}
