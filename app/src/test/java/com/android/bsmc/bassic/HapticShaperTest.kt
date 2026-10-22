package com.android.bsmc.bassic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HapticShaperTest {

    @Test
    fun envelopeStartsSolidThenFades() {
        val env = HapticShaper.envelope(strength = 1.0, flow = 0.6)
        assertEquals(1.0, env[0], 0.0)
        val tail = env.drop(4)
        assertTrue(tail.zipWithNext().all { (a, b) -> b < a })
        assertTrue(tail.last() < 0.1)
    }

    @Test
    fun harderHitsAndMoreFlowLastLonger() {
        assertTrue(HapticShaper.envelope(1.0, 0.5).size > HapticShaper.envelope(0.2, 0.5).size)
        assertTrue(HapticShaper.envelope(0.5, 1.0).size > HapticShaper.envelope(0.5, 0.0).size)
    }

    @Test
    fun harderHitsPushLonger() {
        assertEquals(10L, HapticShaper.pulseMillis(0.0))
        assertEquals(50L, HapticShaper.pulseMillis(1.0))
        assertTrue(HapticShaper.pulseMillis(0.7) > HapticShaper.pulseMillis(0.3))
    }

    @Test
    fun sustainHoldsWhileBassStaysAndMoreFlowHoldsLonger() {
        assertEquals(0L, HapticShaper.sustainCapMillis(0.0))
        assertTrue(HapticShaper.sustainCapMillis(1.0) > HapticShaper.sustainCapMillis(0.5))
        // A held note just under the hit level keeps the motor on...
        assertTrue(HapticShaper.shouldSustain(energy = 90.0, hitEnergy = 100.0, avgEnergy = 40.0, flow = 0.5))
        // ...a note that has died away does not.
        assertTrue(!HapticShaper.shouldSustain(energy = 30.0, hitEnergy = 100.0, avgEnergy = 40.0, flow = 0.5))
        // More flow tolerates a deeper dip.
        assertTrue(HapticShaper.shouldSustain(energy = 55.0, hitEnergy = 100.0, avgEnergy = 40.0, flow = 1.0))
        assertTrue(!HapticShaper.shouldSustain(energy = 55.0, hitEnergy = 100.0, avgEnergy = 40.0, flow = 0.0))
    }

    @Test
    fun amplitudeWaveformScalesAndTrims() {
        val env = HapticShaper.envelope(0.5, 0.3)
        val wave = HapticShaper.toAmplitudeWaveform(env, scale = 0.5)
        assertEquals(128, wave.amplitudes.first())
        assertTrue(wave.amplitudes.last() > 0)
        assertEquals(wave.timings.size, wave.amplitudes.size)
    }
}
