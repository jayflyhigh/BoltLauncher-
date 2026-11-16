package app.olauncher.ui

import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
import app.olauncher.helper.isSystemAnimationsDisabled
import app.olauncher.helper.isEinkDisplay
import app.olauncher.helper.isSystemApp
import app.olauncher.helper.openAppInfo
import app.olauncher.helper.openSearch
import app.olauncher.helper.openUrl
import app.olauncher.helper.showKeyboard
import app.olauncher.helper.showToast
import app.olauncher.helper.uninstall

class AppDrawerFragment : BaseFragment() {

    private lateinit var inAppSearch: app.olauncher.helper.InAppSearch
    private var activeSearch: app.olauncher.helper.InAppSearch.Target? = null
    private var suggestionJob: kotlinx.coroutines.Job? = null
    private var searchEditor: androidx.appcompat.app.AlertDialog? = null
    private lateinit var prefs: Prefs
    private lateinit var adapter: AppDrawerAdapter
    private lateinit var linearLayoutManager: LinearLayoutManager
    private var searchTextView: TextView? = null
    private var cachedIsCjkKeyboard: Boolean? = null
    private var positionedAppQuery: String? = null
    private var homeSearchCommandsOnly = false

    private val isHomePage: Boolean
        get() = findNavController().currentDestination?.id == R.id.mainFragment

    private enum class ContactCommand(val icon: Int, val hint: String) {
        CALL(R.drawable.contact_call, "Call....."),
        MESSAGE(R.drawable.contact_message, "Message....."),
        WHATSAPP(R.drawable.contact_whatsapp, "WhatsApp message....."),
        ADD(R.drawable.contact_add, "Add a contact......."),
        SEARCH(R.drawable.dock_search, "Search contacts")
    }
    private var contactCommand: ContactCommand? = null
    private var commandKeys = emptyList<android.widget.ImageButton>()
    private var commandProgress = 0f
    private var contactLaunchQuery: String? = null
    private var contactDialog: androidx.appcompat.app.AlertDialog? = null
    private var contactSelectionBar = false
    private var unifiedReversed: Boolean? = null
    private var unifiedAdapter: androidx.recyclerview.widget.ConcatAdapter? = null
    private val appsHeader = SearchSectionAdapter("Apps")
    private val contactsHeader = SearchSectionAdapter("Contacts")
    private var contactsMode = false

    private fun isUnifiedSearch() = flag == Constants.FLAG_LAUNCH_APP && !contactsMode &&
        !thunderMode && activeSearch == null && !adapter.searchCommandsOnly

    private fun configureResultSections(query: String): Boolean {
        val previousAdapter = binding.recyclerView.adapter
        if (!isUnifiedSearch()) {
            unifiedReversed = null
            unifiedAdapter?.let { old -> old.adapters.toList().forEach { old.removeAdapter(it) } }
            unifiedAdapter = null
            val target = if (contactsMode) contactsAdapter else adapter
            if (binding.recyclerView.adapter !== target) binding.recyclerView.adapter = target
            return previousAdapter !== target
        }
        val reversed = query.isNotBlank()
        if (unifiedReversed != reversed || binding.recyclerView.adapter !== unifiedAdapter) {
            unifiedReversed = reversed
            unifiedAdapter?.let { old -> old.adapters.toList().forEach { old.removeAdapter(it) } }
            unifiedAdapter = if (reversed) androidx.recyclerview.widget.ConcatAdapter(
                adapter, appsHeader, contactsAdapter, contactsHeader)
            else androidx.recyclerview.widget.ConcatAdapter(appsHeader, adapter, contactsHeader, contactsAdapter)
            binding.recyclerView.adapter = unifiedAdapter
        }
        linearLayoutManager.reverseLayout = reversed
        linearLayoutManager.stackFromEnd = false
        updateSectionHeaders()
        return previousAdapter !== unifiedAdapter
    }

    private fun updateSectionHeaders() {
        appsHeader.show(isUnifiedSearch() && adapter.appFilteredList.isNotEmpty())
        contactsHeader.show(isUnifiedSearch() && contactsAdapter.itemCount > 0)
    }

    private fun filterUnifiedContacts(query: String) {
        contactsAdapter.filter(query, showAll = false) {
            if (_binding == null || !isUnifiedSearch() || binding.search.query.toString() != query) return@filter
            updateSectionHeaders()
        }
    }

    private fun showSelectedContactActions() {
        cancelAutoLaunch()
        if (contactsAdapter.selectedContact == null) {
            dismissSelectedContactActions()
            return
        }
        contactSelectionBar = true
        dockAnimator?.cancel()
        ensureCommandKeys()
        binding.homeSearch.visibility = View.GONE
        // Keep the focused editor attached and visible to the IME while actions cover it.
        binding.search.visibility = View.VISIBLE
        binding.search.alpha = 0f
        binding.dockSearch.visibility = View.GONE
        binding.dockKeys.visibility = View.INVISIBLE
        binding.dockAllContacts.visibility = View.GONE
        commandKeys.forEach { key ->
            key.animate().cancel()
            key.translationX = 0f
            key.translationZ = 12f
            key.alpha = 1f
            key.visibility = View.VISIBLE
            key.isEnabled = true
        }
    }

    private fun dismissSelectedContactActions() {
        if (!contactSelectionBar) return
        contactSelectionBar = false
        contactsAdapter.clearSelection()
        commandKeys.forEach { it.animate().cancel(); it.visibility = View.GONE }
        binding.dockKeys.visibility = View.VISIBLE
        binding.search.visibility = View.VISIBLE
        setDockExpanded(true, animate = false)
    }

