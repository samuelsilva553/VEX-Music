package com.vexmusic.app

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.vexmusic.app.ui.VexTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class Song(
    val id: Long,
    val title: String,
    val artist: String,
    val duration: Long,
    val uri: String
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            VexTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    MusicHome()
                }
            }
        }
    }
}

private val audioPermission: String
    get() = if (Build.VERSION.SDK_INT >= 33)
        Manifest.permission.READ_MEDIA_AUDIO
    else Manifest.permission.READ_EXTERNAL_STORAGE

private fun hasPermission(context: Context): Boolean =
    ContextCompat.checkSelfPermission(
        context, audioPermission
    ) == PackageManager.PERMISSION_GRANTED

private suspend fun loadSongs(context: Context): List<Song> =
    withContext(Dispatchers.IO) {
        val result = mutableListOf<Song>()
        val collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.DURATION
        )

        try {
            context.contentResolver.query(
                collection,
                projection,
                "${MediaStore.Audio.Media.IS_MUSIC} != 0",
                null,
                "${MediaStore.Audio.Media.TITLE} ASC"
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(
                    MediaStore.Audio.Media._ID
                )
                val titleCol = cursor.getColumnIndexOrThrow(
                    MediaStore.Audio.Media.TITLE
                )
                val artistCol = cursor.getColumnIndexOrThrow(
                    MediaStore.Audio.Media.ARTIST
                )
                val durationCol = cursor.getColumnIndexOrThrow(
                    MediaStore.Audio.Media.DURATION
                )

                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idCol)
                    val title = cursor.getString(titleCol) ?: "Sem título"
                    val artist = cursor.getString(artistCol)
                        ?.takeIf { it != "<unknown>" }
                        ?: "Artista desconhecido"
                    val duration = cursor.getLong(durationCol)
                    val uri = ContentUris.withAppendedId(
                        collection, id
                    ).toString()

                    result.add(Song(id, title, artist, duration, uri))
                }
            }
        } catch (_: Exception) {
            // A tela continua utilizável caso a consulta falhe.
        }

        result
    }

private fun formatTime(milliseconds: Long): String {
    val totalSeconds = (milliseconds.coerceAtLeast(0L) / 1000)
    return "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)
}

@Composable
private fun MusicHome() {
    val context = LocalContext.current
    var permitted by remember { mutableStateOf(hasPermission(context)) }
    var songs by remember { mutableStateOf(emptyList<Song>()) }
    var search by remember { mutableStateOf("") }
    var currentId by remember { mutableStateOf<String?>(null) }
    var isPlaying by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }

    val player = remember {
        ExoPlayer.Builder(context.applicationContext).build()
    }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
            }

            override fun onMediaItemTransition(
                mediaItem: MediaItem?,
                reason: Int
            ) {
                currentId = mediaItem?.mediaId
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.release()
        }
    }

    val permissionLauncher =
        rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { permitted = it }

    LaunchedEffect(permitted) {
        if (permitted) {
            loading = true
            songs = loadSongs(context)
            loading = false
        }
    }

    val filtered = remember(songs, search) {
        songs.filter {
            it.title.contains(search, ignoreCase = true) ||
            it.artist.contains(search, ignoreCase = true)
        }
    }

    val currentSong = songs.find { it.id.toString() == currentId }

    fun playSong(song: Song) {
        val index = songs.indexOfFirst { it.id == song.id }
        if (index >= 0) {
            val playlist = songs.map {
                MediaItem.Builder()
                    .setMediaId(it.id.toString())
                    .setUri(it.uri)
                    .build()
            }
            player.setMediaItems(playlist, index, 0L)
            player.prepare()
            player.play()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp)
            .statusBarsPadding()
    ) {
        Spacer(Modifier.height(22.dp))

        Text(
            "VEX-MUSIC",
            fontSize = 29.sp,
            fontWeight = FontWeight.Black,
            color = MaterialTheme.colorScheme.primary
        )
        Text(
            "Sua música, do seu jeito.",
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(Modifier.height(22.dp))

        if (!permitted) {
            Card(shape = RoundedCornerShape(20.dp)) {
                Column(Modifier.padding(20.dp)) {
                    Text("Acesse suas músicas", fontSize = 20.sp)
                    Spacer(Modifier.height(8.dp))
                    Text("Permita o acesso aos arquivos de áudio para carregar sua biblioteca.")
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = {
                        permissionLauncher.launch(audioPermission)
                    }) {
                        Text("Permitir acesso")
                    }
                }
            }
        } else {
            Text(
                "${songs.size} músicas no dispositivo",
                fontWeight = FontWeight.SemiBold
            )

            Spacer(Modifier.height(12.dp))

            OutlinedTextField(
                value = search,
                onValueChange = { search = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                shape = RoundedCornerShape(16.dp),
                label = { Text("Pesquisar música ou artista") }
            )

            Spacer(Modifier.height(12.dp))

            when {
                loading -> Box(
                    Modifier.weight(1f).fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }

                songs.isEmpty() -> Box(
                    Modifier.weight(1f).fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    Text("Nenhuma música encontrada.\nAdicione arquivos de áudio ao celular.")
                }

                filtered.isEmpty() -> Box(
                    Modifier.weight(1f).fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    Text("Nenhuma música corresponde à pesquisa.")
                }

                else -> LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(5.dp),
                    contentPadding = PaddingValues(bottom = 12.dp)
                ) {
                    items(filtered, key = { it.id }) { song ->
                        val selected = currentId == song.id.toString()

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { playSong(song) }
                                .padding(vertical = 12.dp, horizontal = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = MaterialTheme.colorScheme.secondaryContainer,
                                modifier = Modifier.size(48.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Text("♫", fontSize = 25.sp)
                                }
                            }

                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(start = 12.dp, end = 8.dp)
                            ) {
                                Text(
                                    song.title,
                                    fontWeight = FontWeight.SemiBold,
                                    color = if (selected)
                                        MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1
                                )
                                Text(
                                    "${song.artist} • ${formatTime(song.duration)}",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontSize = 12.sp,
                                    maxLines = 1
                                )
                            }

                            Text(
                                if (selected && isPlaying) "Ⅱ" else "▶",
                                fontSize = 20.sp,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                    }
                }
            }
        }

        if (currentSong != null) {
            HorizontalDivider()
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(top = 12.dp, bottom = 12.dp)
            ) {
                Text(
                    currentSong.title,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1
                )
                Text(
                    currentSong.artist,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 13.sp,
                    maxLines = 1
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = {
                        if (player.hasPreviousMediaItem()) player.seekToPreviousMediaItem()
                    }) { Text("|◀", fontSize = 20.sp) }

                    Button(
                        onClick = {
                            if (player.isPlaying) player.pause()
                            else player.play()
                        }
                    ) {
                        Text(if (isPlaying) "Ⅱ PAUSAR" else "▶ OUVIR")
                    }

                    TextButton(onClick = {
                        if (player.hasNextMediaItem()) player.seekToNextMediaItem()
                    }) { Text("▶|", fontSize = 20.sp) }
                }
            }
        }
    }
}
