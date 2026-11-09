package app.sonora

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Divider
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import coil.compose.AsyncImage
import com.yausername.youtubedl_android.YoutubeDL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private val sharedText = mutableStateOf("")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleIntent(intent)
        setContent { SonoraTheme { SonoraRoot(sharedText) } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    /** Links enviados pelo menu "Compartilhar" do YouTube/Spotify/navegador chegam preenchidos na tela. */
    private fun handleIntent(i: Intent?) {
        if (i?.action == Intent.ACTION_SEND && i.type?.startsWith("text/") == true) {
            val t = i.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
            if (t.isNotBlank()) sharedText.value = t
        }
    }
}

// ----------------------------------------------------------------------- tema
private val Pink = Color(0xFFFF4F8B)
private val Mint = Color(0xFF5EE6B0)
private val Amber = Color(0xFFFFC15E)
private val Red = Color(0xFFFF6B6B)

@Composable
fun SonoraTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val colors = if (dark) {
        darkColorScheme(
            primary = Pink, onPrimary = Color(0xFF2A0713),
            background = Color(0xFF141A3A), onBackground = Color(0xFFEEF1FF),
            surface = Color(0xFF141A3A), onSurface = Color(0xFFEEF1FF),
            surfaceVariant = Color(0xFF1B2250), onSurfaceVariant = Color(0xFF9AA3D1),
            outline = Color(0xFF2A3262), secondaryContainer = Color(0xFF3A2150), onSecondaryContainer = Color(0xFFFFD9E6),
            error = Red,
        )
    } else {
        lightColorScheme(
            primary = Color(0xFFE0245F), onPrimary = Color.White,
            background = Color(0xFFF4F2FB), onBackground = Color(0xFF1A1D3D),
            surface = Color(0xFFF4F2FB), onSurface = Color(0xFF1A1D3D),
            surfaceVariant = Color(0xFFEBE8F7), onSurfaceVariant = Color(0xFF5D6290),
            outline = Color(0xFFD9D6EE), secondaryContainer = Color(0xFFFFD9E6), onSecondaryContainer = Color(0xFF3A0A1C),
            error = Color(0xFFD1383D),
        )
    }
    MaterialTheme(colorScheme = colors, content = content)
}

// ----------------------------------------------------------------------- raiz
@Composable
fun SonoraRoot(shared: MutableState<String>) {
    var tab by remember { mutableIntStateOf(0) }
    val active by Engine.activeCount.collectAsState()

    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33) notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
    LaunchedEffect(shared.value) {
        if (shared.value.isNotBlank()) tab = 0
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            Row(
                Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 18.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("Sonora", fontSize = 28.sp, fontWeight = FontWeight.Bold)
                Text(
                    if (active > 0) "$active em andamento" else "Pronto",
                    color = if (active > 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 13.sp,
                )
            }
        },
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceVariant) {
                listOf("⬇" to "Fila", "♫" to "Biblioteca", "⚙" to "Ajustes").forEachIndexed { i, (icon, label) ->
                    NavigationBarItem(
                        selected = tab == i,
                        onClick = { tab = i },
                        icon = { Text(icon, fontSize = 20.sp) },
                        label = { Text(label) },
                    )
                }
            }
        },
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            when (tab) {
                0 -> QueueScreen(shared)
                1 -> LibraryScreen()
                else -> SettingsScreen()
            }
        }
    }
}

// ----------------------------------------------------------------------- fila
@Composable
fun QueueScreen(shared: MutableState<String>) {
    val jobs by Engine.ui.collectAsState()
    val notice by Engine.notice.collectAsState()
    var input by rememberSaveable { mutableStateOf("") }
    val clipboard = LocalClipboardManager.current

    LaunchedEffect(shared.value) {
        if (shared.value.isNotBlank()) {
            input = shared.value
            shared.value = ""
        }
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        OutlinedTextField(
            value = input,
            onValueChange = { input = it },
            modifier = Modifier.fillMaxWidth(),
            minLines = 3,
            maxLines = 6,
            placeholder = { Text("Cole links do YouTube ou Spotify (música, álbum ou playlist), um por linha — ou digite: Artista - Música") },
        )
        Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick = {
                val clip = clipboard.getText()?.text.orEmpty()
                if (clip.isNotBlank()) input = if (input.isBlank()) clip else input.trimEnd() + "\n" + clip
            }) { Text("Colar") }
            Button(
                onClick = {
                    if (input.isBlank()) {
                        Engine.notice.value = "Cole um link primeiro."
                    } else {
                        Engine.addLinks(input)
                        input = ""
                    }
                },
                modifier = Modifier.weight(1f),
            ) { Text("Baixar") }
        }
        Text(
            notice,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 14.sp,
            modifier = Modifier.padding(vertical = 8.dp, horizontal = 2.dp),
        )

        if (jobs.isEmpty()) {
            Column(Modifier.fillMaxWidth().weight(1f).padding(top = 40.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Nenhuma música na fila", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Text(
                    "Cole um link acima ou compartilhe um link do YouTube/Spotify com o Sonora.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        } else {
            LazyColumn(Modifier.weight(1f)) {
                items(jobs, key = { it.id }) { JobRow(it) }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = { Engine.clearFinished() }) { Text("Limpar concluídas") }
                TextButton(onClick = { Engine.cancelAll() }) { Text("Cancelar tudo") }
            }
        }
    }
}

