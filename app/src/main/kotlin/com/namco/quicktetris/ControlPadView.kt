package com.namco.quicktetris

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.os.Build
import android.os.SystemClock
import android.util.SparseArray
import android.util.SparseIntArray
import android.util.SparseLongArray
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import com.namco.quicktetris.ArcadeArt.Icon
import org.libsdl.app.SDLActivity
import kotlin.math.max
import kotlin.math.min

/**
 * Multi-touch arcade control panel injecting Android keycodes into SDL, which MAME reads as its
 * default keyboard bindings:
 *
 *        COIN            START
 *   LEFT      RIGHT   DROP    ROTATE
 *
 * A single view owns every pointer so a finger can slide between buttons (LEFT <-> RIGHT) without
 * lifting. Keys are refcounted, so START and ROTATE (both BUTTON1 on atetris) never release each
 * other.
 *
 * Rendering: a full-bleed screwed-on plate (see [ArcadeArt]) running under the navigation bar;
 * buttons stay inside the inset-free area.
 */
class ControlPadView(context: Context) : View(context) {

    private enum class Control(val label: String, val keyCode: Int, val color: Int, val icon: Icon) {
        // MAME COIN1 = KEYCODE_5.
        COIN("COIN", KeyEvent.KEYCODE_5, Color.rgb(0xEC, 0xEC, 0xE6), Icon.NONE),
        // atetris has no START input: the cabinet wires START and ROTATE to the same bit (BUTTON1).
        START("START", KeyEvent.KEYCODE_CTRL_LEFT, Color.rgb(0xFF, 0x8A, 0x1C), Icon.NONE),
        LEFT("LEFT", KeyEvent.KEYCODE_DPAD_LEFT, Color.rgb(0xF6, 0xC8, 0x1A), Icon.LEFT),
        RIGHT("RIGHT", KeyEvent.KEYCODE_DPAD_RIGHT, Color.rgb(0xF6, 0xC8, 0x1A), Icon.RIGHT),
        DROP("DROP", KeyEvent.KEYCODE_DPAD_DOWN, Color.rgb(0x2E, 0xB8, 0x4A), Icon.DOWN),
        ROTATE("ROTATE", KeyEvent.KEYCODE_CTRL_LEFT, ArcadeArt.COLOR_RED, Icon.ROTATE),
    }

    private val controls = Control.entries
    private val hitRects = Array(controls.size) { RectF() }
    private val centerX = FloatArray(controls.size)
    private val centerY = FloatArray(controls.size)
    private val labelY = FloatArray(controls.size)
    private val skins = arrayOfNulls<ArcadeArt.ButtonSkin>(controls.size)
    private val pressCount = IntArray(controls.size)
    private val pointerTargets = SparseArray<Control>()
    private val keyHolds = SparseIntArray()
    private val pressedAt = SparseLongArray()
    private val pendingUps = SparseArray<Runnable>()

    private val density = resources.displayMetrics.density
    private var bottomInset = 0

