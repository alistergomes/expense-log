package com.expenselog.app

import android.animation.ValueAnimator
import android.annotation.TargetApi
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import java.util.Collections
import java.util.WeakHashMap
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * #67: where the light comes from. Tilting the phone moves it, so highlights on the glass slide across
 * like real light on glass. Uses the gravity sensor; off in battery saver / low battery.
 */
object Light {
    /** Light direction, -1..1 on each axis. (-0.6, -0.8) is the resting top-left light. */
    @Volatile var x = -0.6f
    @Volatile var y = -0.8f

    private val views = Collections.newSetFromMap(WeakHashMap<View, Boolean>())
    private var sensors: SensorManager? = null

    private val listener = object : SensorEventListener {
        override fun onSensorChanged(e: SensorEvent) {
            // Gravity in the phone's frame: tilting right moves the light left, tilting toward you moves it down.
            val nx = (-0.6f + e.values[0] / 9.81f * 0.9f).coerceIn(-1f, 1f)
            val ny = (-0.8f - (e.values[1] / 9.81f - 0.7f) * 0.9f).coerceIn(-1f, 1f)
            if (abs(nx - x) + abs(ny - y) < 0.02f) return
            x = x + (nx - x) * 0.35f // smooth
            y = y + (ny - y) * 0.35f
            views.forEach { it.invalidate() }
        }

        override fun onAccuracyChanged(s: Sensor?, a: Int) {}
    }

    fun track(v: View) { views += v }

    fun start(c: Context) {
        if (sensors != null || Battery.low(c)) return
        val sm = c.getSystemService(SensorManager::class.java) ?: return
        val s = sm.getDefaultSensor(Sensor.TYPE_GRAVITY) ?: sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) ?: return
        sensors = sm
        sm.registerListener(listener, s, SensorManager.SENSOR_DELAY_UI)
    }

    fun stop() {
        sensors?.unregisterListener(listener)
        sensors = null
    }
}

/**
 * The glass material: a translucent body, a sheen on the side facing the light, a bright rim that catches
 * the light (and moves with tilt), a glow under the finger while pressed, and a ripple ring that spreads
 * from the touch point.
 */
class GlassDrawable(private val kind: Kind, private val light: Boolean, private val d: Float) : Drawable() {
    enum class Kind { CARD, FIELD, PILL, PRIMARY, YES, NO, BAR }