@Composable
private fun SpinningArt(url: String, spinning: Boolean) {
    val angle = if (spinning) {
        val transition = rememberInfiniteTransition(label = "spin")
        val a by transition.animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(tween(3200, easing = LinearEasing), RepeatMode.Restart),
            label = "angle",
        )
        a
    } else {
        0f
    }
    val shape = if (spinning) CircleShape else RoundedCornerShape(10.dp)
    val mod = Modifier
        .size(52.dp)
        .rotate(angle)
        .clip(shape)
        .background(MaterialTheme.colorScheme.surfaceVariant)
    if (url.isNotEmpty()) {
        AsyncImage(model = url, contentDescription = null, contentScale = ContentScale.Crop, modifier = mod)
    } else {
        Box(mod)
    }
}

@Composable
fun JobRow(j: JobUi) {
    val statusColor = when (j.status) {
        Status.DONE -> Mint
        Status.ERROR -> Red
        Status.SKIPPED -> Amber
        Status.CANCELLED -> MaterialTheme.colorScheme.onSurfaceVariant
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Column {
        Divider(color = MaterialTheme.colorScheme.outline)
        Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            SpinningArt(j.cover, j.active && j.status != Status.QUEUED)
            Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
                Text(j.title, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (j.error.isNotEmpty()) {
                    Text(j.error, color = Red, fontSize = 13.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
                } else if (j.subtitle.isNotEmpty()) {
                    Text(
                        j.subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.height(6.dp))
                val shown = when (j.status) {
                    Status.DONE, Status.SKIPPED -> 1f
                    else -> (j.progress / 100f).coerceIn(0f, 1f)
                }
                LinearProgressIndicator(
                    progress = { shown },
                    modifier = Modifier.fillMaxWidth(),
                    color = if (j.status == Status.ERROR) Red else if (j.status == Status.DONE || j.status == Status.SKIPPED) Mint else MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.outline,
                )
                Text(
                    if (j.status == Status.DOWNLOADING) "Baixando ${j.progress.toInt()}%" else j.status.label,
                    color = statusColor, fontSize = 13.sp,
                )
            }
            if (j.active) {
                TextButton(onClick = { Engine.cancel(j.id) }) { Text("Cancelar") }
            } else if (j.status == Status.ERROR || j.status == Status.CANCELLED) {
                TextButton(onClick = { Engine.retry(j.id) }) { Text("Tentar de novo") }
            }
        }
    }
}

// ----------------------------------------------------------------- biblioteca
@Composable
fun LibraryScreen() {
    val ctx = LocalContext.current
    val perm = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE
    var granted by remember { mutableStateOf(ContextCompat.checkSelfPermission(ctx, perm) == PackageManager.PERMISSION_GRANTED) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    var items by remember { mutableStateOf<List<LibraryItem>>(emptyList()) }
    var query by rememberSaveable { mutableStateOf("") }
    val jobs by Engine.ui.collectAsState()
    val doneCount = jobs.count { it.status == Status.DONE }

    LaunchedEffect(granted, doneCount) {
        items = withContext(Dispatchers.IO) { MediaSaver.list(ctx) }
    }

    val filtered = items.filter {
        query.isBlank() || "${it.title} ${it.artist} ${it.album}".contains(query.trim(), ignoreCase = true)
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        OutlinedTextField(
            value = query, onValueChange = { query = it }, singleLine = true,
            modifier = Modifier.fillMaxWidth(), placeholder = { Text("Buscar por título, artista ou álbum") },
        )
        Text(
            "${filtered.size} de ${items.size} músicas  •  Música/Sonora",
            color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp,
            modifier = Modifier.padding(vertical = 8.dp),
        )
        if (!granted) {
            OutlinedButton(onClick = { launcher.launch(perm) }, modifier = Modifier.fillMaxWidth()) {
                Text("Permitir ver músicas baixadas antes (opcional)")
            }
        }
        LazyColumn(Modifier.weight(1f)) {
            items(filtered, key = { it.uriString }) { it ->
                Column(
                    Modifier.fillMaxWidth().clickable {
                        try {
                            ctx.startActivity(
                                Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(it.uriString), "audio/*")
                                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
                            )
                        } catch (e: Exception) {
                            Engine.notice.value = "Nenhum player de música encontrado."
                        }
                    },
                ) {
                    Divider(color = MaterialTheme.colorScheme.outline)
                    Text(it.title.ifBlank { "(sem título)" }, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 10.dp))
                    val sub = listOf(it.artist, it.album, fmtDuration(it.durationMs)).filter { s -> s.isNotBlank() }.joinToString(" • ")
                    Text(sub, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(bottom = 10.dp))
                }
            }
        }
    }
}

private fun fmtDuration(ms: Long): String {
    val total = ms / 1000
    return "%d:%02d".format(total / 60, total % 60)
}

// -------------------------------------------------------------------- ajustes
private val SAMPLE = mapOf(
    "artist" to "Mother Mother", "artists" to "Mother Mother", "title" to "Burning Pile",
    "album" to "O My Heart", "album_artist" to "Mother Mother", "year" to "2011", "track" to "02",
    "disc" to "1", "genre" to "Indie Rock", "index" to "07", "playlist" to "Minhas músicas",
)

@Composable
private fun SectionTitle(text: String) {
    Text(text, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 22.dp, bottom = 6.dp))
}