    private val art = ArcadeArt(density)
    private val molding = RectF()
    private var moldingFill: Shader? = null
    private var plate: ArcadeArt.PlateSkin? = null
    private var screw: ArcadeArt.ScrewSkin? = null
    private var splitY = 0f
    private var screwBottom = 0f

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = ArcadeArt.LEGEND_TYPEFACE
        letterSpacing = 0.14f
    }

    init {
        setBackgroundColor(ArcadeArt.COLOR_CABINET)
        isFocusable = false
    }

    /**
     * Portrait: everything below the game area, which is 4:3 stretched by [GAME_STRETCH]
     * (MAME runs with -nokeepaspect and fills whatever it gets).
     */
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        val game = (w * 0.75f * GAME_STRETCH).toInt()
        val desired = max(h - game, (h * MIN_PANEL_FRACTION).toInt() + bottomInset)
        setMeasuredDimension(w, resolveSize(min(desired, h), heightMeasureSpec))
    }

    override fun onApplyWindowInsets(insets: WindowInsets): WindowInsets {
        val bottom = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            insets.getInsets(WindowInsets.Type.navigationBars() or WindowInsets.Type.displayCutout()).bottom
        } else {
            @Suppress("DEPRECATION")
            insets.systemWindowInsetBottom
        }
        if (bottom != bottomInset) {
            bottomInset = bottom
            // Height may not change (so no onSizeChanged), but the buttons still have to move.
            if (width > 0) layoutPanel(width, height)
            requestLayout()
        }
        return insets
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) = layoutPanel(w, h)

    private fun layoutPanel(w: Int, h: Int) {
        val d = density
        molding.set(0f, 0f, w.toFloat(), MOLDING_DP * d)
        moldingFill = LinearGradient(
            0f, molding.top, 0f, molding.bottom,
            intArrayOf(Color.rgb(0x9A, 0x9A, 0xA4), Color.rgb(0x2C, 0x2C, 0x32), Color.rgb(0x0C, 0x0C, 0x10)),
            floatArrayOf(0f, 0.35f, 1f), Shader.TileMode.CLAMP,
        )

        skins.fill(null)
        hitRects.forEach { it.setEmpty() }
        plate = null
        screw = null
        // The plate bleeds to every edge; controls keep to the area above the navigation bar.
        val rect = RectF(0f, molding.bottom, w.toFloat(), h.toFloat())
        val usableBottom = h - bottomInset.toFloat()
        if (rect.width() <= 0f || usableBottom - rect.top <= 0f) return

        val pad = PLATE_PAD_DP * d
        val colW = rect.width() / 4
        label.textSize = min(LABEL_DP * d, colW * 0.15f)
        val fm = label.fontMetrics
        val labelGap = LABEL_GAP_DP * d
        val labelH = labelGap + fm.descent - fm.ascent
        val stripeHalf = STRIPE_BAND_DP * d / 2
        splitY = rect.top + (usableBottom - rect.top) * TOP_SECTION

        // Bottom row: four big buttons sized by column width and the height left under the stripes.
        val bigTop = splitY + stripeHalf + pad * 0.5f
        val bigH = usableBottom - pad - bigTop
        val bigR = (min(colW / 2 - 4f * d, (bigH - labelH) / 2) / ArcadeArt.BEZEL_SCALE)
            .coerceIn(0f, MAX_BIG_DP * d)
        val bigCy = bigTop + (bigH - 2 * bigR * ArcadeArt.BEZEL_SCALE - labelH) / 2 + bigR * ArcadeArt.BEZEL_SCALE
        listOf(Control.LEFT, Control.RIGHT, Control.DROP, Control.ROTATE).forEachIndexed { i, c ->
            place(c, colW * (i + 0.5f), bigCy, bigR, labelGap - fm.ascent)
            // Hit areas run to the view edges and down through the inset for forgiving thumbs.
            hitRects[c.ordinal].set(colW * i, splitY, colW * (i + 1), h.toFloat())
        }

        // Top row: small buttons centered over each half.
        val smallTop = rect.top + pad * 0.75f
        val smallH = splitY - stripeHalf - pad * 0.5f - smallTop
        val smallR = min(bigR * SMALL_SCALE, (smallH - labelH) / 2 / ArcadeArt.BEZEL_SCALE).coerceAtLeast(0f)
        val smallCy = smallTop + (smallH - 2 * smallR * ArcadeArt.BEZEL_SCALE - labelH) / 2 +
            smallR * ArcadeArt.BEZEL_SCALE
        place(Control.COIN, colW, smallCy, smallR, labelGap - fm.ascent)
        place(Control.START, colW * 3, smallCy, smallR, labelGap - fm.ascent)
        hitRects[Control.COIN.ordinal].set(0f, 0f, w / 2f, splitY)
        hitRects[Control.START.ordinal].set(w / 2f, 0f, w.toFloat(), splitY)

        plate = ArcadeArt.PlateSkin(rect, 0f)
        screw = ArcadeArt.ScrewSkin(SCREW_DP * d)
        screwBottom = usableBottom - SCREW_INSET_Y_DP * d
    }

    private fun place(c: Control, cx: Float, cy: Float, r: Float, labelOffset: Float) {
        centerX[c.ordinal] = cx
        centerY[c.ordinal] = cy
        labelY[c.ordinal] = cy + r * ArcadeArt.BEZEL_SCALE + labelOffset
        skins[c.ordinal] = if (r > 0f) ArcadeArt.ButtonSkin(r, c.color) else null
    }

    override fun onDraw(canvas: Canvas) {
        val plate = plate ?: return
        val screw = screw ?: return
        val d = density

        paint.shader = moldingFill
        canvas.drawRect(molding, paint)
        paint.shader = null

        art.drawPlate(canvas, plate)
        art.drawStripes(canvas, plate.rect.left, plate.rect.right, splitY, STRIPE_DP * d, STRIPE_BAND_DP * d)

        val left = plate.rect.left + SCREW_INSET_X_DP * d
        val right = plate.rect.right - SCREW_INSET_X_DP * d
        val top = plate.rect.top + SCREW_INSET_Y_DP * d
        art.drawScrew(canvas, screw, left, top, 20f)
        art.drawScrew(canvas, screw, right, top, 65f)
        art.drawScrew(canvas, screw, left, screwBottom, 110f)
        art.drawScrew(canvas, screw, right, screwBottom, 40f)

        for (c in controls) {
            val skin = skins[c.ordinal] ?: continue
            drawLabel(canvas, c)
            art.drawButton(canvas, skin, centerX[c.ordinal], centerY[c.ordinal], pressCount[c.ordinal] > 0, c.icon)
        }
    }

    /** Screen-printed legend under a button. */
    private fun drawLabel(canvas: Canvas, c: Control) {
        val x = centerX[c.ordinal]
        val y = labelY[c.ordinal]
        label.color = ArcadeArt.COLOR_SHADOW
        canvas.drawText(c.label, x, y + density, label)
        label.color = ArcadeArt.COLOR_LEGEND
        canvas.drawText(c.label, x, y, label)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val i = event.actionIndex
                retarget(event.getPointerId(i), hitTest(event.getX(i), event.getY(i)))
            }
            MotionEvent.ACTION_MOVE -> for (i in 0 until event.pointerCount) {
                retarget(event.getPointerId(i), hitTest(event.getX(i), event.getY(i)))
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP ->
                retarget(event.getPointerId(event.actionIndex), null)
            MotionEvent.ACTION_CANCEL -> releaseAll()
        }
        return true
    }

    private fun hitTest(x: Float, y: Float): Control? =
        controls.firstOrNull { hitRects[it.ordinal].contains(x, y) }

    private fun retarget(pointerId: Int, target: Control?) {
        val current = pointerTargets.get(pointerId)
        if (current == target) return
        current?.let { release(it) }
        if (target != null) {
            pointerTargets.put(pointerId, target)
            press(target)
        } else {
            pointerTargets.remove(pointerId)
        }
        invalidate()
    }

    private fun press(c: Control) {
        pressCount[c.ordinal]++
        val holds = keyHolds.get(c.keyCode)
        keyHolds.put(c.keyCode, holds + 1)
        if (holds > 0) return
        // A re-press inside the minimum hold window just cancels the pending key-up.
        val pending = pendingUps.get(c.keyCode)
        if (pending != null) {
            removeCallbacks(pending)
            pendingUps.remove(c.keyCode)
        } else {
            sendKey(c.keyCode, true)
            pressedAt.put(c.keyCode, SystemClock.uptimeMillis())
        }
    }

    private fun release(c: Control) {
        if (pressCount[c.ordinal] > 0) pressCount[c.ordinal]--
        val holds = keyHolds.get(c.keyCode)
        if (holds <= 0) return
        keyHolds.put(c.keyCode, holds - 1)
        if (holds > 1) return
        // MAME samples key state once per frame: a down+up within one poll is lost, so fast taps
        // are stretched to MIN_HOLD_MS.
        val remaining = pressedAt.get(c.keyCode) + MIN_HOLD_MS - SystemClock.uptimeMillis()
        if (remaining <= 0) {
            sendKey(c.keyCode, false)
            return
        }
        val keyCode = c.keyCode
        val up = Runnable {
            pendingUps.remove(keyCode)
            sendKey(keyCode, false)
        }
        pendingUps.put(keyCode, up)
        postDelayed(up, remaining)
    }

    /** Lifts every held key; call whenever touch delivery may be interrupted. */
    fun releaseAll() {
        for (i in 0 until pendingUps.size()) {
            removeCallbacks(pendingUps.valueAt(i))
            sendKey(pendingUps.keyAt(i), false)
        }
        pendingUps.clear()
        for (i in 0 until keyHolds.size()) {
            if (keyHolds.valueAt(i) > 0) sendKey(keyHolds.keyAt(i), false)
        }
        keyHolds.clear()
        pointerTargets.clear()
        pressCount.fill(0)
        invalidate()
    }

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        if (!hasWindowFocus) releaseAll()
    }

    override fun onDetachedFromWindow() {
        releaseAll()
        super.onDetachedFromWindow()
    }

    private fun sendKey(keyCode: Int, down: Boolean) {
        if (SDLActivity.mBrokenLibraries) return
        if (down) SDLActivity.onNativeKeyDown(keyCode) else SDLActivity.onNativeKeyUp(keyCode)
    }

    private companion object {
        const val MIN_PANEL_FRACTION = 0.3f
        const val MIN_HOLD_MS = 50L // 3 frames at 60 Hz

        /** Vertical stretch of the 4:3 game image; 1.2 keeps blocks visibly square-ish. */
        const val GAME_STRETCH = 1.2f

        // Geometry (dp unless noted).
        const val MOLDING_DP = 6f
        const val PLATE_PAD_DP = 18f
        const val TOP_SECTION = 0.3f // fraction of the usable plate height above the stripes
        const val STRIPE_DP = 3f
        const val STRIPE_BAND_DP = 13f
        const val SCREW_DP = 6f
        const val SCREW_INSET_X_DP = 16f // clear of rounded display corners
        const val SCREW_INSET_Y_DP = 13f
        const val LABEL_DP = 13f
        const val LABEL_GAP_DP = 6f
        const val MAX_BIG_DP = 58f
        const val SMALL_SCALE = 0.6f
    }
}
