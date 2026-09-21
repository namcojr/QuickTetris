package com.namco.quicktetris

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.withRotation
import androidx.core.graphics.withTranslation
import kotlin.math.cos
import kotlin.math.sin

/**
 * Cabinet artwork shared by the control panel and overlays: a blue screen-printed plate with
 * racing stripes, Phillips screws and concave Sanwa-style buttons.
 *
 * Skins hold shaders built around (0,0) (plates: in view coordinates), so drawing only translates
 * the canvas. Build skins on size changes, never per frame.
 */
internal class ArcadeArt(private val density: Float) {

    enum class Icon { NONE, LEFT, RIGHT, DOWN, ROTATE }

    class PlateSkin(val rect: RectF, val corner: Float) {
        val fill: Shader = LinearGradient(
            0f, rect.top, 0f, rect.bottom, COLOR_PLATE_TOP, COLOR_PLATE_BOTTOM, Shader.TileMode.CLAMP,
        )
        val sheen: Shader = LinearGradient(
            rect.left, rect.top, rect.left + rect.width() * 0.7f, rect.top + rect.height() * 0.5f,
            Color.argb(0x2A, 0xFF, 0xFF, 0xFF), Color.TRANSPARENT, Shader.TileMode.CLAMP,
        )
        val bevel: Shader = LinearGradient(
            0f, rect.top, 0f, rect.bottom,
            Color.argb(0x70, 0xFF, 0xFF, 0xFF), Color.argb(0xA0, 0, 0, 0), Shader.TileMode.CLAMP,
        )
    }

    class ButtonSkin(val r: Float, color: Int) {
        val bezelR = r * BEZEL_SCALE
        val shadowR = bezelR * 1.25f
        val shadow: Shader = RadialGradient(
            0f, 0f, shadowR,
            intArrayOf(COLOR_SHADOW, COLOR_SHADOW, Color.TRANSPARENT), floatArrayOf(0f, 0.78f, 1f),
            Shader.TileMode.CLAMP,
        )
        val bezel = vGradient(bezelR, shade(color, 0.35f), shade(color, -0.6f))
        val hole = shade(color, -0.88f)
        val skirt = shade(color, -0.45f)
        // Convex rim around a concave dish: light falls on the rim's top and the dish's bottom.
        val rim = vGradient(r, shade(color, 0.5f), shade(color, -0.3f))
        val dish = vGradient(r * DISH_SCALE, shade(color, -0.22f), shade(color, 0.2f))
        val rimPressed = vGradient(r, shade(color, 0.2f), shade(color, -0.45f))
        val dishPressed = vGradient(r * DISH_SCALE, shade(color, -0.42f), shade(color, -0.05f))
    }

    class ScrewSkin(val r: Float) {
        val recessR = r * 1.6f
        val recess: Shader = RadialGradient(
            0f, 0f, recessR,
            intArrayOf(COLOR_SHADOW, COLOR_SHADOW, Color.TRANSPARENT), floatArrayOf(0f, 0.6f, 1f),
            Shader.TileMode.CLAMP,
        )
        val head: Shader = LinearGradient(
            -r, -r, r, r,
            intArrayOf(Color.rgb(0xF4, 0xF4, 0xF6), Color.rgb(0xA8, 0xA8, 0xB0), Color.rgb(0x4C, 0x4C, 0x56)),
            null, Shader.TileMode.CLAMP,
        )
        val slot = RectF(-r * 0.62f, -r * 0.13f, r * 0.62f, r * 0.13f)
    }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val iconStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val path = Path()
    private val tmp = RectF()

    /** Plate with a drop shadow, top-left sheen and bevelled edge. */
    fun drawPlate(canvas: Canvas, s: PlateSkin) {
        val d = density
        paint.color = COLOR_SHADOW
        tmp.set(s.rect)
        tmp.offset(0f, 2f * d)
        canvas.drawRoundRect(tmp, s.corner, s.corner, paint)

        paint.shader = s.fill
        canvas.drawRoundRect(s.rect, s.corner, s.corner, paint)
        paint.shader = s.sheen
        canvas.drawRoundRect(s.rect, s.corner, s.corner, paint)
        paint.shader = null

        stroke.shader = s.bevel
        stroke.strokeWidth = 1.5f * d
        tmp.set(s.rect)
        tmp.inset(0.75f * d, 0.75f * d)
        canvas.drawRoundRect(tmp, s.corner, s.corner, stroke)
        stroke.shader = null
    }

