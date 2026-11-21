package com.soundofsouls.player

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class LyricsResult(
    val trackName: String,
    val artistName: String,
    val plainLyrics: String,
    val syncedLyrics: String
)

object LyricsClient {
    private fun normalize(value: String): String =
        value.lowercase()
            .replace(Regex("\\([^)]*\\)|\\[[^]]*]"), " ")
            .replace(Regex("\\b(feat|ft|featuring)\\b.*$"), " ")
            .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
            .trim()
            .replace(Regex("\\s+"), " ")

    fun find(track: String, artist: String): LyricsResult? {
        val cleanTrack = track.trim()
        if (cleanTrack.isBlank()) return null
        val queries = listOf(
            "$cleanTrack $artist".trim(),
            cleanTrack
        ).distinct()
        val results = linkedMapOf<String, JSONObject>()

        for (queryText in queries) {
            val query = URLEncoder.encode(queryText, Charsets.UTF_8.name())
            val connection = URL("https://lrclib.net/api/search?q=$query")
                .openConnection() as HttpURLConnection
            connection.connectTimeout = 6500
            connection.readTimeout = 8500
            connection.setRequestProperty("User-Agent", "SoundOfSouls/0.3 (Android lyrics lookup)")
            try {
                if (connection.responseCode !in 200..299) continue
                val json = connection.inputStream.bufferedReader().use { JSONArray(it.readText()) }
                for (i in 0 until json.length()) {
                    val candidate = json.optJSONObject(i) ?: continue
                    val id = candidate.optString("id").ifBlank {
                        candidate.optString("trackName") + candidate.optString("artistName")
                    }
                    results[id] = candidate
                }
            } finally {
                connection.disconnect()
            }
            if (results.values.any { normalize(it.optString("trackName")) == normalize(cleanTrack) }) break
        }

        val wantedTrack = normalize(cleanTrack)
        val wantedArtist = normalize(artist)
        val best = results.values.mapNotNull { candidate ->
            val candidateTrack = normalize(candidate.optString("trackName"))
            val candidateArtist = normalize(candidate.optString("artistName"))
            if (candidateTrack.isBlank()) return@mapNotNull null
            val trackExact = candidateTrack == wantedTrack
            val trackClose = trackExact || candidateTrack.contains(wantedTrack) ||
                wantedTrack.contains(candidateTrack)
            val wantedArtistTokens = wantedArtist.split(" ").filter { it.length > 1 }.toSet()
            val candidateArtistTokens = candidateArtist.split(" ").filter { it.length > 1 }.toSet()
            val sharedArtistTokens = wantedArtistTokens.intersect(candidateArtistTokens).size
            val artistClose = wantedArtist.isBlank() || candidateArtist == wantedArtist ||
                candidateArtist.contains(wantedArtist) || wantedArtist.contains(candidateArtist) ||
                (wantedArtistTokens.isNotEmpty() && sharedArtistTokens >= 1 &&
                    sharedArtistTokens.toDouble() / wantedArtistTokens.size >= 0.5)
            // Never accept a same-title result from a different artist. A missing artist
            // is allowed, but a supplied artist must have a meaningful match.
            if (!trackClose || (wantedArtist.isNotBlank() && !artistClose)) return@mapNotNull null
            val score = (if (trackExact) 100 else 60) +
                (if (artistClose && wantedArtist.isNotBlank()) 30 else 0) +
                (if (candidate.optString("syncedLyrics").isNotBlank()) 5 else 0) +
                (if (candidate.optString("plainLyrics").isNotBlank()) 2 else 0)
            score to candidate
        }.maxByOrNull { it.first }?.second ?: return null

        val synced = best.optString("syncedLyrics")
        val plain = best.optString("plainLyrics")
        if (synced.isBlank() && plain.isBlank()) return null
        return LyricsResult(
            best.optString("trackName", cleanTrack),
            best.optString("artistName", artist),
            plain,
            synced
        )
    }
}
