package app.olauncher.ui

import android.content.Context
import android.graphics.Typeface
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import app.olauncher.R
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.provider.ContactsContract
import android.telephony.PhoneNumberUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.DiffUtil
import app.olauncher.databinding.AdapterAppDrawerBinding
import kotlinx.coroutines.*
import java.util.Locale

internal fun formatContactNumber(number: String): String {
    val formatted = PhoneNumberUtils.formatNumber(number, Locale.getDefault().country) ?: number
    return formatted.replace(Regex("(?:\\s+|\\)\\s*)"), "-").replace("(", "")
}

/** Contact rows share the app result layout and dock-key silhouette. */
class ContactSearchAdapter(
    private val tap: (Contact) -> Unit,
    private val hold: (Contact) -> Unit,
) : RecyclerView.Adapter<ContactSearchAdapter.Holder>() {
    data class Contact(val name: String, val uri: Uri, val photo: Uri?, val numbers: List<String>)
    var contacts: List<Contact> = emptyList()
    private var results: List<Contact> = emptyList()
    private var selectedUri: Uri? = null
    var committedQuery: String? = null
        private set
    val selectedContact: Contact?
        get() = results.firstOrNull { it.uri == selectedUri }

    val firstResult: Contact? get() = results.firstOrNull()

    var enterHighlightedResult: Contact? = null
        set(value) {
            if (field == value) return
            val previous = field
            field = value
            previous?.let { results.indexOf(it) }?.takeIf { it >= 0 }?.let { notifyItemChanged(it, HIGHLIGHT) }
            value?.let { results.indexOf(it) }?.takeIf { it >= 0 }?.let { notifyItemChanged(it, HIGHLIGHT) }
        }

    fun toggleSelection(contact: Contact) {
        updateSelection(contact.uri.takeUnless { it == selectedUri })
    }

    fun clearSelection(): Boolean {
        if (selectedUri == null) return false
        updateSelection(null)
        return true
    }

    private fun updateSelection(uri: Uri?) {
        val previous = selectedUri
        selectedUri = uri
        for (changed in listOfNotNull(previous, uri)) {
            val index = results.indexOfFirst { it.uri == changed }
            if (index >= 0) notifyItemChanged(index, HIGHLIGHT)
        }
    }

    fun clearResults() {
        filterGeneration++
        filterJob?.cancel()
        val previousSize = results.size
        results = emptyList()
        committedQuery = null
        enterHighlightedResult = null
        selectedUri = null
        if (previousSize > 0) notifyItemRangeRemoved(0, previousSize)
    }

    fun clearContacts() {
        clearResults()
        contacts = emptyList()
        photoScope.coroutineContext.cancelChildren()
        photoCache.evictAll()
    }
    private val photoScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    // Bound by bitmap bytes so large address books cannot retain unlimited photos.
    private val photoCache = object : android.util.LruCache<Uri, android.graphics.Bitmap>(4 * 1024 * 1024) {
        override fun sizeOf(key: Uri, value: android.graphics.Bitmap) = value.byteCount
    }

    private var filterJob: Job? = null
    private var filterGeneration = 0L
    private val termSeparator = Regex("\\s+")

    fun filter(query: String, showAll: Boolean = true, applied: () -> Unit = {}) {
        filterJob?.cancel()
        committedQuery = null
        val generation = ++filterGeneration
        val source = contacts
        val trimmed = query.trim()
        val terms = trimmed.split(termSeparator).filter { it.isNotEmpty() }
        filterJob = photoScope.launch {
            val previous = results
            val (filtered, changes) = withContext(Dispatchers.Default) {
                val next = if (terms.isEmpty()) { if (showAll) source else emptyList() } else source.filter { contact ->
                    ensureActive()
                    terms.all { contact.name.contains(it, ignoreCase = true) ||
                        contact.numbers.any { number -> number.contains(it) } }
                }.map { contact ->
                    ensureActive()
                    Triple(contact, app.olauncher.helper.appSearchRank(contact.name, trimmed),
                        contact.name.lowercase(Locale.ROOT))
                }.sortedWith(compareBy<Triple<Contact, Int, String>> { it.second }.thenBy { it.third })
                    .map { it.first }
                val diff = if (previous == next) null else DiffUtil.calculateDiff(object : DiffUtil.Callback() {
                    override fun getOldListSize() = previous.size
                    override fun getNewListSize() = next.size
                    override fun areItemsTheSame(old: Int, new: Int): Boolean {
                        ensureActive()
                        return previous[old].uri == next[new].uri
                    }
                    override fun areContentsTheSame(old: Int, new: Int): Boolean {
                        ensureActive()
                        return previous[old] == next[new]
                    }
                }, true)
                next to diff
            }
            if (generation != filterGeneration || source !== contacts) return@launch
            if (results != filtered) {
                results = filtered
                changes?.dispatchUpdatesTo(this@ContactSearchAdapter)
            }
            committedQuery = query
            applied()
        }
    }

    override fun getItemCount() = results.size
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        Holder(AdapterAppDrawerBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(results[position])
    override fun onBindViewHolder(holder: Holder, position: Int, payloads: MutableList<Any>) {
        if (payloads.isNotEmpty() && payloads.all { it == HIGHLIGHT }) {
            holder.bindHighlight(results[position])
        } else super.onBindViewHolder(holder, position, payloads)
    }

    override fun onViewRecycled(holder: Holder) {
        holder.photoJob?.cancel()
        super.onViewRecycled(holder)
    }
    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        filterGeneration++
        committedQuery = null
        photoScope.coroutineContext.cancelChildren()
        super.onDetachedFromRecyclerView(recyclerView)
    }

    inner class Holder(private val binding: AdapterAppDrawerBinding) : RecyclerView.ViewHolder(binding.root) {
        var photoJob: Job? = null
        private val initial = TextView(binding.root.context).apply {
            gravity = android.view.Gravity.CENTER
            setTextColor(Color.BLACK)
            textSize = 20f
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        init {
            val density = binding.root.resources.displayMetrics.density
            binding.root.layoutParams.height = ViewGroup.LayoutParams.WRAP_CONTENT
            binding.root.minimumHeight = (50 * density).toInt()
            // Measure the name and number together so two name lines never overlap the number.
            binding.root.removeView(binding.appTitle)
            binding.root.removeView(binding.contactNumber)
            val labels = LinearLayout(binding.root.context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, (4 * density).toInt(), 0, (4 * density).toInt())
            }
            binding.appTitle.apply {
                maxLines = 2
                setPaddingRelative(0, 0, 0, 0)
            }
            binding.contactNumber.setPaddingRelative(0, 0, 0, 0)
            labels.addView(binding.appTitle, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            labels.addView(binding.contactNumber, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            binding.root.addView(labels, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                android.view.Gravity.CENTER_VERTICAL).apply {
                marginStart = (68 * density).toInt()
                marginEnd = (36 * density).toInt()
            })
            binding.root.addView(initial, FrameLayout.LayoutParams(binding.appIcon.layoutParams as FrameLayout.LayoutParams))
        }
        fun bindHighlight(contact: Contact) {
            val selected = contact.uri == selectedUri
            val highlighted = !selected && contact == enterHighlightedResult
            binding.contactSelectedIndicator.isVisible = selected
            binding.root.isActivated = selected
            binding.root.isSelected = highlighted
            val state = when {
                selected -> R.string.bolt_contact_selected
                highlighted -> R.string.bolt_enter_target
                else -> null
            }
            androidx.core.view.ViewCompat.setStateDescription(binding.root,
                state?.let(binding.root.resources::getString))
            binding.appTitle.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            binding.appTitle.setTextColor(if (highlighted || selected) Color.WHITE else
                ContextCompat.getColor(binding.root.context, R.color.bolt_search_label))
        }

        fun bind(contact: Contact) {
            binding.root.setTag(R.id.bolt_result_identity, contact.uri)
            (binding.root.parent as? DockResultsView)?.rememberRow(binding.root, contact.uri)
            photoJob?.cancel()
            bindHighlight(contact)
            val number = contact.numbers.firstOrNull().orEmpty()
            binding.contactNumber.text = formatContactNumber(number)
            binding.contactNumber.visibility = if (number.isEmpty()) View.GONE else View.VISIBLE
            binding.appTitle.text = contact.name
            binding.otherProfileIndicator.visibility = View.GONE
            binding.appHideLayout.visibility = View.GONE
            binding.renameLayout.visibility = View.GONE
            val radius = (36f * 18f / 52f) * binding.root.resources.displayMetrics.density
            binding.appIcon.apply {
                background = GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = radius }
                clipToOutline = true
                scaleType = ImageView.ScaleType.CENTER_CROP
                setImageDrawable(null)
            }
            initial.text = contact.name.trim().let { name ->
                if (name.isEmpty()) "?" else String(Character.toChars(name.codePointAt(0))).uppercase(Locale.getDefault())
            }
            initial.visibility = View.VISIBLE
            binding.root.setOnClickListener { tap(contact) }
            binding.root.setOnLongClickListener {
                hold(contact)
                true
            }
            contact.photo?.let { photo ->
                photoCache.get(photo)?.let { bitmap ->
                    binding.appIcon.setImageBitmap(bitmap)
                    initial.visibility = View.GONE
                    return
                }
                val resolver = binding.root.context.applicationContext.contentResolver
                photoJob = photoScope.launch {
                    val bitmap = withContext(Dispatchers.IO) {
                        try {
                            resolver.openInputStream(photo)?.use {
                                android.graphics.BitmapFactory.decodeStream(it, null,
                                    android.graphics.BitmapFactory.Options().apply { inSampleSize = 2 })
                            }
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            null
                        }
                    }
                    if (bitmap != null) {
                        photoCache.put(photo, bitmap)
                        binding.appIcon.setImageBitmap(bitmap)
                        initial.visibility = View.GONE
                    }
                }
            }
        }
    }

    companion object {
        private const val HIGHLIGHT = "contact_selection"
        suspend fun load(context: Context): app.olauncher.helper.ContactLoadResult<Contact> =
            withContext(Dispatchers.IO) { app.olauncher.helper.readContactsSafely {
            val cancellation = currentCoroutineContext()
            val columns = arrayOf(ContactsContract.Contacts._ID, ContactsContract.Contacts.LOOKUP_KEY,
                ContactsContract.Contacts.DISPLAY_NAME_PRIMARY, ContactsContract.Contacts.PHOTO_THUMBNAIL_URI)
                val numbers = mutableMapOf<Long, MutableList<String>>()
                context.contentResolver.query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                    arrayOf(ContactsContract.CommonDataKinds.Phone.CONTACT_ID, ContactsContract.CommonDataKinds.Phone.NUMBER,
                        ContactsContract.CommonDataKinds.Phone.NORMALIZED_NUMBER),
                    null, null, null).let { requireNotNull(it) { "Null phone cursor" } }.use { cursor ->
                    while (cursor.moveToNext()) {
                        cancellation.ensureActive()
                        (cursor.getString(2)?.takeIf { it.isNotBlank() } ?: cursor.getString(1))?.takeIf { it.isNotBlank() }?.let {
                            numbers.getOrPut(cursor.getLong(0)) { mutableListOf() }.add(it)
                        }
                    }
                }
                context.contentResolver.query(ContactsContract.Contacts.CONTENT_URI, columns, null, null,
                    "${ContactsContract.Contacts.DISPLAY_NAME_PRIMARY} COLLATE LOCALIZED ASC").let { requireNotNull(it) { "Null contacts cursor" } }.use { cursor ->
                    buildList {
                        while (cursor.moveToNext()) {
                        cancellation.ensureActive()
                            val name = cursor.getString(2)?.trim().orEmpty()
                            if (name.isNotEmpty()) add(Contact(name,
                                ContactsContract.Contacts.getLookupUri(cursor.getLong(0), cursor.getString(1)),
                                cursor.getString(3)?.let(Uri::parse), numbers[cursor.getLong(0)]?.distinct().orEmpty()))
                        }
                    }
                }
            } }
    }
}
