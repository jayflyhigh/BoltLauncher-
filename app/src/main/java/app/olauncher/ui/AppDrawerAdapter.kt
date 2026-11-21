package app.olauncher.ui

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.UserManager
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
import androidx.core.graphics.drawable.toBitmap
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
import app.olauncher.helper.thunderMatchRanges
import app.olauncher.helper.normalizeForSearch
import app.olauncher.helper.appSearchRank
import app.olauncher.helper.hideKeyboard
import app.olauncher.helper.isSystemApp
import app.olauncher.helper.showKeyboard

class AppDrawerAdapter(
    private val flag: Int,
    private val appLabelGravity: Int,
    private val appClickListener: (AppModel) -> Unit,
    private val appInfoListener: (AppModel) -> Unit,
    private val appDeleteListener: (AppModel) -> Unit,
    private val appHideListener: (AppModel, Int) -> Unit,
    private val appRenameListener: (AppModel, String) -> Unit,
    private val canShowSearchActions: () -> Boolean = { false },
    private val searchRenameListener: (AppModel) -> Unit = {},
    private val appPlayStoreListener: (AppModel) -> Unit = {},
    private val resultsChanged: (String) -> Unit = {},
    private val privateSpaceToggleListener: () -> Unit = {},
    private val privateSpaceSettingsListener: () -> Unit = {},
) : RecyclerView.Adapter<RecyclerView.ViewHolder>(), Filterable {

    companion object {
        const val VIEW_TYPE_APP = 0
        const val VIEW_TYPE_PRIVATE_HEADER = 1
        private const val SEARCH_STYLE = "search_style"

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
            previous?.let { currentList.indexOf(it) }?.takeIf { it >= 0 }?.let { notifyItemChanged(it, SEARCH_STYLE) }
            value?.let { currentList.indexOf(it) }?.takeIf { it >= 0 }?.let { notifyItemChanged(it, SEARCH_STYLE) }
        }

    private var actionApp: AppModel? = null

    val actionPosition: Int
        get() = actionApp?.let(currentList::indexOf) ?: RecyclerView.NO_POSITION

    fun closeSearchActions(): Boolean {
        if (actionApp == null) return false
        val position = actionPosition
        actionApp = null
        if (position != RecyclerView.NO_POSITION) notifyItemChanged(position, SEARCH_STYLE)
        return true
    }

    private fun showSearchActions(app: AppModel): Boolean {
        if (!canShowSearchActions()) return false
        if (committedQuery == null || app !in currentList) return true
        if (app != actionApp) {
            closeSearchActions()
            actionApp = app
            notifyItemChanged(actionPosition, SEARCH_STYLE)
        }
        return true
    }

    private fun runSearchAction(app: AppModel, action: () -> Unit) {
        if (app != actionApp || committedQuery == null || app !in currentList) return
        closeSearchActions()
        action()
    }

    private fun getItem(position: Int) = displayedItems[position]
    override fun getItemCount() = displayedItems.size
    private var filterGeneration = 0L
    private var filterJob: Job? = null

    /** Invalidate work even when a mode change keeps exactly the same query. */
    fun cancelFiltering() {
        filterGeneration++
        filterJob?.cancel()
        committedQuery = null
    }

    fun filterApps(query: String) {
        cancelFiltering()
        val generation = filterGeneration
        val thunder = thunderMode
        val trimmed = query.trim()
        requestedQuery = query
        val apps = appsList
        val labels = normalizedLabels
        val previous = displayedItems
        filterJob = iconScope.launch {
            val (items, changes) = withContext(Dispatchers.Default) {
                val items = if (trimmed.isEmpty()) apps else {
                    val matches: (String, String) -> Boolean = if (thunder)
                        ThunderAppLabelMatcher(trimmed)::matches else AppLabelMatcher(trimmed)::matches
                    apps.asSequence().filter { app ->
                        ensureActive()
                        app !is AppModel.PrivateSpaceHeader &&
                            matches(app.appLabel, labels.getValue(app.appLabel))
                    }.map { app ->
                        app to appSearchRank(app.appLabel, trimmed)
                    }.sortedWith(compareBy<Pair<AppModel, Int>> { it.second }
                        .thenBy { it.first }).map { it.first }.toList()
                }
                val changes = if (previous == items) null else DiffUtil.calculateDiff(object : DiffUtil.Callback() {
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
                }, true)
                items to changes
            }
            if (generation != filterGeneration) return@launch
            if (actionApp?.let { it !in items } == true) closeSearchActions()
            displayedItems = items
            committedQuery = query
            changes?.dispatchUpdatesTo(this@AppDrawerAdapter)
            if (thunder) notifyItemRangeChanged(0, itemCount, SEARCH_STYLE)
            resultsChanged(query)
        }
    }

    fun setThunderMode(enabled: Boolean) {
        if (thunderMode == enabled) return
        thunderMode = enabled
        notifyItemRangeChanged(0, itemCount, SEARCH_STYLE)
    }

    private val myUserHandle = android.os.Process.myUserHandle()

    private var normalizedLabels: Map<String, String> = emptyMap()
    var appsList: List<AppModel> = emptyList()
        private set

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

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int, payloads: MutableList<Any>) {
        if (holder is ViewHolder && payloads.isNotEmpty() && payloads.all { it == SEARCH_STYLE }) {
            val app = getItem(position)
            val highlighted = !thunderMode && app == enterHighlightedResult
            holder.bindSearchStyle(app, thunderMode, committedQuery.orEmpty(), highlighted)
            holder.bindSearchActions(app, app == actionApp)
        } else {
            super.onBindViewHolder(holder, position, payloads)
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
                    ::showSearchActions,
                    { action -> runSearchAction(appModel, action) },
                    searchRenameListener,
                    appPlayStoreListener,
                    iconScope,
                    iconCache,
                    thunderMode, committedQuery.orEmpty(), !thunderMode && appModel == enterHighlightedResult,
                    appModel == actionApp
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
        this.appsList = appsList.toList()
        normalizedLabels = appsList.associate { it.appLabel to it.appLabel.normalizeForSearch() }
        filterApps(requestedQuery)
    }

    internal fun launchAnimation(recyclerView: RecyclerView, root: View, app: AppModel): ThunderLaunchAnimation? {
        val holder = recyclerView.findViewHolderForAdapterPosition(0) as? ViewHolder ?: return null
        if (holder.bindingAdapter !== this || holder.bindingAdapterPosition != 0) return null
        return holder.launchAnimation(root, app)
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
        private var renameWatcher: TextWatcher? = null

        private var boundApp: AppModel? = null

        fun launchAnimation(root: View, app: AppModel): ThunderLaunchAnimation? {
            if (boundApp != app) return null
            val icon = copyDisplayedIcon(app)
            if (!binding.appIcon.isShown || binding.appIcon.width == 0) return null
            val origin = IntArray(2)
            root.getLocationInWindow(origin)
            fun boundsOf(view: View): android.graphics.RectF {
                val location = IntArray(2)
                view.getLocationInWindow(location)
                val x = (location[0] - origin[0]).toFloat()
                val y = (location[1] - origin[1]).toFloat()
                return android.graphics.RectF(x, y, x + view.width, y + view.height)
            }
            val title = binding.appTitle
            val titleBounds = boundsOf(title)
            val labelX = titleBounds.left + title.compoundPaddingLeft
            return ThunderLaunchAnimation(
                root.context, icon, boundsOf(binding.root), boundsOf(binding.appIcon),
                title.text.toString(), title.paint,
                androidx.core.widget.TextViewCompat.getTextMetricsParams(title).textDirection
                    ?: android.text.TextDirectionHeuristics.FIRSTSTRONG_LTR,
                labelX, title.width - title.compoundPaddingLeft - title.compoundPaddingRight,
            )
        }

        private fun copyDisplayedIcon(app: AppModel): Drawable? {
            if (boundApp != app) return null
            val icon = binding.appIcon.drawable ?: return null
            return icon.constantState?.newDrawable(binding.root.resources)
                ?: android.graphics.drawable.BitmapDrawable(
                    binding.root.resources,
                    icon.toBitmap(
                        width = binding.appIcon.width.coerceAtLeast(1),
                        height = binding.appIcon.height.coerceAtLeast(1),
                    ),
                )
        }

        fun cancelIconLoad() {
            iconJob?.cancel()
            iconJob = null
        }

        fun bindSearchStyle(appModel: AppModel, thunderMode: Boolean, query: String, enterHighlighted: Boolean) =
            with(binding) {
                root.isSelected = enterHighlighted
                appTitle.setTextColor(if (enterHighlighted) Color.WHITE else
                    androidx.core.content.ContextCompat.getColor(root.context, R.color.bolt_search_label))
                appTitle.setTypeface(null, if (enterHighlighted) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
                // Show indicators in title based on app type and state
                appTitle.text = buildString {
                    append(appModel.appLabel)
                    if (appModel.isNew) append(" ✦")
                }
                if (thunderMode && !enterHighlighted && query.isNotBlank()) {
                    val yellow = androidx.core.content.ContextCompat.getColor(root.context, R.color.bolt_search_yellow)
                    appTitle.text = android.text.SpannableString(appTitle.text).apply {
                        thunderMatchRanges(appModel.appLabel, query).forEach { range ->
                            setSpan(android.text.style.ForegroundColorSpan(yellow), range.first, range.last + 1,
                                android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        }
                    }
                }
            }

        fun bindSearchActions(app: AppModel, show: Boolean) = with(binding) {
            if (show) {
                if (!searchAppActions.isVisible) {
                    val context = root.context
                    searchUninstall.isVisible = app is AppModel.App &&
                        app.user == android.os.Process.myUserHandle() &&
                        !context.isSystemApp(app.appPackage, app.user) &&
                        context.getSystemService(UserManager::class.java)
                            ?.hasUserRestriction(UserManager.DISALLOW_UNINSTALL_APPS) == false &&
                        Intent(Intent.ACTION_DELETE, Uri.fromParts("package", app.appPackage, null))
                            .resolveActivity(context.packageManager) != null
                    searchAppActions.isVisible = true
                    LauncherMotion.reveal(searchAppActions)
                }
            } else if (searchAppActions.isVisible) {
                LauncherMotion.reset(searchAppActions)
                searchAppActions.isVisible = false
            } else {
                return@with
            }
            appTitle.isVisible = !show && !renameLayout.isVisible
            appIcon.isVisible = !show && app.appPackage.isNotEmpty()
            otherProfileIndicator.isVisible = !show && app.user != android.os.Process.myUserHandle()
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
            showSearchActions: (AppModel) -> Boolean,
            runSearchAction: (() -> Unit) -> Unit,
            searchRenameListener: (AppModel) -> Unit,
            appPlayStoreListener: (AppModel) -> Unit,
            iconScope: CoroutineScope,
            iconCache: LruCache<IconKey, Drawable.ConstantState>,
            thunderMode: Boolean,
            query: String,
            enterHighlighted: Boolean,
            showInlineActions: Boolean,
        ) = with(binding) {
            boundApp = appModel
            val identity = when (appModel) {
                is AppModel.App -> appModel.appPackage to appModel.user
                is AppModel.PinnedShortcut -> appModel.identity
                else -> appModel
            }
            root.setTag(R.id.bolt_result_identity, identity)
            (root.parent as? DockResultsView)?.rememberRow(root, identity)
            bindSearchStyle(appModel, thunderMode, query, enterHighlighted)
            appTitle.maxLines = 1
            androidx.core.widget.TextViewCompat.setAutoSizeTextTypeWithDefaults(appTitle, 0)
            appTitle.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 18f)
            appTitle.ellipsize = android.text.TextUtils.TruncateAt.END
            if (appHideLayout.isVisible) LauncherMotion.reset(appHideLayout)
            appHideLayout.visibility = View.GONE
            renameLayout.visibility = View.GONE
            LauncherMotion.reset(searchAppActions)
            searchAppActions.isVisible = false
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
                    val resources = root.resources
                    val appIconSlotSize = appIcon.layoutParams.width
                    iconJob = iconScope.launch {
                        val icon = withContext(Dispatchers.IO) {
                            try {
                                val launcher = context.getSystemService(Context.LAUNCHER_APPS_SERVICE) as LauncherApps
                                launcher.getActivityList(key.packageName, key.user)
                                    .firstOrNull()?.getBadgedIcon(key.density)
                                    ?.let { normalizeSearchIcon(it, resources, appIconSlotSize) }
                            } catch (_: Exception) { null }
                        }
                        ensureActive()
                        icon?.constantState?.let { iconCache.put(key, it) }
                        if (icon != null) appIcon.setImageDrawable(icon)
                    }
                }
            }
            appTitle.gravity = android.view.Gravity.START or android.view.Gravity.CENTER_VERTICAL
            otherProfileIndicator.isVisible = appModel.user != myUserHandle

            root.setOnClickListener { clickListener(appModel) }
            val showAppActions = View.OnLongClickListener {
                if (appModel.appPackage.isNotEmpty() && !showSearchActions(appModel)) {
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
                    if (!appHideLayout.isVisible) {
                        appHideLayout.visibility = View.VISIBLE
                        LauncherMotion.reveal(appHideLayout)
                    }
                    // Only allow renaming non hidden apps
                    appRename.isVisible = flag != Constants.FLAG_HIDDEN_APPS
                }
                true
            }

            root.setOnLongClickListener(showAppActions)

            // Configure rename behavior
            appRename.setOnClickListener {
                if (appModel.appPackage.isNotEmpty()) {
                    etAppRename.hint = getAppName(etAppRename.context, appModel.appPackage, appModel.user)
                    etAppRename.setText(appModel.appLabel)
                    etAppRename.setSelectAllOnFocus(true)
                    renameLayout.visibility = View.VISIBLE
                    LauncherMotion.reset(appHideLayout)
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
            searchRename.setOnClickListener { runSearchAction { searchRenameListener(appModel) } }
            searchPlayStore.setOnClickListener { runSearchAction { appPlayStoreListener(appModel) } }
            searchAppInfo.setOnClickListener { runSearchAction { appInfoListener(appModel) } }
            searchUninstall.setOnClickListener { runSearchAction { appDeleteListener(appModel) } }
            bindSearchActions(appModel, showInlineActions)
            appInfo.setOnClickListener { appInfoListener(appModel) }
            appDelete.setOnClickListener { appDeleteListener(appModel) }
            appMenuClose.setOnClickListener {
                LauncherMotion.reset(appHideLayout)
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
