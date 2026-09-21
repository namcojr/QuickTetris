package com.namco.quicktetris

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import kotlin.math.max
import kotlin.math.min

/**
 * Plaque announcing the active CRT shader, built from the control panel's artwork: a screwed-on
 * plate with a red arcade button that clicks down as it appears, the shader name and a stripe.
 *
 * Never consumes touches, so taps fall through to the game surface underneath.
 */
class ShaderToastView(context: Context) : View(context) {

    private val density = resources.displayMetrics.density
    private val art = ArcadeArt(density)
    private var plate: ArcadeArt.PlateSkin? = null
    private var button: ArcadeArt.ButtonSkin? = null
    private var screw: ArcadeArt.ScrewSkin? = null

    private var title = ""
    private var subtitle = ""
    private var pressed = false

    private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = ArcadeArt.LEGEND_TYPEFACE
        letterSpacing = 0.1f
        textSize = TITLE_DP * density
    }
    private val subtitlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = ArcadeArt.LEGEND_TYPEFACE
        letterSpacing = 0.08f
        textSize = SUBTITLE_DP * density
        color = COLOR_SUBTITLE
    }

    private val popUp = Runnable {
        pressed = false
        invalidate()
    }
    private val hide = Runnable {
        animate().alpha(0f).translationY(-SLIDE_DP * density)
            .setDuration(HIDE_MS).setInterpolator(AccelerateInterpolator())
            .withEndAction { visibility = GONE }
            .start()
    }

    init {
        visibility = GONE
        isClickable = false
        isFocusable = false
        accessibilityLiveRegion = ACCESSIBILITY_LIVE_REGION_POLITE
    }

    /** Shows (or refreshes) the plaque; it hides itself after [VISIBLE_MS]. */
    fun show(title: String, subtitle: String) {
        this.title = title
        this.subtitle = subtitle
        contentDescription = "$title. $subtitle"
        pressed = true
        removeCallbacks(popUp)
        removeCallbacks(hide)
        // Cancelling skips the hide animation's end action, so the view stays VISIBLE.
        animate().cancel()
        if (visibility != VISIBLE) {
            visibility = VISIBLE
            alpha = 0f
            translationY = -SLIDE_DP * density
        }
        animate().alpha(1f).translationY(0f)
            .setDuration(SHOW_MS).setInterpolator(DecelerateInterpolator())
            .start()
        postDelayed(popUp, PRESS_MS)
        postDelayed(hide, VISIBLE_MS)
        requestLayout()
        invalidate()
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(popUp)
        removeCallbacks(hide)
        super.onDetachedFromWindow()
    }

    /** Wraps the text; the plate never exceeds what the parent offers. */
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val d = density
        val text = max(titlePaint.measureText(title), subtitlePaint.measureText(subtitle))
        val desired = (textLeft() + text + (PAD_DP + SHADOW_DP) * d).toInt()
        val minW = (MIN_WIDTH_DP * d).toInt()
        setMeasuredDimension(
            resolveSize(max(desired, minW), widthMeasureSpec),
            resolveSize((HEIGHT_DP * d).toInt(), heightMeasureSpec),
        )
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val d = density
        // Leave room below for the plate's drop shadow.
        val rect = RectF(0f, 0f, w.toFloat(), h - SHADOW_DP * d)
        plate = ArcadeArt.PlateSkin(rect, CORNER_DP * d)
        val r = min(BUTTON_DP * d, rect.height() * 0.3f)
        button = ArcadeArt.ButtonSkin(r, ArcadeArt.COLOR_RED)
        screw = ArcadeArt.ScrewSkin(SCREW_DP * d)
    }

    override fun onDraw(canvas: Canvas) {
        val plate = plate ?: return
        val button = button ?: return
        val screw = screw ?: return
        val d = density
        val rect = plate.rect

        art.drawPlate(canvas, plate)

        val inset = SCREW_INSET_DP * d
        art.drawScrew(canvas, screw, rect.left + inset, rect.top + inset, 30f)
        art.drawScrew(canvas, screw, rect.right - inset, rect.top + inset, 75f)
        art.drawScrew(canvas, screw, rect.left + inset, rect.bottom - inset, 120f)
        art.drawScrew(canvas, screw, rect.right - inset, rect.bottom - inset, 50f)

        art.drawButton(
            canvas, button, buttonCx(), rect.centerY() + button.r * 0.06f, pressed, ArcadeArt.Icon.NONE,
        )

        val x = textLeft()
        val right = rect.right - (PAD_DP + 4f) * d
        val titleY = rect.top + TITLE_BASELINE_DP * d
        val subtitleY = titleY + SUBTITLE_GAP_DP * d
        titlePaint.color = ArcadeArt.COLOR_SHADOW
        canvas.drawText(title, x, titleY + d, titlePaint)
        titlePaint.color = Color.WHITE
        canvas.drawText(title, x, titleY, titlePaint)
        canvas.drawText(subtitle, x, subtitleY, subtitlePaint)

        if (right > x) {
            art.drawStripes(canvas, x, right, rect.bottom - STRIPE_FROM_BOTTOM_DP * d, 2f * d, 7f * d)
        }
    }

    private fun buttonCx(): Float = (PAD_DP + BUTTON_DP * ArcadeArt.BEZEL_SCALE) * density

    private fun textLeft(): Float = buttonCx() + (BUTTON_DP * ArcadeArt.BEZEL_SCALE + 12f) * density

    private companion object {
        const val HEIGHT_DP = 62f
        const val MIN_WIDTH_DP = 200f
        const val SHADOW_DP = 3f
        const val CORNER_DP = 12f
        const val PAD_DP = 14f
        const val BUTTON_DP = 15f
        const val SCREW_DP = 3.2f
        const val SCREW_INSET_DP = 7f
        // dp, not sp: the plaque is fixed-size artwork, like the panel legends.
        const val TITLE_DP = 17f
        const val SUBTITLE_DP = 10.5f
        const val TITLE_BASELINE_DP = 24f
        const val SUBTITLE_GAP_DP = 14f
        const val STRIPE_FROM_BOTTOM_DP = 10f
        const val SLIDE_DP = 16f

        const val SHOW_MS = 180L
        const val HIDE_MS = 240L
        const val PRESS_MS = 170L
        const val VISIBLE_MS = 1800L

        val COLOR_SUBTITLE = Color.rgb(0xFD, 0xD8, 0x35)
    }
}
