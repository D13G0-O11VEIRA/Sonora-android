package app.sonora

import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.CompletableDeferred
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** Tudo que fala com o yt-dlp embutido (via youtubedl-android). */
object Yt {
    /** Completado pelo SonoraApp quando Python/ffmpeg terminaram de ser extraídos. */
    val ready = CompletableDeferred<Unit>()

    private fun parseJson(out: String): JSONObject {
        val text = out.trim()
        return try {
            JSONObject(text)
        } catch (e: Exception) {
            val start = text.indexOf('{')
            if (start < 0) throw IllegalStateException("O yt-dlp não retornou dados.")
            JSONObject(text.substring(start))
        }
    }

    private fun dumpJson(url: String, flat: Boolean): JSONObject {
        val req = YoutubeDLRequest(url)
        req.addOption("--dump-single-json")
        req.addOption("--skip-download")
        req.addOption("--no-warnings")
        if (flat) req.addOption("--flat-playlist") else req.addOption("--no-playlist")
        val resp = YoutubeDL.getInstance().execute(request = req, processId = "info-" + UUID.randomUUID())
        return parseJson(resp.out)
    }

    suspend fun fetchInfo(url: String): JSONObject {
        ready.await()
        return dumpJson(url, flat = false)
    }

    /** Atualiza os metadados a partir do JSON completo do yt-dlp. */
    fun metaFromInfo(info: JSONObject, base: TrackMeta? = null): TrackMeta {
        val m = base ?: TrackMeta()
        var artists = mutableListOf<String>()
        val arr = info.optJSONArray("artists")
        if (arr != null) {
            for (i in 0 until arr.length()) {
                val a = arr.optString(i)
                if (a.isNotBlank()) artists.add(a.trim())
            }
        }
        if (artists.isEmpty() && info.s("artist").isNotEmpty()) {
            artists = info.s("artist").split(Regex(",\\s*")).map { it.trim() }.filter { it.isNotEmpty() }.toMutableList()
        }
        var title = info.s("track")
        if (title.isEmpty() || artists.isEmpty()) { // vídeo comum: deduz pelo título/canal
            val g = guessArtistTitle(info.s("title"), info.s("channel").ifEmpty { info.s("uploader") })
            if (title.isEmpty()) title = g.title
            if (artists.isEmpty()) {
                artists = (listOfNotNull(g.artist.ifEmpty { null }) + g.featured).toMutableList()
            }
        }
        if (title.isNotEmpty()) m.title = title
        val deduped = dedupe(artists)
        if (deduped.isNotEmpty()) m.artists = deduped
        if (m.album.isEmpty()) m.album = info.s("album")
        if (m.year.isEmpty()) {
            val ry = info.i("release_year")
            m.year = if (ry > 0) ry.toString() else info.s("release_date").take(4)
        }
        if (m.genre.isEmpty()) m.genre = info.s("genre")
        if (m.trackNo == 0) m.trackNo = info.i("track_number")
        if (m.duration == 0.0) m.duration = info.d("duration")
        if (info.s("id").isNotEmpty()) m.youtubeId = info.s("id")
        if (info.s("webpage_url").isNotEmpty()) m.youtubeUrl = info.s("webpage_url")
        if (m.sourceUrl.isEmpty()) m.sourceUrl = m.youtubeUrl
        if (info.s("thumbnail").isNotEmpty()) m.thumbUrl = info.s("thumbnail")
        m.infoLoaded = true
        return m
    }

    suspend fun resolveVideo(url: String): Resolved {
        ready.await()
        val info = dumpJson(url, flat = false)
        val meta = metaFromInfo(info, TrackMeta(origin = "youtube", sourceUrl = url))
        return Resolved(listOf(meta), "", "single")
    }

