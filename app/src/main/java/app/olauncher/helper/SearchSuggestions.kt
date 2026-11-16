package app.olauncher.helper

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONArray
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/** Autocomplete never holds up local filtering; cancelling its coroutine cancels the socket. */
object SearchSuggestions {
    private val cache = android.util.LruCache<String, List<String>>(64)
    private val client = OkHttpClient.Builder()
        .connectTimeout(2, TimeUnit.SECONDS)
        .readTimeout(2, TimeUnit.SECONDS)
        .callTimeout(3, TimeUnit.SECONDS)
        .build()

    fun cached(query: String): List<String>? = cache.get(query.trim())

    suspend fun fetch(query: String): List<String> {
        val key = query.trim()
        if (key.isEmpty()) return emptyList()
        cached(key)?.let { return it }
        return suspendCancellableCoroutine { continuation ->
            val url = "https://suggestqueries.google.com/complete/search".toHttpUrl().newBuilder()
                .addQueryParameter("client", "firefox").addQueryParameter("q", key).build()
            val call = client.newCall(Request.Builder().url(url).build())
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resume(emptyList())
                }
                override fun onResponse(call: Call, response: Response) {
                    val values = response.use {
                        try {
                            if (!it.isSuccessful) emptyList() else {
                                val array = JSONArray(it.body?.string().orEmpty()).getJSONArray(1)
                                (0 until array.length()).map { index -> array.getString(index) }
                                    .filter { value -> value.isNotBlank() && !value.equals(key, true) }
                                    .distinct().take(6)
                            }
                        } catch (_: Exception) { emptyList() }
                    }
                    if (continuation.isActive) {
                        cache.put(key, values)
                        continuation.resume(values)
                    }
                }
            })
        }
    }
}
