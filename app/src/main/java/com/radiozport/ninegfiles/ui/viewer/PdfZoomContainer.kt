package com.radiozport.ninegfiles.ui.viewer

import android.content.Context
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.ViewConfiguration
import android.widget.FrameLayout
import android.widget.OverScroller
import androidx.recyclerview.widget.RecyclerView
import kotlin.math.abs

/**
 * A FrameLayout that adds pinch-to-zoom and free (diagonal) panning to its
 * single RecyclerView child.
 *
 * Transform strategy:
 *  - Scale  → scaleX/Y on the child (pivot always at top-left: 0,0)
 *  - Pan X  → child.translationX  (managed here, clamped to keep content on-screen)
 *  - Pan Y  → child.translationY  (managed here when zoomed, NOT delegated to the
 *             RecyclerView — the RecyclerView's scroll range is based on un-scaled
 *             dimensions and cannot reach the extra height produced by zoom)
 *
 * When scaleFactor returns to 1f the RecyclerView's own scroll is restored so that
 * normal (un-zoomed) paging works correctly.
 *
 * Gestures:
 *  - Pinch           → zoom 1× – 5×, centred at the focal point of the gesture
 *  - Double-tap      → toggle 2.5× (centred on tap point) / reset to 1×
 *  - Drag while zoomed → free diagonal pan (X + Y simultaneously)
 *  - Fling while zoomed → momentum on both axes via OverScroller
 */
