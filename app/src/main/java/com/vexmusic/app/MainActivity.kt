package com.vexmusic.app

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaMetadataRetriever
import java.io.File
import java.io.FileOutputStream
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import coil.compose.AsyncImage
import com.vexmusic.app.ui.VexTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

private val Bg = Color(0xFF090B0F)
private val Panel = Color(0xFF151922)
private val Green = Color(0xFFB6FF3B)
private val Muted = Color(0xFF9299A8)
private val White = Color(0xFFF6F7FA)

data class Song(
    val id: Long,
    val title: String,
    val artist: String,
    val duration: Long,
    val uri: String,
    val cover: String?
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            VexTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = Bg
                ) {
                    VexMusicApp()
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
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.DATA
        )

        try {
            context.contentResolver.query(
                collection,
                projection,
                "${MediaStore.Audio.Media.IS_MUSIC} != 0 AND ${MediaStore.Audio.Media.DURATION} >= 45000",
                null,
                "${MediaStore.Audio.Media.TITLE} ASC"
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val titleCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                val artistCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                val durationCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
                val pathCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)

                while (cursor.moveToNext()) {
                    val path = cursor.getString(pathCol)?.lowercase().orEmpty()
                    if (listOf("/android/data/", "/android/obb/").any { path.contains(it) }) {
                        continue
                    }

                    val id = cursor.getLong(idCol)
                    val title = cursor.getString(titleCol)
                        ?.takeIf { it.isNotBlank() && it != "<unknown>" }
                        ?: "Música sem título"
                    val artist = cursor.getString(artistCol)
                        ?.takeIf { it.isNotBlank() && it != "<unknown>" }
                        ?: "Artista desconhecido"

                    val songUri = ContentUris.withAppendedId(collection, id)
                    val cover = extractSongCover(context, songUri, id)

                    result.add(
                        Song(
                            id = id,
                            title = title,
                            artist = artist,
                            duration = cursor.getLong(durationCol),
                            uri = songUri.toString(),
                            cover = cover
                        )
                    )
                }
            }
        } catch (_: Exception) {
        }
        result
    }

private fun extractSongCover(
    context: Context,
    songUri: android.net.Uri,
    songId: Long
): String? {
    val directory = File(context.cacheDir, "vex_music_covers")
    if (!directory.exists() && !directory.mkdirs()) return null

    val file = File(directory, "$songId.jpg")
    if (file.exists() && file.length() > 0L) {
        return android.net.Uri.fromFile(file).toString()
    }

    val retriever = MediaMetadataRetriever()
    return try {
        retriever.setDataSource(context, songUri)
        val picture = retriever.embeddedPicture ?: return null

        FileOutputStream(file).use { output ->
            output.write(picture)
        }

        android.net.Uri.fromFile(file).toString()
    } catch (_: Exception) {
        file.delete()
        null
    } finally {
        retriever.release()
    }
}

private fun timeText(ms: Long): String {
    val seconds = ms.coerceAtLeast(0L) / 1000
    return "%d:%02d".format(seconds / 60, seconds % 60)
}

