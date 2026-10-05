package com.fkurevija.pingring

import com.fkurevija.pingring.data.BeaconAlertPolicy
import com.fkurevija.pingring.data.BeaconAlertRepeat
import com.fkurevija.pingring.data.BeaconAlertSettings
import com.fkurevija.pingring.data.BeaconVibrationPattern
import com.fkurevija.pingring.data.DEFAULT_BEACON_REPEAT_GAP_MILLIS
import com.fkurevija.pingring.data.MAX_BEACON_REPEAT_GAP_MILLIS
import com.fkurevija.pingring.data.MIN_BEACON_REPEAT_GAP_MILLIS
import com.fkurevija.pingring.data.normalizeBeaconRepeatGapMillis
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * How a beacon's pattern and the member's own alert settings become a waveform.
 *
 * The failure this guards against is a member who asked for a quiet phone and
 * got one that will not stop buzzing.
 */
class BeaconAlertPolicyTest {

    private val steady = BeaconVibrationPattern.STEADY_PULSE.pattern

    private fun settings(
        repeat: BeaconAlertRepeat,
        gap: Long = DEFAULT_BEACON_REPEAT_GAP_MILLIS,
    ) = BeaconAlertSettings(repeat = repeat, repeatGapMillis = gap)

    @Test
    fun `the default is one alert and nothing else`() {
        // Every install starts here. Anyone upgrading gets exactly the behaviour
        // they had, which is the only safe default for an alerting feature.
        assertEquals(BeaconAlertRepeat.ONCE, BeaconAlertSettings.DEFAULT.repeat)
        assertEquals(
            BeaconAlertPolicy.NO_REPEAT,
            BeaconAlertPolicy.waveformFor(steady, BeaconAlertSettings.DEFAULT)?.repeatFromIndex,
        )
    }

    @Test
    fun `playing once leaves the pattern untouched`() {
        val wave = BeaconAlertPolicy.waveformFor(steady, settings(BeaconAlertRepeat.ONCE))

        assertArrayEquals(steady, wave?.timings)
        assertEquals(BeaconAlertPolicy.NO_REPEAT, wave?.repeatFromIndex)
    }

    @Test
    fun `repeating loops the pattern with the gap at the end`() {
        // The gap has to be the LAST entry so the loop reads pattern-then-pause.
        // Putting it first would buzz immediately on every repeat with no pause,
        // which is a different and much worse sound.
        val gap = 7_000L
        val wave = BeaconAlertPolicy.waveformFor(steady, settings(BeaconAlertRepeat.REPEAT, gap))

        val expected = steady.copyOf(steady.size + 1).also { it[steady.size] = gap }
        assertArrayEquals(expected, wave?.timings)
        assertEquals(0, wave?.repeatFromIndex)
    }

    @Test
    fun `no vibration stays silent however loudly the member asked to repeat`() {
        // The one case where the repeat setting is ignored. Repeating silence on
        // a timer would wake the device for somebody who already said they did
        // not want to be buzzed.
        assertNull(
            BeaconAlertPolicy.waveformFor(
                BeaconVibrationPattern.SILENT.pattern,
                settings(BeaconAlertRepeat.REPEAT),
            )
        )
    }

    @Test
    fun `a malformed pattern is treated as silence rather than buzzing`() {
        // An empty or all-zero array is what an older or corrupted payload can
        // carry. Wrong in this direction means staying quiet; wrong the other way
        // means buzzing a member who asked for no vibration.
        listOf(longArrayOf(), longArrayOf(0L), longArrayOf(0L, 0L, 0L)).forEach { pattern ->
            assertNull(
                "expected silence for ${pattern.toList()}",
                BeaconAlertPolicy.waveformFor(pattern, settings(BeaconAlertRepeat.REPEAT)),
            )
        }
    }

    @Test
    fun `a gap outside the allowed range is pulled back into it`() {
        assertEquals(MIN_BEACON_REPEAT_GAP_MILLIS, normalizeBeaconRepeatGapMillis(0L))
        assertEquals(MIN_BEACON_REPEAT_GAP_MILLIS, normalizeBeaconRepeatGapMillis(-9_000L))
        assertEquals(MAX_BEACON_REPEAT_GAP_MILLIS, normalizeBeaconRepeatGapMillis(999_999L))
        assertEquals(12_000L, normalizeBeaconRepeatGapMillis(12_000L))
    }
    @Test
    fun `an out of range gap never reaches the waveform`() {
        // Normalisation has to happen on the way to playback, not only when the
        // setting is saved: the value can arrive from a restored backup.
        val wave = BeaconAlertPolicy.waveformFor(
            steady,
            settings(BeaconAlertRepeat.REPEAT, gap = Long.MAX_VALUE),
        )

        assertEquals(MAX_BEACON_REPEAT_GAP_MILLIS, wave?.timings?.last())
    }

    @Test
    fun `every rhythm in the palette survives both settings`() {
        // The sweep that matters: no rhythm may produce a waveform that plays
        // nothing, loops from outside its own timings, or loses its buzz.
        BeaconVibrationPattern.entries.forEach { pattern ->
            BeaconAlertRepeat.entries.forEach { repeat ->
                val wave = BeaconAlertPolicy.waveformFor(pattern.pattern, settings(repeat))
                if (pattern == BeaconVibrationPattern.SILENT) {
                    assertNull("${pattern.name} with $repeat must stay silent", wave)
                    return@forEach
                }
                assertTrue("${pattern.name} with $repeat produced no waveform", wave != null)
                assertTrue(
                    "${pattern.name} with $repeat plays no buzz at all",
                    wave!!.timings.any { it > 0L },
                )
                // Only a repeating waveform has to point its loop at a real entry.
                // "Once" deliberately carries NO_REPEAT (-1), which is the value
                // the platform reads as "do not loop", so it must stay outside the
                // timings by construction rather than by clamping.
                if (repeat == BeaconAlertRepeat.REPEAT) {
                    assertEquals(
                        "${pattern.name} with $repeat must loop from the top",
                        0,
                        wave.repeatFromIndex,
                    )
                } else {
                    assertEquals(
                        "${pattern.name} with $repeat must not loop",
                        BeaconAlertPolicy.NO_REPEAT,
                        wave.repeatFromIndex,
                    )
                }
            }
        }
    }

    @Test
    fun `the waveform never aliases the pattern it was given`() {
        // Appending the gap in place would corrupt the enum's shared pattern
        // array, and the second beacon raised in a session would inherit the
        // first one's gap.
        val before = steady.copyOf()
        BeaconAlertPolicy.waveformFor(steady, settings(BeaconAlertRepeat.REPEAT, 9_000L))

        assertArrayEquals(before, steady)
        assertArrayEquals(
            BeaconVibrationPattern.STEADY_PULSE.pattern,
            steady,
        )
    }

    @Test
    fun `a zero gap in the settings is not mistaken for no repeat`() {
        // 0 ms is coerced up to the minimum rather than being honoured: a
        // zero-length loop is a buzz with no pause, which reads as one
        // continuous vibration and is not what anybody choosing a gap asked for.
        val wave = BeaconAlertPolicy.waveformFor(
            steady,
            settings(BeaconAlertRepeat.REPEAT, gap = 0L),
        )

        assertEquals(MIN_BEACON_REPEAT_GAP_MILLIS, wave?.timings?.last())
        assertEquals(0, wave?.repeatFromIndex)
    }
}