package com.expenselog.app

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.view.animation.OvershootInterpolator
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/** Theme selection (dark glass on true black by default, light glass optional) and screen framing. */
object Ui {
    /** Call before setContentView. Returns whether the light theme was applied. */
    fun applyTheme(a: Activity): Boolean {
        val light = Store(a).lightMode
        a.setTheme(if (light) R.style.AppTheme_Light else R.style.AppTheme)
        return light
    }

    /**
     * Call after setContentView. Applies the glass backgrounds, draws the screen edge to edge, and keeps
     * the scrolling content padded so it starts below the floating [top] bar and ends above [bottom]
     * (or the keyboard), while still being able to scroll underneath them.
     */
    fun frame(a: Activity, top: View, scroll: View, bottom: View?) {
        val root = a.findViewById<View>(android.R.id.content)
        Glass.apply(root)
        val d = a.resources.displayMetrics.density
        var bottomInset = 0

        // A fade from the background colour at the very top, so scrolled content dissolves before it
        // reaches the status bar icons instead of running underneath them.
        val base = if (Store(a).lightMode) a.getColor(R.color.bg_light) else Color.BLACK
        val scrim = View(a).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(base, base, Color.argb(0, Color.red(base), Color.green(base), Color.blue(base)))
            )
        }
        (top.parent as ViewGroup).let { p ->
            p.addView(scrim, p.indexOfChild(top), ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (56 * d).toInt()))
        }

        fun sync() {
            val padTop = top.bottom + (12 * d).toInt()
            val padBottom = if (bottom != null) (scroll.height - bottom.top) + (12 * d).toInt()
            else bottomInset + (24 * d).toInt()
            if (scroll.paddingTop != padTop || scroll.paddingBottom != padBottom) {
                scroll.setPadding(scroll.paddingLeft, padTop, scroll.paddingRight, padBottom)
            }
        }
        val onLayout = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> scroll.post { sync() } }
        top.addOnLayoutChangeListener(onLayout)
        bottom?.addOnLayoutChangeListener(onLayout)
        scroll.addOnLayoutChangeListener(onLayout)

        if (Build.VERSION.SDK_INT >= 30) {
            val w = a.window
            w.setDecorFitsSystemWindows(false)
            w.statusBarColor = Color.TRANSPARENT
            w.navigationBarColor = Color.TRANSPARENT
            w.isNavigationBarContrastEnforced = false
            if (Store(a).lightMode) {
                val flags = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
                    WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
                w.insetsController?.setSystemBarsAppearance(flags, flags)
            }
            root.setOnApplyWindowInsetsListener { _, insets ->
                val bars = insets.getInsets(WindowInsets.Type.systemBars())
                val ime = insets.getInsets(WindowInsets.Type.ime())
                bottomInset = max(bars.bottom, ime.bottom)
                (top.layoutParams as ViewGroup.MarginLayoutParams).topMargin = bars.top + (8 * d).toInt()
                top.requestLayout()
                scrim.layoutParams.height = bars.top + (44 * d).toInt()
                scrim.requestLayout()
                bottom?.let {
                    (it.layoutParams as ViewGroup.MarginLayoutParams).bottomMargin = bottomInset + (12 * d).toInt()
                    it.requestLayout()
                }
                scroll.post { sync() }
                insets
            }
        }
    }
}

/** #77: when to save battery on animation. */
object Battery {
    @Volatile
    var lastInput = 0L

    /** Battery saver is on, or the battery is below 20% and not charging. */
    fun low(c: Context): Boolean {
        val pm = c.getSystemService(android.os.PowerManager::class.java)
        if (pm?.isPowerSaveMode == true) return true
        val bm = c.getSystemService(android.os.BatteryManager::class.java) ?: return false
        return !bm.isCharging && bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY) in 0..19
    }
}

/** Small motion helpers shared by the screens. */
object Fx {
    /** Fades and slides the visible children of [container] in, one after another. */
    fun enter(container: ViewGroup) {
        val dy = 28 * container.resources.displayMetrics.density
        var n = 0
        for (i in 0 until container.childCount) {
            val v = container.getChildAt(i)
            if (v.visibility != View.VISIBLE) continue
            v.alpha = 0f
            v.translationY = dy
            v.scaleX = 0.96f
            v.scaleY = 0.96f
            v.animate().alpha(1f).translationY(0f).scaleX(1f).scaleY(1f)
                .setStartDelay(60 + n++ * 55L).setDuration(520)
                .setInterpolator(DecelerateInterpolator(2.2f)).start()
        }
    }