    suspend fun resolvePlaylist(url: String, log: (String) -> Unit): Resolved {
        ready.await()
        val info = dumpJson(url, flat = true)
        val name = info.s("title").ifEmpty { "Playlist" }
        val entries = info.optJSONArray("entries")
        val tracks = mutableListOf<TrackMeta>()
        if (entries != null) {
            for (i in 0 until entries.length()) {
                val e = entries.optJSONObject(i) ?: continue
                val vid = e.s("id")
                if (vid.isEmpty()) continue
                val title = e.s("title")
                if (title == "[Private video]" || title == "[Deleted video]") {
                    log("Ignorado (indisponível): $vid")
                    continue
                }
                val g = guessArtistTitle(title, e.s("channel").ifEmpty { e.s("uploader") })
                tracks.add(
                    TrackMeta(
                        title = g.title.ifEmpty { title },
                        artists = dedupe(listOfNotNull(g.artist.ifEmpty { null }) + g.featured),
                        duration = e.d("duration"),
                        origin = "youtube",
                        youtubeId = vid,
                        youtubeUrl = "https://www.youtube.com/watch?v=$vid",
                        thumbUrl = "https://i.ytimg.com/vi/$vid/mqdefault.jpg",
                        sourceUrl = url,
                    ),
                )
            }
        }
        return Resolved(tracks, name, "playlist")
    }

    private fun search(query: String, n: Int = 8): List<Candidate> {
        val info = dumpJson("ytsearch$n:$query", flat = true)
        val entries = info.optJSONArray("entries") ?: return emptyList()
        val out = mutableListOf<Candidate>()
        for (i in 0 until entries.length()) {
            val e = entries.optJSONObject(i) ?: continue
            val id = e.s("id")
            if (id.isEmpty()) continue
            val dur = e.d("duration")
            out.add(
                Candidate(
                    id = id,
                    url = "https://www.youtube.com/watch?v=$id",
                    title = e.s("title"),
                    channel = e.s("channel").ifEmpty { e.s("uploader") },
                    duration = if (dur > 0) dur else null,
                ),
            )
        }
        return out
    }

    /** Procura no YouTube a versão que melhor casa com título/artista/duração. */
    suspend fun findBestMatch(meta: TrackMeta, log: (String) -> Unit): Candidate? {
        ready.await()
        val base = "${meta.mainArtist} - ${meta.title}".trim(' ', '-')
        val queries = if (meta.origin == "spotify") listOf(base, "$base audio") else listOf(base)
        val found = LinkedHashMap<String, Candidate>()
        for (q in queries) {
            try {
                for (c in search(q)) found.putIfAbsent(c.id, c)
            } catch (e: Exception) {
                log("Busca falhou ('$q'): ${e.message}")
            }
        }
        val (best, score) = pickBest(meta, found.values.toList())
        if (best != null) log("Fonte: ${best.title} [${best.channel}] (pontos: ${score.toInt()})")
        return best
    }

    /**
     * Baixa o melhor áudio e converte para MP3 em [dir]. [onProgress] recebe (0..100, velocidade) e
     * [onConvert] é chamado quando começa a conversão.
     */
    suspend fun download(
        job: DlJob,
        url: String,
        dir: File,
        bitrate: String,
        onProgress: (Float) -> Unit,
        onConvert: () -> Unit,
    ): File {
        ready.await()
        dir.mkdirs()
        val req = YoutubeDLRequest(url)
        req.addOption("-f", "bestaudio/best")
        req.addOption("-x")
        req.addOption("--audio-format", "mp3")
        req.addOption("--audio-quality", "${bitrate}K")
        req.addOption("--no-playlist")
        req.addOption("--no-warnings")
        req.addOption("--no-mtime")
        req.addOption("-o", File(dir, "%(id)s.%(ext)s").absolutePath)
        try {
            YoutubeDL.getInstance().execute(
                request = req,
                processId = job.processId,
                callback = { progress, _, line ->
                    if (line.contains("[ExtractAudio]")) onConvert() else onProgress(progress)
                },
            )
        } catch (e: Exception) {
            if (job.cancelled.get()) throw CancelledByUser()
            throw e
        }
        if (job.cancelled.get()) throw CancelledByUser()
        val mp3 = dir.listFiles { f -> f.extension.equals("mp3", ignoreCase = true) }?.firstOrNull()
        return mp3 ?: throw IllegalStateException("O MP3 não foi gerado (falha na conversão com o ffmpeg).")
    }

    fun cancel(job: DlJob) {
        try {
            YoutubeDL.getInstance().destroyProcessById(job.processId)
        } catch (e: Exception) {
            // sem processo ativo
        }
    }
}
