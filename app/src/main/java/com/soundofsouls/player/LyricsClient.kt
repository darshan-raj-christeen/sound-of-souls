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
    fun find(track: String, artist: String): LyricsResult? {
        val query = URLEncoder.encode("$track $artist", Charsets.UTF_8.name())
        val connection = (URL("https://lrclib.net/api/search?q=$query").openConnection() as HttpURLConnection)
        connection.connectTimeout = 10000
        connection.readTimeout = 12000
        connection.setRequestProperty("User-Agent", "SoundOfSouls/0.2 (https://github.com/darshan-raj-christeen/sound-of-souls)")
        return try {
            if (connection.responseCode !in 200..299) return null
            val json = connection.inputStream.bufferedReader().use { JSONArray(it.readText()) }
            var best: JSONObject? = null
            val wantedTrack = track.trim().lowercase()
            val wantedArtist = artist.trim().lowercase()
            for (i in 0 until json.length()) {
                val candidate = json.optJSONObject(i) ?: continue
                val candidateTrack = candidate.optString("trackName").lowercase()
                val candidateArtist = candidate.optString("artistName").lowercase()
                if (candidateTrack == wantedTrack && (wantedArtist.isBlank() ||
                    candidateArtist == wantedArtist || candidateArtist.contains(wantedArtist) ||
                    wantedArtist.contains(candidateArtist))) {
                    best = candidate
                    if (candidate.optString("syncedLyrics").isNotBlank()) break
                }
            }
            val chosen = best ?: return null
            val synced = chosen.optString("syncedLyrics")
            val plain = chosen.optString("plainLyrics")
            if (synced.isBlank() && plain.isBlank()) return null
            LyricsResult(
                chosen.optString("trackName", track),
                chosen.optString("artistName", artist),
                plain,
                synced
            )
        } finally {
            connection.disconnect()
        }
    }
}
