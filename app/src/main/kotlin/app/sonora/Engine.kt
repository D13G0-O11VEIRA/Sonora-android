package app.sonora

import android.content.Context
import androidx.core.content.ContextCompat
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/** Fila de downloads e pipeline completo (equivalente ao Manager da versão Python). */
object Engine {
    private lateinit var app: Context
    lateinit var store: SettingsStore
        private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = CopyOnWriteArrayList<DlJob>()
    private val ids = AtomicInteger(0)
    private val pendingResolves = AtomicInteger(0)
    @Volatile private var sem = Semaphore(2)
    @Volatile private var lastPublish = 0L

    val ui = MutableStateFlow<List<JobUi>>(emptyList())
    val logLines = MutableStateFlow<List<String>>(emptyList())
    val notice = MutableStateFlow("")
    val activeCount = MutableStateFlow(0)

    fun init(context: Context) {
        app = context.applicationContext
        store = SettingsStore(app)
        sem = Semaphore(store.settings.value.concurrent)
    }

    fun applySettings() {
        sem = Semaphore(store.settings.value.concurrent)
    }

    // ------------------------------------------------------------ estado
    private fun publish(force: Boolean = true) {
        val now = System.currentTimeMillis()
        if (!force && now - lastPublish < 300) return
        lastPublish = now
        ui.value = jobs.map { it.toUi() }
        activeCount.value = jobs.count { !it.status.finished } + pendingResolves.get()
    }

    fun log(msg: String) {
        val stamp = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        val line = "$stamp  $msg"
        logLines.value = (logLines.value + line).takeLast(150)
    }

    private fun say(msg: String) {
        log(msg)
        notice.value = msg
    }

    // ------------------------------------------------------------ entrada
    fun addLinks(text: String) {
        val items = parseInputs(text)
        if (items.isEmpty()) {
            notice.value = "Cole pelo menos um link ou nome de música."
            return
        }
        notice.value = "Lendo ${items.size} link(s)…"
        pendingResolves.addAndGet(items.size)
        publish()
        DownloadService.start(app)
        for (item in items) scope.launch { resolveLink(item) }
    }

    private suspend fun resolveLink(item: String) {
        try {
            val s = store.settings.value
            val link = classify(item, s.watchListIsPlaylist)
            log("Lendo: $item")
            val res: Resolved = when {
                link.kind == "unsupported" -> {
                    say("Link não suportado: $item")
                    return
                }
                link.kind == "spotify_artist" -> {
                    say("Links de artista não são suportados: cole uma faixa, álbum ou playlist.")
                    return
                }
                link.kind.startsWith("spotify_") ->
                    SpotifyResolver.resolve(link.kind.substringAfter('_'), link.id, item, ::log)
                link.kind == "youtube_video" -> Yt.resolveVideo(link.url)
                link.kind == "youtube_playlist" -> Yt.resolvePlaylist(link.url, ::log)
                else -> {
                    val idx = item.indexOf(" - ")
                    val meta = if (idx > 0) {
                        TrackMeta(title = item.substring(idx + 3).trim(), artists = listOf(item.substring(0, idx).trim()), origin = "search")
                    } else {
                        TrackMeta(title = item, origin = "search")
                    }
                    Resolved(listOf(meta), "", "single")
                }
            }
            enqueue(res)
        } catch (e: Exception) {
            say("Não consegui ler o link: ${shortError(e)}")
        } finally {
            pendingResolves.decrementAndGet()
            publish()
        }
    }

    private fun enqueue(res: Resolved) {
        if (res.tracks.isEmpty()) {
            say("Nenhuma faixa encontrada nesse link.")
            return
        }
        val batch = if (res.kind == "album" || res.kind == "playlist") Batch(res.name, res.kind) else null
        val created = mutableListOf<DlJob>()
        res.tracks.forEachIndexed { i, meta ->
            if (batch != null) {
                meta.playlistName = res.name
                meta.playlistIndex = i + 1
            }
            val job = DlJob(ids.incrementAndGet(), meta, batch)
            jobs.add(job)
            created.add(job)
        }
        log("${created.size} faixa(s) adicionada(s)" + if (res.name.isNotEmpty()) " de '${res.name}'" else "")
        publish()
        for (job in created) scope.launch { runJob(job) }
    }