class PdfZoomContainer @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

    private var scaleFactor = 1f
    private var transX = 0f
    private var transY = 0f

    // ── Fling / momentum ────────────────────────────────────────────────────

    private val scroller = OverScroller(context)
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

    // ── Pinch-to-zoom ───────────────────────────────────────────────────────

    private val scaleDetector = ScaleGestureDetector(context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val prev = scaleFactor
                scaleFactor = (scaleFactor * detector.scaleFactor).coerceIn(MIN_SCALE, MAX_SCALE)
                if (scaleFactor != prev) {
                    val ratio = scaleFactor / prev
                    // Keep the focal point of the pinch gesture stationary on screen:
                    //   newTrans = focusPx - ratio * (focusPx - oldTrans)
                    transX = detector.focusX - ratio * (detector.focusX - transX)
                    transY = detector.focusY - ratio * (detector.focusY - transY)
                    clampTrans()
                }
                applyTransform()
                return true
            }
        })

    // ── Pan + fling (all directions when zoomed) ────────────────────────────

    private val gestureDetector = GestureDetector(context,
        object : GestureDetector.SimpleOnGestureListener() {

            override fun onScroll(
                e1: MotionEvent?, e2: MotionEvent,
                distanceX: Float, distanceY: Float
            ): Boolean {
                if (scaleFactor <= 1f) return false

                transX -= distanceX
                transY -= distanceY
                clampTrans()
                applyTransform()
                return true
            }

            override fun onFling(
                e1: MotionEvent?, e2: MotionEvent,
                velocityX: Float, velocityY: Float
            ): Boolean {
                if (scaleFactor <= 1f) return false

                val child = getChildAt(0) ?: return false
                val scaledH = child.height * scaleFactor
                val scaledW = child.width  * scaleFactor

                val minX = -(scaledW - width).toInt().coerceAtLeast(0)
                val minY = -(scaledH - height).toInt().coerceAtLeast(0)

                scroller.fling(
                    transX.toInt(), transY.toInt(),
                    velocityX.toInt(), velocityY.toInt(),
                    minX, 0,
                    minY, 0
                )
                postInvalidateOnAnimation()
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (scaleFactor > 1f) {
                    // Reset to 1× — snap back to top-left origin
                    scaleFactor = 1f
                    transX = 0f
                    transY = 0f
                } else {
                    val prev = scaleFactor           // == 1f
                    scaleFactor = DOUBLE_TAP_SCALE
                    val ratio = scaleFactor / prev
                    // Centre zoom on the tap point
                    transX = e.x - ratio * (e.x - transX)
                    transY = e.y - ratio * (e.y - transY)
                    clampTrans()
                }
                applyTransform()
                return true
            }
        })

    // ── Fling animation tick ─────────────────────────────────────────────────

    override fun computeScroll() {
        super.computeScroll()
        if (scroller.computeScrollOffset()) {
            transX = scroller.currX.toFloat()
            transY = scroller.currY.toFloat()
            clampTrans()
            applyTransform()
            postInvalidateOnAnimation()
        }
    }

    // ── Transform helpers ────────────────────────────────────────────────────

    private fun applyTransform() {
        val child = getChildAt(0) ?: return
        // Pivot at (0,0) — we manage the full translation ourselves.
        child.pivotX = 0f
        child.pivotY = 0f
        child.scaleX = scaleFactor
        child.scaleY = scaleFactor
        child.translationX = transX
        child.translationY = transY

        // While zoomed we own vertical scrolling; prevent the RecyclerView from
        // applying its own scroll offset on top of our translationY.
        if (scaleFactor > 1f) {
            (child as? RecyclerView)?.stopScroll()
        }
    }

    /**
     * Clamp both translation axes so the scaled content never leaves a gap at
     * any edge of the viewport.
     *
     * With pivot at (0,0):
     *   - transX must be in  [-(scaledW - viewW), 0]   (0 = left-aligned)
     *   - transY must be in  [-(scaledH - viewH), 0]   (0 = top-aligned)
     *
     * If the scaled dimension is smaller than the viewport (shouldn't happen
     * given MIN_SCALE = 1f, but be defensive) we centre the content.
     */
    private fun clampTrans() {
        val child = getChildAt(0) ?: return

        val scaledW = child.width  * scaleFactor
        val scaledH = child.height * scaleFactor

        val minX = if (scaledW > width)  -(scaledW - width)  else 0f
        val maxX = if (scaledW > width)  0f                  else (width - scaledW) / 2f

        val minY = if (scaledH > height) -(scaledH - height) else 0f
        val maxY = if (scaledH > height) 0f                  else (height - scaledH) / 2f

        transX = transX.coerceIn(minX, maxX)
        transY = transY.coerceIn(minY, maxY)
    }

    // ── Touch interception ───────────────────────────────────────────────────

    private var downX = 0f
    private var downY = 0f

    /**
     * True when ACTION_DOWN has already been fed to [gestureDetector] inside
     * [onInterceptTouchEvent].  Used to prevent a duplicate feed in [onTouchEvent]
     * for the same event when we decide to intercept (returning true from
     * onInterceptTouchEvent also triggers onTouchEvent for that same event).
     */
    private var downFedToGestureDetectorViaIntercept = false

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x
                downY = ev.y
                downFedToGestureDetectorViaIntercept = false
                if (!scroller.isFinished) scroller.abortAnimation()

                // ── KEY FIX ──────────────────────────────────────────────────
                // Spy ACTION_DOWN through the GestureDetector *before* deciding
                // whether to intercept.  When scaleFactor == 1f the RecyclerView
                // normally swallows all events, so onTouchEvent is never called
                // and onDoubleTap never fires.  By feeding DOWN here we let the
                // detector accumulate the first tap; on the second tap it fires
                // onDoubleTap synchronously, which sets scaleFactor to 2.5f.
                // The scaleFactor > 1f check below then intercepts the gesture.
                gestureDetector.onTouchEvent(ev)
                downFedToGestureDetectorViaIntercept = true

                // Intercept immediately when already zoomed, or when onDoubleTap
                // just fired (scaleFactor was updated synchronously above).
                if (scaleFactor > 1f) return true
            }
            MotionEvent.ACTION_POINTER_DOWN -> return true   // start of pinch
            MotionEvent.ACTION_MOVE -> {
                if (ev.pointerCount > 1) return true
                // Spy single-pointer MOVE so the GestureDetector can distinguish
                // a tap from a scroll (prevents false double-tap after a scroll).
                if (!scaleDetector.isInProgress) gestureDetector.onTouchEvent(ev)
                if (scaleFactor > 1f) {
                    val dx = abs(ev.x - downX)
                    val dy = abs(ev.y - downY)
                    if (dx > touchSlop || dy > touchSlop) return true
                }
            }
            // Spy ACTION_UP / ACTION_CANCEL so the GestureDetector can complete
            // first-tap recognition and start the double-tap timeout window.
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (!scaleDetector.isInProgress) gestureDetector.onTouchEvent(ev)
            }
        }
        return false
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN && !scroller.isFinished) {
            scroller.abortAnimation()
        }
        scaleDetector.onTouchEvent(ev)
        if (!scaleDetector.isInProgress) {
            // Skip re-feeding ACTION_DOWN to the GestureDetector when it was
            // already processed inside onInterceptTouchEvent for this same event.
            if (ev.actionMasked == MotionEvent.ACTION_DOWN && downFedToGestureDetectorViaIntercept) {
                downFedToGestureDetectorViaIntercept = false
            } else {
                gestureDetector.onTouchEvent(ev)
            }
        }
        return true
    }

    // ── Public API ───────────────────────────────────────────────────────────

    fun resetZoom() {
        scroller.abortAnimation()
        scaleFactor = 1f
        transX = 0f
        transY = 0f
        applyTransform()
    }

    companion object {
        private const val MIN_SCALE        = 1f
        private const val MAX_SCALE        = 5f
        private const val DOUBLE_TAP_SCALE = 2.5f
    }
}
