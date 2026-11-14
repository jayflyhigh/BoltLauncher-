package app.olauncher.ui

import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.text.Spannable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.InputMethodManager
import android.widget.TextView
import androidx.appcompat.widget.SearchView
import androidx.fragment.app.activityViewModels
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.RecyclerView.Recycler
import app.olauncher.MainViewModel
import app.olauncher.R
import app.olauncher.data.AppModel
import app.olauncher.data.Constants
import app.olauncher.data.Prefs
import app.olauncher.databinding.FragmentAppDrawerBinding
import app.olauncher.helper.deletePinnedShortcut
import app.olauncher.helper.hideKeyboard
import app.olauncher.helper.isEinkDisplay
import app.olauncher.helper.isSystemApp
import app.olauncher.helper.openAppInfo
import app.olauncher.helper.openSearch
import app.olauncher.helper.openUrl
import app.olauncher.helper.showKeyboard
import app.olauncher.helper.showToast
import app.olauncher.helper.uninstall

class AppDrawerFragment : BaseFragment() {

    private lateinit var prefs: Prefs
    private lateinit var adapter: AppDrawerAdapter
    private lateinit var linearLayoutManager: LinearLayoutManager
    private var searchTextView: TextView? = null
    private var cachedIsCjkKeyboard: Boolean? = null

    private val isHomePage: Boolean
        get() = findNavController().currentDestination?.id == R.id.mainFragment

    private var thunderMode = false
    private var pendingAutoLaunch: Runnable? = null
    private var lastAutoLaunchedQuery: String? = null
    private var dockExpanded = false
    private var dockAnimator: android.animation.ValueAnimator? = null

    private var flag = Constants.FLAG_LAUNCH_APP
    private var canRename = false
    private var currentAppList: List<AppModel>? = null
    private var currentPrivateSpaceApps: List<AppModel>? = null
    private var currentPrivateSpaceLocked: Boolean = true
    private var currentPrivateSpaceAvailable: Boolean = false

    private val viewModel: MainViewModel by activityViewModels()
    private var _binding: FragmentAppDrawerBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        dockExpanded = false
        thunderMode = false
        _binding = FragmentAppDrawerBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        prefs = Prefs(requireContext())
        arguments?.let {
            flag = it.getInt(Constants.Key.FLAG, Constants.FLAG_LAUNCH_APP)
            canRename = it.getBoolean(Constants.Key.RENAME, false)
        }

