package com.overdrive.app.ui.dashboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DashboardStatusParserTest {

    @Test
    fun parsesVehicleAndChargingStatusWithoutInventingValues() {
        val result = DashboardStatusParser.parse(
            """
            {
              "status": "ok",
              "vehicleDataReady": true,
              "distanceUnit": "km",
              "soc": {"percent": 72.4},
              "range": {"totalRangeKm": 338.2, "elecRangeKm": 320.0},
              "recording": [1, 3],
              "charging": {
                "charging": true,
                "plugged": true,
                "full": false,
                "fault": false,
                "powerKw": 6.8,
                "timeToFullMin": 102,
                "sessionKwh": 12.6,
                "sessionEnergyIncomplete": false,
                "sessionEnergyEstimated": false,
                "sessionEnergySource": "metered_counter"
              }
            }
            """.trimIndent()
        )

        assertTrue(result is DashboardStatusResult.Available)
        val snapshot = (result as DashboardStatusResult.Available).snapshot
        assertEquals(72.4, snapshot.socPercent!!, 0.0)
        assertEquals(338, snapshot.range?.value)
        assertEquals(DashboardDistance.Unit.KILOMETRES, snapshot.range?.unit)
        assertEquals(2, snapshot.activeRecordingCameras)
        assertEquals(6.8, snapshot.charging?.powerKw!!, 0.0)
        assertFalse(snapshot.charging?.powerEstimated!!)
        assertEquals(102, snapshot.charging?.timeToFullMinutes)
        assertEquals(12.6, snapshot.charging?.sessionKwh!!, 0.0)
        assertFalse(snapshot.charging?.sessionEnergyIncomplete!!)
        assertFalse(snapshot.charging?.sessionEnergyEstimated!!)
        assertEquals(
            "metered_counter",
            snapshot.charging?.sessionEnergySource,
        )
    }

    @Test
    fun personalizedRangeHeadlinesWhenTheEstimatorHasData() {
        val result = DashboardStatusParser.parse(
            """
            {
              "status": "ok",
              "vehicleDataReady": true,
              "distanceUnit": "km",
              "soc": {"percent": 72.4},
              "range": {
                "totalRangeKm": 338.2,
                "elecRangeKm": 320.0,
                "fuelRangeKm": 0,
                "isPhev": false,
                "personalized": {"evKm": 287.5, "evLowKm": 260.1, "evHighKm": 315.9, "sampleCount": 14}
              }
            }
            """.trimIndent()
        ) as DashboardStatusResult.Available

        val details = result.snapshot.rangeDetails!!
        assertEquals(288, details.personalized?.value)
        assertFalse(details.isPhev)
        // HAL range untouched — still available as the fallback figure.
        assertEquals(338, result.snapshot.range?.value)
    }

    @Test
    fun rangeEndpointPopulatesPersonalizedRangeWhenStatusOmitsIt() {
        val result = DashboardStatusParser.parse(
            """
            {
              "status": "ok",
              "vehicleDataReady": true,
              "distanceUnit": "km",
              "soc": {"percent": 47},
              "range": {
                "totalRangeKm": 299,
                "elecRangeKm": 299,
                "fuelRangeKm": 0,
                "isPhev": false
              }
            }
            """.trimIndent(),
            """
            {
              "success": true,
              "range": {
                "predictedRangeKm": 152.2265,
                "lowerBoundKm": 120.1682,
                "upperBoundKm": 207.6132,
                "bucketKey": "city_hot_high",
                "sampleCount": 44,
                "builtInRangeKm": 299
              }
            }
            """.trimIndent(),
        ) as DashboardStatusResult.Available

        assertEquals(152, result.snapshot.rangeDetails?.personalized?.value)
        assertEquals(299, result.snapshot.range?.value)
    }

    @Test
    fun rangeEndpointResolvesPhevLearnedAndHalLegsIndependently() {
        val result = DashboardStatusParser.parse(
            """
            {
              "status": "ok",
              "vehicleDataReady": true,
              "distanceUnit": "km",
              "soc": {"percent": 60},
              "range": {
                "totalRangeKm": 540,
                "elecRangeKm": 90,
                "fuelRangeKm": 450,
                "fuelPercent": 62,
                "isPhev": true
              }
            }
            """.trimIndent(),
            """
            {
              "success": true,
              "range": {"predictedRangeKm": 84},
              "halFuelRangeKm": 450,
              "fuelPercent": 62
            }
            """.trimIndent(),
        ) as DashboardStatusResult.Available

        val details = result.snapshot.rangeDetails!!
        assertEquals(84, details.evLeg?.value)
        assertEquals(450, details.fuelLeg?.value)
        assertEquals(534, details.personalized?.value)
    }

    @Test
    fun phevBreakdownResolvesEachLegIndependently() {
        // Learned EV leg + unseeded fuel leg: fuel falls back to the HAL
        // figure, and the personalized headline sums the resolved legs.
        val result = DashboardStatusParser.parse(
            """
            {
              "status": "ok",
              "vehicleDataReady": true,
              "distanceUnit": "km",
              "soc": {"percent": 60},
              "range": {
                "totalRangeKm": 540.0,
                "elecRangeKm": 90.0,
                "fuelRangeKm": 450.0,
                "fuelPercent": 62.0,
                "isPhev": true,
                "personalized": {"evKm": 84.0, "evLowKm": 75.0, "evHighKm": 95.0, "sampleCount": 9}
              }
            }
            """.trimIndent()
        ) as DashboardStatusResult.Available

        val details = result.snapshot.rangeDetails!!
        assertTrue(details.isPhev)
        assertEquals(84, details.evLeg?.value)
        assertEquals(450, details.fuelLeg?.value)
        assertEquals(534, details.personalized?.value)
        assertEquals(62.0, details.fuelPercent!!, 0.0)
    }

    @Test
    fun phevWithoutLearnedDataStillExposesHalBreakdown() {
        val result = DashboardStatusParser.parse(
            """
            {
              "status": "ok",
              "vehicleDataReady": true,
              "soc": {"percent": 60},
              "range": {
                "totalRangeKm": 540.0,
                "elecRangeKm": 90.0,
                "fuelRangeKm": 450.0,
                "fuelPercent": 62.0,
                "isPhev": true
              }
            }
            """.trimIndent()
        ) as DashboardStatusResult.Available

        val details = result.snapshot.rangeDetails!!
        assertNull(details.personalized)
        assertEquals(90, details.evLeg?.value)
        assertEquals(450, details.fuelLeg?.value)
    }

    @Test
    fun bevWithoutPersonalizedDataHasNoRangeDetails() {
        val result = DashboardStatusParser.parse(
            """
            {
              "status": "ok",
              "vehicleDataReady": true,
              "soc": {"percent": 72},
              "range": {"totalRangeKm": 338.2, "elecRangeKm": 320.0, "isPhev": false}
            }
            """.trimIndent()
        ) as DashboardStatusResult.Available

        assertNull(result.snapshot.rangeDetails)
        assertEquals(338, result.snapshot.range?.value)
    }

    @Test
    fun personalizedRangeConvertsToMiles() {
        val result = DashboardStatusParser.parse(
            """
            {
              "status": "ok",
              "vehicleDataReady": true,
              "distanceUnit": "mi",
              "soc": {"percent": 50},
              "range": {
                "elecRangeKm": 160.934,
                "isPhev": false,
                "personalized": {"evKm": 160.934, "sampleCount": 5}
              }
            }
            """.trimIndent()
        ) as DashboardStatusResult.Available

        assertEquals(100, result.snapshot.rangeDetails?.personalized?.value)
        assertEquals(
            DashboardDistance.Unit.MILES,
            result.snapshot.rangeDetails?.personalized?.unit,
        )
    }

    @Test
    fun convertsPublishedKilometresWhenMilesAreRequested() {
        val result = DashboardStatusParser.parse(
            """
            {
              "status": "ok",
              "vehicleDataReady": true,
              "distanceUnit": "mi",
              "soc": {"percent": 50},
              "range": {"elecRangeKm": 160.934},
              "charging": {"charging": false, "plugged": false}
            }
            """.trimIndent()
        ) as DashboardStatusResult.Available

        assertEquals(100, result.snapshot.range?.value)
        assertEquals(DashboardDistance.Unit.MILES, result.snapshot.range?.unit)
        assertNull(result.snapshot.charging)
    }

    @Test
    fun idleChargingPayloadDoesNotProduceAChargingCard() {
        val result = DashboardStatusParser.parse(
            """
            {
              "status": "ok",
              "vehicleDataReady": true,
              "soc": {"percent": 80},
              "charging": {
                "charging": false,
                "plugged": false,
                "full": false,
                "fault": false,
                "powerKw": 0,
                "timeToFullMin": 0,
                "sessionKwh": 0
              }
            }
            """.trimIndent()
        ) as DashboardStatusResult.Available

        assertNull(result.snapshot.charging)
    }

    @Test
    fun pluggedStatusKeepsOnlyPositiveOptionalMetrics() {
        val result = DashboardStatusParser.parse(
            """
            {
              "status": "ok",
              "vehicleDataReady": true,
              "soc": {"percent": 81},
              "charging": {
                "charging": false,
                "plugged": true,
                "stateName": "Ready",
                "powerKw": -4,
                "timeToFullMin": -1,
                "sessionKwh": 0
              }
            }
            """.trimIndent()
        ) as DashboardStatusResult.Available

        val charging = result.snapshot.charging!!
        assertFalse(charging.charging)
        assertTrue(charging.plugged)
        assertEquals("Ready", charging.stateName)
        assertNull(charging.powerKw)
        assertNull(charging.timeToFullMinutes)
        assertNull(charging.sessionKwh)
    }

    @Test
    fun nominalChargingPlaceholderIsNotPresentedAsMeasured() {
        val result = DashboardStatusParser.parse(
            """
            {
              "status": "ok",
              "vehicleDataReady": true,
              "soc": {"percent": 43},
              "charging": {
                "charging": true,
                "plugged": true,
                "powerKw": 7.0,
                "chargingPowerKW": 7.0,
                "isEstimated": true,
                "powerSource": "nominalPlaceholder",
                "timeToFullMin": 120
              }
            }
            """.trimIndent()
        ) as DashboardStatusResult.Available

        val charging = result.snapshot.charging!!
        assertTrue(charging.charging)
        assertTrue(charging.plugged)
        assertNull(charging.powerKw)
        assertFalse(charging.powerEstimated)
        assertEquals(120, charging.timeToFullMinutes)
    }

    @Test
    fun dataDerivedChargingEstimateIsPresentedAsApproximate() {
        val result = DashboardStatusParser.parse(
            """
            {
              "status": "ok",
              "vehicleDataReady": true,
              "soc": {"percent": 43},
              "charging": {
                "charging": true,
                "plugged": true,
                "powerKw": 6.4,
                "isEstimated": true,
                "powerSource": "ringEstimator"
              }
            }
            """.trimIndent()
        ) as DashboardStatusResult.Available

        val charging = result.snapshot.charging!!
        assertEquals(6.4, charging.powerKw!!, 0.0)
        assertTrue(charging.powerEstimated)
    }

    @Test
    fun reconstructedSessionEnergyIsPresentedAsApproximate() {
        val result = DashboardStatusParser.parse(
            """
            {
              "status": "ok",
              "vehicleDataReady": true,
              "soc": {"percent": 43},
              "charging": {
                "charging": true,
                "plugged": true,
                "sessionKwh": 3.6,
                "sessionEnergyIncomplete": false,
                "sessionEnergyEstimated": false,
                "sessionEnergySource": "integrated_rate"
              }
            }
            """.trimIndent()
        ) as DashboardStatusResult.Available

        val charging = result.snapshot.charging!!
        assertEquals(3.6, charging.sessionKwh!!, 0.0)
        assertFalse(charging.sessionEnergyIncomplete)
        assertTrue(charging.sessionEnergyEstimated)
        assertEquals("integrated_rate", charging.sessionEnergySource)
    }

    @Test
    fun sessionEnergyWithoutProvenanceIsConservativelyApproximate() {
        val result = DashboardStatusParser.parse(
            """
            {
              "status": "ok",
              "vehicleDataReady": true,
              "charging": {
                "charging": true,
                "plugged": true,
                "sessionKwh": 2.1
              }
            }
            """.trimIndent()
        ) as DashboardStatusResult.Available

        val charging = result.snapshot.charging!!
        assertEquals(2.1, charging.sessionKwh!!, 0.0)
        assertTrue(charging.sessionEnergyEstimated)
        assertNull(charging.sessionEnergySource)
    }

    @Test
    fun missingRealVehicleValuesIsExplicitlyUnavailable() {
        val result = DashboardStatusParser.parse(
            """{"status":"ok","vehicleDataReady":true,"charging":{"charging":false}}"""
        )

        assertEquals(
            DashboardStatusResult.Unavailable(
                DashboardStatusResult.Reason.VEHICLE_DATA_UNAVAILABLE
            ),
            result,
        )
    }

    @Test
    fun vehicleNotReadySettlesAsUnavailable() {
        assertEquals(
            DashboardStatusResult.Unavailable(
                DashboardStatusResult.Reason.VEHICLE_DATA_UNAVAILABLE
            ),
            DashboardStatusParser.parse(
                """{"status":"ok","vehicleDataReady":false,"recording":[]}"""
            ),
        )
    }

    @Test
    fun malformedAndErrorResponsesHaveDistinctUnavailableReasons() {
        assertEquals(
            DashboardStatusResult.Unavailable(
                DashboardStatusResult.Reason.MALFORMED_RESPONSE
            ),
            DashboardStatusParser.parse("not-json"),
        )
        assertEquals(
            DashboardStatusResult.Unavailable(
                DashboardStatusResult.Reason.SERVICE_UNAVAILABLE
            ),
            DashboardStatusParser.parse("""{"status":"error"}"""),
        )
    }
}
