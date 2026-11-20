package com.soundofsouls.player

import android.net.Uri
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

    fun search(query: String): List<OnlineSong> {
        val terms = query.trim().split(Regex("\\s+")).filter { it.isNotBlank() }.take(5)
        if (terms.isEmpty()) return emptyList()
        fun lucene(value: String) = value.replace(Regex("[^\\p{L}\\p{N}_-]"), "")
        val clauses = terms.mapNotNull { raw ->
            val term = lucene(raw)
            if (term.isBlank()) null else "(title:$term OR creator:$term OR subject:$term)"
        }
        if (clauses.isEmpty()) return emptyList()

        // Ask the catalogue to filter by audio and licence before downloading item metadata.
        val archiveQuery = "mediatype:audio AND (" + clauses.joinToString(" OR ") + ")"
        val searchUrl = "https://archive.org/advancedsearch.php?q=${enc(archiveQuery)}&fl[]=identifier&fl[]=title&fl[]=creator&fl[]=licenseurl&rows=14&page=1&output=json"
        val docs = getJson(searchUrl).getJSONObject("response").getJSONArray("docs")
        val candidates = (0 until docs.length()).map { docs.getJSONObject(it) }
            .filter { doc ->
                val license = doc.optString("licenseurl")
                license.isBlank() || allowedLicense.containsMatchIn(license)
            }.take(10)

        // Fetch a small number of item records concurrently instead of making 40 serial requests.
        val pool = Executors.newFixedThreadPool(5)
        return try {
            val tasks = candidates.map { doc ->
                Callable { readPlayableItem(doc) }
            }
            pool.invokeAll(tasks).mapNotNull { future ->
                try { future.get() } catch (_: Exception) { null }
            }
        } finally {
            pool.shutdownNow()
        }
    }

    private fun readPlayableItem(doc: JSONObject): OnlineSong? {
        val identifier = doc.optString("identifier")
        if (identifier.isBlank()) return null
        val item = getJson("https://archive.org/metadata/${enc(identifier)}")
        val metadata = item.optJSONObject("metadata") ?: return null
        val license = metadata.optString("licenseurl").ifBlank { doc.optString("licenseurl") }
        if (!allowedLicense.containsMatchIn(license)) return null

        val files = item.optJSONArray("files") ?: return null
        var audioName: String? = null
        var audioScore = -1
        for (j in 0 until files.length()) {
            val file = files.optJSONObject(j) ?: continue
            val name = file.optString("name")
            val lower = name.lowercase()
            val extScore = when {
                lower.endsWith(".mp3") -> 5
                lower.endsWith(".m4a") || lower.endsWith(".aac") -> 4
                lower.endsWith(".ogg") -> 3
                lower.endsWith(".flac") -> 2
                lower.endsWith(".wav") -> 1
                else -> -1
            }
            if (extScore > audioScore && file.optString("source") != "metadata") {
                audioScore = extScore
                audioName = name
            }
        }
        val fileName = audioName ?: return null
        val title = metadata.optString("title").ifBlank { doc.optString("title") }
            .ifBlank { fileName.substringAfterLast('/') }
        val creatorValue = metadata.opt("creator") ?: doc.opt("creator")
        val artist = when (creatorValue) {
            is org.json.JSONArray -> creatorValue.optString(0, "Unknown artist")
            is String -> creatorValue
            else -> "Unknown artist"
        }
        val streamUrl = "https://archive.org/download/${enc(identifier)}/${Uri.encode(fileName)}"
        return OnlineSong(identifier, title, artist, streamUrl, license)
    }

    private fun getJson(address: String): JSONObject {
        val connection = URL(address).openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.connectTimeout = 7000
        connection.readTimeout = 9000
        connection.setRequestProperty("User-Agent", "SoundOfSouls/0.3 (Android music discovery)")
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