        initViews()
        initSearch()
        initAdapter()
        initObservers()
        initClickListeners()
        initDock()
        initHomeSwipe()
        applyDockInsets()
        binding.recyclerView.visibility = if (isHomePage) View.INVISIBLE else View.VISIBLE
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner,
            object : androidx.activity.OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (!collapseSearch() && !isHomePage) closeDrawer()
                }
            })
    }

    private fun applyDockInsets() {
        val root = binding.root
        val initialLeft = root.paddingLeft
        val initialTop = root.paddingTop
        val initialRight = root.paddingRight
        val initialBottom = root.paddingBottom
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.navigationBars())
            val keyboard = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.ime())
            val keyboardVisible = insets.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime())
            // Keep a compact 2dp gap while typing, and the usual 12dp above navigation.
            val dockParams = binding.dock.layoutParams as android.view.ViewGroup.MarginLayoutParams
            val bottomMargin = ((if (keyboardVisible) 2 else 12) * resources.displayMetrics.density).toInt()
            if (dockParams.bottomMargin != bottomMargin) {
                dockParams.bottomMargin = bottomMargin
                binding.dock.layoutParams = dockParams
            }
            // Reserve the system area beneath the dock in both gesture and button navigation.
            // A hidden status bar still leaves the physical camera cutout unsafe.
            val safe = insets.getInsetsIgnoringVisibility(
                androidx.core.view.WindowInsetsCompat.Type.statusBars() or
                    androidx.core.view.WindowInsetsCompat.Type.displayCutout())
            view.setPadding(initialLeft + maxOf(bars.left, safe.left), initialTop + safe.top,
                initialRight + maxOf(bars.right, safe.right),
                initialBottom + maxOf(bars.bottom, keyboard.bottom))
            insets
        }
        root.post { androidx.core.view.ViewCompat.requestApplyInsets(root) }
    }

    private fun initHomeSwipe() {
        val threshold = 64f * resources.displayMetrics.density
        var notificationsOpened = false
        val detector = android.view.GestureDetector(requireContext(),
            object : android.view.GestureDetector.SimpleOnGestureListener() {
                override fun onDown(event: android.view.MotionEvent): Boolean {
                    notificationsOpened = false
                    return true
                }

                override fun onScroll(
                    start: android.view.MotionEvent?,
                    current: android.view.MotionEvent,
                    distanceX: Float,
                    distanceY: Float,
                ): Boolean {
                    if (start == null || notificationsOpened || !isHomePage || dockExpanded) return false
                    val dy = current.y - start.y
                    val dx = current.x - start.x
                    if (dy > threshold && dy > kotlin.math.abs(dx)) {
                        notificationsOpened = true
                        app.olauncher.helper.expandNotificationDrawer(requireContext())
                    }
                    return true
                }
            })
        detector.setIsLongpressEnabled(false)
        binding.root.setOnTouchListener { _, event ->
            if (isHomePage && !dockExpanded) detector.onTouchEvent(event) else false
        }
    }

    private fun initDock() {
        binding.dockHome.setOnClickListener {
            if (isHomePage) resetHomeSearch()
            else {
                binding.search.hideKeyboard()
                findNavController().popBackStack(R.id.mainFragment, false)
            }
        }
        binding.dockSearch.setOnClickListener {
            if (dockExpanded) resetHomeSearch()
            else {
                setThunderMode(false)
                setDockExpanded(true)
            }
        }
        binding.dockBolt.setOnClickListener {
            setThunderMode(true)
            setDockExpanded(true)
        }
        binding.dockContacts.setOnClickListener {
            try { startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW,
                android.provider.ContactsContract.Contacts.CONTENT_URI)) }
            catch (_: android.content.ActivityNotFoundException) { }
        }
        binding.dockSettings.setOnClickListener {
            findNavController().navigate(if (isHomePage)
                R.id.action_mainFragment_to_settingsFragment
                else R.id.action_appListFragment_to_settingsFragment2)
        }
        binding.dock.post {
            if (_binding == null) return@post
            val params = binding.search.layoutParams as android.widget.FrameLayout.LayoutParams
            params.marginStart = binding.dockHome.right + (6 * resources.displayMetrics.density).toInt()
            binding.search.layoutParams = params
        }
        setThunderMode(false)
        searchTextView?.gravity = android.view.Gravity.START or android.view.Gravity.CENTER_VERTICAL
    }

    private fun setThunderMode(enabled: Boolean) {
        cancelAutoLaunch()
        thunderMode = enabled
        lastAutoLaunchedQuery = null
        adapter.setThunderMode(enabled)
        val foreground = if (enabled) android.graphics.Color.BLACK else android.graphics.Color.WHITE
        binding.search.setBackgroundResource(if (enabled) R.drawable.dock_yellow else R.drawable.dock_key)
        binding.dockSearch.setBackgroundResource(if (enabled) R.drawable.dock_yellow else R.drawable.dock_key)
        binding.dockSearch.setImageResource(if (enabled) R.drawable.dock_bolt else R.drawable.dock_search)
        binding.dockSearch.imageTintList = android.content.res.ColorStateList.valueOf(foreground)
        searchTextView?.apply {
            setTextColor(foreground)
            setHintTextColor(if (enabled) android.graphics.Color.BLACK else 0xFFBDBDBD.toInt())
            // The theme supplies a stateful cursor on Android 7–9 as well.
            isActivated = enabled
            isCursorVisible = true
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                textCursorDrawable = androidx.appcompat.content.res.AppCompatResources.getDrawable(
                    context, R.drawable.bolt_search_cursor)?.apply {
                        state = if (enabled) intArrayOf(android.R.attr.state_activated) else intArrayOf()
                    }
            }
            refreshDrawableState()
        }
    }

    private fun cancelAutoLaunch() {
        pendingAutoLaunch?.let { _binding?.root?.removeCallbacks(it) }
        pendingAutoLaunch = null
    }

    private fun scheduleAutoLaunch(query: String) {
        cancelAutoLaunch()
        if (!thunderMode || !dockExpanded || query.isBlank() ||
            query == lastAutoLaunchedQuery || flag != Constants.FLAG_LAUNCH_APP ||
            adapter.itemCount != 1) return
        val task = object : Runnable {
            override fun run() {
                val b = _binding ?: return
                if (!thunderMode || !dockExpanded || b.search.query.toString() != query ||
                    adapter.committedQuery != query ||
                    !viewLifecycleOwner.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) {
                    pendingAutoLaunch = null
                    return
                }
                if (isSearchComposing()) {
                    b.root.postDelayed(this, 500L)
                    return
                }
                pendingAutoLaunch = null
                // Recheck the committed list after the typing pause, including result count.
                if (adapter.launchOnlyResult()) lastAutoLaunchedQuery = query
            }
        }
        pendingAutoLaunch = task
        binding.root.postDelayed(task, 500L)
    }

    fun collapseSearch(): Boolean {
        if (!dockExpanded || _binding == null) return false
        cancelAutoLaunch()
        val insets = androidx.core.view.ViewCompat.getRootWindowInsets(binding.root)
        if (insets?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime()) == true) {
            binding.search.hideKeyboard()
        } else {
            resetHomeSearch()
        }
        return true
    }

    fun resetHomeSearch(animate: Boolean = true) {
        if (_binding == null) return
        setThunderMode(false)
        binding.search.setQuery("", false)
        setDockExpanded(false, animate = animate)
    }

    private fun setDockExpanded(expanded: Boolean, animate: Boolean = true) {
        val opening = expanded && !dockExpanded
        dockExpanded = expanded
        binding.recyclerView.visibility = if (expanded || !isHomePage) View.VISIBLE else View.INVISIBLE
        if (opening && animate) binding.recyclerView.animateSearchResults(binding.search.query.toString(), force = true)
        dockAnimator?.cancel()
        val start = (binding.dockSearch.tag as? Float) ?: 0f
        val target = if (expanded) 1f else 0f
        binding.search.visibility = View.VISIBLE
        if (!expanded) {
            binding.search.hideKeyboard()
            binding.search.clearFocus()
        }
        dockAnimator = android.animation.ValueAnimator.ofFloat(start, target).apply {
            duration = if (animate) (220 * kotlin.math.abs(target - start)).toLong() else 0L
            interpolator = android.view.animation.PathInterpolator(0.2f, 0f, 0f, 1f)
            addUpdateListener {
                val progress = it.animatedValue as Float
                val b = _binding ?: return@addUpdateListener
                b.dockSearch.tag = progress
                b.dockSearch.translationX = (b.dockHome.x - b.dockSearch.left) * progress
                b.dockSearch.translationZ = 8f
                b.dockHome.alpha = 1f - progress
                b.dockHome.isEnabled = !expanded
                listOf(b.dockBolt, b.dockContacts, b.dockSettings).forEach { key ->
                    key.alpha = 1f - progress
                    key.isEnabled = !expanded
                }
                b.search.alpha = progress
                b.search.translationX = -b.search.width * (1f - progress)
                b.search.clipBounds = android.graphics.Rect(
                    (b.search.width * (1f - progress)).toInt(), 0,
                    b.search.width, b.search.height)
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    if (_binding == null || dockExpanded != expanded) return
                    if (expanded) binding.search.showKeyboard()
                    else binding.search.visibility = View.INVISIBLE
                }
            })
            start()
        }
    }

    private fun initViews() {
        if (flag == Constants.FLAG_HIDDEN_APPS)
            binding.search.queryHint = getString(R.string.hidden_apps)
        else if (flag in Constants.FLAG_SET_HOME_APP_1..Constants.FLAG_SET_CALENDAR_APP)
            binding.search.queryHint = "Please select an app"
        try {
            searchTextView = binding.search.findViewById(R.id.search_src_text)
            searchTextView?.gravity = prefs.appLabelAlignment
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun initSearch() {
        binding.search.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?): Boolean {
                cancelAutoLaunch()
                if (adapter.committedQuery == binding.search.query.toString()) adapter.launchFirstInList()
                return true
            }

            override fun onQueryTextChange(newText: String): Boolean {
                try {
                    cancelAutoLaunch()
                    if (newText != lastAutoLaunchedQuery) lastAutoLaunchedQuery = null
                    adapter.filterApps(newText)
                    binding.appRename.visibility =
                        if (canRename && newText.isNotBlank()) View.VISIBLE else View.GONE
                    return true
                } catch (e: Exception) {
                    e.printStackTrace()
                }
                return false
            }
        })
    }

    private fun isSearchComposing(): Boolean {
        val text = searchTextView?.text
        if (text !is Spannable) return false
        val start = BaseInputConnection.getComposingSpanStart(text)
        val end = BaseInputConnection.getComposingSpanEnd(text)
        if (start !in 0 until end) return false
        return isCjkKeyboard()
    }

    private fun isCjkKeyboard(): Boolean {
        cachedIsCjkKeyboard?.let { return it }
        val result = try {
            val imm = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            val subtype = imm.currentInputMethodSubtype
            val language = when {
                subtype == null -> ""
                subtype.languageTag.isNotEmpty() -> subtype.languageTag // e.g. "zh-CN", "ja-JP", "en-US"
                else -> subtype.locale // deprecated fallback, e.g. "zh_CN"
            }
            language.startsWith("zh") || language.startsWith("ja") || language.startsWith("ko")
        } catch (e: Exception) {
            false
        }
        cachedIsCjkKeyboard = result
        return result
    }

    private fun initAdapter() {
        adapter = AppDrawerAdapter(
            flag,
            prefs.appLabelAlignment,
            appClickListener = { appModel ->
                cancelAutoLaunch()
                viewModel.selectedApp(appModel, flag)
                if (flag == Constants.FLAG_LAUNCH_APP || flag == Constants.FLAG_HIDDEN_APPS)
                    findNavController().popBackStack(R.id.mainFragment, false)
                else
                    closeDrawer()
            },
            appInfoListener = {
                openAppInfo(
                    requireContext(),
                    it.user,
                    it.appPackage
                )
                findNavController().popBackStack(R.id.mainFragment, false)
            },
            appDeleteListener = { appModel ->
                when (appModel) {
                    is AppModel.PrivateSpaceHeader -> {}
                    is AppModel.PinnedShortcut ->
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N_MR1) {
                            requireContext().deletePinnedShortcut(
                                packageName = appModel.appPackage,
                                shortcutIdToDelete = appModel.shortcutId,
                                user = appModel.user,
                            )
                        }

                    is AppModel.App -> {
                        if (appModel.user != Process.myUserHandle()) {
                            openAppInfo(requireContext(), appModel.user, appModel.appPackage)
                        } else if (requireContext().isSystemApp(appModel.appPackage, appModel.user)) {
                            requireContext().showToast(getString(R.string.system_app_cannot_delete))
                            openAppInfo(requireContext(), appModel.user, appModel.appPackage)
                        } else {
                            requireContext().uninstall(appModel.appPackage)
                        }
                    }
                }
                viewModel.getAppList()
            },
            appHideListener = { appModel, position ->
                if (appModel is AppModel.PinnedShortcut) {
                    requireContext().showToast("Hiding pinned shortcuts is not supported")
                    return@AppDrawerAdapter
                }
                adapter.appFilteredList.removeAt(position)
                adapter.notifyItemRemoved(position)
                adapter.appsList.remove(appModel)

                val newSet = mutableSetOf<String>()
                newSet.addAll(prefs.hiddenApps)
                if (flag == Constants.FLAG_HIDDEN_APPS)
                    newSet.remove(appModel.appPackage + "|" + appModel.user.toString())
                else
                    newSet.add(appModel.appPackage + "|" + appModel.user.toString())

                prefs.hiddenApps = newSet
                if (newSet.isEmpty())
                    closeDrawer()
                if (prefs.firstHide) {
                    binding.search.hideKeyboard()
                    prefs.firstHide = false
                    viewModel.showDialog.postValue(Constants.Dialog.HIDDEN)
                    findNavController().navigate(if (isHomePage)
                R.id.action_mainFragment_to_settingsFragment
                else R.id.action_appListFragment_to_settingsFragment2)
                }
                viewModel.getAppList()
                viewModel.getHiddenApps()
            },
            appRenameListener = { appModel, renameLabel ->
                val identifier = when (appModel) {
                    is AppModel.PinnedShortcut -> appModel.identity
                    is AppModel.App -> appModel.appPackage
                    else -> return@AppDrawerAdapter
                }
                prefs.setAppRenameLabel(identifier, renameLabel)
                viewModel.getAppList()
            },
            resultsChanged = { query ->
                if (_binding != null && query == binding.search.query.toString()) {
                    linearLayoutManager.reverseLayout = query.isNotBlank()
                    linearLayoutManager.stackFromEnd = false
                    binding.recyclerView.scrollToPosition(0)
                    binding.recyclerView.animateSearchResults(query)
                    scheduleAutoLaunch(query)
                }
            },
            privateSpaceToggleListener = {
                viewModel.togglePrivateSpaceLock()
            },
            privateSpaceSettingsListener = {
                viewModel.openPrivateSpaceSettings()
                findNavController().popBackStack(R.id.mainFragment, false)
            }
        )

        linearLayoutManager = object : LinearLayoutManager(requireContext()) {
            override fun scrollVerticallyBy(
                dx: Int,
                recycler: Recycler,
                state: RecyclerView.State,
            ): Int {
                val scrollRange = super.scrollVerticallyBy(dx, recycler, state)
                val overScroll = dx - scrollRange
                if (overScroll < -10 && binding.recyclerView.scrollState == RecyclerView.SCROLL_STATE_DRAGGING)
                    checkMessageAndExit()
                return scrollRange
            }
        }

        binding.recyclerView.layoutManager = linearLayoutManager
        binding.recyclerView.adapter = adapter
        binding.recyclerView.addOnScrollListener(getRecyclerViewOnScrollListener())
        binding.recyclerView.itemAnimator = null
        binding.recyclerView.addOnItemTouchListener(object : RecyclerView.SimpleOnItemTouchListener() {
            override fun onInterceptTouchEvent(rv: RecyclerView, event: android.view.MotionEvent): Boolean {
                if (event.actionMasked == android.view.MotionEvent.ACTION_DOWN) cancelAutoLaunch()
                return false
            }
        })
        if (requireContext().isEinkDisplay())
            binding.recyclerView.overScrollMode = View.OVER_SCROLL_NEVER
    }

    private fun initObservers() {
        viewModel.firstOpen.observe(viewLifecycleOwner) {
        }
        if (flag == Constants.FLAG_HIDDEN_APPS) {
            viewModel.hiddenApps.observe(viewLifecycleOwner) {
                it?.let {
                    adapter.setAppList(it.toMutableList())
                }
            }
        } else {
            viewModel.appList.observe(viewLifecycleOwner) {
                currentAppList = it
                updateCombinedAppList()
            }
            if (flag == Constants.FLAG_LAUNCH_APP) {
                viewModel.privateSpaceAvailable.observe(viewLifecycleOwner) {
                    currentPrivateSpaceAvailable = it
                    updateCombinedAppList()
                }
                viewModel.privateSpaceLocked.observe(viewLifecycleOwner) {
                    currentPrivateSpaceLocked = it
                    updateCombinedAppList()
                }
                viewModel.privateSpaceApps.observe(viewLifecycleOwner) {
                    currentPrivateSpaceApps = it
                    updateCombinedAppList()
                }
            }
        }
    }

    private fun updateCombinedAppList() {
        val apps = currentAppList ?: return
        val combined = apps.toMutableList()

        if (flag == Constants.FLAG_LAUNCH_APP && currentPrivateSpaceAvailable) {
            combined.add(AppModel.PrivateSpaceHeader(isLocked = currentPrivateSpaceLocked))
            if (!currentPrivateSpaceLocked) {
                currentPrivateSpaceApps?.let { combined.addAll(it) }
            }
        }

        adapter.setAppList(combined)
        adapter.filterApps(binding.search.query.toString())
    }

    private fun initClickListeners() {
        binding.appRename.setOnClickListener {
            val name = binding.search.query.toString().trim()
            if (name.isEmpty()) {
                requireContext().showToast(getString(R.string.type_a_new_app_name_first))
                binding.search.showKeyboard()
                return@setOnClickListener
            }

            when (flag) {
                Constants.FLAG_SET_HOME_APP_1 -> prefs.appName1 = name
                Constants.FLAG_SET_HOME_APP_2 -> prefs.appName2 = name
                Constants.FLAG_SET_HOME_APP_3 -> prefs.appName3 = name
                Constants.FLAG_SET_HOME_APP_4 -> prefs.appName4 = name
                Constants.FLAG_SET_HOME_APP_5 -> prefs.appName5 = name
                Constants.FLAG_SET_HOME_APP_6 -> prefs.appName6 = name
                Constants.FLAG_SET_HOME_APP_7 -> prefs.appName7 = name
                Constants.FLAG_SET_HOME_APP_8 -> prefs.appName8 = name
            }
            closeDrawer()
        }
    }

    private fun getRecyclerViewOnScrollListener(): RecyclerView.OnScrollListener =
        object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                if (newState != RecyclerView.SCROLL_STATE_IDLE) cancelAutoLaunch()
            }
        }

    private fun checkMessageAndExit() {
        if (!closeDrawer()) return
        if (flag == Constants.FLAG_LAUNCH_APP)
            viewModel.checkForMessages.call()
    }

    // Pops only the drawer. A plain popBackStack() from a late overscroll frame could pop the
    // home screen too, leaving a drawer that auto-launch and back can't close (#766)
    private fun closeDrawer(): Boolean {
        if (isHomePage) {
            resetHomeSearch()
            return false
        }
        return findNavController().popBackStack(R.id.appListFragment, true)
    }

    override fun onStart() {
        super.onStart()
        cachedIsCjkKeyboard = null
        val controller = androidx.core.view.WindowCompat.getInsetsController(requireActivity().window, binding.root)
        controller.systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (prefs.showStatusBar) controller.show(androidx.core.view.WindowInsetsCompat.Type.statusBars())
        else controller.hide(androidx.core.view.WindowInsetsCompat.Type.statusBars())
        if (dockExpanded) binding.search.showKeyboard(prefs.autoShowKeyboard)
    }

    override fun onStop() {
        cancelAutoLaunch()
        binding.search.hideKeyboard()
        if (isHomePage) resetHomeSearch(animate = false)
        super.onStop()
    }

    override fun onDestroyView() {
        cancelAutoLaunch()
        binding.recyclerView.adapter = null
        super.onDestroyView()
        dockAnimator?.cancel()
        dockAnimator = null
        searchTextView = null
        _binding = null
    }
}