@Composable
private fun VexMusicApp() {
    val context = androidx.compose.ui.platform.LocalContext.current
    var permitted by remember { mutableStateOf(hasPermission(context)) }
    var songs by remember { mutableStateOf(emptyList<Song>()) }
    var search by remember { mutableStateOf("") }
    var tab by remember { mutableStateOf("Início") }
    var currentId by remember { mutableStateOf<String?>(null) }
    var playing by remember { mutableStateOf(false) }
    var expanded by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var position by remember { mutableFloatStateOf(0f) }
    var duration by remember { mutableLongStateOf(0L) }
    var shuffle by remember { mutableStateOf(false) }
    var repeatAll by remember { mutableStateOf(false) }

    val prefs = remember {
        context.getSharedPreferences("vex_music", Context.MODE_PRIVATE)
    }
    var favorites by remember {
        mutableStateOf(prefs.getStringSet("favorites", emptySet())?.toSet() ?: emptySet())
    }

    val player = remember {
        ExoPlayer.Builder(context.applicationContext).build()
    }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                playing = isPlaying
            }

            override fun onMediaItemTransition(item: MediaItem?, reason: Int) {
                currentId = item?.mediaId
                position = 0f
                duration = player.duration.coerceAtLeast(0L)
            }

            override fun onPlaybackStateChanged(state: Int) {
                duration = player.duration.coerceAtLeast(0L)
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.release()
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { permitted = it }

    LaunchedEffect(permitted) {
        if (permitted) {
            loading = true
            songs = loadSongs(context)
            loading = false
        }
    }

    LaunchedEffect(playing, currentId) {
        while (true) {
            if (playing) {
                position = player.currentPosition.coerceAtLeast(0L).toFloat()
                duration = player.duration.coerceAtLeast(0L)
            }
            delay(500)
        }
    }

    val currentSong = songs.find { it.id.toString() == currentId }
    val filtered = remember(songs, search, tab, favorites) {
        when (tab) {
            "Favoritas" -> songs.filter { it.id.toString() in favorites }
            else -> songs
        }.filter {
            it.title.contains(search, true) || it.artist.contains(search, true)
        }
    }

    fun playSong(song: Song) {
        val index = songs.indexOfFirst { it.id == song.id }
        if (index < 0) return

        val playlist = songs.map {
            MediaItem.Builder()
                .setMediaId(it.id.toString())
                .setUri(it.uri)
                .build()
        }
        player.setMediaItems(playlist, index, 0L)
        player.prepare()
        player.play()
        currentId = song.id.toString()
        expanded = true
    }

    fun toggleFavorite(song: Song) {
        val key = song.id.toString()
        favorites = if (key in favorites) favorites - key else favorites + key
        prefs.edit().putStringSet("favorites", favorites).apply()
    }

    if (expanded && currentSong != null) {
        FullPlayer(
            song = currentSong,
            playing = playing,
            position = position,
            duration = duration,
            favorite = currentSong.id.toString() in favorites,
            shuffle = shuffle,
            repeatAll = repeatAll,
            onBack = { expanded = false },
            onPlayPause = {
                if (player.isPlaying) player.pause() else player.play()
            },
            onPrevious = { player.seekToPreviousMediaItem() },
            onNext = { player.seekToNextMediaItem() },
            onSeek = {
                player.seekTo(it.toLong())
                position = it
            },
            onFavorite = { toggleFavorite(currentSong) },
            onShuffle = {
                shuffle = !shuffle
                player.shuffleModeEnabled = shuffle
            },
            onRepeat = {
                repeatAll = !repeatAll
                player.repeatMode = if (repeatAll) Player.REPEAT_MODE_OFF
                    else Player.REPEAT_MODE_ALL
            }
        )
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Bg)
            .statusBarsPadding()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 22.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    "VEX", fontSize = 30.sp,
                    fontWeight = FontWeight.Black, color = Green
                )
                Text(
                    "MUSIC PLAYER", fontSize = 10.sp,
                    letterSpacing = 3.sp, color = Muted
                )
            }
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(Panel),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.GraphicEq, null, tint = Green)
            }
        }

        if (!permitted) {
            Column(
                Modifier.fillMaxSize().padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(Icons.Default.LibraryMusic, null, tint = Green, modifier = Modifier.size(64.dp))
                Spacer(Modifier.height(16.dp))
                Text("Sua música começa aqui", color = White, fontSize = 23.sp, fontWeight = FontWeight.Bold)
                Text("Permita o acesso para carregar as músicas do aparelho.", color = Muted)
                Spacer(Modifier.height(18.dp))
                Button(onClick = { permissionLauncher.launch(audioPermission) }) {
                    Text("Permitir acesso")
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (tab == "Início") {
                    item {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(26.dp))
                                .background(
                                    Brush.linearGradient(
                                        listOf(Color(0xFF293B17), Color(0xFF151922), Color(0xFF202631))
                                    )
                                )
                                .padding(22.dp)
                        ) {
                            Column {
                                Text("SEU SOM. SUA VIBE.", color = Green, fontSize = 11.sp, letterSpacing = 2.sp)
                                Spacer(Modifier.height(8.dp))
                                Text("Toda sua música\nem um só lugar.", color = White, fontSize = 25.sp, fontWeight = FontWeight.ExtraBold)
                                Spacer(Modifier.height(8.dp))
                                Text("${songs.size} músicas na biblioteca", color = Muted, fontSize = 13.sp)
                            }
                        }
                    }
                }

                item {
                    Text(
                        when (tab) {
                            "Favoritas" -> "Suas favoritas"
                            "Biblioteca" -> "Sua biblioteca"
                            "Buscar" -> "Encontrar músicas"
                            else -> "Tocadas no seu aparelho"
                        },
                        color = White, fontSize = 21.sp, fontWeight = FontWeight.Bold
                    )
                }

                if (tab == "Buscar") {
                    item {
                        OutlinedTextField(
                            value = search,
                            onValueChange = { search = it },
                            modifier = Modifier.fillMaxWidth(),
                            placeholder = { Text("Nome da música ou artista", color = Muted) },
                            leadingIcon = { Icon(Icons.Default.Search, null, tint = Green) },
                            singleLine = true,
                            shape = RoundedCornerShape(18.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = White,
                                unfocusedTextColor = White,
                                focusedBorderColor = Green,
                                unfocusedBorderColor = Panel,
                                cursorColor = Green
                            )
                        )
                    }
                }

                if (loading) {
                    item {
                        Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(color = Green)
                        }
                    }
                } else if (filtered.isEmpty()) {
                    item {
                        Column(
                            Modifier.fillMaxWidth().padding(vertical = 36.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(Icons.Default.MusicOff, null, tint = Muted, modifier = Modifier.size(48.dp))
                            Spacer(Modifier.height(12.dp))
                            Text(
                                if (tab == "Favoritas") "Você ainda não favoritou músicas"
                                else "Nenhuma música encontrada",
                                color = White, fontWeight = FontWeight.SemiBold
                            )
                            Text("Adicione arquivos de áudio ao aparelho e atualize a biblioteca.", color = Muted, fontSize = 12.sp)
                        }
                    }
                } else {
                    items(filtered, key = { it.id }) { song ->
                        SongRow(
                            song = song,
                            isCurrent = song.id.toString() == currentId,
                            favorite = song.id.toString() in favorites,
                            onClick = { playSong(song) },
                            onFavorite = { toggleFavorite(song) }
                        )
                    }
                }
            }

            if (currentSong != null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                        .clip(RoundedCornerShape(18.dp))
                        .background(Panel)
                        .clickable { expanded = true }
                        .padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Cover(song = currentSong, size = 48)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(currentSong.title, color = White, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(currentSong.artist, color = Muted, fontSize = 12.sp, maxLines = 1)
                    }
                    IconButton(onClick = { if (player.isPlaying) player.pause() else player.play() }) {
                        Icon(
                            if (playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                            null, tint = Green
                        )
                    }
                    IconButton(onClick = { player.seekToNextMediaItem() }) {
                        Icon(Icons.Default.SkipNext, null, tint = White)
                    }
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF0E1117))
                    .navigationBarsPadding()
                    .padding(top = 8.dp, bottom = 5.dp),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                NavItem("Início", Icons.Default.Home, tab == "Início") { tab = "Início"; search = "" }
                NavItem("Buscar", Icons.Default.Search, tab == "Buscar") { tab = "Buscar" }
                NavItem("Biblioteca", Icons.Default.LibraryMusic, tab == "Biblioteca") { tab = "Biblioteca"; search = "" }
                NavItem("Favoritas", Icons.Default.Favorite, tab == "Favoritas") { tab = "Favoritas"; search = "" }
            }
        }
    }
}

