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
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class LocalSong(val id: Long, val title: String, val artist: String, val uri: Uri)
private enum class AppTab { ONLINE, LIBRARY }

class MainActivity : ComponentActivity() {
    private lateinit var player: ExoPlayer
    private var songs by mutableStateOf(listOf<LocalSong>())
    private var onlineSongs by mutableStateOf(listOf<OnlineSong>())
    private var currentSongTitle by mutableStateOf<String?>(null)
    private var currentSongArtist by mutableStateOf<String?>(null)
    private var currentSongIsOnline by mutableStateOf(false)
    private var isPlaying by mutableStateOf(false)
    private var searchQuery by mutableStateOf("")
    private var isSearching by mutableStateOf(false)
    private var searchError by mutableStateOf<String?>(null)
    private var lyricsText by mutableStateOf<String?>(null)
    private var syncedLyrics by mutableStateOf("")
    private var lyricsLoading by mutableStateOf(false)
    private var lyricsError by mutableStateOf<String?>(null)
    private var showLyrics by mutableStateOf(false)
    private var darkMode by mutableStateOf(true)
    private var selectedTab by mutableStateOf(AppTab.ONLINE)
    private var playbackPosition by mutableStateOf(0L)
    private var playbackDuration by mutableStateOf(0L)
    private var progressJob: Job? = null

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) loadSongs()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        player = ExoPlayer.Builder(this).build()
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) { isPlaying = playing }
            override fun onPlaybackStateChanged(state: Int) {
                playbackDuration = player.duration.takeIf { it > 0 } ?: 0L
            }
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                playbackPosition = 0L
                playbackDuration = player.duration.takeIf { it > 0 } ?: 0L
            }
        })
        startProgressUpdates()
        requestAudioPermission()
        setContent {
            SoundOfSoulsScreen(
                songs = songs, onlineSongs = onlineSongs,
                currentSongTitle = currentSongTitle, currentSongArtist = currentSongArtist,
                isPlaying = isPlaying, searchQuery = searchQuery,
                isSearching = isSearching, searchError = searchError,
                darkMode = darkMode, selectedTab = selectedTab,
                showLyrics = showLyrics, lyricsText = lyricsText,
                syncedLyrics = syncedLyrics, lyricsLoading = lyricsLoading,
                lyricsError = lyricsError, playbackPosition = playbackPosition,
                playbackDuration = playbackDuration,
                onSearchQueryChange = { searchQuery = it },
                onSearch = { searchOnlineMusic() },
                onRefresh = { refreshCurrentScreen() },
                onThemeToggle = { darkMode = !darkMode },
                onTabChange = { selectedTab = it },
                onSongClick = { playLocalSong(it) },
                onOnlineSongClick = { playOnlineSong(it) },
                onPlayPause = { togglePlayback() },
                onLyricsClick = { showLyrics = true; if (lyricsText == null && !lyricsLoading) findLyrics() },
                onLyricsBack = { showLyrics = false },
                onSeek = { player.seekTo(it) },
                onNext = { skipSong(true) }, onPrevious = { skipSong(false) }
            )
        }
    }

    private fun startProgressUpdates() {
        progressJob = lifecycleScope.launch {
            while (true) {
                if (::player.isInitialized) {
                    playbackPosition = player.currentPosition.coerceAtLeast(0L)
                    playbackDuration = player.duration.takeIf { it > 0 } ?: 0L
                }
                delay(500)
            }
        }
    }

    private fun requestAudioPermission() {
        val permission = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO
            else Manifest.permission.READ_EXTERNAL_STORAGE
        if (ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED) loadSongs()
        else permissionLauncher.launch(permission)
    }

    private fun loadSongs() {
        val found = mutableListOf<LocalSong>()
        val collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.ARTIST)
        val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0"
        val cursor: Cursor? = contentResolver.query(collection, projection, selection, null, "${MediaStore.Audio.Media.TITLE} ASC")
        cursor?.use {
            val idColumn = it.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val titleColumn = it.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val artistColumn = it.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            while (it.moveToNext()) {
                val id = it.getLong(idColumn)
                found.add(LocalSong(id, it.getString(titleColumn) ?: "Unknown title",
                    it.getString(artistColumn) ?: "Unknown artist", ContentUris.withAppendedId(collection, id)))
            }
        }
        songs = found
    }

    private fun refreshCurrentScreen() {
        if (selectedTab == AppTab.LIBRARY) loadSongs()
        else if (searchQuery.isNotBlank()) searchOnlineMusic()
        else { onlineSongs = emptyList(); searchError = "Search for a song, artist or genre to discover online music." }
    }

    private fun searchOnlineMusic() {
        val query = searchQuery.trim()
        if (query.isEmpty()) {
            searchError = "Type a song, artist or genre first."
            onlineSongs = emptyList()
            return
        }
        lifecycleScope.launch {
            isSearching = true
            searchError = null
            try {
                val results = withContext(Dispatchers.IO) { InternetArchiveMusic.search(query) }
                onlineSongs = results
                if (results.isEmpty()) searchError = "No open-licensed tracks found for “$query”. Try a different spelling or broader search."
            } catch (e: Exception) {
                onlineSongs = emptyList()
                searchError = "Search failed. Check your internet connection and tap refresh."
            } finally { isSearching = false }
        }
    }

    private fun playLocalSong(song: LocalSong) {
        currentSongTitle = song.title; currentSongArtist = song.artist; currentSongIsOnline = false
        lyricsText = null; syncedLyrics = ""; lyricsError = null
        player.setMediaItem(MediaItem.fromUri(song.uri)); player.prepare(); player.play()
    }

    private fun playOnlineSong(song: OnlineSong) {
        currentSongTitle = song.title; currentSongArtist = song.artist; currentSongIsOnline = true
        lyricsText = null; syncedLyrics = ""; lyricsError = null
        player.setMediaItem(MediaItem.fromUri(song.streamUrl)); player.prepare(); player.play()
    }

    private fun togglePlayback() {
        if (player.mediaItemCount == 0) {
            if (songs.isNotEmpty()) playLocalSong(songs.first())
        } else if (player.isPlaying) player.pause() else player.play()
    }

    private fun skipSong(next: Boolean) {
        if (currentSongIsOnline) {
            val index = onlineSongs.indexOfFirst { it.title == currentSongTitle && it.artist == currentSongArtist }
            val target = if (next) index + 1 else index - 1
            if (target in onlineSongs.indices) playOnlineSong(onlineSongs[target])
        } else {
            val index = songs.indexOfFirst { it.title == currentSongTitle && it.artist == currentSongArtist }
            val target = if (next) index + 1 else index - 1
            if (target in songs.indices) playLocalSong(songs[target])
        }
    }

    private fun findLyrics() {
        val title = currentSongTitle ?: return
        val artist = currentSongArtist ?: ""
        lifecycleScope.launch {
            lyricsLoading = true; lyricsError = null
            try {
                val result = withContext(Dispatchers.IO) { LyricsClient.find(title, artist) }
                if (result == null) {
                    lyricsError = "Lyrics weren't found for this exact song. You can try checking the title and artist."
                    lyricsText = null; syncedLyrics = ""
                } else {
                    lyricsText = result.plainLyrics.ifBlank { result.syncedLyrics }
                    syncedLyrics = result.syncedLyrics
                }
            } catch (e: Exception) {
                lyricsError = "Couldn't retrieve lyrics. Check your internet connection and try again."
            } finally { lyricsLoading = false }
        }
    }

    override fun onDestroy() {
        progressJob?.cancel()
        player.release()
        super.onDestroy()
    }
}

