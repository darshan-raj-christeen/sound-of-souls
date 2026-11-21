package com.soundofsouls.player

import android.content.Context
import android.net.Uri

/**
 * Register bundled MP3 files here.
 *
 * For each song:
 * 1. Copy its MP3 into app/src/main/res/raw/
 * 2. Use a lowercase resource filename, for example radhimaa.mp3
 * 3. Add an entry below with the filename WITHOUT the .mp3 extension.
 *
 * Entries are only shown when the matching audio resource exists, so the app
 * never displays a built-in song that has no playable audio file.
 */
private data class BuiltInSongDefinition(
    val title: String,
    val artist: String,
    val resourceName: String
)

object BuiltInSongCatalog {
    private val definitions = listOf(
        BuiltInSongDefinition(
            title = "Radhimaa",
            artist = "Sai Abhyankkar",
            resourceName = "radhimaa"
        )
        // Add more songs by copying this pattern:
        // BuiltInSongDefinition("Song title", "Artist name", "mp3_file_name")
    )

    fun resolve(context: Context): List<LocalSong> = definitions.mapNotNull { song ->
        val resourceId = context.resources.getIdentifier(
            song.resourceName,
            "raw",
            context.packageName
        )
        if (resourceId == 0) {
            null
        } else {
            LocalSong(
                id = Long.MIN_VALUE + resourceId.toLong(),
                title = song.title,
                artist = song.artist,
                uri = Uri.parse("android.resource://${context.packageName}/$resourceId")
            )
        }
    }
}
