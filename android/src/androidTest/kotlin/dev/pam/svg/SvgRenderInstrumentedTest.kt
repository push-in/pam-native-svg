package dev.pam.svg

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.HardwareRenderer
import android.graphics.PixelFormat
import android.graphics.RenderNode
import android.hardware.HardwareBuffer
import android.media.ImageReader
import android.os.Build
import android.os.SystemClock
import android.view.View
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.pam.nativeapp.protocol.WireMap
import dev.pam.nativeapp.protocol.WireValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.abs

@RunWith(AndroidJUnit4::class)
class SvgRenderInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    private fun props(source: String, vararg extra: Pair<String, WireValue>): Map<String, WireValue> =
        mapOf("source" to WireValue.Text(source), "key" to WireValue.Text(source.hashCode().toString() + source.length), *extra)

    private fun view(source: String, vararg extra: Pair<String, WireValue>, events: MutableList<ByteArray>? = null): PamSvgView {
        lateinit var view: PamSvgView
        instrumentation.runOnMainSync {
            val factory = SvgViewFactory(context)
            view = factory.create(context) { events?.add(it) } as PamSvgView
            factory.update(view, props(source, *extra))
            view.measure(View.MeasureSpec.makeMeasureSpec(100, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(100, View.MeasureSpec.EXACTLY))
            view.layout(0, 0, 100, 100)
        }
        return view
    }

    private fun render(view: View): Bitmap {
        val bitmap = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
        instrumentation.runOnMainSync { view.draw(Canvas(bitmap)) }
        return bitmap
    }

    private fun assertColor(expected: Int, actual: Int, label: String) {
        val ok = abs(Color.red(expected) - Color.red(actual)) < 40 && abs(Color.green(expected) - Color.green(actual)) < 40 &&
            abs(Color.blue(expected) - Color.blue(actual)) < 40 && abs(Color.alpha(expected) - Color.alpha(actual)) < 40
        assertTrue("$label expected #${Integer.toHexString(expected)} got #${Integer.toHexString(actual)}", ok)
    }

    @Test fun drawsShapesScaledFromViewBox() {
        val bitmap = render(view("""<svg viewBox="0 0 10 10"><rect width="5" height="10" fill="#ff0000"/><circle cx="7.5" cy="5" r="2" fill="#0000ff"/></svg>"""))
        assertColor(Color.RED, bitmap.getPixel(20, 50), "rect")
        assertColor(Color.BLUE, bitmap.getPixel(75, 50), "circle")
        assertEquals(0, Color.alpha(bitmap.getPixel(95, 5)))
    }

    @Test fun tintAndCurrentColorRecolorPaints() {
        val src = """<svg viewBox="0 0 10 10"><rect width="10" height="10" fill="currentColor"/></svg>"""
        assertColor(Color.GREEN, render(view(src, "color" to WireValue.Text("#00ff00"))).getPixel(50, 50), "currentColor")
        val tinted = """<svg viewBox="0 0 10 10"><rect width="10" height="10" fill="#ff0000" fill-opacity="0.5"/></svg>"""
        assertColor(Color.argb(128, 0, 0, 255), render(view(tinted, "tint" to WireValue.Text("#0000ff"))).getPixel(50, 50), "tint")
    }

    @Test fun linearGradientInterpolatesAcrossBoundingBox() {
        val bitmap = render(view("""<svg viewBox="0 0 10 10"><defs><linearGradient id="g"><stop offset="0" stop-color="#ff0000"/><stop offset="1" stop-color="#0000ff"/></linearGradient></defs><rect width="10" height="10" fill="url(#g)"/></svg>"""))
        assertColor(Color.RED, bitmap.getPixel(1, 50), "left")
        assertColor(Color.BLUE, bitmap.getPixel(98, 50), "right")
    }

    @Test fun trimDrawsOnlyTheRequestedFractionOfTheStroke() {
        // progress ring at 25%: starts at 12 o'clock (rotate -90) and runs clockwise to 3 o'clock.
        val ring = """<svg viewBox="0 0 100 100"><circle id="p" cx="50" cy="50" r="40" fill="none" stroke="#ffffff" stroke-width="10" pam-trim-end="0" transform="rotate(-90 50 50)"/></svg>"""
        val anim = "anim" to WireValue.Text("""[{"i":"p","p":4,"t":0.25,"d":0,"e":1}]""")
        val bitmap = render(view(ring, anim))
        assertColor(Color.WHITE, bitmap.getPixel(78, 22), "top-right quadrant")
        assertEquals(0, Color.alpha(bitmap.getPixel(22, 78)))
        assertEquals(0, Color.alpha(bitmap.getPixel(22, 22)))
    }

    @Test fun dashArrayHonoursPathLength() {
        val line = """<svg viewBox="0 0 100 10"><line x1="0" y1="5" x2="100" y2="5" stroke="#000000" stroke-width="10" pathLength="10" stroke-dasharray="1 1"/></svg>"""
        val bitmap = render(view(line))
        assertColor(Color.BLACK, bitmap.getPixel(5, 50), "first dash")
        assertEquals(0, Color.alpha(bitmap.getPixel(15, 50)))
        assertColor(Color.BLACK, bitmap.getPixel(25, 50), "second dash")
    }

    @Test fun nativeAnimationRunsOnFramesOnlyWhileVisibleAndEmitsEnd() {
        val events = CopyOnWriteArrayList<ByteArray>()
        val ring = """<svg viewBox="0 0 100 100"><circle id="p" cx="50" cy="50" r="40" fill="none" stroke="#fff" stroke-width="10" pam-trim-end="0"/><g id="spin"><rect width="10" height="10"/></g></svg>"""
        val intent = android.content.Intent(context, SvgTestActivity::class.java).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        val activity = instrumentation.startActivitySync(intent) as SvgTestActivity
        val view = view(ring, "events" to WireValue.Flag(true), events = events)
        instrumentation.runOnMainSync {
            activity.root.addView(view, 100, 100)
            view.update(props(ring, "events" to WireValue.Flag(true), "anim" to WireValue.Text("""[{"i":"p","p":4,"t":1.0,"d":2000,"e":1,"m":1}]""")))
        }
        assertTrue("animators disabled on device", android.animation.ValueAnimator.areAnimatorsEnabled())
        SystemClock.sleep(500)
        var midway = -1f
        instrumentation.runOnMainSync { midway = view.overrideValue("p", SvgProperty.TRIM_END) ?: -1f }
        assertTrue("midway was $midway", midway > 0.05f && midway < 0.95f)
        SystemClock.sleep(2500)
        instrumentation.runOnMainSync {
            assertEquals(1f, view.overrideValue("p", SvgProperty.TRIM_END)!!, 0f)
            assertFalse(view.isAnimating())
        }
        assertEquals(1, events.size)
        val end = WireMap.decode(events[0])
        assertEquals(WireValue.Text("p"), end["id"])
        assertEquals(WireValue.Integer(SvgProperty.TRIM_END.toLong()), end["property"])

        // Hidden views keep their timeline but run no frames.
        instrumentation.runOnMainSync {
            view.visibility = View.INVISIBLE
            view.update(props(ring, "anim" to WireValue.Text("""[{"i":"p","p":4,"t":1.0,"d":2000,"e":1,"m":1},{"i":"spin","p":5,"t":360.0,"f":0.0,"d":1000,"e":1,"r":-1}]""")))
        }
        SystemClock.sleep(300)
        var hidden = -1f
        instrumentation.runOnMainSync { hidden = view.overrideValue("spin", SvgProperty.ROTATION) ?: -1f }
        assertEquals(0f, hidden, 0f)
        instrumentation.runOnMainSync { view.visibility = View.VISIBLE }
        SystemClock.sleep(300)
        var shown = -1f
        instrumentation.runOnMainSync { shown = view.overrideValue("spin", SvgProperty.ROTATION) ?: -1f }
        assertTrue("rotation resumed: $shown", shown > 0f)
        instrumentation.runOnMainSync { view.release(); assertFalse(view.isAnimating()) }
        activity.finish()
    }

    @Test fun repeatedPropsDoNotRestartAnimations() {
        val ring = """<svg viewBox="0 0 10 10"><circle id="p" cx="5" cy="5" r="4" stroke="#fff"/></svg>"""
        val spec = """[{"i":"p","p":1,"t":0.5,"d":0,"e":1}]"""
        val view = view(ring, "anim" to WireValue.Text(spec))
        val first = view.document
        instrumentation.runOnMainSync { view.update(props(ring, "anim" to WireValue.Text(spec))) }
        assertTrue(first === view.document)
        assertEquals(0.5f, view.overrideValue("p", SvgProperty.OPACITY)!!, 0f)
        assertFalse(view.isAnimating())
    }

    @Test fun hardwareRenderNodeLayerRendersAnimatedGroups() {
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
        val src = """<svg viewBox="0 0 100 100"><g id="spin"><rect x="0" y="0" width="50" height="100" fill="#ff0000"/></g></svg>"""
        // Rotate 180 degrees around the center: the red half moves to the right.
        val view = view(src, "anim" to WireValue.Text("""[{"i":"spin","p":5,"t":180.0,"d":0,"e":1,"o":[50.0,50.0]}]"""))
        val reader = ImageReader.newInstance(100, 100, PixelFormat.RGBA_8888, 1, HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE or HardwareBuffer.USAGE_GPU_COLOR_OUTPUT)
        val root = RenderNode("root").apply { setPosition(0, 0, 100, 100) }
        lateinit var bitmap: Bitmap
        instrumentation.runOnMainSync {
            val canvas = root.beginRecording()
            assertTrue(canvas.isHardwareAccelerated)
            view.draw(canvas)
            root.endRecording()
        }
        val renderer = HardwareRenderer().apply { setContentRoot(root); setSurface(reader.surface) }
        renderer.createRenderRequest().setWaitForPresent(true).syncAndDraw()
        val image = reader.acquireLatestImage()
        assertNotNull(image)
        val hardware = Bitmap.wrapHardwareBuffer(image!!.hardwareBuffer!!, null)!!
        bitmap = hardware.copy(Bitmap.Config.ARGB_8888, false)
        image.close(); renderer.destroy(); reader.close()
        assertColor(Color.RED, bitmap.getPixel(80, 50), "rotated right half")
        assertEquals(0, Color.alpha(bitmap.getPixel(20, 50)))
    }

    @Test fun emitsWireEncodedAnimationEnd() {
        val payload = WireMap.encode(mapOf("event" to WireValue.Integer(1), "id" to WireValue.Text("p"), "property" to WireValue.Integer(4)))
        val decoded = WireMap.decode(payload)
        assertEquals(WireValue.Text("p"), decoded["id"])
    }

    @Test fun parsedDocumentsAreSharedAcrossViews() {
        val src = """<svg viewBox="0 0 10 10"><rect id="r" width="10" height="10"/></svg>"""
        assertTrue(view(src).document === view(src).document)
    }
}
