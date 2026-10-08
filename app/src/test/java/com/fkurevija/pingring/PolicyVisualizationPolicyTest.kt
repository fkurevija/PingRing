package com.fkurevija.pingring

import com.fkurevija.pingring.data.ConnectionMethodPolicy
import com.fkurevija.pingring.data.PolicyVisualizationPolicy
import com.fkurevija.pingring.data.RadioPowerPolicy
import com.fkurevija.pingring.data.BeaconAlertSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PolicyVisualizationPolicyTest {

    @Test
    fun summarizeActivePolicies() {
        val summary = PolicyVisualizationPolicy.summarize(
            connectionMethod = ConnectionMethodPolicy.Method.NEARBY,
            radioPowerPlan = RadioPowerPolicy.Plan(RadioPowerPolicy.ScanEffort.ACTIVE, 2, 45_000L),
            beaconAlertSettings = BeaconAlertSettings.DEFAULT,
        )
        assertEquals("NEARBY", summary.connectionMethod)
        assertTrue(summary.exactTriggers.size >= 3)
    }
}
