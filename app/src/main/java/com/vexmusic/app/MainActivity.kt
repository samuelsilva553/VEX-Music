package com.vexmusic.app

import android.Manifest
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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.vexmusic.app.ui.VexTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { VexTheme { Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { Home() } } }
    }
}

private val audioPermission: String =
    if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO
    else Manifest.permission.READ_EXTERNAL_STORAGE

private fun hasAudioPermission(c: Context) =
    ContextCompat.checkSelfPermission(c, audioPermission) == PackageManager.PERMISSION_GRANTED

private suspend fun countSongs(c: Context): Int = withContext(Dispatchers.IO) {
    runCatching {
        c.contentResolver.query(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Audio.Media._ID),
            "${MediaStore.Audio.Media.IS_MUSIC} != 0", null, null,
        )?.use { it.count } ?: 0
    }.getOrDefault(0)
}

@Composable
private fun Home() {
    val ctx = LocalContext.current
    var granted by remember { mutableStateOf(hasAudioPermission(ctx)) }
    var songs by remember { mutableIntStateOf(-1) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }

    LaunchedEffect(granted) { if (granted) songs = countSongs(ctx) }

    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(stringResource(R.string.app_name), fontSize = 32.sp, fontWeight = FontWeight.Bold)
        Text(stringResource(R.string.tagline), color = MaterialTheme.colorScheme.primary)
        Column(Modifier.padding(top = 32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            if (!granted) {
                Text(stringResource(R.string.perm_title), fontWeight = FontWeight.SemiBold)
                Text(stringResource(R.string.perm_body), modifier = Modifier.padding(vertical = 12.dp))
                Button(onClick = { ask.launch(audioPermission) }) { Text(stringResource(R.string.perm_button)) }
            } else if (songs >= 0) {
                Text(if (songs > 0) stringResource(R.string.found_songs, songs) else stringResource(R.string.found_none))
            }
        }
    }
}
