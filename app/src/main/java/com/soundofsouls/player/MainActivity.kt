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
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer

data class LocalSong(val id: Long, val title: String, val artist: String, val uri: Uri)

class MainActivity : ComponentActivity() {
    private lateinit var player: ExoPlayer
    private var songs by mutableStateOf(listOf<LocalSong>())
    private var currentSong by mutableStateOf<LocalSong?>(null)
    private var isPlaying by mutableStateOf(false)

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) loadSongs()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        player = ExoPlayer.Builder(this).build()
        requestAudioPermission()
        setContent {
            MaterialTheme {
                SoundOfSoulsScreen(
                    songs = songs,
                    currentSong = currentSong,
                    isPlaying = isPlaying,
                    onRefresh = { requestAudioPermission() },
                    onSongClick = { playSong(it) },
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

    private fun playSong(song: LocalSong) {
        currentSong = song
        player.setMediaItem(MediaItem.fromUri(song.uri))
        player.prepare()
        player.play()
        isPlaying = true
    }

    private fun togglePlayback() {
        if (currentSong == null && songs.isNotEmpty()) {
            playSong(songs.first())
        } else if (player.isPlaying) {
            player.pause()
            isPlaying = false
        } else {
            player.play()
            isPlaying = true
        }
    }

    override fun onStop() {
        super.onStop()
        // Playback lifecycle will be moved to a media service in the next milestone.
    }

    override fun onDestroy() {
        player.release()
        super.onDestroy()
    }
}

@androidx.compose.runtime.Composable
private fun SoundOfSoulsScreen(
    songs: List<LocalSong>,
    currentSong: LocalSong?,
    isPlaying: Boolean,
    onRefresh: () -> Unit,
    onSongClick: (LocalSong) -> Unit,
    onPlayPause: () -> Unit
) {
    val background = Color(0xFF090711)
    val purple = Color(0xFF9B6DFF)
    Surface(modifier = Modifier.fillMaxSize(), color = background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Brush.verticalGradient(listOf(Color(0xFF21133D), background)))
                .padding(20.dp)
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
            Spacer(Modifier.size(28.dp))
            Text("Your library", color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold)
            Text("${songs.size} songs on this device", color = Color.LightGray, fontSize = 13.sp,
                modifier = Modifier.padding(top = 4.dp, bottom = 18.dp))
            if (songs.isEmpty()) {
                Column(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(Icons.Default.MusicNote, contentDescription = null, tint = purple, modifier = Modifier.size(54.dp))
                    Text("No music found yet", color = Color.White, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(top = 12.dp))
                    Text("Add audio files to your phone, then tap refresh.", color = Color.LightGray,
                        fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
                }
            } else {
                LazyColumn(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(songs, key = { it.id }) { song ->
                        Row(
                            modifier = Modifier.fillMaxWidth()
                                .background(Color(0xFF211D30), RoundedCornerShape(16.dp))
                                .clickable { onSongClick(song) }
                                .padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.MusicNote, contentDescription = null, tint = purple)
                            Column(modifier = Modifier.weight(1f).padding(start = 12.dp)) {
                                Text(song.title, color = Color.White, fontWeight = FontWeight.SemiBold, maxLines = 1)
                                Text(song.artist, color = Color.LightGray, fontSize = 12.sp, maxLines = 1)
                            }
                            if (currentSong?.id == song.id) {
                                Text("PLAYING", color = purple, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
            if (currentSong != null) {
                Row(
                    modifier = Modifier.fillMaxWidth()
                        .background(Color(0xFF30204D), RoundedCornerShape(18.dp))
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(currentSong.title, color = Color.White, fontWeight = FontWeight.Bold, maxLines = 1)
                        Text(currentSong.artist, color = Color(0xFFD0C2F5), fontSize = 12.sp, maxLines = 1)
                    }
                    Button(onClick = onPlayPause) {
                        Icon(if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = if (isPlaying) "Pause" else "Play")
                    }
                }
            }
        }
    }
}
