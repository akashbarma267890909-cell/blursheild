package com.blureshield.app

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Build
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * BubbleView — The floating draggable bubble that sits on top of all apps.
 *
 * Features:
 * - Draws a filled circle with an "X" icon in the center
 * - Drag to reposition anywhere on screen
 * - Short tap (no movement) triggers onTap callback to toggle blur overlay
 * - On finger release, smoothly snaps to the nearest screen edge (left or right)
 * - Always stays within screen bounds
 */
class BubbleView(
    context: Context,
    private var colorName: String = MainActivity.COLOR_BLUE
) : View(context) {

    // Callback invoked when user taps (not drags) the bubble
    var onTap: (() -> Unit)? = null

    // -------------------------------------------------------------------------
    // Paint objects
    // -------------------------------------------------------------------------

    private val circlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = colorNameToInt(colorName)
    }

    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.argb(60, 0, 0, 0)
    }

    private val xPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.WHITE
        strokeWidth = dpToPx(2.5f)
        strokeCap = Paint.Cap.ROUND
    }

    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.argb(80, 255, 255, 255)  // Subtle white ring
        strokeWidth = dpToPx(1.5f)
    }

    // -------------------------------------------------------------------------
    // Touch tracking
    // -------------------------------------------------------------------------

    // Position tracking for drag vs tap detection
    private var initialTouchX = 0f
    private var initialTouchY = 0f
    private var lastTouchX = 0f
    private var lastTouchY = 0f

    // Whether finger has moved enough to count as a drag (not a tap)
    private var isDragging = false

    // Touch slop: minimum distance in px that counts as movement (not a tap)
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

    // -------------------------------------------------------------------------
    // Drawing
    // -------------------------------------------------------------------------

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val cx = width / 2f
        val cy = height / 2f
        val radius = (minOf(width, height) / 2f) - dpToPx(3f)

        // Drop shadow (offset circle behind main circle)
        canvas.drawCircle(cx + dpToPx(2f), cy + dpToPx(3f), radius, shadowPaint)

        // Main filled circle
        canvas.drawCircle(cx, cy, radius, circlePaint)

        // Subtle white border ring
        canvas.drawCircle(cx, cy, radius - dpToPx(1f), borderPaint)

        // Draw "X" icon in the center
        val xSize = radius * 0.35f
        canvas.drawLine(cx - xSize, cy - xSize, cx + xSize, cy + xSize, xPaint)
        canvas.drawLine(cx + xSize, cy - xSize, cx - xSize, cy + xSize, xPaint)
    }

    // -------------------------------------------------------------------------
    // Touch handling
    // -------------------------------------------------------------------------

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val lp = layoutParams as WindowManager.LayoutParams

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                // Record where the finger first touched
                initialTouchX = event.rawX
                initialTouchY = event.rawY
                lastTouchX = event.rawX
                lastTouchY = event.rawY
                isDragging = false
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val deltaX = event.rawX - lastTouchX
                val deltaY = event.rawY - lastTouchY

                // Check if total movement exceeds the touch slop threshold
                val totalDeltaX = event.rawX - initialTouchX
                val totalDeltaY = event.rawY - initialTouchY
                val totalDistance = sqrt(totalDeltaX * totalDeltaX + totalDeltaY * totalDeltaY)

                if (!isDragging && totalDistance > touchSlop) {
                    isDragging = true
                }

                if (isDragging) {
                    // Move the view by updating WindowManager LayoutParams
                    lp.x = (lp.x + deltaX.toInt()).coerceIn(0, getScreenWidth() - width)
                    lp.y = (lp.y + deltaY.toInt()).coerceIn(0, getScreenHeight() - height)
                    wm.updateViewLayout(this, lp)
                }

                lastTouchX = event.rawX
                lastTouchY = event.rawY
                return true
            }

            MotionEvent.ACTION_UP -> {
                if (!isDragging) {
                    // This was a tap — fire the callback
                    onTap?.invoke()
                } else {
                    // Drag ended — snap to nearest screen edge (left or right)
                    snapToNearestEdge(wm, lp)
                }
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                if (isDragging) {
                    snapToNearestEdge(wm, lp)
                }
                return true
            }
        }

        return super.onTouchEvent(event)
    }

    // -------------------------------------------------------------------------
    // Edge snapping animation
    // -------------------------------------------------------------------------

    /**
     * Animates the bubble to snap to either the left or right edge of the screen,
     * whichever is closer. This keeps the bubble out of the way but still accessible.
     */
    private fun snapToNearestEdge(wm: WindowManager, lp: WindowManager.LayoutParams) {
        val screenWidth = getScreenWidth()
        val bubbleCenterX = lp.x + width / 2

        // Target X position: snap to right edge if bubble center is in the right half
        val targetX = if (bubbleCenterX > screenWidth / 2) {
            screenWidth - width - dpToPx(8).toInt()  // Snap right (8dp margin)
        } else {
            dpToPx(8).toInt()  // Snap left (8dp margin)
        }

        val startX = lp.x

        // Animate from current position to target edge
        ValueAnimator.ofInt(startX, targetX).apply {
            duration = 300
            interpolator = DecelerateInterpolator()
            addUpdateListener { animator ->
                lp.x = animator.animatedValue as Int
                try {
                    wm.updateViewLayout(this@BubbleView, lp)
                } catch (e: Exception) {
                    cancel()  // View was removed while animating
                }
            }
            start()
        }
    }

    // -------------------------------------------------------------------------
    // Color updating
    // -------------------------------------------------------------------------

    /**
     * Updates the bubble color. Called when user changes color in MainActivity.
     */
    fun updateColor(newColorName: String) {
        colorName = newColorName
        circlePaint.color = colorNameToInt(newColorName)
        invalidate()  // Trigger redraw
    }

    // -------------------------------------------------------------------------
    // Utility helpers
    // -------------------------------------------------------------------------

    private fun dpToPx(dp: Float): Float {
        return dp * resources.displayMetrics.density
    }

    private fun dpToPx(dp: Int): Int {
        return (dp * resources.displayMetrics.density).toInt()
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

    /**
     * Maps a color name string to its int color value.
     */
    private fun colorNameToInt(name: String): Int = when (name) {
        MainActivity.COLOR_RED -> Color.parseColor("#FF4569")
        MainActivity.COLOR_GREEN -> Color.parseColor("#00E676")
        MainActivity.COLOR_BLACK -> Color.parseColor("#1A1A2E")
        else -> Color.parseColor("#1D9BF0")  // Default blue
    }
}
