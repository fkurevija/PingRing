package com.fkurevija.pingring

import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.test.platform.app.InstrumentationRegistry
import com.fkurevija.pingring.data.radar.RadarPeerUpdate
import com.fkurevija.pingring.data.ranging.RangingSourcePolicy
import com.fkurevija.pingring.data.ranging.EstimatedRadarDirection
import com.fkurevija.pingring.ui.screens.RadarContent
import org.junit.Rule
import org.junit.Test

class RadarHeadingInstrumentedTest {
    @get:Rule
    val composeRule = PausedClockComposeContentTestRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val peer = RadarPeerUpdate(
        peerId = "guest",
        name = "Guest",
        rssi = -60,
        proximity = "Hot",
        distance = 1.5f,
        azimuth = null,
        elevation = null,
        worldDirectionX = null,
        worldDirectionY = null,
        worldDirectionZ = null,
        lastSeen = 0L,
    )

    private fun showPeer(value: RadarPeerUpdate?, accessibility: Boolean = false) {
        composeRule.setScreen {
            RadarContent(
                peer = value,
                hasPeers = value != null,
                beacon = null,
                radarScreenReaderSummaryEnabled = accessibility,
                reduceMotion = true,
                spokenUpdateText = null,
                modifier = Modifier,
            )
        }
    }

    private fun assertHeading(status: String) {
        composeRule.onNodeWithText("Guest").assertIsDisplayed()
        composeRule.onNodeWithText(status).assertIsDisplayed()
    }

    @Test
    fun distanceIsInTheNameHeadingAndBleModeIsAbsent() {
        showPeer(peer)
        assertHeading(context.getString(R.string.distance_away, 1.5f))
        composeRule.onNodeWithText(context.getString(R.string.ble_proximity_mode)).assertDoesNotExist()
    }

    @Test
    fun directionalPeerKeepsDistanceWithoutAModeLabel() {
        showPeer(peer.copy(azimuth = 45f))
        assertHeading(context.getString(R.string.distance_away, 1.5f))
        composeRule.onNodeWithText(context.getString(R.string.direction_locked, 45)).assertDoesNotExist()
    }

    @Test
    fun gpsHeadingPreservesEstimateAndUncertaintyWithoutAModeLabel() {
        showPeer(peer.copy(distanceSource = RangingSourcePolicy.RangingSource.GNSS, distanceAccuracyMeters = 10f))
        assertHeading(context.getString(R.string.distance_with_uncertainty, 1.5f, 10f))
        composeRule.onNodeWithText(context.getString(R.string.gnss_distance_estimate)).assertDoesNotExist()
    }

    @Test
    fun wifiRttPeerHasNoConnectionModeLabel() {
        showPeer(peer.copy(distanceSource = RangingSourcePolicy.RangingSource.WIFI_RTT))
        assertHeading(context.getString(R.string.distance_away, 1.5f))
        composeRule.onNodeWithText(context.getString(R.string.wifi_rtt_distance_mode)).assertDoesNotExist()
    }

    @Test
    fun unavailableDistanceUsesSignalStatusInTheHeading() {
        showPeer(peer.copy(distance = null, meshOnlyPresence = true))
        assertHeading(context.getString(R.string.proximity_signal, context.getString(R.string.proximity_hot)))
        composeRule.onNodeWithText(context.getString(R.string.mesh_presence_no_range)).assertDoesNotExist()
    }

    @Test
    fun noTrackedPeerHasNoHeadingOrMode() {
        showPeer(null)
        composeRule.onNodeWithText("Guest", substring = true).assertDoesNotExist()
        composeRule.onNodeWithText(context.getString(R.string.ble_proximity_mode)).assertDoesNotExist()
    }

    @Test
    fun supplementalGpsDirectionIsLabelledAndSpokenWithoutChangingDistance() {
        showPeer(peer.copy(estimatedDirection = EstimatedRadarDirection(90f, 25f)), accessibility = true)
        assertHeading(context.getString(R.string.distance_away, 1.5f))
        composeRule.onNodeWithText(context.getString(R.string.gps_direction_estimate, 25)).assertIsDisplayed()
        composeRule.onNodeWithContentDescription(
            context.getString(R.string.radar_gps_direction, 90, 25), substring = true,
        ).assertIsDisplayed()
    }

    @Test
    fun aMeasuredBearingDoesNotShowAGpsEstimateLabel() {
        showPeer(peer.copy(azimuth = 45f, estimatedDirection = EstimatedRadarDirection(90f, 25f)))
        composeRule.onNodeWithText(context.getString(R.string.gps_direction_estimate, 25)).assertDoesNotExist()
        assertHeading(context.getString(R.string.distance_away, 1.5f))
    }
}
