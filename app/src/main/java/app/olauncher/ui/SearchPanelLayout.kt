package app.olauncher.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import android.widget.FrameLayout
import androidx.core.content.ContextCompat
import app.olauncher.R

/** Bottom-aligns the measured content and animates the outline without relaying out rows. */
class SearchPanelLayout(context: Context, attrs: AttributeSet?) : FrameLayout(context, attrs) {
    private lateinit var results: View
    private lateinit var rename: View
    private lateinit var status: View
    private lateinit var moveModeHint: View
    private lateinit var dock: View

    private val surface = ContextCompat.getColor(context, R.color.bolt_search_dock)
    private val outline = ContextCompat.getColor(context, R.color.bolt_search_outline)
    private val radius = resources.getDimension(R.dimen.bolt_search_corner_radius)
    private val rowInset = resources.getDimensionPixelSize(R.dimen.bolt_search_results_inset)
    private val stroke = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shape = Path()
    private val bounds = RectF()
    private var animator: ValueAnimator? = null
    private var visibleHeight = 0f
    private var targetHeight = 0f
    private var resultsHeight = 0
    private var statusInPanel = false

    var statusIsResult = false
        set(value) {
            if (field == value) return
            field = value
            requestLayout()
        }

    private var dockColor = surface
    private var dividerAlpha = 255

    fun setDockAppearance(color: Int, thunderProgress: Float) {
        val alpha = ((1f - thunderProgress) * 255).toInt()
        if (dockColor == color && dividerAlpha == alpha) return
        dockColor = color
        dividerAlpha = alpha
        invalidate()
    }

    override fun onFinishInflate() {
        super.onFinishInflate()
        results = findViewById(R.id.recyclerView)
        rename = findViewById(R.id.appRename)
        status = findViewById(R.id.searchStatus)
        moveModeHint = findViewById(R.id.moveModeHint)
        dock = findViewById(R.id.dock)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = MeasureSpec.getSize(heightMeasureSpec)
        val exactWidth = MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY)
        dock.measure(exactWidth, MeasureSpec.makeMeasureSpec(
            minOf(dock.layoutParams.height, height), MeasureSpec.EXACTLY
        ))
        var remaining = height - dock.measuredHeight
        statusInPanel = status.visibility == View.VISIBLE && statusIsResult
        val statusInset = if (statusInPanel) rowInset.coerceAtMost(width / 2) else 0
        val statusWidth = MeasureSpec.makeMeasureSpec(width - 2 * statusInset, MeasureSpec.EXACTLY)
        val statusSpace = if (statusInPanel) (remaining - rowInset).coerceAtLeast(0) else remaining
        val statusHeight = measureContent(status, statusWidth, statusSpace)
        remaining -= statusHeight + if (statusInPanel) minOf(rowInset, remaining - statusHeight) else 0
        remaining -= measureContent(rename, exactWidth, remaining)
        remaining -= measureContent(moveModeHint, exactWidth, remaining)
        resultsHeight = measureContent(results, exactWidth, (remaining - rowInset).coerceAtLeast(0))
        setMeasuredDimension(width, height)
    }

    private fun measureContent(view: View, widthSpec: Int, available: Int): Int {
        if (view.visibility != View.VISIBLE) return 0
        view.measure(widthSpec, MeasureSpec.makeMeasureSpec(available, MeasureSpec.AT_MOST))
        return view.measuredHeight
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val dockTop = height - dock.measuredHeight
        dock.layout(0, dockTop, width, height)
        var edge = dockTop
        if (statusInPanel) {
            val inset = (width - status.measuredWidth) / 2
            val statusTop = edge - status.measuredHeight
            status.layout(inset, statusTop, width - inset, edge)
            edge = (statusTop - rowInset).coerceAtLeast(0)
        }
        edge = layoutAbove(results, edge)
        if (resultsHeight > 0) edge -= rowInset
        updateHeight((height - edge).toFloat())
        if (!statusInPanel) edge = layoutAbove(status, edge)
        edge = layoutAbove(moveModeHint, edge)
        layoutAbove(rename, edge)
    }

    private fun layoutAbove(view: View, bottom: Int): Int {
        val top = if (view.visibility == View.VISIBLE) bottom - view.measuredHeight else bottom
        view.layout(0, top, width, bottom)
        return top
    }

    private fun updateHeight(target: Float) {
        if (target == targetHeight && visibleHeight <= height) return
        animator?.cancel()
        targetHeight = target
        val start = visibleHeight.coerceIn(dock.measuredHeight.toFloat(), height.toFloat())
        if (!isLaidOut || !LauncherMotion.isEnabled(context) || start == target) {
            visibleHeight = target
            invalidate()
            return
        }
        visibleHeight = start
        animator = ValueAnimator.ofFloat(start, target).apply {
            duration = LauncherMotion.MODE_DURATION
            interpolator = LauncherMotion.settle
            addUpdateListener {
                visibleHeight = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun dispatchDraw(canvas: Canvas) {
        if (visibleHeight <= stroke || width <= stroke) return
        val panelTop = height - visibleHeight
        val dockTop = dock.top.toFloat()
        bounds.set(0f, panelTop, width.toFloat(), height.toFloat())
        shape.rewind()
        shape.addRoundRect(bounds, radius, radius, Path.Direction.CW)
        paint.color = surface
        canvas.drawPath(shape, paint)

        val save = canvas.save()
        canvas.clipPath(shape)
        paint.color = dockColor
        canvas.drawRect(0f, dockTop, width.toFloat(), height.toFloat(), paint)
        // In Thunder mode the transition from charcoal to yellow already separates the bar.
        if (dividerAlpha > 0 && (resultsHeight > 0 || statusInPanel)) {
            paint.color = outline
            paint.alpha = dividerAlpha
            canvas.drawRect(rowInset.toFloat(), dockTop, (width - rowInset).toFloat(), dockTop + stroke, paint)
        }
        canvas.restoreToCount(save)
        super.dispatchDraw(canvas)

        bounds.inset(stroke / 2f, stroke / 2f)
        paint.color = outline
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = stroke
        canvas.drawRoundRect(bounds, radius - stroke / 2f, radius - stroke / 2f, paint)
        paint.style = Paint.Style.FILL
    }

    override fun drawChild(canvas: Canvas, child: View, drawingTime: Long): Boolean {
        if (child !== results && child !== dock && !(child === status && statusInPanel)) {
            return super.drawChild(canvas, child, drawingTime)
        }
        val save = canvas.save()
        canvas.clipPath(shape)
        val needsRedraw = super.drawChild(canvas, child, drawingTime)
        canvas.restoreToCount(save)
        return needsRedraw
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        animator = null
        visibleHeight = targetHeight
        super.onDetachedFromWindow()
    }
}
