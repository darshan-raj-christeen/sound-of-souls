package com.soundofsouls.player

import android.Manifest
import android.content.ContentUris
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class LocalSong(val id: Long, val title: String, val artist: String, val uri: Uri)

class MainActivity : ComponentActivity() {
    private lateinit var player: ExoPlayer
    private var songs by mutableStateOf(listOf<LocalSong>())
    private var onlineSongs by mutableStateOf(listOf<OnlineSong>())
    private var currentSongTitle by mutableStateOf<String?>(null)
    private var currentSongArtist by mutableStateOf<String?>(null)
    private var isPlaying by mutableStateOf(false)
    private var searchQuery by mutableStateOf("")
    private var isSearching by mutableStateOf(false)
    private var searchError by mutableStateOf<String?>(null)

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) loadSongs()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        player = ExoPlayer.Builder(this).build()
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
            }
        })
        requestAudioPermission()
        setContent {
            MaterialTheme {
                SoundOfSoulsScreen(
                    songs = songs,
                    onlineSongs = onlineSongs,
                    currentSongTitle = currentSongTitle,
                    currentSongArtist = currentSongArtist,
                    isPlaying = isPlaying,
                    searchQuery = searchQuery,
                    isSearching = isSearching,
                    searchError = searchError,
                    onSearchQueryChange = { searchQuery = it },
                    onSearch = { searchOnlineMusic() },
                    onRefresh = { requestAudioPermission() },
                    onSongClick = { playLocalSong(it) },
                    onOnlineSongClick = { playOnlineSong(it) },
                    onPlayPause = { togglePlayback() }
                )
            }
        }
    }

    private fun requestAudioPermission() {
        val permission = if (Build.VERSION.SDK_INT >= 33) {
            Manifest.permission.READ_MEDIA_AUDIO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
        if (ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED) {
            loadSongs()
        } else {
            permissionLauncher.launch(permission)
        }
    }

    private fun loadSongs() {
        val found = mutableListOf<LocalSong>()
        val collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST
        )
        val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0"
        val sortOrder = "${MediaStore.Audio.Media.TITLE} ASC"
        val cursor: Cursor? = contentResolver.query(collection, projection, selection, null, sortOrder)
        cursor?.use {
            val idColumn = it.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val titleColumn = it.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val artistColumn = it.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            while (it.moveToNext()) {
                val id = it.getLong(idColumn)
                val uri = ContentUris.withAppendedId(collection, id)
                found.add(LocalSong(id, it.getString(titleColumn) ?: "Unknown title",
                    it.getString(artistColumn) ?: "Unknown artist", uri))
            }
        }
        songs = found
    }

    private fun searchOnlineMusic() {
        val query = searchQuery.trim()
        if (query.isEmpty()) {
            searchError = "Enter a song, artist, or genre to search."
            return
        }
        lifecycleScope.launch {
            isSearching = true
            searchError = null
            try {
                val results = withContext(Dispatchers.IO) {
                    InternetArchiveMusic.search(query)
                }
                onlineSongs = results
                if (results.isEmpty()) {
                    searchError = "No playable tracks with a verified open licence were found. Try another search."
                }
            } catch (error: Exception) {
                searchError = "Could not load music. Check your internet connection and try again."
            } finally {
                isSearching = false
            }
        }
    }

    private fun playLocalSong(song: LocalSong) {
        currentSongTitle = song.title
        currentSongArtist = song.artist
        player.setMediaItem(MediaItem.fromUri(song.uri))
        player.prepare()
        player.play()
    }

    private fun playOnlineSong(song: OnlineSong) {
        currentSongTitle = song.title
        currentSongArtist = song.artist
        player.setMediaItem(MediaItem.fromUri(song.streamUrl))
        player.prepare()
        player.play()
    }

    private fun togglePlayback() {
        if (player.mediaItemCount == 0 && songs.isNotEmpty()) {
            playLocalSong(songs.first())
        } else if (player.isPlaying) {
            player.pause()
        } else {
            player.play()
        }
    }

    override fun onDestroy() {
        player.release()
        super.onDestroy()
    }
}

