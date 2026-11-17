package app.olauncher.ui

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.provider.ContactsContract
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.DiffUtil
import app.olauncher.databinding.AdapterAppDrawerBinding
import kotlinx.coroutines.*
import java.util.Locale

/** Contact rows share the app result layout and dock-key silhouette. */
class ContactSearchAdapter(
    private val open: (Contact) -> Unit,
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
        get() = contacts.firstOrNull { it.uri == selectedUri }

    fun toggleSelection(contact: Contact) {
        val previous = selectedUri
        selectedUri = if (previous == contact.uri) null else contact.uri
        results.forEachIndexed { index, result ->
            if (result.uri == previous || result.uri == selectedUri) notifyItemChanged(index)
        }
    }

    fun clearSelection() {
        val previous = selectedUri ?: return
        selectedUri = null
        results.indexOfFirst { it.uri == previous }.takeIf { it >= 0 }?.let { notifyItemChanged(it) }
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
        val terms = query.trim().split(termSeparator).filter { it.isNotEmpty() }
        filterJob = photoScope.launch {
            val previous = results
            val (filtered, changes) = withContext(Dispatchers.Default) {
                val next = if (terms.isEmpty()) { if (showAll) source else emptyList() } else source.filter { contact ->
                    ensureActive()
                    terms.all { contact.name.contains(it, ignoreCase = true) ||
                        contact.numbers.any { number -> number.contains(it) } }
                }.sortedWith(compareBy<Contact> {
                    app.olauncher.helper.appSearchRank(it.name, query.trim())
                }.thenBy { it.name.lowercase(Locale.ROOT) })
                val diff = if (previous == next) null else DiffUtil.calculateDiff(object : DiffUtil.Callback() {
                    override fun getOldListSize() = previous.size
                    override fun getNewListSize() = next.size
                    override fun areItemsTheSame(old: Int, new: Int) = previous[old].uri == next[new].uri
                    override fun areContentsTheSame(old: Int, new: Int) = previous[old] == next[new]
                }, false)
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

    fun openFirst() { results.firstOrNull()?.let(open) }
    override fun getItemCount() = results.size
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        Holder(AdapterAppDrawerBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(results[position])
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
            binding.root.addView(initial, FrameLayout.LayoutParams(binding.appIcon.layoutParams as FrameLayout.LayoutParams))
        }
        fun bind(contact: Contact) {
            photoJob?.cancel()
            binding.root.isSelected = contact.uri == selectedUri
            binding.root.foreground = if (binding.root.isSelected) GradientDrawable().apply {
                setColor(Color.TRANSPARENT)
                cornerRadius = 30f * binding.root.resources.displayMetrics.density
                setStroke((2f * binding.root.resources.displayMetrics.density).toInt().coerceAtLeast(1), Color.rgb(255, 214, 0))
            } else null
            val number = contact.numbers.firstOrNull().orEmpty()
            binding.contactNumber.text = number
            binding.contactNumber.visibility = if (number.isEmpty()) View.GONE else View.VISIBLE
            binding.appTitle.setPaddingRelative(binding.appTitle.paddingStart, 0,
                binding.appTitle.paddingEnd,
                if (number.isEmpty()) 0 else (20 * binding.root.resources.displayMetrics.density).toInt())
            binding.appTitle.text = contact.name
            binding.otherProfileIndicator.visibility = View.GONE
            binding.resultBolt.visibility = View.GONE
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
                        } catch (_: Exception) { null }
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
        fun load(context: Context): List<Contact> {
            val columns = arrayOf(ContactsContract.Contacts._ID, ContactsContract.Contacts.LOOKUP_KEY,
                ContactsContract.Contacts.DISPLAY_NAME_PRIMARY, ContactsContract.Contacts.PHOTO_THUMBNAIL_URI)
            return try {
                val numbers = mutableMapOf<Long, MutableList<String>>()
                context.contentResolver.query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                    arrayOf(ContactsContract.CommonDataKinds.Phone.CONTACT_ID, ContactsContract.CommonDataKinds.Phone.NUMBER,
                        ContactsContract.CommonDataKinds.Phone.NORMALIZED_NUMBER),
                    null, null, null)?.use { cursor ->
                    while (cursor.moveToNext()) {
                        (cursor.getString(2)?.takeIf { it.isNotBlank() } ?: cursor.getString(1))?.takeIf { it.isNotBlank() }?.let {
                            numbers.getOrPut(cursor.getLong(0)) { mutableListOf() }.add(it)
                        }
                    }
                }
                context.contentResolver.query(ContactsContract.Contacts.CONTENT_URI, columns, null, null,
                    "${ContactsContract.Contacts.DISPLAY_NAME_PRIMARY} COLLATE LOCALIZED ASC")?.use { cursor ->
                    buildList {
                        while (cursor.moveToNext()) {
                            val name = cursor.getString(2)?.trim().orEmpty()
                            if (name.isNotEmpty()) add(Contact(name,
                                ContactsContract.Contacts.getLookupUri(cursor.getLong(0), cursor.getString(1)),
                                cursor.getString(3)?.let(Uri::parse), numbers[cursor.getLong(0)]?.distinct().orEmpty()))
                        }
                    }
                } ?: emptyList()
            } catch (_: SecurityException) { emptyList() }
        }
    }
}
