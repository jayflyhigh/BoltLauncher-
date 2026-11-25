package app.olauncher.ui

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.os.Build
import android.util.DisplayMetrics
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import app.olauncher.R
import kotlin.math.roundToInt

/** Draws the exact source rectangle that will be applied. */
private class WallpaperCropPreview(
    context: Context,
    private val bitmap: Bitmap,
    private val aspect: Float,
) : View(context) {
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val destination = Rect()
    private val centeredWidth = minOf(bitmap.width.toFloat(), bitmap.height * aspect)
    private val centeredHeight = centeredWidth / aspect

    var zoom = 1f
        set(value) {
            field = value
            invalidate()
        }

    var horizontal = 0.5f
        set(value) {
            field = value
            invalidate()
        }

    var vertical = 0.5f
        set(value) {
            field = value
            invalidate()
        }

    var onDrag: ((Float, Float) -> Unit)? = null

    private val dragDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(event: MotionEvent): Boolean {
            if (!destination.contains(event.x.toInt(), event.y.toInt())) return false
            val crop = crop()
            if (crop.width() == bitmap.width && crop.height() == bitmap.height) return false
            parent.requestDisallowInterceptTouchEvent(true)
            return true
        }

        override fun onScroll(
            start: MotionEvent?,
            current: MotionEvent,
            distanceX: Float,
            distanceY: Float,
        ): Boolean {
            val crop = crop()
            val horizontalTravel = bitmap.width - crop.width()
            val verticalTravel = bitmap.height - crop.height()
            // Convert the gesture from preview pixels to a fraction of the available crop travel.
            if (horizontalTravel > 0) {
                val delta = distanceX * crop.width() / destination.width() / horizontalTravel
                horizontal = (horizontal + delta).coerceIn(0f, 1f)
            }
            if (verticalTravel > 0) {
                val delta = distanceY * crop.height() / destination.height() / verticalTravel
                vertical = (vertical + delta).coerceIn(0f, 1f)
            }
            onDrag?.invoke(horizontal, vertical)
            return true
        }
    }).apply { setIsLongpressEnabled(false) }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val handled = dragDetector.onTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
            parent.requestDisallowInterceptTouchEvent(false)
        }
        return handled || event.actionMasked != MotionEvent.ACTION_DOWN
    }

    fun crop(): Rect {
        val width = (centeredWidth / zoom).roundToInt().coerceIn(1, bitmap.width)
        val height = (centeredHeight / zoom).roundToInt().coerceIn(1, bitmap.height)
        val left = ((bitmap.width - width) * horizontal).roundToInt()
        val top = ((bitmap.height - height) * vertical).roundToInt()
        return Rect(left, top, left + width, top + height)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val previewWidth = minOf(width.toFloat(), height * aspect).roundToInt()
        val previewHeight = (previewWidth / aspect).roundToInt()
        val left = (width - previewWidth) / 2
        val top = (height - previewHeight) / 2
        destination.set(left, top, left + previewWidth, top + previewHeight)
        canvas.drawBitmap(bitmap, crop(), destination, paint)
    }
}

internal fun wallpaperScreenBounds(activity: Activity): Rect =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        activity.windowManager.maximumWindowMetrics.bounds
    } else {
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        activity.windowManager.defaultDisplay.getRealMetrics(metrics)
        Rect(0, 0, metrics.widthPixels, metrics.heightPixels)
    }

internal fun createWallpaperCropDialog(
    activity: Activity,
    bitmap: Bitmap,
    bounds: Rect,
    setWallpaper: Boolean,
    applyCrop: (Rect) -> Unit,
): AlertDialog {
    val builder = AlertDialog.Builder(activity)
    val context = builder.context
    val aspect = bounds.width().coerceAtLeast(1).toFloat() / bounds.height().coerceAtLeast(1)
    val preview = WallpaperCropPreview(context, bitmap, aspect)
    val density = context.resources.displayMetrics.density
    val padding = (20 * density).roundToInt()
    val screenHeight = context.resources.configuration.screenHeightDp
    val previewHeight = ((screenHeight * 0.36f).coerceIn(100f, 280f) * density).roundToInt()
    val description = if (setWallpaper) R.string.bolt_set_wallpaper_description else R.string.bolt_wallpaper_crop_description
    val applyLabel = if (setWallpaper) R.string.bolt_set_wallpaper else R.string.bolt_wallpaper_crop_apply
    val controls = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(padding, 0, padding, 0)
        addView(TextView(context).apply { setText(description) })
        addView(preview, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, previewHeight))
    }
    fun slider(label: Int, initial: Int, update: (Float) -> Unit): SeekBar {
        val sliderId = View.generateViewId()
        controls.addView(TextView(context).apply {
            setText(label)
            labelFor = sliderId
        })
        val slider = SeekBar(context).apply {
            id = sliderId
            max = 100
            progress = initial
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar, value: Int, fromUser: Boolean) {
                    if (fromUser) update(value / 100f)
                }
                override fun onStartTrackingTouch(bar: SeekBar) = Unit
                override fun onStopTrackingTouch(bar: SeekBar) = Unit
            })
        }
        controls.addView(slider)
        return slider
    }
    slider(R.string.bolt_wallpaper_crop_zoom, 0) { preview.zoom = 1f + 4f * it }
    val horizontalSlider = slider(R.string.bolt_wallpaper_crop_horizontal, 50) { preview.horizontal = it }
    val verticalSlider = slider(R.string.bolt_wallpaper_crop_vertical, 50) { preview.vertical = it }
    preview.onDrag = { horizontal, vertical ->
        horizontalSlider.progress = (horizontal * horizontalSlider.max).roundToInt()
        verticalSlider.progress = (vertical * verticalSlider.max).roundToInt()
    }
    return builder.setTitle(R.string.bolt_wallpaper_crop_title)
        .setView(ScrollView(context).apply { addView(controls) })
        .setNegativeButton(android.R.string.cancel, null)
        .setPositiveButton(applyLabel) { _, _ -> applyCrop(preview.crop()) }
        .create()
}