@androidx.compose.runtime.Composable
private fun SoundOfSoulsScreen(
    songs: List<LocalSong>,
    onlineSongs: List<OnlineSong>,
    currentSongTitle: String?,
    currentSongArtist: String?,
    isPlaying: Boolean,
    searchQuery: String,
    isSearching: Boolean,
    searchError: String?,
    onSearchQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
    onRefresh: () -> Unit,
    onSongClick: (LocalSong) -> Unit,
    onOnlineSongClick: (OnlineSong) -> Unit,
    onPlayPause: () -> Unit
) {
    val background = Color(0xFF090711)
    val purple = Color(0xFF9B6DFF)
    Surface(modifier = Modifier.fillMaxSize(), color = background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Brush.verticalGradient(listOf(Color(0xFF21133D), background)))
                .padding(horizontal = 18.dp, vertical = 16.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("SOUND OF SOULS", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold)
                    Text("YOUR MUSIC. YOUR WORLD.", color = Color(0xFFBDA5FF), fontSize = 10.sp, letterSpacing = 2.sp)
                }
                IconButton(onClick = onRefresh) {
                    Icon(Icons.Default.Refresh, contentDescription = "Refresh music", tint = Color.White)
                }
            }
            Spacer(Modifier.size(18.dp))
            Text("Discover music", color = Color.White, fontSize = 25.sp, fontWeight = FontWeight.Bold)
            Text("Search free-to-stream music", color = Color.LightGray, fontSize = 13.sp,
                modifier = Modifier.padding(top = 3.dp, bottom = 10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = onSearchQueryChange,
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    placeholder = { Text("Song, artist or genre") }
                )
                IconButton(onClick = onSearch, enabled = !isSearching) {
                    Icon(Icons.Default.Search, contentDescription = "Search songs", tint = Color.White)
                }
            }
            if (isSearching) {
                Text("Searching music catalogue…", color = Color(0xFFD0C2F5), fontSize = 13.sp,
                    modifier = Modifier.padding(vertical = 8.dp))
            }
            if (searchError != null) {
                Text(searchError, color = Color(0xFFFFC1C1), fontSize = 12.sp,
                    modifier = Modifier.padding(vertical = 6.dp))
            }
            if (onlineSongs.isNotEmpty()) {
                Text("ONLINE RESULTS", color = Color(0xFFBDA5FF), fontSize = 11.sp,
                    fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp,
                    modifier = Modifier.padding(top = 8.dp, bottom = 8.dp))
            }
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(onlineSongs, key = { it.identifier + it.streamUrl }) { song ->
                    Row(
                        modifier = Modifier.fillMaxWidth()
                            .background(Color(0xFF211D30), RoundedCornerShape(14.dp))
                            .clickable { onOnlineSongClick(song) }
                            .padding(13.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.MusicNote, contentDescription = null, tint = purple)
                        Column(modifier = Modifier.weight(1f).padding(start = 11.dp)) {
                            Text(song.title, color = Color.White, fontWeight = FontWeight.SemiBold, maxLines = 1)
                            Text(song.artist, color = Color.LightGray, fontSize = 12.sp, maxLines = 1)
                            Text("Open-licensed • Internet Archive", color = Color(0xFFBDA5FF), fontSize = 10.sp)
                        }
                        Icon(Icons.Default.PlayArrow, contentDescription = "Play ${song.title}", tint = Color.White)
                    }
                }
                item {
                    Row(verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(top = 12.dp, bottom = 6.dp)) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Your library", color = Color.White, fontSize = 21.sp, fontWeight = FontWeight.Bold)
                            Text("${songs.size} songs on this device", color = Color.LightGray, fontSize = 12.sp)
                        }
                        IconButton(onClick = onRefresh) {
                            Icon(Icons.Default.Refresh, contentDescription = "Refresh library", tint = Color.White)
                        }
                    }
                }
                if (songs.isEmpty()) {
                    item {
                        Text("No local music found. Import audio files or search online above.",
                            color = Color.LightGray, fontSize = 13.sp, modifier = Modifier.padding(vertical = 18.dp))
                    }
                } else {
                    items(songs, key = { "local-${it.id}" }) { song ->
                        Row(
                            modifier = Modifier.fillMaxWidth()
                                .background(Color(0xFF211D30), RoundedCornerShape(14.dp))
                                .clickable { onSongClick(song) }
                                .padding(13.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.MusicNote, contentDescription = null, tint = purple)
                            Column(modifier = Modifier.weight(1f).padding(start = 11.dp)) {
                                Text(song.title, color = Color.White, fontWeight = FontWeight.SemiBold, maxLines = 1)
                                Text(song.artist, color = Color.LightGray, fontSize = 12.sp, maxLines = 1)
                            }
                            Icon(Icons.Default.PlayArrow, contentDescription = "Play ${song.title}", tint = Color.White)
                        }
                    }
                }
            }
            if (currentSongTitle != null) {
                Row(
                    modifier = Modifier.fillMaxWidth()
                        .background(Color(0xFF30204D), RoundedCornerShape(18.dp))
                        .padding(13.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(currentSongTitle ?: "", color = Color.White, fontWeight = FontWeight.Bold, maxLines = 1)
                        Text(currentSongArtist ?: "", color = Color(0xFFD0C2F5), fontSize = 12.sp, maxLines = 1)
                    }
                    IconButton(onClick = onPlayPause) {
                        Icon(if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = if (isPlaying) "Pause" else "Play", tint = Color.White)
                    }
                }
            }
        }
    }
}
