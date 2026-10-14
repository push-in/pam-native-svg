package dev.pam.svg

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class SvgParserTest {
    private fun ops(d: String) = PathDataParser.parse(d).ops.toList()

    @Test fun parsesAbsoluteAndRelativeCommandsWithImplicitRepeats() {
        assertEquals(listOf(0f, 1f, 2f, 1f, 3f, 4f, 1f, 5f, 6f), ops("M1 2 3 4 5 6"))
        assertEquals(listOf(0f, 10f, 10f, 1f, 15f, 10f, 1f, 15f, 20f, 4f), ops("m10,10h5v10z"))
        assertEquals(listOf(0f, 1f, 1f, 1f, 2f, 3f), ops("M1 1l1 2"))
    }

    @Test fun parsesCompactNumbersAndExponents() {
        assertEquals(listOf(0f, 1.5f, 0.5f, 1f, -1f, -2f, 1f, 0.001f, 2f), ops("M1.5.5L-1-2 1e-3 2"))
    }

    @Test fun convertsArcsWithPackedFlagsToCubics() {
        val path = PathDataParser.parse("M0 0a5 5 0 105 5")
        assertTrue(path.ops.any { it == PathData.CUBIC })
        val last = path.ops.takeLast(2)
        assertEquals(5f, last[0], 1e-3f)
        assertEquals(5f, last[1], 1e-3f)
    }

    @Test fun smoothCurvesReflectPreviousControlPoint() {
        val path = PathDataParser.parse("M0 0C0 10 10 10 10 0S20 -10 20 0")
        // second cubic: first control = reflection of (10,10) around (10,0) = (10,-10)
        val ops = path.ops
        val second = ops.indexOfLast { it == PathData.CUBIC }
        assertEquals(10f, ops[second + 1], 1e-4f)
        assertEquals(-10f, ops[second + 2], 1e-4f)
    }

    @Test fun circleStartsAtThreeOclockAndRunsClockwise() {
        val doc = SvgParser.parse("""<svg viewBox="0 0 24 24"><circle id="c" cx="12" cy="12" r="10"/></svg>""")
        val shape = doc.byId["c"] as SvgShape
        assertEquals(22f, shape.path.ops[1], 1e-4f)
        assertEquals(12f, shape.path.ops[2], 1e-4f)
        // first segment ends at (12, 22): bottom of the circle in y-down space
        assertEquals(12f, shape.path.ops[8], 1e-4f)
        assertEquals(22f, shape.path.ops[9], 1e-4f)
        assertEquals((2 * Math.PI * 10).toFloat(), shape.path.length, 0.05f)
    }

    @Test fun pathLengthScalesDashes() {
        val doc = SvgParser.parse("""<svg viewBox="0 0 100 10"><line id="l" x1="0" y1="5" x2="50" y2="5" pathLength="100" stroke="#000" stroke-dasharray="10 5"/></svg>""")
        val line = doc.byId["l"] as SvgShape
        assertEquals(0.5f, line.dashScale, 1e-4f)
        assertArrayEquals(floatArrayOf(10f, 5f), line.style.dashes, 0f)
    }

    @Test fun oddDashArraysAreRepeated() {
        val doc = SvgParser.parse("""<svg><path id="p" d="M0 0L10 0" stroke-dasharray="1,2,3"/></svg>""")
        assertArrayEquals(floatArrayOf(1f, 2f, 3f, 1f, 2f, 3f), (doc.byId["p"] as SvgShape).style.dashes, 0f)
    }

    @Test fun inheritsPresentationAndAppliesCascade() {
        val doc = SvgParser.parse(
            """<svg viewBox="0 0 10 10"><style>.a{fill:#00ff00} #b{stroke:red}</style>
            <g fill="#ff0000" stroke-width="3"><rect id="r" width="5" height="5"/><rect id="b" class="a" width="5" height="5" style="stroke-width:7"/></g></svg>""",
        )
        val r = doc.byId["r"] as SvgShape
        val b = doc.byId["b"] as SvgShape
        assertEquals(SvgPaint.Solid(0xFFFF0000.toInt()), r.style.fill)
        assertEquals(3f, r.style.strokeWidth)
        assertEquals(SvgPaint.Solid(0xFF00FF00.toInt()), b.style.fill)
        assertEquals(SvgPaint.Solid(0xFFFF0000.toInt()), b.style.stroke)
        assertEquals(7f, b.style.strokeWidth)
    }

    @Test fun parsesColors() {
        assertEquals(0xFFFFFFFF.toInt(), SvgColor.argb("#fff"))
        assertEquals(0x80112233.toInt(), SvgColor.argb("#11223380"))
        assertEquals(0x80FF0000.toInt(), SvgColor.argb("rgba(255, 0, 0, 0.5)"))
        assertEquals(0xFF000080.toInt(), SvgColor.argb("navy"))
        assertEquals(SvgPaint.Current, SvgColor.parse("currentColor"))
        assertEquals(SvgPaint.None, SvgColor.parse("none"))
        assertNull(SvgColor.parse("#12"))
    }

    @Test fun composesTransformLists() {
        val m = SvgParser.transform("translate(10 20) scale(2)")!!
        assertArrayEquals(floatArrayOf(2f, 0f, 0f, 2f, 10f, 20f), m, 1e-5f)
        val r = SvgParser.transform("rotate(90 5 5)")!!
        // (10, 5) rotates to (5, 10) around (5, 5)
        assertEquals(5f, r[0] * 10 + r[2] * 5 + r[4], 1e-4f)
        assertEquals(10f, r[1] * 10 + r[3] * 5 + r[5], 1e-4f)
    }

    @Test fun resolvesGradientsThroughHrefAndPercentages() {
        val doc = SvgParser.parse(
            """<svg><defs><linearGradient id="base"><stop offset="0%" stop-color="#000"/><stop offset="100%" stop-color="#fff" stop-opacity="0.5"/></linearGradient>
            <linearGradient id="g" href="#base" x1="0" y1="0" x2="0" y2="100%" gradientUnits="objectBoundingBox"/>
            <radialGradient id="r" xlink:href="#base" cx="50%" r="25%"/></defs><rect width="2" height="2" fill="url(#g)"/></svg>""",
        )
        val g = doc.gradients["g"]!!
        assertEquals(2, g.stops.size)
        assertEquals(0x80FFFFFF.toInt(), g.stops[1].argb)
        assertArrayEquals(floatArrayOf(0f, 0f, 0f, 1f), g.coordinates, 0f)
        val r = doc.gradients["r"]!!
        assertTrue(r.radial)
        assertEquals(0.25f, r.coordinates[2], 1e-5f)
        assertTrue(doc.root.children.single() is SvgShape)
    }

    @Test fun expandsUseAndSymbolButSkipsDefs() {
        val doc = SvgParser.parse(
            """<svg><defs><path id="p" d="M0 0L1 1"/></defs><symbol id="s"><circle r="2"/></symbol><use href="#p" x="3"/><use xlink:href="#s"/></svg>""",
        )
        assertEquals(2, doc.root.children.size)
        val first = doc.root.children[0] as SvgGroup
        assertEquals(3f, first.transform!![4])
    }

    @Test fun clipPathsAreCollected() {
        val doc = SvgParser.parse("""<svg><clipPath id="c"><circle cx="5" cy="5" r="5"/></clipPath><rect width="10" height="10" clip-path="url(#c)"/></svg>""")
        assertEquals(1, doc.clips["c"]!!.shapes.size)
        assertEquals("c", doc.root.children.single().clipId)
    }

    @Test fun rejectsDoctypeAndMalformedMarkup() {
        for (bad in listOf("""<!DOCTYPE svg [<!ENTITY x "y">]><svg/>""", "<svg><g></svg>", "<g/>", "")) {
            try { SvgParser.parse(bad); fail("accepted $bad") } catch (_: IllegalArgumentException) {}
        }
    }

    @Test fun decodesEntitiesAndIgnoresCommentsAndProlog() {
        val doc = SvgParser.parse("""<?xml version="1.0"?><!-- c --><svg viewBox="0 0 1 1"><path id="a&amp;b" d="M0 0L1 1"/></svg>""")
        assertNotNull(doc.byId["a&b"])
    }

    @Test fun fitsViewBoxLikePreserveAspectRatio() {
        val doc = SvgParser.parse("""<svg viewBox="0 0 10 20"><rect width="1" height="1"/></svg>""")
        assertArrayEquals(floatArrayOf(5f, 5f, 25f, 0f), doc.fit(100f, 100f, null), 1e-4f)
        assertArrayEquals(floatArrayOf(10f, 10f, 0f, -50f), doc.fit(100f, 100f, SvgFit.COVER), 1e-4f)
        assertArrayEquals(floatArrayOf(10f, 5f, 0f, 0f), doc.fit(100f, 100f, SvgFit.STRETCH), 1e-4f)
        val min = SvgParser.parse("""<svg viewBox="0 0 10 20" preserveAspectRatio="xMinYMin meet"><rect width="1" height="1"/></svg>""")
        assertArrayEquals(floatArrayOf(5f, 5f, 0f, 0f), min.fit(100f, 100f, null), 1e-4f)
    }

    @Test fun parsesPamTrimAttributes() {
        val doc = SvgParser.parse("""<svg><circle id="c" cx="5" cy="5" r="4" pam-trim-start="0.1" pam-trim-end="0.6"/></svg>""")
        val c = doc.byId["c"] as SvgShape
        assertEquals(0.1f, c.trimStart, 1e-6f)
        assertEquals(0.6f, c.trimEnd, 1e-6f)
    }

    @Test fun cacheParsesOncePerKeyAndEvictsLeastRecentlyUsed() {
        val cache = SvgDocumentCache(maxEntries = 2)
        val a = cache.obtain("a", "<svg><rect width='1' height='1'/></svg>")
        assertSame(a, cache.obtain("a", "<svg>ignored</svg>"))
        cache.obtain("b", "<svg/>")
        cache.get("a")
        cache.obtain("c", "<svg/>")
        assertNotNull(cache.get("a"))
        assertNull(cache.get("b"))
        cache.trim(0f)
        assertEquals(0, cache.size())
        assertEquals(0, cache.bytes())
    }

    @Test fun parsesPhpBuiltProgressRingFixture() {
        val source = java.io.File("../tests/fixtures/progress-ring.svg").readText()
        val doc = SvgParser.parse(source)
        val arc = doc.byId["pam-progress"] as SvgShape
        assertEquals(0f, arc.trimEnd, 0f)
        assertEquals(SvgPaint.None, arc.style.fill)
        assertEquals(2, arc.style.lineCap)
        assertNotNull(arc.transform)
        assertTrue(doc.byId["pam-spinner"] is SvgGroup)
        val track = doc.root.children[0] as SvgShape
        assertEquals(0x40FFFFFF, track.style.stroke.let { (it as SvgPaint.Solid).argb })
        val spinner = SvgParser.parse(java.io.File("../tests/fixtures/spinner.svg").readText())
        assertEquals(0.25f, (spinner.byId["pam-progress"] as SvgShape).trimEnd, 1e-6f)
    }
}
