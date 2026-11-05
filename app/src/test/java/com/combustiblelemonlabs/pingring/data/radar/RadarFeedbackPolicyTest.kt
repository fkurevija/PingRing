package com.combustiblelemonlabs.pingring.data.radar
import com.combustiblelemonlabs.pingring.data.radar.RadarFeedbackPolicy
import com.combustiblelemonlabs.pingring.data.ranging.RangingSourcePolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the radar says, buzzes and animates in response to a distance.
 *
 * The two failure modes pull in opposite directions: buzzing for a reading that
 * is not a real measurement, and staying silent for one that is. Both are
 * covered below.
 */
class RadarFeedbackPolicyTest {

    private fun haptics(
        enabled: Boolean = true,
        distance: Float? = 1f,
        source: RangingSourcePolicy.RangingSource? = RangingSourcePolicy.RangingSource.UWB,
    ) = RadarFeedbackPolicy.proximityHaptic(enabled, distance, source)

    @Test
    fun `close range with a real fix buzzes`() {
        assertTrue(haptics(distance = 1.0f))
    }

    @Test
    fun `haptics stay off when the member has turned them off`() {
        assertFalse(haptics(enabled = false, distance = 0.1f))
    }

    @Test
    fun `a gps fix never buzzes however close it says the member is`() {
        // A satellite fix is metres and routinely off by ten, so "under two
        // metres" from GNSS is noise, not proximity. Buzzing on it would fire
        // constantly outdoors.
        assertFalse(
            haptics(distance = 0.2f, source = RangingSourcePolicy.RangingSource.GNSS)
        )
    }

    @Test
    fun `the boundary is two metres and just past it`() {
        assertTrue(haptics(distance = 1.999f))
        assertFalse(haptics(distance = 2.0f))
        assertFalse(haptics(distance = 2.001f))
    }

    @Test
    fun `a missing or nonsensical distance never buzzes`() {
        // NaN, infinity and negatives all come out of a stale or corrupt
        // reading, and every one of them must read as "no reading" rather than
        // accidentally satisfying the comparisons.
        assertFalse(haptics(distance = null))
        assertFalse(haptics(distance = Float.NaN))
        assertFalse(haptics(distance = Float.POSITIVE_INFINITY))
        assertFalse(haptics(distance = -1f))
    }

    @Test
    fun `a reading of exactly zero metres is inside the near band`() {
        // Zero is the "standing on them" reading, and treating it as absent
        // would silently drop the strongest possible proximity.
        assertTrue(haptics(distance = 0f))
        assertEquals("near", RadarFeedbackPolicy.proximityState(0f, null, null))
    }

    @Test
    fun `a null range is still treated as a real fix`() {
        // A fix carrying no source is still a fix, so it must not fall through
        // to the "no source" path and stay silent.
        assertTrue(haptics(distance = 0.5f, source = null))
    }
    @Test
    fun `reduce motion is off only when both the app and the system want it`() {
        // The app's own switch is not enough on its own: Android's
        // disabled-animation setting is respected whatever the app says, and the
        // reverse is not true.
        assertTrue(
            RadarFeedbackPolicy.reduceMotion(appEnabled = true, systemAnimationsEnabled = true)
        )
        assertTrue(
            RadarFeedbackPolicy.reduceMotion(appEnabled = true, systemAnimationsEnabled = false)
        )
        assertTrue(
            RadarFeedbackPolicy.reduceMotion(appEnabled = false, systemAnimationsEnabled = false)
        )
        assertFalse(
            RadarFeedbackPolicy.reduceMotion(appEnabled = false, systemAnimationsEnabled = true)
        )
    }

    @Test
    fun `the spoken proximity state names the band the member is in`() {
        assertEquals("near", RadarFeedbackPolicy.proximityState(1.0f, null, null))
        assertEquals("within-ten-metres", RadarFeedbackPolicy.proximityState(5f, null, null))
        assertEquals("farther", RadarFeedbackPolicy.proximityState(50f, null, null))
    }

    @Test
    fun `the band boundaries are exclusive of the next band`() {
        assertEquals("near", RadarFeedbackPolicy.proximityState(1.9999f, null, null))
        assertEquals("within-ten-metres", RadarFeedbackPolicy.proximityState(2.0f, null, null))
        assertEquals("within-ten-metres", RadarFeedbackPolicy.proximityState(9.999f, null, null))
        assertEquals("farther", RadarFeedbackPolicy.proximityState(10.0f, null, null))
    }

    @Test
    fun `a gps fix is announced as an estimate rather than a measurement`() {
        assertEquals(
            "gps-estimate",
            RadarFeedbackPolicy.proximityState(5f, RangingSourcePolicy.RangingSource.GNSS, "near"),
        )
    }

    @Test
    fun `with no usable distance the signal state is reported instead`() {
        // A peer we can hear but cannot place is a real state, and reporting
        // "unknown" where we have a signal state would throw away the one thing
        // we do know.
        assertEquals("Cold", RadarFeedbackPolicy.proximityState(null, null, "Cold"))
        assertEquals("Cold", RadarFeedbackPolicy.proximityState(Float.NaN, null, "Cold"))
        assertEquals("Cold", RadarFeedbackPolicy.proximityState(-3f, null, "Cold"))
        assertEquals("unknown", RadarFeedbackPolicy.proximityState(null, null, null))
    }

    @Test
    fun `a measured distance beats the signal state`() {
        // Once there is a real distance the signal state is stale, and mixing
        // them would put two different claims in one spoken sentence.
        assertEquals("farther", RadarFeedbackPolicy.proximityState(100f, null, "Hot"))
    }
}