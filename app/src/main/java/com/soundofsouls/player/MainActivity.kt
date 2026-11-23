package com.soundofsouls.player

import android.Manifest
import android.content.ContentUris
import android.content.Intent
import android.media.audiofx.AudioEffect
import android.provider.Settings
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.PlaybackException
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
    private var builtInSongs by mutableStateOf(listOf<LocalSong>())
    private var importedSongs by mutableStateOf(listOf<LocalSong>())
    private var onlineSongs by mutableStateOf(listOf<OnlineSong>())
    private var currentSongTitle by mutableStateOf<String?>(null)
    private var currentSongArtist by mutableStateOf<String?>(null)
    private var currentSongIsOnline by mutableStateOf(false)
    private var isPlaying by mutableStateOf(false)
    private var playbackError by mutableStateOf<String?>(null)
    private var searchQuery by mutableStateOf("")
    private var isSearching by mutableStateOf(false)
    private var searchError by mutableStateOf<String?>(null)
    private var lyricsText by mutableStateOf<String?>(null)
    private var syncedLyrics by mutableStateOf("")
    private var lyricsLoading by mutableStateOf(false)
    private var lyricsRequestId = 0L
    private var lyricsError by mutableStateOf<String?>(null)
    private var showLyrics by mutableStateOf(false)
    private var darkMode by mutableStateOf(true)
    private var highQualityAudio by mutableStateOf(false)
    private var selectedTab by mutableStateOf(AppTab.ONLINE)
    private var playbackPosition by mutableStateOf(0L)
    private var playbackDuration by mutableStateOf(0L)
    private var progressJob: Job? = null

    private val audioPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            try { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            catch (_: Exception) { }
            if (importedSongs.none { it.uri == uri } && songs.none { it.uri == uri }) {
                val displayName = uri.lastPathSegment?.substringAfterLast('/')?.substringBefore('?')
                    ?.substringAfterLast(':')?.ifBlank { "Imported audio" } ?: "Imported audio"
                val added = LocalSong(-System.currentTimeMillis(),
                    displayName.substringBeforeLast('.', displayName), "Local audio file", uri)
                importedSongs = importedSongs + added
                songs = songs + added
                val savedUris = getSharedPreferences("sound_of_souls_library", MODE_PRIVATE)
                    .getStringSet("imported_song_uris", emptySet())?.toMutableSet() ?: mutableSetOf()
                savedUris.add(uri.toString())
                getSharedPreferences("sound_of_souls_library", MODE_PRIVATE).edit()
                    .putStringSet("imported_song_uris", savedUris).apply()
            }
        }
    }

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) loadSongs()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        highQualityAudio = getSharedPreferences("sound_of_souls_settings", MODE_PRIVATE)
            .getBoolean("high_quality_audio", false)
        player = ExoPlayer.Builder(this).build()
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) { isPlaying = playing }
            override fun onPlaybackStateChanged(state: Int) {
                playbackDuration = player.duration.takeIf { it > 0 } ?: 0L
            }
            override fun onPlayerError(error: PlaybackException) {
                isPlaying = false
                playbackError = "This stream could not be played. The source may block streaming or the file may be unavailable."
            }
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                playbackPosition = 0L
                playbackDuration = player.duration.takeIf { it > 0 } ?: 0L
            }
        })
        startProgressUpdates()
        restoreImportedSongs()
        builtInSongs = BuiltInSongCatalog.resolve(this)
        songs = builtInSongs + importedSongs
        requestAudioPermission()
        setContent {
            SoundOfSoulsScreen(
                songs = songs, builtInSongs = builtInSongs, onlineSongs = onlineSongs,
                featuredSongAvailable = builtInSongs.any { it.title.equals("Radhimaa", ignoreCase = true) },
                currentSongTitle = currentSongTitle, currentSongArtist = currentSongArtist,
                isPlaying = isPlaying, playbackError = playbackError, searchQuery = searchQuery,
                isSearching = isSearching, searchError = searchError,
                darkMode = darkMode, selectedTab = selectedTab, highQualityAudio = highQualityAudio,
                showLyrics = showLyrics, lyricsText = lyricsText,
                syncedLyrics = syncedLyrics, lyricsLoading = lyricsLoading,
                lyricsError = lyricsError, playbackPosition = playbackPosition,
                playbackDuration = playbackDuration,
                onSearchQueryChange = { searchQuery = it },
                onSearch = { searchOnlineMusic() },
                onRefresh = { refreshCurrentScreen() },
                onAddMp3 = { audioPicker.launch(arrayOf("audio/mpeg", "audio/mp3", "audio/*")) },
                onThemeToggle = { darkMode = !darkMode },
                onQualityToggle = {
                    highQualityAudio = !highQualityAudio
                    getSharedPreferences("sound_of_souls_settings", MODE_PRIVATE).edit()
                        .putBoolean("high_quality_audio", highQualityAudio).apply()
                    if (searchQuery.isNotBlank()) searchOnlineMusic()
                },
                onDolbyAtmosClick = { openDolbyAtmosSettings() },
                onTabChange = { selectedTab = it },
                onSongClick = { playLocalSong(it) },
                onFeaturedSongClick = { builtInSongs.firstOrNull { it.title.equals("Radhimaa", ignoreCase = true) }?.let { playLocalSong(it) } },
                onTrendingSongClick = { openTamilMusicCategory(it) },
                onOnlineSongClick = { playOnlineSong(it) },
                onPlayPause = { togglePlayback() },
                onLyricsClick = { showLyrics = true; if (lyricsText == null && !lyricsLoading) findLyrics() },
                onLyricsBack = { showLyrics = false },
                onSaveLyrics = { saveManualLyrics(it) },
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

    private fun restoreImportedSongs() {
        val savedUris = getSharedPreferences("sound_of_souls_library", MODE_PRIVATE)
            .getStringSet("imported_song_uris", emptySet()).orEmpty()
        val restored = savedUris.mapNotNull { value ->
            try {
                val uri = Uri.parse(value)
                val displayName = uri.lastPathSegment?.substringAfterLast('/')?.substringBefore('?')
                    ?.substringAfterLast(':')?.ifBlank { "Imported audio" } ?: "Imported audio"
                LocalSong(-uri.toString().hashCode().toLong(), displayName.substringBeforeLast('.', displayName),
                    "Imported audio", uri)
            } catch (_: Exception) { null }
        }
        importedSongs = restored
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
        builtInSongs = BuiltInSongCatalog.resolve(this)
        songs = builtInSongs + found + importedSongs
    }

    private fun openDolbyAtmosSettings() {
        val effectIntent = Intent(AudioEffect.ACTION_DISPLAY_AUDIO_EFFECT_CONTROL_PANEL).apply {
            putExtra(AudioEffect.EXTRA_AUDIO_SESSION, player.audioSessionId)
            putExtra(AudioEffect.EXTRA_PACKAGE_NAME, packageName)
            putExtra(AudioEffect.EXTRA_CONTENT_TYPE, AudioEffect.CONTENT_TYPE_MUSIC)
        }
        try {
            startActivity(effectIntent)
        } catch (_: Exception) {
            try {
                startActivity(Intent(Settings.ACTION_SOUND_SETTINGS))
            } catch (_: Exception) {
                playbackError = "Open Settings > Sound & vibration > Dolby Atmos to adjust audio."
            }
        }
    }

    private fun openOfficialMusicSearch() {
        val query = searchQuery.trim().ifBlank { currentSongTitle ?: "music" }
        val musicUri = Uri.parse("https://music.youtube.com/search?q=${Uri.encode(query)}")
        try {
            startActivity(Intent(Intent.ACTION_VIEW, musicUri))
        } catch (_: Exception) {
            val youtubeUri = Uri.parse("https://www.youtube.com/results?search_query=${Uri.encode(query)}")
            try {
                startActivity(Intent(Intent.ACTION_VIEW, youtubeUri))
            } catch (_: Exception) {
                playbackError = "No compatible music app or browser was found."
            }
        }
    }

    private fun openTamilMusicCategory(query: String) {
        val musicUri = Uri.parse("https://music.youtube.com/search?q=${Uri.encode(query)}")
        try { startActivity(Intent(Intent.ACTION_VIEW, musicUri)) }
        catch (_: Exception) {
            val youtubeUri = Uri.parse("https://www.youtube.com/results?search_query=${Uri.encode(query)}")
            try { startActivity(Intent(Intent.ACTION_VIEW, youtubeUri)) }
            catch (_: Exception) { playbackError = "Could not open the official music search." }
        }
    }

    private fun openMyYouTubeSong() {
        val videoUri = Uri.parse("https://youtu.be/0d0Ud58wCq8")
        try { startActivity(Intent(Intent.ACTION_VIEW, videoUri)) }
        catch (_: Exception) { playbackError = "Could not open the official YouTube video. Check your internet connection." }
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
                val results = withContext(Dispatchers.IO) { InternetArchiveMusic.search(query, highQualityAudio) }
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
        playbackError = null
        lyricsRequestId++
        lyricsLoading = false
        lyricsText = null; syncedLyrics = ""; lyricsError = null
        player.setMediaItem(MediaItem.fromUri(song.uri)); player.prepare(); player.play()
        // Automatically look up lyrics as soon as a new local track starts.
        findLyrics()
    }

    private fun playOnlineSong(song: OnlineSong) {
        currentSongTitle = song.title; currentSongArtist = song.artist; currentSongIsOnline = true
        playbackError = null
        lyricsRequestId++
        lyricsLoading = false
        lyricsText = null; syncedLyrics = ""; lyricsError = null
        player.setMediaItem(MediaItem.fromUri(song.streamUrl)); player.prepare(); player.play()
        // Automatically look up lyrics as soon as a new online track starts.
        findLyrics()
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

    private fun manualLyricsKey(title: String, artist: String): String =
        "manual_lyrics_" + (title.trim().lowercase() + "|" + artist.trim().lowercase())

    private fun saveManualLyrics(text: String) {
        lyricsRequestId++
        lyricsLoading = false
        val title = currentSongTitle ?: return
        val artist = currentSongArtist ?: ""
        getSharedPreferences("sound_of_souls_lyrics", MODE_PRIVATE).edit()
            .putString(manualLyricsKey(title, artist), text).apply()
        lyricsText = text.takeIf { it.isNotBlank() }
        syncedLyrics = ""
        lyricsError = if (text.isBlank()) "No saved lyrics. Tap Find lyrics to search again." else null
    }

    private fun findLyrics() {
        val title = currentSongTitle ?: return
        val artist = currentSongArtist ?: ""
        val requestId = ++lyricsRequestId
        val savedLyrics = getSharedPreferences("sound_of_souls_lyrics", MODE_PRIVATE)
            .getString(manualLyricsKey(title, artist), null)
        if (!savedLyrics.isNullOrBlank()) {
            lyricsText = savedLyrics
            syncedLyrics = ""
            lyricsError = null
            lyricsLoading = false
            return
        }
        lifecycleScope.launch {
            lyricsLoading = true
            lyricsError = null
            try {
                val result = withContext(Dispatchers.IO) { LyricsClient.find(title, artist) }
                // A slow lookup for the previous track must never overwrite the current track's lyrics.
                if (requestId != lyricsRequestId ||
                    title != currentSongTitle || artist != (currentSongArtist ?: "")) return@launch
                if (result == null) {
                    lyricsError = "Verified lyrics weren't found for this exact song."
                    lyricsText = null
                    syncedLyrics = ""
                } else {
                    lyricsText = result.plainLyrics.ifBlank { result.syncedLyrics }
                    syncedLyrics = result.syncedLyrics
                }
            } catch (e: Exception) {
                if (requestId == lyricsRequestId &&
                    title == currentSongTitle && artist == (currentSongArtist ?: "")) {
                    lyricsError = "Couldn't retrieve lyrics. Check your internet connection and try again."
                    lyricsText = null
                    syncedLyrics = ""
                }
            } finally {
                if (requestId == lyricsRequestId) lyricsLoading = false
            }
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
    songs: List<LocalSong>, builtInSongs: List<LocalSong>, onlineSongs: List<OnlineSong>, featuredSongAvailable: Boolean,
    currentSongTitle: String?, currentSongArtist: String?, isPlaying: Boolean, playbackError: String?,
    searchQuery: String, isSearching: Boolean, searchError: String?,
    darkMode: Boolean, selectedTab: AppTab, highQualityAudio: Boolean, showLyrics: Boolean,
    lyricsText: String?, syncedLyrics: String, lyricsLoading: Boolean, lyricsError: String?,
    playbackPosition: Long, playbackDuration: Long,
    onSearchQueryChange: (String) -> Unit, onSearch: () -> Unit, onRefresh: () -> Unit,
    onAddMp3: () -> Unit,
    onThemeToggle: () -> Unit, onQualityToggle: () -> Unit, onDolbyAtmosClick: () -> Unit, onTabChange: (AppTab) -> Unit,
    onSongClick: (LocalSong) -> Unit, onFeaturedSongClick: () -> Unit,
    onTrendingSongClick: (String) -> Unit, onOnlineSongClick: (OnlineSong) -> Unit,
    onPlayPause: () -> Unit, onLyricsClick: () -> Unit, onLyricsBack: () -> Unit,
    onSaveLyrics: (String) -> Unit, onSeek: (Long) -> Unit, onNext: () -> Unit, onPrevious: () -> Unit
) {
    val bg = if (darkMode) Color(0xFF090711) else Color(0xFFF5F2FA)
    val panel = if (darkMode) Color(0xFF211D30) else Color(0xFFFFFFFF)
    val primaryText = if (darkMode) Color.White else Color(0xFF21172E)
    val secondaryText = if (darkMode) Color(0xFFD0C8DD) else Color(0xFF665D70)
    val accent = Color(0xFF9567EF)
    val fmt: (Long) -> String = { ms -> "%d:%02d".format(ms / 60000, (ms / 1000) % 60) }
    var showLyricsEditor by remember { mutableStateOf(false) }
    var lyricsDraft by remember { mutableStateOf("") }
    val trendingTamilSongs = listOf(
        "Roddy Theme" to "Anirudh Ravichander",
        "Veezha Veera" to "Sai Abhyankkar, Tippu & Arun Srinivasan",
        "FA FA FA" to "Sai Abhyankkar & Thamizh Aadhavan",
        "The Wild Theme" to "Sai Abhyankkar & Dhanush",
        "Alaakaa Loova" to "Sai Abhyankkar & Rokesh",
        "One Name" to "Anirudh Ravichander & Super Subu",
        "Radhimaa" to "Sai Abhyankkar, Nargis Teji & Sai Smriti",
        "Pavazha Malli" to "Sai Abhyankkar & Shruti Haasan"
    )

    MaterialTheme(colorScheme = if (darkMode) darkColorScheme(primary = accent, surface = panel, background = bg)
        else lightColorScheme(primary = accent, surface = panel, background = bg)) {
        Surface(Modifier.fillMaxSize(), color = bg) {
            Column(Modifier.fillMaxSize().padding(horizontal = 18.dp, vertical = 14.dp)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            androidx.compose.ui.graphics.Brush.horizontalGradient(
                                listOf(Color(0xFF100B1C), Color(0xFF24143B), Color(0xFF0B0912))
                            ),
                            RoundedCornerShape(20.dp)
                        )
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_launcher_logo),
                        contentDescription = "Sound of Souls logo",
                        modifier = Modifier.size(58.dp)
                    )
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = onThemeToggle) {
                        Icon(if (darkMode) Icons.Default.LightMode else Icons.Default.DarkMode,
                            contentDescription = "Switch theme", tint = primaryText)
                    }
                    IconButton(onClick = onQualityToggle) {
                        Icon(Icons.Default.HighQuality, contentDescription = if (highQualityAudio) "High quality audio enabled" else "Enable high quality audio", tint = if (highQualityAudio) Color(0xFFD8FF00) else primaryText)
                    }
                    IconButton(onClick = onDolbyAtmosClick) {
                        Icon(Icons.Default.GraphicEq, contentDescription = "Dolby Atmos settings", tint = accent)
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
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = {
                            lyricsDraft = lyricsText.orEmpty()
                            showLyricsEditor = true
                        }) { Text("Add / edit my lyrics") }
                    }
                    if (showLyricsEditor) {
                        AlertDialog(
                            onDismissRequest = { showLyricsEditor = false },
                            title = { Text("Your song lyrics") },
                            text = {
                                OutlinedTextField(
                                    value = lyricsDraft,
                                    onValueChange = { lyricsDraft = it },
                                    modifier = Modifier.fillMaxWidth().heightIn(min = 180.dp),
                                    placeholder = { Text("Paste or type the correct lyrics here") },
                                    minLines = 6
                                )
                            },
                            confirmButton = {
                                TextButton(onClick = {
                                    onSaveLyrics(lyricsDraft)
                                    showLyricsEditor = false
                                }) { Text("Save lyrics") }
                            },
                            dismissButton = {
                                TextButton(onClick = { showLyricsEditor = false }) { Text("Cancel") }
                            }
                        )
                    }
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
                        Column(
                            Modifier.fillMaxWidth()
                                .background(
                                    androidx.compose.ui.graphics.Brush.linearGradient(
                                        listOf(Color(0xFF39245F), Color(0xFF171323))
                                    ),
                                    RoundedCornerShape(18.dp)
                                )
                                .clickable(enabled = featuredSongAvailable, onClick = onFeaturedSongClick)
                                .padding(16.dp)
                        ) {
                            Text("FEATURED SONG", color = Color(0xFFD6BFFF), fontSize = 10.sp,
                                fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
                            Spacer(Modifier.height(7.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.MusicNote, null, tint = Color.White,
                                    modifier = Modifier.size(38.dp))
                                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                                    Text("Radhimaa", color = Color.White, fontSize = 21.sp,
                                        fontWeight = FontWeight.ExtraBold)
                                    Text("Sai Abhyankkar", color = Color(0xFFE0D7EF), fontSize = 13.sp)
                                }
                            }
                            Text(
                                if (featuredSongAvailable) "Tap to play this built-in song"
                                else "Add radhimaa.mp3 to app/src/main/res/raw to enable playback",
                                color = Color(0xFFD6BFFF), fontSize = 11.sp,
                                modifier = Modifier.padding(top = 8.dp)
                            )
                        }
                        Spacer(Modifier.height(14.dp))
                        val soundOfSoulsTracks = builtInSongs.filter { it.artist.contains("Sound of Souls", ignoreCase = true) }
                        val otherArtistTracks = builtInSongs.filterNot { it.artist.contains("Sound of Souls", ignoreCase = true) }
                        if (soundOfSoulsTracks.isNotEmpty()) {
                            Text("Sound of Souls", color = primaryText, fontSize = 18.sp, fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(bottom = 6.dp))
                            soundOfSoulsTracks.forEach { track ->
                                SongRow(track.title, track.artist, "Built-in song • Offline playback", panel, primaryText, secondaryText, accent) {
                                    onSongClick(track)
                                }
                            }
                            Spacer(Modifier.height(12.dp))
                        }
                        if (otherArtistTracks.isNotEmpty()) {
                            Text("Other Artists", color = primaryText, fontSize = 18.sp, fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(bottom = 6.dp))
                            otherArtistTracks.forEach { track ->
                                SongRow(track.title, track.artist, "Other artists • Offline playback", panel, primaryText, secondaryText, accent) {
                                    onSongClick(track)
                                }
                            }
                            Spacer(Modifier.height(12.dp))
                        }
                        Text("MP3 music discovery", color = primaryText, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                        Text("Play audio files directly inside Sound of Souls. Only reusable, open-licensed tracks are shown here.",
                            color = secondaryText, fontSize = 12.sp, modifier = Modifier.padding(bottom = 8.dp))
                        Text("Find your next favourite", color = primaryText, fontSize = 23.sp, fontWeight = FontWeight.Bold)
                        Text(if (highQualityAudio) "High quality preference: lossless files first when available" else "Search open-licensed music you can stream", color = secondaryText, fontSize = 13.sp,
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
                        if (searchError != null) {
                            Text(searchError, color = secondaryText, fontSize = 13.sp,
                                modifier = Modifier.padding(vertical = 8.dp))
                        }
                        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            item {
                                Column(Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 2.dp)) {
                                    Text("Trending Tamil songs", color = primaryText, fontSize = 18.sp,
                                        fontWeight = FontWeight.Bold)
                                    Text("Updated 23 Nov 2026 • Tap a song to open its official music search",
                                        color = secondaryText, fontSize = 11.sp)
                                }
                            }
                            items(trendingTamilSongs, key = { it.first }) { (title, artist) ->
                                SongRow(title, artist, "Trending • Open in YouTube Music", panel, primaryText, secondaryText, accent) {
                                    onTrendingSongClick("$title $artist")
                                }
                            }
                            item {
                                Text("Open-licensed tracks", color = primaryText, fontSize = 18.sp,
                                    fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 12.dp))
                            }
                            items(onlineSongs, key = { it.identifier + it.streamUrl }) { song ->
                                SongRow(song.title, song.artist, "Open-licensed • Internet Archive", panel, primaryText, secondaryText, accent) {
                                    onOnlineSongClick(song)
                                }
                            }
                        }
                    } else {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("Your music", color = primaryText, fontSize = 23.sp, fontWeight = FontWeight.Bold)
                                Text("${songs.size} audio files on this device", color = secondaryText, fontSize = 13.sp)
                            }
                            Button(onClick = onAddMp3) {
                                Icon(Icons.Default.Add, contentDescription = null)
                                Spacer(Modifier.width(4.dp))
                                Text("Add MP3")
                            }
                        }
                        Text("Choose an audio file from your phone. MP3 files play directly in the app.",
                            color = secondaryText, fontSize = 11.sp, modifier = Modifier.padding(top = 3.dp, bottom = 10.dp))
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
                        if (playbackError != null) {
                            Text(playbackError, color = Color(0xFFE57373), fontSize = 11.sp,
                                modifier = Modifier.padding(top = 4.dp, bottom = 2.dp))
                        }
                        when {
                            lyricsLoading -> Text("Finding lyrics automatically…", color = secondaryText, fontSize = 11.sp)
                            lyricsText != null -> Text("Lyrics ready • tap the lyrics icon", color = accent, fontSize = 11.sp)
                            lyricsError != null -> Text("No verified lyrics found for this track", color = secondaryText, fontSize = 11.sp)
                        }
                        /* PLAYER_CONTROLS_START */
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
