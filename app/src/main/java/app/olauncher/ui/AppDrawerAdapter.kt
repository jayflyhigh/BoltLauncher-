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
import androidx.recyclerview.widget.RecyclerView
import app.olauncher.R
import app.olauncher.data.AppModel
import app.olauncher.data.Constants
import app.olauncher.databinding.AdapterAppDrawerBinding
import app.olauncher.databinding.AdapterPrivateSpaceHeaderBinding
import app.olauncher.helper.AppLabelMatcher
import app.olauncher.helper.ThunderAppLabelMatcher
import app.olauncher.helper.normalizeForSearch
import app.olauncher.helper.appSearchRank
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
    private val searchClick: (app.olauncher.helper.InAppSearch.Target) -> Unit = {},
    private val searchHold: (app.olauncher.helper.InAppSearch.Target) -> Unit = {},
    private val searchAlias: (AppModel) -> String? = { null },
    private val appSearchHold: (AppModel.App) -> Unit = {},
    private val webSuggestionsEnabled: () -> Boolean = { false },
    private val emptyAppResults: (String) -> Unit = {},
    private val resultsChanged: (String) -> Unit = {},
    private val privateSpaceToggleListener: () -> Unit = {},
    private val privateSpaceSettingsListener: () -> Unit = {},
    private val reverseDestinations: Boolean = false,
) : RecyclerView.Adapter<RecyclerView.ViewHolder>(), Filterable {

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

                oldItem is AppModel.SearchSuggestion && newItem is AppModel.SearchSuggestion ->
                    oldItem.appLabel == newItem.appLabel

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
        cancelFiltering()
        iconScope.coroutineContext.cancelChildren()
        super.onDetachedFromRecyclerView(recyclerView)
    }

    @Volatile var searchPackage: String? = null
    @Volatile var searchCommandsOnly = false
    private var suggestions: List<String> = emptyList()
    private var requestedQuery = ""
    var committedQuery: String? = null
        private set
    private var thunderMode = false

    private var displayedItems: List<AppModel> = emptyList()
    val currentList: List<AppModel> get() = displayedItems
    val firstLaunchableResult: AppModel?
        get() = currentList.firstOrNull { it !is AppModel.PrivateSpaceHeader && it.appPackage.isNotEmpty() }

    var enterHighlightedResult: AppModel? = null
        set(value) {
            if (field == value) return
            val previous = field
            field = value
            previous?.let { currentList.indexOf(it) }?.takeIf { it >= 0 }?.let { notifyItemChanged(it) }
            value?.let { currentList.indexOf(it) }?.takeIf { it >= 0 }?.let { notifyItemChanged(it) }
        }

    private fun getItem(position: Int) = displayedItems[position]
    override fun getItemCount() = displayedItems.size
    private var diffJob: Job? = null
    private var diffGeneration = 0L

    /** Unlike AsyncListDiffer's queue, obsolete diffs stop consuming worker time. */
    private fun submitList(items: List<AppModel>, committed: () -> Unit) {
        diffJob?.cancel()
        val generation = ++diffGeneration
        val previous = displayedItems
        diffJob = iconScope.launch {
            val changes = withContext(Dispatchers.Default) {
                if (previous == items) null else DiffUtil.calculateDiff(object : DiffUtil.Callback() {
                    override fun getOldListSize(): Int { ensureActive(); return previous.size }
                    override fun getNewListSize(): Int { ensureActive(); return items.size }
                    override fun areItemsTheSame(old: Int, new: Int): Boolean {
                        ensureActive()
                        return DIFF_CALLBACK.areItemsTheSame(previous[old], items[new])
                    }
                    override fun areContentsTheSame(old: Int, new: Int): Boolean {
                        ensureActive()
                        return DIFF_CALLBACK.areContentsTheSame(previous[old], items[new])
                    }
                }, false)
            }
            if (generation != diffGeneration) return@launch
            displayedItems = items
            changes?.dispatchUpdatesTo(this@AppDrawerAdapter)
            committed()
        }
    }

    private var filterGeneration = 0L
    private var filterJob: Job? = null

    /** Invalidate work even when a mode change keeps exactly the same query. */
    fun cancelFiltering() {
        filterGeneration++
        filterJob?.cancel()
        diffJob?.cancel()
        diffGeneration++
        committedQuery = null
    }

    fun filterApps(query: String) {
        cancelFiltering()
        enterHighlightedResult = null
        val generation = filterGeneration
        val selectedPackage = searchPackage
        val commandsOnly = searchCommandsOnly
        val thunder = thunderMode
        val trimmed = query.trim()
        suggestions = if (selectedPackage != null && trimmed.isNotEmpty())
            (listOf(trimmed) + suggestions.filter { it.contains(trimmed, ignoreCase = true) })
                .distinct().take(7)
        else emptyList()
        requestedQuery = query
        // Resolve aliases and destinations on the UI thread; the worker only sees immutable data.
        val apps = appsList.toList()
        val labels = normalizedLabels
        val aliases = if (trimmed.isNotEmpty() && selectedPackage == null && !commandsOnly)
            apps.associateWith { searchAlias(it) } else emptyMap()
        val destinations = if (commandsOnly && selectedPackage == null)
            apps.filter { it is AppModel.App && searchTarget(it) != null }
                .distinctBy { it.appPackage }.sortedBy { it.appLabel.lowercase(java.util.Locale.ROOT) }
                .let { if (reverseDestinations) it.asReversed() else it }
            else emptyList()
        filterJob = iconScope.launch {
            val items = withContext(Dispatchers.Default) {
                val matcher = AppLabelMatcher(trimmed)
                val thunderMatcher = if (thunder) ThunderAppLabelMatcher(trimmed) else null
                when {
                    commandsOnly && selectedPackage == null -> destinations
                    selectedPackage != null -> apps.filter {
                        it is AppModel.App && it.appPackage == selectedPackage && it.user == myUserHandle
                    }
                    trimmed.isBlank() -> apps
                    else -> apps.filter { app ->
                        ensureActive()
                        app !is AppModel.PrivateSpaceHeader &&
                            ((if (thunder) thunderMatcher!!.matches(app.appLabel, labels[app.appLabel] ?: app.appLabel.normalizeForSearch())
                              else matcher.matches(app.appLabel, labels[app.appLabel] ?: app.appLabel.normalizeForSearch())) ||
                                aliases[app]?.startsWith(trimmed, true) == true)
                    }.map { app ->
                        ensureActive()
                        Triple(app, appSearchRank(app.appLabel, trimmed),
                            app.appLabel.lowercase(java.util.Locale.ROOT))
                    }.sortedWith(compareBy<Triple<AppModel, Int, String>> { it.second }.thenBy { it.third })
                        .map { it.first }
                }
            }
            if (generation != filterGeneration || searchPackage != selectedPackage ||
                searchCommandsOnly != commandsOnly) return@launch
            val webFallback = items.isEmpty() && query.isNotBlank() && webSuggestionsEnabled()
            val displayed = if (thunder) items
                else if (webFallback) listOf(AppModel.SearchSuggestion(trimmed, query))
                else if (selectedPackage != null) items + suggestions.map { AppModel.SearchSuggestion(it, query) }
                else items
            submitList(displayed) {
                if (generation == filterGeneration && searchPackage == selectedPackage && searchCommandsOnly == commandsOnly) {
                    appFilteredList = items.toMutableList()
                    committedQuery = query
                    if (thunder) notifyItemRangeChanged(0, itemCount)
                    resultsChanged(query)
                    if (webFallback) emptyAppResults(query)
                }
            }
        }
    }

    fun showSuggestions(query: String, values: List<String>) {
        if (query != requestedQuery || (searchPackage == null &&
            (!webSuggestionsEnabled() || appFilteredList.isNotEmpty()))) return
        suggestions = (listOf(query.trim()) + values).filter { it.isNotBlank() }.distinct().take(7)
        // The pending filter will include these suggestions. Do not replace its
        // commit with a suggestion-only update before the query is ready.
        if (committedQuery != query) return
        val items = appsList.filter { it is AppModel.App && it.appPackage == searchPackage && it.user == myUserHandle } +
            suggestions.map { AppModel.SearchSuggestion(it, query) }
        val generation = filterGeneration
        submitList(items) {
            if (generation == filterGeneration && query == requestedQuery) resultsChanged(query)
        }
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
            onBindViewHolder(holder, position)
        } else {
            super.onBindViewHolder(holder, position, payloads)
        }
    }

    private val myUserHandle = android.os.Process.myUserHandle()

    private var normalizedLabels: Map<String, String> = emptyMap()
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
                    thunderMode, searchTarget(appModel), searchClick, searchHold, appSearchHold, searchCommandsOnly,
                    requestedQuery, currentList.size == 1, !thunderMode && appModel == enterHighlightedResult
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun getFilter(): Filter = object : Filter() {
        override fun performFiltering(constraint: CharSequence?) = FilterResults()
        override fun publishResults(constraint: CharSequence?, results: FilterResults?) {
            filterApps(constraint?.toString().orEmpty())
        }
    }

    fun setAppList(appsList: MutableList<AppModel>) {
        iconCache.evictAll()
        this.appsList = appsList.toMutableList()
        normalizedLabels = appsList.associate { it.appLabel to it.appLabel.normalizeForSearch() }
        filterApps(requestedQuery)
    }

    fun launchOnlyResult(): Boolean {
        val only = currentList.singleOrNull() ?: return false
        if (only is AppModel.PrivateSpaceHeader || only.appPackage.isEmpty()) return false
        appClickListener(only)
        return true
    }

    fun launchFirstInList() {
        firstLaunchableResult?.let(appClickListener)
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
        private var isSuggestion = false
        private var isSearchDestination = false
        private var renameWatcher: TextWatcher? = null

        fun applyThunderStyle(enabled: Boolean) = with(binding) {
            root.setBackgroundResource(R.drawable.dock_shell)
            appTitle.setTextColor(android.graphics.Color.WHITE)
            searchBadge.isVisible = hasSearchBadge && !enabled
            // Thunder mode uses its existing search-bar indicator; no per-result bolt.
            resultBolt.isVisible = false
            val endPadding = ((if (hasSearchBadge) 76 else 24) * root.resources.displayMetrics.density).toInt()
            appTitle.setPaddingRelative(((if (isSuggestion || isSearchDestination) 24 else 76) * root.resources.displayMetrics.density).toInt(), appTitle.paddingTop, endPadding, appTitle.paddingBottom)
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
            appSearchHold: (AppModel.App) -> Unit,
            searchCommandsOnly: Boolean,
            query: String,
            onlyResult: Boolean,
            enterHighlighted: Boolean,
        ) = with(binding) {
            isSuggestion = appModel is AppModel.SearchSuggestion
            isSearchDestination = searchCommandsOnly
            hasSearchBadge = !thunderMode && target != null && flag == Constants.FLAG_LAUNCH_APP
            searchBadge.isVisible = hasSearchBadge
            if (target != null) {
                searchBadge.text = target.code
                searchBadge.background = app.olauncher.helper.InAppSearch.background(target.color, root.resources.displayMetrics.density)
                searchBadge.setTextColor(app.olauncher.helper.InAppSearch.foreground(target.color))
                androidx.core.widget.TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(searchBadge, 8, 20, 1, android.util.TypedValue.COMPLEX_UNIT_SP)
                searchBadge.contentDescription = "Search in ${appModel.appLabel}, alias ${target.code}"
                searchBadge.setOnClickListener { searchClick(target) }
                searchBadge.setOnLongClickListener { searchHold(target); true }
            } else {
                searchBadge.setOnClickListener(null)
                searchBadge.setOnLongClickListener(null)
            }
            applyThunderStyle(thunderMode)
            if (appModel is AppModel.SearchSuggestion) {
                cancelIconLoad()
                appHideLayout.visibility = View.GONE
                renameLayout.visibility = View.GONE
                appTitle.visibility = View.VISIBLE
                appTitle.text = android.text.SpannableString(appModel.appLabel).apply {
                    val needle = appModel.query.trim()
                    if (needle.isNotEmpty()) {
                        var start = appModel.appLabel.indexOf(needle, ignoreCase = true)
                        while (start >= 0) {
                            setSpan(android.text.style.ForegroundColorSpan(android.graphics.Color.rgb(255, 214, 0)),
                                start, start + needle.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                            start = appModel.appLabel.indexOf(needle, start + needle.length, ignoreCase = true)
                        }
                    }
                }
                appTitle.maxLines = 2
                androidx.core.widget.TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(
                    appTitle, 8, 20, 1, android.util.TypedValue.COMPLEX_UNIT_SP)
                appTitle.ellipsize = null
                appTitle.gravity = android.view.Gravity.START or android.view.Gravity.CENTER_VERTICAL
                appIcon.isVisible = false
                appIcon.setImageDrawable(null)
                otherProfileIndicator.isVisible = false
                root.setOnClickListener { clickListener(appModel) }
                appTitle.setOnClickListener { clickListener(appModel) }
                appIcon.setOnClickListener { clickListener(appModel) }
                root.setOnLongClickListener(null)
                appTitle.setOnLongClickListener(null)
                appIcon.setOnLongClickListener(null)
                return@with
            }
            appTitle.maxLines = 1
            androidx.core.widget.TextViewCompat.setAutoSizeTextTypeWithDefaults(appTitle, 0)
            appTitle.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 20f)
            appTitle.ellipsize = android.text.TextUtils.TruncateAt.END
            appHideLayout.visibility = View.GONE
            renameLayout.visibility = View.GONE
            appTitle.visibility = View.VISIBLE

            cancelIconLoad()
            appIcon.setImageDrawable(null)
            appIcon.isVisible = !searchCommandsOnly && appModel.appPackage.isNotEmpty()
            if (!searchCommandsOnly && appModel.appPackage.isNotEmpty()) {
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
                if (searchCommandsOnly) append("Search in ")
                append(appModel.appLabel)
                if (appModel.isNew) append(" ✦")
            }
            if (thunderMode || enterHighlighted) {
                val title = appTitle.text.toString()
                appTitle.text = android.text.SpannableString(title).apply {
                    val yellow = android.graphics.Color.rgb(255, 214, 0)
                    if (enterHighlighted || (onlyResult && query.isNotBlank())) {
                        setSpan(android.text.style.ForegroundColorSpan(yellow), 0, length,
                            android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    } else {
                        query.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.forEach { term ->
                            var start = title.indexOf(term, ignoreCase = true)
                            while (start >= 0) {
                                setSpan(android.text.style.ForegroundColorSpan(yellow), start, start + term.length,
                                    android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                                start = title.indexOf(term, start + term.length, ignoreCase = true)
                            }
                        }
                    }
                }
            }
            appTitle.gravity = android.view.Gravity.START or android.view.Gravity.CENTER_VERTICAL
            otherProfileIndicator.isVisible = appModel.user != myUserHandle

            appTitle.setOnClickListener { clickListener(appModel) }
            root.setOnClickListener(if (searchCommandsOnly) View.OnClickListener { clickListener(appModel) } else null)

            val canEditAppSearch = flag == Constants.FLAG_LAUNCH_APP &&
                appModel is AppModel.App && appModel.user == myUserHandle
            val editSearch = if (canEditAppSearch) View.OnLongClickListener {
                appSearchHold(appModel as AppModel.App)
                true
            } else null
            root.setOnLongClickListener(editSearch)
            appIcon.setOnLongClickListener(editSearch)
            appTitle.setOnLongClickListener {
                if (editSearch != null) {
                    return@setOnLongClickListener editSearch.onLongClick(it)
                }
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
            appHide.setOnClickListener {
                val position = bindingAdapterPosition
                if (position != RecyclerView.NO_POSITION) appHideListener(appModel, position)
            }
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
