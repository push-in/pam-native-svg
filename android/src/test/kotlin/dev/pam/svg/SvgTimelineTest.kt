package dev.pam.svg

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SvgTimelineTest {
    private fun spec(
        to: Float = 1f, from: Float? = null, duration: Long = 100, easing: Int = SvgEasing.LINEAR,
        delay: Long = 0, repeat: Int = 0, reverse: Boolean = false,
    ) = SvgAnimationSpec("n", SvgProperty.TRIM_END, to, from, duration, easing, delay, repeat, reverse, false, null, null)

    @Test fun linearTrackInterpolatesAndSettles() {
        val track = SvgTrack(spec(), 0f, 1_000)
        assertEquals(0f, track.value(1_000), 0f)
        assertEquals(0.5f, track.value(1_050), 1e-5f)
        assertFalse(track.finished(1_099))
        assertTrue(track.finished(1_100))
        assertEquals(1f, track.value(5_000), 0f)
    }

    @Test fun delayHoldsTheStartValue() {
        val track = SvgTrack(spec(delay = 50), 0.2f, 0)
        assertEquals(0.2f, track.value(40), 0f)
        assertEquals(0.6f, track.value(100), 1e-5f)
        assertTrue(track.finished(150))
    }

    @Test fun repeatWithReverseEndsAtStartForEvenIterations() {
        val track = SvgTrack(spec(repeat = 1, reverse = true), 0f, 0)
        assertEquals(0.75f, track.value(125), 1e-5f)
        assertTrue(track.finished(200))
        assertEquals(0f, track.endValue(), 0f)
    }

    @Test fun infiniteTracksNeverFinishAndRestartFromStart() {
        val track = SvgTrack(spec(to = 360f, from = 0f, repeat = -1), 0f, 0)
        assertFalse(track.finished(1_000_000))
        assertEquals(90f, track.value(10_025), 1e-3f)
    }

    @Test fun easingCurvesMatchCoreAnimationControlPoints() {
        assertEquals(0.5f, SvgEasing.apply(SvgEasing.EASE_IN_OUT, 0.5f), 1e-3f)
        assertTrue(SvgEasing.apply(SvgEasing.EASE_IN, 0.5f) < 0.5f)
        assertTrue(SvgEasing.apply(SvgEasing.EASE_OUT, 0.5f) > 0.5f)
        assertEquals(1f, SvgEasing.apply(SvgEasing.EASE_OUT, 1f), 0f)
    }

    @Test fun decodesAnimationSpecsFromPhpJson() {
        val specs = SvgAnimationSpec.parseAll(
            """[{"i":"pam-progress","p":4,"t":0.75,"d":200,"e":3},{"i":"spin","p":5,"t":360.0,"d":900,"e":1,"f":0.0,"r":-1,"o":[12.0,12.0]},{"i":"bad","p":99,"t":1}]""",
        )
        assertEquals(2, specs.size)
        assertEquals("pam-progress:4", specs[0].key)
        assertEquals(0.75f, specs[0].to, 0f)
        assertNull(specs[0].from)
        assertEquals(-1, specs[1].repeat)
        assertEquals(12f, specs[1].originX!!, 0f)
        assertTrue(SvgAnimationSpec.parseAll("not json").isEmpty())
    }
}