    private val rect = RectF()
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val sheen = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG)
    private val lift = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.2f * d
    }
    private val clip = Path()
    private var radius = 0f
    private var pressed = false
    private var focused = false
    private var enabled = true
    private var press = 0f // 0..1, animated
    private var ripple = -1f // 0..1 while a ripple spreads, -1 when none
    private var rx = 0f
    private var ry = 0f
    private var hx = Float.NaN
    private var hy = Float.NaN
    private var anim: ValueAnimator? = null
    private var rippleAnim: ValueAnimator? = null
    private var lightX = Float.NaN
    private var lightY = Float.NaN

    private fun white(a: Float) = Color.argb((a * 255).toInt().coerceIn(0, 255), 255, 255, 255)
    private fun black(a: Float) = Color.argb((a * 255).toInt().coerceIn(0, 255), 0, 0, 0)
    private fun tint(a: Float, rgb: Int) = Color.argb((a * 255).toInt().coerceIn(0, 255), Color.red(rgb), Color.green(rgb), Color.blue(rgb))

    override fun onBoundsChange(bounds: Rect) = build()

    private fun build() {
        val b = bounds
        if (b.isEmpty) return
        val inset = rim.strokeWidth / 2
        rect.set(b.left + inset, b.top + inset, b.right - inset, b.bottom - inset)
        radius = when (kind) {
            Kind.CARD, Kind.BAR -> 30 * d
            Kind.FIELD -> 18 * d
            else -> rect.height() / 2 // capsule
        }.coerceAtMost(rect.height() / 2)
        clip.reset()
        clip.addRoundRect(rect, radius, radius, Path.Direction.CW)

        fill.shader = null
        when (kind) {
            Kind.PRIMARY, Kind.YES -> fill.shader = LinearGradient(
                rect.left, 0f, rect.right, 0f, tint(0.90f, MINT), tint(0.90f, CYAN), Shader.TileMode.CLAMP
            )
            Kind.NO -> fill.color = tint(if (light) 0.16f else 0.20f, RED)
            Kind.FIELD -> fill.color = if (light) white(0.55f) else black(0.32f)
            Kind.CARD -> fill.color = if (light) white(0.40f) else white(0.05f)
            Kind.PILL -> fill.color = if (light) white(0.62f) else white(0.11f)
            Kind.BAR -> fill.color = if (light) white(0.30f) else white(0.06f)
        }
        lightX = Float.NaN // rebuild the light-dependent parts on next draw
    }

    /** Sheen and rim depend on where the light is (#67); rebuilt only when it has moved. */
    private fun buildLight() {
        val lx = Light.x
        val ly = Light.y
        if (lx == lightX && ly == lightY) return
        lightX = lx
        lightY = ly
        val cx = rect.centerX()
        val cy = rect.centerY()
        val reach = max(rect.width(), rect.height())
        // From the lit edge toward the far edge.
        val sx = cx + lx * reach / 2
        val sy = cy + ly * rect.height()
        val ex = cx - lx * reach / 2
        val ey = cy - ly * rect.height()

        val sheenColor = when (kind) {
            Kind.PRIMARY, Kind.YES -> white(0.40f)
            Kind.FIELD -> Color.TRANSPARENT
            else -> if (light) white(0.50f) else white(0.12f)
        }
        sheen.shader = LinearGradient(sx, sy, cx, cy, sheenColor, Color.TRANSPARENT, Shader.TileMode.CLAMP)

        val colors: IntArray
        val stops: FloatArray
        when {
            kind == Kind.FIELD && focused -> {
                colors = intArrayOf(tint(0.95f, MINT), tint(0.55f, CYAN), tint(0.95f, MINT)); stops = floatArrayOf(0f, 0.5f, 1f)
            }
            kind == Kind.FIELD -> {
                // Recessed: shadowed on the lit side, light catching the far edge.
                colors = if (light) intArrayOf(black(0.12f), black(0.05f), white(0.95f))
                else intArrayOf(black(0.35f), white(0.06f), white(0.24f))
                stops = floatArrayOf(0f, 0.5f, 1f)
            }
            kind == Kind.NO -> {
                colors = intArrayOf(tint(0.80f, RED), tint(0.25f, RED), tint(0.55f, RED)); stops = floatArrayOf(0f, 0.5f, 1f)
            }
            else -> {
                colors = if (light) intArrayOf(white(1f), white(0.55f), white(0.30f), white(0.90f))
                else intArrayOf(white(0.66f), white(0.12f), white(0.05f), white(0.34f))
                stops = floatArrayOf(0f, 0.3f, 0.7f, 1f)
            }
        }
        rim.shader = LinearGradient(sx, sy, ex, ey, colors, stops, Shader.TileMode.CLAMP)
    }

    override fun draw(canvas: Canvas) {
        if (rect.isEmpty) return
        buildLight()
        val layer = if (!enabled) canvas.saveLayerAlpha(rect, 110) else -1
        canvas.drawRoundRect(rect, radius, radius, fill)
        canvas.drawRoundRect(rect, radius, radius, sheen)
        if (press > 0f) {
            // The whole surface brightens a little, and a pool of light sits under the finger.
            lift.color = white((if (light) 0.25f else 0.07f) * press)
            canvas.drawRoundRect(rect, radius, radius, lift)
            val cx = if (hx.isNaN()) rect.centerX() else hx
            val cy = if (hy.isNaN()) rect.centerY() else hy
            val g = (if (light) 0.75f else 0.34f) * press
            glow.shader = RadialGradient(
                cx, cy, max(rect.width(), rect.height()) * 0.7f,
                intArrayOf(white(g), white(g * 0.3f), Color.TRANSPARENT), floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP
            )
            canvas.drawRoundRect(rect, radius, radius, glow)
        }
        if (ripple >= 0f) {
            // #64: a ring of light spreading through the glass from the touch point, fading as it goes.
            val maxR = hypot(rect.width().toDouble(), rect.height().toDouble()).toFloat()
            ring.strokeWidth = (10 * d) * (1f - ripple) + 1.5f * d
            ring.color = white((if (light) 0.6f else 0.35f) * (1f - ripple))
            canvas.save()
            canvas.clipPath(clip)
            canvas.drawCircle(rx, ry, maxR * ripple, ring)
            canvas.restore()
        }
        canvas.drawRoundRect(rect, radius, radius, rim)
        if (layer != -1) canvas.restoreToCount(layer)
    }

    override fun isStateful() = true

    override fun onStateChange(state: IntArray): Boolean {
        val p = android.R.attr.state_pressed in state
        val f = android.R.attr.state_focused in state
        val e = android.R.attr.state_enabled in state
        var changed = false
        if (f != focused) {
            focused = f
            lightX = Float.NaN
            changed = true
        }
        if (e != enabled) {
            enabled = e
            changed = true
        }
        if (p != pressed) {
            pressed = p
            anim?.cancel()
            anim = ValueAnimator.ofFloat(press, if (p) 1f else 0f).apply {
                duration = if (p) 90 else 420 // light comes on fast and fades slowly
                addUpdateListener {
                    press = it.animatedValue as Float
                    invalidateSelf()
                }
                start()
            }
            if (p) startRipple()
            changed = true
        }
        if (changed) invalidateSelf()
        return changed
    }

    private fun startRipple() {
        rx = if (hx.isNaN()) rect.centerX() else hx
        ry = if (hy.isNaN()) rect.centerY() else hy
        rippleAnim?.cancel()
        rippleAnim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 650
            interpolator = DecelerateInterpolator(1.6f)
            addUpdateListener {
                ripple = it.animatedValue as Float
                invalidateSelf()
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(a: android.animation.Animator) { ripple = -1f; invalidateSelf() }
            })
            start()
        }
    }

    override fun setHotspot(x: Float, y: Float) {
        hx = x
        hy = y
        if (press > 0f) invalidateSelf()
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: ColorFilter?) {}

    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT

    companion object {
        const val MINT = 0x34D399
        const val CYAN = 0x22D3EE
        const val RED = 0xF87171
    }
}