    // ------------------------------------------------------------ controle
    fun cancel(id: Int) {
        val job = jobs.firstOrNull { it.id == id } ?: return
        if (job.status.finished) return
        job.cancelled.set(true)
        Yt.cancel(job)
    }

    fun cancelAll() {
        for (j in jobs) if (!j.status.finished) {
            j.cancelled.set(true)
            Yt.cancel(j)
        }
    }

    fun retry(id: Int) {
        val job = jobs.firstOrNull { it.id == id } ?: return
        if (!job.status.finished) return
        job.cancelled.set(false)
        job.status = Status.QUEUED
        job.error = ""
        job.progress = 0f
        job.speed = ""
        publish()
        DownloadService.start(app)
        scope.launch { runJob(job) }
    }

    fun clearFinished() {
        jobs.removeAll { it.status.finished }
        publish()
    }

    // ------------------------------------------------------------ execução
    private suspend fun runJob(job: DlJob) {
        var mySem: Semaphore? = null
        while (!job.cancelled.get()) { // espera uma vaga, mas deixa cancelar ainda na fila
            val s = sem
            if (s.tryAcquire()) {
                mySem = s
                break
            }
            delay(300)
        }
        var outcome = Status.CANCELLED
        var err = ""
        try {
            if (mySem != null && !job.cancelled.get()) outcome = process(job)
        } catch (e: CancelledByUser) {
            outcome = Status.CANCELLED
        } catch (e: Exception) {
            if (job.cancelled.get()) {
                outcome = Status.CANCELLED
            } else {
                outcome = Status.ERROR
                err = shortError(e)
                log("Erro em '${job.meta.title}': ${e.javaClass.simpleName}: ${e.message}")
            }
        } finally {
            mySem?.release()
            File(app.cacheDir, "dl/${job.id}").deleteRecursively()
        }
        job.status = outcome
        job.error = err
        job.speed = ""
        if (outcome == Status.DONE) job.progress = 100f
        publish()
    }

    private fun setStatus(job: DlJob, st: Status) {
        job.status = st
        publish()
    }

    private suspend fun process(job: DlJob): Status {
        val s = store.settings.value
        val m = job.meta
        setStatus(job, Status.RESOLVING)

        val authoritative = m.origin == "spotify" || (m.origin == "search" && m.artists.isNotEmpty())

        if (m.youtubeUrl.isEmpty()) {
            val cand = Yt.findBestMatch(m, ::log)
                ?: throw IllegalStateException("Nenhuma versão correspondente encontrada no YouTube.")
            m.youtubeUrl = cand.url
            m.youtubeId = cand.id
            if (m.duration == 0.0 && cand.duration != null) m.duration = cand.duration
        }
        if (!authoritative && !m.infoLoaded) {
            Yt.metaFromInfo(Yt.fetchInfo(m.youtubeUrl), m)
        }
        if (job.cancelled.get()) throw CancelledByUser()

        if (s.skipExisting && authoritative && m.title.isNotEmpty()) {
            val (rel, name) = targetPath(job, s)
            if (MediaSaver.exists(app, rel, name)) {
                job.outputName = name
                return Status.SKIPPED
            }
        }

        // ------------------------------------------------------ download
        setStatus(job, Status.DOWNLOADING)
        val tmp = File(app.cacheDir, "dl/${job.id}")
        val mp3 = Yt.download(
            job, m.youtubeUrl, tmp, s.bitrate,
            onProgress = { pct ->
                job.progress = pct
                publish(force = false)
            },
            onConvert = {
                if (job.status != Status.CONVERTING) {
                    job.progress = 99f
                    setStatus(job, Status.CONVERTING)
                }
            },
        )

        // ------------------------------------------------------ metadados
        setStatus(job, Status.TAGGING)
        if (m.title.isEmpty()) m.title = "Sem título"
        if (s.enrichOnline) Enrich.itunes(m, ::log)

        var cover: ByteArray? = null
        if (s.embedCover) {
            for (url in listOf(m.coverUrl, m.thumbUrl)) {
                if (url.isEmpty()) continue
                try {
                    cover = Enrich.cover(url)
                    if (cover != null) break
                } catch (e: Exception) {
                    log("Capa indisponível (${e.javaClass.simpleName}); tentando outra fonte.")
                }
            }
        }
        val lyrics = if (s.fetchLyrics) Enrich.lyrics(m, ::log) else ""

        val track = when {
            m.trackNo > 0 && m.trackTotal > 0 -> "${m.trackNo}/${m.trackTotal}"
            m.trackNo > 0 -> "${m.trackNo}"
            else -> ""
        }
        val disc = when {
            m.discNo > 0 && m.discTotal > 0 -> "${m.discNo}/${m.discTotal}"
            m.discNo > 0 -> "${m.discNo}"
            else -> ""
        }
        Tagger.write(
            mp3,
            TagFields(
                title = m.title,
                artist = m.artists.joinToString(s.artistSeparator),
                album = m.album,
                albumArtist = m.albumArtist.ifEmpty { m.mainArtist },
                year = m.year,
                track = track,
                disc = disc,
                genre = m.genre,
                isrc = m.isrc,
                comment = "Baixado com Sonora | ${m.sourceUrl.ifEmpty { m.youtubeUrl }}",
                lyrics = lyrics,
                sourceUrl = m.sourceUrl.ifEmpty { m.youtubeUrl },
            ),
            cover,
        )

        // ------------------------------------------------------ salvar
        var (rel, name) = targetPath(job, s)
        if (MediaSaver.exists(app, rel, name)) {
            if (s.skipExisting) {
                job.outputName = name
                return Status.SKIPPED
            }
            name = name.removeSuffix(".mp3") + " (2).mp3"
        }
        MediaSaver.save(app, mp3, rel, name)
        job.outputName = name
        log("✔ $name")
        return Status.DONE
    }