    /** Red/orange/yellow racing stripes, [stripe] thick each, spanning [band] centered on [cy]. */
    fun drawStripes(canvas: Canvas, left: Float, right: Float, cy: Float, stripe: Float, band: Float) {
        val gap = (band - STRIPE_COLORS.size * stripe) / (STRIPE_COLORS.size - 1)
        var y = cy - band / 2
        for (color in STRIPE_COLORS) {
            paint.color = color
            canvas.drawRect(left, y, right, y + stripe, paint)
            y += stripe + gap
        }
    }

    fun drawScrew(canvas: Canvas, s: ScrewSkin, x: Float, y: Float, angle: Float) {
        val d = density
        canvas.withTranslation(x, y) {
            paint.shader = s.recess
            drawCircle(0f, 0.8f * d, s.recessR, paint)
            paint.shader = s.head
            drawCircle(0f, 0f, s.r, paint)
            paint.shader = null
            stroke.color = Color.argb(0xB0, 0x18, 0x18, 0x1C)
            stroke.strokeWidth = 0.8f * d
            drawCircle(0f, 0f, s.r, stroke)
            withRotation(angle) {
                val slotCorner = s.slot.height() / 2
                // Lower-right lip catches light, then the dark slot itself.
                for (pass in 0..1) {
                    val off = if (pass == 0) 0.6f * d else 0f
                    paint.color = if (pass == 0) Color.argb(0x80, 0xFF, 0xFF, 0xFF) else COLOR_SLOT
                    withTranslation(off, off) {
                        drawRoundRect(s.slot, slotCorner, slotCorner, paint)
                        withRotation(90f) { drawRoundRect(s.slot, slotCorner, slotCorner, paint) }
                    }
                }
            }
        }
    }

    /** Button seated in its bezel; a pressed plunger sinks nearly flush and loses its shine. */
    fun drawButton(canvas: Canvas, s: ButtonSkin, cx: Float, cy: Float, pressed: Boolean, icon: Icon) {
        val r = s.r
        val lift = r * if (pressed) LIFT_PRESSED else LIFT
        canvas.withTranslation(cx, cy) {
            paint.shader = s.shadow
            drawCircle(0f, r * if (pressed) 0.05f else 0.14f, s.shadowR, paint)

            paint.shader = s.bezel
            drawCircle(0f, 0f, s.bezelR, paint)
            paint.shader = null
            stroke.color = Color.argb(0x90, 0, 0, 0)
            stroke.strokeWidth = this@ArcadeArt.density
            drawCircle(0f, 0f, s.bezelR, stroke)

            paint.color = s.hole
            drawCircle(0f, 0f, r * 1.05f, paint)

            // Plunger side wall: a capsule from the hole up to the raised cap.
            paint.color = s.skirt
            tmp.set(-r, -lift - r, r, r)
            drawRoundRect(tmp, r, r, paint)

            withTranslation(0f, -lift) {
                paint.shader = if (pressed) s.rimPressed else s.rim
                drawCircle(0f, 0f, r, paint)
                paint.shader = if (pressed) s.dishPressed else s.dish
                drawCircle(0f, 0f, r * DISH_SCALE, paint)
                paint.shader = null

                iconStroke.color = if (pressed) Color.argb(0x50, 0xFF, 0xFF, 0xFF) else Color.argb(0xB0, 0xFF, 0xFF, 0xFF)
                iconStroke.strokeWidth = r * 0.07f
                tmp.set(-r * 0.9f, -r * 0.9f, r * 0.9f, r * 0.9f)
                drawArc(tmp, 195f, 60f, false, iconStroke)

                drawIcon(this, icon, r)
            }
        }
    }

