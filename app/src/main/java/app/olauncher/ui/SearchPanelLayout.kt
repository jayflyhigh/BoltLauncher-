package app.olauncher.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import android.widget.FrameLayout
import app.olauncher.helper.getColorFromAttr
import app.olauncher.R

/** Bottom-aligns the measured content and animates the outline without relaying out rows. */
class SearchPanelLayout(context: Context, attrs: AttributeSet?) : FrameLayout(context, attrs) {
    private lateinit var results: View
    private lateinit var rename: View
    private lateinit var status: View
    private lateinit var moveModeHint: View
    private lateinit var dock: View

    private val surface = context.getColorFromAttr(R.attr.boltPanelColor)
    private val outline = context.getColorFromAttr(R.attr.boltPanelOutlineColor)
    private val radius = resources.getDimension(R.dimen.bolt_search_corner_radius)
    private val dockGap = resources.getDimensionPixelSize(R.dimen.bolt_result_dock_gap)
    private val rowInset = resources.getDimensionPixelSize(R.dimen.bolt_search_results_inset)
    private val stroke = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glassReflection = context.getColorFromAttr(R.attr.boltGlassReflectionColor)
    private val dockEdge = if (context.getColorFromAttr(R.attr.boltSmokedControlColor) != 0) {
        context.getColorFromAttr(R.attr.boltGlassTopEdgeColor)
    } else {
        outline
    }
    // Cache the gradients. The panel moves with the keyboard and changes height while typing.
    private val glassSheen = if (glassReflection != 0) {
        LinearGradient(0f, 0f, 240f * stroke, 64f * stroke,
            glassReflection, 0x00FFFFFF, Shader.TileMode.CLAMP)
    } else null
    private val glassEdge = if (glassSheen != null) {
        LinearGradient(0f, 0f, 0f, 52f * stroke,
            context.getColorFromAttr(R.attr.boltGlassTopEdgeColor), outline, Shader.TileMode.CLAMP)
    } else null
    private val glassGrain = if (glassSheen != null) {
        val size = 64
        val random = kotlin.random.Random(0)
        val pixels = IntArray(size * size) {
            val tone = if (random.nextBoolean()) 255 else 0
            Color.argb(random.nextInt(12), tone, tone, tone)
        }
        val texture = Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
        BitmapShader(texture, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
    } else null
    private val shape = Path()
    private val bounds = RectF()
    private val edgeBounds = RectF()
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

    private var dockColor = context.getColorFromAttr(R.attr.boltDockColor)
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
        val statusGap = if (statusInPanel) dockGap.coerceAtMost(remaining) else 0
        val statusSpace = if (statusInPanel) (remaining - rowInset - statusGap).coerceAtLeast(0) else remaining
        val statusHeight = measureContent(status, statusWidth, statusSpace)
        remaining -= statusHeight + statusGap
        if (statusInPanel) remaining -= minOf(rowInset, remaining)
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
            val statusBottom = (edge - dockGap).coerceAtLeast(0)
            val statusTop = statusBottom - status.measuredHeight
            status.layout(inset, statusTop, width - inset, statusBottom)
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
        if (bounds.top != panelTop || bounds.right != width.toFloat() || bounds.bottom != height.toFloat()) {
            bounds.set(0f, panelTop, width.toFloat(), height.toFloat())
            shape.rewind()
            shape.addRoundRect(bounds, radius, radius, Path.Direction.CW)
            edgeBounds.set(bounds)
            edgeBounds.inset(stroke / 2f, stroke / 2f)
            if (glassEdge != null) edgeBounds.offset(0f, -edgeBounds.top)
        }
        val save = canvas.save()
        canvas.clipPath(shape)
        if (panelTop < dockTop) {
            paint.color = surface
            canvas.drawRect(0f, panelTop, width.toFloat(), dockTop, paint)
            drawGlassSheen(canvas, panelTop, dockTop, 200)
        }
        paint.color = dockColor
        canvas.drawRect(0f, dockTop, width.toFloat(), height.toFloat(), paint)
        drawGlassSheen(canvas, dockTop, height.toFloat(), dividerAlpha)
        // In Thunder mode the transition from charcoal to yellow already separates the bar.
        if (dividerAlpha > 0 && (resultsHeight > 0 || statusInPanel)) {
            paint.color = dockEdge
            paint.alpha = paint.alpha * dividerAlpha / 255
            canvas.drawRect(rowInset.toFloat(), dockTop, (width - rowInset).toFloat(), dockTop + stroke, paint)
        }
        canvas.restoreToCount(save)
        super.dispatchDraw(canvas)

        paint.color = outline
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = stroke
        if (glassEdge == null) {
            canvas.drawRoundRect(edgeBounds, radius - stroke / 2f, radius - stroke / 2f, paint)
        } else {
            // Draw in panel-local coordinates so the bright rim tracks its top edge.
            val edgeSave = canvas.save()
            canvas.translate(0f, panelTop + stroke / 2f)
            paint.color = Color.WHITE
            paint.shader = glassEdge
            canvas.drawRoundRect(edgeBounds, radius - stroke / 2f, radius - stroke / 2f, paint)
            paint.shader = null
            canvas.restoreToCount(edgeSave)
        }
        paint.style = Paint.Style.FILL
    }

    private fun drawGlassSheen(canvas: Canvas, top: Float, bottom: Float, opacity: Int) {
        val sheen = glassSheen ?: return
        if (opacity == 0) return
        val save = canvas.save()
        canvas.translate(0f, top)
        paint.shader = sheen
        paint.alpha = opacity
        canvas.drawRect(0f, 0f, width.toFloat(), bottom - top, paint)
        paint.shader = glassGrain
        canvas.drawRect(0f, 0f, width.toFloat(), bottom - top, paint)
        paint.shader = null
        canvas.restoreToCount(save)
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
