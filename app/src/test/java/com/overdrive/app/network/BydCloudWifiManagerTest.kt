package com.overdrive.app.network

import org.junit.Assert.assertEquals
import org.junit.Test

class BydCloudWifiManagerTest {

    @Test
    fun parsesAndroidAndBydApnStatusLines() {
        val parsed = BydCloudWifiManager.parseStatusOutput(
            "mobile_data_always_on=1\nmobile_data=1\napn3_state=connected\napn3_iface=rmnet_data3\n"
        )

        assertEquals("1", parsed["mobile_data_always_on"])
        assertEquals("1", parsed["mobile_data"])
        assertEquals("connected", parsed["apn3_state"])
        assertEquals("rmnet_data3", parsed["apn3_iface"])
    }

    @Test
    fun preservesDisconnectedStateAndUnknownValues() {
        val parsed = BydCloudWifiManager.parseStatusOutput(
            "mobile_data_always_on=null\nmobile_data=0\napn3_state=disconnected\napn3_iface=\n"
        )

        assertEquals("null", parsed["mobile_data_always_on"])
        assertEquals("0", parsed["mobile_data"])
        assertEquals("disconnected", parsed["apn3_state"])
        assertEquals("unknown", parsed["apn3_iface"])
    }
}