    /**
     * Glass controls lift towards the finger while pressed (the drawable adds the light) and settle
     * back with a soft spring, with a light haptic tick.
     */
    fun pressable(vararg views: View) {
        for (v in views) {
            v.setOnTouchListener { view, e ->
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                        view.animate().scaleX(1.05f).scaleY(1.05f).setStartDelay(0).setDuration(140)
                            .setInterpolator(DecelerateInterpolator()).start()
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        view.animate().cancel()
                        wobble(view) // settles back with a jelly wobble (#65)
                    }
                }
                false
            }
        }
    }

    /** A quick springy appearance, for messages that just changed. */
    fun pop(v: View) {
        v.animate().cancel()
        v.translationY = 0f
        v.scaleX = 0.94f
        v.scaleY = 0.94f
        v.alpha = 0f
        v.animate().scaleX(1f).scaleY(1f).alpha(1f).setStartDelay(0).setDuration(380)
            .setInterpolator(OvershootInterpolator(2f)).start()
    }

    /** #57: counts a number up (or down) to [to] over half a second, e.g. totals rolling into place. */
    fun countUp(v: android.widget.TextView, to: Double, format: (Double) -> String) {
        val from = (v.getTag(R.id.countTag) as? Double) ?: 0.0
        v.setTag(R.id.countTag, to)
        if (from == to) { v.text = format(to); return }
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 650
            interpolator = DecelerateInterpolator(2f)
            addUpdateListener { v.text = format(from + (to - from) * (it.animatedValue as Float)) }
            start()
        }
    }

    /**
     * #54: the add celebration: the card flashes green, a tick draws itself over it with a spring,
     * the background glows, then the card folds away. [then] runs once it has gone.
     */
    fun celebrate(card: View, then: () -> Unit) {
        val parent = card.parent as? ViewGroup ?: run { then(); return }
        val d = card.resources.displayMetrics.density
        AuroraView.success()
        card.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
        val overlay = android.widget.TextView(card.context).apply {
            text = "✓"
            textSize = 44f
            setTextColor(Color.WHITE)
            gravity = android.view.Gravity.CENTER
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = 30 * d
                setColor(Color.argb(150, 0x34, 0xD3, 0x99))
            }
            alpha = 0f
            scaleX = 0.6f
            scaleY = 0.6f
        }
        val lp = android.widget.FrameLayout.LayoutParams(card.width, card.height).apply {
            leftMargin = card.left; topMargin = card.top
        }
        (parent as? android.widget.FrameLayout)?.addView(overlay, lp)
        overlay.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(260).setInterpolator(OvershootInterpolator(3f))
            .withEndAction {
                // Fold: squeeze vertically and fade, then hand over.
                val row = parent
                row.animate().scaleY(0f).alpha(0f).setStartDelay(250).setDuration(260)
                    .setInterpolator(DecelerateInterpolator()).withEndAction {
                        (parent as? android.widget.FrameLayout)?.removeView(overlay)
                        row.scaleY = 1f; row.alpha = 1f
                        then()
                    }.start()
            }.start()
    }

    /**
     * #53: the Add button morphs: shrinks to a circle with a spinner while working, then shows a tick
     * (or shakes on failure) and springs back to full width.
     */
    fun morphBusy(button: android.widget.Button, busy: Boolean) {
        val full = (button.getTag(R.id.widthTag) as? Int) ?: button.width.also { button.setTag(R.id.widthTag, it) }
        val target = if (busy) button.height else full
        ValueAnimator.ofInt(button.width, target).apply {
            duration = 300
            interpolator = DecelerateInterpolator(2f)
            addUpdateListener {
                button.layoutParams = button.layoutParams.apply { width = it.animatedValue as Int }
            }
            start()
        }
    }

    fun morphDone(button: android.widget.Button, ok: Boolean, label: String) {
        if (ok) {
            button.text = "✓"
            button.animate().scaleX(1.15f).scaleY(1.15f).setDuration(160).withEndAction {
                button.animate().scaleX(1f).scaleY(1f).setDuration(260).setInterpolator(OvershootInterpolator(3f)).withEndAction {
                    button.postDelayed({ button.text = label; morphBusy(button, false) }, 450)
                }.start()
            }.start()
        } else {
            button.text = label
            morphBusy(button, false)
            shake(button)
        }
    }

    /**
     * #65: a jelly wobble on release, squashing and stretching like a soft glass blob.
     * (Android's mesh warp only works on still images, so live controls use squash-and-stretch.)
     */
    fun wobble(v: View) {
        val sx = android.animation.ObjectAnimator.ofFloat(v, "scaleX", 1.06f, 0.96f, 1.03f, 0.99f, 1f)
        val sy = android.animation.ObjectAnimator.ofFloat(v, "scaleY", 0.94f, 1.04f, 0.98f, 1.01f, 1f)
        android.animation.AnimatorSet().apply {
            playTogether(sx, sy)
            duration = 520
            start()
        }
    }

    /** Side-to-side shake for a field that needs attention. */
    fun shake(v: View) {
        val d = v.resources.displayMetrics.density
        ObjectAnimator.ofFloat(v, "translationX", 0f, -10 * d, 10 * d, -6 * d, 6 * d, 0f).setDuration(380).start()
    }
}

/**
 * Full-screen backdrop. Dark mode is true black (pixels off on an AMOLED panel) with a few coloured
 * glows drifting slowly, which is what the glass picks up; light mode is a pale wash with the same glows.
 */