    /** Legend printed on the cap, drawn around (0,0). */
    private fun drawIcon(canvas: Canvas, icon: Icon, r: Float) {
        paint.color = COLOR_ICON
        when (icon) {
            Icon.LEFT -> drawArrow(canvas, r, -1f, 0f)
            Icon.RIGHT -> drawArrow(canvas, r, 1f, 0f)
            Icon.DOWN -> drawArrow(canvas, r, 0f, 1f)
            Icon.ROTATE -> {
                // Clockwise arc with its gap at the top right, arrowhead at the arc's end.
                val rr = r * 0.36f
                iconStroke.color = COLOR_ICON
                iconStroke.strokeWidth = r * 0.12f
                tmp.set(-rr, -rr, rr, rr)
                canvas.drawArc(tmp, 0f, ROTATE_SWEEP, false, iconStroke)
                val a = Math.toRadians(ROTATE_SWEEP.toDouble())
                val nx = cos(a).toFloat()
                val ny = sin(a).toFloat()
                val px = nx * rr
                val py = ny * rr
                val tx = -ny
                val ty = nx
                val h = r * 0.2f
                path.reset()
                path.moveTo(px + tx * h * 1.1f, py + ty * h * 1.1f)
                path.lineTo(px - tx * h * 0.3f + nx * h, py - ty * h * 0.3f + ny * h)
                path.lineTo(px - tx * h * 0.3f - nx * h, py - ty * h * 0.3f - ny * h)
                path.close()
                canvas.drawPath(path, paint)
            }
            Icon.NONE -> Unit
        }
    }

    /** Filled triangle pointing along (dx, dy), one axis only. */
    private fun drawArrow(canvas: Canvas, r: Float, dx: Float, dy: Float) {
        val s = r * 0.38f
        path.reset()
        path.moveTo(dx * s, dy * s)
        path.lineTo(-dx * s * 0.6f - dy * s, -dy * s * 0.6f - dx * s)
        path.lineTo(-dx * s * 0.6f + dy * s, -dy * s * 0.6f + dx * s)
        path.close()
        canvas.drawPath(path, paint)
    }

    companion object {
        const val BEZEL_SCALE = 1.22f // bezel radius / cap radius
        private const val DISH_SCALE = 0.8f
        private const val LIFT = 0.14f // cap height above the bezel, in cap radii
        private const val LIFT_PRESSED = 0.02f
        private const val ROTATE_SWEEP = 285f

        val COLOR_CABINET = Color.rgb(0x0A, 0x0A, 0x0E)
        val COLOR_SHADOW = Color.argb(0xA0, 0, 0, 0)
        val COLOR_LEGEND = Color.argb(0xE6, 0xFF, 0xFF, 0xFF)
        val COLOR_RED = Color.rgb(0xE0, 0x24, 0x24)
        private val COLOR_PLATE_TOP = Color.rgb(0x2A, 0x3C, 0xB8)
        private val COLOR_PLATE_BOTTOM = Color.rgb(0x10, 0x18, 0x5A)
        private val COLOR_SLOT = Color.rgb(0x26, 0x26, 0x2C)
        private val COLOR_ICON = Color.argb(0x80, 0, 0, 0)
        private val STRIPE_COLORS = intArrayOf(
            Color.rgb(0xE5, 0x39, 0x35), Color.rgb(0xFB, 0x8C, 0x00), Color.rgb(0xFD, 0xD8, 0x35),
        )

        /** Condensed bold used for every screen-printed legend. */
        val LEGEND_TYPEFACE: Typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)

        private fun shade(color: Int, amount: Float): Int =
            if (amount >= 0f) ColorUtils.blendARGB(color, Color.WHITE, amount)
            else ColorUtils.blendARGB(color, Color.BLACK, -amount)

        private fun vGradient(extent: Float, top: Int, bottom: Int): Shader =
            LinearGradient(0f, -extent, 0f, extent, top, bottom, Shader.TileMode.CLAMP)
    }
}