/** Gives every view tagged card / field / pill / primary / yes / no its glass background. */
object Glass {
    fun apply(root: View) {
        walk(root, Store(root.context).lightMode, root.resources.displayMetrics.density)
    }

    private fun walk(v: View, light: Boolean, d: Float) {
        val kind = when (v.tag) {
            "card", "pill_card" -> GlassDrawable.Kind.CARD
            "field" -> GlassDrawable.Kind.FIELD
            "pill" -> GlassDrawable.Kind.PILL
            "primary" -> GlassDrawable.Kind.PRIMARY
            "yes" -> GlassDrawable.Kind.YES
            "no" -> GlassDrawable.Kind.NO
            else -> null
        }
        if (kind != null) {
            v.background = GlassDrawable(kind, light, d)
            Light.track(v)
        }
        if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i), light, d)
    }
}

/**
 * Refraction for glass (Android 13+). Near the rim of the rounded shape the backdrop is sampled from
 * further inside, the way a thick glass edge bends what is behind it; red and blue are bent by slightly
 * different amounts for a faint colour fringe, and the rim picks up a little light.
 */
@TargetApi(33)
private object Lens {
    private const val SRC = """
        uniform shader content;
        uniform float2 size;
        uniform float radius;
        uniform float edge;
        uniform float strength;

        float sdRoundRect(float2 p, float2 halfSize, float r) {
            float2 q = abs(p) - halfSize + r;
            return length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - r;
        }

        half4 main(float2 coord) {
            float2 c = size * 0.5;
            float2 p = coord - c;
            float d = sdRoundRect(p, c, radius);              // 0 at the rim, negative inside
            float t = clamp(1.0 + d / edge, 0.0, 1.0);        // 0 in the flat middle, 1 at the rim
            float bend = t * t * t;
            float2 g = float2(
                sdRoundRect(p + float2(1.0, 0.0), c, radius) - sdRoundRect(p - float2(1.0, 0.0), c, radius),
                sdRoundRect(p + float2(0.0, 1.0), c, radius) - sdRoundRect(p - float2(0.0, 1.0), c, radius));
            float2 n = g / max(length(g), 0.0001);            // points outward, towards the nearest rim
            float2 off = n * bend * strength;
            half4 mid = content.eval(coord - off);
            half red = content.eval(coord - off * 1.15).r;
            half blue = content.eval(coord - off * 0.85).b;
            half3 col = half3(red, mid.g, blue) + half3(0.10 * bend);
            return half4(col, mid.a);
        }
    """

    fun refract(inner: RenderEffect, w: Float, h: Float, corner: Float, d: Float, strengthDp: Float): RenderEffect {
        val shader = RuntimeShader(SRC)
        shader.setFloatUniform("size", w, h)
        shader.setFloatUniform("radius", corner)
        shader.setFloatUniform("edge", min(24 * d, h / 2f))
        shader.setFloatUniform("strength", strengthDp * d)
        return RenderEffect.createChainEffect(RenderEffect.createRuntimeShaderEffect(shader, "content"), inner)
    }
}

/**
 * Draws a live, blurred, colour-boosted and refracted copy of what is behind [host] (the [sources]),
 * clipped to the host's rounded shape. Android 12+ only; older phones just get the plain glass.
 */
class Backdrop(private val host: View, private val blurDp: Float, private val lensDp: Float) {
    companion object {
        /** Glass views showing a backdrop; the moving background redraws them on each of its frames. */
        val hosts: MutableSet<View> = Collections.newSetFromMap(WeakHashMap<View, Boolean>())
        fun redrawAll() = hosts.forEach { it.invalidate() }
    }

