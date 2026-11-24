package app.olauncher.ui

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import app.olauncher.R
import app.olauncher.data.Prefs
import app.olauncher.databinding.AdapterSearchDestinationBinding
import app.olauncher.helper.InAppSearch
import app.olauncher.helper.getColorFromAttr
import kotlin.math.roundToInt

internal class SearchDestinationAdapter(
    private val launch: (InAppSearch.Target) -> Unit,
    private val activate: (InAppSearch.Target) -> Unit,
    private val edit: (InAppSearch.Target) -> Unit,
) : ListAdapter<InAppSearch.Target, SearchDestinationAdapter.Holder>(Diff) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
        Holder(AdapterSearchDestinationBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val target = getItem(position)
        with(holder.binding) {
            root.setTag(R.id.bolt_result_identity, "search:${target.packageName}")
            destinationTitle.text = root.context.getString(R.string.bolt_search_in_app, target.appLabel)
            destinationBadge.bindAliasBadge(target, R.string.bolt_activate_alias)
            root.setOnClickListener { launch(target) }
            destinationBadge.setOnClickListener { activate(target) }
            destinationBadge.setOnLongClickListener { edit(target); true }
        }
    }

    class Holder(val binding: AdapterSearchDestinationBinding) : RecyclerView.ViewHolder(binding.root) {
        init {
            WallpaperThemeColors.from(binding.root.context)?.let {
                binding.root.background = it.rowBackground(binding.root.context)
            }
            if (Prefs(binding.root.context).launcherTheme == Prefs.LauncherTheme.E_INK) {
                binding.destinationBadge.isDuplicateParentStateEnabled = true
                binding.destinationBadge.layoutParams.height =
                    binding.root.resources.getDimensionPixelSize(R.dimen.bolt_eink_chip_height)
            }
        }
    }

    private object Diff : DiffUtil.ItemCallback<InAppSearch.Target>() {
        override fun areItemsTheSame(oldItem: InAppSearch.Target, newItem: InAppSearch.Target): Boolean =
            oldItem.packageName == newItem.packageName

        override fun areContentsTheSame(oldItem: InAppSearch.Target, newItem: InAppSearch.Target): Boolean =
            oldItem == newItem
    }
}

internal fun TextView.bindAliasBadge(target: InAppSearch.Target, description: Int) {
    if (tag == target) return
    tag = target
    text = target.code
    contentDescription = context.getString(description, target.code)
    styleAliasBadge(target.color, target.usesPresetColor)
}

internal fun TextView.styleAliasBadge(color: Int, usesPresetColor: Boolean = false) {
    val density = resources.displayMetrics.density
    val stroke = density.roundToInt().coerceAtLeast(1)
    setTextColor(Color.WHITE)
    val theme = Prefs(context).launcherTheme
    when (theme) {
        Prefs.LauncherTheme.E_INK -> setBackgroundResource(R.drawable.bolt_eink_alias)
        Prefs.LauncherTheme.DEFAULT, Prefs.LauncherTheme.PAPER_NOTEBOOK, Prefs.LauncherTheme.COZY_QUEST -> {
            val opaqueColor = if (theme == Prefs.LauncherTheme.COZY_QUEST && usesPresetColor) {
                androidx.core.content.ContextCompat.getColor(context, R.color.bolt_cozy_chip)
            } else color or Color.BLACK
            val fill = if (ColorUtils.calculateContrast(Color.WHITE, opaqueColor) >= 4.5) {
                opaqueColor
            } else {
                ColorUtils.blendARGB(opaqueColor, Color.BLACK, 0.55f)
            }
            background = InAppSearch.background(fill, density)
        }
        Prefs.LauncherTheme.SMOKED_GLASS -> {
            val surface = context.getColorFromAttr(R.attr.boltSmokedControlColor)
            background = InAppSearch.background(surface, density).apply {
                setStroke(stroke, ColorUtils.blendARGB(color, Color.WHITE, 0.35f))
            }
        }
        Prefs.LauncherTheme.FROSTED_GLASS -> {
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(0xFF364D68.toInt(), 0xFF1D314A.toInt()),
            ).apply {
                cornerRadius = 13f * density
                setStroke(stroke, ColorUtils.blendARGB(color, 0xFFB7DFFF.toInt(), 0.55f))
            }
        }
        Prefs.LauncherTheme.LIQUID_GLASS -> {
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(
                    context.getColorFromAttr(R.attr.boltSelectedRowColor),
                    context.getColorFromAttr(R.attr.boltDockColor),
                ),
            ).apply {
                cornerRadius = 10f * density
                val rim = context.getColorFromAttr(R.attr.boltSelectedRowOutlineColor)
                setStroke(stroke, ColorUtils.blendARGB(rim, color, 0.35f))
            }
        }
        Prefs.LauncherTheme.WINDOWS_XP -> {
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(
                    ColorUtils.blendARGB(color, 0xFF244C8A.toInt(), 0.86f),
                    ColorUtils.blendARGB(color, 0xFF101D33.toInt(), 0.86f),
                ),
            ).apply {
                cornerRadius = 5f * density
                setStroke(stroke, ColorUtils.blendARGB(color, 0xFF244C8A.toInt(), 0.55f))
            }
        }
        Prefs.LauncherTheme.WII_U -> {
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(
                    ColorUtils.blendARGB(color, 0xFF004B69.toInt(), 0.78f),
                    ColorUtils.blendARGB(color, 0xFF00344D.toInt(), 0.86f),
                ),
            ).apply {
                cornerRadius = 9f * density
                setStroke(stroke, ColorUtils.blendARGB(color, 0xFF75DDFF.toInt(), 0.58f))
            }
        }
        Prefs.LauncherTheme.SEATTLE_SEAHAWKS -> {
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(
                    0xFF002244.toInt(),
                    0xFF001532.toInt(),
                ),
            ).apply {
                cornerRadius = 6f * density
                setStroke(stroke, 0xFF69BE28.toInt())
            }
        }
    }
}
