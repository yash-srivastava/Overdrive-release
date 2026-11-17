package com.overdrive.app.navmap.nav

import com.overdrive.app.navmap.NavMapConfig
import com.overdrive.app.navmap.google.GoogleApi
import com.overdrive.app.navmap.google.GoogleHttp
import com.overdrive.app.navmap.google.GoogleMapsBudget
import com.overdrive.app.navmap.google.GoogleMapsConfig
import com.overdrive.app.navmap.google.GoogleRoutesClient
import com.overdrive.app.navmap.google.GoogleSku

/**
 * Routing entry point: Google Routes while [googleActive], otherwise exactly [ValhallaRouteClient].
 * Routing calls are synchronous (run them off the main thread); [lastError] describes the last foreground call.
 */
object NavRouting {

    enum class Error { NONE, NO_KEY, AUTH, HTTP, TIMEOUT, NETWORK, EMPTY, GOOGLE_AUTH, GOOGLE_LIMIT }

    @Volatile
    var lastError: Error = Error.NONE

    @Volatile
    var lastHttpCode: Int = 0

    @Volatile
    var lastProvider: RouteProvider = RouteProvider.VALHALLA

    /** Text of the synthetic arrival maneuver on Google routes. */
    @Volatile
    var arrivalInstruction: String = "You have arrived at your destination"

    private const val MAX_INTERMEDIATES = 25

    @JvmStatic
    fun googleActive(): Boolean = try {
        val settings = GoogleMapsConfig.cached()
        settings.routingUsable && GoogleMapsBudget.hasRoom(settings.routesSku)
    } catch (_: Throwable) {
        false
    }

    @JvmStatic
    fun routesWithAlternates(
        startLat: Double, startLng: Double, endLat: Double, endLng: Double,
        alternates: Int, startHeading: Double? = null
    ): List<NavRoute> {
        if (!googleActive()) {
            return fromValhalla {
                ValhallaRouteClient.routesWithAlternates(startLat, startLng, endLat, endLng, alternates, startHeading)
            }
        }
        val out = GoogleRoutesClient.computeOutcome(
            GeoPoint(startLat, startLng), GeoPoint(endLat, endLng), emptyList(),
            alternates > 0, startHeading, GoogleRoutesClient.Quality.FAST, arrivalInstruction
        )
        if (out.routes.isNotEmpty()) return fromGoogle(out.routes.take(1 + alternates.coerceAtLeast(0)))
        return afterGoogleFailure(out) {
            ValhallaRouteClient.routesWithAlternates(startLat, startLng, endLat, endLng, alternates, startHeading)
        }
    }

    @JvmStatic
    @JvmOverloads
    fun routeVia(points: List<GeoPoint>, startHeading: Double? = null): List<NavRoute> {
        if (points.size < 2 || points.size - 2 > MAX_INTERMEDIATES || !googleActive()) {
            return fromValhalla { ValhallaRouteClient.routeVia(points, startHeading) }
        }
        val out = GoogleRoutesClient.computeOutcome(
            points[0], points[points.size - 1], ArrayList(points.subList(1, points.size - 1)),
            false, startHeading, GoogleRoutesClient.Quality.FAST, arrivalInstruction
        )
        if (out.routes.isNotEmpty()) return fromGoogle(out.routes.take(1))
        return afterGoogleFailure(out) { ValhallaRouteClient.routeVia(points, startHeading) }
    }

    /** Background re-query of the remaining trip ([origin, stops…, destination]); leaves [lastError] untouched. */
    @JvmStatic
    fun refreshForTraffic(points: List<GeoPoint>, startHeading: Double?): List<NavRoute> {
        if (points.size < 2 || points.size - 2 > MAX_INTERMEDIATES || !googleActive()) return emptyList()
        val intermediates = ArrayList(points.subList(1, points.size - 1))
        return GoogleRoutesClient.computeOutcome(
            points[0], points[points.size - 1], intermediates,
            intermediates.isEmpty(), startHeading, GoogleRoutesClient.Quality.BEST, arrivalInstruction
        ).routes
    }

    @JvmStatic
    fun warmUp() {
        try {
            if (googleActive()) GoogleHttp.warmUp(GoogleHttp.ROUTES_HOST)
        } catch (_: Throwable) {
        }
    }

    private inline fun fromValhalla(call: () -> List<NavRoute>): List<NavRoute> {
        val routes = call()
        lastError = when (ValhallaRouteClient.lastError) {
            ValhallaRouteClient.RouteError.NONE -> Error.NONE
            ValhallaRouteClient.RouteError.NO_KEY -> Error.NO_KEY
            ValhallaRouteClient.RouteError.AUTH -> Error.AUTH
            ValhallaRouteClient.RouteError.HTTP -> Error.HTTP
            ValhallaRouteClient.RouteError.TIMEOUT -> Error.TIMEOUT
            ValhallaRouteClient.RouteError.NETWORK -> Error.NETWORK
            ValhallaRouteClient.RouteError.EMPTY -> Error.EMPTY
        }
        lastHttpCode = ValhallaRouteClient.lastHttpCode
        lastProvider = RouteProvider.VALHALLA
        return routes
    }

    private fun fromGoogle(routes: List<NavRoute>): List<NavRoute> {
        lastError = Error.NONE
        lastHttpCode = 200
        lastProvider = RouteProvider.GOOGLE
        return routes
    }

    private inline fun afterGoogleFailure(
        out: GoogleRoutesClient.Outcome,
        valhalla: () -> List<NavRoute>
    ): List<NavRoute> {
        val fallback = try { NavMapConfig.fromUnifiedConfig().isRoutingConfigured() } catch (_: Throwable) { false }
        if (fallback) return fromValhalla(valhalla)
        lastError = when (out.error) {
            GoogleRoutesClient.Error.BUDGET, GoogleRoutesClient.Error.QUOTA -> Error.GOOGLE_LIMIT
            GoogleRoutesClient.Error.AUTH -> Error.GOOGLE_AUTH
            GoogleRoutesClient.Error.TIMEOUT -> Error.TIMEOUT
            GoogleRoutesClient.Error.NETWORK -> Error.NETWORK
            GoogleRoutesClient.Error.HTTP -> Error.HTTP
            GoogleRoutesClient.Error.EMPTY, GoogleRoutesClient.Error.NONE -> Error.EMPTY
        }
        lastHttpCode = out.httpCode
        lastProvider = RouteProvider.GOOGLE
        return emptyList()
    }
}