    var sources: List<View> = emptyList()
    private val d = host.resources.displayMetrics.density
    private val clip = Path()
    private val here = IntArray(2)
    private val there = IntArray(2)
    private var ok = Build.VERSION.SDK_INT >= 31
    private val node: RenderNode? = if (Build.VERSION.SDK_INT >= 31) RenderNode("backdrop") else null

    fun resize(w: Int, h: Int, corner: Float) {
        clip.reset()
        clip.addRoundRect(0f, 0f, w.toFloat(), h.toFloat(), corner, corner, Path.Direction.CW)
        if (Build.VERSION.SDK_INT < 31 || node == null || w == 0 || h == 0) return
        val vivid = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(1.5f) })
        val frosted = RenderEffect.createColorFilterEffect(
            vivid, RenderEffect.createBlurEffect(blurDp * d, blurDp * d, Shader.TileMode.CLAMP)
        )
        node.setRenderEffect(
            if (Build.VERSION.SDK_INT >= 33) runCatching { Lens.refract(frosted, w.toFloat(), h.toFloat(), corner, d, lensDp) }.getOrDefault(frosted)
            else frosted
        )
    }

    fun draw(canvas: Canvas) {
        val n = node ?: return
        if (!ok || sources.isEmpty() || !canvas.isHardwareAccelerated || Build.VERSION.SDK_INT < 31) return
        try {
            n.setPosition(0, 0, host.width, host.height)
            val rc = n.beginRecording()
            host.getLocationInWindow(here)
            for (s in sources) {
                if (s === host || !s.isAttachedToWindow) continue
                s.getLocationInWindow(there)
                rc.save()
                rc.translate((there[0] - here[0]).toFloat(), (there[1] - here[1]).toFloat())
                s.draw(rc)
                rc.restore()
            }
            n.endRecording()
            canvas.save()
            canvas.clipPath(clip)
            canvas.drawRenderNode(n)
            canvas.restore()
        } catch (e: Exception) {
            ok = false // fall back to plain glass rather than risk the screen
        }
    }
}

/** A floating glass bar: content passes underneath, blurred and bent at the rim. */
class BlurBar(context: Context, attrs: AttributeSet?) : FrameLayout(context, attrs) {
    /** The views drawn behind this bar, back to front (they must be siblings, not ancestors). */
    var sources: List<View>
        get() = backdrop.sources
        set(v) { backdrop.sources = v }

    private val backdrop = Backdrop(this, blurDp = 9f, lensDp = 30f)
    private val glass = GlassDrawable(GlassDrawable.Kind.BAR, Store(context).lightMode, resources.displayMetrics.density)

    // Redraw when what is behind moves: scrolling, or the drifting background (see Backdrop.hosts).
    private val redraw = ViewTreeObserver.OnScrollChangedListener { invalidate() }

    init {
        setWillNotDraw(false)
        Light.track(this)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        viewTreeObserver.addOnScrollChangedListener(redraw)
        Backdrop.hosts += this
    }

    override fun onDetachedFromWindow() {
        viewTreeObserver.removeOnScrollChangedListener(redraw)
        Backdrop.hosts -= this
        super.onDetachedFromWindow()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        glass.setBounds(0, 0, w, h)
        backdrop.resize(w, h, min(30 * resources.displayMetrics.density, h / 2f))
    }

    override fun onDraw(canvas: Canvas) {
        backdrop.draw(canvas)
        glass.draw(canvas)
    }
}

/**
 * #66: a glass card that refracts the moving background behind it (the glows bend at its rim).
 * Finds the screen's background view by id, so layouts only need to use this in place of LinearLayout.
 */
class GlassCard(context: Context, attrs: AttributeSet?) : LinearLayout(context, attrs) {
    private val backdrop = Backdrop(this, blurDp = 6f, lensDp = 22f)

    private val redraw = ViewTreeObserver.OnScrollChangedListener { invalidate() }

    init {
        setWillNotDraw(false)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        rootView.findViewById<View>(R.id.aurora)?.let { backdrop.sources = listOf(it) }
        viewTreeObserver.addOnScrollChangedListener(redraw)
        Backdrop.hosts += this
    }

    override fun onDetachedFromWindow() {
        viewTreeObserver.removeOnScrollChangedListener(redraw)
        Backdrop.hosts -= this
        super.onDetachedFromWindow()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        backdrop.resize(w, h, min(30 * resources.displayMetrics.density, h / 2f))
    }

    override fun draw(canvas: Canvas) {
        backdrop.draw(canvas) // behind the glass body (background) and the content
        super.draw(canvas)
    }
}
