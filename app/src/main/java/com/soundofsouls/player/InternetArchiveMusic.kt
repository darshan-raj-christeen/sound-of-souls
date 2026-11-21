package com.soundofsouls.player

import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.Callable
import java.util.concurrent.Executors

data class OnlineSong(
    val identifier: String,
    val title: String,
    val artist: String,
    val streamUrl: String,
    val licenseUrl: String
)

object InternetArchiveMusic {
    private val allowedLicense = Regex(
        "creativecommons\\.org/licenses/(by|by-sa)/(2\\.0|3\\.0|4\\.0)/|creativecommons\\.org/publicdomain/zero/|creativecommons\\.org/publicdomain/mark/"
    )

    fun search(query: String, preferHighQuality: Boolean = false): List<OnlineSong> {
        val cleaned = query.trim().replace(Regex("\\s+"), " ")
        if (cleaned.isBlank()) return emptyList()
        val terms = cleaned.split(" ").filter { it.isNotBlank() }.take(5)
        fun lucene(value: String) = value.replace(Regex("[^\\p{L}\\p{N}_-]"), "")
        val clauses = terms.mapNotNull { raw ->
            val term = lucene(raw)
            if (term.isBlank()) null else "(title:$term OR creator:$term OR subject:$term)"
        }
        if (clauses.isEmpty()) return emptyList()

        // Search exact title phrase first, then broader individual words so a common
        // word such as "song" cannot bury a distinctive title such as "Radhima".
        val queries = listOf(
            "mediatype:audio AND title:\"$cleaned\"",
            "mediatype:audio AND (" + clauses.joinToString(" OR ") + ")"
        ).distinct()
        val docsById = linkedMapOf<String, JSONObject>()
        for (archiveQuery in queries) {
            val url = "https://archive.org/advancedsearch.php?q=${enc(archiveQuery)}&fl[]=identifier&fl[]=title&fl[]=creator&rows=30&page=1&output=json"
            val docs = getJson(url).optJSONObject("response")?.optJSONArray("docs") ?: JSONArray()
            for (i in 0 until docs.length()) {
                val doc = docs.optJSONObject(i) ?: continue
                val id = doc.optString("identifier")
                if (id.isNotBlank()) docsById.putIfAbsent(id, doc)
            }
            if (docsById.size >= 30) break
        }

        val pool = Executors.newFixedThreadPool(5)
        return try {
            pool.invokeAll(docsById.values.take(24).map { doc -> Callable { readPlayableItem(doc, preferHighQuality) } })
                .mapNotNull { future -> try { future.get() } catch (_: Exception) { null } }
                .sortedBy { song ->
                    val title = song.title.lowercase()
                    val needle = cleaned.lowercase()
                    when {
                        title == needle -> 0
                        title.contains(needle) -> 1
                        terms.any { title.contains(it.lowercase()) } -> 2
                        else -> 3
                    }
                }
        } finally {
            pool.shutdownNow()
        }
    }

    private fun readPlayableItem(doc: JSONObject, preferHighQuality: Boolean): OnlineSong? {
        val identifier = doc.optString("identifier")
        if (identifier.isBlank()) return null
        val item = getJson("https://archive.org/metadata/${enc(identifier)}")
        val metadata = item.optJSONObject("metadata") ?: return null
        val license = metadata.optString("licenseurl").ifBlank { doc.optString("licenseurl") }
        // Only stream tracks whose item metadata explicitly identifies a reusable licence.
        if (!allowedLicense.containsMatchIn(license)) return null

        val files = item.optJSONArray("files") ?: return null
        var audioName: String? = null
        var audioScore = -1
        for (j in 0 until files.length()) {
            val file = files.optJSONObject(j) ?: continue
            val name = file.optString("name")
            val lower = name.lowercase()
            val score = if (preferHighQuality) when {
                lower.endsWith(".flac") -> 6
                lower.endsWith(".wav") -> 5
                lower.endsWith(".m4a") || lower.endsWith(".aac") -> 4
                lower.endsWith(".ogg") -> 3
                lower.endsWith(".mp3") -> 2
                else -> -1
            } else when {
                lower.endsWith(".mp3") -> 5
                lower.endsWith(".m4a") || lower.endsWith(".aac") -> 4
                lower.endsWith(".ogg") -> 3
                lower.endsWith(".flac") -> 2
                lower.endsWith(".wav") -> 1
                else -> -1
            }
            if (score > audioScore && file.optString("source") != "metadata") {
                audioScore = score
                audioName = name
            }
        }
        val fileName = audioName ?: return null
        val title = metadata.optString("title").ifBlank { doc.optString("title") }
            .ifBlank { fileName.substringAfterLast('/') }
        val creator = metadata.opt("creator") ?: doc.opt("creator")
        val artist = when (creator) {
            is JSONArray -> creator.optString(0, "Unknown artist")
            is String -> creator
            else -> "Unknown artist"
        }
        val streamUrl = "https://archive.org/download/${enc(identifier)}/${Uri.encode(fileName)}"
        return OnlineSong(identifier, title, artist, streamUrl, license)
    }

    private fun getJson(address: String): JSONObject {
        val connection = URL(address).openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.connectTimeout = 8000
        connection.readTimeout = 12000
        connection.setRequestProperty("User-Agent", "SoundOfSouls/0.4 (Android music discovery)")
        return try {
            if (connection.responseCode !in 200..299) {
                throw IllegalStateException("Catalogue returned HTTP ${connection.responseCode}")
            }
            connection.inputStream.bufferedReader().use { JSONObject(it.readText()) }
        } finally {
            connection.disconnect()
        }
    }

    private fun enc(value: String): String = URLEncoder.encode(value, Charsets.UTF_8.name())
}
