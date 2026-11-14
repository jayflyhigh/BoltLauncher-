package app.olauncher.ui

import android.content.Context
import android.content.pm.LauncherApps
import android.os.UserHandle
import android.graphics.drawable.Drawable
import android.util.LruCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.Filter
import android.widget.Filterable
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import app.olauncher.R
import app.olauncher.data.AppModel
import app.olauncher.data.Constants
import app.olauncher.databinding.AdapterAppDrawerBinding
import app.olauncher.databinding.AdapterPrivateSpaceHeaderBinding
import app.olauncher.helper.appLabelMatches
import app.olauncher.helper.hideKeyboard
import app.olauncher.helper.isSystemApp
import app.olauncher.helper.showKeyboard

class AppDrawerAdapter(
    private var flag: Int,
    private val appLabelGravity: Int,
    private val appClickListener: (AppModel) -> Unit,
    private val appInfoListener: (AppModel) -> Unit,
    private val appDeleteListener: (AppModel) -> Unit,
    private val appHideListener: (AppModel, Int) -> Unit,
    private val appRenameListener: (AppModel, String) -> Unit,
    private val searchTarget: (AppModel) -> app.olauncher.helper.InAppSearch.Target? = { null },
    private val searchAlias: (AppModel) -> String? = { null },
    private val searchClick: (app.olauncher.helper.InAppSearch.Target) -> Unit = {},
    private val searchProbe: (AppModel) -> Unit = {},
    private val searchProbing: (AppModel) -> Boolean = { false },
    private val searchHold: (app.olauncher.helper.InAppSearch.Target) -> Unit = {},
    private val resultsChanged: (String) -> Unit = {},
    private val privateSpaceToggleListener: () -> Unit = {},
    private val privateSpaceSettingsListener: () -> Unit = {},
) : ListAdapter<AppModel, RecyclerView.ViewHolder>(DIFF_CALLBACK), Filterable {

    companion object {
        private const val THUNDER_STYLE = "thunder_style"
        const val VIEW_TYPE_APP = 0
        const val VIEW_TYPE_PRIVATE_HEADER = 1

        val DIFF_CALLBACK = object : DiffUtil.ItemCallback<AppModel>() {
            override fun areItemsTheSame(oldItem: AppModel, newItem: AppModel): Boolean = when {
                oldItem is AppModel.App && newItem is AppModel.App ->
                    oldItem.appPackage == newItem.appPackage && oldItem.user == newItem.user

                oldItem is AppModel.PinnedShortcut && newItem is AppModel.PinnedShortcut ->
                    oldItem.identity == newItem.identity

                oldItem is AppModel.PrivateSpaceHeader && newItem is AppModel.PrivateSpaceHeader -> true

                else -> false
            }

            override fun areContentsTheSame(oldItem: AppModel, newItem: AppModel): Boolean =
                oldItem == newItem
        }
    }

    private val iconScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val iconCache = LruCache<IconKey, Drawable.ConstantState>(128)
    private data class IconKey(val packageName: String, val user: UserHandle, val density: Int)

    override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
        if (holder is ViewHolder) holder.cancelIconLoad()
        super.onViewRecycled(holder)
    }

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        iconScope.coroutineContext.cancelChildren()
        super.onDetachedFromRecyclerView(recyclerView)
    }

    @Volatile var searchPackage: String? = null
    private var requestedQuery = ""
    var committedQuery: String? = null
        private set
    private var thunderMode = false

    fun filterApps(query: String) {
        requestedQuery = query
        committedQuery = null
        appFilter.filter(query)
    }

    fun setThunderMode(enabled: Boolean) {
        if (thunderMode == enabled) return
        thunderMode = enabled
        notifyItemRangeChanged(0, itemCount, THUNDER_STYLE)
    }

    override fun onBindViewHolder(
        holder: RecyclerView.ViewHolder,
        position: Int,
        payloads: MutableList<Any>,
    ) {
        if (holder is ViewHolder && payloads.isNotEmpty() && payloads.all { it == THUNDER_STYLE }) {
            holder.applyThunderStyle(thunderMode)
        } else {
            super.onBindViewHolder(holder, position, payloads)
        }
    }

    private val appFilter = createAppFilter()
    private val myUserHandle = android.os.Process.myUserHandle()

    var appsList: MutableList<AppModel> = mutableListOf()
    var appFilteredList: MutableList<AppModel> = mutableListOf()

    override fun getItemViewType(position: Int): Int {
        return when (getItem(position)) {
            is AppModel.PrivateSpaceHeader -> VIEW_TYPE_PRIVATE_HEADER
            else -> VIEW_TYPE_APP
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return when (viewType) {
            VIEW_TYPE_PRIVATE_HEADER -> PrivateSpaceHeaderViewHolder(
                AdapterPrivateSpaceHeaderBinding.inflate(
                    LayoutInflater.from(parent.context),
                    parent,
                    false
                )
            )

            else -> ViewHolder(
                AdapterAppDrawerBinding.inflate(
                    LayoutInflater.from(parent.context),
                    parent,
                    false
                )
            )
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        try {
            if (position == RecyclerView.NO_POSITION) return
            val appModel = getItem(position)
            when (holder) {
                is PrivateSpaceHeaderViewHolder -> {
                    holder.bind(
                        appLabelGravity,
                        privateSpaceToggleListener,
                        privateSpaceSettingsListener,
                    )
                }

                is ViewHolder -> holder.bind(
                    flag,
                    appLabelGravity,
                    myUserHandle,
                    appModel,
                    appClickListener,
                    appDeleteListener,
                    appInfoListener,
                    appHideListener,
                    appRenameListener,
                    iconScope,
                    iconCache,
                    thunderMode, searchTarget(appModel), searchClick, searchHold, searchProbe, searchProbing(appModel)
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun getFilter(): Filter = this.appFilter

    private fun createAppFilter(): Filter {
        return object : Filter() {
            override fun performFiltering(charSearch: CharSequence?): FilterResults {
                val query = charSearch?.toString()?.trim().orEmpty()
                val appFilteredList = if (searchPackage != null) appsList.filter { it is AppModel.App && it.appPackage == searchPackage && it.user == android.os.Process.myUserHandle() }.toMutableList()
                else if (query.isBlank()) appsList.toMutableList()
                else appsList.filter { app ->
                    app !is AppModel.PrivateSpaceHeader && (appLabelMatches(app.appLabel, query) || searchAlias(app)?.startsWith(query, true) == true)
                }.sortedWith(compareBy<AppModel> {
                    when {
                        it.appLabel.equals(query, true) -> 0
                        it.appLabel.startsWith(query, true) -> 1
                        it.appLabel.split(Regex("\\s+")).any { word -> word.startsWith(query, true) } -> 2
                        else -> 3
                    }
                }.thenBy { it.appLabel.lowercase(java.util.Locale.ROOT) }).toMutableList()

                val filterResults = FilterResults()
                filterResults.values = appFilteredList
                return filterResults
            }

            @Suppress("UNCHECKED_CAST")
            override fun publishResults(constraint: CharSequence?, results: FilterResults?) {
                val query = constraint?.toString().orEmpty()
                if (query != requestedQuery) return
                results?.values?.let {
                    val items = it as MutableList<AppModel>
                    appFilteredList = items
                    submitList(items) {
                        if (query == requestedQuery) {
                            committedQuery = query
                            resultsChanged(query)
                        }
                    }
                }
            }
        }
    }

    fun setAppList(appsList: MutableList<AppModel>) {
        this.appsList = appsList
        this.appFilteredList = appsList
        submitList(appsList)
    }

    fun launchOnlyResult(): Boolean {
        val only = currentList.singleOrNull() ?: return false
        if (only is AppModel.PrivateSpaceHeader || only.appPackage.isEmpty()) return false
        appClickListener(only)
        return true
    }

    fun launchFirstInList() {
        val first = currentList.firstOrNull { it !is AppModel.PrivateSpaceHeader && it.appPackage.isNotEmpty() }
        if (first != null) appClickListener(first)
    }

    class PrivateSpaceHeaderViewHolder(private val binding: AdapterPrivateSpaceHeaderBinding) :
        RecyclerView.ViewHolder(binding.root) {
        fun bind(
            appLabelGravity: Int,
            toggleListener: () -> Unit,
            settingsListener: () -> Unit,
        ) = with(binding) {
            privateSpaceTitle.gravity = appLabelGravity
            privateSpaceTitle.setOnClickListener { toggleListener() }
            privateSpaceTitle.setOnLongClickListener {
                settingsListener()
                true
            }
        }
    }

    private class ViewHolder(private val binding: AdapterAppDrawerBinding) :
        RecyclerView.ViewHolder(binding.root) {
        private var iconJob: Job? = null
        private var hasSearchBadge = false
        private var renameWatcher: TextWatcher? = null

        fun applyThunderStyle(enabled: Boolean) = with(binding) {
            root.setBackgroundResource(R.drawable.dock_shell)
            appTitle.setTextColor(android.graphics.Color.WHITE)
            resultBolt.isVisible = enabled && !hasSearchBadge
            val endPadding = ((if (enabled || hasSearchBadge) 76 else 24) * root.resources.displayMetrics.density).toInt()
            appTitle.setPaddingRelative(appTitle.paddingStart, appTitle.paddingTop, endPadding, appTitle.paddingBottom)
        }

        fun cancelIconLoad() {
            iconJob?.cancel()
            iconJob = null
        }

        fun bind(
            flag: Int,
            appLabelGravity: Int,
            myUserHandle: UserHandle,
            appModel: AppModel,
            clickListener: (AppModel) -> Unit,
            appDeleteListener: (AppModel) -> Unit,
            appInfoListener: (AppModel) -> Unit,
            appHideListener: (AppModel, Int) -> Unit,
            appRenameListener: (AppModel, String) -> Unit,
            iconScope: CoroutineScope,
            iconCache: LruCache<IconKey, Drawable.ConstantState>,
            thunderMode: Boolean,
            target: app.olauncher.helper.InAppSearch.Target?,
            searchClick: (app.olauncher.helper.InAppSearch.Target) -> Unit,
            searchHold: (app.olauncher.helper.InAppSearch.Target) -> Unit,
            searchProbe: (AppModel) -> Unit,
            probing: Boolean,
        ) = with(binding) {
            hasSearchBadge = flag == Constants.FLAG_LAUNCH_APP && appModel is AppModel.App
            searchBadge.isEnabled = !probing
            searchBadge.alpha = if (probing) .6f else 1f
            searchBadge.isVisible = hasSearchBadge
            if (target != null) {
                searchBadge.text = appModel.appLabel.take(1).uppercase(java.util.Locale.ROOT)
                searchBadge.background = app.olauncher.helper.InAppSearch.background(target.color, root.resources.displayMetrics.density)
                searchBadge.setTextColor(app.olauncher.helper.InAppSearch.foreground(target.color))
                searchBadge.contentDescription = "Search in ${appModel.appLabel}. Hold to change alias"
                searchBadge.setOnClickListener { searchClick(target) }
                searchBadge.setOnLongClickListener { searchHold(target); true }
            } else {
                searchBadge.text = if (probing) "…" else "+"
                searchBadge.setTextColor(android.graphics.Color.WHITE)
                searchBadge.setBackgroundResource(R.drawable.dock_key)
                searchBadge.contentDescription = if (probing) "Checking search support for ${appModel.appLabel}"
                    else "Check whether ${appModel.appLabel} supports search"
                searchBadge.setOnClickListener { searchProbe(appModel) }
                searchBadge.setOnLongClickListener(null)
            }
            applyThunderStyle(thunderMode)
            appHideLayout.visibility = View.GONE
            renameLayout.visibility = View.GONE
            appTitle.visibility = View.VISIBLE

            cancelIconLoad()
            appIcon.setImageDrawable(null)
            appIcon.isVisible = appModel.appPackage.isNotEmpty()
            if (appModel.appPackage.isNotEmpty()) {
                val key = IconKey(appModel.appPackage, appModel.user, root.resources.displayMetrics.densityDpi)
                val cached = iconCache.get(key)
                appIcon.setImageDrawable(cached?.newDrawable(root.resources)
                    ?: root.context.packageManager.defaultActivityIcon)
                if (cached == null) {
                    val context = root.context.applicationContext
                    iconJob = iconScope.launch {
                        val icon = withContext(Dispatchers.IO) {
                            try {
                                val launcher = context.getSystemService(Context.LAUNCHER_APPS_SERVICE) as LauncherApps
                                launcher.getActivityList(key.packageName, key.user)
                                    .firstOrNull()?.getBadgedIcon(key.density)
                            } catch (_: Exception) { null }
                        }
                        ensureActive()
                        icon?.constantState?.let { iconCache.put(key, it) }
                        if (icon != null) appIcon.setImageDrawable(icon)
                    }
                }
            }
            appIcon.setOnClickListener { clickListener(appModel) }
            // Show indicators in title based on app type and state
            appTitle.text = buildString {
                append(appModel.appLabel)
                if (appModel.isNew) append(" ✦")
            }
            appTitle.gravity = android.view.Gravity.START or android.view.Gravity.CENTER_VERTICAL
            otherProfileIndicator.isVisible = appModel.user != myUserHandle

            appTitle.setOnClickListener { clickListener(appModel) }

            appTitle.setOnLongClickListener {
                if (appModel.appPackage.isNotEmpty()) {
                    appDelete.alpha = when (
                        appModel is AppModel.PinnedShortcut || !root.context.isSystemApp(appModel.appPackage, appModel.user)
                    ) {
                        true -> 1.0f
                        false -> 0.5f
                    }
                    appDelete.text = if (appModel is AppModel.PinnedShortcut)
                        root.context.getString(R.string.remove)
                    else
                        root.context.getString(R.string.delete)
                    appHide.text = if (flag == Constants.FLAG_HIDDEN_APPS)
                        root.context.getString(R.string.adapter_show)
                    else
                        root.context.getString(R.string.adapter_hide)
                    appTitle.visibility = View.INVISIBLE
                    appHide.alpha = when (appModel is AppModel.PinnedShortcut) {
                        true -> 0.5f
                        false -> 1.0f
                    }
                    appHideLayout.visibility = View.VISIBLE
                    // Only allow renaming non hidden apps
                    appRename.isVisible = flag != Constants.FLAG_HIDDEN_APPS
                }
                true
            }

            // Configure rename behavior
            appRename.setOnClickListener {
                if (appModel.appPackage.isNotEmpty()) {
                    etAppRename.hint = getAppName(etAppRename.context, appModel.appPackage, appModel.user)
                    etAppRename.setText(appModel.appLabel)
                    etAppRename.setSelectAllOnFocus(true)
                    renameLayout.visibility = View.VISIBLE
                    appHideLayout.visibility = View.GONE
                    etAppRename.showKeyboard()
                    etAppRename.imeOptions = EditorInfo.IME_ACTION_DONE
                }
            }
            etAppRename.onFocusChangeListener = View.OnFocusChangeListener { _, hasFocus ->
                appTitle.visibility = if (hasFocus) View.INVISIBLE else View.VISIBLE
            }
            renameWatcher?.let { etAppRename.removeTextChangedListener(it) }
            renameWatcher = object : TextWatcher {
                override fun afterTextChanged(s: Editable?) {
                    etAppRename.hint = appModel.appLabel
                }

                override fun beforeTextChanged(
                    s: CharSequence?,
                    start: Int,
                    count: Int,
                    after: Int,
                ) {
                }

                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    etAppRename.hint = ""
                }
            }
            etAppRename.addTextChangedListener(renameWatcher)
            etAppRename.setOnEditorActionListener { _, actionCode, _ ->
                if (actionCode == EditorInfo.IME_ACTION_DONE) {
                    val renameLabel = etAppRename.text.toString().trim()
                    if (renameLabel.isNotBlank() && appModel.appPackage.isNotBlank()) {
                        appRenameListener(appModel, renameLabel)
                        renameLayout.visibility = View.GONE
                    }
                    true
                }
                false
            }
            tvSaveRename.setOnClickListener {
                etAppRename.hideKeyboard()
                val renameLabel = etAppRename.text.toString().trim()
                if (renameLabel.isNotBlank() && appModel.appPackage.isNotBlank()) {
                    appRenameListener(appModel, renameLabel)
                    renameLayout.visibility = View.GONE
                } else {
                    appRenameListener(
                        appModel,
                        getAppName(etAppRename.context, appModel.appPackage, appModel.user)
                    )
                    renameLayout.visibility = View.GONE
                }
            }
            appInfo.setOnClickListener { appInfoListener(appModel) }
            appDelete.setOnClickListener { appDeleteListener(appModel) }
            appMenuClose.setOnClickListener {
                appHideLayout.visibility = View.GONE
                appTitle.visibility = View.VISIBLE
            }
            appRenameClose.setOnClickListener {
                renameLayout.visibility = View.GONE
                appTitle.visibility = View.VISIBLE
            }
            appHide.setOnClickListener { appHideListener(appModel, bindingAdapterPosition) }
        }

        private fun getAppName(context: Context, appPackage: String, user: UserHandle): String {
            val launcherApps = context.getSystemService(Context.LAUNCHER_APPS_SERVICE) as LauncherApps
            return try {
                val activityList = launcherApps.getActivityList(appPackage, user)
                if (activityList.isNotEmpty()) {
                    activityList.first().label.toString()
                } else {
                    val packageManager = context.packageManager
                    packageManager.getApplicationLabel(
                        packageManager.getApplicationInfo(appPackage, 0)
                    ).toString()
                }
            } catch (_: Exception) {
                "" // As a fallback, display an empty string.
            }
        }
    }
}
