package app.olauncher.ui

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.os.Build
import android.util.DisplayMetrics
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import app.olauncher.R
import kotlin.math.roundToInt

/** Draws the exact source rectangle that will be sampled. */
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

internal fun createWallpaperCropDialog(activity: Activity, bitmap: Bitmap, applyCrop: (Rect) -> Unit): AlertDialog {
    val builder = AlertDialog.Builder(activity)
    val context = builder.context
    val bounds = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        activity.windowManager.currentWindowMetrics.bounds
    } else {
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        activity.windowManager.defaultDisplay.getRealMetrics(metrics)
        Rect(0, 0, metrics.widthPixels, metrics.heightPixels)
    }
    val aspect = bounds.width().coerceAtLeast(1).toFloat() / bounds.height().coerceAtLeast(1)
    val preview = WallpaperCropPreview(context, bitmap, aspect)
    val density = context.resources.displayMetrics.density
    val padding = (20 * density).roundToInt()
    val screenHeight = context.resources.configuration.screenHeightDp
    val previewHeight = ((screenHeight * 0.36f).coerceIn(100f, 280f) * density).roundToInt()
    val controls = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(padding, 0, padding, 0)
        addView(TextView(context).apply { setText(R.string.bolt_wallpaper_crop_description) })
        addView(preview, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, previewHeight))
    }
    fun slider(label: Int, initial: Int, update: (Float) -> Unit) {
        val sliderId = View.generateViewId()
        controls.addView(TextView(context).apply {
            setText(label)
            labelFor = sliderId
        })
        controls.addView(SeekBar(context).apply {
            id = sliderId
            max = 100
            progress = initial
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar, value: Int, fromUser: Boolean) {
                    update(value / 100f)
                }
                override fun onStartTrackingTouch(bar: SeekBar) = Unit
                override fun onStopTrackingTouch(bar: SeekBar) = Unit
            })
        })
    }
    slider(R.string.bolt_wallpaper_crop_zoom, 0) { preview.zoom = 1f + 4f * it }
    slider(R.string.bolt_wallpaper_crop_horizontal, 50) { preview.horizontal = it }
    slider(R.string.bolt_wallpaper_crop_vertical, 50) { preview.vertical = it }
    return builder.setTitle(R.string.bolt_wallpaper_crop_title)
        .setView(ScrollView(context).apply { addView(controls) })
        .setNegativeButton(android.R.string.cancel, null)
        .setPositiveButton(R.string.bolt_wallpaper_crop_apply) { _, _ -> applyCrop(preview.crop()) }
        .create()
}