@Composable
private fun Cover(song: Song, size: Int) {
    AsyncImage(
        model = song.cover,
        contentDescription = "Capa de ${song.title}",
        modifier = Modifier
            .size(size.dp)
            .clip(RoundedCornerShape(14.dp)),
        contentScale = androidx.compose.ui.layout.ContentScale.Crop,
        placeholder = ColorPainter(Panel),
        error = ColorPainter(Panel)
    )
}

@Composable
private fun SongRow(
    song: Song,
    isCurrent: Boolean,
    favorite: Boolean,
    onClick: () -> Unit,
    onFavorite: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(if (isCurrent) Color(0xFF202B19) else Panel)
            .clickable(onClick = onClick)
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Cover(song, 54)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                song.title, color = if (isCurrent) Green else White,
                fontWeight = FontWeight.SemiBold, maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(3.dp))
            Text(song.artist, color = Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        IconButton(onClick = onFavorite) {
            Icon(
                if (favorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                null, tint = if (favorite) Green else Muted
            )
        }
        Text(timeText(song.duration), color = Muted, fontSize = 11.sp)
    }
}

@Composable
private fun NavItem(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    selected: Boolean,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 9.dp, vertical = 5.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(icon, null, tint = if (selected) Green else Muted, modifier = Modifier.size(22.dp))
        Text(label, color = if (selected) Green else Muted, fontSize = 10.sp)
    }
}