    /** Pasta relativa (Music/Sonora/...) e nome do arquivo conforme o modelo escolhido. */
    private fun targetPath(job: DlJob, s: Settings): Pair<String, String> {
        val m = job.meta
        val values = mapOf(
            "artist" to m.mainArtist.ifEmpty { "Desconhecido" },
            "artists" to m.artists.joinToString(", "),
            "title" to m.title.ifEmpty { "Sem título" },
            "album" to m.album,
            "album_artist" to m.albumArtist.ifEmpty { m.mainArtist },
            "year" to m.year,
            "track" to if (m.trackNo > 0) "%02d".format(m.trackNo) else "",
            "disc" to if (m.discNo > 0) m.discNo.toString() else "",
            "genre" to m.genre,
            "index" to if (m.playlistIndex > 0) "%02d".format(m.playlistIndex) else "",
            "playlist" to m.playlistName,
        )
        val parts = renderTemplate(s.filenameTemplate, values)
        val dirs = mutableListOf<String>()
        if (job.batch != null && s.playlistSubfolder) {
            sanitizeFilename(job.batch.name).takeIf { it.isNotEmpty() }?.let { dirs.add(it) }
        }
        dirs.addAll(parts.dropLast(1))
        return MediaSaver.relativePath(dirs) to parts.last() + ".mp3"
    }

    private val ANSI = Regex("\u001B\\[[0-9;]*m")

    fun shortError(e: Throwable): String {
        val raw = ANSI.replace(e.message.orEmpty(), "").replace("ERROR:", "").trim()
        var msg = raw.lines().firstOrNull { it.isNotBlank() }?.trim() ?: e.javaClass.simpleName
        val low = msg.lowercase()
        if (("confirm you" in low && "bot" in low) || "sign in" in low) {
            msg += " → o YouTube pediu login; tente de novo mais tarde ou atualize o yt-dlp (Ajustes)."
        } else if ("signature" in low || "n challenge" in low || "javascript" in low) {
            msg += " → toque em 'Atualizar yt-dlp' em Ajustes e tente de novo."
        } else if (e is HttpException || "unable to resolve host" in low) {
            msg += " (verifique a conexão com a internet)"
        }
        return msg.take(260)
    }
}
