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
import android.graphics.drawable.ColorDrawable
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
    private var revealedContactShortcuts = false
    private var resultsQuery: String? = null
    private var decoratedContact: Pair<Int, Uri>? = null
    private var highlightedContactQuery: String? = null
    private var autoLaunch: Runnable? = null
    private var thunderEditAt: Long? = null
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
        const val THUNDER_QUIET_MS = 220L
        const val COMPOSITION_RECHECK_MS = 100L
    }
    private var backdropAnimator: ValueAnimator? = null
    private val searchBackdrop = ColorDrawable(0xFF0D0D0D.toInt())
    private var boltAnimator: ValueAnimator? = null
    private var boltProgress = 0f

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
        revealedContactShortcuts = false
        boltProgress = 0f
        resultsQuery = null
        decoratedContact = null
        initAdapter()
        initSearch()
        binding.root.viewTreeObserver.addOnWindowFocusChangeListener(searchWindowFocusListener)
        binding.root.onBackBeforeIme = ::dismissSearchEdit
        binding.root.onTap = ::dismissSearchActionsOutside
        initObservers()
        initClickListeners()
        initHomeSwipe()
        applyDockInsets()
        binding.root.background = searchBackdrop
        updateSearchVisibility()
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner,
            object : androidx.activity.OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (!dismissSearchEdit() && !collapseSearch() && !isHomePage) closeDrawer()
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
        // The IME applies its final padding before drawing. Offset that layout jump,
        // then follow the system keyboard animation using compositor translations.
        androidx.core.view.ViewCompat.setWindowInsetsAnimationCallback(root,
            object : androidx.core.view.WindowInsetsAnimationCompat.Callback(DISPATCH_MODE_CONTINUE_ON_SUBTREE) {
                private var startBottom = 0
                private var offset = 0f
                private val location = IntArray(2)
                private var activeIme: androidx.core.view.WindowInsetsAnimationCompat? = null
                private fun dockBottom(): Int {
                    binding.dock.getLocationOnScreen(location)
                    return location[1] + binding.dock.height
                }
                override fun onPrepare(animation: androidx.core.view.WindowInsetsAnimationCompat) {
                    if (animation.typeMask and androidx.core.view.WindowInsetsCompat.Type.ime() != 0) {
                        startBottom = dockBottom()
                        root.translationY = 0f
                        activeIme = animation
                    }
                }
                override fun onStart(animation: androidx.core.view.WindowInsetsAnimationCompat,
                    bounds: androidx.core.view.WindowInsetsAnimationCompat.BoundsCompat): androidx.core.view.WindowInsetsAnimationCompat.BoundsCompat {
                    if (animation.typeMask and androidx.core.view.WindowInsetsCompat.Type.ime() != 0) {
                        offset = if (LauncherMotion.isEnabled(requireContext()))
                            (startBottom - dockBottom()).toFloat() else 0f
                        root.translationY = offset
                    }
                    return bounds
                }
                override fun onProgress(insets: androidx.core.view.WindowInsetsCompat,
                    runningAnimations: MutableList<androidx.core.view.WindowInsetsAnimationCompat>): androidx.core.view.WindowInsetsCompat {
                    runningAnimations.firstOrNull {
                        it.typeMask and androidx.core.view.WindowInsetsCompat.Type.ime() != 0
                    }?.let { root.translationY = offset * (1f - it.interpolatedFraction) }
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
        binding.search.hint = when {
            flag == Constants.FLAG_HIDDEN_APPS -> getString(R.string.hidden_apps)
            flag != Constants.FLAG_LAUNCH_APP -> getString(R.string.bolt_select_app)
            else -> getString(R.string.bolt_search)
        }
        binding.homeSearch.setOnClickListener { openSearch(thunder = false) }
        binding.dockBolt.isVisible = flag == Constants.FLAG_LAUNCH_APP
        if (!binding.dockBolt.isVisible) {
            binding.search.updateLayoutParams<FrameLayout.LayoutParams> { marginEnd = 0 }
        }
        binding.dockBolt.setOnClickListener { openSearch(thunder = !thunderMode) }
        binding.contactCall.setOnClickListener { runContactAction(Intent.ACTION_DIAL, "tel") }
        binding.contactMessage.setOnClickListener { runContactAction(Intent.ACTION_SENDTO, "smsto") }
        binding.contactWhatsapp.setOnClickListener { runContactAction(Intent.ACTION_VIEW, "https") }
        binding.dock.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            positionBolt(boltProgress)
        }
        applySearchColors()
        binding.search.onImeCommit = {
            // Let the IME finish its edit before checking the composing span.
            autoLaunch?.let { task ->
                val delay = thunderEditAt?.let { it + THUNDER_QUIET_MS - SystemClock.uptimeMillis() }
                if (delay != null) {
                    binding.root.removeCallbacks(task)
                    binding.root.postDelayed(task, delay.coerceAtLeast(0L))
                }
            }
        }
        binding.renameConfirm.setOnClickListener { saveSearchRename() }
        binding.renameCancel.setOnClickListener { finishSearchRename() }
        binding.search.doAfterTextChanged { text ->
            if (searchRename != null) return@doAfterTextChanged
            adapter.closeSearchActions()
            cancelAutoLaunch()
            val query = text.toString()
            if (thunderMode && expanded && query.isNotBlank()) thunderEditAt = SystemClock.uptimeMillis()
            binding.appRename.isVisible = canRename && query.isNotBlank()
            highlightedContactQuery = null
            contactsAdapter.clearSelection()
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
                if (highlightedContact != null) openContact(highlightedContact)
                else if (adapter.committedQuery == query) adapter.launchFirstInList()
            }
            true
        }
    }

    private fun openSearch(thunder: Boolean) {
        val opening = !expanded
        expanded = true
        updateSearchVisibility(animateBackdrop = opening)
        if (opening) {
            LauncherMotion.reveal(
                binding.recyclerView, fromAlpha = 1f, riseDp = 22f,
                duration = LauncherMotion.SEARCH_DURATION,
            )
            LauncherMotion.reveal(
                binding.dock, fromAlpha = 1f, fromScale = 0.94f,
                duration = LauncherMotion.SEARCH_DURATION,
            )
        }
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
        if (!imeVisible) binding.search.showKeyboard()
    }

    private fun updateSearchVisibility(animateBackdrop: Boolean = false) {
        binding.homeSearch.isVisible = !expanded
        binding.search.isVisible = expanded
        binding.recyclerView.visibility = if (expanded) View.VISIBLE else View.INVISIBLE
        binding.recyclerView.updateRowMotion(expanded)
        updateSearchBackdrop(animateBackdrop)
        updateDockButtons()
    }

    private fun updateSearchBackdrop(animate: Boolean) {
        val target = if (expanded) 255 else 0
        backdropAnimator?.cancel()
        backdropAnimator = null
        if (!animate || !LauncherMotion.isEnabled(requireContext()) || searchBackdrop.alpha == target) {
            searchBackdrop.alpha = target
            return
        }
        backdropAnimator = ValueAnimator.ofInt(searchBackdrop.alpha, target).apply {
            duration = LauncherMotion.SEARCH_DURATION
            interpolator = LauncherMotion.settle
            addUpdateListener { searchBackdrop.alpha = it.animatedValue as Int }
            start()
        }
    }

    private fun setThunderMode(enabled: Boolean, animate: Boolean = true) {
        if (thunderMode == enabled) return
        cancelAutoLaunch()
        adapter.closeSearchActions()
        thunderMode = enabled
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
        val yellow = ContextCompat.getColor(requireContext(), R.color.bolt_search_yellow)
        val foreground = if (thunderMode) Color.BLACK else Color.WHITE
        binding.dock.setBackgroundResource(
            if (thunderMode) R.drawable.bolt_search_active else R.drawable.bolt_search_idle
        )
        binding.search.setTextColor(foreground)
        binding.search.setHintTextColor(if (thunderMode) 0xFF332A0D.toInt() else 0xFFBDBDBD.toInt())
        binding.search.setThunderCursor(thunderMode)
        binding.dockBolt.imageTintList = ColorStateList.valueOf(if (thunderMode) Color.BLACK else yellow)
        binding.dockBolt.contentDescription = getString(
            if (thunderMode) R.string.bolt_disable_thunder else R.string.bolt_enable_thunder
        )
    }

    private fun positionBolt(progress: Float) {
        boltProgress = progress
        val bolt = binding.dockBolt
        if (flag != Constants.FLAG_LAUNCH_APP) return
        val travel = (binding.dock.width - bolt.width).coerceAtLeast(0)
        bolt.translationX = -travel * progress
        val inset = resources.getDimensionPixelSize(R.dimen.bolt_search_button_width)
        binding.search.translationX = inset * progress
    }

    private fun currentResultsQuery(): String = searchRename?.query ?: binding.search.text.toString()

    private fun onResultsChanged(query: String) {
        if (_binding == null || query != currentResultsQuery()) return
        updateContactDecoration()
        val bottomAnchored = thunderMode || query.isNotBlank()
        val directionChanged = layoutManager.reverseLayout != bottomAnchored
        layoutManager.reverseLayout = bottomAnchored
        if (resultsQuery != query || directionChanged) layoutManager.scrollToPositionWithOffset(0, 0)
        resultsQuery = query
        updateResultHighlight(query)
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
                    updateResultHighlight(currentResultsQuery())
                }
                is ContactLoadResult.Failure -> Unit
            }
        }
    }

    private fun filterContacts(query: String) {
        if (!expanded || thunderMode || flag != Constants.FLAG_LAUNCH_APP || query.isBlank()) {
            contactsAdapter.clearResults()
            updateContactDecoration()
            updateDockButtons()
            return
        }
        contactsAdapter.filter(query, showAll = false) {
            updateContactDecoration()
            updateResultHighlight(query)
            if (highlightedContactQuery == query && contactsAdapter.selectedContact == null &&
                contactsAdapter.enterHighlightedResult != null) {
                layoutManager.scrollToPositionWithOffset(0, 0)
            }
        }
        loadContactsIfAllowed()
    }

    private fun updateContactDecoration() {
        val boundary = contactsAdapter.firstResult?.takeIf { adapter.itemCount > 0 }
            ?.let { adapter.itemCount to it.uri }
        if (boundary == decoratedContact) return
        decoratedContact = boundary
        binding.recyclerView.invalidateItemDecorations()
    }

    private fun selectContact(contact: ContactSearchAdapter.Contact) {
        val query = binding.search.text.toString()
        if (!expanded || thunderMode || contactsAdapter.committedQuery != query) return
        contactsAdapter.toggleSelection(contact)
        updateResultHighlight(query)
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
        val showShortcuts = expanded && !thunderMode && contact != null && searchRename == null
        binding.renameControls.isVisible = searchRename != null
        val shortcuts = binding.contactShortcuts
        if (shortcuts.isVisible != showShortcuts) {
            shortcuts.isVisible = showShortcuts
            if (!showShortcuts) {
                LauncherMotion.reset(shortcuts)
            } else if (!revealedContactShortcuts) {
                revealedContactShortcuts = true
                LauncherMotion.reveal(shortcuts, fromAlpha = 0.6f)
            }
        }
        val hasNumber = contact?.numbers?.isNotEmpty() == true
        listOf(binding.contactCall, binding.contactMessage, binding.contactWhatsapp).forEach {
            it.isEnabled = hasNumber
            it.alpha = if (hasNumber) 1f else 0.4f
        }
        binding.dockBolt.isVisible = flag == Constants.FLAG_LAUNCH_APP && (!expanded || thunderMode)
        val margin = when {
            searchRename != null -> resources.getDimensionPixelSize(R.dimen.bolt_rename_action_width) * 2
            showShortcuts -> resources.getDimensionPixelSize(R.dimen.bolt_contact_action_width) * 3 +
                resources.getDimensionPixelSize(R.dimen.bolt_contact_action_divider_width)
            thunderMode -> resources.getDimensionPixelSize(R.dimen.bolt_search_button_width)
            else -> 0
        }
        if ((binding.search.layoutParams as FrameLayout.LayoutParams).marginEnd != margin) {
            binding.search.updateLayoutParams<FrameLayout.LayoutParams> { marginEnd = margin }
        }
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
            try {
                startActivity(intent)
            } catch (_: ActivityNotFoundException) {
                requireContext().showToast(getString(R.string.bolt_action_unavailable))
            }
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
        try {
            startActivity(Intent(Intent.ACTION_VIEW, contact.uri))
        } catch (_: ActivityNotFoundException) {
            requireContext().showToast(getString(R.string.bolt_contacts_app_unavailable))
        } catch (_: SecurityException) {
            contactsAdapter.clearContacts()
            updateResultHighlight(currentResultsQuery())
        }
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

    private fun cancelAutoLaunch() {
        autoLaunch?.let { _binding?.root?.removeCallbacks(it) }
        autoLaunch = null
        thunderEditAt = null
    }

    private fun scheduleAutoLaunch(query: String) {
        val editedAt = thunderEditAt ?: return
        autoLaunch?.let(binding.root::removeCallbacks)
        autoLaunch = null
        if (!thunderMode || !expanded || query.isBlank() || adapter.itemCount != 1 ||
            adapter.firstLaunchableResult == null) return
        val committedAt = SystemClock.uptimeMillis()
        val task = object : Runnable {
            override fun run() {
                val view = _binding ?: return
                if (!thunderMode || !expanded || adapter.committedQuery != query ||
                    view.search.text.toString() != query || !view.root.hasWindowFocus() ||
                    viewLifecycleOwner.lifecycle.currentState != Lifecycle.State.RESUMED) {
                    cancelAutoLaunch()
                    return
                }
                val composing = view.search.text?.let { BaseInputConnection.getComposingSpanStart(it) >= 0 } == true
                if (composing) {
                    // Covers IMEs that remove composing spans through another input method.
                    view.root.postDelayed(this, COMPOSITION_RECHECK_MS)
                    return
                }
                val launchAt = SystemClock.uptimeMillis()
                cancelAutoLaunch()
                if (adapter.launchOnlyResult() && BuildConfig.DEBUG) {
                    Log.d("BoltThunderTiming", "editToCommitMs=${committedAt - editedAt} " +
                        "commitToLaunchRequestMs=${launchAt - committedAt}")
                }
            }
        }
        autoLaunch = task
        binding.root.postDelayed(task, (editedAt + THUNDER_QUIET_MS - committedAt).coerceAtLeast(0L))
    }

    private fun beginSearchRename(app: AppModel) {
        searchRename = SearchRename(app, binding.search.text.toString())
        binding.search.hint = getString(R.string.bolt_new_app_name)
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
        binding.search.hint = getString(R.string.bolt_search)
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
        try {
            startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            requireContext().showToast(getString(R.string.bolt_play_store_unavailable, app.appLabel))
        } catch (_: SecurityException) {
            requireContext().showToast(getString(R.string.bolt_play_store_unavailable, app.appLabel))
        }
    }

    fun collapseSearch(): Boolean {
        if (_binding == null || !expanded || !isHomePage) return false
        cancelAutoLaunch()
        val insets = androidx.core.view.ViewCompat.getRootWindowInsets(binding.root)
        if (insets?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime()) == true) {
            binding.search.hideKeyboard()
        } else {
            resetHomeSearch(animateBackdrop = true)
            LauncherMotion.reveal(binding.homeSearch, fromAlpha = 0.45f)
        }
        return true
    }

    fun resetHomeSearchUnlessReturningFromApp() {
        // Background callbacks run before we know how the user will return.
        if (searchAppLaunch?.isRecent() != true) resetHomeSearch()
    }

    fun resetHomeSearch(animateBackdrop: Boolean = false) {
        if (_binding == null || !isHomePage) return
        searchAppLaunch = null
        restoreSearchKeyboard = false
        dismissSearchEdit()
        expanded = false
        revealedContactShortcuts = false
        LauncherMotion.reset(binding.recyclerView)
        LauncherMotion.reset(binding.homeSearch)
        LauncherMotion.reset(binding.dock)
        cancelAutoLaunch()
        binding.search.hideKeyboard()
        binding.search.text?.clear()
        setThunderMode(false, animate = false)
        updateSearchVisibility(animateBackdrop)
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

    private fun initAdapter() {
        adapter = AppDrawerAdapter(
            flag,
            prefs.appLabelAlignment,
            appClickListener = { appModel ->
                if (searchRename != null) return@AppDrawerAdapter
                cancelAutoLaunch()
                if (flag == Constants.FLAG_LAUNCH_APP && isHomePage && expanded && !thunderMode) {
                    val keyboardWasVisible = imeWasVisible
                    if (!viewModel.launchAppResult(appModel)) return@AppDrawerAdapter
                    searchAppLaunch = SearchAppLaunch(SystemClock.elapsedRealtime(), keyboardWasVisible)
                } else {
                    binding.search.hideKeyboard()
                    viewModel.selectedApp(appModel, flag)
                }
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
            canShowSearchActions = { expanded && !thunderMode && flag == Constants.FLAG_LAUNCH_APP },
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
        binding.recyclerView.adapter = ConcatAdapter(adapter, contactsAdapter)
        binding.recyclerView.addItemDecoration(object : RecyclerView.ItemDecoration() {
            override fun getItemOffsets(outRect: Rect, view: View, parent: RecyclerView,
                                        state: RecyclerView.State) {
                if (adapter.itemCount > 0 && parent.getChildAdapterPosition(view) == adapter.itemCount &&
                    contactsAdapter.itemCount > 0) {
                    outRect.bottom = (4 * resources.displayMetrics.density).toInt()
                }
            }
        })
        binding.recyclerView.itemAnimator = null
        binding.recyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
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
        cancelAutoLaunch()
        super.onPause()
    }

    override fun onStop() {
        cancelAutoLaunch()
        stopLoadingContacts()
        if (isHomePage) resetHomeSearchUnlessReturningFromApp() else binding.search.hideKeyboard()
        super.onStop()
    }

    override fun onDestroyView() {
        binding.root.viewTreeObserver.removeOnWindowFocusChangeListener(searchWindowFocusListener)
        restoreSearchKeyboard = false
        searchRename = null
        searchAppLaunch = null
        cancelAutoLaunch()
        binding.search.onImeCommit = null
        stopLoadingContacts()
        boltAnimator?.cancel()
        boltAnimator = null
        backdropAnimator?.cancel()
        backdropAnimator = null
        LauncherMotion.reset(binding.recyclerView)
        LauncherMotion.reset(binding.homeSearch)
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
