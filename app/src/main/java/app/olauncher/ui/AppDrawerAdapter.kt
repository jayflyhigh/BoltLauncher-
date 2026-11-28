package app.olauncher.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.UserManager
import android.content.pm.LauncherApps
import android.os.UserHandle
import android.graphics.drawable.Drawable
import android.graphics.drawable.LayerDrawable
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
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.Filter
import android.widget.Filterable
import androidx.annotation.DrawableRes
import androidx.appcompat.content.res.AppCompatResources
import androidx.appcompat.widget.AppCompatImageButton
import androidx.core.graphics.drawable.toBitmap
import androidx.core.view.doOnLayout
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import app.olauncher.R
import app.olauncher.data.Prefs
import app.olauncher.helper.getColorFromAttr
import app.olauncher.data.AppModel
import app.olauncher.data.Constants
import app.olauncher.databinding.AdapterAppDrawerBinding
import app.olauncher.databinding.AdapterPrivateSpaceHeaderBinding
import app.olauncher.helper.AppLabelMatcher
import app.olauncher.helper.ThunderAppLabelMatcher
import app.olauncher.helper.thunderMatchRanges
import app.olauncher.helper.thunderNextLetters
import app.olauncher.helper.normalizeForSearch
import app.olauncher.helper.SearchTypoMatcher
import app.olauncher.helper.appSearchRank
import app.olauncher.helper.normalizeDisplayedName
import app.olauncher.helper.hideKeyboard
import app.olauncher.helper.isSystemApp
import app.olauncher.helper.showKeyboard
import kotlin.math.roundToInt

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
    private val appAliasListener: (AppModel.App) -> Unit = {},
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
                    oldItem.appPackage == newItem.appPackage && oldItem.user == newItem.user &&
                        oldItem.activityClassName == newItem.activityClassName

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
    private data class IconKey(
        val packageName: String,
        val user: UserHandle,
        val density: Int,
        val activityClass: String?,
        val shortcutId: String?,
    )

    override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
        if (holder is ViewHolder) holder.cancelIconLoad()
        super.onViewRecycled(holder)
    }

    // ConcatAdapter also detaches children when the best-match section moves.
    fun dispose() {
        cancelFiltering()
        iconScope.coroutineContext.cancelChildren()
    }

    private var requestedQuery = ""
    private var displayedQuery = ""
    var committedQuery: String? = null
        private set
    private var thunderMode = false

    private var displayedItems: List<AppModel> = emptyList()
    val currentList: List<AppModel> get() = displayedItems
    val firstLaunchableResult: AppModel?
        get() = currentList.firstOrNull { it.canLaunchFromSearch() }

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
        // The visible row may still be valid while a new filter is in flight.
        // Shortcut actions themselves still require a committed query.
        if (app !in currentList) return true
        if (app != actionApp) {
            closeSearchActions()
            actionApp = app
            showThunderHints(false)
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
    private var nextLetters: Map<AppModel, Char> = emptyMap()
    private var showNextLetters = false

    fun showThunderHints(show: Boolean) {
        if (showNextLetters == show) return
        showNextLetters = show
        currentList.forEachIndexed { index, app ->
            if (app in nextLetters) notifyItemChanged(index, SEARCH_STYLE)
        }
    }

    private var filterGeneration = 0L
    private var filterJob: Job? = null

    /** Invalidate work even when a mode change keeps exactly the same query. */
    fun cancelFiltering() {
        showThunderHints(false)
        filterGeneration++
        filterJob?.cancel()
        committedQuery = null
    }

    fun filterApps(query: String) {
        cancelFiltering()
        val generation = filterGeneration
        val thunder = thunderMode
        val trimmed = query.trim()
        val exactName = query.normalizeDisplayedName()
            .takeIf { !thunder && flag == Constants.FLAG_LAUNCH_APP }
        requestedQuery = query
        val apps = appsList
        val labelIndex = searchLabels
        val previous = displayedItems
        filterJob = iconScope.launch {
            val (items, changes, fuzzy) = withContext(Dispatchers.Default) {
                val ranked = if (trimmed.isEmpty()) emptyList() else {
                    val labels = labelIndex.value
                    val matches: (String, String) -> Boolean = if (thunder)
                        ThunderAppLabelMatcher(trimmed)::matches else AppLabelMatcher(trimmed)::matches
                    val typoMatcher = SearchTypoMatcher(trimmed).takeUnless { thunder }
                    apps.asSequence().mapNotNull { app ->
                        ensureActive()
                        if (app is AppModel.PrivateSpaceHeader) return@mapNotNull null
                        val label = labels.getValue(app.appLabel)
                        val original = app.originalLabel
                        val originalNormalized = if (original == app.appLabel) null else labels.getValue(original).normalized
                        when {
                            exactName != null && label.displayedName.equals(exactName, ignoreCase = true) -> app to -1
                            matches(app.appLabel, label.normalized) -> app to appSearchRank(app.appLabel, trimmed)
                            originalNormalized != null && matches(original, originalNormalized) ->
                                app to (4 + appSearchRank(original, trimmed))
                            typoMatcher?.matches(label.normalized) == true -> app to 8
                            originalNormalized != null && typoMatcher?.matches(originalNormalized) == true -> app to 9
                            else -> null
                        }
                    }.sortedWith(compareBy<Pair<AppModel, Int>> { it.second }
                        .thenBy { it.first }).toList()
                }
                val items = if (trimmed.isEmpty()) apps else ranked.map { it.first }
                val fuzzy = ranked.filter { it.second >= 8 }.mapTo(mutableSetOf()) { it.first }
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
                Triple(items, changes, fuzzy)
            }
            if (generation != filterGeneration) return@launch
            if (actionApp?.let { it !in items } == true) closeSearchActions()
            val previousTypoMatches = typoMatches
            typoMatches = fuzzy
            displayedItems = items
            committedQuery = query
            val previousQuery = displayedQuery
            displayedQuery = query
            changes?.dispatchUpdatesTo(this@AppDrawerAdapter)
            if (thunder && previousQuery != query) notifyItemRangeChanged(0, itemCount, SEARCH_STYLE)
            else if (previousTypoMatches != fuzzy) {
                items.forEachIndexed { index, app ->
                    if ((app in previousTypoMatches) != (app in fuzzy)) notifyItemChanged(index, SEARCH_STYLE)
                }
            }
            nextLetters = emptyMap()
            resultsChanged(query)
            if (!thunder || items.size < 2 || trimmed.isEmpty()) return@launch
            val hints = withContext(Dispatchers.Default) {
                thunderNextLetters(
                    query, apps.filterNot { it is AppModel.PrivateSpaceHeader }, items.toSet(),
                    AppModel::appLabel, AppModel::originalLabel,
                    { labelIndex.value.getValue(it).normalized },
                    { it.canLaunchFromSearch() },
                    { ensureActive() },
                )
            }
            if (generation != filterGeneration) return@launch
            nextLetters = hints
            if (showNextLetters) {
                items.forEachIndexed { index, app ->
                    if (app in hints) notifyItemChanged(index, SEARCH_STYLE)
                }
            }
        }
    }

    fun setThunderMode(enabled: Boolean) {
        if (thunderMode == enabled) return
        showThunderHints(false)
        thunderMode = enabled
        notifyItemRangeChanged(0, itemCount, SEARCH_STYLE)
    }

    private val myUserHandle = android.os.Process.myUserHandle()

    private var typoMatches: Set<AppModel> = emptySet()
    val hasDirectMatches: Boolean get() = itemCount > typoMatches.size
    var showTypoHints = false
        set(value) {
            if (field == value) return
            field = value
            if (typoMatches.isEmpty()) return
            displayedItems.forEachIndexed { index, app ->
                if (app in typoMatches) notifyItemChanged(index, SEARCH_STYLE)
            }
        }

    private data class SearchLabel(val normalized: String, val displayedName: String)

    private var searchLabels = lazy { emptyMap<String, SearchLabel>() }
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
            holder.bindSearchStyle(app, thunderMode, displayedQuery, highlighted, animateHighlight = true)
            holder.bindSearchActions(app, app == actionApp)
            holder.bindTypoHint(showTypoHints && app in typoMatches && app != actionApp)
            holder.bindThunderHint(nextLetters[app].takeIf { showNextLetters && actionApp == null })
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

                is ViewHolder -> {
                    holder.bind(
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
                        appAliasListener,
                        iconScope,
                        iconCache,
                        thunderMode, displayedQuery, !thunderMode && appModel == enterHighlightedResult,
                        appModel == actionApp
                    )
                    holder.bindTypoHint(showTypoHints && appModel in typoMatches && appModel != actionApp)
                    holder.bindThunderHint(nextLetters[appModel].takeIf { showNextLetters && actionApp == null })
                }
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
        val snapshot = appsList.toList()
        this.appsList = snapshot
        // Build the index on the search dispatcher only when a nonblank query needs it.
        searchLabels = lazy {
            snapshot.asSequence().flatMap { sequenceOf(it.appLabel, it.originalLabel) }
                .distinct().associateWith { SearchLabel(it.normalizeForSearch(), it.normalizeDisplayedName()) }
        }
        filterApps(requestedQuery)
    }

    internal fun launchAnimation(recyclerView: RecyclerView, root: View, app: AppModel): ThunderLaunchAnimation? {
        val holder = recyclerView.findViewHolderForAdapterPosition(0) as? ViewHolder ?: return null
        if (holder.bindingAdapter !== this || holder.bindingAdapterPosition != 0) return null
        return holder.launchAnimation(root, app)
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
        private val wallpaperColors = WallpaperThemeColors.from(binding.root.context)

        init {
            wallpaperColors?.let {
                binding.root.background = it.rowBackground(binding.root.context)
            }
            LauncherMotion.configureResultPress(binding.root)
            binding.appTitle.typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
        }

        private val theme = Prefs(binding.root.context).launcherTheme
        private val paperNotebook = theme == Prefs.LauncherTheme.PAPER_NOTEBOOK
        private val eInk = theme == Prefs.LauncherTheme.E_INK
        private val cozyQuest = theme == Prefs.LauncherTheme.COZY_QUEST
        private val allowsTwoLineNames = paperNotebook || eInk || cozyQuest

        init {
            if (cozyQuest) binding.root.setBackgroundResource(R.drawable.bolt_cozy_selected)
            if (allowsTwoLineNames) {
                binding.root.minimumHeight = binding.root.resources.getDimensionPixelSize(R.dimen.bolt_search_result_height)
                binding.root.layoutParams.height = android.view.ViewGroup.LayoutParams.WRAP_CONTENT
                binding.appTitle.layoutParams.height = android.view.ViewGroup.LayoutParams.WRAP_CONTENT
                if (eInk || cozyQuest) {
                    (binding.appTitle.layoutParams as android.widget.FrameLayout.LayoutParams).gravity =
                        android.view.Gravity.CENTER_VERTICAL
                }
            }
        }

        private val selectedText = binding.root.context.getColorFromAttr(R.attr.boltSelectedRowTextColor)
        private val normalText = binding.root.context.getColorFromAttr(R.attr.boltResultTextColor)
        private val thunderMatchText = binding.root.context.getColorFromAttr(R.attr.boltThunderMatchTextColor)
        private var iconJob: Job? = null

        init {
            val context = binding.root.context
            if (theme == Prefs.LauncherTheme.LIQUID_GLASS) {
                binding.appIcon.apply {
                    setBackgroundResource(R.drawable.liquid_glass_icon_frame)
                    val inset = (3 * resources.displayMetrics.density).toInt()
                    setPadding(inset, inset, inset, inset)
                }
            }
        }

        private fun AppCompatImageButton.setCozyActionIcon(@DrawableRes icon: Int) {
            val glyph = AppCompatResources.getDrawable(context, icon) ?: return
            val density = resources.displayMetrics.density
            val size = (32 * density).roundToInt()
            val padding = (2 * density).roundToInt()
            setPadding(padding, 0, padding, 0)
            setImageDrawable(LayerDrawable(arrayOf(glyph)).apply {
                setLayerSize(0, size, size)
                setLayerGravity(0, Gravity.CENTER)
            })
        }

        private fun configureActionIcons() {
            val context = binding.root.context
            with(binding) {
                when (theme) {
                    Prefs.LauncherTheme.COZY_QUEST -> {
                        searchRename.setCozyActionIcon(R.drawable.bolt_cozy_rename)
                        searchPlayStore.setCozyActionIcon(R.drawable.bolt_cozy_store)
                        searchAddAlias.setCozyActionIcon(R.drawable.bolt_cozy_alias)
                        searchAppInfo.setCozyActionIcon(R.drawable.bolt_cozy_info)
                        searchUninstall.setCozyActionIcon(R.drawable.bolt_cozy_delete)
                    }
                    Prefs.LauncherTheme.WINDOWS_XP -> {
                        searchRename.setXpIcon(R.drawable.xp_rename)
                        searchPlayStore.setXpIcon(R.drawable.xp_store)
                        searchAddAlias.setXpIcon(R.drawable.xp_alias)
                        searchAppInfo.setXpIcon(R.drawable.xp_info)
                        searchUninstall.setXpIcon(R.drawable.xp_delete)
                    }
                    Prefs.LauncherTheme.LIQUID_GLASS, Prefs.LauncherTheme.WII_U,
                    Prefs.LauncherTheme.SEATTLE_SEAHAWKS -> {
                        searchRename.setAppActionTileIcon(R.drawable.ic_rename)
                        searchPlayStore.setAppActionTileIcon(R.drawable.bolt_play_store_icon)
                        searchAddAlias.setAppActionTileIcon(R.drawable.bolt_add_alias_icon)
                        searchAppInfo.setAppActionTileIcon(R.drawable.ic_info)
                        searchUninstall.setAppActionTileIcon(R.drawable.ic_delete)
                    }
                    else -> {
                        searchPlayStore.setImageResource(R.drawable.bolt_google_play_icon)
                        val tint = wallpaperColors?.let {
                            android.content.res.ColorStateList(
                                arrayOf(intArrayOf(android.R.attr.state_selected), intArrayOf()),
                                intArrayOf(selectedText, it.accent),
                            )
                        } ?: AppCompatResources.getColorStateList(context, R.color.bolt_app_action_tint)
                        for (button in listOf(searchRename, searchPlayStore, searchAddAlias, searchAppInfo)) {
                            button.imageTintList = tint
                        }
                        searchUninstall.imageTintList = if (eInk || wallpaperColors != null) tint else
                            AppCompatResources.getColorStateList(context, R.color.bolt_app_uninstall_tint)
                    }
                }
            }
        }
        private var actionIconsConfigured = false
        private var renameWatcher: TextWatcher? = null

        private var boundApp: AppModel? = null

        fun launchAnimation(root: View, app: AppModel): ThunderLaunchAnimation? {
            if (boundApp != app) return null
            val source = binding.appIcon
            if (!source.isShown || source.width == 0 || source.height == 0) return null
            val icon = copyDisplayedIcon(app) ?: return null
            val origin = IntArray(2)
            val location = IntArray(2)
            root.getLocationInWindow(origin)
            source.getLocationInWindow(location)
            val x = (location[0] - origin[0]).toFloat()
            val y = (location[1] - origin[1]).toFloat()
            return ThunderLaunchAnimation(source, icon,
                android.graphics.RectF(x, y, x + source.width, y + source.height),
                binding.appTitle.takeIf { paperNotebook && it.isShown },
                wallpaperColors?.accent ?: source.context.getColorFromAttr(R.attr.boltThunderPulseColor),
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

        fun bindThunderHint(letter: Char?) = with(binding) {
            thunderNextLetter.isVisible = letter != null
            if (letter != null) {
                thunderNextLetter.text = root.context.getString(R.string.bolt_thunder_next_letter, letter.toString())
            }
            appTitle.trailingHintWidth = if (letter == null) 0 else
                thunderNextLetter.paint.measureText(thunderNextLetter.text.toString()).roundToInt()
            if (letter != null) {
                root.doOnLayout {
                    if (thunderNextLetter.isVisible) {
                        thunderNextLetter.translationY = (appTitle.top + appTitle.baseline -
                            thunderNextLetter.top - thunderNextLetter.baseline).toFloat()
                    }
                }
            }
        }

        fun bindTypoHint(show: Boolean) = binding.updateTypoHint(show)

        fun bindSearchStyle(
            appModel: AppModel,
            thunderMode: Boolean,
            query: String,
            enterHighlighted: Boolean,
            animateHighlight: Boolean = false,
        ) = with(binding) {
            LauncherMotion.setRowHighlight(root, enterHighlighted, animate = animateHighlight)
            appTitle.markerSelected = enterHighlighted
            val textColor = if (enterHighlighted) selectedText else normalText
            if (appTitle.currentTextColor != textColor) appTitle.setTextColor(textColor)
            androidx.core.view.ViewCompat.setStateDescription(root,
                if (enterHighlighted) root.resources.getString(R.string.bolt_enter_target) else null)
            val title = if (appModel.isNew) "${appModel.appLabel} ✦" else appModel.appLabel
            if (thunderMode && !enterHighlighted && query.isNotBlank()) {
                appTitle.text = android.text.SpannableString(title).apply {
                    thunderMatchRanges(appModel.appLabel, query).forEach { range ->
                        setSpan(android.text.style.ForegroundColorSpan(thunderMatchText), range.first, range.last + 1,
                            android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        setSpan(android.text.style.StyleSpan(android.graphics.Typeface.BOLD), range.first, range.last + 1,
                            android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    }
                }
            } else if (appTitle.text != title) {
                appTitle.text = title
            }
        }

        fun bindSearchActions(app: AppModel, show: Boolean) = with(binding) {
            searchAddAlias.isVisible = app is AppModel.App &&
                app.user == android.os.Process.myUserHandle()
            if (show) {
                if (!searchAppActions.isVisible) {
                    if (!actionIconsConfigured) {
                        configureActionIcons()
                        actionIconsConfigured = true
                    }
                    val context = root.context
                    searchUninstall.isVisible = app is AppModel.App &&
                        app.user == android.os.Process.myUserHandle() &&
                        !context.isSystemApp(app.appPackage, app.user) &&
                        context.getSystemService(UserManager::class.java)
                            ?.hasUserRestriction(UserManager.DISALLOW_UNINSTALL_APPS) == false &&
                        Intent(Intent.ACTION_DELETE, Uri.fromParts("package", app.appPackage, null))
                            .resolveActivity(context.packageManager) != null
                    searchAppActions.isVisible = true
                    LauncherMotion.reveal(searchAppActions, riseDp = if (cozyQuest) 0f else 4f)
                }
            } else if (searchAppActions.isVisible) {
                LauncherMotion.reset(searchAppActions)
                searchAppActions.isVisible = false
            } else {
                return@with
            }
            appTitle.visibility = when {
                renameLayout.isVisible -> View.GONE
                !show -> View.VISIBLE
                allowsTwoLineNames -> View.INVISIBLE
                else -> View.GONE
            }
            appIcon.isVisible = app.appPackage.isNotEmpty()
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
            appAliasListener: (AppModel.App) -> Unit,
            iconScope: CoroutineScope,
            iconCache: LruCache<IconKey, Drawable.ConstantState>,
            thunderMode: Boolean,
            query: String,
            enterHighlighted: Boolean,
            showInlineActions: Boolean,
        ) = with(binding) {
            boundApp = appModel
            val identity = when (appModel) {
                is AppModel.App -> Triple(appModel.appPackage, appModel.user, appModel.activityClassName)
                is AppModel.PinnedShortcut -> appModel.identity
                else -> appModel
            }
            root.setTag(R.id.bolt_result_identity, identity)
            (root.parent as? DockResultsView)?.rememberRow(root, identity)
            bindSearchStyle(appModel, thunderMode, query, enterHighlighted)
            appTitle.maxLines = if (allowsTwoLineNames) 2 else 1
            androidx.core.widget.TextViewCompat.setAutoSizeTextTypeWithDefaults(appTitle, 0)
            appTitle.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 16f)
            appTitle.ellipsize = android.text.TextUtils.TruncateAt.END
            if (appHideLayout.isVisible) LauncherMotion.reset(appHideLayout)
            appHideLayout.visibility = View.GONE
            renameLayout.visibility = View.GONE
            LauncherMotion.reset(searchAppActions)
            searchAppActions.isVisible = false
            appTitle.visibility = View.VISIBLE

            cancelIconLoad()
            appIcon.isVisible = appModel.appPackage.isNotEmpty()
            if (appModel.appPackage.isNotEmpty()) {
                val key = IconKey(
                    appModel.appPackage,
                    appModel.user,
                    root.resources.displayMetrics.densityDpi,
                    (appModel as? AppModel.App)?.activityClassName,
                    (appModel as? AppModel.PinnedShortcut)?.shortcutId,
                )
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
                                val shortcutIcon = key.shortcutId?.let { shortcutId ->
                                    if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.N_MR1 ||
                                        !launcher.hasShortcutHostPermission()
                                    ) return@let null
                                    val query = LauncherApps.ShortcutQuery()
                                        .setPackage(key.packageName)
                                        .setShortcutIds(listOf(shortcutId))
                                        .setQueryFlags(LauncherApps.ShortcutQuery.FLAG_MATCH_PINNED)
                                    try {
                                        launcher.getShortcuts(query, key.user)?.firstOrNull()?.let {
                                            launcher.getShortcutBadgedIconDrawable(it, key.density)
                                        }
                                    } catch (_: SecurityException) {
                                        null
                                    }
                                }
                                val icon = shortcutIcon ?: launcher.getActivityList(key.packageName, key.user).let { activities ->
                                    val activity = activities.firstOrNull { it.componentName.className == key.activityClass }
                                        ?: activities.firstOrNull()
                                    activity?.getBadgedIcon(key.density)
                                }
                                icon?.let { normalizeSearchIcon(it, resources, appIconSlotSize) }
                            } catch (_: Exception) { null }
                        }
                        ensureActive()
                        icon?.constantState?.let { iconCache.put(key, it) }
                        if (icon != null) appIcon.setImageDrawable(icon)
                    }
                }
            } else {
                appIcon.setImageDrawable(null)
            }
            appTitle.gravity = android.view.Gravity.START or android.view.Gravity.CENTER_VERTICAL
            otherProfileIndicator.isVisible = appModel.user != myUserHandle

            root.setOnClickListener { clickListener(appModel) }
            val showAppActions = View.OnLongClickListener {
                if (appModel.appPackage.isNotEmpty() && !showSearchActions(appModel)) {
                    appDelete.text = if (appModel is AppModel.PinnedShortcut)
                        root.context.getString(R.string.remove)
                    else
                        root.context.getString(R.string.delete)
                    appHide.text = if (flag == Constants.FLAG_HIDDEN_APPS)
                        root.context.getString(R.string.adapter_show)
                    else
                        root.context.getString(R.string.adapter_hide)
                    appTitle.visibility = View.INVISIBLE
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
            val aliasApp = (appModel as? AppModel.App)?.takeIf { it.user == myUserHandle }
            searchAddAlias.setOnClickListener {
                aliasApp?.let { runSearchAction { appAliasListener(it) } }
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

private fun AppModel.canLaunchFromSearch(): Boolean = this !is AppModel.PrivateSpaceHeader && appPackage.isNotEmpty()
