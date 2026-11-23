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

    class Holder(val binding: AdapterSearchDestinationBinding) : RecyclerView.ViewHolder(binding.root)

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
    styleAliasBadge(target.color)
}

internal fun TextView.styleAliasBadge(color: Int, useSmokedSurface: Boolean = true) {
    val density = resources.displayMetrics.density
    if (Prefs(context).launcherTheme == Prefs.LauncherTheme.WINDOWS_XP) {
        background = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(
                ColorUtils.blendARGB(color, Color.WHITE, 0.84f),
                ColorUtils.blendARGB(color, Color.WHITE, 0.50f),
            ),
        ).apply {
            cornerRadius = 5f * density
            setStroke(density.roundToInt(), ColorUtils.blendARGB(color, 0xFF244C8A.toInt(), 0.55f))
        }
        setTextColor(0xFF101D33.toInt())
        return
    }
    val smokedSurface = context.getColorFromAttr(R.attr.boltSmokedControlColor)
    if (useSmokedSurface && smokedSurface != 0) {
        background = InAppSearch.background(smokedSurface, density).apply {
            setStroke(density.roundToInt(), ColorUtils.blendARGB(color, Color.WHITE, 0.35f))
        }
        setTextColor(Color.WHITE)
    } else {
        background = InAppSearch.background(color, density)
        setTextColor(InAppSearch.foreground(color))
    }
}