@Composable
private fun SwitchRow(label: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(checked = value, onCheckedChange = onChange)
    }
}

@Composable
fun SettingsScreen() {
    val ctx = LocalContext.current
    val saved by Engine.store.settings.collectAsState()
    var d by remember(saved) { mutableStateOf(saved) }
    val logLines by Engine.logLines.collectAsState()
    var msg by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp).padding(bottom = 24.dp)) {
        SectionTitle("Qualidade do MP3 (kbps)")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (b in BITRATES) {
                FilterChip(selected = d.bitrate == b, onClick = { d = d.copy(bitrate = b) }, label = { Text(b) })
            }
        }
        Text(
            "A fonte do YouTube tem cerca de 128–160 kbps; 320 apenas garante compatibilidade.",
            color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp),
        )

        SectionTitle("Nome do arquivo")
        OutlinedTextField(
            value = d.filenameTemplate, onValueChange = { d = d.copy(filenameTemplate = it) },
            singleLine = true, modifier = Modifier.fillMaxWidth(),
        )
        Text(
            "Exemplo:  " + renderTemplate(d.filenameTemplate, SAMPLE).joinToString("/") + ".mp3",
            color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp),
        )
        for ((label, tpl) in TEMPLATE_PRESETS) {
            TextButton(onClick = { d = d.copy(filenameTemplate = tpl) }) { Text(label) }
        }
        Text(
            "Variáveis: {artist} {title} {album} {year} {track} {index} {playlist}. Use / para criar subpastas dentro de Música/Sonora.",
            color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp,
        )
        SwitchRow("Playlists e álbuns em subpasta", d.playlistSubfolder) { d = d.copy(playlistSubfolder = it) }
        SwitchRow("Pular o que já foi baixado", d.skipExisting) { d = d.copy(skipExisting = it) }

        SectionTitle("Metadados")
        SwitchRow("Embutir capa", d.embedCover) { d = d.copy(embedCover = it) }
        SwitchRow("Completar álbum, ano e gênero online", d.enrichOnline) { d = d.copy(enrichOnline = it) }
        SwitchRow("Buscar e embutir a letra", d.fetchLyrics) { d = d.copy(fetchLyrics = it) }
        OutlinedTextField(
            value = d.artistSeparator, onValueChange = { d = d.copy(artistSeparator = it) },
            label = { Text("Separador de artistas") }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
        )

        SectionTitle("Downloads")
        Text("Simultâneos: ${d.concurrent}")
        Slider(
            value = d.concurrent.toFloat(), onValueChange = { d = d.copy(concurrent = it.toInt().coerceIn(1, 4)) },
            valueRange = 1f..4f, steps = 2,
        )
        SwitchRow("Link com vídeo + playlist baixa a playlist inteira", d.watchListIsPlaylist) { d = d.copy(watchListIsPlaylist = it) }

        Row(Modifier.fillMaxWidth().padding(top = 18.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(
                onClick = {
                    Engine.store.save(d)
                    Engine.applySettings()
                    msg = "Ajustes salvos."
                },
                modifier = Modifier.weight(1f),
            ) { Text("Salvar ajustes") }
            OutlinedButton(onClick = {
                msg = "Atualizando o yt-dlp…"
                scope.launch(Dispatchers.IO) {
                    msg = try {
                        Yt.ready.await()
                        val r = YoutubeDL.getInstance().updateYoutubeDL(ctx)
                        "yt-dlp atualizado ($r). Reinicie o app para usar a nova versão."
                    } catch (e: Exception) {
                        "Não foi possível atualizar: ${Engine.shortError(e)}"
                    }
                }
            }) { Text("Atualizar yt-dlp") }
        }
        if (msg.isNotEmpty()) {
            Text(msg, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
        }

        SectionTitle("Registro")
        Text(
            logLines.takeLast(25).joinToString("\n").ifEmpty { "Sem mensagens ainda." },
            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp)).padding(12.dp),
        )
    }
}
