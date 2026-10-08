package com.fkurevija.pingring.data

/**
 * Combines active states of ConnectionMethodPolicy, RadioPowerPolicy, and BeaconAlertPolicy
 * for real-time policy visualization in Developer Options.
 */
object PolicyVisualizationPolicy {

    data class PolicyStateSummary(
        val connectionMethod: String,
        val radioPowerPlan: String,
        val beaconAlertSettings: String,
        val exactTriggers: List<String>,
    )

    fun summarize(
        connectionMethod: ConnectionMethodPolicy.Method?,
        radioPowerPlan: RadioPowerPolicy.Plan?,
        beaconAlertSettings: BeaconAlertSettings?,
    ): PolicyStateSummary {
        val triggers = mutableListOf<String>()
        connectionMethod?.let { triggers.add("ConnectionMethod: $it") }
        radioPowerPlan?.let { triggers.add("RadioPower: scan=${it.scanEffort}, passes=${it.scanPassCount}") }
        beaconAlertSettings?.let { triggers.add("BeaconAlert: repeat=${it.repeat}, gap=${it.repeatGapMillis}ms") }

        return PolicyStateSummary(
            connectionMethod = connectionMethod?.name ?: "UNKNOWN",
            radioPowerPlan = radioPowerPlan?.let { "Effort=${it.scanEffort}, Passes=${it.scanPassCount}" } ?: "DEFAULT",
            beaconAlertSettings = beaconAlertSettings?.let { "Repeat=${it.repeat}" } ?: "DEFAULT",
            exactTriggers = triggers,
        )
    }
}