@Composable
private fun SoundOfSoulsScreen(
    songs: List<LocalSong>, onlineSongs: List<OnlineSong>,
    currentSongTitle: String?, currentSongArtist: String?, isPlaying: Boolean,
    searchQuery: String, isSearching: Boolean, searchError: String?,
    darkMode: Boolean, selectedTab: AppTab, showLyrics: Boolean,
    lyricsText: String?, syncedLyrics: String, lyricsLoading: Boolean, lyricsError: String?,
    playbackPosition: Long, playbackDuration: Long,
    onSearchQueryChange: (String) -> Unit, onSearch: () -> Unit, onRefresh: () -> Unit,
    onThemeToggle: () -> Unit, onTabChange: (AppTab) -> Unit,
    onSongClick: (LocalSong) -> Unit, onOnlineSongClick: (OnlineSong) -> Unit,
    onPlayPause: () -> Unit, onLyricsClick: () -> Unit, onLyricsBack: () -> Unit,
    onSeek: (Long) -> Unit, onNext: () -> Unit, onPrevious: () -> Unit
) {
    val bg = if (darkMode) Color(0xFF090711) else Color(0xFFF5F2FA)
    val panel = if (darkMode) Color(0xFF211D30) else Color(0xFFFFFFFF)
    val primaryText = if (darkMode) Color.White else Color(0xFF21172E)
    val secondaryText = if (darkMode) Color(0xFFD0C8DD) else Color(0xFF665D70)
    val accent = Color(0xFF9567EF)
    val fmt: (Long) -> String = { ms -> "%d:%02d".format(ms / 60000, (ms / 1000) % 60) }

    MaterialTheme(colorScheme = if (darkMode) darkColorScheme(primary = accent, surface = panel, background = bg)
        else lightColorScheme(primary = accent, surface = panel, background = bg)) {
        Surface(Modifier.fillMaxSize(), color = bg) {
            Column(Modifier.fillMaxSize().padding(horizontal = 18.dp, vertical = 14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("SOUND OF SOULS", color = primaryText, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold)
                        Text("YOUR MUSIC. YOUR WORLD.", color = accent, fontSize = 10.sp, letterSpacing = 2.sp)
                    }
                    IconButton(onClick = onThemeToggle) {
                        Icon(if (darkMode) Icons.Default.LightMode else Icons.Default.DarkMode,
                            contentDescription = "Switch theme", tint = primaryText)
                    }
                    IconButton(onClick = onRefresh) {
                        Icon(Icons.Default.Refresh, contentDescription = "Reload", tint = primaryText)
                    }
                }
                if (showLyrics) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 12.dp)) {
                        IconButton(onClick = onLyricsBack) { Icon(Icons.Default.ArrowBack, "Back", tint = primaryText) }
                        Text("NOW PLAYING • LYRICS", color = primaryText, fontWeight = FontWeight.Bold)
                    }
                    Text(currentSongTitle ?: "No song selected", color = primaryText, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    Text(currentSongArtist ?: "", color = secondaryText, fontSize = 14.sp)
                    Spacer(Modifier.height(16.dp))
                    when {
                        lyricsLoading -> Text("Finding lyrics…", color = secondaryText)
                        lyricsError != null -> Text(lyricsError, color = secondaryText)
                        lyricsText != null -> LazyColumn(Modifier.weight(1f)) {
                            items(lyricsText.lines()) { line -> Text(line, color = primaryText, fontSize = 17.sp,
                                modifier = Modifier.padding(vertical = 5.dp)) }
                        }
                        else -> Text("Lyrics will appear here.", color = secondaryText)
                    }
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 14.dp, bottom = 12.dp)) {
                        FilterChip(selected = selectedTab == AppTab.ONLINE, onClick = { onTabChange(AppTab.ONLINE) },
                            label = { Text("Discover online") }, leadingIcon = { Icon(Icons.Default.Explore, null) })
                        FilterChip(selected = selectedTab == AppTab.LIBRARY, onClick = { onTabChange(AppTab.LIBRARY) },
                            label = { Text("My library") }, leadingIcon = { Icon(Icons.Default.LibraryMusic, null) })
                    }
                    if (selectedTab == AppTab.ONLINE) {
                        Text("Find your next favourite", color = primaryText, fontSize = 23.sp, fontWeight = FontWeight.Bold)
                        Text("Search open-licensed music you can stream", color = secondaryText, fontSize = 13.sp,
                            modifier = Modifier.padding(top = 3.dp, bottom = 10.dp))
                        OutlinedTextField(
                            value = searchQuery, onValueChange = onSearchQueryChange,
                            modifier = Modifier.fillMaxWidth(), singleLine = true,
                            placeholder = { Text("Search songs, artists, genres") },
                            leadingIcon = { Icon(Icons.Default.Search, null) },
                            trailingIcon = { IconButton(onClick = onSearch, enabled = !isSearching) {
                                Icon(Icons.Default.ArrowForward, "Search")
                            } },
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            keyboardActions = KeyboardActions(onSearch = { onSearch() })
                        )
                        if (isSearching) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
                        if (searchError != null) Text(searchError, color = secondaryText, fontSize = 13.sp,
                            modifier = Modifier.padding(vertical = 8.dp))
                        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(onlineSongs, key = { it.identifier + it.streamUrl }) { song ->
                                SongRow(song.title, song.artist, "Open-licensed • Internet Archive", panel, primaryText, secondaryText, accent) {
                                    onOnlineSongClick(song)
                                }
                            }
                        }
                    } else {
                        Text("Your music", color = primaryText, fontSize = 23.sp, fontWeight = FontWeight.Bold)
                        Text("${songs.size} songs on this device", color = secondaryText, fontSize = 13.sp,
                            modifier = Modifier.padding(top = 3.dp, bottom = 10.dp))
                        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(songs, key = { it.id }) { song ->
                                SongRow(song.title, song.artist, "On this device", panel, primaryText, secondaryText, accent) { onSongClick(song) }
                            }
                        }
                    }
                }
                if (currentSongTitle != null) {
                    Column(Modifier.fillMaxWidth().background(panel, RoundedCornerShape(18.dp)).padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Album, null, tint = accent, modifier = Modifier.size(36.dp))
                            Column(Modifier.weight(1f).padding(start = 10.dp)) {
                                Text(currentSongTitle, color = primaryText, fontWeight = FontWeight.Bold, maxLines = 1)
                                Text(currentSongArtist ?: "", color = secondaryText, fontSize = 12.sp, maxLines = 1)
                            }
                            IconButton(onClick = onLyricsClick) { Icon(Icons.Default.Lyrics, "Show lyrics", tint = accent) }
                        }
                        Slider(value = if (playbackDuration > 0) playbackPosition.coerceIn(0, playbackDuration).toFloat() else 0f,
                            onValueChange = { onSeek(it.toLong()) },
                            valueRange = 0f..(playbackDuration.coerceAtLeast(1L).toFloat()))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(fmt(playbackPosition), color = secondaryText, fontSize = 10.sp)
                            Text(fmt(playbackDuration), color = secondaryText, fontSize = 10.sp)
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = onPrevious) { Icon(Icons.Default.SkipPrevious, "Previous", tint = primaryText) }
                            IconButton(onClick = onPlayPause) {
                                Icon(if (isPlaying) Icons.Default.PauseCircleFilled else Icons.Default.PlayCircleFilled,
                                    if (isPlaying) "Pause" else "Play", tint = accent, modifier = Modifier.size(42.dp))
                            }
                            IconButton(onClick = onNext) { Icon(Icons.Default.SkipNext, "Next", tint = primaryText) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SongRow(title: String, artist: String, subtitle: String, panel: Color,
    primary: Color, secondary: Color, accent: Color, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().background(panel, RoundedCornerShape(14.dp)).clickable(onClick = onClick).padding(13.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Default.MusicNote, null, tint = accent, modifier = Modifier.size(30.dp))
        Column(Modifier.weight(1f).padding(start = 11.dp)) {
            Text(title, color = primary, fontWeight = FontWeight.SemiBold, maxLines = 1)
            Text(artist, color = secondary, fontSize = 12.sp, maxLines = 1)
            Text(subtitle, color = accent, fontSize = 10.sp)
        }
        Icon(Icons.Default.PlayArrow, "Play $title", tint = primary)
    }
}