@Composable
private fun FullPlayer(
    song: Song,
    playing: Boolean,
    position: Float,
    duration: Long,
    favorite: Boolean,
    shuffle: Boolean,
    repeatAll: Boolean,
    onBack: () -> Unit,
    onPlayPause: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onSeek: (Float) -> Unit,
    onFavorite: () -> Unit,
    onShuffle: () -> Unit,
    onRepeat: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(Color(0xFF25351C), Bg, Bg)
                )
            )
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Default.KeyboardArrowDown, null, tint = White, modifier = Modifier.size(32.dp))
            }
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("TOCANDO AGORA", color = Green, fontSize = 10.sp, letterSpacing = 2.sp)
                Text("VEX MUSIC", color = Muted, fontSize = 11.sp)
            }
            IconButton(onClick = onFavorite) {
                Icon(
                    if (favorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                    null, tint = if (favorite) Green else White
                )
            }
        }

        Spacer(Modifier.weight(0.7f))

        AsyncImage(
            model = song.cover,
            contentDescription = "Capa de ${song.title}",
            modifier = Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(28.dp)),
            contentScale = androidx.compose.ui.layout.ContentScale.Crop,
            placeholder = ColorPainter(Panel),
            error = ColorPainter(Panel)
        )

        Spacer(Modifier.weight(0.7f))

        Column(Modifier.fillMaxWidth()) {
            Text(song.title, color = White, fontSize = 24.sp, fontWeight = FontWeight.ExtraBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(5.dp))
            Text(song.artist, color = Muted, fontSize = 15.sp)
            Spacer(Modifier.height(18.dp))

            Slider(
                value = position.coerceIn(0f, duration.coerceAtLeast(1L).toFloat()),
                onValueChange = onSeek,
                valueRange = 0f..duration.coerceAtLeast(1L).toFloat(),
                colors = SliderDefaults.colors(
                    thumbColor = Green,
                    activeTrackColor = Green,
                    inactiveTrackColor = Color(0xFF414653)
                )
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(timeText(position.toLong()), color = Muted, fontSize = 11.sp)
                Text(timeText(duration), color = Muted, fontSize = 11.sp)
            }
        }

        Spacer(Modifier.height(16.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onShuffle) {
                Icon(Icons.Default.Shuffle, null, tint = if (shuffle) Green else Muted)
            }
            IconButton(onClick = onPrevious, modifier = Modifier.size(52.dp)) {
                Icon(Icons.Default.SkipPrevious, null, tint = White, modifier = Modifier.size(36.dp))
            }
            IconButton(
                onClick = onPlayPause,
                modifier = Modifier.size(76.dp).clip(CircleShape).background(Green)
            ) {
                Icon(
                    if (playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                    null, tint = Bg, modifier = Modifier.size(42.dp)
                )
            }
            IconButton(onClick = onNext, modifier = Modifier.size(52.dp)) {
                Icon(Icons.Default.SkipNext, null, tint = White, modifier = Modifier.size(36.dp))
            }
            IconButton(onClick = onRepeat) {
                Icon(Icons.Default.Repeat, null, tint = if (repeatAll) Green else Muted)
            }
        }

        Spacer(Modifier.weight(1f))
        Text("SUA MÚSICA. SUA VIBE.", color = Muted, fontSize = 10.sp, letterSpacing = 2.sp)
        Spacer(Modifier.height(18.dp))
    }
}