class AuroraView(context: Context, attrs: AttributeSet?) : View(context, attrs) {
    private class Blob(val color: Int, val x: Float, val y: Float, val radius: Float, val phase: Float, val speed: Int) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        var r = 1f

        /** Builds the glow at its real pixel radius, centred on (0,0); onDraw moves it into place. */
        fun resize(size: Float, c: Int = color) {
            if (size <= 0f) return // not laid out yet; onSizeChanged builds it
            r = size * radius
            val mid = Color.argb(Color.alpha(c) / 3, Color.red(c), Color.green(c), Color.blue(c))
            paint.shader = RadialGradient(
                0f, 0f, r, intArrayOf(c, mid, Color.TRANSPARENT), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP
            )
        }
    }

    private val light = Store(context).lightMode
    private val base = if (light) context.getColor(R.color.bg_light) else Color.BLACK
    private val a = if (light) 0x80 else 0x78
    private val blobs = listOf(
        Blob(Color.argb(a, 0x2D, 0xD4, 0xA7), 0.05f, 0.10f, 0.46f, 0.0f, 1), // mint, top left
        Blob(Color.argb(a, 0x7C, 0x5C, 0xFF), 1.00f, 0.42f, 0.48f, 2.1f, 2), // violet, right
        Blob(Color.argb(a, 0x2F, 0x8C, 0xFF), 0.15f, 0.98f, 0.50f, 4.0f, 1)  // blue, bottom
    )
    private val amber = Color.argb(a, 0xF5, 0x9E, 0x0B)
    private var waiting = false
    private var t = 0f
    private val flashPaint = Paint()

    private val animator = ValueAnimator.ofFloat(0f, (2 * Math.PI).toFloat()).apply {
        duration = 28000
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener {
            t = it.animatedValue as Float
            invalidate()
            Backdrop.redrawAll() // the glass shows the background, so it moves with it
            // #77: stop redrawing (and with it the glass) once the screen has been left alone a while,
            // or straight away in battery saver / below 20% battery. Any touch starts it again.
            if (!shouldAnimate()) it.pause()
        }
    }

    private fun shouldAnimate(): Boolean {
        if (System.currentTimeMillis() - Battery.lastInput > IDLE_MS) return false
        return !Battery.low(context)
    }

    private val wake = Runnable { if (animator.isPaused && shouldAnimate()) animator.resume() }

    /** #69: amber glow while payments wait in Detected. */
    fun setWaiting(on: Boolean) {
        if (on == waiting) return
        waiting = on
        blobs[2].resize(max(width, height).toFloat(), if (on) amber else blobs[2].color)
        invalidate()
        Backdrop.redrawAll()
    }

    companion object {
        private const val IDLE_MS = 30_000L
        private val all = java.util.Collections.newSetFromMap(java.util.WeakHashMap<AuroraView, Boolean>())

        // #68: where the finger is (screen pixels) and when it was last there.
        @Volatile private var touchX = 0f
        @Volatile private var touchY = 0f
        @Volatile private var touchAt = 0L

        // #69: a green wash after something was added, fading over 2 seconds.
        @Volatile private var flashAt = 0L

        /** Called on every touch: wakes the backgrounds that went still, and draws the glows toward the finger. */
        fun wakeAll() = all.forEach { it.post(it.wake) }

        fun touch(x: Float, y: Float) {
            touchX = x; touchY = y; touchAt = System.currentTimeMillis()
        }

        /** A success: the background briefly glows green. */
        fun success() {
            flashAt = System.currentTimeMillis()
            Battery.lastInput = flashAt
            wakeAll()
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val size = max(w, h).toFloat()
        blobs.forEachIndexed { i, b -> b.resize(size, if (i == 2 && waiting) amber else b.color) }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        all += this
        Battery.lastInput = System.currentTimeMillis()
        animator.start()
        if (!shouldAnimate()) animator.pause()
    }

    override fun onDetachedFromWindow() {
        all -= this
        animator.cancel()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        canvas.drawColor(base)
        val now = System.currentTimeMillis()
        // The glows drift slowly and identically on every screen. (They used to be pulled toward the finger,
        // which made colours swing around while scrolling, so that was removed.)
        for (b in blobs) {
            val cx = w * (b.x + 0.12f * sin(t * b.speed + b.phase))
            val cy = h * (b.y + 0.08f * cos(t * b.speed + b.phase))
            canvas.save()
            canvas.translate(cx, cy)
            canvas.drawCircle(0f, 0f, b.r, b.paint)
            canvas.restore()
        }
        val flash = 1f - (now - flashAt) / 2000f
        if (flash > 0f) {
            flashPaint.shader = RadialGradient(
                w / 2, h * 0.6f, max(w, h) * 0.8f,
                intArrayOf(Color.argb((110 * flash).toInt(), 0x34, 0xD3, 0x99), Color.TRANSPARENT),
                null, Shader.TileMode.CLAMP
            )
            canvas.drawRect(0f, 0f, w, h, flashPaint)
        }
    }
}
