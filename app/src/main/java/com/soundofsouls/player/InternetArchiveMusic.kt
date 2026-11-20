package com.soundofsouls.player

import android.net.Uri
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

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
        val terms = query.trim().split(Regex("\\s+")).filter { it.isNotBlank() }.take(6)
        if (terms.isEmpty()) return emptyList()
        fun lucene(value: String) = value.replace(Regex("[^\\p{L}\\p{N}_-]"), "")
        val clauses = terms.mapNotNull { raw ->
            val term = lucene(raw)
            if (term.isBlank()) null else "(title:$term OR creator:$term OR subject:$term)"
        }
        if (clauses.isEmpty()) return emptyList()
        val archiveQuery = "mediatype:audio AND (" + clauses.joinToString(" OR ") + ")"
        val searchUrl = "https://archive.org/advancedsearch.php?q=${enc(archiveQuery)}&fl[]=identifier&fl[]=title&fl[]=creator&rows=40&page=1&output=json"
        val response = getJson(searchUrl)
        val docs = response.getJSONObject("response").getJSONArray("docs")
        val results = mutableListOf<OnlineSong>()

        for (i in 0 until docs.length()) {
            val doc = docs.getJSONObject(i)
            val identifier = doc.optString("identifier")
            if (identifier.isBlank()) continue
            try {
                val item = getJson("https://archive.org/metadata/${enc(identifier)}")
                val metadata = item.optJSONObject("metadata") ?: continue
                val license = metadata.optString("licenseurl")
                if (!allowedLicense.containsMatchIn(license)) continue

                val files = item.optJSONArray("files") ?: continue
                var audioName: String? = null
                for (j in 0 until files.length()) {
                    val file = files.optJSONObject(j) ?: continue
                    val name = file.optString("name")
                    val format = file.optString("format")
                    val lower = name.lowercase()
                    val isAudio = lower.endsWith(".mp3") || lower.endsWith(".m4a") ||
                        lower.endsWith(".ogg") || lower.endsWith(".flac") ||
                        lower.endsWith(".wav") || lower.endsWith(".aac")
                    val isOriginalOrPlayable = format.contains("MP3", true) ||
                        format.contains("FLAC", true) || format.contains("Ogg", true) ||
                        format.contains("Wave", true) || format.contains("AAC", true) ||
                        format.contains("MPEG", true) || format.isBlank()
                    if (isAudio && isOriginalOrPlayable && file.optString("source") != "metadata") {
                        audioName = name
                        break
                    }
                }
                val fileName = audioName ?: continue
                val title = metadata.optString("title").ifBlank { doc.optString("title") }.ifBlank { fileName.substringAfterLast('/') }
                val creatorValue = metadata.opt("creator")
                val artist = when (creatorValue) {
                    is org.json.JSONArray -> creatorValue.optString(0, "Unknown artist")
                    is String -> creatorValue
                    else -> "Unknown artist"
                }
                val streamUrl = "https://archive.org/download/${enc(identifier)}/${Uri.encode(fileName)}"
                results.add(OnlineSong(identifier, title, artist, streamUrl, license))
            } catch (_: Exception) {
                // Skip unavailable, restricted, or malformed catalogue items.
            }
        }
        return results
    }

    private fun getJson(address: String): JSONObject {
        val connection = URL(address).openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.connectTimeout = 12000
        connection.readTimeout = 15000
        connection.setRequestProperty("User-Agent", "SoundOfSouls/0.2 (Android music discovery)")
        return try {
            if (connection.responseCode !in 200..299) {
                throw IllegalStateException("Catalogue returned HTTP ${connection.responseCode}")
            }
            connection.inputStream.bufferedReader().use { JSONObject(it.readText()) }
        } finally {
            connection.disconnect()
        }
    }

    private fun enc(value: String): String =
        URLEncoder.encode(value, Charsets.UTF_8.name())
}