    private lateinit var contactsAdapter: ContactSearchAdapter
    private var contactsLoad: kotlinx.coroutines.Job? = null
    private val contactsPermission = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted && _binding != null) loadContacts()
        else if (!granted && _binding != null) {
            contactsAdapter.contacts = emptyList()
            contactsAdapter.clearSelection()
            if (contactsMode) filterContacts(binding.search.query.toString())
            else if (isUnifiedSearch()) filterUnifiedContacts(binding.search.query.toString())
            requireContext().showToast("Allow contacts access to search contacts")
        }
    }

    private var thunderMode = false
    private var pendingAutoLaunch: Runnable? = null
    private var lastAutoLaunchedQuery: String? = null
    private var dockThunderTransition = false
    private var dockExpanded = false
    private var dockAnimator: android.animation.ValueAnimator? = null
    private val searchRevealBounds = android.graphics.Rect()

    private var flag = Constants.FLAG_LAUNCH_APP
    private var canRename = false
    private var combinedAppsJob: kotlinx.coroutines.Job? = null
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
        activeSearch = null
        contactsMode = false
        contactCommand = null
        commandKeys = emptyList()
        commandProgress = 0f
        thunderMode = false
        _binding = FragmentAppDrawerBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        prefs = Prefs(requireContext())
        inAppSearch = app.olauncher.helper.InAppSearch(requireContext().applicationContext)
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
                        offset = if (requireContext().isEinkDisplay() || requireContext().isSystemAnimationsDisabled()) 0f
                            else (startBottom - dockBottom()).toFloat()
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
        binding.root.canSwipeSearch = {
            isHomePage && flag == Constants.FLAG_LAUNCH_APP && !dockExpanded && !contactsMode && !thunderMode
        }
        binding.root.onSwipeUp = { openHomeSearch() }
        val threshold = 64f * resources.displayMetrics.density
        var notificationsOpened = false
        val detector = android.view.GestureDetector(requireContext(),
            object : android.view.GestureDetector.SimpleOnGestureListener() {
                override fun onDown(event: android.view.MotionEvent): Boolean {
                    notificationsOpened = false
                    return true
                }

                override fun onSingleTapUp(event: android.view.MotionEvent): Boolean {
                    if (!isHomePage || dockExpanded) return false
                    if (contactsMode) resetHomeSearch()
                    else if (flag == Constants.FLAG_LAUNCH_APP) {
                        homeSearchCommandsOnly = !homeSearchCommandsOnly
                        updateHomeSearchHint()
                    }
                    return true
                }

                override fun onLongPress(event: android.view.MotionEvent) {
                    if (isHomePage && !dockExpanded && !contactsMode) showHomeSettings()
                }

                override fun onScroll(
                    start: android.view.MotionEvent?,
                    current: android.view.MotionEvent,
                    distanceX: Float,
                    distanceY: Float,
                ): Boolean {
                    if (start == null || notificationsOpened || !isHomePage || dockExpanded || contactsMode) return false
                    val dy = current.y - start.y
                    val dx = current.x - start.x
                    if (dy > threshold && dy > kotlin.math.abs(dx)) {
                        notificationsOpened = true
                        app.olauncher.helper.expandNotificationDrawer(requireContext())
                    }
                    return true
                }
            })
        // Keep rapid home taps available for dismissing contact commands.
        detector.setOnDoubleTapListener(null)
        detector.setIsLongpressEnabled(true)
        binding.root.setOnTouchListener { _, event ->
            if (isHomePage && !dockExpanded) detector.onTouchEvent(event) else false
        }
    }

    private fun updateHomeSearchHint() {
        val hint = if (homeSearchCommandsOnly) "Search within apps" else "Search"
        binding.homeSearch.text = hint
        binding.homeSearch.contentDescription = hint
    }

    private fun openHomeSearch() {
        clearAppSearch()
        setContactsMode(false)
        adapter.searchCommandsOnly = homeSearchCommandsOnly
        binding.search.queryHint = binding.homeSearch.text
        setThunderMode(false)
        configureResultSections(binding.search.query.toString())
        adapter.filterApps(binding.search.query.toString())
        if (isUnifiedSearch()) {
            filterUnifiedContacts(binding.search.query.toString())
            if (androidx.core.content.ContextCompat.checkSelfPermission(requireContext(),
                    android.Manifest.permission.READ_CONTACTS) == android.content.pm.PackageManager.PERMISSION_GRANTED) loadContacts()
            else {
                contactsAdapter.contacts = emptyList()
                filterUnifiedContacts(binding.search.query.toString())
                contactsPermission.launch(android.Manifest.permission.READ_CONTACTS)
            }
        }
        setDockExpanded(true)
    }

    private fun initDock() {
        updateHomeSearchHint()
        binding.homeSearch.setOnClickListener { openHomeSearch() }
        binding.dockSearch.setOnClickListener { switchSearchDestination() }
        binding.dockSearch.setOnLongClickListener { showAppSearchEditor(); true }
        searchTextView?.let {
            binding.homeSearch.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, it.textSize)
            binding.homeSearch.typeface = it.typeface
        }
        binding.dockBolt.setOnClickListener {
            clearAppSearch()
            setContactsMode(false)
            setThunderMode(true)
            setDockExpanded(true)
        }
        binding.dockContacts.visibility = View.INVISIBLE
        binding.homeSearch.setPadding((16 * resources.displayMetrics.density).toInt(), 0,
            (64 * resources.displayMetrics.density).toInt(), 0)
        binding.dockAllContacts.setOnClickListener { showContactCommands() }
        setThunderMode(false)
        searchTextView?.gravity = android.view.Gravity.START or android.view.Gravity.CENTER_VERTICAL
    }

    private fun ensureCommandKeys() {
        if (commandKeys.isNotEmpty()) return
        val slotWidth = binding.dockKeys.width / 5
        val icons = listOf(R.drawable.dock_search) + ContactCommand.entries.filter { it != ContactCommand.SEARCH }.map { it.icon }
        commandKeys = icons.mapIndexed { index, _ ->
            android.widget.ImageButton(requireContext()).apply {
                setBackgroundResource(R.drawable.dock_key)
                setImageResource(icons[index])
                imageTintList = android.content.res.ColorStateList.valueOf(
                    if (icons[index] == R.drawable.contact_whatsapp) android.graphics.Color.rgb(37, 211, 102)
                    else android.graphics.Color.WHITE
                )
                val padding = (13 * resources.displayMetrics.density).toInt()
                setPadding(padding, padding, padding, padding)
                contentDescription = if (index == 0) "Search contacts" else ContactCommand.entries[index - 1].hint.trimEnd('.')
                visibility = View.GONE
                binding.dock.addView(this, android.widget.FrameLayout.LayoutParams(slotWidth - (8 * resources.displayMetrics.density).toInt(), binding.dockContacts.height).apply {
                    leftMargin = index * slotWidth + (4 * resources.displayMetrics.density).toInt()
                    topMargin = binding.dockContacts.top
                })
                setOnClickListener {
                    if (contactSelectionBar) {
                        if (index == 0) {
                            dismissSelectedContactActions()
                            showContactCommands()
                        } else {
                            val selected = contactsAdapter.selectedContact
                            val command = ContactCommand.entries[index - 1]
                            if (command == ContactCommand.ADD) addContact()
                            else if (selected != null) runContactCommand(selected, command)
                        }
                        return@setOnClickListener
                    }
                    if (contactCommand != null) animateContactSearch(false)
                    else if (index == 0) selectContactCommand(ContactCommand.SEARCH)
                    else selectContactCommand(ContactCommand.entries[index - 1])
                }
            }
        }
    }

    private fun showContactCommands() {
        dismissSelectedContactActions()
        clearAppSearch()
        binding.homeSearch.visibility = View.GONE
        binding.dockSearch.visibility = View.GONE
        binding.dockKeys.translationZ = 0f
        ensureCommandKeys()
        cancelAutoLaunch()
        setContactsMode(true)
        setThunderMode(false)
        contactCommand = null
        dockExpanded = false
        binding.search.hideKeyboard()
        binding.search.visibility = View.INVISIBLE
        binding.search.setQuery("", false)
        // Do not expose cached contacts after permission has been revoked.
        if (androidx.core.content.ContextCompat.checkSelfPermission(requireContext(),
                android.Manifest.permission.READ_CONTACTS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            contactsAdapter.contacts = emptyList()
        }
        // Show the full list above the contact shortcuts without entering search.
        binding.recyclerView.visibility = View.VISIBLE
        filterContacts("")
        binding.recyclerView.animateSearchResults("", force = true)
        if (androidx.core.content.ContextCompat.checkSelfPermission(requireContext(),
                android.Manifest.permission.READ_CONTACTS) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            loadContacts()
        } else {
            contactsPermission.launch(android.Manifest.permission.READ_CONTACTS)
        }
        dockAnimator?.cancel()
        binding.dockSearch.tag = 0f
        binding.dockKeys.visibility = View.INVISIBLE
        binding.dockAllContacts.visibility = View.GONE
        commandProgress = 0f
        commandKeys.forEachIndexed { index, key ->
            key.animate().cancel()
            key.translationZ = 0f
            key.visibility = View.VISIBLE
            key.isEnabled = false
            key.alpha = if (index == 0) 1f else 0f
            key.translationX = if (index == 0)
                0f else 12f * resources.displayMetrics.density
            key.animate().translationX(0f).alpha(1f)
                .setDuration(if (requireContext().isEinkDisplay() || requireContext().isSystemAnimationsDisabled()) 0L else 320L)
                .setInterpolator(android.view.animation.PathInterpolator(0.2f, 0f, 0.2f, 1f))
                .withEndAction { key.isEnabled = true }.start()
        }
    }

    private fun selectContactCommand(command: ContactCommand) {
        if (command == ContactCommand.ADD) {
            addContact()
            return
        }
        val selected = contactsAdapter.selectedContact
        if (selected != null && command in listOf(ContactCommand.CALL, ContactCommand.MESSAGE, ContactCommand.WHATSAPP)) {
            runContactCommand(selected, command)
            return
        }
        contactsAdapter.clearSelection()
        contactCommand = command
        contactLaunchQuery = null
        binding.search.queryHint = command.hint
        searchTextView?.inputType = android.text.InputType.TYPE_CLASS_TEXT
        binding.search.setQuery("", false)
        binding.recyclerView.visibility = if (command == ContactCommand.ADD) View.INVISIBLE else View.VISIBLE
        animateContactSearch(true)
        if (command != ContactCommand.ADD) {
            if (androidx.core.content.ContextCompat.checkSelfPermission(requireContext(),
                    android.Manifest.permission.READ_CONTACTS) == android.content.pm.PackageManager.PERMISSION_GRANTED) loadContacts()
            else contactsPermission.launch(android.Manifest.permission.READ_CONTACTS)
        }
    }

    private fun animateContactSearch(expanded: Boolean) {
        val selected = contactCommand ?: return
        configureSearchGeometry(contact = true)
        cancelAutoLaunch()
        dockAnimator?.cancel()
        dockExpanded = expanded
        if (!expanded) {
            binding.search.hideKeyboard()
            binding.search.clearFocus()
        }
        val selectedIndex = if (selected == ContactCommand.SEARCH) 0 else selected.ordinal + 1
        binding.search.visibility = View.VISIBLE
        commandKeys.forEach { it.animate().cancel(); it.isEnabled = false }
        if (expanded) binding.search.showKeyboard()
        dockAnimator = android.animation.ValueAnimator.ofFloat(commandProgress, if (expanded) 1f else 0f).apply {
            duration = if (requireContext().isEinkDisplay() || requireContext().isSystemAnimationsDisabled()) 0L
                else ((if (expanded) 380 else 300) * kotlin.math.abs((if (expanded) 1f else 0f) - commandProgress)).toLong()
            interpolator = android.view.animation.PathInterpolator(0.2f, 0f, 0.2f, 1f)
            addUpdateListener {
                val b = _binding ?: return@addUpdateListener
                val progress = it.animatedValue as Float
                commandProgress = progress
                commandKeys.forEachIndexed { index, key ->
                    key.alpha = if (index == selectedIndex) 1f else 1f - progress
                    key.translationX = if (index == selectedIndex)
                        (commandKeys[0].left - key.left) * progress else 0f
                    key.translationZ = if (index == selectedIndex) 12f else 0f
                }
                b.search.alpha = progress
                b.search.translationX = 0f
                searchRevealBounds.set(0, 0, (b.search.width * progress).toInt(), b.search.height)
                b.search.clipBounds = searchRevealBounds
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                private var cancelled = false
                override fun onAnimationCancel(animation: android.animation.Animator) {
                    cancelled = true
                }
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    if (cancelled || _binding == null) return
                    commandKeys.forEachIndexed { index, key -> key.isEnabled = !expanded || index == selectedIndex }
                    if (!expanded) {
                        contactCommand = null
                        contactLaunchQuery = null
                        binding.search.setQuery("", false)
                        binding.search.visibility = View.INVISIBLE
                        // Returning from a contact command keeps the contacts list visible.
                        binding.recyclerView.visibility = View.VISIBLE
                        filterContacts("")
                    }
                }
            })
            start()
        }
    }

    private fun scheduleContactLaunch(query: String) {
        cancelAutoLaunch()
        val command = contactCommand ?: return
        if (!dockExpanded || command in listOf(ContactCommand.ADD, ContactCommand.SEARCH) || query.isBlank() ||
            contactsAdapter.itemCount != 1 || query == contactLaunchQuery) return
        val task = object : Runnable {
            override fun run() {
                val b = _binding ?: return
                if (!contactsMode || !dockExpanded || contactCommand != command ||
                    b.search.query.toString() != query || contactsAdapter.committedQuery != query ||
                    contactsAdapter.itemCount != 1 ||
                    !viewLifecycleOwner.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) return
                if (isSearchComposing()) { b.root.postDelayed(this, 500L); return }
                pendingAutoLaunch = null
                contactLaunchQuery = query
                contactsAdapter.openFirst()
            }
        }
        pendingAutoLaunch = task
        binding.root.postDelayed(task, 500L)
    }

    private fun runContactCommand(
        contact: ContactSearchAdapter.Contact,
        command: ContactCommand? = contactCommand
    ) {
        cancelAutoLaunch()
        contactLaunchQuery = binding.search.query.toString()
        if (command == null || command == ContactCommand.SEARCH) {
            try { startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, contact.uri)) }
            catch (_: android.content.ActivityNotFoundException) { requireContext().showToast("No contacts app available") }
            return
        }
        if (contact.numbers.isEmpty()) { requireContext().showToast("This contact has no phone number"); return }
        fun launchNumber(number: String) {
            val intent = when (command) {
                ContactCommand.CALL -> android.content.Intent(android.content.Intent.ACTION_DIAL,
                    android.net.Uri.fromParts("tel", number, null))
                ContactCommand.MESSAGE -> android.content.Intent(android.content.Intent.ACTION_SENDTO,
                    android.net.Uri.fromParts("smsto", number, null))
                ContactCommand.WHATSAPP -> android.content.Intent(android.content.Intent.ACTION_VIEW,
                    android.net.Uri.parse("https://wa.me/" + number.filter { it.isDigit() })).setPackage("com.whatsapp")
                ContactCommand.ADD, ContactCommand.SEARCH -> return
            }
            try { startActivity(intent) }
            catch (_: android.content.ActivityNotFoundException) { requireContext().showToast("No app available for this command") }
        }
        if (contact.numbers.size == 1) launchNumber(contact.numbers.first())
        else {
            if (contactDialog?.isShowing == true) return
            contactDialog = androidx.appcompat.app.AlertDialog.Builder(requireContext()).setTitle(contact.name)
                .setItems(contact.numbers.toTypedArray()) { _, index -> launchNumber(contact.numbers[index]) }
                .setNegativeButton(android.R.string.cancel, null)
                .setOnDismissListener { contactDialog = null }.show()
        }
    }

    private fun addContact() {
        cancelAutoLaunch()
        val intent = android.content.Intent(android.content.Intent.ACTION_INSERT).apply {
            type = android.provider.ContactsContract.Contacts.CONTENT_TYPE
        }
        try { startActivity(intent) }
        catch (_: android.content.ActivityNotFoundException) {
            requireContext().showToast("No contacts app available")
        }
    }

    private fun setContactsMode(enabled: Boolean) {
        suggestionJob?.cancel()
        adapter.cancelFiltering()
        positionedAppQuery = null
        contactsMode = enabled
        contactsAdapter.clearSelection()
        cancelAutoLaunch()
        configureResultSections(binding.search.query.toString())
        binding.search.queryHint = if (enabled) "Search contacts" else "Search"
        if (enabled) filterContacts(binding.search.query.toString())
        else {
            adapter.filterApps(binding.search.query.toString())
            if (isUnifiedSearch()) filterUnifiedContacts(binding.search.query.toString())
        }
    }

    private fun filterContacts(query: String) {
        if (query != contactLaunchQuery) contactLaunchQuery = null
        contactsAdapter.filter(query) {
            val b = _binding ?: return@filter
            if (!contactsMode || b.search.query.toString() != query) return@filter
            linearLayoutManager.reverseLayout = query.isNotBlank()
            linearLayoutManager.stackFromEnd = false
            b.recyclerView.scrollToPosition(0)
            b.recyclerView.animateSearchResults(query)
            scheduleContactLaunch(query)
        }
    }

    private fun loadContacts() {
        contactsLoad?.cancel()
        val context = requireContext().applicationContext
        contactsLoad = viewLifecycleOwner.lifecycleScope.launch {
            val contacts = withContext(Dispatchers.IO) { ContactSearchAdapter.load(context) }
            if (_binding != null) {
                contactsAdapter.contacts = contacts
                if (contactsMode) filterContacts(binding.search.query.toString())
                else if (isUnifiedSearch()) filterUnifiedContacts(binding.search.query.toString())
            }
        }
    }

    private fun setThunderMode(enabled: Boolean) {
        cancelAutoLaunch()
        suggestionJob?.cancel()
        thunderMode = enabled
        lastAutoLaunchedQuery = null
        adapter.setThunderMode(enabled)
        configureResultSections(binding.search.query.toString())
        adapter.filterApps(binding.search.query.toString())
        if (isUnifiedSearch()) filterUnifiedContacts(binding.search.query.toString())
        val foreground = if (enabled) android.graphics.Color.BLACK else android.graphics.Color.WHITE
        binding.dock.setBackgroundResource(if (enabled) R.drawable.dock_yellow else R.drawable.dock_search_bar)
        binding.search.setBackgroundColor(android.graphics.Color.TRANSPARENT)
        val iconPadding = (13 * resources.displayMetrics.density).toInt()
        binding.dockSearch.setPadding(iconPadding, iconPadding, iconPadding, iconPadding)
        binding.dockSearch.setBackgroundColor(android.graphics.Color.TRANSPARENT)
        if (activeSearch == null) {
            binding.dockSearch.contentDescription = if (adapter.searchCommandsOnly)
                "Search destination: Search within Apps. Tap to switch; hold to edit" else
                "Search destination: Apps. Tap to switch; hold to edit Search within Apps"
            binding.dockSearch.setImageResource(if (adapter.searchCommandsOnly) R.drawable.dock_search else R.drawable.dock_apps)
            binding.dockSearch.imageTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.WHITE)
        }
        // Both controls share the search bar surface; yellow identifies Bolt at rest.
        binding.dockBolt.imageTintList = android.content.res.ColorStateList.valueOf(
            if (enabled) android.graphics.Color.BLACK else 0xFFFFD54F.toInt()
        )
        searchTextView?.apply {
            setTextColor(foreground)
            setHintTextColor(if (enabled) android.graphics.Color.BLACK else 0xFFBDBDBD.toInt())
            isActivated = enabled
            (this as? AliasSearchInput)?.setThunderCursor(enabled)
            refreshDrawableState()
        }
    }

    private fun cancelAutoLaunch() {
        pendingAutoLaunch?.let { _binding?.root?.removeCallbacks(it) }
        pendingAutoLaunch = null
    }

    private fun scheduleAutoLaunch(query: String) {
        cancelAutoLaunch()
        if (activeSearch != null || !thunderMode || !dockExpanded || query.isBlank() ||
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
        if (_binding != null && contactSelectionBar) {
            dismissSelectedContactActions()
            return true
        }
        if (_binding != null && contactsMode) {
            val keyboardVisible = androidx.core.view.ViewCompat.getRootWindowInsets(binding.root)
                ?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime()) == true
            if (dockExpanded && keyboardVisible) {
                // Keep the command, query, and results available for scrolling.
                binding.search.hideKeyboard()
            } else if (contactCommand != null) {
                animateContactSearch(false)
            } else {
                resetHomeSearch()
            }
            return true
        }
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
        contactSelectionBar = false
        clearAppSearch()
        dockAnimator?.cancel()
        commandKeys.forEach { it.animate().cancel(); it.visibility = View.GONE }
        binding.dockKeys.visibility = View.VISIBLE
        contactCommand = null
        commandProgress = 0f
        contactLaunchQuery = null
        setContactsMode(false)
        setThunderMode(false)
        binding.search.setQuery("", false)
        setDockExpanded(false, animate = animate)
    }

    private fun configureSearchGeometry(contact: Boolean = false) {
        binding.dock.setSearchProgress(null)
        val density = resources.displayMetrics.density
        val params = binding.search.layoutParams as android.widget.FrameLayout.LayoutParams
        val startMargin = if (contact && commandKeys.isNotEmpty())
            commandKeys[0].right + (6 * density).toInt()
        else if (!thunderMode && !contactsMode && activeSearch == null)
            (4 * density).toInt()
        else (56 * density).toInt()
        val endMargin = ((if (!contact && isUnifiedSearch()) 56 else 4) * density).toInt()
        if (params.marginStart != startMargin || params.marginEnd != endMargin) {
            params.marginStart = startMargin
            params.marginEnd = endMargin
            binding.search.layoutParams = params
        }
        binding.search.setPadding(0, 0, 0, 0)
        binding.dockSearch.visibility = if (dockExpanded && !contact && !contactsMode && !thunderMode &&
            activeSearch != null) View.VISIBLE else View.GONE
    }

    private fun setDockExpanded(expanded: Boolean, animate: Boolean = true) {
        val opening = expanded && !dockExpanded
        dockExpanded = expanded
        if (expanded) dockThunderTransition = thunderMode
        val animateThunder = dockThunderTransition
        binding.recyclerView.visibility = if (expanded || !isHomePage) View.VISIBLE else View.INVISIBLE
        if (opening && animate) binding.recyclerView.animateSearchResults(binding.search.query.toString(), force = true)
        dockAnimator?.cancel()
        val query = binding.search.query.toString()
        if (configureResultSections(query)) {
            if (contactsMode) filterContacts(query) else adapter.filterApps(query)
            if (isUnifiedSearch()) filterUnifiedContacts(query)
        }
        configureSearchGeometry()
        binding.dock.setSearchProgress((binding.dockSearch.tag as? Float) ?: 0f)
        binding.dockKeys.visibility = View.VISIBLE
        binding.dockContacts.isEnabled = false
        binding.dockContacts.visibility = View.INVISIBLE
        binding.dockAllContacts.isEnabled = expanded && isUnifiedSearch()
        binding.dockAllContacts.visibility = if (expanded && isUnifiedSearch()) View.VISIBLE else View.GONE
        binding.dockBolt.isEnabled = !expanded
        binding.dockKeys.translationZ = if (animateThunder || isUnifiedSearch()) 8f else 0f
        binding.search.visibility = View.VISIBLE
        binding.search.clipBounds = null
        binding.search.alpha = 1f
        binding.search.translationX = 0f
        binding.homeSearch.visibility = View.VISIBLE
        binding.homeSearch.isClickable = !expanded
        if (expanded) binding.search.showKeyboard()
        else {
            binding.search.hideKeyboard()
            binding.search.clearFocus()
        }
        // Expand into the freed right-hand slot while the selected icon takes
        // the left slot. Use the existing reversible dock timing and easing.
        val start = (binding.dockSearch.tag as? Float) ?: 0f
        val target = if (expanded) 1f else 0f
        dockAnimator = android.animation.ValueAnimator.ofFloat(start, target).apply {
            duration = if (animate && !requireContext().isEinkDisplay() && !requireContext().isSystemAnimationsDisabled())
                (320 * kotlin.math.abs(target - start)).toLong() else 0L
            interpolator = android.view.animation.PathInterpolator(0.2f, 0f, 0.2f, 1f)
            addUpdateListener {
                val b = _binding ?: return@addUpdateListener
                val progress = it.animatedValue as Float
                b.dockSearch.tag = progress
                b.homeSearch.alpha = 1f - progress
                b.search.alpha = progress
                b.dockSearch.alpha = progress
                b.dockSearch.translationX = 0f
                b.dockContacts.alpha = 0f
                b.dockContacts.translationX = 0f
                b.dockAllContacts.alpha = if (isUnifiedSearch()) progress else 0f
                b.dockBolt.alpha = if (animateThunder) 1f else 1f - progress
                b.dockBolt.translationX = if (animateThunder)
                    (b.dockContacts.left - b.dockBolt.left) * progress else 0f
                b.dock.setSearchProgress(progress)
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                private var cancelled = false
                override fun onAnimationCancel(animation: android.animation.Animator) { cancelled = true }
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    if (cancelled || _binding == null || dockExpanded != expanded) return
                    binding.homeSearch.visibility = if (expanded) View.GONE else View.VISIBLE
                    binding.search.visibility = if (expanded) View.VISIBLE else View.INVISIBLE
                    if (!expanded) dockThunderTransition = false
                }
            })
            start()
        }
    }

    private fun switchSearchDestination() {
        if (!dockExpanded || contactsMode || thunderMode) return
        val input = searchTextView as? android.widget.EditText
        val start = input?.selectionStart ?: -1
        val end = input?.selectionEnd ?: -1
        val commandsOnly = !adapter.searchCommandsOnly
        // Do not replace the query, change focus, or issue an IME request here.
        // This also preserves a deliberately hidden keyboard.
        clearAppSearch()
        adapter.searchCommandsOnly = commandsOnly
        binding.search.queryHint = if (commandsOnly) "Search within apps" else "Search"
        setThunderMode(false)
        configureSearchGeometry()
        configureResultSections(binding.search.query.toString())
        adapter.filterApps(binding.search.query.toString())
        if (isUnifiedSearch()) filterUnifiedContacts(binding.search.query.toString())
        setDockExpanded(true, animate = false)
        if (input != null && start >= 0 && end >= 0) {
            input.setSelection(start.coerceAtMost(input.length()), end.coerceAtMost(input.length()))
        }
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

    private fun clearAppSearch() {
        suggestionJob?.cancel()
        adapter.searchCommandsOnly = false
        activeSearch = null
        adapter.searchPackage = null
        binding.dockSearch.contentDescription = "Search destination: Apps. Tap to switch; hold to edit Search within Apps"
        binding.dockSearch.setOnLongClickListener { showAppSearchEditor(); true }
    }

    private fun activateAppSearch(target: app.olauncher.helper.InAppSearch.Target, query: String = "") {
        cancelAutoLaunch()
        setContactsMode(false)
        setThunderMode(false)
        activeSearch = target
        adapter.searchPackage = target.app.appPackage
        val size = (52 * resources.displayMetrics.density).toInt()
        val bitmap = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = app.olauncher.helper.InAppSearch.foreground(target.color)
            textSize = size * .46f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            textAlign = android.graphics.Paint.Align.CENTER
        }
        val code = inAppSearch.alias(target)
        val textWidth = paint.measureText(code)
        if (textWidth > size * .86f) paint.textSize *= size * .86f / textWidth
        android.graphics.Canvas(bitmap).drawText(code, size / 2f,
            size / 2f - (paint.ascent() + paint.descent()) / 2f, paint)
        binding.dockSearch.setPadding(0, 0, 0, 0)
        binding.dockSearch.setImageBitmap(bitmap)
        binding.dockSearch.imageTintList = null
        binding.dockSearch.background = app.olauncher.helper.InAppSearch.background(target.color, resources.displayMetrics.density)
        binding.dockSearch.contentDescription = "Search in ${target.app.appLabel}, alias ${inAppSearch.alias(target)}"
        binding.dockSearch.setOnLongClickListener { showAppSearchEditor(target); true }
        binding.search.queryHint = "Search in ${target.app.appLabel}....."
        binding.search.setQuery(query, false)
        adapter.filterApps(query)
        updateSearchSuggestions(query)
        setDockExpanded(true)
        binding.search.showKeyboard()
    }

    // Suggestions belong to Search within apps, not the unified Search page.
    private fun canShowWebSuggestions(): Boolean = false

    private fun updateSearchSuggestions(query: String) {
        suggestionJob?.cancel()
        val target = activeSearch
        if (target == null && (!canShowWebSuggestions() || adapter.appFilteredList.isNotEmpty())) return
        if (query.isBlank()) return
        app.olauncher.helper.SearchSuggestions.cached(query)?.let {
            adapter.showSuggestions(query, it)
            return
        }
        suggestionJob = viewLifecycleOwner.lifecycleScope.launch {
            val suggestions = app.olauncher.helper.SearchSuggestions.fetch(query)
            if (_binding != null && !contactsMode && !thunderMode &&
                activeSearch == target && (target != null || canShowWebSuggestions()) &&
                binding.search.query.toString() == query) {
                adapter.showSuggestions(query, suggestions)
            }
        }
    }

    private fun showAppSearchEditor(
        initial: app.olauncher.helper.InAppSearch.Target? = activeSearch,
        selectedApp: AppModel.App? = null,
    ) {
        if (searchEditor?.isShowing == true) return
        cancelAutoLaunch()
        binding.search.hideKeyboard()
        val apps = adapter.appsList.filterIsInstance<AppModel.App>()
            .filter { it.user == Process.myUserHandle() }.distinctBy { it.appPackage }
            .sortedBy { it.appLabel.lowercase(java.util.Locale.ROOT) }
        searchEditor = AppSearchEditor.show(requireContext(), apps, inAppSearch, initial, selectedApp) {
            val current = activeSearch
            if (current != null) {
                val updated = inAppSearch.targets[current.app.appPackage]
                if (updated != null) activateAppSearch(updated, binding.search.query.toString())
                else resetHomeSearch()
            }
            adapter.notifyItemRangeChanged(0, adapter.itemCount)
            adapter.filterApps(binding.search.query.toString())
        }
    }

    private fun sendSearchTo(target: app.olauncher.helper.InAppSearch.Target) {
        cancelAutoLaunch()
        val query = binding.search.query.toString().trim()
        if (query.isBlank()) {
            binding.search.showKeyboard()
            return
        }
        if (!inAppSearch.launch(target, query)) {
            requireContext().showToast("Search is unavailable in ${target.app.appLabel}. Hold its code to edit its URL.")
        }
    }

    private fun launchAppSearch(): Boolean {
        val target = activeSearch ?: return false
        val query = binding.search.query.toString().trim()
        if (query.isBlank()) return true
        try {
            if (!inAppSearch.launch(target, query)) {
                clearAppSearch()
                setThunderMode(false)
                binding.search.queryHint = "Search"
                adapter.filterApps(query)
                adapter.notifyItemRangeChanged(0, adapter.itemCount)
                requireContext().showToast("Search is unavailable in ${target.app.appLabel}. Hold search to edit its URL.")
            }
        } catch (_: Exception) { requireContext().showToast("This app could not open search") }
        return true
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

    private fun undoSearchAlias(): Boolean {
        if (activeSearch == null) return false
        cancelAutoLaunch()
        val commandsOnly = adapter.searchCommandsOnly
        clearAppSearch()
        adapter.searchCommandsOnly = commandsOnly
        binding.search.queryHint = if (commandsOnly) "Search within apps" else "Search"
        adapter.notifyItemRangeChanged(0, adapter.itemCount)
        adapter.filterApps(binding.search.query.toString())
        setThunderMode(false)
        configureSearchGeometry()
        binding.search.showKeyboard()
        return true
    }

    private fun toggleEmptySearchDestination(): Boolean {
        if (!dockExpanded || contactsMode || thunderMode || activeSearch != null ||
            flag != Constants.FLAG_LAUNCH_APP || binding.search.query.isNotEmpty()) return false
        switchSearchDestination()
        return true
    }

    private fun initSearch() {
        (binding.search.findViewById<android.view.View>(R.id.search_src_text) as? AliasSearchInput)
            ?.apply {
                undoAlias = { undoSearchAlias() }
                submitEmpty = { toggleEmptySearchDestination() }
            }
        binding.search.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?): Boolean {
                cancelAutoLaunch()
                if (contactSelectionBar) return true
                if (launchAppSearch()) return true
                if (contactsMode && contactCommand == ContactCommand.ADD) addContact()
                else if (contactsMode) {
                    if (contactsAdapter.committedQuery == binding.search.query.toString()) contactsAdapter.openFirst()
                }
                else if (adapter.searchCommandsOnly) {
                    if (!inAppSearch.launchBrowser(binding.search.query.toString()))
                        requireContext().showToast("No browser available")
                }
                else if (adapter.committedQuery == binding.search.query.toString()) {
                    val text = binding.search.query.toString().trim()
                    if (!thunderMode && flag == Constants.FLAG_LAUNCH_APP &&
                        adapter.appFilteredList.isEmpty() && text.isNotBlank()) {
                        if (isUnifiedSearch() && contactsAdapter.committedQuery == binding.search.query.toString() &&
                            contactsAdapter.itemCount > 0) {
                            contactsAdapter.openFirst()
                            return true
                        }
                        if (!inAppSearch.launchBrowser(text))
                            requireContext().showToast("No browser available")
                    } else adapter.launchFirstInList()
                }
                return true
            }

            override fun onQueryTextChange(newText: String): Boolean {
                try {
                    cancelAutoLaunch()
                    if (contactSelectionBar) dismissSelectedContactActions()
                    contactsAdapter.clearSelection()
                    suggestionJob?.cancel()
                    if (activeSearch == null && !contactsMode && flag == Constants.FLAG_LAUNCH_APP && newText.contains(' ') && !isSearchComposing()) {
                        val confirmed = inAppSearch.confirmed(newText)
                        if (confirmed != null) {
                            activateAppSearch(confirmed.first, confirmed.second)
                            return true
                        }
                    }
                    if (newText != lastAutoLaunchedQuery) lastAutoLaunchedQuery = null
                    if (contactsMode) {
                        if (contactCommand != ContactCommand.ADD) filterContacts(newText)
                    } else {
                        configureResultSections(newText)
                        adapter.filterApps(newText)
                        if (isUnifiedSearch()) filterUnifiedContacts(newText)
                        if (activeSearch != null) updateSearchSuggestions(newText)
                    }
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
        contactsAdapter = ContactSearchAdapter { contact ->
            if (isUnifiedSearch()) {
                contactsAdapter.toggleSelection(contact)
                showSelectedContactActions()
            } else if (contactsMode && contactCommand == null) contactsAdapter.toggleSelection(contact)
            else runContactCommand(contact)
        }
        adapter = AppDrawerAdapter(
            flag,
            prefs.appLabelAlignment,
            appClickListener = { appModel ->
                cancelAutoLaunch()
                if (appModel is AppModel.SearchSuggestion) {
                    activeSearch?.let { target ->
                        if (!inAppSearch.launch(target, appModel.appLabel))
                            requireContext().showToast("Search is unavailable in ${target.app.appLabel}")
                    } ?: run {
                        if (canShowWebSuggestions() && !inAppSearch.launchBrowser(appModel.appLabel))
                            requireContext().showToast("No browser available")
                    }
                    return@AppDrawerAdapter
                }
                if (launchAppSearch()) return@AppDrawerAdapter
                if (adapter.searchCommandsOnly) {
                    inAppSearch.targets[appModel.appPackage]?.let { sendSearchTo(it) }
                    return@AppDrawerAdapter
                }
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
            searchTarget = { app -> if (app is AppModel.App && app.user == Process.myUserHandle()) inAppSearch.targets[app.appPackage] else null },
            searchClick = { activateAppSearch(it, binding.search.query.toString()) },
            searchHold = { showAppSearchEditor(it) },
            appSearchHold = { app -> showAppSearchEditor(inAppSearch.targets[app.appPackage], app) },
            searchAlias = { app -> if (app is AppModel.App && app.user == Process.myUserHandle()) inAppSearch.targets[app.appPackage]?.let { inAppSearch.alias(it) } else null },
            webSuggestionsEnabled = { canShowWebSuggestions() },
            emptyAppResults = { query -> updateSearchSuggestions(query) },
            resultsChanged = { query ->
                if (_binding != null && query == binding.search.query.toString()) {
                    if (contactsMode) return@AppDrawerAdapter
                    updateSectionHeaders()
                    linearLayoutManager.reverseLayout = activeSearch != null || (!adapter.searchCommandsOnly && query.isNotBlank())
                    linearLayoutManager.stackFromEnd = false
                    if (positionedAppQuery != query) binding.recyclerView.scrollToPosition(0)
                    positionedAppQuery = query
                    if (activeSearch == null) binding.recyclerView.animateSearchResults(query)
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
        configureResultSections(binding.search.query.toString())
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

        // Repeated private-space/app observers often publish the same snapshot.
        // Keep existing icons and search results when nothing actually changed.
        combinedAppsJob?.cancel()
        if (combined == adapter.appsList) return
        combinedAppsJob = viewLifecycleOwner.lifecycleScope.launch {
            inAppSearch.loadAsync(combined)
            if (_binding != null) adapter.setAppList(combined)
        }
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
        suggestionJob?.cancel()
        contactsLoad?.cancel()
        combinedAppsJob?.cancel()
        adapter.cancelFiltering()
        (searchTextView as? AliasSearchInput)?.apply { undoAlias = null; submitEmpty = null }
        binding.search.setOnQueryTextListener(null)
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(binding.root, null)
        contactDialog?.dismiss()
        contactDialog = null
        searchEditor?.dismiss()
        searchEditor = null
        androidx.core.view.ViewCompat.setWindowInsetsAnimationCallback(binding.root, null)
        binding.root.translationY = 0f
        dockAnimator?.cancel()
        dockAnimator = null
        commandKeys.forEach { it.animate().cancel() }
        commandKeys = emptyList()
        unifiedAdapter?.let { old -> old.adapters.toList().forEach { old.removeAdapter(it) } }
        unifiedAdapter = null
        unifiedReversed = null
        contactSelectionBar = false
        binding.root.setOnTouchListener(null)
        binding.root.canSwipeSearch = null
        binding.root.onSwipeUp = null
        binding.recyclerView.clearOnScrollListeners()
        binding.recyclerView.adapter = null
        super.onDestroyView()
        searchTextView = null
        _binding = null
    }
}
