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
import app.olauncher.databinding.AdapterAppDrawerBinding
import kotlinx.coroutines.*
import java.util.Locale

/** Contact rows share the app result layout and dock-key silhouette. */
class ContactSearchAdapter(private val open: (Contact) -> Unit) : RecyclerView.Adapter<ContactSearchAdapter.Holder>() {
    data class Contact(val name: String, val uri: Uri, val photo: Uri?, val numbers: List<String>)
    var contacts: List<Contact> = emptyList()
    private var results: List<Contact> = emptyList()

    fun filter(query: String) {
        val terms = query.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        results = contacts.filter { contact -> terms.all { (contact.name.contains(it, ignoreCase = true) || contact.numbers.any { number -> number.contains(it) }) } }
        notifyDataSetChanged()
    }

    fun openFirst() { results.firstOrNull()?.let(open) }
    override fun getItemCount() = results.size
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        Holder(AdapterAppDrawerBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(results[position])
    override fun onViewRecycled(holder: Holder) { holder.photoJob?.cancel() }
    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        for (index in 0 until recyclerView.childCount) {
            (recyclerView.getChildViewHolder(recyclerView.getChildAt(index)) as? Holder)?.photoJob?.cancel()
        }
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
            binding.root.setOnClickListener { open(contact) }
            contact.photo?.let { photo ->
                photoJob = CoroutineScope(Dispatchers.Main.immediate).launch {
                    val bitmap = withContext(Dispatchers.IO) {
                        try {
                            binding.root.context.contentResolver.openInputStream(photo)?.use {
                                android.graphics.BitmapFactory.decodeStream(it, null,
                                    android.graphics.BitmapFactory.Options().apply { inSampleSize = 2 })
                            }
                        } catch (_: Exception) { null }
                    }
                    if (bitmap != null) {
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
