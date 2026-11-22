package app.olauncher.ui

import android.Manifest
import android.animation.ValueAnimator
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.ContactsContract
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Rect
import android.net.Uri
import android.telephony.PhoneNumberUtils
import java.util.Locale
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.widget.FrameLayout
import androidx.core.animation.doOnEnd
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.core.widget.doAfterTextChanged
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.ConcatAdapter
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import androidx.fragment.app.activityViewModels
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import app.olauncher.BuildConfig
import app.olauncher.MainViewModel
import app.olauncher.R
import app.olauncher.data.AppModel
import app.olauncher.data.Constants
import app.olauncher.data.Prefs
import app.olauncher.databinding.FragmentAppDrawerBinding
import app.olauncher.helper.ContactLoadResult
import app.olauncher.helper.deletePinnedShortcut
import app.olauncher.helper.hideKeyboard
import app.olauncher.helper.isEinkDisplay
import app.olauncher.helper.isSystemApp
import app.olauncher.helper.openAppInfo
import app.olauncher.helper.showKeyboard
import app.olauncher.helper.showToast
import app.olauncher.helper.uninstall

class AppDrawerFragment : BaseFragment() {

    private val viewModel: MainViewModel by activityViewModels()
    private var _binding: FragmentAppDrawerBinding? = null
    private val binding get() = _binding!!
    private lateinit var prefs: Prefs
    private lateinit var adapter: AppDrawerAdapter
    private lateinit var layoutManager: LinearLayoutManager
    private lateinit var contactsAdapter: ContactSearchAdapter
    private var contactsLoad: Job? = null
    private var contactsLoaded = false
    private var observingContacts = false
    private var askedForContacts = false
    private val contactsObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) {
            contactsLoaded = false
            contactsLoad?.cancel()
            contactsLoad = null
            loadContactsIfAllowed()
        }
    }
    private val requestContacts = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        if (_binding != null && expanded && !thunderMode) {
            if (it) loadContactsIfAllowed()
            binding.search.showKeyboard()
        }
    }


    private val isHomePage: Boolean
        get() = findNavController().currentDestination?.id == R.id.mainFragment

    private var flag = Constants.FLAG_LAUNCH_APP
    private var canRename = false
    private var currentAppList: List<AppModel>? = null
    private var currentPrivateSpaceApps: List<AppModel>? = null
    private var currentPrivateSpaceLocked = true
    private var currentPrivateSpaceAvailable = false

    private var expanded = false
    private var thunderMode = false
    private var shortcutsRequested = false
    private var dismissedShortcutsQuery: String? = null
    private var searchFeedback: Int? = null
    private val clearFeedback = Runnable {
        searchFeedback = null
        if (_binding != null) updateSearchStatus()
    }
    private var thunderEmptyQuery: String? = null
    private val showThunderEmpty = Runnable {
        if (_binding != null && thunderMode && hasEmptySearchResults(currentResultsQuery())) {
            thunderEmptyQuery = currentResultsQuery()
            updateSearchStatus()
        }
    }
    private var scrolledFromBestMatch = false
    private var displayedQuery: String? = null
    private lateinit var appsSection: SearchSectionAdapter
    private lateinit var contactsSection: SearchSectionAdapter
    private var highlightedContactQuery: String? = null
    private var autoLaunch: Runnable? = null
    private var thunderLaunchAnimation: ThunderLaunchAnimation? = null
    private var thunderLaunchAnimator: ValueAnimator? = null
    private var thunderEditAt: Long? = null
    private var thunderCommitted = false
    private var imeWasVisible = false
    private data class SearchRename(val app: AppModel, val query: String)
    private var searchRename: SearchRename? = null

    private var searchAppLaunch: SearchAppLaunch? = null
    private var restoreSearchKeyboard = false
    private val searchWindowFocusListener = android.view.ViewTreeObserver.OnWindowFocusChangeListener { focused ->
        if (focused) showRestoredSearchKeyboard()
    }

    private data class SearchAppLaunch(val at: Long, val keyboardWasVisible: Boolean) {
        fun isRecent() = SystemClock.elapsedRealtime() - at <= SEARCH_RETURN_WINDOW_MS
    }

    private companion object {
        const val SEARCH_RETURN_WINDOW_MS = 5_000L
        const val THUNDER_LIFT_MS = 90L
        const val THUNDER_QUIET_MS = 220L
        const val COMPOSITION_RECHECK_MS = 100L
    }
    private var boltAnimator: ValueAnimator? = null
    private var boltProgress = 0f
    private var dockIdleColor = 0
    private var boltYellow = 0

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentAppDrawerBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        prefs = Prefs(requireContext())
        flag = arguments?.getInt(Constants.Key.FLAG, Constants.FLAG_LAUNCH_APP)
            ?: Constants.FLAG_LAUNCH_APP
        canRename = arguments?.getBoolean(Constants.Key.RENAME, false) ?: false
        expanded = !isHomePage
        thunderMode = false
        shortcutsRequested = false
        dismissedShortcutsQuery = null
        boltProgress = 0f
        scrolledFromBestMatch = false
        displayedQuery = null
        initAdapter()
        initSearch()
        binding.root.viewTreeObserver.addOnWindowFocusChangeListener(searchWindowFocusListener)
        binding.root.onBackBeforeIme = {
            cancelAutoLaunch()
            dismissSearchEdit() || dismissContactSelection()
        }
        binding.root.onTap = ::dismissSearchActionsOutside
        initObservers()
        initClickListeners()
        initHomeSwipe()
        applyDockInsets()
        updateSearchVisibility()
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner,
            object : androidx.activity.OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (!dismissSearchEdit() && !dismissContactSelection() && !collapseSearch() && !isHomePage) closeDrawer()
                }
            })
    }

    private fun applyDockInsets() {
        val root = binding.root
        val initialLeft = root.paddingLeft
        imeWasVisible = false
        val initialTop = root.paddingTop
        val initialRight = root.paddingRight
        val initialBottom = root.paddingBottom
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.navigationBars())
            val keyboard = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.ime())
            val keyboardVisible = insets.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime())
            if (imeWasVisible && !keyboardVisible) cancelAutoLaunch()
            imeWasVisible = keyboardVisible
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
        // Layout uses the final IME inset. Translation keeps the dock aligned to
        // the current inset, including when the system reverses the animation.
        androidx.core.view.ViewCompat.setWindowInsetsAnimationCallback(root,
            object : androidx.core.view.WindowInsetsAnimationCompat.Callback(DISPATCH_MODE_CONTINUE_ON_SUBTREE) {
                private var activeIme: androidx.core.view.WindowInsetsAnimationCompat? = null
                private var animateIme = false

                override fun onPrepare(animation: androidx.core.view.WindowInsetsAnimationCompat) {
                    if (animation.typeMask and androidx.core.view.WindowInsetsCompat.Type.ime() != 0) {
                        activeIme = animation
                        animateIme = LauncherMotion.isEnabled(root.context)
                    }
                }

                override fun onProgress(insets: androidx.core.view.WindowInsetsCompat,
                    runningAnimations: MutableList<androidx.core.view.WindowInsetsAnimationCompat>): androidx.core.view.WindowInsetsCompat {
                    if (animateIme && runningAnimations.any { it == activeIme }) {
                        val bars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.navigationBars())
                        val ime = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.ime())
                        root.translationY = (root.paddingBottom - initialBottom - maxOf(bars.bottom, ime.bottom)).toFloat()
                    }
                    return insets
                }

                override fun onEnd(animation: androidx.core.view.WindowInsetsAnimationCompat) {
                    if (animation == activeIme) {
                        root.translationY = 0f
                        activeIme = null
                    }
                }
            })
        root.post { androidx.core.view.ViewCompat.requestApplyInsets(root) }
    }

    private fun initHomeSwipe() {
        binding.root.canSwipeSearch = { isHomePage && !expanded }
        binding.root.onSwipeUp = { openSearch(thunder = false) }
        val threshold = 64f * resources.displayMetrics.density
        var notificationsOpened = false
        val detector = android.view.GestureDetector(requireContext(),
            object : android.view.GestureDetector.SimpleOnGestureListener() {
                override fun onDown(event: android.view.MotionEvent): Boolean {
                    notificationsOpened = false
                    return true
                }

                override fun onLongPress(event: android.view.MotionEvent) {
                    if (isHomePage && !expanded) showHomeSettings()
                }

                override fun onScroll(
                    start: android.view.MotionEvent?,
                    current: android.view.MotionEvent,
                    distanceX: Float,
                    distanceY: Float,
                ): Boolean {
                    if (start == null || notificationsOpened || !isHomePage || expanded) return false
                    val dy = current.y - start.y
                    val dx = current.x - start.x
                    if (dy > threshold && dy > kotlin.math.abs(dx)) {
                        notificationsOpened = true
                        app.olauncher.helper.expandNotificationDrawer(requireContext())
                    }
                    return true
                }
            })
        detector.setOnDoubleTapListener(null)
        binding.root.setOnTouchListener { _, event ->
            isHomePage && !expanded && detector.onTouchEvent(event)
        }
    }

    private fun initSearch() {
        dockIdleColor = ContextCompat.getColor(requireContext(), R.color.bolt_search_dock)
        boltYellow = ContextCompat.getColor(requireContext(), R.color.bolt_search_yellow)
        binding.search.filters = binding.search.filters + android.text.InputFilter { _, _, _, dest, start, end ->
            if (thunderCommitted) dest.subSequence(start, end) else null
        }
        binding.search.setOnClickListener { if (!expanded) openSearch(thunder = false) }
        binding.dockBolt.isVisible = flag == Constants.FLAG_LAUNCH_APP
        if (!binding.dockBolt.isVisible) {
            binding.search.updateLayoutParams<FrameLayout.LayoutParams> { marginEnd = 0 }
        }
        binding.dockBolt.setOnClickListener {
            if (!thunderCommitted) openSearch(thunder = !thunderMode)
        }
        binding.dockWeb.setOnClickListener { openWebSearch(currentResultsQuery()) }
        binding.contactCall.setOnClickListener { runContactAction(Intent.ACTION_DIAL, "tel") }
        binding.contactMessage.setOnClickListener { runContactAction(Intent.ACTION_SENDTO, "smsto") }
        binding.contactWhatsapp.setOnClickListener { runContactAction(Intent.ACTION_VIEW, "https") }
        binding.dock.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            positionBolt(boltProgress)
        }
        applySearchColors()
        binding.search.onImeCommit = {
            // Let the IME finish its edit before checking the composing span.
            autoLaunch?.takeUnless { thunderCommitted }?.let { task ->
                val delay = thunderEditAt?.let {
                    it + THUNDER_QUIET_MS - SystemClock.uptimeMillis()
                }
                if (delay != null) {
                    binding.root.removeCallbacks(task)
                    binding.root.postDelayed(task, delay.coerceAtLeast(0L))
                }
            }
        }
        binding.renameConfirm.setOnClickListener { saveSearchRename() }
        binding.renameCancel.setOnClickListener { finishSearchRename() }
        binding.search.doAfterTextChanged { text ->
            if (searchRename != null || thunderCommitted) return@doAfterTextChanged
            adapter.closeSearchActions()
            cancelAutoLaunch()
            val query = text.toString()
            clearThunderEmpty()
            if (thunderMode && expanded && query.isNotBlank()) thunderEditAt = SystemClock.uptimeMillis()
            binding.appRename.isVisible = canRename && query.isNotBlank()
            highlightedContactQuery = null
            contactsAdapter.clearSelection()
            dismissedShortcutsQuery = null
            binding.root.removeCallbacks(clearFeedback)
            clearFeedback.run()
            if (binding.dockWeb.isVisible != canOpenWebSearch(query)) updateDockButtons()
            adapter.filterApps(query)
            filterContacts(query)
        }
        binding.search.setOnEditorActionListener { _, actionId, event ->
            val enter = event?.keyCode == KeyEvent.KEYCODE_ENTER ||
                event?.keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER
            if (actionId != EditorInfo.IME_ACTION_GO &&
                !(searchRename != null && actionId == EditorInfo.IME_ACTION_DONE) && !enter) return@setOnEditorActionListener false
            if (event?.action != KeyEvent.ACTION_DOWN && !thunderMode) {
                if (searchRename != null) {
                    saveSearchRename()
                    return@setOnEditorActionListener true
                }
                if (adapter.closeSearchActions()) return@setOnEditorActionListener true
                val query = binding.search.text.toString()
                val highlightedContact = contactsAdapter.enterHighlightedResult
                    ?.takeIf { highlightedContactQuery == query && contactsAdapter.committedQuery == query }
                when {
                    highlightedContact != null -> openContact(highlightedContact)
                    offersWebFallback(query) -> openWebSearch(query)
                    adapter.committedQuery == query -> adapter.launchFirstInList()
                }
            }
            true
        }
    }

    private fun openSearch(thunder: Boolean) {
        expanded = true
        updateSearchVisibility()
        setThunderMode(thunder)
        if (!thunder && flag == Constants.FLAG_LAUNCH_APP && !hasContactsPermission() && !askedForContacts) {
            askedForContacts = true
            requestContacts.launch(Manifest.permission.READ_CONTACTS)
            binding.search.requestFocus()
            return
        }
        if (!thunder) loadContactsIfAllowed()
        val imeVisible = androidx.core.view.ViewCompat.getRootWindowInsets(binding.root)
            ?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime()) == true
        if (imeVisible) binding.search.requestFocus()
        else binding.search.showKeyboard()
    }

    private fun updateSearchVisibility() {
        binding.search.hint = when {
            searchRename != null -> getString(R.string.bolt_new_app_name)
            !expanded -> getString(R.string.bolt_search_anything)
            flag == Constants.FLAG_HIDDEN_APPS -> getString(R.string.hidden_apps)
            flag != Constants.FLAG_LAUNCH_APP -> getString(R.string.bolt_select_app)
            else -> null
        }
        binding.search.isFocusableInTouchMode = expanded
        binding.search.isFocusable = expanded
        binding.search.isLongClickable = expanded
        if (!expanded) binding.search.clearFocus()
        binding.recyclerView.visibility = if (expanded) View.VISIBLE else View.INVISIBLE
        binding.recyclerView.updateRowMotion(expanded)
        updateDockButtons()
        updateSearchStatus()
    }

    private fun setThunderMode(enabled: Boolean, animate: Boolean = true) {
        if (thunderMode == enabled) return
        cancelAutoLaunch()
        adapter.closeSearchActions()
        scrolledFromBestMatch = false
        thunderMode = enabled
        clearThunderEmpty()
        updateSearchStatus()
        updateDockButtons()
        adapter.setThunderMode(enabled)
        applySearchColors()
        val target = if (enabled) 1f else 0f
        boltAnimator?.cancel()
        if (!animate || !LauncherMotion.isEnabled(requireContext())) {
            positionBolt(target)
        } else {
            boltAnimator = ValueAnimator.ofFloat(boltProgress, target).apply {
                duration = LauncherMotion.MODE_DURATION
                interpolator = LauncherMotion.settle
                addUpdateListener { positionBolt(it.animatedValue as Float) }
                start()
            }
        }
        if (enabled && !binding.search.text.isNullOrEmpty()) binding.search.text?.clear()
        else {
            val query = binding.search.text.toString()
            adapter.filterApps(query)
            filterContacts(query)
        }
    }

    private fun applySearchColors() {
        binding.dockBolt.contentDescription = getString(
            if (thunderMode) R.string.bolt_disable_thunder else R.string.bolt_enable_thunder
        )
    }

    private fun positionBolt(progress: Float) {
        boltProgress = progress
        val bolt = binding.dockBolt
        val dockColor = androidx.core.graphics.ColorUtils.blendARGB(dockIdleColor, boltYellow, progress)
        binding.searchPanel.setDockAppearance(dockColor, progress)
        val lightDock = androidx.core.graphics.ColorUtils.calculateLuminance(dockColor) > 0.18
        binding.search.setThunderCursor(lightDock)
        binding.search.setTextColor(if (lightDock) Color.BLACK else Color.WHITE)
        binding.search.setHintTextColor(if (lightDock) 0xFF28230F.toInt() else 0xFFD2D2D2.toInt())
        bolt.imageTintList = ColorStateList.valueOf(if (lightDock) Color.BLACK else boltYellow)
        updateBoltVisibility()
        if (flag != Constants.FLAG_LAUNCH_APP) return
        val travel = (binding.dock.width - bolt.width).coerceAtLeast(0)
        bolt.translationX = -travel * progress
        val inset = resources.getDimensionPixelSize(R.dimen.bolt_search_button_width)
        binding.search.translationX = inset * progress
    }

    private fun currentResultsQuery(): String = searchRename?.query ?: binding.search.text.toString()

    private fun onResultsChanged(query: String) {
        if (_binding == null || query != currentResultsQuery()) return
        updateSearchSections()
        val bottomAnchored = thunderMode || query.isNotBlank()
        val directionChanged = layoutManager.reverseLayout != bottomAnchored
        layoutManager.reverseLayout = bottomAnchored
        if (directionChanged) scrolledFromBestMatch = false
        if (bottomAnchored) {
            positionResults()
        } else if (displayedQuery != query || directionChanged) {
            layoutManager.scrollToPositionWithOffset(0, 0)
        }
        displayedQuery = query
        updateResultHighlight(query)
        if (thunderMode && hasEmptySearchResults(query)) {
            if (thunderEmptyQuery != query) {
                binding.root.removeCallbacks(showThunderEmpty)
                binding.root.postDelayed(showThunderEmpty, 240L)
            }
        } else {
            clearThunderEmpty()
        }
        updateSearchStatus()
        scheduleAutoLaunch(query)
    }

    private fun hasContactsPermission(): Boolean =
        ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.READ_CONTACTS) ==
            PackageManager.PERMISSION_GRANTED

    private fun loadContactsIfAllowed() {
        if (_binding == null || !expanded || thunderMode || flag != Constants.FLAG_LAUNCH_APP ||
            contactsLoaded || contactsLoad?.isActive == true || !hasContactsPermission()) return
        val context = requireContext().applicationContext
        if (!observingContacts) {
            context.contentResolver.registerContentObserver(ContactsContract.AUTHORITY_URI, true, contactsObserver)
            observingContacts = true
        }
        contactsLoad = viewLifecycleOwner.lifecycleScope.launch {
            when (val result = ContactSearchAdapter.load(context)) {
                is ContactLoadResult.Success -> {
                    contactsLoaded = true
                    contactsAdapter.contacts = result.contacts
                    filterContacts(currentResultsQuery())
                }
                ContactLoadResult.PermissionDenied -> {
                    contactsAdapter.clearContacts()
                    updateSearchSections()
                    updateSearchStatus()
                    updateResultHighlight(currentResultsQuery())
                }
                is ContactLoadResult.Failure -> Unit
            }
        }
    }

    private fun filterContacts(query: String) {
        if (!expanded || thunderMode || flag != Constants.FLAG_LAUNCH_APP || query.isBlank()) {
            contactsAdapter.clearResults()
            updateSearchSections()
            updateDockButtons()
            return
        }
        contactsAdapter.filter(query, showAll = false) {
            updateSearchSections()
            updateResultHighlight(query)
            positionResults()
            updateSearchStatus()
        }
        loadContactsIfAllowed()
    }

    private fun positionResults() {
        if (!layoutManager.reverseLayout) return
        val results = binding.recyclerView
        if (scrolledFromBestMatch) {
            // DiffUtil maps the still-attached holders to the new adapter positions.
            val anchor = (0 until results.childCount).asSequence()
                .map(results::getChildAt)
                .filter { it.bottom > results.paddingTop && it.top < results.height - results.paddingBottom }
                .sortedByDescending { it.bottom }
                .firstOrNull { results.getChildAdapterPosition(it) != RecyclerView.NO_POSITION }
            if (anchor != null) {
                val bounds = Rect()
                layoutManager.getDecoratedBoundsWithMargins(anchor, bounds)
                layoutManager.scrollToPositionWithOffset(
                    results.getChildAdapterPosition(anchor),
                    results.height - results.paddingBottom - bounds.bottom,
                )
                return
            }
            scrolledFromBestMatch = false
        }
        layoutManager.scrollToPositionWithOffset(0, 0)
    }

    private fun updateSearchSections() {
        val showSections = expanded && !thunderMode && flag == Constants.FLAG_LAUNCH_APP &&
            currentResultsQuery().isNotBlank()
        appsSection.show(showSections && adapter.itemCount > 0)
        contactsSection.show(showSections && contactsAdapter.itemCount > 0)
    }

    private fun selectContact(contact: ContactSearchAdapter.Contact) {
        val query = binding.search.text.toString()
        if (!expanded || thunderMode || contactsAdapter.committedQuery != query) return
        dismissedShortcutsQuery = null
        contactsAdapter.toggleSelection(contact)
        updateResultHighlight(query)
    }

    private fun dismissContactSelection(): Boolean {
        if (!contactsAdapter.clearSelection()) return false
        val query = currentResultsQuery()
        dismissedShortcutsQuery = query
        updateResultHighlight(query)
        return true
    }

    private fun hasEmptySearchResults(query: String): Boolean {
        val noApps = adapter.committedQuery == query && adapter.itemCount == 0
        val noContacts = thunderMode || !hasContactsPermission() ||
            (contactsAdapter.committedQuery == query && contactsAdapter.itemCount == 0)
        return expanded && flag == Constants.FLAG_LAUNCH_APP && searchRename == null &&
            query.isNotBlank() && noApps && noContacts
    }

    private fun canOpenWebSearch(query: String): Boolean =
        expanded && !thunderMode && flag == Constants.FLAG_LAUNCH_APP && searchRename == null &&
            query.isNotBlank()

    private fun offersWebFallback(query: String): Boolean =
        canOpenWebSearch(query) && searchFeedback == null && hasEmptySearchResults(query)

    private fun clearThunderEmpty() {
        binding.root.removeCallbacks(showThunderEmpty)
        thunderEmptyQuery = null
    }

    private fun updateSearchStatus() {
        val query = currentResultsQuery()
        val feedback = searchFeedback.takeIf { expanded }
        val offerWeb = offersWebFallback(query)
        val thunderEmpty = thunderMode && thunderEmptyQuery == query && hasEmptySearchResults(query)
        val statusIsResult = offerWeb || (thunderEmpty && feedback == null)
        binding.searchPanel.statusIsResult = statusIsResult
        binding.searchStatus.apply {
            isVisible = feedback != null || offerWeb || thunderEmpty
            text = when {
                feedback != null -> getString(feedback)
                offerWeb -> getString(R.string.bolt_search_web_for, query.trim())
                thunderEmpty -> getString(R.string.bolt_no_apps_found)
                else -> ""
            }
            setBackgroundResource(if (statusIsResult) R.drawable.bolt_search_best_match else 0)
            minimumHeight = if (statusIsResult) resources.getDimensionPixelSize(R.dimen.bolt_search_result_height) else 0
            setOnClickListener(if (offerWeb) View.OnClickListener { openWebSearch(query) } else null)
            isClickable = offerWeb
            isFocusable = offerWeb
        }
    }

    private fun openWebSearch(query: String) {
        if (query != currentResultsQuery() || !canOpenWebSearch(query)) return
        val url = Uri.parse("https://www.google.com/search").buildUpon()
            .appendQueryParameter("q", query.trim())
            .build()
        launchSearchAction(Intent(Intent.ACTION_VIEW, url), R.string.bolt_web_unavailable)
    }

    private fun launchSearchAction(intent: Intent, unavailable: Int) {
        try {
            startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            showSearchFeedback(unavailable)
        } catch (_: SecurityException) {
            showSearchFeedback(unavailable)
        }
    }

    private fun showSearchFeedback(message: Int) {
        searchFeedback = message
        updateSearchStatus()
        binding.root.removeCallbacks(clearFeedback)
        binding.root.postDelayed(clearFeedback, 2200L)
    }

    private fun updateResultHighlight(query: String) {
        val showHighlight = expanded && !thunderMode && flag == Constants.FLAG_LAUNCH_APP && query.isNotBlank()
        val appsReady = showHighlight && adapter.committedQuery == query
        val contactsReady = showHighlight && contactsAdapter.committedQuery == query
        val selected = if (contactsReady) contactsAdapter.selectedContact else null
        if (showHighlight && selected == null && !appsReady) return
        val app = if (appsReady && selected == null) adapter.firstLaunchableResult else null
        if (showHighlight && selected == null && app == null && !contactsReady &&
            contactsLoaded && hasContactsPermission()) return
        adapter.enterHighlightedResult = app
        contactsAdapter.enterHighlightedResult = selected ?: if (contactsReady && appsReady && app == null)
            contactsAdapter.firstResult else null
        highlightedContactQuery = query.takeIf { contactsAdapter.enterHighlightedResult != null }
        updateDockButtons()
    }

    private fun updateDockButtons() {
        val contact = contactsAdapter.enterHighlightedResult
        val showShortcuts = expanded && !thunderMode && contact != null && searchRename == null &&
            dismissedShortcutsQuery != currentResultsQuery()
        binding.renameControls.isVisible = searchRename != null
        val shortcuts = binding.contactShortcuts
        val animate = expanded && !thunderMode && searchRename == null &&
            LauncherMotion.isEnabled(shortcuts.context)
        if (shortcutsRequested != showShortcuts || (!animate && shortcuts.isVisible != showShortcuts)) {
            shortcutsRequested = showShortcuts
            if (showShortcuts) {
                val fromAlpha = if (shortcuts.isVisible) shortcuts.alpha else 0f
                shortcuts.isVisible = true
                LauncherMotion.reveal(shortcuts, fromAlpha = fromAlpha, duration = 120L)
            } else if (animate) {
                shortcuts.animate().cancel()
                shortcuts.animate().alpha(0f).setDuration(100L)
                    .setInterpolator(LauncherMotion.settle)
                    .withEndAction {
                        shortcuts.isVisible = false
                        LauncherMotion.reset(shortcuts)
                        updateDockButtons()
                    }.start()
            } else {
                shortcuts.isVisible = false
                LauncherMotion.reset(shortcuts)
            }
        }
        val hasNumber = showShortcuts && contact?.numbers?.isNotEmpty() == true
        listOf(binding.contactCall, binding.contactMessage, binding.contactWhatsapp).forEach {
            it.isEnabled = hasNumber
            if (showShortcuts) it.alpha = if (hasNumber) 1f else 0.4f
        }
        updateBoltVisibility()
        val contactWidth = resources.getDimensionPixelSize(R.dimen.bolt_contact_action_width) * 3 +
            resources.getDimensionPixelSize(R.dimen.bolt_contact_action_divider_width)
        val actionMargin = when {
            searchRename != null -> resources.getDimensionPixelSize(R.dimen.bolt_rename_action_width) * 2
            shortcuts.isVisible -> contactWidth
            thunderMode -> resources.getDimensionPixelSize(R.dimen.bolt_search_button_width)
            !expanded && flag == Constants.FLAG_LAUNCH_APP ->
                resources.getDimensionPixelSize(R.dimen.bolt_search_button_width)
            else -> 0
        }
        val web = binding.dockWeb
        web.isVisible = canOpenWebSearch(currentResultsQuery())
        if (web.isVisible && (web.layoutParams as FrameLayout.LayoutParams).marginEnd != actionMargin) {
            web.updateLayoutParams<FrameLayout.LayoutParams> { marginEnd = actionMargin }
        }
        val divider = binding.dockWebDivider
        divider.isVisible = web.isVisible
        val webStart = actionMargin + if (web.isVisible) web.layoutParams.width else 0
        if (divider.isVisible && (divider.layoutParams as FrameLayout.LayoutParams).marginEnd != webStart) {
            divider.updateLayoutParams<FrameLayout.LayoutParams> { marginEnd = webStart }
        }
        val margin = webStart + if (divider.isVisible) divider.layoutParams.width else 0
        val endPadding = resources.getDimensionPixelSize(
            if (web.isVisible) R.dimen.bolt_search_web_text_gap else R.dimen.bolt_search_text_inset
        )
        val search = binding.search
        if (search.paddingEnd != endPadding) {
            search.setPaddingRelative(search.paddingStart, search.paddingTop, endPadding, search.paddingBottom)
        }
        if ((binding.search.layoutParams as FrameLayout.LayoutParams).marginEnd != margin) {
            binding.search.updateLayoutParams<FrameLayout.LayoutParams> { marginEnd = margin }
        }
    }

    private fun updateBoltVisibility() {
        val available = flag == Constants.FLAG_LAUNCH_APP && (!expanded || thunderMode)
        val bolt = binding.dockBolt
        bolt.isVisible = available || (flag == Constants.FLAG_LAUNCH_APP && boltProgress > 0f)
        bolt.isEnabled = available
        bolt.importantForAccessibility = if (available) View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
            else View.IMPORTANT_FOR_ACCESSIBILITY_NO
        bolt.alpha = if (available) 1f else (boltProgress * 3f).coerceAtMost(1f)
    }

    private fun runContactAction(action: String, scheme: String) {
        val query = binding.search.text.toString()
        if (highlightedContactQuery != query || contactsAdapter.committedQuery != query) return
        val contact = contactsAdapter.enterHighlightedResult ?: return
        fun launch(number: String) {
            val intent = if (scheme == "https") {
                val international = PhoneNumberUtils.formatNumberToE164(number, Locale.getDefault().country) ?: number
                Intent(action, Uri.parse("https://wa.me/" + international.filter { it in '0'..'9' }))
                    .setPackage("com.whatsapp")
            } else {
                Intent(action, Uri.fromParts(scheme, number, null))
            }
            launchSearchAction(intent, if (scheme == "https") R.string.bolt_whatsapp_unavailable
                else R.string.bolt_action_unavailable)
        }
        when (contact.numbers.size) {
            0 -> Unit
            1 -> launch(contact.numbers.first())
            else -> androidx.appcompat.app.AlertDialog.Builder(requireContext())
                .setTitle(contact.name)
                .setItems(contact.numbers.map(::formatContactNumber).toTypedArray()) { _, index ->
                    launch(contact.numbers[index])
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }

    private fun openContact(contact: ContactSearchAdapter.Contact) {
        launchSearchAction(Intent(Intent.ACTION_VIEW, contact.uri), R.string.bolt_contacts_app_unavailable)
    }

    private fun stopLoadingContacts() {
        contactsLoad?.cancel()
        contactsLoad = null
        contactsLoaded = false
        if (observingContacts) {
            requireContext().contentResolver.unregisterContentObserver(contactsObserver)
            observingContacts = false
        }
    }

    private fun cancelAutoLaunch(clearAnimation: Boolean = true) {
        autoLaunch?.let { _binding?.root?.removeCallbacks(it) }
        autoLaunch = null
        thunderEditAt = null
        thunderCommitted = false
        if (clearAnimation) clearThunderLaunchAnimation()
    }

    private fun clearThunderLaunchAnimation() {
        thunderLaunchAnimator?.cancel()
    }

    private fun showThunderLaunchAnimation(app: AppModel) {
        val root = binding.root
        if (!LauncherMotion.isEnabled(root.context)) return
        val animation = adapter.launchAnimation(binding.recyclerView, root, app) ?: return
        animation.setBounds(0, 0, root.width, root.height)
        root.overlay.add(animation)
        thunderLaunchAnimation = animation
        thunderLaunchAnimator = ValueAnimator.ofFloat(animation.progress, 1f).apply {
            duration = THUNDER_LIFT_MS
            interpolator = LauncherMotion.settle
            addUpdateListener { animation.progress = it.animatedValue as Float }
            doOnEnd {
                root.overlay.remove(animation)
                animation.restoreIcon()
                thunderLaunchAnimation = null
                thunderLaunchAnimator = null
            }
        }
        thunderLaunchAnimator?.start()
    }

    private fun scheduleAutoLaunch(query: String) {
        if (thunderCommitted) return
        val editedAt = thunderEditAt ?: return
        autoLaunch?.let(binding.root::removeCallbacks)
        autoLaunch = null
        clearThunderLaunchAnimation()
        if (!thunderMode || !expanded || searchRename != null ||
            adapter.actionPosition != RecyclerView.NO_POSITION || query.isBlank() || adapter.itemCount != 1 ||
            adapter.firstLaunchableResult == null) return
        val result = adapter.firstLaunchableResult ?: return
        val committedAt = SystemClock.uptimeMillis()
        val launchDeadline = editedAt + THUNDER_QUIET_MS
        val task = object : Runnable {
            override fun run() {
                val view = _binding ?: return
                if (!thunderMode || !expanded || searchRename != null ||
                    adapter.actionPosition != RecyclerView.NO_POSITION || adapter.committedQuery != query ||
                    view.search.text.toString() != query || !view.root.hasWindowFocus() ||
                    adapter.itemCount != 1 || adapter.firstLaunchableResult != result ||
                    viewLifecycleOwner.lifecycle.currentState != Lifecycle.State.RESUMED) {
                    cancelAutoLaunch()
                    return
                }
                val composing = view.search.text?.let {
                    BaseInputConnection.getComposingSpanStart(it) >= 0
                } == true
                if (composing) {
                    // Covers IMEs that remove composing spans through another input method.
                    view.root.postDelayed(this, COMPOSITION_RECHECK_MS)
                    return
                }
                thunderCommitted = true
                showThunderLaunchAnimation(result)
                view.dock.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
                val launchAt = SystemClock.uptimeMillis()
                val launched = launchResult(result)
                cancelAutoLaunch(clearAnimation = !launched)
                if (launched && BuildConfig.DEBUG) {
                    Log.d("BoltThunderTiming", "editToCommitMs=${committedAt - editedAt} " +
                        "commitToLaunchRequestMs=${launchAt - committedAt}")
                }
            }
        }
        autoLaunch = task
        binding.root.postDelayed(task, (launchDeadline - committedAt).coerceAtLeast(0L))
    }

    private fun beginSearchRename(app: AppModel) {
        searchRename = SearchRename(app, binding.search.text.toString())
        binding.search.imeOptions = EditorInfo.IME_ACTION_DONE or EditorInfo.IME_FLAG_NO_EXTRACT_UI
        binding.search.text?.clear()
        updateSearchVisibility()
        binding.search.showKeyboard()
    }

    private fun saveSearchRename() {
        val rename = searchRename ?: return
        val label = binding.search.text.toString().trim()
        finishSearchRename()
        saveAppLabel(rename.app, label)
    }

    private fun finishSearchRename(): Boolean {
        val rename = searchRename ?: return false
        searchRename = null
        binding.search.imeOptions = EditorInfo.IME_ACTION_GO or EditorInfo.IME_FLAG_NO_EXTRACT_UI
        binding.search.setText(rename.query)
        binding.search.setSelection(rename.query.length)
        updateSearchVisibility()
        return true
    }

    private fun dismissSearchEdit(): Boolean =
        finishSearchRename() || adapter.closeSearchActions()

    private fun saveAppLabel(app: AppModel, label: String) {
        val identifier = when (app) {
            is AppModel.PinnedShortcut -> app.identity
            is AppModel.App -> app.appPackage
            else -> return
        }
        prefs.setAppRenameLabel(identifier, label)
        viewModel.getAppList()
    }

    private fun dismissSearchActionsOutside(x: Float, y: Float): Boolean {
        val position = adapter.actionPosition
        if (position == RecyclerView.NO_POSITION) return false
        val results = binding.recyclerView
        val location = IntArray(2)
        results.getLocationOnScreen(location)
        val localX = x - location[0]
        val localY = y - location[1]
        val tile = if (localX >= 0 && localX < results.width && localY >= 0 && localY < results.height)
            results.findChildViewUnder(localX, localY) else null
        val holder = tile?.let(results::getChildViewHolder)
        if (holder?.absoluteAdapterPosition == position) return false
        adapter.closeSearchActions()
        // A tap on another app dismisses the strip before that app can be opened.
        return holder?.bindingAdapter === adapter
    }

    private fun openPlayStore(app: AppModel) {
        val uri = Uri.parse("market://details").buildUpon()
            .appendQueryParameter("id", app.appPackage)
            .build()
        val intent = Intent(Intent.ACTION_VIEW, uri).setPackage("com.android.vending")
        launchSearchAction(intent, R.string.bolt_play_store_action_unavailable)
    }

    fun collapseSearch(): Boolean {
        if (_binding == null || !expanded || !isHomePage) return false
        cancelAutoLaunch()
        val insets = androidx.core.view.ViewCompat.getRootWindowInsets(binding.root)
        if (insets?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime()) == true) {
            binding.search.hideKeyboard()
        } else {
            resetHomeSearch()
        }
        return true
    }

    fun resetHomeSearchUnlessReturningFromApp() {
        // Background callbacks run before we know how the user will return.
        if (searchAppLaunch?.isRecent() != true) resetHomeSearch()
    }

    fun resetHomeSearch() {
        if (_binding == null || !isHomePage) return
        searchAppLaunch = null
        restoreSearchKeyboard = false
        dismissSearchEdit()
        expanded = false
        clearThunderEmpty()
        binding.root.removeCallbacks(clearFeedback)
        searchFeedback = null
        scrolledFromBestMatch = false
        dismissedShortcutsQuery = null
        LauncherMotion.reset(binding.recyclerView)
        LauncherMotion.reset(binding.dock)
        cancelAutoLaunch()
        binding.search.hideKeyboard()
        binding.search.text?.clear()
        setThunderMode(false, animate = false)
        updateSearchVisibility()
    }

    private fun showHomeSettings() {
        cancelAutoLaunch()
        androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setItems(arrayOf("Launcher settings", "System settings")) { _, which ->
                if (which == 0) {
                    findNavController().navigate(if (isHomePage) R.id.action_mainFragment_to_settingsFragment
                        else R.id.action_appListFragment_to_settingsFragment2)
                } else {
                    try {
                        startActivity(android.content.Intent(android.provider.Settings.ACTION_SETTINGS))
                    } catch (_: android.content.ActivityNotFoundException) {
                        requireContext().showToast("System settings are unavailable")
                    }
                }
            }.show()
    }

    private fun launchResult(appModel: AppModel): Boolean {
        val animatingThunderLaunch = thunderLaunchAnimation != null
        if (!thunderCommitted) cancelAutoLaunch(clearAnimation = !animatingThunderLaunch)
        if (flag == Constants.FLAG_LAUNCH_APP && expanded) {
            val keyboardWasVisible = imeWasVisible
            if (!viewModel.launchAppResult(
                    appModel, ::showSearchFeedback, thunderLaunchAnimation?.launchOptions()
                )) return false
            if (isHomePage && !thunderMode) {
                searchAppLaunch = SearchAppLaunch(SystemClock.elapsedRealtime(), keyboardWasVisible)
            } else if (!animatingThunderLaunch) {
                binding.search.hideKeyboard()
            }
        } else {
            if (!animatingThunderLaunch) binding.search.hideKeyboard()
            viewModel.selectedApp(appModel, flag)
        }
        if (flag == Constants.FLAG_LAUNCH_APP || flag == Constants.FLAG_HIDDEN_APPS)
            findNavController().popBackStack(R.id.mainFragment, false)
        else
            closeDrawer()
        return true
    }

    private fun initAdapter() {
        adapter = AppDrawerAdapter(
            flag,
            prefs.appLabelAlignment,
            appClickListener = { appModel ->
                if (searchRename == null && !thunderCommitted) launchResult(appModel)
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
                    is AppModel.PrivateSpaceHeader, is AppModel.SearchSuggestion -> {}
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
                            try {
                                requireContext().uninstall(appModel.appPackage)
                            } catch (_: ActivityNotFoundException) {
                                requireContext().showToast(getString(R.string.bolt_uninstall_unavailable))
                            } catch (_: SecurityException) {
                                requireContext().showToast(getString(R.string.bolt_uninstall_unavailable))
                            }
                        }
                    }
                }
                viewModel.getAppList()
            },
            appHideListener = { appModel, _ ->
                if (appModel is AppModel.PinnedShortcut) {
                    requireContext().showToast("Hiding pinned shortcuts is not supported")
                    return@AppDrawerAdapter
                }
                adapter.setAppList(adapter.appsList.filter { it != appModel }.toMutableList())

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
                    findNavController().navigate(
                        if (isHomePage) R.id.action_mainFragment_to_settingsFragment
                        else R.id.action_appListFragment_to_settingsFragment2
                    )
                }
                viewModel.getAppList()
                viewModel.getHiddenApps()
            },
            appRenameListener = ::saveAppLabel,
            searchRenameListener = ::beginSearchRename,
            canShowSearchActions = {
                (expanded && searchRename == null && flag == Constants.FLAG_LAUNCH_APP).also {
                    if (it && thunderMode) cancelAutoLaunch()
                }
            },
            appPlayStoreListener = ::openPlayStore,
            resultsChanged = ::onResultsChanged,
            privateSpaceToggleListener = { viewModel.togglePrivateSpaceLock() },
            privateSpaceSettingsListener = {
                viewModel.openPrivateSpaceSettings()
                findNavController().popBackStack(R.id.mainFragment, false)
            }
        )
        layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerView.layoutManager = layoutManager
        contactsAdapter = ContactSearchAdapter(tap = ::selectContact, hold = ::openContact)
        appsSection = SearchSectionAdapter(getString(R.string.bolt_apps_section))
        contactsSection = SearchSectionAdapter(getString(R.string.bolt_contacts_section))
        binding.recyclerView.adapter = ConcatAdapter(adapter, appsSection, contactsAdapter, contactsSection)
        binding.recyclerView.itemAnimator = null
        binding.recyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                if (dy != 0 && layoutManager.reverseLayout &&
                    recyclerView.scrollState != RecyclerView.SCROLL_STATE_IDLE) {
                    scrolledFromBestMatch = recyclerView.canScrollVertically(1)
                }
            }

            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                if (newState != RecyclerView.SCROLL_STATE_IDLE) cancelAutoLaunch()
            }
        })
        binding.recyclerView.addOnItemTouchListener(object : RecyclerView.SimpleOnItemTouchListener() {
            override fun onInterceptTouchEvent(rv: RecyclerView, event: android.view.MotionEvent): Boolean {
                if (event.actionMasked == android.view.MotionEvent.ACTION_DOWN) cancelAutoLaunch()
                return searchRename != null
            }
        })
        if (requireContext().isEinkDisplay()) binding.recyclerView.overScrollMode = View.OVER_SCROLL_NEVER
    }

    private fun initObservers() {
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

        if (combined != adapter.appsList) adapter.setAppList(combined)
    }

    private fun initClickListeners() {
        binding.appRename.setOnClickListener {
            val name = binding.search.text.toString().trim()
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

    private fun closeDrawer(): Boolean {
        if (isHomePage) {
            resetHomeSearch()
            return false
        }
        return findNavController().popBackStack(R.id.appListFragment, true)
    }

    override fun onStart() {
        super.onStart()
        if (searchAppLaunch?.isRecent() == false) resetHomeSearch()
        val controller = androidx.core.view.WindowCompat.getInsetsController(requireActivity().window, binding.root)
        controller.systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (prefs.showStatusBar) controller.show(androidx.core.view.WindowInsetsCompat.Type.statusBars())
        else controller.hide(androidx.core.view.WindowInsetsCompat.Type.statusBars())
        if (flag == Constants.FLAG_LAUNCH_APP) {
            if (hasContactsPermission()) loadContactsIfAllowed()
            else contactsAdapter.clearContacts()
        }
        binding.recyclerView.updateRowMotion(expanded)
        if (expanded && searchAppLaunch == null) binding.search.showKeyboard(prefs.autoShowKeyboard)
    }

    override fun onResume() {
        super.onResume()
        val launch = searchAppLaunch ?: return
        searchAppLaunch = null
        if (!launch.isRecent()) {
            resetHomeSearch()
        } else if (launch.keyboardWasVisible && expanded && !thunderMode) {
            restoreSearchKeyboard = true
            showRestoredSearchKeyboard()
        }
    }

    private fun showRestoredSearchKeyboard() {
        if (!restoreSearchKeyboard || _binding == null || !binding.search.hasWindowFocus()) return
        restoreSearchKeyboard = false
        if (!expanded || thunderMode) return
        binding.search.requestFocus()
        androidx.core.view.WindowCompat.getInsetsController(requireActivity().window, binding.search)
            .show(androidx.core.view.WindowInsetsCompat.Type.ime())
    }

    override fun onPause() {
        restoreSearchKeyboard = false
        dismissSearchEdit()
        cancelAutoLaunch(clearAnimation = false)
        super.onPause()
    }

    override fun onStop() {
        cancelAutoLaunch()
        stopLoadingContacts()
        if (isHomePage) resetHomeSearchUnlessReturningFromApp() else binding.search.hideKeyboard()
        super.onStop()
    }

    override fun onDestroyView() {
        clearThunderEmpty()
        binding.root.viewTreeObserver.removeOnWindowFocusChangeListener(searchWindowFocusListener)
        restoreSearchKeyboard = false
        searchRename = null
        searchAppLaunch = null
        cancelAutoLaunch()
        binding.root.removeCallbacks(clearFeedback)
        searchFeedback = null
        binding.search.onImeCommit = null
        stopLoadingContacts()
        boltAnimator?.cancel()
        boltAnimator = null
        LauncherMotion.reset(binding.recyclerView)
        LauncherMotion.reset(binding.dock)
        LauncherMotion.reset(binding.contactShortcuts)
        adapter.cancelFiltering()
        binding.recyclerView.adapter = null
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(binding.root, null)
        androidx.core.view.ViewCompat.setWindowInsetsAnimationCallback(binding.root, null)
        _binding = null
        super.onDestroyView()
    }
}
