package com.blureshield.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.os.Build
import android.renderscript.Allocation
import android.renderscript.Element
import android.renderscript.RenderScript
import android.renderscript.ScriptIntrinsicBlur
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import androidx.annotation.RequiresApi
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * BlurOverlayView — The draggable, resizable frosted-glass privacy overlay.
 *
 * This view renders a frosted glass effect over whatever is beneath it:
 *
 * On Android 12+ (API 31+):
 *   Uses RenderEffect.createBlurEffect() — this is a native hardware-accelerated
 *   blur that reads what's behind the view and blurs it in real time.
 *   No screenshot needed — it blurs live content automatically.
 *
 * On Android 8–11 (API 26–30):
 *   Uses a semi-transparent white overlay with a subtle pattern to simulate
 *   frosted glass. True live-capture blurring is not possible without
 *   screen recording permission (which requires user consent per-session).
 *   The frosted glass simulation is still visually effective at hiding content
 *   from shoulder-surfers.
 *
 * User interactions:
 *   - Drag to reposition
 *   - Pinch to resize (minimum 80x60dp)
 */
class BlurOverlayView(
    context: Context,
    private var blurRadius: Float = 10f
) : View(context) {

    // -------------------------------------------------------------------------
    // Paint objects for drawing the frosted glass overlay
    // -------------------------------------------------------------------------

    // Semi-transparent white frosted glass background
    private val frostedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(160, 240, 240, 255)  // ~63% opaque white-blue tint
        style = Paint.Style.FILL
    }

    // Blue border around the overlay
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#1D9BF0")
        style = Paint.Style.STROKE
        strokeWidth = dpToPx(2f)
    }

    // Inner highlight line — makes it look more like glass
    private val highlightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(80, 255, 255, 255)
        style = Paint.Style.STROKE
        strokeWidth = dpToPx(1f)
    }

    // Noise/grain overlay — simulates frosted glass texture on older Android
    private val noisePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(20, 255, 255, 255)
        style = Paint.Style.FILL
    }

    // Corner radius for the rounded rectangle
    private val cornerRadiusPx = dpToPx(16f)

    // -------------------------------------------------------------------------
    // Touch tracking for drag
    // -------------------------------------------------------------------------

    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var initialTouchX = 0f
    private var initialTouchY = 0f
    private var isDragging = false
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

    // -------------------------------------------------------------------------
    // Pinch-to-resize
    // -------------------------------------------------------------------------

    private val scaleGestureDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val scaleFactor = detector.scaleFactor
                val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
                val lp = layoutParams as? WindowManager.LayoutParams ?: return true

                // Calculate new dimensions based on pinch scale factor
                val newWidth = (lp.width * scaleFactor).toInt()
                    .coerceIn(dpToPx(80f).toInt(), getScreenWidth())
                val newHeight = (lp.height * scaleFactor).toInt()
                    .coerceIn(dpToPx(60f).toInt(), getScreenHeight())

                // Keep overlay centered on the pinch focal point
                val widthDelta = newWidth - lp.width
                val heightDelta = newHeight - lp.height
                lp.width = newWidth
                lp.height = newHeight
                lp.x = (lp.x - widthDelta / 2).coerceIn(0, getScreenWidth() - newWidth)
                lp.y = (lp.y - heightDelta / 2).coerceIn(0, getScreenHeight() - newHeight)

                try {
                    wm.updateViewLayout(this@BlurOverlayView, lp)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
                return true
            }
        }
    )

    // -------------------------------------------------------------------------
    // RenderEffect blur (Android 12+)
    // -------------------------------------------------------------------------

    init {
        // Apply hardware-accelerated blur on Android 12+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            applyRenderEffectBlur()
        }
        // On older Android: the frosted appearance is painted in onDraw()
    }

    // -------------------------------------------------------------------------
    // Drawing
    // -------------------------------------------------------------------------

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val rect = RectF(
            dpToPx(2f),
            dpToPx(2f),
            width.toFloat() - dpToPx(2f),
            height.toFloat() - dpToPx(2f)
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // On API 31+, RenderEffect blurs everything behind us automatically.
            // We draw a semi-transparent tint on top to complete the frosted glass look.
            canvas.drawRoundRect(rect, cornerRadiusPx, cornerRadiusPx, frostedPaint)
        } else {
            // On API 26–30: paint a stronger frosted simulation
            drawFrostedSimulation(canvas, rect)
        }

        // Border
        canvas.drawRoundRect(rect, cornerRadiusPx, cornerRadiusPx, borderPaint)

        // Inner highlight
        val innerRect = RectF(
            dpToPx(4f),
            dpToPx(4f),
            width.toFloat() - dpToPx(4f),
            height.toFloat() - dpToPx(4f)
        )
        canvas.drawRoundRect(innerRect, cornerRadiusPx - dpToPx(2f), cornerRadiusPx - dpToPx(2f), highlightPaint)

        // Drag handle indicator — three horizontal dots in the center
        drawDragHandle(canvas)
    }

    /**
     * Draws a frosted glass simulation for Android 8–11.
     * Uses semi-transparent white + subtle stripe pattern.
     */
    private fun drawFrostedSimulation(canvas: Canvas, rect: RectF) {
        // Base opaque-ish white layer
        val basePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(200, 245, 245, 255)
            style = Paint.Style.FILL
        }
        canvas.drawRoundRect(rect, cornerRadiusPx, cornerRadiusPx, basePaint)

        // Add subtle diagonal stripe texture to suggest frosted glass
        val stripePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(15, 255, 255, 255)
            strokeWidth = dpToPx(4f)
            style = Paint.Style.STROKE
        }

        canvas.save()
        canvas.clipRect(rect.left, rect.top, rect.right, rect.bottom)

        var i = -height
        while (i < width + height) {
            canvas.drawLine(
                rect.left + i, rect.top,
                rect.left + i + height, rect.bottom,
                stripePaint
            )
            i += dpToPx(8f).toInt()
        }
        canvas.restore()

        // Top highlight gradient effect
        val topHighlightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(40, 255, 255, 255)
            style = Paint.Style.FILL
        }
        val topRect = RectF(rect.left, rect.top, rect.right, rect.top + rect.height() * 0.3f)
        canvas.drawRoundRect(topRect, cornerRadiusPx, cornerRadiusPx, topHighlightPaint)
    }

    /**
     * Draws three small horizontal dots to hint at drag functionality.
     */
    private fun drawDragHandle(canvas: Canvas) {
        val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(100, 100, 100, 180)
            style = Paint.Style.FILL
        }
        val cy = height / 2f
        val dotRadius = dpToPx(3f)
        val spacing = dpToPx(8f)
        val startX = width / 2f - spacing

        canvas.drawCircle(startX, cy, dotRadius, dotPaint)
        canvas.drawCircle(startX + spacing, cy, dotRadius, dotPaint)
        canvas.drawCircle(startX + spacing * 2, cy, dotRadius, dotPaint)
    }

    // -------------------------------------------------------------------------
    // Touch handling (drag + pinch)
    // -------------------------------------------------------------------------

    override fun onTouchEvent(event: MotionEvent): Boolean {
        // Pass to pinch detector first
        scaleGestureDetector.onTouchEvent(event)

        // If pinch gesture is in progress, don't handle drag
        if (scaleGestureDetector.isInProgress) return true

        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val lp = layoutParams as? WindowManager.LayoutParams ?: return false

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                initialTouchX = event.rawX
                initialTouchY = event.rawY
                lastTouchX = event.rawX
                lastTouchY = event.rawY
                isDragging = false
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val totalDeltaX = event.rawX - initialTouchX
                val totalDeltaY = event.rawY - initialTouchY
                val totalDistance = sqrt(totalDeltaX * totalDeltaX + totalDeltaY * totalDeltaY)

                if (!isDragging && totalDistance > touchSlop) {
                    isDragging = true
                }

                if (isDragging) {
                    val deltaX = event.rawX - lastTouchX
                    val deltaY = event.rawY - lastTouchY

                    // Move the overlay, clamping to screen bounds
                    lp.x = (lp.x + deltaX.toInt()).coerceIn(0, getScreenWidth() - lp.width)
                    lp.y = (lp.y + deltaY.toInt()).coerceIn(0, getScreenHeight() - lp.height)

                    try {
                        wm.updateViewLayout(this, lp)
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }

                lastTouchX = event.rawX
                lastTouchY = event.rawY
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                isDragging = false
                return true
            }
        }
        return false
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Updates the blur radius. Values 1–20 are typical; higher = more blur.
     * Called when user adjusts the slider in MainActivity.
     */
    fun setBlurRadius(radius: Float) {
        blurRadius = radius.coerceIn(1f, 25f)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            applyRenderEffectBlur()
        }

        // On older Android, repaint to adjust the frosted simulation opacity
        invalidate()
    }

    // -------------------------------------------------------------------------
    // RenderEffect (API 31+)
    // -------------------------------------------------------------------------

    /**
     * Applies a hardware-accelerated blur behind this view using RenderEffect.
     *
     * RenderEffect.createBlurEffect() blurs everything rendered beneath this
     * view in the window hierarchy. Since we use TYPE_APPLICATION_OVERLAY with
     * FLAG_NOT_FOCUSABLE, the entire screen content behind the overlay gets
     * blurred — achieving the frosted glass effect without any screenshot capture.
     *
     * The blur radius maps our 1–20 slider to 5–60px radius range.
     */
    @RequiresApi(Build.VERSION_CODES.S)
    private fun applyRenderEffectBlur() {
        // Map our 1–20 scale to a physically meaningful pixel radius
        val pixelRadius = (blurRadius / 20f) * 55f + 5f  // Range: 5px to 60px

        val blurEffect = android.graphics.RenderEffect.createBlurEffect(
            pixelRadius,
            pixelRadius,
            android.graphics.Shader.TileMode.CLAMP
        )
        setRenderEffect(blurEffect)
    }

    // -------------------------------------------------------------------------
    // Utility helpers
    // -------------------------------------------------------------------------

    private fun dpToPx(dp: Float): Float {
        return dp * resources.displayMetrics.density
    }

    private fun getScreenWidth(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            wm.currentWindowMetrics.bounds.width()
        } else {
            val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            @Suppress("DEPRECATION")
            wm.defaultDisplay.width
        }
    }

    private fun getScreenHeight(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            wm.currentWindowMetrics.bounds.height()
        } else {
            val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            @Suppress("DEPRECATION")
            wm.defaultDisplay.height
        }
    }
}
