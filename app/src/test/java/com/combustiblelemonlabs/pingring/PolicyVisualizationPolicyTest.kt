package com.combustiblelemonlabs.pingring

import com.combustiblelemonlabs.pingring.data.ConnectionMethodPolicy
import com.combustiblelemonlabs.pingring.data.PolicyVisualizationPolicy
import com.combustiblelemonlabs.pingring.data.RadioPowerPolicy
import com.combustiblelemonlabs.pingring.data.BeaconAlertSettings
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
