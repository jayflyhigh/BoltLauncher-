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
    private val searchProbeJobs = mutableMapOf<String, kotlinx.coroutines.Job>()
    private var searchTargetsLoad: kotlinx.coroutines.Job? = null
    private lateinit var prefs: Prefs
    private lateinit var adapter: AppDrawerAdapter
    private lateinit var linearLayoutManager: LinearLayoutManager
    private var searchTextView: TextView? = null
    private var cachedIsCjkKeyboard: Boolean? = null

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
    private var contactDialogVisible = false
    private var contactsMode = false
    private lateinit var contactsAdapter: ContactSearchAdapter
    private var contactsLoad: kotlinx.coroutines.Job? = null
    private val contactsPermission = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted && contactsMode && _binding != null) loadContacts()
        else if (!granted && _binding != null) requireContext().showToast("Allow contacts access to search contacts")
    }

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

                override fun onSingleTapUp(event: android.view.MotionEvent): Boolean {
                    if (!isHomePage || !contactsMode || dockExpanded) return false
                    resetHomeSearch()
                    return true
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
                setContactsMode(false)
                setThunderMode(false)
                setDockExpanded(true)
            }
        }
        binding.dockBolt.setOnClickListener {
            setContactsMode(false)
            setThunderMode(true)
            setDockExpanded(true)
        }
        binding.dockContacts.setOnClickListener { showContactCommands() }
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

    private fun ensureCommandKeys() {
        if (commandKeys.isNotEmpty()) return
        val originals = listOf(binding.dockHome, binding.dockSearch, binding.dockBolt,
            binding.dockContacts, binding.dockSettings)
        val icons = listOf(R.drawable.dock_search) + ContactCommand.entries.filter { it != ContactCommand.SEARCH }.map { it.icon }
        commandKeys = originals.mapIndexed { index, original ->
            android.widget.ImageButton(requireContext()).apply {
                setBackgroundResource(R.drawable.dock_key)
                setImageResource(icons[index])
                imageTintList = android.content.res.ColorStateList.valueOf(
                    if (icons[index] == R.drawable.contact_whatsapp) android.graphics.Color.rgb(37, 211, 102)
                    else android.graphics.Color.WHITE
                )
                setPadding(original.paddingLeft, original.paddingTop, original.paddingRight, original.paddingBottom)
                contentDescription = if (index == 0) "Search contacts" else ContactCommand.entries[index - 1].hint.trimEnd('.')
                visibility = View.GONE
                binding.dock.addView(this, android.widget.FrameLayout.LayoutParams(original.width, original.height).apply {
                    leftMargin = (binding.dockKeys.x + original.left - binding.dock.paddingLeft).toInt()
                    topMargin = (binding.dockKeys.y + original.top - binding.dock.paddingTop).toInt()
                })
                setOnClickListener {
                    if (contactCommand != null) animateContactSearch(false)
                    else if (index == 0) selectContactCommand(ContactCommand.SEARCH)
                    else selectContactCommand(ContactCommand.entries[index - 1])
                }
            }
        }
    }

    private fun showContactCommands() {
        ensureCommandKeys()
        cancelAutoLaunch()
        setContactsMode(true)
        setThunderMode(false)
        contactCommand = null
        dockExpanded = false
        binding.search.visibility = View.INVISIBLE
        binding.recyclerView.visibility = View.INVISIBLE
        binding.search.setQuery("", false)
        binding.dockKeys.visibility = View.INVISIBLE
        commandProgress = 0f
        commandKeys.forEachIndexed { index, key ->
            key.visibility = View.VISIBLE
            key.isEnabled = false
            key.alpha = if (index == 0) 1f else 0f
            key.translationX = if (index == 0)
                binding.dockContacts.x - binding.dockHome.x else 28f * resources.displayMetrics.density
            key.animate().translationX(0f).alpha(1f)
                .setDuration(if (requireContext().isEinkDisplay() || requireContext().isSystemAnimationsDisabled()) 0L else 220L)
                .setInterpolator(android.view.animation.DecelerateInterpolator())
                .withEndAction { key.isEnabled = true }.start()
        }
    }

    private fun selectContactCommand(command: ContactCommand) {
        if (command == ContactCommand.ADD) {
            addContact()
            return
        }
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
        dockAnimator = android.animation.ValueAnimator.ofFloat(commandProgress, if (expanded) 1f else 0f).apply {
            duration = if (requireContext().isEinkDisplay() || requireContext().isSystemAnimationsDisabled()) 0L
                else (220 * kotlin.math.abs((if (expanded) 1f else 0f) - commandProgress)).toLong()
            interpolator = android.view.animation.PathInterpolator(0.2f, 0f, 0f, 1f)
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
                b.search.translationX = -b.search.width * (1f - progress)
                b.search.clipBounds = android.graphics.Rect((b.search.width * (1f - progress)).toInt(), 0,
                    b.search.width, b.search.height)
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                private var cancelled = false
                override fun onAnimationCancel(animation: android.animation.Animator) {
                    cancelled = true
                }
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    if (cancelled || _binding == null) return
                    commandKeys.forEachIndexed { index, key -> key.isEnabled = !expanded || index == selectedIndex }
                    if (expanded) binding.search.showKeyboard()
                    else {
                        contactCommand = null
                        contactLaunchQuery = null
                        binding.search.setQuery("", false)
                        binding.search.visibility = View.INVISIBLE
                        binding.recyclerView.visibility = View.INVISIBLE
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
                    b.search.query.toString() != query || contactsAdapter.itemCount != 1 ||
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

    private fun runContactCommand(contact: ContactSearchAdapter.Contact) {
        cancelAutoLaunch()
        contactLaunchQuery = binding.search.query.toString()
        val command = contactCommand ?: return
        if (command == ContactCommand.SEARCH) {
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
            contactDialogVisible = true
            androidx.appcompat.app.AlertDialog.Builder(requireContext()).setTitle(contact.name)
                .setItems(contact.numbers.toTypedArray()) { _, index -> launchNumber(contact.numbers[index]) }
                .setNegativeButton(android.R.string.cancel, null)
                .setOnDismissListener { contactDialogVisible = false }.show()
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
        contactsMode = enabled
        cancelAutoLaunch()
        binding.recyclerView.adapter = if (enabled) contactsAdapter else adapter
        binding.search.queryHint = if (enabled) "Search contacts" else "Search apps"
        if (enabled) filterContacts(binding.search.query.toString())
        else adapter.filterApps(binding.search.query.toString())
    }

    private fun filterContacts(query: String) {
        if (query != contactLaunchQuery) contactLaunchQuery = null
        contactsAdapter.filter(query)
        linearLayoutManager.reverseLayout = query.isNotBlank()
        linearLayoutManager.stackFromEnd = false
        binding.recyclerView.scrollToPosition(0)
        binding.recyclerView.animateSearchResults(query, force = true)
        scheduleContactLaunch(query)
    }

    private fun loadContacts() {
        contactsLoad?.cancel()
        val context = requireContext().applicationContext
        contactsLoad = viewLifecycleOwner.lifecycleScope.launch {
            val contacts = withContext(Dispatchers.IO) { ContactSearchAdapter.load(context) }
            if (_binding != null && contactsMode) {
                contactsAdapter.contacts = contacts
                filterContacts(binding.search.query.toString())
            }
        }
    }

    private fun setThunderMode(enabled: Boolean) {
        cancelAutoLaunch()
        thunderMode = enabled
        lastAutoLaunchedQuery = null
        adapter.setThunderMode(enabled)
        val foreground = if (enabled) android.graphics.Color.BLACK else android.graphics.Color.WHITE
        binding.search.setBackgroundResource(if (enabled) R.drawable.dock_yellow else R.drawable.dock_key)
        val iconPadding = (13 * resources.displayMetrics.density).toInt()
        binding.dockSearch.setPadding(iconPadding, iconPadding, iconPadding, iconPadding)
        binding.dockSearch.setBackgroundResource(if (enabled) R.drawable.dock_yellow else R.drawable.dock_key)
        binding.dockSearch.setImageResource(if (enabled) R.drawable.dock_bolt else if (contactsMode) R.drawable.dock_contacts else R.drawable.dock_search)
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
            duration = if (animate && !requireContext().isEinkDisplay() && !requireContext().isSystemAnimationsDisabled())
                (220 * kotlin.math.abs(target - start)).toLong() else 0L
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
                private var cancelled = false
                override fun onAnimationCancel(animation: android.animation.Animator) {
                    cancelled = true
                }
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    if (cancelled || _binding == null || dockExpanded != expanded) return
                    if (expanded) binding.search.showKeyboard()
                    else binding.search.visibility = View.INVISIBLE
                }
            })
            start()
        }
    }

    private fun clearAppSearch() {
        activeSearch = null
        adapter.searchPackage = null
        binding.dockSearch.contentDescription = "Search"
        binding.dockSearch.setOnLongClickListener(null)
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
        android.graphics.Canvas(bitmap).drawText(target.app.appLabel.take(1).uppercase(java.util.Locale.ROOT), size / 2f,
            size / 2f - (paint.ascent() + paint.descent()) / 2f, paint)
        binding.dockSearch.setPadding(0, 0, 0, 0)
        binding.dockSearch.setImageBitmap(bitmap)
        binding.dockSearch.imageTintList = null
        binding.dockSearch.background = app.olauncher.helper.InAppSearch.background(target.color, resources.displayMetrics.density)
        binding.dockSearch.contentDescription = "Search in ${target.app.appLabel}, alias ${inAppSearch.alias(target)}"
        binding.dockSearch.setOnLongClickListener { editSearchAlias(target); true }
        binding.search.queryHint = "Search in ${target.app.appLabel}....."
        binding.search.setQuery(query, false)
        adapter.filterApps(query)
        setDockExpanded(true)
        binding.search.showKeyboard()
    }

    private fun checkAppSearch(app: AppModel) {
        val key = app.appPackage
        if (searchProbeJobs.containsKey(key)) return
        cancelAutoLaunch()
        val job = viewLifecycleOwner.lifecycleScope.launch(start = kotlinx.coroutines.CoroutineStart.LAZY) {
            try {
                // Let the initial scan finish so it cannot overwrite a newly added badge.
                searchTargetsLoad?.join()
                val target = withContext(Dispatchers.IO) { inAppSearch.probe(app) }
                if (_binding == null) return@launch
                if (target != null) {
                    inAppSearch.add(target)
                    requireContext().showToast("Search added for ${app.appLabel}. Code: ${inAppSearch.alias(target)}")
                } else {
                    requireContext().showToast("No supported search route found for ${app.appLabel}")
                }
            } finally {
                searchProbeJobs.remove(key)
                if (_binding != null) {
                    adapter.notifyItemRangeChanged(0, adapter.itemCount)
                }
            }
        }
        searchProbeJobs[key] = job
        adapter.notifyItemRangeChanged(0, adapter.itemCount)
        job.start()
    }

    private fun editSearchAlias(target: app.olauncher.helper.InAppSearch.Target) {
        val field = android.widget.EditText(requireContext()).apply {
            setSingleLine(true)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            setText(inAppSearch.alias(target))
            selectAll()
        }
        val dialog = androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle("${target.app.appLabel} alias code").setView(field)
            .setPositiveButton("Save", null).setNegativeButton(android.R.string.cancel, null).create()
        dialog.setOnShowListener {
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val value = app.olauncher.helper.AppSearchAlias.normalize(field.text.toString())
                if (inAppSearch.saveAlias(target, value)) dialog.dismiss()
                else field.error = "Use a unique code with letters or numbers"
            }
            field.requestFocus()
            dialog.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
        }
        dialog.setOnDismissListener { if (_binding != null && dockExpanded) binding.search.showKeyboard() }
        dialog.show()
    }

    private fun launchAppSearch(): Boolean {
        val target = activeSearch ?: return false
        val query = binding.search.query.toString().trim()
        if (query.isBlank()) return true
        try {
            if (!inAppSearch.launch(target, query)) {
                clearAppSearch()
                setThunderMode(false)
                binding.search.queryHint = "Search apps"
                adapter.filterApps(query)
                adapter.notifyItemRangeChanged(0, adapter.itemCount)
                requireContext().showToast("Search is unavailable in ${target.app.appLabel}. Its code has been removed.")
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

    private fun initSearch() {
        binding.search.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?): Boolean {
                cancelAutoLaunch()
                if (launchAppSearch()) return true
                if (contactsMode && contactCommand == ContactCommand.ADD) addContact()
                else if (contactsMode) contactsAdapter.openFirst()
                else if (adapter.committedQuery == binding.search.query.toString()) adapter.launchFirstInList()
                return true
            }

            override fun onQueryTextChange(newText: String): Boolean {
                try {
                    cancelAutoLaunch()
                    if (activeSearch == null && !contactsMode && flag == Constants.FLAG_LAUNCH_APP && newText.contains(' ')) {
                        val confirmed = app.olauncher.helper.AppSearchAlias.confirmed(newText)
                        val target = confirmed?.first?.let { inAppSearch.find(it) }
                        if (target != null) {
                            activateAppSearch(target, confirmed?.second.orEmpty())
                            return true
                        }
                    }
                    if (newText != lastAutoLaunchedQuery) lastAutoLaunchedQuery = null
                    if (contactsMode) {
                        if (contactCommand != ContactCommand.ADD) filterContacts(newText)
                    } else adapter.filterApps(newText)
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
        contactsAdapter = ContactSearchAdapter { contact -> runContactCommand(contact) }
        adapter = AppDrawerAdapter(
            flag,
            prefs.appLabelAlignment,
            appClickListener = { appModel ->
                cancelAutoLaunch()
                if (launchAppSearch()) return@AppDrawerAdapter
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
            searchTarget = { app -> if (app is AppModel.App && app.user == Process.myUserHandle()) inAppSearch.targets[app.appPackage] else null },
            searchAlias = { app -> if (app is AppModel.App && app.user == Process.myUserHandle()) inAppSearch.targets[app.appPackage]?.let { inAppSearch.alias(it) } else null },
            searchClick = { activateAppSearch(it) },
            searchHold = { editSearchAlias(it) },
            searchProbe = { checkAppSearch(it) },
            searchProbing = { searchProbeJobs.containsKey(it.appPackage) },
            resultsChanged = { query ->
                if (_binding != null && query == binding.search.query.toString()) {
                    if (contactsMode) return@AppDrawerAdapter
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
        searchTargetsLoad?.cancel()
        searchTargetsLoad = viewLifecycleOwner.lifecycleScope.launch {
            withContext(Dispatchers.IO) { inAppSearch.load(combined) }
            if (_binding != null) adapter.notifyItemRangeChanged(0, adapter.itemCount)
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
        contactsLoad?.cancel()
        searchTargetsLoad?.cancel()
        searchProbeJobs.values.toList().forEach { it.cancel() }
        searchProbeJobs.clear()
        dockAnimator?.cancel()
        dockAnimator = null
        commandKeys.forEach { it.animate().cancel() }
        commandKeys = emptyList()
        binding.recyclerView.adapter = null
        super.onDestroyView()
        searchTextView = null
        _binding = null
    }
}
