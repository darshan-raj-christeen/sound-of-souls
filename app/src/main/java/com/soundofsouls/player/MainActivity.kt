package com.soundofsouls.player

import android.Manifest
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import java.net.HttpURLConnection
import java.net.URL
import android.content.ComponentName
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
import androidx.compose.foundation.Image
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.PlaybackException
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class LocalSong(val id: Long, val title: String, val artist: String, val uri: Uri)
private enum class AppTab { ONLINE, LIBRARY }

class MainActivity : ComponentActivity() {
    private var player: MediaController? = null
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var pendingMediaItem: MediaItem? = null
    private var songs by mutableStateOf(listOf<LocalSong>())
    private var builtInSongs by mutableStateOf(listOf<LocalSong>())
    private var importedSongs by mutableStateOf(listOf<LocalSong>())
    private var favouriteUris by mutableStateOf<Set<String>>(emptySet())
    private var playlistNames by mutableStateOf<List<String>>(emptyList())
    private var playlistSongs by mutableStateOf<Map<String, Set<String>>>(emptyMap())
    private var onlineSongs by mutableStateOf(listOf<OnlineSong>())
    private var upNext by mutableStateOf(listOf<Pair<String, String>>())
    private var currentSongTitle by mutableStateOf<String?>(null)
    private var currentSongArtist by mutableStateOf<String?>(null)
    private var currentSongIsOnline by mutableStateOf(false)
    private var currentArtwork by mutableStateOf<Bitmap?>(null)
    private var artworkJob: Job? = null
    private var isPlaying by mutableStateOf(false)
    private var shuffleEnabled by mutableStateOf(false)
    private var repeatMode by mutableIntStateOf(Player.REPEAT_MODE_OFF)
    private var playbackSpeed by mutableFloatStateOf(1.0f)
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
        val settingsPrefs = getSharedPreferences("sound_of_souls_settings", MODE_PRIVATE)
        highQualityAudio = settingsPrefs.getBoolean("high_quality_audio", false)
        shuffleEnabled = settingsPrefs.getBoolean("shuffle_enabled", false)
        repeatMode = settingsPrefs.getInt("repeat_mode", Player.REPEAT_MODE_OFF)
        val libraryPrefs = getSharedPreferences("sound_of_souls_library", MODE_PRIVATE)
        favouriteUris = libraryPrefs.getStringSet("favourite_song_uris", emptySet())?.toSet() ?: emptySet()
        playlistNames = libraryPrefs.getStringSet("playlist_names", emptySet()).orEmpty().sorted()
        playlistSongs = playlistNames.associateWith { name ->
            libraryPrefs.getStringSet("playlist_items_$name", emptySet())?.toSet() ?: emptySet()
        }
        connectToPlaybackService()
        startProgressUpdates()
        restoreImportedSongs()
        builtInSongs = BuiltInSongCatalog.resolve(this)
        songs = builtInSongs + importedSongs
        requestAudioPermission()
        setContent {
            SoundOfSoulsScreen(
                songs = songs, builtInSongs = builtInSongs, onlineSongs = onlineSongs,
                favouriteUris = favouriteUris, playlistNames = playlistNames,
                playlistSongs = playlistSongs,
                featuredSongAvailable = builtInSongs.any { it.title.equals("Radhimaa", ignoreCase = true) },
                currentSongTitle = currentSongTitle, currentSongArtist = currentSongArtist,
                currentArtwork = currentArtwork, upNext = upNext,
                isPlaying = isPlaying, shuffleEnabled = shuffleEnabled, repeatMode = repeatMode,
                playbackSpeed = playbackSpeed,
                playbackError = playbackError, searchQuery = searchQuery,
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
                onToggleFavourite = { song ->
                    val uri = song.uri.toString()
                    val updated = favouriteUris.toMutableSet()
                    if (!updated.add(uri)) updated.remove(uri)
                    favouriteUris = updated
                    getSharedPreferences("sound_of_souls_library", MODE_PRIVATE).edit()
                        .putStringSet("favourite_song_uris", updated).apply()
                },
                onCreatePlaylist = { name ->
                    val cleanName = name.trim().take(40)
                    if (cleanName.isNotBlank() && playlistNames.none { it.equals(cleanName, true) }) {
                        playlistNames = (playlistNames + cleanName).sorted()
                        playlistSongs = playlistSongs + (cleanName to emptySet())
                        getSharedPreferences("sound_of_souls_library", MODE_PRIVATE).edit()
                            .putStringSet("playlist_names", playlistNames.toSet())
                            .putStringSet("playlist_items_$cleanName", emptySet()).apply()
                    }
                },
                onAddToPlaylist = { song, playlist ->
                    val updatedItems = (playlistSongs[playlist] ?: emptySet()) + song.uri.toString()
                    playlistSongs = playlistSongs + (playlist to updatedItems)
                    getSharedPreferences("sound_of_souls_library", MODE_PRIVATE).edit()
                        .putStringSet("playlist_items_$playlist", updatedItems).apply()
                },
                onFeaturedSongClick = { builtInSongs.firstOrNull { it.title.equals("Radhimaa", ignoreCase = true) }?.let { playLocalSong(it) } },
                onTrendingSongClick = { openTamilMusicCategory(it) },
                onOnlineSongClick = { playOnlineSong(it) },
                onPlayPause = { togglePlayback() },
                onLyricsClick = { showLyrics = true; if (lyricsText == null && !lyricsLoading) findLyrics() },
                onLyricsBack = { showLyrics = false },
                onSaveLyrics = { saveManualLyrics(it) },
                onSeek = { player?.seekTo(it) },
                onNext = { skipSong(true) }, onPrevious = { skipSong(false) },
                onShuffleToggle = {
                    shuffleEnabled = !shuffleEnabled
                    player?.shuffleModeEnabled = shuffleEnabled
                    settingsPrefs.edit().putBoolean("shuffle_enabled", shuffleEnabled).apply()
                },
                onRepeatCycle = {
                    repeatMode = when (repeatMode) {
                        Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                        Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                        else -> Player.REPEAT_MODE_OFF
                    }
                    player?.repeatMode = repeatMode
                    settingsPrefs.edit().putInt("repeat_mode", repeatMode).apply()
                },
                onPlaybackSpeedCycle = {
                    playbackSpeed = when (playbackSpeed) {
                        1.0f -> 1.25f
                        1.25f -> 1.5f
                        1.5f -> 2.0f
                        else -> 1.0f
                    }
                    player?.setPlaybackSpeed(playbackSpeed)
                }
            )
        }
    }

    private fun connectToPlaybackService() {
        val token = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        val future = MediaController.Builder(this, token).buildAsync()
        controllerFuture = future
        future.addListener({
            try {
                val connected = future.get()
                player = connected
                connected.shuffleModeEnabled = shuffleEnabled
                connected.repeatMode = repeatMode
                connected.setPlaybackSpeed(playbackSpeed)
                connected.addListener(object : Player.Listener {
                    override fun onIsPlayingChanged(playing: Boolean) { isPlaying = playing }
                    override fun onPlaybackStateChanged(state: Int) {
                        playbackDuration = connected.duration.takeIf { it > 0 } ?: 0L
                    }
                    override fun onPlayerError(error: PlaybackException) {
                        isPlaying = false
                        playbackError = "This stream could not be played. The source may block streaming or the file may be unavailable."
                    }
                    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                        playbackPosition = 0L
                        playbackDuration = connected.duration.takeIf { it > 0 } ?: 0L
                    }
                })
                pendingMediaItem?.let { item ->
                    connected.setMediaItem(item)
                    connected.prepare()
                    connected.play()
                    pendingMediaItem = null
                }
            } catch (_: Exception) {
                playbackError = "The playback service could not be started. Please reopen the app."
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun startMediaItem(item: MediaItem) {
        val activePlayer = player
        if (activePlayer == null) {
            pendingMediaItem = item
            return
        }
        activePlayer.setMediaItem(item)
        activePlayer.prepare()
        activePlayer.play()
    }

    private fun startProgressUpdates() {
        progressJob = lifecycleScope.launch {
            while (true) {
                player?.let { activePlayer ->
                    playbackPosition = activePlayer.currentPosition.coerceAtLeast(0L)
                    playbackDuration = activePlayer.duration.takeIf { it > 0 } ?: 0L
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
            putExtra(AudioEffect.EXTRA_AUDIO_SESSION, 0)
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
        val index = songs.indexOfFirst { it.uri == song.uri }
        upNext = if (index >= 0) songs.drop(index + 1).map { it.title to it.artist } else emptyList()
        currentSongTitle = song.title; currentSongArtist = song.artist; currentSongIsOnline = false
        loadLocalArtwork(song.uri)
        playbackError = null
        lyricsRequestId++
        lyricsLoading = false
        lyricsText = null; syncedLyrics = ""; lyricsError = null
        startMediaItem(MediaItem.Builder().setUri(song.uri).setMediaMetadata(MediaMetadata.Builder().setTitle(song.title).setArtist(song.artist).build()).build())
        // Automatically look up lyrics as soon as a new local track starts.
        findLyrics()
    }

    private fun playOnlineSong(song: OnlineSong) {
        val index = onlineSongs.indexOfFirst { it.identifier == song.identifier }
        upNext = if (index >= 0) onlineSongs.drop(index + 1).map { it.title to it.artist } else emptyList()
        currentSongTitle = song.title; currentSongArtist = song.artist; currentSongIsOnline = true
        loadOnlineArtwork(song.identifier)
        playbackError = null
        lyricsRequestId++
        lyricsLoading = false
        lyricsText = null; syncedLyrics = ""; lyricsError = null
        startMediaItem(MediaItem.Builder().setUri(song.streamUrl).setMediaMetadata(MediaMetadata.Builder().setTitle(song.title).setArtist(song.artist).setArtworkUri(Uri.parse("https://archive.org/services/img/${Uri.encode(song.identifier)}")).build()).build())
        // Automatically look up lyrics as soon as a new online track starts.
        findLyrics()
    }

    private fun loadLocalArtwork(uri: Uri) {
        artworkJob?.cancel()
        currentArtwork = null
        artworkJob = lifecycleScope.launch(Dispatchers.IO) {
            val bitmap = try {
                val retriever = MediaMetadataRetriever()
                try {
                    retriever.setDataSource(this@MainActivity, uri)
                    retriever.embeddedPicture?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
                } finally { retriever.release() }
            } catch (_: Exception) { null }
            withContext(Dispatchers.Main) { currentArtwork = bitmap }
        }
    }

    private fun loadOnlineArtwork(identifier: String) {
        artworkJob?.cancel()
        currentArtwork = null
        artworkJob = lifecycleScope.launch(Dispatchers.IO) {
            val bitmap = try {
                val connection = URL("https://archive.org/services/img/${Uri.encode(identifier)}").openConnection() as HttpURLConnection
                connection.connectTimeout = 7000
                connection.readTimeout = 7000
                try {
                    if (connection.responseCode in 200..299) connection.inputStream.use { BitmapFactory.decodeStream(it) }
                    else null
                } finally { connection.disconnect() }
            } catch (_: Exception) { null }
            withContext(Dispatchers.Main) { currentArtwork = bitmap }
        }
    }

    private fun togglePlayback() {
        val activePlayer = player ?: return
        if (activePlayer.mediaItemCount == 0) {
            if (songs.isNotEmpty()) playLocalSong(songs.first())
        } else if (activePlayer.isPlaying) activePlayer.pause() else activePlayer.play()
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
        controllerFuture?.let { MediaController.releaseFuture(it) }
        player = null
        super.onDestroy()
    }
}

@Composable
private fun SoundOfSoulsScreen(
    songs: List<LocalSong>, builtInSongs: List<LocalSong>, onlineSongs: List<OnlineSong>,
    favouriteUris: Set<String>, playlistNames: List<String>, playlistSongs: Map<String, Set<String>>,
    featuredSongAvailable: Boolean, currentSongTitle: String?, currentSongArtist: String?, currentArtwork: Bitmap?, upNext: List<Pair<String, String>>, isPlaying: Boolean,
    shuffleEnabled: Boolean, repeatMode: Int, playbackSpeed: Float, playbackError: String?,
    searchQuery: String, isSearching: Boolean, searchError: String?,
    darkMode: Boolean, selectedTab: AppTab, highQualityAudio: Boolean, showLyrics: Boolean,
    lyricsText: String?, syncedLyrics: String, lyricsLoading: Boolean, lyricsError: String?,
    playbackPosition: Long, playbackDuration: Long,
    onSearchQueryChange: (String) -> Unit, onSearch: () -> Unit, onRefresh: () -> Unit,
    onAddMp3: () -> Unit,
    onThemeToggle: () -> Unit, onQualityToggle: () -> Unit, onDolbyAtmosClick: () -> Unit, onTabChange: (AppTab) -> Unit,
    onSongClick: (LocalSong) -> Unit, onToggleFavourite: (LocalSong) -> Unit,
    onCreatePlaylist: (String) -> Unit, onAddToPlaylist: (LocalSong, String) -> Unit,
    onFeaturedSongClick: () -> Unit,
    onTrendingSongClick: (String) -> Unit, onOnlineSongClick: (OnlineSong) -> Unit,
    onPlayPause: () -> Unit, onLyricsClick: () -> Unit, onLyricsBack: () -> Unit,
    onSaveLyrics: (String) -> Unit, onSeek: (Long) -> Unit, onNext: () -> Unit, onPrevious: () -> Unit,
    onShuffleToggle: () -> Unit, onRepeatCycle: () -> Unit, onPlaybackSpeedCycle: () -> Unit
) {
    val bg = if (darkMode) Color(0xFF090711) else Color(0xFFF5F2FA)
    val panel = if (darkMode) Color(0xFF211D30) else Color(0xFFFFFFFF)
    val primaryText = if (darkMode) Color.White else Color(0xFF21172E)
    val secondaryText = if (darkMode) Color(0xFFD0C8DD) else Color(0xFF665D70)
    val accent = Color(0xFF9567EF)
    val fmt: (Long) -> String = { ms -> "%d:%02d".format(ms / 60000, (ms / 1000) % 60) }
    var showLyricsEditor by remember { mutableStateOf(false) }
    var lyricsDraft by remember { mutableStateOf("") }
    var showFavouritesOnly by remember { mutableStateOf(false) }
    var selectedPlaylist by remember { mutableStateOf<String?>(null) }
    var showCreatePlaylist by remember { mutableStateOf(false) }
    var playlistNameDraft by remember { mutableStateOf("") }
    var songToAddToPlaylist by remember { mutableStateOf<LocalSong?>(null) }
    var showNowPlaying by remember { mutableStateOf(false) }
    var selectedCoverStyle by remember { mutableIntStateOf(0) }
    // Curated from Tamil daily chart listings checked on 28 Nov 2026.
    // These are official-source search shortcuts, not direct in-app streams.
    val trendingTamilSongs = listOf(
        "Roddy Theme" to "Anirudh Ravichander • Jailer 2",
        "Jailer 2 Theme" to "Anirudh Ravichander, M.S Krsna & Super Subu",
        "Veezha Veera" to "Sai Abhyankkar, Tippu & Arun Srinivasan • OM Chapter 1",
        "Dileep Theme (Instrumental)" to "Anirudh Ravichander • Jailer 2",
        "FA FA FA" to "Sai Abhyankkar & Thamizh Aadhavan • OM Chapter 1",
        "One Sun One Moon" to "Anirudh Ravichander, Super Subu, M.S Krsna & HEISENBERG",
        "One Name" to "Anirudh Ravichander & Super Subu • Jailer 2",
        "Alaakaa Loova" to "Sai Abhyankkar & Rokesh • OM Chapter 1",
        "Oorum Blood" to "Sai Abhyankkar, Paal Dabba, bebhumika & Deepthi Suresh",
        "Ooroda Oththa Don" to "Sai Abhyankkar, Vangal Pulla Vicky, Thamizh Aadhavan & KK Nagar Gana Jeeva",
        "Radhimaa" to "Sai Abhyankkar, Nargis Teji, Asma Teji & Vivek",
        "Pavazha Malli" to "Sai Abhyankkar, Shruti Haasan & Vivek"
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
                } else if (showNowPlaying) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 12.dp)) {
                        IconButton(onClick = { showNowPlaying = false }) {
                            Icon(Icons.Default.ArrowBack, "Back", tint = primaryText)
                        }
                        Text("NOW PLAYING", color = primaryText, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
                        Spacer(Modifier.weight(1f))
                        IconButton(onClick = onLyricsClick) {
                            Icon(Icons.Default.Lyrics, "Open lyrics", tint = accent)
                        }
                    }
                    Text("COVER STYLE", color = secondaryText, fontSize = 10.sp,
                        fontWeight = FontWeight.Bold, letterSpacing = 1.4.sp,
                        modifier = Modifier.padding(start = 2.dp, bottom = 7.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier.fillMaxWidth()) {
                        listOf("iPod", "Drip", "Spotify", "Mono").forEachIndexed { index, label ->
                            FilterChip(selected = selectedCoverStyle == index,
                                onClick = { selectedCoverStyle = index },
                                label = { Text(label, fontSize = 10.sp) })
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    Box(
                        Modifier.fillMaxWidth().aspectRatio(0.82f)
                            .background(
                                androidx.compose.ui.graphics.Brush.linearGradient(
                                    when (selectedCoverStyle) {
                                        1 -> listOf(Color(0xFF777777), Color(0xFF292929), Color(0xFF111111))
                                        2 -> listOf(Color(0xFF071B17), Color(0xFF142B25), Color(0xFF050807))
                                        3 -> listOf(Color(0xFFB8B8B8), Color(0xFF555555), Color(0xFF171717))
                                        else -> listOf(Color(0xFF58358C), Color(0xFF21152F), Color(0xFF100D19))
                                    }
                                ), RoundedCornerShape(if (selectedCoverStyle == 1) 30.dp else 36.dp)
                            ).padding(18.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            Modifier.fillMaxSize()
                                .background(
                                    if (selectedCoverStyle == 1) Color(0xFF343434)
                                    else if (selectedCoverStyle == 3) Color(0xFF252525)
                                    else Color(0xFF17151D),
                                    RoundedCornerShape(if (selectedCoverStyle == 1) 26.dp else 30.dp)
                                ).padding(18.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.SpaceEvenly
                        ) {
                            Text(
                                when (selectedCoverStyle) {
                                    0 -> "SOUND OF SOULS  •  NOW PLAYING"
                                    1 -> "SOUND OF SOULS"
                                    2 -> "YOUR MUSIC"
                                    else -> "S O U N D  O F  S O U L S"
                                },
                                color = Color.White.copy(alpha = 0.78f), fontSize = 9.sp,
                                fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp
                            )
                            Box(
                                Modifier.fillMaxWidth().weight(1f)
                                    .background(Color.Black, RoundedCornerShape(8.dp)),
                                contentAlignment = Alignment.Center
                            ) {
                                if (currentArtwork != null) {
                                    Image(bitmap = currentArtwork.asImageBitmap(),
                                        contentDescription = "Album artwork",
                                        modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                                } else {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Icon(Icons.Default.Album, null, tint = Color(0xFFE6D8FF),
                                            modifier = Modifier.size(82.dp))
                                        Text("SOUND OF SOULS", color = Color(0xFFD6BFFF),
                                            fontSize = 9.sp, fontWeight = FontWeight.Bold,
                                            letterSpacing = 2.sp)
                                    }
                                }
                            }
                            Text(currentSongTitle ?: "No song selected", color = Color.White,
                                fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                            Text(currentSongArtist ?: "", color = Color.White.copy(alpha = 0.68f),
                                fontSize = 10.sp, maxLines = 1)
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly,
                                verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.SkipPrevious, null, tint = Color.White,
                                    modifier = Modifier.size(22.dp))
                                Icon(if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                    null, tint = Color.White, modifier = Modifier.size(30.dp))
                                Icon(Icons.Default.SkipNext, null, tint = Color.White,
                                    modifier = Modifier.size(22.dp))
                            }
                            if (selectedCoverStyle == 0 || selectedCoverStyle == 2) {
                                Text("●  ━━━━━━━━━━━━━━━━━  ◉", color = Color.White.copy(alpha = 0.7f),
                                    fontSize = 9.sp)
                            }
                        }
                    }
                    Spacer(Modifier.height(28.dp))
                    Text(currentSongTitle ?: "No song selected", color = primaryText,
                        fontSize = 24.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                    Text(currentSongArtist ?: "", color = secondaryText, fontSize = 15.sp,
                        modifier = Modifier.padding(top = 5.dp), maxLines = 1)
                    if (playbackError != null) {
                        Text(playbackError, color = Color(0xFFE57373), fontSize = 12.sp,
                            modifier = Modifier.padding(top = 8.dp))
                    }
                    Spacer(Modifier.height(18.dp))
                    Slider(value = if (playbackDuration > 0) playbackPosition.coerceIn(0, playbackDuration).toFloat() else 0f,
                        onValueChange = { onSeek(it.toLong()) },
                        valueRange = 0f..(playbackDuration.coerceAtLeast(1L).toFloat()))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(fmt(playbackPosition), color = secondaryText, fontSize = 11.sp)
                        Text(fmt(playbackDuration), color = secondaryText, fontSize = 11.sp)
                    }
                    Row(Modifier.fillMaxWidth().padding(vertical = 16.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = onPrevious, modifier = Modifier.size(54.dp)) {
                            Icon(Icons.Default.SkipPrevious, "Previous", tint = primaryText, modifier = Modifier.size(34.dp))
                        }
                        IconButton(onClick = onPlayPause, modifier = Modifier.size(76.dp)) {
                            Icon(if (isPlaying) Icons.Default.PauseCircleFilled else Icons.Default.PlayCircleFilled,
                                if (isPlaying) "Pause" else "Play", tint = accent, modifier = Modifier.size(70.dp))
                        }
                        IconButton(onClick = onNext, modifier = Modifier.size(54.dp)) {
                            Icon(Icons.Default.SkipNext, "Next", tint = primaryText, modifier = Modifier.size(34.dp))
                        }
                    }
                    Row(Modifier.fillMaxWidth().padding(bottom = 8.dp),
                        horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = onShuffleToggle) {
                            Icon(Icons.Default.Shuffle, "Shuffle",
                                tint = if (shuffleEnabled) accent else secondaryText)
                        }
                        Text(if (shuffleEnabled) "Shuffle on" else "Shuffle off",
                            color = if (shuffleEnabled) accent else secondaryText, fontSize = 11.sp)
                        Spacer(Modifier.width(18.dp))
                        IconButton(onClick = onRepeatCycle) {
                            Icon(if (repeatMode == Player.REPEAT_MODE_ONE) Icons.Default.RepeatOne else Icons.Default.Repeat,
                                "Repeat", tint = if (repeatMode != Player.REPEAT_MODE_OFF) accent else secondaryText)
                        }
                        Text(when (repeatMode) {
                            Player.REPEAT_MODE_ONE -> "Repeat one"
                            Player.REPEAT_MODE_ALL -> "Repeat all"
                            else -> "Repeat off"
                        }, color = if (repeatMode != Player.REPEAT_MODE_OFF) accent else secondaryText, fontSize = 11.sp)
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                        FilterChip(selected = playbackSpeed != 1.0f, onClick = onPlaybackSpeedCycle,
                            label = { Text("Playback speed  ${playbackSpeed}x", fontSize = 11.sp) },
                            leadingIcon = { Icon(Icons.Default.Speed, null, modifier = Modifier.size(16.dp)) })
                    }
                    if (upNext.isNotEmpty()) {
                        Text("UP NEXT", color = secondaryText, fontSize = 11.sp,
                            fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp,
                            modifier = Modifier.padding(top = 8.dp, bottom = 6.dp))
                        LazyColumn(Modifier.weight(1f, fill = false).heightIn(max = 150.dp),
                            verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            items(upNext.take(8)) { (title, artist) ->
                                Row(Modifier.fillMaxWidth().background(panel, RoundedCornerShape(10.dp))
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.QueueMusic, null, tint = accent, modifier = Modifier.size(20.dp))
                                    Column(Modifier.weight(1f).padding(start = 10.dp)) {
                                        Text(title, color = primaryText, fontSize = 13.sp, maxLines = 1)
                                        Text(artist, color = secondaryText, fontSize = 11.sp, maxLines = 1)
                                    }
                                }
                            }
                        }
                    } else {
                        Text("No more songs in this queue", color = secondaryText, fontSize = 11.sp,
                            modifier = Modifier.padding(top = 8.dp))
                    }
                    Spacer(Modifier.weight(1f))
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
                                SongRow(track.title, track.artist, "Built-in song • Offline playback", panel, primaryText, secondaryText, accent,
                                    onClick = { onSongClick(track) })
                            }
                            Spacer(Modifier.height(12.dp))
                        }
                        if (otherArtistTracks.isNotEmpty()) {
                            Text("Other Artists", color = primaryText, fontSize = 18.sp, fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(bottom = 6.dp))
                            otherArtistTracks.forEach { track ->
                                SongRow(track.title, track.artist, "Other artists • Offline playback", panel, primaryText, secondaryText, accent,
                                    onClick = { onSongClick(track) })
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
                                    Text("Chart snapshot: 28 Nov 2026 • Tap to find the official audio",
                                        color = secondaryText, fontSize = 11.sp)
                                }
                            }
                            items(trendingTamilSongs, key = { it.first }) { (title, artist) ->
                                SongRow(title, artist, "Trending • Open in YouTube Music", panel, primaryText, secondaryText, accent,
                                    onClick = { onTrendingSongClick("$title $artist") })
                            }
                            item {
                                Text("Open-licensed tracks", color = primaryText, fontSize = 18.sp,
                                    fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 12.dp))
                            }
                            items(onlineSongs, key = { it.identifier + it.streamUrl }) { song ->
                                SongRow(song.title, song.artist, "Open-licensed • Internet Archive", panel, primaryText, secondaryText, accent,
                                    onClick = { onOnlineSongClick(song) })
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
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(top = 10.dp, bottom = 8.dp)) {
                            FilterChip(selected = !showFavouritesOnly && selectedPlaylist == null,
                                onClick = { showFavouritesOnly = false; selectedPlaylist = null }, label = { Text("All") })
                            FilterChip(selected = showFavouritesOnly, onClick = { showFavouritesOnly = true; selectedPlaylist = null },
                                label = { Text("Favourites") }, leadingIcon = { Icon(Icons.Default.Favorite, null) })
                            IconButton(onClick = { playlistNameDraft = ""; showCreatePlaylist = true }) {
                                Icon(Icons.Default.Add, contentDescription = "Create playlist", tint = accent)
                            }
                        }
                        if (playlistNames.isNotEmpty()) {
                            LazyColumn(Modifier.heightIn(max = 52.dp)) {
                                items(playlistNames) { name ->
                                    FilterChip(selected = selectedPlaylist == name && !showFavouritesOnly,
                                        onClick = { selectedPlaylist = name; showFavouritesOnly = false },
                                        label = { Text("$name (${playlistSongs[name]?.size ?: 0})") })
                                }
                            }
                        }
                        if (showCreatePlaylist) {
                            AlertDialog(onDismissRequest = { showCreatePlaylist = false },
                                title = { Text("Create playlist") },
                                text = { OutlinedTextField(value = playlistNameDraft, onValueChange = { playlistNameDraft = it },
                                    singleLine = true, label = { Text("Playlist name") }) },
                                confirmButton = { TextButton(onClick = { onCreatePlaylist(playlistNameDraft); showCreatePlaylist = false }) { Text("Create") } },
                                dismissButton = { TextButton(onClick = { showCreatePlaylist = false }) { Text("Cancel") } })
                        }
                        if (songToAddToPlaylist != null) {
                            AlertDialog(onDismissRequest = { songToAddToPlaylist = null },
                                title = { Text("Add to playlist") },
                                text = {
                                    if (playlistNames.isEmpty()) Text("Create a playlist first using the + button.")
                                    else Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        playlistNames.forEach { name ->
                                            TextButton(onClick = { onAddToPlaylist(songToAddToPlaylist!!, name); songToAddToPlaylist = null }) {
                                                Text(name)
                                            }
                                        }
                                    }
                                },
                                confirmButton = { TextButton(onClick = { songToAddToPlaylist = null }) { Text("Close") } })
                        }
                        Text("Choose an audio file from your phone. MP3 files play directly in the app.",
                            color = secondaryText, fontSize = 11.sp, modifier = Modifier.padding(bottom = 10.dp))
                        val visibleSongs = when {
                            showFavouritesOnly -> songs.filter { it.uri.toString() in favouriteUris }
                            selectedPlaylist != null -> songs.filter { it.uri.toString() in (playlistSongs[selectedPlaylist] ?: emptySet()) }
                            else -> songs
                        }
                        if (visibleSongs.isEmpty()) {
                            Text(when {
                                showFavouritesOnly -> "No favourites yet. Tap the heart on a song to save it here."
                                selectedPlaylist != null -> "This playlist is empty. Use the playlist button beside a song to add tracks."
                                else -> "No songs found yet. Add an MP3 or refresh your library."
                            },
                                color = secondaryText, fontSize = 13.sp, modifier = Modifier.padding(vertical = 18.dp))
                        } else {
                            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                items(visibleSongs, key = { it.id }) { song ->
                                    SongRow(song.title, song.artist, "On this device", panel, primaryText, secondaryText, accent,
                                        onClick = { onSongClick(song) },
                                        isFavourite = song.uri.toString() in favouriteUris,
                                        onFavouriteClick = { onToggleFavourite(song) },
                                        onPlaylistClick = { songToAddToPlaylist = song })
                                }
                            }
                        }
                    }
                }
                if (currentSongTitle != null && !showNowPlaying) {
                    Column(Modifier.fillMaxWidth().background(panel, RoundedCornerShape(18.dp))
                        .clickable { showNowPlaying = true }.padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (currentArtwork != null) {
                                Image(bitmap = currentArtwork.asImageBitmap(), contentDescription = "Album artwork",
                                    modifier = Modifier.size(42.dp), contentScale = ContentScale.Crop)
                            } else {
                                Icon(Icons.Default.Album, null, tint = accent, modifier = Modifier.size(36.dp))
                            }
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
    primary: Color, secondary: Color, accent: Color, onClick: () -> Unit,
    isFavourite: Boolean = false, onFavouriteClick: (() -> Unit)? = null,
    onPlaylistClick: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().background(panel, RoundedCornerShape(14.dp)).clickable(onClick = onClick).padding(13.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Default.MusicNote, null, tint = accent, modifier = Modifier.size(30.dp))
        Column(Modifier.weight(1f).padding(start = 11.dp)) {
            Text(title, color = primary, fontWeight = FontWeight.SemiBold, maxLines = 1)
            Text(artist, color = secondary, fontSize = 12.sp, maxLines = 1)
            Text(subtitle, color = accent, fontSize = 10.sp)
        }
        if (onPlaylistClick != null) {
            IconButton(onClick = onPlaylistClick) {
                Icon(Icons.Default.PlaylistAdd, contentDescription = "Add to playlist", tint = primary)
            }
        }
        if (onFavouriteClick != null) {
            IconButton(onClick = onFavouriteClick) {
                Icon(if (isFavourite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                    contentDescription = if (isFavourite) "Remove from favourites" else "Add to favourites",
                    tint = if (isFavourite) Color(0xFFE879A9) else primary)
            }
        }
        Icon(Icons.Default.PlayArrow, "Play $title", tint = primary)
    }
}
